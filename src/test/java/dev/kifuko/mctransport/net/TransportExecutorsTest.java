package dev.kifuko.mctransport.net;

import dev.kifuko.mctransport.McTransport;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportExecutorsTest {

    @Test
    void shutdownIsIdempotent() {
        TransportExecutors execs = new TransportExecutors("test");
        execs.shutdown();
        execs.shutdown();
        assertTrue(execs.isShutdown());
        assertTrue(execs.accept().isShutdown());
        assertTrue(execs.io().isShutdown());
    }

    @Test
    void acceptAndIoAreIndependent() {
        ExecutorService accept = Executors.newSingleThreadExecutor();
        ExecutorService io = Executors.newSingleThreadExecutor();
        TransportExecutors execs = new TransportExecutors(accept, io);
        assertSame(accept, execs.accept());
        assertSame(io, execs.io());
        assertFalse(execs.isShutdown());
        execs.shutdown();
        assertTrue(execs.isShutdown());
    }

    @Test
    void usesModIdInThreadName() throws Exception {
        TransportExecutors execs = new TransportExecutors("my-mod");
        String[] name = new String[1];
        execs.accept().submit(() -> name[0] = Thread.currentThread().getName()).get();
        assertTrue(name[0].startsWith("mctransport-my-mod-accept-"),
                "got: " + name[0]);
        execs.shutdown();
    }

    @Test
    void notShutdownByDefault() {
        TransportExecutors execs = new TransportExecutors("test");
        assertFalse(execs.isShutdown());
        execs.shutdown();
    }

    @Test
    void loggerConstantMatchesModId() {
        // Sanity: McTransport.MOD_ID matches the fabric mod id we register.
        assertTrue(McTransport.MOD_ID.equals("mctransport"));
    }
}