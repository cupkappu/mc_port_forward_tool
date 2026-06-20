package dev.kifuko.mctransport.config;

import dev.kifuko.mctransport.protocol.StreamMode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal hand-rolled TOML reader/writer for the MVP config files.
 *
 * <p>Server config supports flat {@code key = value} pairs and repeated
 * {@code [[routes]]} tables. Client config remains flat for legacy
 * parser coverage.</p>
 */
public final class ConfigLoader {

    private static final Pattern STRING_VALUE = Pattern.compile("\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
    private static final Pattern INT_VALUE = Pattern.compile("[+-]?[0-9][0-9_]*");
    private static final Pattern KEY_LINE = Pattern.compile(
            "^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*?)\\s*$");
    private static final Pattern TABLE_HEADER = Pattern.compile(
            "^\\s*\\[\\[([A-Za-z_][A-Za-z0-9_]*)\\]\\]\\s*$");

    private ConfigLoader() {
    }

    /**
     * Loads a {@link ServerConfig} from {@code configDir/filename}, copying
     * {@code bundledResource} into that path when missing.
     */
    public static ServerConfig loadServer(Path configDir, String filename, String bundledResource) {
        Path file = ensureFile(configDir, filename, bundledResource);
        ParsedToml toml = parseFile(file);
        return parseServer(toml, file);
    }

    /** Parses a {@link ParsedToml} into a {@link ServerConfig}. */
    public static ServerConfig parseServer(ParsedToml toml, Path sourceFile) {
        try {
            toml.requireOnlyRootKeys(List.of(
                    "enabled",
                    "channel_name",
                    "max_streams_per_player",
                    "stream_buffer_size",
                    "global_buffer_size_per_player",
                    "idle_timeout_seconds",
                    "connect_timeout_seconds",
                    "log_level"
            ));
            List<RouteConfig> routes = new ArrayList<>();
            for (Map<String, Object> table : toml.tables("routes")) {
                routes.add(toRouteConfig(table));
            }
            return new ServerConfig(
                    toml.bool("enabled"),
                    toml.string("channel_name"),
                    routes,
                    (int) toml.longValue("max_streams_per_player"),
                    (int) toml.longValue("stream_buffer_size"),
                    toml.longValue("global_buffer_size_per_player"),
                    (int) toml.longValue("idle_timeout_seconds"),
                    (int) toml.longValue("connect_timeout_seconds"),
                    toml.string("log_level")
            );
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "invalid server config " + sourceFile + ": " + e.getMessage(), e);
        }
    }

