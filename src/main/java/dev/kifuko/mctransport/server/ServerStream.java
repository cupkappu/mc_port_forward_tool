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
     * Sends a {@code DATA} frame built from target socket bytes. Reserves
     * buffer budget first; on reservation failure the current chunk is kept
     * and retried, which pauses the target reader without losing bytes.
     */
    public void sendTargetBytes(byte[] chunk, int length) {
        if (closed.get() || length <= 0) {
            return;
        }
        if (!reserveOrWait(length)) {
            return;
        }
        Frame f = Frame.create(protocolVersion, 0, streamId,
                FrameType.DATA, (byte) 0,
                java.util.Arrays.copyOf(chunk, length), maxPayloadSize);
        session.bridge().send(f);
    }

    private boolean reserveOrWait(int bytes) {
        while (!closed.get()) {
            try {
                budget.reserve(streamId, bytes, reservations);
                return true;
            } catch (IllegalStateException e) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
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
