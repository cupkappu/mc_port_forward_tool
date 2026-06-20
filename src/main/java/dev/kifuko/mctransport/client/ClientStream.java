package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One client-side transport stream: a local TCP socket bound to a Minecraft
 * channel stream ID.
 */
public final class ClientStream {

    private final ClientTunnelSession session;
    private final int streamId;
    private final BufferBudget budget;
    private final ReservationState reservations;
    private final int maxPayloadSize;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Socket localSocket;
    private volatile Thread readThread;
    private byte[] readBuffer;

    public ClientStream(ClientTunnelSession session,
                        int streamId,
                        BufferBudget budget,
                        ReservationState reservations,
                        int maxPayloadSize) {
        this.session = session;
        this.streamId = streamId;
        this.budget = budget;
        this.reservations = reservations;
        this.maxPayloadSize = maxPayloadSize;
    }

    public int streamId() {
        return streamId;
    }

    /** Returns the max DATA frame payload size in bytes. */
    public int maxPayload() {
        return maxPayloadSize;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public Socket socket() {
        return localSocket;
    }

    /**
     * Attaches the local TCP socket and spawns a single read thread that
     * converts socket bytes into outbound {@code DATA} frames.
     */
    public synchronized void attach(Socket socket, byte[] readBuffer) {
        if (closed.get()) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            return;
        }
        if (socket == null) {
            throw new IllegalArgumentException("socket must not be null");
        }
        if (this.localSocket != null) {
            throw new IllegalStateException("stream already attached");
        }
        if (readBuffer == null || readBuffer.length == 0) {
            throw new IllegalArgumentException("readBuffer must be non-empty");
        }
        this.localSocket = socket;
        this.readBuffer = readBuffer;
        Thread t = new Thread(this::drainLocalReads, "mctransport-client-read-" + streamId);
        t.setDaemon(true);
        this.readThread = t;
        t.start();
    }

    private void drainLocalReads() {
        Socket sock = localSocket;
        if (sock == null) {
            return;
        }
        byte[] buf = this.readBuffer;
        try {
            InputStream in = sock.getInputStream();
            while (!closed.get()) {
                int n = in.read(buf);
                if (n < 0) {
                    sendClose();
                    break;
                }
                if (n == 0) {
                    continue;
                }
                if (!reserveOrWait(n)) {
                    break;
                }
                sendData(buf, n);
            }
        } catch (IOException e) {
            sendReset();
        } finally {
            closeSocketAndRelease();
        }
    }

    static long DRAIN_INTERVAL_MS = 150L;

    boolean reserveOrWait(int bytes) {
        long nextDrain = System.currentTimeMillis() + DRAIN_INTERVAL_MS;
        while (!closed.get()) {
            try {
                budget.reserve(streamId, bytes, reservations);
                return true;
            } catch (IllegalStateException e) {
                if (System.currentTimeMillis() > nextDrain) {
                    long reserved = reservations.reservedFor(streamId);
                    if (reserved > 0) {
                        budget.release(streamId, (int) Math.max(reserved / 4, 1),
                                reservations);
                    }
                    nextDrain = System.currentTimeMillis() + DRAIN_INTERVAL_MS;
                    continue;
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private void sendData(byte[] src, int length) {
        byte[] body = new byte[length];
        System.arraycopy(src, 0, body, 0, length);
        Frame f = Frame.create(ClientTunnelSession.PROTOCOL_VERSION,
                ClientTunnelSession.SESSION_ID, streamId, FrameType.DATA,
                (byte) 0, body, maxPayloadSize);
        session.bridge().send(f);
    }

    private void sendClose() {
        if (closed.get()) {
            return;
        }
        Frame f = Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                ClientTunnelSession.SESSION_ID, streamId, FrameType.CLOSE,
                (byte) 0, new byte[0]);
        session.bridge().send(f);
    }

    private void sendReset() {
        if (closed.get()) {
            return;
        }
        Frame f = Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                ClientTunnelSession.SESSION_ID, streamId, FrameType.RESET,
                (byte) 0, new byte[0]);
        session.bridge().send(f);
    }

    /** Handles inbound frames from the Minecraft channel. */
    public void onFrame(Frame frame) {
        if (closed.get()) {
            return;
        }
        switch (frame.type()) {
            case DATA -> writeToLocal(frame);
            case CLOSE, RESET, ERROR -> closeFromPeer();
            default -> {
                // Other frame types are ignored at the per-stream level.
            }
        }
    }

    private void writeToLocal(Frame frame) {
        Socket sock = localSocket;
        if (sock == null || sock.isClosed()) {
            closeReset();
            return;
        }
        try {
            OutputStream out = sock.getOutputStream();
            out.write(frame.payload());
            out.flush();
            budget.release(streamId, frame.payloadLength(), reservations);
        } catch (IOException e) {
            closeReset();
        }
    }

    /** Idempotent full close. */
    public void closeClean() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Frame f = Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                ClientTunnelSession.SESSION_ID, streamId, FrameType.CLOSE,
                (byte) 0, new byte[0]);
        session.bridge().send(f);
        cleanup();
    }

    public void closeReset() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Frame f = Frame.createTrusted(ClientTunnelSession.PROTOCOL_VERSION,
                ClientTunnelSession.SESSION_ID, streamId, FrameType.RESET,
                (byte) 0, new byte[0]);
        session.bridge().send(f);
        cleanup();
    }

    private void closeFromPeer() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cleanup();
    }

    /** Close the local socket, release budget, and detach from the session. */
    public synchronized void closeSocketAndRelease() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cleanup();
    }

    private void cleanup() {
        budget.releaseAll(streamId, reservations);
        readBuffer = null;
        Socket sock = localSocket;
        localSocket = null;
        if (sock != null) {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
        Thread t = readThread;
        if (t != null) {
            t.interrupt();
        }
        session.closeLocalStream(streamId);
    }
}
