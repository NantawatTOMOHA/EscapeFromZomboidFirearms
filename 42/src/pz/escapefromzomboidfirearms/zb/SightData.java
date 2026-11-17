package pz.escapefromzomboidfirearms.zb;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.gameStates.ChooseGameInfo;

/** Lazy, bounded binary64 sight tables. No Viewpoint or graphics dependency. */
public final class SightData {
    private static final byte[] MAGIC = {'T','W','S','I','G','H','T',0};
    private static final int HEADER = 32, MAX_BYTES = 64 * 1024 * 1024;
    private static final int MAX_COUNT = 65536, MAX_TEXT = 65536, MAX_DEPTH = 64;
    private static final int MAX_VALUES = 262144;
    private static final Map<String, SightData> resources = new LinkedHashMap<>();
    private static final Set<String> attempted = new HashSet<>();
    private static boolean logged;
    private final ByteBuffer bytes;
    private final List<String> strings;
    private final Map<String, Entry> entries;
    private final Map<String, Map<String, Object>> records = new LinkedHashMap<>();
    private final Map<String, KahluaTable> tables = new LinkedHashMap<>();
    private boolean failed;
    private KahluaTable tableEnvironment;

    private record Entry(int offset, int length) {}

    private SightData(ByteBuffer bytes, List<String> strings, Map<String, Entry> entries) {
        this.bytes = bytes.asReadOnlyBuffer();
        this.strings = List.copyOf(strings);
        this.entries = Collections.unmodifiableMap(entries);
    }

    /** Resolve only an explicitly requested resource through B42 mod directories. */
    public static synchronized KahluaTable table(String resource, String key) {
        String filename = switch (resource == null ? "" : resource) {
            case "optics" -> "TW_ViewpointOptics.bin";
            case "channels" -> "TW_SightChannels.bin";
            default -> null;
        };
        if (filename == null || key == null) return null;
        try {
            if (attempted.add(resource)) {
                ChooseGameInfo.Mod mod = ChooseGameInfo.getModDetails("EscapeFromZomboidFirearms");
                if (mod == null) throw new IOException("Mod information unavailable");
                for (String root : new String[]{mod.getVersionDir(), mod.getCommonDir(), mod.getDir()}) {
                    if (root == null || root.isBlank()) continue;
                    Path path = Path.of(root).resolve("media/viewpoint/" + filename);
                    if (Files.isRegularFile(path)) {
                        SightData data = load(path);
                        if (data != null) resources.put(resource, data);
                        break;
                    }
                }
                if (!resources.containsKey(resource)) throw new IOException("Missing or invalid sight resource");
            }
            SightData data = resources.get(resource);
            return data == null ? null : data.lua(key);
        } catch (Exception | LinkageError error) {
            log(error);
            return null;
        }
    }

