package pz.escapefromzomboidfirearms.zb;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import zombie.gameStates.ChooseGameInfo;

/**
 * Demand-loaded vanilla Tarkov reticle geometry. No renderer or Viewpoint
 * dependency. Format: docs/pending/VP_W3_D1_RETICLE_BINARY_RU.md.
 * Geometry buffers are read-only; use absolute reads or duplicate a buffer
 * before relative iteration. All indices, including sparse deltas, start at 0.
 */
public final class ReticleMeshData {
    private static final String RESOURCE = "media/viewpoint/TW_ReticleMeshes.bin";
    private static final byte[] MAGIC = {'T', 'W', 'R', 'M', 'E', 'S', 'H', 0};
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = 24;
    private static final int MAX_BYTES = 64 * 1024 * 1024;
    private static final int MAX_MESHES = 4096;
    private static final int MAX_TEXT_BYTES = 4096;
    private static ReticleMeshData cached;
    private static boolean attempted, logged;

    private final ByteBuffer bytes;
    private final Map<String, Entry> entries;
    private final Map<String, Mesh> meshes = new LinkedHashMap<>();
    private boolean failed;

    private record Entry(int offset, int length) {
    }

    public record Mesh(String id, String name, FloatBuffer vertices, FloatBuffer uv0,
                       List<IntBuffer> submeshes, List<BlendShape> blendShapes) {
        public int vertexCount() {
            return vertices.limit() / 3;
        }

        public int triangleCount() {
            int count = 0;
            for (IntBuffer indices : submeshes) count += indices.limit() / 3;
            return count;
        }
    }

    public record BlendShape(String name, List<Frame> frames) {
    }

    /** Each optional delta buffer is null when its source channel is absent. */
    public record Frame(float weight, IntBuffer indices, FloatBuffer deltas,
                        FloatBuffer normalDeltas, FloatBuffer tangentDeltas) {
    }

    private ReticleMeshData(ByteBuffer bytes, Map<String, Entry> entries) {
        this.bytes = bytes;
        this.entries = Collections.unmodifiableMap(entries);
    }

    /** First call only: resolve the active mod through PZ, then cache its directory. */
    public static synchronized ReticleMeshData get() {
        if (!attempted) {
            attempted = true;
            try {
                ChooseGameInfo.Mod mod = ChooseGameInfo.getModDetails("EscapeFromZomboidFirearms");
                if (mod == null) throw new IOException("Mod information unavailable");
                // B42 chooses the version directory itself. Never assume a
                // particular install path or a literal version folder name.
                String[] roots = {mod.getVersionDir(), mod.getCommonDir(), mod.getDir()};
                for (String root : roots) {
                    if (root == null || root.isBlank()) continue;
                    Path path = Path.of(root).resolve(RESOURCE);
                    if (Files.isRegularFile(path)) {
                        cached = load(path);
                        return cached;
                    }
                }
                throw new IOException("Reticle resource missing from mod directories");
            } catch (Exception | LinkageError error) {
                log(error);
            }
        }
        return cached;
    }

    /** Explicit-path entry point for offline validation; does not initialize PZ. */
    public static ReticleMeshData load(Path path) {
        try (FileChannel input = FileChannel.open(path, StandardOpenOption.READ)) {
            long length = input.size();
            require(length >= HEADER_BYTES && length <= MAX_BYTES, "Resource size out of bounds");
            ByteBuffer data = ByteBuffer.allocate((int) length).order(ByteOrder.LITTLE_ENDIAN);
            while (data.hasRemaining()) {
                if (input.read(data) < 0) throw new IOException("Truncated resource");
            }
            require(input.read(ByteBuffer.allocate(1)) < 0, "Resource grew during read");
            data.flip();
            return directory(data);
        } catch (Exception | LinkageError error) {
            log(error);
            return null;
        }
    }

    public Set<String> ids() {
        return entries.keySet();
    }

    /** Decode each mesh once; a malformed record disables this entire resource. */
    public synchronized Mesh mesh(String id) {
        if (failed || id == null) return null;
        Entry entry = entries.get(id);
        if (entry == null) return null;
        try {
            Mesh mesh = meshes.get(id);
            if (mesh == null) {
                mesh = decode(id, bytes.slice(entry.offset, entry.length).order(ByteOrder.LITTLE_ENDIAN));
                meshes.put(id, mesh);
            }
            return mesh;
        } catch (Exception | LinkageError error) {
            failed = true;
            meshes.clear();
            log(error);
            return null;
        }
    }

