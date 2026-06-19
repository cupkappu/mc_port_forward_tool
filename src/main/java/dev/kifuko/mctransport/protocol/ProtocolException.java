package dev.kifuko.mctransport.protocol;

/**
 * Thrown when an incoming frame or stream of bytes violates the MC Transport
 * Dialer internal protocol contract.
 */
public class ProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}