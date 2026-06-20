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
 * One client-side transport stream (DIRECT mode): a local TCP socket bound
 * to a Minecraft channel stream ID. Implements {@link ClientStream}.
 */
public final class DirectClientStream implements ClientStream {

    private final ClientTunnelSession session;
    private final int streamId;
    private final BufferBudget budget;
    private final ReservationState reservations;
    private final int maxPayloadSize;

    private static final java.util.concurrent.Semaphore SEND_WINDOW =
            new java.util.concurrent.Semaphore(512);
    private static final java.util.concurrent.ScheduledExecutorService RELEASE_SCHEDULER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mctransport-send-window-release");
                t.setDaemon(true);
                return t;
            });

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Socket localSocket;
    private volatile Thread readThread;
    private byte[] readBuffer;

    public DirectClientStream(ClientTunnelSession session,
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
                budget.reserve(streamId, n, reservations);
                try {
                    SEND_WINDOW.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                sendData(buf, n);
                RELEASE_SCHEDULER.schedule(() -> SEND_WINDOW.release(),
                        2, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (IOException e) {
            sendReset();
        } finally {
            closeSocketAndRelease();
        }
    }

    @Override
    public boolean reserveOrWait(int bytes) {
        // DATA path uses SEND_WINDOW semaphore instead.
        // Kept for test compatibility.
        try {
            budget.reserve(streamId, bytes, reservations);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    @Override
    public void releaseBudget(int bytes) {
        if (bytes > 0) {
            budget.release(streamId, bytes, reservations);
        }
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
            dev.kifuko.mctransport.McTransport.LOGGER.debug(
                    "client stream {} ignoring {} frame: stream closed", streamId, frame.type());
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
            dev.kifuko.mctransport.McTransport.LOGGER.debug(
                    "client stream {} writeToLocal failed: socket {}",
                    streamId, sock == null ? "null" : "closed");
            closeReset();
            return;
        }
        try {
            OutputStream out = sock.getOutputStream();
            out.write(frame.payload());
            out.flush();
            budget.release(streamId, frame.payloadLength(), reservations);
            dev.kifuko.mctransport.McTransport.LOGGER.debug(
                    "client stream {} wrote {} bytes to local socket",
                    streamId, frame.payloadLength());
        } catch (IOException e) {
            dev.kifuko.mctransport.McTransport.LOGGER.debug(
                    "client stream {} writeToLocal IO error: {}", streamId, e.getMessage());
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