    private static ReticleMeshData directory(ByteBuffer data) throws IOException {
        byte[] magic = new byte[8];
        data.get(magic);
        require(Arrays.equals(magic, MAGIC), "Magic mismatch");
        require(uint(data, VERSION) == VERSION, "Version mismatch");
        int count = uint(data, MAX_MESHES);
        int directoryBytes = uint(data, data.limit() - HEADER_BYTES);
        require(uint(data, MAX_BYTES) == data.limit(), "File length mismatch");
        require(count > 0 && directoryBytes >= count * 13, "Directory size out of bounds");
        int expectedOffset = HEADER_BYTES + directoryBytes;
        ByteBuffer directory = data.slice(HEADER_BYTES, directoryBytes).order(ByteOrder.LITTLE_ENDIAN);
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String id = text(directory);
            int offset = uint(directory, data.limit());
            int length = uint(directory, data.limit() - offset);
            require(offset == expectedOffset && length > 0, "Record bounds overlap or leave gaps");
            require(entries.putIfAbsent(id, new Entry(offset, length)) == null, "Duplicate mesh ID");
            expectedOffset += length;
        }
        require(!directory.hasRemaining() && expectedOffset == data.limit(), "Trailing resource bytes");
        return new ReticleMeshData(data.asReadOnlyBuffer(), entries);
    }

    private static Mesh decode(String id, ByteBuffer data) throws IOException {
        String name = text(data);
        int vertices = uint(data, data.remaining() / 20);
        int submeshCount = uint(data, data.remaining() / 4);
        int shapeCount = uint(data, data.remaining() / 8);
        require(vertices > 0, "Empty mesh");
        FloatBuffer positions = floats(data, vertices * 3);
        FloatBuffer uv = floats(data, vertices * 2);
        List<IntBuffer> submeshes = new ArrayList<>();
        for (int i = 0; i < submeshCount; i++) {
            int count = uint(data, data.remaining() / 4);
            require(count % 3 == 0, "Nontriangular submesh");
            IntBuffer indices = data.slice(data.position(), count * 4).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            for (int j = 0; j < count; j++) require(Integer.toUnsignedLong(indices.get(j)) < vertices, "Triangle index out of bounds");
            data.position(data.position() + count * 4);
            submeshes.add(indices.asReadOnlyBuffer());
        }
        List<BlendShape> shapes = new ArrayList<>();
        for (int i = 0; i < shapeCount; i++) {
            String shapeName = text(data);
            int frameCount = uint(data, data.remaining() / 12);
            List<Frame> frames = new ArrayList<>();
            for (int j = 0; j < frameCount; j++) frames.add(frame(data, vertices));
            shapes.add(new BlendShape(shapeName, List.copyOf(frames)));
        }
        require(!data.hasRemaining(), "Trailing record bytes");
        return new Mesh(id, name, positions, uv, List.copyOf(submeshes), List.copyOf(shapes));
    }

    private static Frame frame(ByteBuffer data, int vertices) throws IOException {
        float weight = finite(data.getFloat());
        int flags = uint(data, 3);
        int count = uint(data, vertices);
        int stride = 16 + ((flags & 1) != 0 ? 12 : 0) + ((flags & 2) != 0 ? 12 : 0);
        require(count <= data.remaining() / stride, "Sparse frame out of bounds");
        int[] indices = new int[count];
        float[] positions = new float[count * 3];
        float[] normals = (flags & 1) != 0 ? new float[count * 3] : null;
        float[] tangents = (flags & 2) != 0 ? new float[count * 3] : null;
        BitSet seen = new BitSet(vertices);
        for (int i = 0; i < count; i++) {
            int index = uint(data, vertices - 1);
            require(!seen.get(index), "Duplicate sparse vertex");
            seen.set(index);
            indices[i] = index;
            for (int axis = 0; axis < 3; axis++) positions[i * 3 + axis] = finite(data.getFloat());
            if (normals != null) for (int axis = 0; axis < 3; axis++) normals[i * 3 + axis] = finite(data.getFloat());
            if (tangents != null) for (int axis = 0; axis < 3; axis++) tangents[i * 3 + axis] = finite(data.getFloat());
        }
        return new Frame(weight, IntBuffer.wrap(indices).asReadOnlyBuffer(), FloatBuffer.wrap(positions).asReadOnlyBuffer(),
                         normals == null ? null : FloatBuffer.wrap(normals).asReadOnlyBuffer(),
                         tangents == null ? null : FloatBuffer.wrap(tangents).asReadOnlyBuffer());
    }

    private static FloatBuffer floats(ByteBuffer data, int count) throws IOException {
        require(count <= data.remaining() / 4, "Float array out of bounds");
        FloatBuffer values = data.slice(data.position(), count * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        for (int i = 0; i < count; i++) finite(values.get(i));
        data.position(data.position() + count * 4);
        return values.asReadOnlyBuffer();
    }

    private static int uint(ByteBuffer data, int maximum) throws IOException {
        require(data.remaining() >= 4, "Truncated uint32");
        long value = Integer.toUnsignedLong(data.getInt());
        require(value <= maximum, "Count or index out of bounds");
        return (int) value;
    }

    private static String text(ByteBuffer data) throws IOException {
        int length = uint(data, MAX_TEXT_BYTES);
        require(length > 0 && length <= data.remaining(), "Text size out of bounds");
        String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(data.slice(data.position(), length)).toString();
        data.position(data.position() + length);
        require(value.indexOf(0) < 0, "NUL in text");
        return value;
    }

    private static float finite(float value) throws IOException {
        require(Float.isFinite(value), "Nonfinite float32");
        return value;
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    private static synchronized void log(Throwable error) {
        if (logged) return;
        logged = true;
        String message = String.valueOf(error.getMessage()).replace('\n', ' ').replace('\r', ' ');
        System.out.println("[TW/Viewpoint] Reticle meshes disabled: " + error.getClass().getSimpleName() + ": " + message);
    }
}
