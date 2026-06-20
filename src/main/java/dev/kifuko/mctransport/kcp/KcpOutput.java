package dev.kifuko.mctransport.kcp;

import java.nio.ByteBuffer;

/**
 * Callback invoked by {@code KcpCore} when it has a segment ready to send.
 * The buffer's position..limit contains the segment bytes.
 */
@FunctionalInterface
public interface KcpOutput {
    /**
     * @param data  segment bytes; position=0, limit=segment length.
     *              Callee must NOT retain the reference after returning.
     */
    void out(ByteBuffer data);
}
