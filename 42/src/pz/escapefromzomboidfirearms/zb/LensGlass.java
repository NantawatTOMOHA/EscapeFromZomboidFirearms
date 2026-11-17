package pz.escapefromzomboidfirearms.zb;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.escapefromzomboidfirearms.realgl.RealGL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.asset.Asset;
import zombie.asset.AssetPath;
import zombie.characters.IsoPlayer;
import zombie.core.opengl.RenderThread;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.ModelMesh;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.core.skinnedmodel.model.VertexBufferObject.Vbo;
import zombie.core.skinnedmodel.model.VertexBufferObject.VertexArray;
import zombie.core.skinnedmodel.model.VertexBufferObject.VertexFormat;
import zombie.core.skinnedmodel.model.VertexBufferObject.VertexType;

/**
 * Omit the active ADS channel's authored glass from the local VP snapshot. Original PZ
 * models, instances, textures and buffers are never written. Each independent
 * copy retains every source vertex byte and only shortens its index stream.
 * Glass rows belong to drawn meshes: a sight geometry state (TW/States/tpl_state)
 * has its own row, listing only the ADS profiles that show that state.
 * The one guarded advice substitutes an argument of ModelDraws.add; it never
 * marks the entire sight as GLASS (which would also make its housing glass).
 */
public final class LensGlass {
    static final String[][] MEMBERS = {
        {"viewpoint.render.ModelDraws", "add", "(Lzombie/core/skinnedmodel/model/ModelMesh;Lzombie/core/skinnedmodel/model/ModelInstance;Lzombie/core/textures/Texture;Lorg/joml/Matrix4f;IIII)I", ""},
    };
    /** Every glass mesh at once, with room for sources PZ reloads in a session. */
    static final int COPY_LIMIT = 2 * LensGlassData.ROWS.size();
    private static final Map<ModelMesh, Copy> copies = new IdentityHashMap<>();
    private static boolean checked;
    /** Performance pass 23.10.2026: the check is over (set after it): detect() from every model draw without the lock. */
    private static volatile boolean settled;
    private static volatile boolean ready, failed;
    private static volatile boolean enabled;
    private static String status = "not checked";
    private static Field handle, format;
    /** VertexBufferObject.elements: PZ's own copy of the index stream its EBO was filled from (optional; null: absent). */
    private static Field elements;
    private static volatile long created, substitutions, warming, errors, rejected, prepares, extraSubstitutions;

    private LensGlass() {
    }

    static boolean detect() {
        if (settled) return ready && !failed;
        return check();
    }

    private static synchronized boolean check() {
        if (checked) return ready && !failed;
        checked = true;
        try {
            return lookUp();
        } finally {
            settled = true;
        }
    }

    private static boolean lookUp() {
        if (!ViewpointBridge.detect()) {
            status = "bridge unavailable";
            return false;
        }
        try {
            for (String[] member : MEMBERS) {
                if (ViewpointBridge.resolve(member, LensGlass.class.getClassLoader()) == null) {
                    throw new NoSuchFieldException(member[0] + "." + member[1] + member[2]);
                }
            }
            handle = field("handle", Vbo.class);
            format = field("vertexFormat", VertexFormat.class);
            try {
                elements = field("elements", int[].class);
            } catch (Exception absent) {
                elements = null; // the index stream is then read back from the GPU, as before
            }
            status = "on";
            ready = true;
        } catch (Throwable error) {
            // A Viewpoint member that is not as checked: the shared guard
            // shuts all our VP use down (the bridge policy).
            fail(error);
        }
        return ready && !failed;
    }

    private static Field field(String name, Class<?> type) throws Exception {
        Field field = VertexBufferObject.class.getDeclaredField(name);
        if (field.getType() != type || Modifier.isStatic(field.getModifiers())) {
            throw new NoSuchFieldException("VertexBufferObject." + name);
        }
        field.setAccessible(true);
        return field;
    }

    private static synchronized void fail(Throwable error) {
        if (failed) return;
        failed = true;
        ready = false;
        errors++;
        status = "off: " + error;
        // The shared guard owns the single diagnostic and shuts all our VP use down.
        ViewpointBridge.fail("lens glass", error);
    }

    /**
     * Our own failure after the members were proven (data, buffers, a reload):
     * only the glass substitution stops, one line; the bridge stays on.
     */
    private static synchronized void disable(Throwable error) {
        if (failed) return;
        failed = true;
        errors++;
        status = "off: " + error;
        System.out.println("[TWZB] Viewpoint lens glass off: " + error);
    }

    /** Parent identity, not a shared ModelMesh identity, distinguishes the held copy. */
    static boolean inPrimaryTree(ModelInstance node, ModelInstance root) {
        if (root == null) return false;
        for (int depth = 0; node != null && depth < 64; depth++, node = node.parent) {
            if (node == root) return true;
        }
        return false;
    }

