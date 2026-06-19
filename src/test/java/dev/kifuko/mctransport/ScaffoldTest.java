package dev.kifuko.mctransport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Smoke test asserting the scaffold compiles and exposes the expected types.
 *
 * <p>Runtime Fabric / SLF4J-backend contracts are out of scope here: this
 * test only checks compile-time type exposure so it can run inside the
 * JUnit 5 harness added in Task 1 without pulling a Minecraft runtime.</p>
 */
class ScaffoldTest {

    @Test
    void modIdMatchesFabricModJson() {
        assertEquals("mctransport", McTransport.MOD_ID);
    }

    @Test
    void loggerFieldIsAvailable() {
        assertNotNull(McTransport.LOGGER);
    }
}