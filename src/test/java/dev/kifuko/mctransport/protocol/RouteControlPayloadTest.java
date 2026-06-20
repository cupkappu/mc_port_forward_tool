package dev.kifuko.mctransport.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteControlPayloadTest {

    @Test
    void applyPayloadRoundTrip() {
        byte[] encoded = RouteControlPayload.encodeApply("127.0.0.1", 25580);
        RouteControlPayload.Apply decoded = RouteControlPayload.decodeApply(encoded);
        assertEquals("127.0.0.1", decoded.listenHost());
        assertEquals(25580, decoded.listenPort());
    }

    @Test
    void applyPayloadAcceptsLocalhost() {
        byte[] encoded = RouteControlPayload.encodeApply("localhost", 25580);
        RouteControlPayload.Apply decoded = RouteControlPayload.decodeApply(encoded);
        assertEquals("localhost", decoded.listenHost());
    }

    @Test
    void ackSuccessRoundTrip() {
        byte[] encoded = RouteControlPayload.encodeAck(true, "listening on 127.0.0.1:25580");
        RouteControlPayload.Ack decoded = RouteControlPayload.decodeAck(encoded);
        assertTrue(decoded.ok());
        assertEquals("listening on 127.0.0.1:25580", decoded.message());
    }

    @Test
    void ackFailureRoundTrip() {
        byte[] encoded = RouteControlPayload.encodeAck(false, "failed to bind 127.0.0.1:25580");
        RouteControlPayload.Ack decoded = RouteControlPayload.decodeAck(encoded);
        assertFalse(decoded.ok());
        assertEquals("failed to bind 127.0.0.1:25580", decoded.message());
    }

    @Test
    void rejectsNonLoopbackListenHost() {
        byte[] encoded = RouteControlPayload.encodeApply("0.0.0.0", 25580);
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> RouteControlPayload.decodeApply(encoded));
        assertTrue(ex.getMessage().contains("loopback"));
    }

    @Test
    void rejectsInvalidPort() {
        assertThrows(IllegalArgumentException.class,
                () -> RouteControlPayload.encodeApply("127.0.0.1", 0));
        assertThrows(IllegalArgumentException.class,
                () -> RouteControlPayload.encodeApply("127.0.0.1", 70000));
        byte[] good = RouteControlPayload.encodeApply("127.0.0.1", 25580);
        String body = new String(good, java.nio.charset.StandardCharsets.UTF_8);
        byte[] tampered = body.replace("25580", "99999")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> RouteControlPayload.decodeApply(tampered));
        assertTrue(ex.getMessage().contains("listenPort"));
    }
}