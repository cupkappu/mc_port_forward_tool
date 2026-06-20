package dev.kifuko.mctransport.client;

import dev.kifuko.mctransport.protocol.Frame;

import java.net.Socket;

/**
 * Common interface for client-side transport streams.
 * Both {@link DirectClientStream} and {@link KcpClientStream} implement this.
 */
public interface ClientStream {

    /** The stream identifier within the tunnel session. */
    int streamId();

    /** Maximum DATA frame payload size in bytes. */
    int maxPayload();

    /** Whether this stream has been closed. */
    boolean isClosed();

    /** The local TCP socket, or null if not yet attached. */
    Socket socket();

    /**
     * Attaches a local TCP socket and begins reading. Implementations
     * spawn a background read thread that converts socket bytes into
     * outbound DATA frames (direct mode) or feeds KCP (KCP mode).
     */
    void attach(Socket socket, byte[] readBuffer);

    /** Handles an inbound DATA / CLOSE / RESET frame from the peer. */
    void onFrame(Frame frame);

    /** Idempotent full close: send CLOSE/RESET, close socket, release buffers. */
    void closeSocketAndRelease();

    /** Sends RESET frame and cleans up (used on socket error before attachment). */
    void closeReset();

    /** Sends CLOSE frame and cleans up normally. */
    void closeClean();

    /** Releases reserved buffer budget for {@code bytes}. */
    void releaseBudget(int bytes);

    /** Tries to reserve budget; returns false if budget is exhausted. */
    boolean reserveOrWait(int bytes);
}
