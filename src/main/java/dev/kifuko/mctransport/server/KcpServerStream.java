package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.kcp.KcpConfig;
import dev.kifuko.mctransport.kcp.KcpCore;
import dev.kifuko.mctransport.kcp.KcpOutput;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameType;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * KCP-backed server stream. Receives DATA frames, feeds into KCP for
 * reassembly, writes merged data to the target socket. Reads from target
 * socket are fed into KCP for coalescing before sending as DATA frames.
 */
public final class KcpServerStream implements ServerStream {

    private static final Logger LOG = Logger.getLogger(KcpServerStream.class.getName());

    private final PlayerTunnelSession session;
    private final int streamId;
    private final Socket targetSocket;
    private final BufferBudget budget;
    private final ReservationState reservations;
    private final KcpCore kcp;
    private final KcpConfig kcpConfig;
    private final int maxKcpFramePayloadSize;
    private final Object kcpLock = new Object();
    private final ArrayDeque<Frame> pendingKcpFrames = new ArrayDeque<>();
    private boolean flushingKcpFrames;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final byte protocolVersion;

    public KcpServerStream(PlayerTunnelSession session,
                           int streamId,
                           Socket targetSocket,
                           BufferBudget budget,
                           ReservationState reservations,
                           KcpConfig kcpConfig,
                           byte protocolVersion) {
        this.session = session;
        this.streamId = streamId;
        this.targetSocket = targetSocket;
        this.budget = budget;
        this.reservations = reservations;
        this.kcpConfig = kcpConfig;
        this.maxKcpFramePayloadSize = kcpConfig.mss() + KcpCore.IKCP_OVERHEAD;
        this.protocolVersion = protocolVersion;
        this.kcp = new KcpCore(streamId, new BridgeOutput());
        this.kcp.applyConfig(kcpConfig);
    }

    @Override public int streamId() { return streamId; }
    @Override public boolean isClosed() { return closed.get(); }

    @Override
    public void onFrame(Frame frame) {
        if (closed.get()) return;
        switch (frame.type()) {
            case DATA -> handleData(frame);
            case CLOSE, RESET, ERROR -> closeFromPeer();
            default -> { /* PING etc. ignored at stream level */ }
        }
    }

    private void handleData(Frame frame) {
        if (frame.payloadLength() == 0) return;
        long now = System.currentTimeMillis();
        List<byte[]> received = new ArrayList<>();
        synchronized (kcpLock) {
            kcp.input(ByteBuffer.wrap(frame.payload()), true, now);
            kcp.update(now);

            ByteBuffer merged;
            while ((merged = kcp.recv()) != null) {
                byte[] bytes = new byte[merged.remaining()];
                merged.get(bytes);
                received.add(bytes);
            }
        }
        flushPendingKcpFrames();

        try {
            for (byte[] bytes : received) {
                targetSocket.getOutputStream().write(bytes);
                targetSocket.getOutputStream().flush();
                budget.release(streamId, bytes.length, reservations);
            }
        } catch (IOException e) {
            closeReset();
        }
    }

    @Override
    public int readTargetChunk(byte[] buffer) throws IOException {
        if (closed.get()) return -1;
        return targetSocket.getInputStream().read(buffer);
    }

    @Override
    public Frame buildDataFrame(byte[] chunk, int length) {
        throw new UnsupportedOperationException("KCP mode uses internal frame building");
    }

    @Override
    public void sendTargetBytes(byte[] chunk, int length) {
        if (closed.get() || length <= 0) return;
        long now = System.currentTimeMillis();
        synchronized (kcpLock) {
            kcp.send(ByteBuffer.wrap(chunk, 0, length));
            kcp.update(now);
        }
        flushPendingKcpFrames();
    }

    @Override
    public void releaseBudget(int bytes) {
        if (bytes > 0) budget.release(streamId, bytes, reservations);
    }

    @Override
    public boolean reserveOrWait(int bytes) {
        try {
            budget.reserve(streamId, bytes, reservations);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    @Override
    public void sendClose() {
        if (closed.get()) return;
        sendFrame(FrameType.CLOSE);
    }

    @Override
    public void closeClean() {
        if (!closed.compareAndSet(false, true)) return;
        sendFrame(FrameType.CLOSE);
        closeResources();
    }

    @Override
    public void closeReset() {
        if (!closed.compareAndSet(false, true)) return;
        sendFrame(FrameType.RESET);
        closeResources();
    }

    @Override
    public void closeFromPeer() {
        if (!closed.compareAndSet(false, true)) return;
        closeResources();
    }

    private void sendFrame(FrameType type) {
        Frame f = Frame.createTrusted(protocolVersion, 0, streamId, type, (byte) 0, new byte[0]);
        session.bridge().send(f);
    }

    private void closeResources() {
        synchronized (kcpLock) {
            kcp.release();
            pendingKcpFrames.clear();
        }
        budget.releaseAll(streamId, reservations);
        try { targetSocket.close(); } catch (IOException ignored) {}
        session.registry().remove(streamId);
    }

    private void enqueueKcpFrame(Frame frame) {
        synchronized (kcpLock) {
            pendingKcpFrames.add(frame);
        }
    }

    private void flushPendingKcpFrames() {
        synchronized (kcpLock) {
            if (flushingKcpFrames) {
                return;
            }
            flushingKcpFrames = true;
        }
        try {
            while (true) {
                Frame frame;
                synchronized (kcpLock) {
                    frame = pendingKcpFrames.poll();
                    if (frame == null) {
                        flushingKcpFrames = false;
                        return;
                    }
                }
                session.bridge().send(frame);
            }
        } catch (RuntimeException e) {
            synchronized (kcpLock) {
                flushingKcpFrames = false;
            }
            throw e;
        }
    }

    private class BridgeOutput implements KcpOutput {
        @Override
        public void out(ByteBuffer data, KcpCore kcp) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            Frame f = Frame.create(protocolVersion, 0, streamId,
                    FrameType.DATA, (byte) 0, bytes, maxKcpFramePayloadSize);
            enqueueKcpFrame(f);
        }
    }
}
