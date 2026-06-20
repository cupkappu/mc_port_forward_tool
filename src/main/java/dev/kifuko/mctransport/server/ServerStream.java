package dev.kifuko.mctransport.server;

import dev.kifuko.mctransport.protocol.Frame;

import java.io.IOException;

/**
 * Common interface for server-side transport streams.
 * Both {@link DirectServerStream} and {@link KcpServerStream} implement this.
 */
public interface ServerStream {

    int streamId();

    boolean isClosed();

    /** Handles an inbound DATA / CLOSE / RESET frame from the peer. */
    void onFrame(Frame frame);

    /** Clean shutdown: send CLOSE, close socket, release buffers. */
    void closeClean();

    /** Reset shutdown: send RESET, close socket, release buffers. */
    void closeReset();

    /** Close initiated by peer (no outbound frame sent). */
    void closeFromPeer();

    /**
     * Reads one chunk from the target socket.
     * @return number of bytes read, 0 on no data, -1 on EOF or error
     */
    int readTargetChunk(byte[] buffer) throws IOException;

    /** Builds a DATA frame from bytes read off the target socket. */
    Frame buildDataFrame(byte[] chunk, int length);

    /** Sends target-side bytes as a DATA frame (direct mode) or feeds KCP (KCP mode). */
    void sendTargetBytes(byte[] chunk, int length);

    /** Releases reserved buffer budget for delivered bytes. */
    void releaseBudget(int bytes);

    /** Tries to reserve budget; returns false if exhausted. */
    boolean reserveOrWait(int bytes);

    /** Sends a CLOSE frame. */
    void sendClose();
}