    /** Main/capture thread. A pending GPU copy leaves the current draw untouched. */
    public static ModelMesh mesh(ModelMesh source, ModelInstance instance) {
        try {
            if (!enabled || failed || !detect() || ViewpointBridge.state() != 1 || source == null || instance == null) return source;
            IsoPlayer player = IsoPlayer.getInstance();
            // Rigid child parts need not carry their own character reference.
            // Ownership is the actual local primary-hand root, below.
            if (player == null || player.getPlayerNum() != 0 || !player.isLocalPlayer()
                    || player.getPrimaryHandItem() == null
                    || !player.getPrimaryHandItem().getFullType().startsWith("EscapeFromZomboidFirearms.")
                    || !inPrimaryTree(instance, player.primaryHandModel) || instance.modelScript == null) return source;
            String name = instance.modelScript.getMeshName();
            if (name == null || !name.startsWith("TW/")) return source;
            LensGlassData.Row row = LensGlassData.ROWS.get(name.substring(3));
            if (row == null || source.vb == null || !source.vb.isStatic) return source;
            Copy copy;
            synchronized (copies) {
                copy = copies.get(source);
                if (copy == null) {
                    if (copies.size() >= COPY_LIMIT) throw new IllegalStateException("Lens copy cache limit");
                    copy = new Copy(source, row);
                    copies.put(source, copy);
                    Copy pending = copy;
                    RenderThread.queueInvokeOnRenderContext(() -> create(pending));
                }
            }
            if (copy.rejected) return source;
            if (copy.sourceVb != source.vb || copy.modification != source.modificationCount) {
                throw new IllegalStateException("Lens source reloaded; original retained");
            }
            ModelMesh result = copy.mesh;
            if (result == null) {
                warming++;
                return source;
            }
            LensGlassSelection.Selection selected = LensGlassSelection.active(player, instance, row.tpl());
            if (selected == null) {
                // FB8 item 5: another collimator of the held weapon (or any at the hip) - its glass open too
                String extra = LensGlassSelection.extraProfile(player, instance, row.tpl());
                result = extra == null ? null : copy.channels.get(extra);
                if (result == null || result == source) return source;
                extraSubstitutions++;
                return result;
            }
            result = copy.channels.get(selected.profile());
            String previous = result == null ? LensGlassSelection.switchProfile(selected) : null;
            if (previous != null) result = copy.channels.get(previous);
            if (result == null || result == source) return source;
            substitutions++;
            copy.substitutions++;
            return result;
        } catch (Throwable error) {
            disable(error);
            return source;
        }
    }

    /**
     * FB4 item 9: true when a sight state's mesh can be drawn in the held weapon at once - it has no glass row, the
     * glass is off, or its copy is made (or rejected). Otherwise the copy is started (render thread) and false is
     * returned: PartModelSwap waits a frame or two instead of drawing the mesh's own glass over the scope picture.
     */
    public static boolean prepared(ModelMesh source, String meshName) {
        try {
            if (!enabled || failed || !detect() || source == null || meshName == null || !meshName.startsWith("TW/")) return true;
            LensGlassData.Row row = LensGlassData.ROWS.get(meshName.substring(3));
            if (row == null || source.vb == null || !source.vb.isStatic) return true;
            Copy copy;
            synchronized (copies) {
                copy = copies.get(source);
                if (copy == null) {
                    if (copies.size() >= COPY_LIMIT) return true;
                    copy = new Copy(source, row);
                    copies.put(source, copy);
                    Copy pending = copy;
                    RenderThread.queueInvokeOnRenderContext(() -> create(pending));
                    prepares++;
                }
            }
            return copy.rejected || copy.mesh != null;
        } catch (Throwable error) {
            disable(error);
            return true;
        }
    }

    private static final class Copy {
        final ModelMesh source;
        final VertexBufferObject sourceVb;
        final int modification;
        final LensGlassData.Row row;
        volatile ModelMesh mesh;
        /** The model does not match its glass data: drawn with its glass, the others still substituted. */
        volatile boolean rejected;
        volatile String reason = "";
        volatile int keptTriangles = -1;
        volatile long substitutions;
        volatile Map<String, ModelMesh> channels = Map.of();

        Copy(ModelMesh source, LensGlassData.Row row) {
            this.source = source;
            sourceVb = source.vb;
            modification = source.modificationCount;
            this.row = row;
        }
    }

