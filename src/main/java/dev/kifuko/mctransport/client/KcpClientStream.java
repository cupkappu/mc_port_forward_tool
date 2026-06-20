package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.kcp.KcpConfig;
import dev.kifuko.mctransport.kcp.KcpCore;
import dev.kifuko.mctransport.kcp.KcpOutput;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * KCP-backed client stream. Feeds local TCP socket reads into KCP for
 * stream-mode coalescing; sends coalesced segments as DATA frames.
 * Receives DATA frames from the peer and reassembles via KCP before
 * writing to the local socket.
 */
public final class KcpClientStream implements ClientStream {

    private static final Logger LOG = Logger.getLogger(KcpClientStream.class.getName());

    private final ClientTunnelSession session;
    private final int streamId;
    private final BufferBudget budget;
    private final ReservationState reservations;
    private final KcpCore kcp;
    private final KcpConfig kcpConfig;
    private final int maxKcpFramePayloadSize;
    private final Object kcpLock = new Object();
    private final ArrayDeque<Frame> pendingKcpFrames = new ArrayDeque<>();
    private boolean flushingKcpFrames;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Socket localSocket;
    private volatile Thread readThread;
    private byte[] readBuffer;

    public KcpClientStream(ClientTunnelSession session,
                           int streamId,
                           BufferBudget budget,
                           ReservationState reservations,
                           KcpConfig kcpConfig) {
        this.session = session;
        this.streamId = streamId;
        this.budget = budget;
        this.reservations = reservations;
        this.kcpConfig = kcpConfig;
        this.maxKcpFramePayloadSize = kcpConfig.mss() + KcpCore.IKCP_OVERHEAD;
        this.kcp = new KcpCore(streamId, new BridgeOutput());
        this.kcp.applyConfig(kcpConfig);
    }

    @Override
    public int streamId() { return streamId; }

    @Override
    public int maxPayload() { return kcpConfig.mss(); }

    @Override
    public boolean isClosed() { return closed.get(); }

    @Override
    public Socket socket() { return localSocket; }

    @Override
    public synchronized void attach(Socket socket, byte[] readBuffer) {
        if (closed.get()) {
            try { socket.close(); } catch (IOException ignored) {}
            return;
        }
        if (this.localSocket != null) {
            throw new IllegalStateException("stream already attached");
        }
        if (readBuffer == null || readBuffer.length == 0) {
            throw new IllegalArgumentException("readBuffer must be non-empty");
        }
        this.localSocket = socket;
        this.readBuffer = readBuffer;
        Thread t = new Thread(this::drainLoop, "mctransport-kcp-read-" + streamId);
        t.setDaemon(true);
        this.readThread = t;
        t.start();
    }

    private void drainLoop() {
        Socket sock = localSocket;
        if (sock == null) return;
        byte[] buf = this.readBuffer;
        try {
            InputStream in = sock.getInputStream();
            while (!closed.get()) {
                // Backpressure: pause reading when KCP send queue is too full
                if (updateForBackpressure()) {
                    Thread.sleep(1);
                    continue;
                }
                int n = in.read(buf);
                if (n < 0) { sendClose(); break; }
                if (n == 0) continue;
                budget.reserve(streamId, n, reservations);
                synchronized (kcpLock) {
                    kcp.send(ByteBuffer.wrap(buf, 0, n));
                    kcp.update(System.currentTimeMillis());
                }
                flushPendingKcpFrames();
            }
        } catch (IOException e) {
            sendReset();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeSocketAndRelease();
        }
    }

    @Override
    public void onFrame(Frame frame) {
        if (closed.get()) {
            LOG.fine(() -> "client kcp stream " + streamId + " ignoring " + frame.type() + ": stream closed");
            return;
        }
        switch (frame.type()) {
            case DATA -> handleData(frame);
            case CLOSE, RESET, ERROR -> closeFromPeer();
            default -> { /* PING/PONG/CONFIG ignored at stream level */ }
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

        Socket sock = localSocket;
        for (byte[] bytes : received) {
            if (sock == null || sock.isClosed()) {
                closeReset();
                return;
            }
            try {
                OutputStream out = sock.getOutputStream();
                out.write(bytes);
                out.flush();
                budget.release(streamId, bytes.length, reservations);
            } catch (IOException e) {
                closeReset();
                return;
            }
        }
    }

    private void sendDataFrame(byte[] data, int len) {
        byte[] body = new byte[len];
        System.arraycopy(data, 0, body, 0, len);
        Frame f = Frame.create(ClientTunnelSession.PROTOCOL_VERSION,
                ClientTunnelSession.SESSION_ID, streamId, FrameType.DATA,
                (byte) 0, body, maxKcpFramePayloadSize);
        enqueueKcpFrame(f);
    }

    private boolean updateForBackpressure() {
        boolean paused;
        synchronized (kcpLock) {
            paused = kcp.waitSnd() > kcpConfig.sndWnd() * 2;
            if (paused) {
                kcp.update(System.currentTimeMillis());
            }
        }
        flushPendingKcpFrames();
        return paused;
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

    private void sendClose() {
        if (!closed.compareAndSet(false, true)) return;
        Frame f = Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                ClientTunnelSession.SESSION_ID, streamId, FrameType.CLOSE,
                (byte) 0, new byte[0]);
        session.bridge().send(f);
        cleanup();
    }

    private void sendReset() {
        if (!closed.compareAndSet(false, true)) return;
        Frame f = Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                ClientTunnelSession.SESSION_ID, streamId, FrameType.RESET,
                (byte) 0, new byte[0]);
        session.bridge().send(f);
        cleanup();
    }

    private void closeFromPeer() {
        if (!closed.compareAndSet(false, true)) return;
        cleanup();
    }

    @Override
    public void closeClean() {
        if (!closed.compareAndSet(false, true)) return;
        Frame f = Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                ClientTunnelSession.SESSION_ID, streamId, FrameType.CLOSE,
                (byte) 0, new byte[0]);
        session.bridge().send(f);
        cleanup();
    }

    @Override
    public void closeReset() {
        if (!closed.compareAndSet(false, true)) return;
        Frame f = Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                ClientTunnelSession.SESSION_ID, streamId, FrameType.RESET,
                (byte) 0, new byte[0]);
        session.bridge().send(f);
        cleanup();
    }

    @Override
    public synchronized void closeSocketAndRelease() {
        if (!closed.compareAndSet(false, true)) return;
        cleanup();
    }

    private void cleanup() {
        synchronized (kcpLock) {
            kcp.release();
            pendingKcpFrames.clear();
        }
        budget.releaseAll(streamId, reservations);
        readBuffer = null;
        Socket sock = localSocket;
        localSocket = null;
        if (sock != null) {
            try { sock.close(); } catch (IOException ignored) {}
        }
        Thread t = readThread;
        if (t != null) t.interrupt();
        session.closeLocalStream(streamId);
    }

    @Override
    public void releaseBudget(int bytes) {
        if (bytes > 0) {
            budget.release(streamId, bytes, reservations);
        }
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

    /** KcpOutput that bridges KCP segments into DATA frames. */
    private class BridgeOutput implements KcpOutput {
        @Override
        public void out(ByteBuffer data, KcpCore kcp) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            sendDataFrame(bytes, bytes.length);
        }
    }
}
