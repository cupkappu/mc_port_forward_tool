package dev.kifuko.mctransport.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal hand-rolled TOML reader/writer for the MVP config files.
 *
 * <p>The transport layer only needs flat key-value pairs and lists of
 * strings. Pulling in a full TOML parser dependency is not justified for
 * MVP. This parser:</p>
 *
 * <ul>
 *   <li>Reads {@code key = value} entries, ignoring blank lines and
 *       {@code #} comments.</li>
 *   <li>Recognises TOML strings ({@code "..."}).</li>
 *   <li>Recognises TOML integers and longs (decimal, with optional
 *       underscores).</li>
 *   <li>Recognises TOML booleans.</li>
 *   <li>Recognises inline arrays of strings and integers.</li>
 *   <li>Rejects duplicate keys.</li>
 *   <li>Throws {@link IllegalStateException} with file path + line number
 *       when parsing fails.</li>
 * </ul>
 *
 * <p>Files are loaded as UTF-8.</p>
 */
public final class ConfigLoader {

    private static final Pattern STRING_VALUE = Pattern.compile("\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
    private static final Pattern INT_VALUE = Pattern.compile("[+-]?[0-9][0-9_]*");
    private static final Pattern KEY_LINE = Pattern.compile(
            "^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*?)\\s*$");

    private ConfigLoader() {
    }

    /**
     * Loads a {@link ClientConfig} from {@code configDir/filename}, copying
     * {@code bundledResource} into that path when missing.
     */
    public static ClientConfig loadClient(Path configDir, String filename, String bundledResource) {
        Path file = ensureFile(configDir, filename, bundledResource);
        ParsedToml toml = parseFile(file);
        try {
            return new ClientConfig(
                    toml.bool("enabled"),
                    toml.string("listen_host"),
                    (int) toml.longValue("listen_port"),
                    toml.string("channel_name"),
                    toml.string("psk"),
                    (int) toml.longValue("max_streams"),
                    (int) toml.longValue("stream_buffer_size"),
                    toml.longValue("global_buffer_size"),
                    toml.string("log_level")
            );
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "invalid client config " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Loads a {@link ServerConfig} from {@code configDir/filename}, copying
     * {@code bundledResource} into that path when missing.
     */
    public static ServerConfig loadServer(Path configDir, String filename, String bundledResource) {
        Path file = ensureFile(configDir, filename, bundledResource);
        ParsedToml toml = parseFile(file);
        try {
            return new ServerConfig(
                    toml.bool("enabled"),
                    toml.string("target_host"),
                    (int) toml.longValue("target_port"),
                    toml.string("channel_name"),
                    toml.string("psk"),
                    toml.stringList("allowed_players"),
                    (int) toml.longValue("max_streams_per_player"),
                    (int) toml.longValue("stream_buffer_size"),
                    toml.longValue("global_buffer_size_per_player"),
                    (int) toml.longValue("idle_timeout_seconds"),
                    (int) toml.longValue("connect_timeout_seconds"),
                    toml.string("log_level")
            );
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "invalid server config " + file + ": " + e.getMessage(), e);
        }
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
        for (String raw : lines) {
            lineNo++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Matcher m = KEY_LINE.matcher(raw);
            if (!m.matches()) {
                throw new IllegalStateException(
                        file + ":" + lineNo + ": cannot parse line: " + raw);
            }
            String key = m.group(1);
            String rhs = m.group(2);
            if (toml.contains(key)) {
                throw new IllegalStateException(
                        file + ":" + lineNo + ": duplicate key '" + key + "'");
            }
            toml.put(key, rhs, file, lineNo);
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
    static final class ParsedToml {
        private final java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();

        boolean contains(String key) {
            return values.containsKey(key);
        }

        void put(String key, String rhs, Path file, int lineNo) {
            if (rhs.startsWith("[") && rhs.endsWith("]")) {
                values.put(key, parseArray(rhs, file, lineNo));
            } else if (rhs.startsWith("\"")) {
                values.put(key, unquote(rhs, file, lineNo));
            } else if (rhs.equals("true") || rhs.equals("false")) {
                values.put(key, Boolean.parseBoolean(rhs));
            } else {
                Matcher m = INT_VALUE.matcher(rhs);
                if (m.matches()) {
                    String digits = rhs.replace("_", "");
                    values.put(key, Long.parseLong(digits));
                } else {
                    throw new IllegalStateException(
                            file + ":" + lineNo + ": unsupported value: " + rhs);
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
                    out.add(parseScalar(cur.toString().trim(), file, lineNo));
                    cur.setLength(0);
                    i++;
                    continue;
                }
                cur.append(c);
                i++;
            }
            String tail = cur.toString().trim();
            if (!tail.isEmpty()) {
                out.add(parseScalar(tail, file, lineNo));
            }
            return out;
        }

        private Object parseScalar(String token, Path file, int lineNo) {
            if (token.startsWith("\"")) {
                return unquote(token, file, lineNo);
            }
            Matcher m = INT_VALUE.matcher(token);
            if (m.matches()) {
                return Long.parseLong(token.replace("_", ""));
            }
            throw new IllegalStateException(
                    file + ":" + lineNo + ": unsupported array element: " + token);
        }

        String string(String key) {
            Object v = values.get(key);
            if (!(v instanceof String s)) {
                throw new IllegalStateException("config key '" + key + "' is not a string");
            }
            return s;
        }

        long longValue(String key) {
            Object v = values.get(key);
            if (v instanceof Long l) {
                return l;
            }
            throw new IllegalStateException("config key '" + key + "' is not an integer");
        }

        boolean bool(String key) {
            Object v = values.get(key);
            if (v instanceof Boolean b) {
                return b;
            }
            throw new IllegalStateException("config key '" + key + "' is not a boolean");
        }

        List<String> stringList(String key) {
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