    /**
     * PZ's CPU copy of a mesh's index stream (the EBO holds exactly these ints: VertexBufferObject.LoadVBO), or null.
     * macOS (02.11.2026): preferred to a GPU read-back, which the Metal shim does not implement.
     */
    private static int[] cpuIndices(VertexBufferObject vb, int count) {
        try {
            int[] kept = elements == null ? null : (int[]) elements.get(vb);
            return kept != null && kept.length == count ? kept.clone() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when a read-back holds only zero bytes: no GPU data came back (a real lens mesh has positions and UVs). */
    private static boolean empty(ByteBuffer data) {
        for (int i = data.position(); i < data.limit(); i++) {
            if (data.get(i) != 0) return false;
        }
        return true;
    }

    /** Render thread only; read source through COPY_READ, never change the source VAO/EBO. */
    private static void create(Copy copy) {
        if (failed) return;
        int oldRead = GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER);
        int oldArray = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int oldElements = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        List<VertexBufferObject> staged = new ArrayList<>();
        boolean published = false;
        try {
            ModelMesh source = copy.source;
            if (source.vb != copy.sourceVb || source.modificationCount != copy.modification) {
                throw new IllegalStateException("Lens source changed before copy");
            }
            Vbo vbo = (Vbo) handle.get(copy.sourceVb);
            VertexFormat layout = (VertexFormat) format.get(copy.sourceVb);
            if (vbo == null || layout == null || vbo.vboId == 0 || vbo.eboId == 0 || vbo.faceDataOnly
                    || layout.indexOf(VertexType.BlendWeightArray) >= 0
                    || layout.indexOf(VertexType.BlendIndexArray) >= 0
                    || vbo.numElements != copy.row.triangles() * 3) {
                throw new IllegalArgumentException("Lens source VBO not a matching rigid mesh");
            }
            int stride = layout.getStride(), uvIndex = layout.indexOf(VertexType.TextureCoordArray);
            if (stride <= 0 || stride != vbo.vertexStride || uvIndex < 0) {
                throw new IllegalArgumentException("Lens VBO stride/UV changed");
            }
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, vbo.vboId);
            int bytes = GL15.glGetBufferParameteri(GL31.GL_COPY_READ_BUFFER, GL15.GL_BUFFER_SIZE);
            if (bytes <= 0 || bytes > 64 * 1024 * 1024 || bytes % stride != 0 || bytes % 4 != 0) {
                throw new IllegalArgumentException("Lens vertex buffer size");
            }
            ByteBuffer vertices = BufferUtils.createByteBuffer(bytes).order(ByteOrder.nativeOrder());
            // The model's buffer is PZ's, on the real context: under the Metal shim its id means nothing to the shim's
            // glGetBufferSubData, which read nothing (player log 04.11.2026, "the sight glass stays drawn").
            if (GLCompat.metalPath()) RealGL.readBuffer(vbo.vboId, vertices);
            else GL15.glGetBufferSubData(GL31.GL_COPY_READ_BUFFER, 0, vertices.asFloatBuffer());
            if (empty(vertices)) {
                // macOS (02.11.2026, the Metal shim: "glGetBufferSubData not implemented"): nothing came back. Not a
                // geometry mismatch - the copy cannot be made on this context; the glass stays drawn, one line.
                throw new IllegalStateException("GPU buffer read-back unavailable on this GL context"
                        + " (glGetBufferSubData returned no data): the sight glass stays drawn");
            }
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, vbo.eboId);
            if (GL15.glGetBufferParameteri(GL31.GL_COPY_READ_BUFFER, GL15.GL_BUFFER_SIZE) != vbo.numElements * 4) {
                throw new IllegalArgumentException("Lens index buffer size");
            }
            int[] indices = cpuIndices(copy.sourceVb, vbo.numElements);
            if (indices == null) {
                ByteBuffer raw = BufferUtils.createByteBuffer(vbo.numElements * 4).order(ByteOrder.nativeOrder());
                if (GLCompat.metalPath()) RealGL.readBuffer(vbo.eboId, raw);
                else GL15.glGetBufferSubData(GL31.GL_COPY_READ_BUFFER, 0, raw.asFloatBuffer());
                IntBuffer read = raw.asIntBuffer();
                indices = new int[read.remaining()];
                read.get(indices);
            }
            int[] kept = LensGlassGeometry.filter(vertices, stride, layout.getElement(uvIndex).byteOffset, indices, copy.row);
            copy.keptTriangles = kept.length / 3;
            ModelMesh mesh = buildMesh(copy, vertices, layout, kept, staged);
            Map<String, ModelMesh> channels = new HashMap<>();
            Map<LensGlassChannels.Channel, ModelMesh> variants = new HashMap<>();
            for (var entry : LensGlassChannels.ROWS.get(copy.row.key()).entrySet()) {
                LensGlassChannels.Channel channel = entry.getValue();
                ModelMesh variant;
                if (channel.removed() == 0) variant = source;
                else if (channel.axis() < 0 && channel.removed() == copy.row.removed()) variant = mesh;
                else {
                    variant = variants.get(channel);
                    if (variant == null) {
                        int positionIndex = layout.indexOf(VertexType.VertexArray);
                        int position = positionIndex < 0 ? -1 : layout.getElement(positionIndex).byteOffset;
                        int[] selected = LensGlassGeometry.filter(vertices, stride, layout.getElement(uvIndex).byteOffset,
                                position, indices, copy.row, channel);
                        variant = buildMesh(copy, vertices, layout, selected, staged);
                        variants.put(channel, variant);
                    }
                }
                channels.put(entry.getKey(), variant);
            }
            copy.channels = Map.copyOf(channels);
            copy.mesh = mesh;
            published = true;
            created++;
        } catch (IllegalArgumentException error) {
            copy.reason = error.getMessage();
            copy.rejected = true;
            rejected++;
            System.out.println("[TWZB] Viewpoint lens glass kept on " + copy.row.key() + ": " + error.getMessage());
        } catch (Throwable error) {
            disable(error);
        } finally {
            try {
                if (!published) for (VertexBufferObject buffer : staged) buffer.clear();
            } finally {
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, oldRead);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, oldArray);
                GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, oldElements);
            }
        }
    }

    private static ModelMesh buildMesh(Copy copy, ByteBuffer vertices, VertexFormat layout, int[] indices,
                                       List<VertexBufferObject> staged) throws Exception {
        VertexArray array = new VertexArray(layout, vertices.limit() / layout.getStride());
        array.buffer.put(vertices.duplicate());
        array.buffer.flip();
        VertexBufferObject own = new VertexBufferObject(array, indices);
        staged.add(own);
        if (handle.get(own) == null) throw new IllegalStateException("Lens copy VBO not created on render thread");
        ModelMesh source = copy.source;
        ModelMesh.MeshAssetParams params = new ModelMesh.MeshAssetParams();
        params.isStatic = true;
        ModelMesh mesh = new ModelMesh(new AssetPath("TW/LensGlass/" + copy.row.key() + "/" + staged.size()), source.getAssetManager(), params);
        mesh.minXyz.set(source.minXyz);
        mesh.maxXyz.set(source.maxXyz);
        mesh.transform = source.transform == null ? null : new Matrix4f(source.transform);
        mesh.fullPath = source.fullPath;
        mesh.SetVertexBuffer(own);
        mesh.hasVbo = true;
        mesh.onCreated(Asset.State.READY);
        return mesh;
    }

    public static String info() {
        // Snapshot only; QA must not create a copy or read GL from the Lua thread.
        Map<String, long[]> models = new TreeMap<>();
        Map<String, String> reasons = new TreeMap<>();
        synchronized (copies) {
            for (Copy copy : copies.values()) {
                long[] counts = models.computeIfAbsent(copy.row.key(), key -> new long[] {0, 0, 0, 0, -1});
                counts[copy.rejected ? 1 : copy.mesh != null ? 0 : 2]++;
                counts[3] += copy.substitutions;
                if (copy.mesh != null) counts[4] = copy.keptTriangles;
                if (copy.rejected) reasons.put(copy.row.key(), copy.reason);
            }
        }
        StringBuilder detail = new StringBuilder(" models=[");
        for (Map.Entry<String, long[]> entry : models.entrySet()) {
            if (detail.length() > 9) detail.append("; ");
            long[] counts = entry.getValue();
            LensGlassData.Row row = LensGlassData.ROWS.get(entry.getKey());
            detail.append(entry.getKey()).append(":created=").append(counts[0])
                    .append(",rejected=").append(counts[1]).append(",pending=").append(counts[2])
                    .append(",substitutions=").append(counts[3])
                    .append(",expectedKeptTriangles=").append(row.triangles() - row.removed())
                    .append(",actualKeptTriangles=").append(counts[4]);
            if (reasons.containsKey(entry.getKey())) detail.append(",reason={").append(reasons.get(entry.getKey())).append('}');
        }
        return "status=" + status + " enabled=" + enabled + " ready=" + (enabled && ready && !failed) + " profiles=" + LensGlassData.ROWS.size()
                + " created=" + created + " substitutions=" + substitutions + " extraSubstitutions=" + extraSubstitutions + " warming=" + warming + " prepares=" + prepares + " errors=" + errors
                + " rejected=" + rejected + LensGlassSelection.info() + detail.append(']');
    }

    public static void enable(boolean on) {
        enabled = on;
        if (!on) LensGlassSelection.set(null, null, null);
    }

    @Patch(className = "viewpoint.render.ModelDraws", methodName = "add")
    public static class Add {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(value = 0, readOnly = false) ModelMesh mesh,
                                 @Patch.Argument(1) ModelInstance instance) {
            mesh = LensGlass.mesh(mesh, instance);
        }
    }
}