    /** Persists a {@link ServerConfig} with canonical formatting. */
    public static void writeServer(Path configDir, String filename, ServerConfig config) {
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "cannot create config directory " + configDir, e);
        }
        Path file = configDir.resolve(filename);
        StringBuilder out = new StringBuilder();
        out.append("enabled = ").append(config.isEnabled()).append('\n');
        out.append("channel_name = \"").append(escapeString(config.getChannelName())).append("\"\n");
        out.append("max_streams_per_player = ").append(config.getMaxStreamsPerPlayer()).append('\n');
        out.append("stream_buffer_size = ").append(config.getStreamBufferSize()).append('\n');
        out.append("global_buffer_size_per_player = ")
                .append(config.getGlobalBufferSizePerPlayer()).append('\n');
        out.append("idle_timeout_seconds = ").append(config.getIdleTimeoutSeconds()).append('\n');
        out.append("connect_timeout_seconds = ").append(config.getConnectTimeoutSeconds()).append('\n');
        out.append("log_level = \"").append(escapeString(config.getLogLevel())).append("\"\n");
        for (RouteConfig route : config.getRoutes()) {
            out.append('\n');
            out.append("[[routes]]\n");
            out.append("player_uuid = \"").append(route.getPlayerUuid().toString()).append("\"\n");
            out.append("player_name = \"").append(escapeString(route.getPlayerName())).append("\"\n");
            out.append("listen_port = ").append(route.getListenPort()).append('\n');
            out.append("target_host = \"").append(escapeString(route.getTargetHost())).append("\"\n");
            out.append("target_port = ").append(route.getTargetPort()).append('\n');
            out.append("stream_mode = \"").append(route.getMode().name()).append("\"\n");
        }
        try {
            Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write config file " + file, e);
        }
    }

    private static RouteConfig toRouteConfig(Map<String, Object> table) {
        String uuidStr = (String) table.get("player_uuid");
        String name = (String) table.get("player_name");
        long listenPort = ((Number) table.get("listen_port")).longValue();
        String targetHost = (String) table.get("target_host");
        long targetPort = ((Number) table.get("target_port")).longValue();
        if (uuidStr == null) {
            throw new IllegalArgumentException("route missing player_uuid");
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid route player_uuid: " + uuidStr, e);
        }
        Object modeObj = table.get("stream_mode");
        StreamMode mode = modeObj instanceof String s ? StreamMode.fromString(s) : StreamMode.DIRECT;
        return new RouteConfig(uuid, name, (int) listenPort, targetHost, (int) targetPort, mode);
    }

    private static String escapeString(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Path ensureFile(Path configDir, String filename, String bundledResource) {
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "cannot create config directory " + configDir, e);
        }
        Path file = configDir.resolve(filename);
        if (!Files.exists(file)) {
            try (InputStream in = ConfigLoader.class.getResourceAsStream(bundledResource)) {
                if (in == null) {
                    throw new IllegalStateException(
                            "bundled resource missing on classpath: " + bundledResource);
                }
                try (OutputStream out = Files.newOutputStream(file)) {
                    in.transferTo(out);
                }
            } catch (IOException e) {
                throw new IllegalStateException(
                        "cannot seed config file " + file + " from " + bundledResource, e);
            }
        }
        return file;
    }

    private static ParsedToml parseFile(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read config file " + file, e);
        }
        ParsedToml toml = new ParsedToml();
        int lineNo = 0;
        String currentTable = null;
        Map<String, Object> currentEntry = null;
        for (String raw : lines) {
            lineNo++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Matcher table = TABLE_HEADER.matcher(raw);
            if (table.matches()) {
                String name = table.group(1);
                if (!"routes".equals(name)) {
                    throw new IllegalStateException(
                            file + ":" + lineNo + ": unsupported table [[" + name + "]]");
                }
                currentTable = name;
                currentEntry = new LinkedHashMap<>();
                toml.addTable(name, currentEntry);
                continue;
            }
            Matcher m = KEY_LINE.matcher(raw);
            if (!m.matches()) {
                throw new IllegalStateException(
                        file + ":" + lineNo + ": cannot parse line: " + raw);
            }
            String key = m.group(1);
            String rhs = m.group(2);
            if (currentEntry != null) {
                if (currentEntry.containsKey(key)) {
                    throw new IllegalStateException(
                            file + ":" + lineNo + ": duplicate key '" + key + "' in table");
                }
                currentEntry.put(key, ParsedToml.parseScalarValue(rhs, file, lineNo));
            } else {
                if (toml.contains(key)) {
                    throw new IllegalStateException(
                            file + ":" + lineNo + ": duplicate key '" + key + "'");
                }
                toml.put(key, rhs, file, lineNo);
            }
        }
        return toml;
    }

    /** Helper that decodes a single quoted TOML string. Visible for tests. */
    static String unquote(String raw, Path file, int lineNo) {
        Matcher m = STRING_VALUE.matcher(raw);
        if (!m.matches()) {
            throw new IllegalStateException(
                    file + ":" + lineNo + ": expected quoted string, got: " + raw);
        }
        String body = m.group(1);
        return body.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\t", "\t");
    }

    /** Internal storage of parsed values. */
    public static final class ParsedToml {
        private final Map<String, Object> values = new LinkedHashMap<>();
        private final List<Map<String, Object>> routes = new ArrayList<>();

        boolean contains(String key) {
            return values.containsKey(key);
        }

        void put(String key, String rhs, Path file, int lineNo) {
            if (rhs.startsWith("[") && rhs.endsWith("]")) {
                values.put(key, parseArray(rhs, file, lineNo));
            } else {
                values.put(key, parseScalarValue(rhs, file, lineNo));
            }
        }

        void addTable(String name, Map<String, Object> entry) {
            if (!"routes".equals(name)) {
                return;
            }
            routes.add(entry);
        }

        public List<Map<String, Object>> tables(String name) {
            if (!"routes".equals(name)) {
                return List.of();
            }
            return routes;
        }

        void requireOnlyRootKeys(List<String> allowed) {
            for (String key : values.keySet()) {
                if (!allowed.contains(key)) {
                    throw new IllegalStateException("unsupported server config key '" + key + "'");
                }
            }
        }

        private List<Object> parseArray(String raw, Path file, int lineNo) {
            String body = raw.substring(1, raw.length() - 1).trim();
            if (body.isEmpty()) {
                return List.of();
            }
            List<Object> out = new ArrayList<>();
            int i = 0;
            int len = body.length();
            StringBuilder cur = new StringBuilder();
            boolean inString = false;
            while (i < len) {
                char c = body.charAt(i);
                if (inString) {
                    cur.append(c);
                    if (c == '\\' && i + 1 < len) {
                        cur.append(body.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (c == '"') {
                        inString = false;
                    }
                    i++;
                    continue;
                }
                if (c == '"') {
                    inString = true;
                    cur.append(c);
                    i++;
                    continue;
                }
                if (c == ',') {
                out.add(parseArrayScalar(cur.toString().trim(), file, lineNo));
                    cur.setLength(0);
                    i++;
                    continue;
                }
                cur.append(c);
                i++;
            }
            String tail = cur.toString().trim();
            if (!tail.isEmpty()) {
                out.add(parseArrayScalar(tail, file, lineNo));
            }
            return out;
        }

        static Object parseScalarValue(String token, Path file, int lineNo) {
            if (token.startsWith("\"")) {
                return unquote(token, file, lineNo);
            }
            if (token.equals("true") || token.equals("false")) {
                return Boolean.parseBoolean(token);
            }
            Matcher m = INT_VALUE.matcher(token);
            if (m.matches()) {
                return Long.parseLong(token.replace("_", ""));
            }
            throw new IllegalStateException(
                    file + ":" + lineNo + ": unsupported value: " + token);
        }

        private Object parseArrayScalar(String token, Path file, int lineNo) {
            try {
                return parseScalarValue(token, file, lineNo);
            } catch (IllegalStateException e) {
                throw new IllegalStateException(
                        file + ":" + lineNo + ": unsupported array element: " + token, e);
            }
        }

        public String string(String key) {
            Object v = values.get(key);
            if (!(v instanceof String s)) {
                throw new IllegalStateException("config key '" + key + "' is not a string");
            }
            return s;
        }

        public long longValue(String key) {
            Object v = values.get(key);
            if (v instanceof Long l) {
                return l;
            }
            throw new IllegalStateException("config key '" + key + "' is not an integer");
        }

        public boolean bool(String key) {
            Object v = values.get(key);
            if (v instanceof Boolean b) {
                return b;
            }
            throw new IllegalStateException("config key '" + key + "' is not a boolean");
        }

        public List<String> stringList(String key) {
            Object v = values.get(key);
            if (!(v instanceof List<?> list)) {
                throw new IllegalStateException("config key '" + key + "' is not an array");
            }
            List<String> out = new ArrayList<>(list.size());
            for (Object item : list) {
                if (!(item instanceof String s)) {
                    throw new IllegalStateException(
                            "config key '" + key + "' must contain only strings");
                }
                out.add(s);
            }
            return out;
        }
    }
}
