package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.buffer.BufferBudget;
import dev.kifuko.mctransport.buffer.ReservationState;
import dev.kifuko.mctransport.net.TunnelBridge;
import dev.kifuko.mctransport.protocol.Frame;
import dev.kifuko.mctransport.protocol.FrameType;
import dev.kifuko.mctransport.protocol.ProtocolException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One server-side transport stream: a Minecraft frame channel endpoint
 * multiplexed with the fixed target TCP socket.
 *
 * <p>Reads from the target socket are turned into outbound {@code DATA}
 * frames; inbound {@code DATA} frames are written to the target socket.
 * {@code CLOSE} and {@code RESET} tear down both sides of the stream.</p>
 */
public final class ServerStream {

    private final PlayerTunnelSession session;
    private final int streamId;
    private final Socket targetSocket;
    private final BufferBudget budget;
    private final ReservationState reservations;

    private static final java.util.concurrent.Semaphore SEND_WINDOW =
            new java.util.concurrent.Semaphore(512);
    private static final java.util.concurrent.ScheduledExecutorService RELEASE_SCHEDULER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mctransport-send-window-release");
                t.setDaemon(true);
                return t;
            });

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final byte protocolVersion;
    private final int maxPayloadSize;

    public ServerStream(PlayerTunnelSession session,
                        int streamId,
                        Socket targetSocket,
                        BufferBudget budget,
                        ReservationState reservations,
                        byte protocolVersion,
                        int maxPayloadSize) {
        this.session = session;
        this.streamId = streamId;
        this.targetSocket = targetSocket;
        this.budget = budget;
        this.reservations = reservations;
        this.protocolVersion = protocolVersion;
        this.maxPayloadSize = maxPayloadSize;
    }

    public int streamId() {
        return streamId;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** Handles inbound frames from the Minecraft channel. */
    public void onFrame(Frame frame) {
        if (closed.get()) {
            return;
        }
        switch (frame.type()) {
            case DATA -> writeToTarget(frame);
            case CLOSE, RESET, ERROR -> closeFromPeer();
            default -> throw new ProtocolException(
                    "unexpected frame type for server stream: " + frame.type());
        }
    }

    private void writeToTarget(Frame frame) {
        if (frame.payloadLength() == 0) {
            return;
        }
        try {
            OutputStream out = targetSocket.getOutputStream();
            out.write(frame.payload());
            out.flush();
            budget.release(streamId, frame.payloadLength(), reservations);
        } catch (IOException e) {
            closeReset();
        }
    }

    /**
     * Reads one chunk from the target socket. Returns the number of bytes
     * read, 0 on EOF, or -1 on error. Callers are responsible for sending
     * the resulting bytes back as a {@code DATA} frame.
     */
    public int readTargetChunk(byte[] buffer) throws IOException {
        if (closed.get()) {
            return -1;
        }
        if (buffer == null || buffer.length == 0) {
            throw new IllegalArgumentException("buffer must be non-empty");
        }
        InputStream in = targetSocket.getInputStream();
        return in.read(buffer);
    }

    /** Builds a {@code DATA} frame from bytes read off the target socket. */
    public Frame buildDataFrame(byte[] chunk, int length) {
        byte[] body = new byte[length];
        System.arraycopy(chunk, 0, body, 0, length);
        return Frame.create(protocolVersion, 0, streamId,
                FrameType.DATA, (byte) 0, body, maxPayloadSize);
    }

    /**
     * Sends a {@code DATA} frame built from target socket bytes. Uses a
     * global semaphore (512 permits, 2 ms release delay) to bound in-flight
     * frames at ~2 MB memory. No budget blocking — the send window is wide
     * enough that the bottleneck is the Minecraft channel, not us.
     */
    public void sendTargetBytes(byte[] chunk, int length) {
        if (closed.get() || length <= 0) {
            return;
        }
        try {
            budget.reserve(streamId, length, reservations);
        } catch (IllegalStateException budgetFull) {
            // Semaphore backpressure is the real limiter; budget is accounting-only.
        }
        try {
            SEND_WINDOW.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        Frame f = Frame.create(protocolVersion, 0, streamId,
                FrameType.DATA, (byte) 0,
                java.util.Arrays.copyOf(chunk, length), maxPayloadSize);
        session.bridge().send(f);
        RELEASE_SCHEDULER.schedule(() -> SEND_WINDOW.release(),
                2, java.util.concurrent.TimeUnit.MILLISECONDS);
        dev.kifuko.mctransport.McTransport.LOGGER.debug(
                "server stream {} sent DATA frame {}B (window permits={})",
                streamId, length, SEND_WINDOW.availablePermits());
    }

    boolean reserveOrWait(int bytes) {
        // Kept for test compatibility; DATA path uses SEND_WINDOW instead.
        try {
            budget.reserve(streamId, bytes, reservations);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** Releases reserved bytes when the receiver confirms delivery. */
    public void releaseBudget(int bytes) {
        if (bytes <= 0) {
            return;
        }
        budget.release(streamId, bytes, reservations);
    }

    /** Sends a CLOSE frame (normal end of stream). */
    public void sendClose() {
        if (closed.get()) {
            return;
        }
        Frame f = Frame.createTrusted(protocolVersion, 0, streamId,
                FrameType.CLOSE, (byte) 0, new byte[0]);
        session.bridge().send(f);
    }

    /** Sends a RESET frame (exceptional end of stream). */
    public void sendReset() {
        if (closed.get()) {
            return;
        }
        Frame f = Frame.createTrusted(protocolVersion, 0, streamId,
                FrameType.RESET, (byte) 0, new byte[0]);
        session.bridge().send(f);
    }

    /** Clean shutdown: send CLOSE, close socket, release buffers, unregister. */
    public void closeClean() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Frame f = Frame.createTrusted(protocolVersion, 0, streamId,
                FrameType.CLOSE, (byte) 0, new byte[0]);
        session.bridge().send(f);
        closeResources();
    }

    /** Reset shutdown: send RESET, close socket, release buffers, unregister. */
    public void closeReset() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Frame f = Frame.createTrusted(protocolVersion, 0, streamId,
                FrameType.RESET, (byte) 0, new byte[0]);
        session.bridge().send(f);
        closeResources();
    }

    public void closeFromPeer() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeResources();
    }

    private void closeResources() {
        budget.releaseAll(streamId, reservations);
        try {
            targetSocket.close();
        } catch (IOException ignored) {
            // best effort
        }
        session.registry().remove(streamId);
    }
}
