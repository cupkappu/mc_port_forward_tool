package dev.kifuko.mctransport.client;

import java.io.IOException;

/**
 * Runtime controller for the local TCP listener. The client creates no
 * listener until the server sends a route config.
 */
public interface ClientListenerController {

    void apply(String listenHost, int listenPort) throws IOException;

    void clear();

    boolean isListening();
}
