package com.worldtransfer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Plain-data helpers shared by both sides of a transfer: the file index used for incremental
 * transfers, the transfer marker, and the ownership history.
 *
 * <p>Nothing in here touches Minecraft classes, so it is easy to reason about and hard to break.</p>
 */
public final class TransferData {
    public static final String DIR = "world-transfer";
    public static final String MARKER_FILE = "world-transfer.json";
    public static final String HISTORY_FILE = DIR + "/history.json";
    public static final String WORLD_ID_FILE = DIR + "/world-id.txt";
    public static final String PLAYERS_DIR = DIR + "/players";

    /** Index format version. Bumped when the header or entry layout changes. */
    public static final int INDEX_FORMAT = 1;

    private TransferData() {
    }

    // ------------------------------------------------------------------
    // File index
    // ------------------------------------------------------------------

    /** One file in a world, identified by content rather than by timestamp. */
    public record FileEntry(String path, long size, String sha1) {
    }

    /**
     * A description of a whole world: a header of key/value metadata plus one line per file.
     * Serialized as gzipped UTF-8 text, which compresses extremely well and parses without a
     * JSON dependency.
     */
    public static final class Index {
        private final Map<String, String> meta = new LinkedHashMap<>();
        private final List<FileEntry> entries = new ArrayList<>();

        public Map<String, String> meta() {
            return meta;
        }

        public List<FileEntry> entries() {
            return entries;
        }

        public String get(String key, String fallback) {
            String value = meta.get(key);
            return value == null ? fallback : value;
        }

        public int getInt(String key, int fallback) {
            try {
                return Integer.parseInt(get(key, String.valueOf(fallback)));
            } catch (NumberFormatException exception) {
                return fallback;
            }
        }

        public long totalBytes() {
            long total = 0L;
            for (FileEntry entry : entries) {
                total += entry.size();
            }
            return total;
        }

        public Map<String, FileEntry> byPath() {
            Map<String, FileEntry> map = new LinkedHashMap<>();
            for (FileEntry entry : entries) {
                map.put(entry.path(), entry);
            }
            return map;
        }

        public byte[] encode() throws IOException {
            StringBuilder text = new StringBuilder();
            text.append("#format ").append(INDEX_FORMAT).append('\n');
            for (Map.Entry<String, String> entry : meta.entrySet()) {
                text.append('#').append(entry.getKey()).append(' ')
                    .append(entry.getValue().replace('\n', ' ')).append('\n');
            }
            for (FileEntry entry : entries) {
                text.append(entry.sha1()).append(' ').append(entry.size()).append(' ')
                    .append(entry.path()).append('\n');
            }
            return gzip(text.toString().getBytes(StandardCharsets.UTF_8));
        }

        public static Index decode(byte[] gzipped) throws IOException {
            Index index = new Index();
            String text = new String(gunzip(gzipped), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                if (line.isEmpty()) {
                    continue;
                }
                if (line.charAt(0) == '#') {
                    int space = line.indexOf(' ');
                    if (space > 1) {
                        index.meta.put(line.substring(1, space), line.substring(space + 1));
                    }
                    continue;
                }
                String[] parts = line.split(" ", 3);
                if (parts.length == 3) {
                    try {
                        index.entries.add(new FileEntry(parts[2], Long.parseLong(parts[1]), parts[0]));
                    } catch (NumberFormatException ignored) {
                        // skip malformed line rather than failing the whole transfer
                    }
                }
            }
            return index;
        }
    }

    /** Paths the receiver is missing or has a different version of. */
    public static List<String> diff(Index wanted, Map<String, FileEntry> local) {
        List<String> needed = new ArrayList<>();
        for (FileEntry entry : wanted.entries()) {
            FileEntry have = local.get(entry.path());
            if (have == null || have.size() != entry.size() || !have.sha1().equals(entry.sha1())) {
                needed.add(entry.path());
            }
        }
        return needed;
    }