    /** Standalone offline entry point: does not initialize PZ or LuaManager. */
    public static SightData load(Path path) {
        try (FileChannel input = FileChannel.open(path, StandardOpenOption.READ)) {
            long length = input.size();
            require(length >= HEADER && length <= MAX_BYTES, "File size");
            ByteBuffer data = ByteBuffer.allocate((int)length).order(ByteOrder.LITTLE_ENDIAN);
            while (data.hasRemaining()) if (input.read(data) < 0) throw new IOException("Truncated file");
            require(input.read(ByteBuffer.allocate(1)) < 0, "File grew during read");
            data.flip();
            byte[] magic = new byte[8];
            data.get(magic);
            require(Arrays.equals(magic, MAGIC), "Magic");
            require(uint(data, 1) == 1, "Version");
            int count = uint(data, MAX_COUNT), textCount = uint(data, MAX_COUNT);
            int textBytes = uint(data, data.limit() - HEADER);
            int directoryBytes = uint(data, data.limit() - HEADER - textBytes);
            require(uint(data, MAX_BYTES) == data.limit(), "File length");
            require(count > 0 && textCount > 0 && textBytes >= textCount * 4
                    && directoryBytes == count * 12, "Header counts");
            ByteBuffer pool = data.slice(HEADER, textBytes).order(ByteOrder.LITTLE_ENDIAN);
            List<String> strings = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < textCount; i++) {
                int n = uint(pool, Math.min(pool.remaining() - 4, MAX_TEXT));
                ByteBuffer utf8 = pool.slice(pool.position(), n);
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(utf8).toString();
                require(text.indexOf(0) < 0 && seen.add(text), "NUL or duplicate string");
                strings.add(text);
                pool.position(pool.position() + n);
            }
            require(!pool.hasRemaining(), "String table length");
            ByteBuffer directory = data.slice(HEADER + textBytes, directoryBytes).order(ByteOrder.LITTLE_ENDIAN);
            int expected = HEADER + textBytes + directoryBytes;
            Map<String, Entry> entries = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                String key = strings.get(uint(directory, strings.size() - 1));
                int offset = uint(directory, data.limit()), n = uint(directory, data.limit() - offset);
                require(offset == expected && n > 0 && !key.isEmpty(), "Record bounds/key");
                require(entries.putIfAbsent(key, new Entry(offset, n)) == null, "Duplicate record");
                expected += n;
            }
            require(expected == data.limit() && !directory.hasRemaining(), "Trailing file bytes");
            return new SightData(data, strings, entries);
        } catch (Exception | LinkageError error) {
            log(error);
            return null;
        }
    }

    public Set<String> keys() { return entries.keySet(); }
    public synchronized int decodedCount() { return records.size(); }
    public synchronized boolean failed() { return failed; }

    /** An invalid payload disables the complete resource; never retries per frame. */
    public synchronized Map<String, Object> record(String key) {
        if (failed || key == null || !entries.containsKey(key)) return null;
        try {
            Map<String, Object> row = records.get(key);
            if (row == null) {
                Entry e = entries.get(key);
                ByteBuffer payload = bytes.slice(e.offset, e.length).order(ByteOrder.LITTLE_ENDIAN);
                Object value = value(payload, 0, new int[]{MAX_VALUES});
                require(!payload.hasRemaining() && value instanceof Map, "Record type/trailing bytes");
                @SuppressWarnings("unchecked") Map<String, Object> decoded = (Map<String, Object>)value;
                row = decoded;
                records.put(key, row);
            }
            return row;
        } catch (Exception | LinkageError error) {
            disable(error);
            return null;
        }
    }

    public synchronized KahluaTable lua(String key) {
        if (failed) return null;
        // ResetLua (joining a server) wipes every table reachable from the old environment in
        // place. Keep the decoded records, but never hand the new environment old Lua copies.
        KahluaTable current = LuaManager.env;
        if (tableEnvironment != current) {
            tables.clear();
            tableEnvironment = current;
        }
        KahluaTable result = tables.get(key);
        if (result != null) return result;
        Map<String, Object> row = record(key);
        if (row == null) return null;
        try {
            result = (KahluaTable)toLua(row);
            tables.put(key, result);
            return result;
        } catch (Exception | LinkageError error) {
            disable(error);
            return null;
        }
    }

    private String string(ByteBuffer data) throws IOException {
        return strings.get(uint(data, strings.size() - 1));
    }

    private Object value(ByteBuffer data, int depth, int[] budget) throws IOException {
        require(depth <= MAX_DEPTH && --budget[0] >= 0 && data.hasRemaining(), "Value depth/length");
        int tag = Byte.toUnsignedInt(data.get());
        switch (tag) {
            case 0: return Boolean.FALSE;
            case 1: return Boolean.TRUE;
            case 2: {
                double x = data.getDouble();
                require(Double.isFinite(x), "Nonfinite number");
                return x;
            }
            case 3: return string(data);
            case 6: return null;
            case 4: {
                int count = uint(data, Math.max(0, data.remaining() - 4));
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < count; i++) list.add(value(data, depth + 1, budget));
                return Collections.unmodifiableList(list);
            }
            case 5: {
                int count = uint(data, Math.max(0, (data.remaining() - 4) / 5));
                Map<String, Object> map = new LinkedHashMap<>();
                for (int i = 0; i < count; i++) {
                    String key = string(data);
                    require(!map.containsKey(key), "Duplicate field");
                    map.put(key, value(data, depth + 1, budget));
                }
                return Collections.unmodifiableMap(map);
            }
            default: throw new IOException("Unknown value tag");
        }
    }

    private static Object toLua(Object value) {
        if (value instanceof Map<?, ?> map) {
            KahluaTable table = LuaManager.platform.newTable();
            map.forEach((k, v) -> table.rawset(k, toLua(v)));
            return table;
        }
        if (value instanceof List<?> list) {
            KahluaTable table = LuaManager.platform.newTable();
            for (int i = 0; i < list.size(); i++) table.rawset((double)i + 1, toLua(list.get(i)));
            return table;
        }
        return value;
    }

    private void disable(Throwable error) {
        failed = true;
        records.clear();
        tables.clear();
        log(error);
    }

    private static int uint(ByteBuffer data, int max) throws IOException {
        require(data.remaining() >= 4, "Truncated integer");
        long n = Integer.toUnsignedLong(data.getInt());
        require(n <= max, "Integer/count limit");
        return (int)n;
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    private static synchronized void log(Throwable error) {
        if (logged) return;
        logged = true;
        String message = String.valueOf(error.getMessage()).replace('\n', ' ').replace('\r', ' ');
        System.out.println("[TW/Viewpoint] Sight data disabled: " + error.getClass().getSimpleName() + ": " + message);
    }
}
