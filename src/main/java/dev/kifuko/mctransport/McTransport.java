package dev.kifuko.mctransport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared constants and logger for the MC Transport Dialer mod.
 *
 * <p>The mod is dual-purpose: it exposes both a client entrypoint
 * ({@link dev.kifuko.mctransport.client.McTransportClient}) and a server
 * entrypoint ({@link dev.kifuko.mctransport.server.McTransportServer}).
 * Both sides share this {@link #MOD_ID} and {@link #LOGGER}.</p>
 */
public final class McTransport {

    /**
     * The mod id, matching {@code fabric.mod.json}.
     */
    public static final String MOD_ID = "mctransport";

    /**
     * Shared SLF4J logger used by all mod components.
     */
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private McTransport() {
        // No instances.
    }
}