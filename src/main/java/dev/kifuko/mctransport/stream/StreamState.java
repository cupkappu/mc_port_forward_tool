package dev.kifuko.mctransport.stream;

/**
 * Lifecycle states for a transport stream on either side of the tunnel.
 */
public enum StreamState {
    /** Stream ID is allocated but no peer has been notified yet. */
    ALLOCATED,
    /** {@code OPEN} has been sent; awaiting peer acknowledgement on the server side. */
    OPEN_SENT,
    /** Server has accepted the OPEN and a target socket is connected. */
    OPEN,
    /** Either side has issued CLOSE; still draining. */
    HALF_CLOSED,
    /** Stream is fully closed. */
    CLOSED
}