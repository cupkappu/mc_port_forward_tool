package dev.kifuko.mctransport.protocol;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiny JSON-shaped codec for the server-pushed route control frames.
 *
 * <p>Payloads are simple ASCII strings — no escapes, no nested objects —
 * so a regex parser is sufficient. Pulling in a JSON dependency for the
 * MVP would be overkill.</p>
 */
public final class RouteControlPayload {

    private RouteControlPayload() {
    }

    /** Encodes the {@code CONFIG_APPLY} payload. */
    public static byte[] encodeApply(String listenHost, int listenPort) {
        return encodeApply(listenHost, listenPort, StreamMode.DIRECT);
    }

    /** Encodes the {@code CONFIG_APPLY} payload with stream mode. */
    public static byte[] encodeApply(String listenHost, int listenPort, StreamMode mode) {
        if (listenHost == null || listenHost.isEmpty()) {
            throw new IllegalArgumentException("listenHost must not be blank");
        }
        if (listenPort < 1 || listenPort > 65535) {
            throw new IllegalArgumentException(
                    "listenPort must be in 1..65535, got: " + listenPort);
        }
        if (mode == null) mode = StreamMode.DIRECT;
        return ("{\"listenHost\":\"" + listenHost
                + "\",\"listenPort\":" + listenPort
                + ",\"streamMode\":\"" + mode.name() + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    public static Apply decodeApply(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new ProtocolException("CONFIG_APPLY payload is empty");
        }
        String body = new String(payload, StandardCharsets.UTF_8);
        String host = extractString(body, "listenHost");
        int port = extractInt(body, "listenPort");
        if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) {
            throw new ProtocolException(
                    "CONFIG_APPLY listen host must be loopback, got: " + host);
        }
        if (port < 1 || port > 65535) {
            throw new ProtocolException(
                    "CONFIG_APPLY listenPort out of range: " + port);
        }
        String modeStr;
        try {
            modeStr = extractString(body, "streamMode");
        } catch (ProtocolException e) {
            modeStr = null; // backward compat: payloads before v0.2.3
        }
        StreamMode mode = modeStr != null ? StreamMode.fromString(modeStr) : StreamMode.DIRECT;
        return new Apply(host, port, mode);
    }

    public static byte[] encodeAck(boolean ok, String message) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        String safe = message.replace("\\", "\\\\").replace("\"", "\\\"");
        return ("{\"ok\":" + (ok ? "true" : "false")
                + ",\"message\":\"" + safe + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    public static Ack decodeAck(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new ProtocolException("CONFIG_ACK payload is empty");
        }
        String body = new String(payload, StandardCharsets.UTF_8);
        boolean ok;
        if (body.contains("\"ok\":true")) {
            ok = true;
        } else if (body.contains("\"ok\":false")) {
            ok = false;
        } else {
            throw new ProtocolException("CONFIG_ACK missing ok field");
        }
        String message = extractString(body, "message");
        return new Ack(ok, message);
    }

    private static final Pattern STRING_FIELD = Pattern.compile(
            "\"(?<key>[A-Za-z]+)\"\\s*:\\s*\"(?<value>[^\"]*)\"");

    private static String extractString(String body, String key) {
        Matcher m = STRING_FIELD.matcher(body);
        while (m.find()) {
            if (key.equals(m.group("key"))) {
                return m.group("value");
            }
        }
        throw new ProtocolException("missing string field: " + key);
    }

    private static int extractInt(String body, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(?<value>-?\\d+)");
        Matcher m = p.matcher(body);
        if (!m.find()) {
            throw new ProtocolException("missing integer field: " + key);
        }
        return Integer.parseInt(m.group("value"));
    }

    public record Apply(String listenHost, int listenPort, StreamMode mode) {
        public Apply(String listenHost, int listenPort) {
            this(listenHost, listenPort, StreamMode.DIRECT);
        }
    }

    public record Ack(boolean ok, String message) {
    }
}