    /** Indexes a folder on disk, skipping nothing; callers filter beforehand. */
    public static Map<String, FileEntry> indexFolder(Path root) throws IOException {
        Map<String, FileEntry> map = new LinkedHashMap<>();
        if (!Files.isDirectory(root)) {
            return map;
        }
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String path = root.relativize(file).toString().replace('\\', '/');
                map.put(path, new FileEntry(path, Files.size(file), sha1(file)));
            }
        }
        return map;
    }

    // ------------------------------------------------------------------
    // Hashing and compression
    // ------------------------------------------------------------------

    public static String sha1(Path file) throws IOException {
        MessageDigest digest = newSha1();
        byte[] buffer = new byte[65536];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    public static String sha1(byte[] bytes) {
        return hex(newSha1().digest(bytes));
    }

    private static MessageDigest newSha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is required but missing", exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >> 4) & 0xF, 16));
            result.append(Character.forDigit(value & 0xF, 16));
        }
        return result.toString();
    }

    public static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream out = new GZIPOutputStream(bytes)) {
            out.write(raw);
        }
        return bytes.toByteArray();
    }

    public static byte[] gunzip(byte[] compressed) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return in.readAllBytes();
        }
    }

    // ------------------------------------------------------------------
    // Marker and history
    // ------------------------------------------------------------------

    public static String markerJson(String transferId, String worldId, String newHost, String newHostUuid,
                                    String previousHost, String gameVersion, int worldVersion,
                                    String modVersion, List<String> participants) {
        StringBuilder names = new StringBuilder();
        for (String participant : participants) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append('"').append(escape(participant)).append('"');
        }
        return "{\n"
            + "  \"format\": 6,\n"
            + "  \"transferId\": \"" + escape(transferId) + "\",\n"
            + "  \"worldId\": \"" + escape(worldId) + "\",\n"
            + "  \"createdAt\": \"" + Instant.now() + "\",\n"
            + "  \"newHost\": \"" + escape(newHost) + "\",\n"
            + "  \"newHostUuid\": \"" + escape(newHostUuid) + "\",\n"
            + "  \"previousHost\": \"" + escape(previousHost) + "\",\n"
            + "  \"gameVersion\": \"" + escape(gameVersion) + "\",\n"
            + "  \"worldVersion\": " + worldVersion + ",\n"
            + "  \"modVersion\": \"" + escape(modVersion) + "\",\n"
            + "  \"participants\": [" + names + "]\n"
            + "}\n";
    }

    /** One entry per handover, appended to a JSON array that stays readable by hand. */
    public record HistoryEntry(String at, String from, String to, String gameVersion, String transferId) {
        public String toJson() {
            return "  {\"at\": \"" + escape(at) + "\", \"from\": \"" + escape(from) + "\", \"to\": \""
                + escape(to) + "\", \"gameVersion\": \"" + escape(gameVersion) + "\", \"transferId\": \""
                + escape(transferId) + "\"}";
        }
    }

    public static List<HistoryEntry> parseHistory(String json) {
        List<HistoryEntry> entries = new ArrayList<>();
        var matcher = java.util.regex.Pattern.compile("\\{[^}]*}").matcher(json);
        while (matcher.find()) {
            String object = matcher.group();
            entries.add(new HistoryEntry(
                field(object, "at"), field(object, "from"), field(object, "to"),
                field(object, "gameVersion"), field(object, "transferId")));
        }
        return entries;
    }

    public static String writeHistory(List<HistoryEntry> entries) {
        StringBuilder json = new StringBuilder("[\n");
        for (int i = 0; i < entries.size(); i++) {
            json.append(entries.get(i).toJson());
            json.append(i == entries.size() - 1 ? "\n" : ",\n");
        }
        return json.append("]\n").toString();
    }

    public static String field(String json, String key) {
        var matcher = java.util.regex.Pattern
            .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    public static int intField(String json, String key, int fallback) {
        var matcher = java.util.regex.Pattern
            .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }

    public static List<String> listField(String json, String key) {
        List<String> values = new ArrayList<>();
        var block = java.util.regex.Pattern
            .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\\[([^]]*)]").matcher(json);
        if (block.find()) {
            var quoted = java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(block.group(1));
            while (quoted.find()) {
                values.add(quoted.group(1));
            }
        }
        return values;
    }

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------

    public static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static String sanitize(String value) {
        String safe = value.replaceAll("[^a-zA-Z0-9._-]", "_");
        return safe.isEmpty() ? "world" : safe;
    }

    public static String safeName(String playerName) {
        return sanitize(playerName).toLowerCase(Locale.ROOT);
    }

    public static String humanBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
