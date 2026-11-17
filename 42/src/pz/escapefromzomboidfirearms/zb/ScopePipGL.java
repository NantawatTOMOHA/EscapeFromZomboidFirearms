package pz.escapefromzomboidfirearms.zb;

import java.util.ArrayDeque;
import java.util.Arrays;

import org.lwjgl.opengl.*;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.IShaderProgramListener;
import zombie.core.opengl.ShaderProgram;
import zombie.core.textures.Texture;

/**
 * Full GL boundary around the independent Viewpoint pass, on the render thread.
 *
 * Performance pass 23.10.2026 (user reports of FPS drops and micro-freezes): the full boundary saves and restores the
 * same state as before, but reads the context's limits once and keeps its arrays in a pool (it re-read MAX_* and
 * allocated ~1,500 ints each use). Its sweep stays complete: Viewpoint's own passes bind texture units up to 139 and
 * by computed indices (javap of Viewpoint 0.1.6.1), so no smaller set of units is provable for the scope picture.
 * reticle() is the light boundary of the passes that change only a known handful of bindings (see there).
 * W13 (01.11.2026): the range of an empty indexed buffer slot is not read - restoreIndexed binds such a slot with
 * glBindBufferBase(target, i, 0) and never uses it - so the restored state is the same, with up to 360 fewer queries.
 * macOS (02.11.2026, GLCompat): the boundary keeps only the texture and buffer targets the context has (all of them on
 * GL 4.3, as before) and reads a storage/dispatch/indirect binding or limit only where it exists; sampler and indexed
 * buffer slots are saved and restored only where glGetIntegeri reads them back (the Metal shim returned nothing, and
 * the zeros written back unbound Viewpoint's slots).
 */
final class ScopePipGL implements AutoCloseable {
    private static final int[] ALL_TARGETS = {GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_3D, GL30.GL_TEXTURE_2D_ARRAY,
            GL13.GL_TEXTURE_CUBE_MAP, GL31.GL_TEXTURE_BUFFER};
    private static final int[] ALL_BINDINGS = {GL11.GL_TEXTURE_BINDING_2D, GL12.GL_TEXTURE_BINDING_3D, GL30.GL_TEXTURE_BINDING_2D_ARRAY,
            GL13.GL_TEXTURE_BINDING_CUBE_MAP, GL31.GL_TEXTURE_BINDING_BUFFER};
    private static final int[] ALL_BUFFER_TARGETS = {GL15.GL_ARRAY_BUFFER, GL21.GL_PIXEL_PACK_BUFFER, GL21.GL_PIXEL_UNPACK_BUFFER,
            GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, GL40.GL_DRAW_INDIRECT_BUFFER,
            GL43.GL_DISPATCH_INDIRECT_BUFFER, GL43.GL_SHADER_STORAGE_BUFFER, GL31.GL_UNIFORM_BUFFER};
    private static final int[] ALL_BUFFER_BINDINGS = {GL15.GL_ARRAY_BUFFER_BINDING, GL21.GL_PIXEL_PACK_BUFFER_BINDING, GL21.GL_PIXEL_UNPACK_BUFFER_BINDING,
            GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, GL40.GL_DRAW_INDIRECT_BUFFER_BINDING,
            GL43.GL_DISPATCH_INDIRECT_BUFFER_BINDING, GL43.GL_SHADER_STORAGE_BUFFER_BINDING, GL31.GL_UNIFORM_BUFFER_BINDING};
    /** The targets of ALL_* this context has (every one on GL 4.3+), with the limits. */
    private static int[] targets, bindings, bufferTargets, bufferBindings;
    private static final int[] PIXEL_KEYS = {GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_PIXELS,
            GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SWAP_BYTES, GL11.GL_PACK_LSB_FIRST, GL12.GL_PACK_IMAGE_HEIGHT, GL12.GL_PACK_SKIP_IMAGES,
            GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_PIXELS, GL11.GL_UNPACK_SKIP_ROWS,
            GL11.GL_UNPACK_SWAP_BYTES, GL11.GL_UNPACK_LSB_FIRST, GL12.GL_UNPACK_IMAGE_HEIGHT, GL12.GL_UNPACK_SKIP_IMAGES};
    private static final int[] READ_PACK_KEYS = {GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_PIXELS,
            GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SWAP_BYTES, GL11.GL_PACK_LSB_FIRST};
    /** The limits of the render thread's one context (constant for its life), read at the first full boundary. */
    private static int units = -1, storageSlots, uniformSlots;
    /** Render thread: saved-state arrays of closed boundaries (a boundary inside another takes its own). */
    private static final ArrayDeque<Saved> pool = new ArrayDeque<>();
    /** W13 QA A/B (TWZB.qaPipIndexedLegacy): true reads the range of empty indexed buffer slots too, as before. */
    static volatile boolean emptyRangeReads;
    /** W13 QA (TWZB.qaPipIndexedStats): while recording, each full boundary adds its indexed slots as one batch. */
    static volatile boolean countSlots;
    /** A new epoch at each start and stop: a boundary adds to the record it began in, if that is still the active one. */
    private static volatile long slotEpoch;
    /** The record: boundaries, slots, empty slots, bound slots, range queries; guarded by itself (render and main thread). */
    private static final long[] slotStats = new long[5];

    /** Everything the full boundary saves, sized by the context's limits. */
    private static final class Saved {
        final int[] textures = new int[units * targets.length], samplers = new int[units];
        final int[] buffers = new int[bufferTargets.length], pixelValues = new int[PIXEL_KEYS.length];
        final int[] storage = new int[storageSlots], uniform = new int[uniformSlots];
        final long[] storageStart = new long[storageSlots], storageSize = new long[storageSlots];
        final long[] uniformStart = new long[uniformSlots], uniformSize = new long[uniformSlots];
    }

    final int drawFbo;
    final int readFbo;
    final int[] viewport = new int[4];
    private final int program;
    private final int active;
    private final int vao;
    private final int elementBuffer, renderbuffer;
    private final int arrayBuffer;
    private final int[] unitTextures, unitSamplers;
    private final Saved saved;
    private final boolean light;

    ScopePipGL() {
        light = false;
        drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        elementBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        renderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        arrayBuffer = 0;
        unitTextures = unitSamplers = null;
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        if (units < 0) {
            boolean indexed = GLCompat.indexedReads();
            storageSlots = indexed && GLCompat.storageBuffers() ? GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) : 0;
            uniformSlots = indexed && GLCompat.uniformBuffers() ? GL11.glGetInteger(GL31.GL_MAX_UNIFORM_BUFFER_BINDINGS) : 0;
            int[][] kept = present(ALL_TARGETS, ALL_BINDINGS);
            targets = kept[0];
            bindings = kept[1];
            kept = present(ALL_BUFFER_TARGETS, ALL_BUFFER_BINDINGS);
            bufferTargets = kept[0];
            bufferBindings = kept[1];
            units = GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
        }
        Saved s = pool.poll();
        saved = s != null ? s : new Saved();
        int[] textures = saved.textures;
        for (int i = 0; i < units; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            for (int j = 0; j < bindings.length; j++) {
                textures[i * targets.length + j] = GL11.glGetInteger(bindings[j]);
            }
            saved.samplers[i] = GLCompat.sampler(i);
        }
        GL13.glActiveTexture(active);
        for (int i = 0; i < saved.buffers.length; i++) {
            saved.buffers[i] = GL11.glGetInteger(bufferBindings[i]);
        }
        for (int i = 0; i < PIXEL_KEYS.length; i++) {
            saved.pixelValues[i] = GL11.glGetInteger(PIXEL_KEYS[i]);
        }
        long epoch = countSlots ? slotEpoch : -1;
        int rangeReads = saveIndexed(saved.storage, saved.storageStart, saved.storageSize,
                GL43.GL_SHADER_STORAGE_BUFFER_BINDING, GL43.GL_SHADER_STORAGE_BUFFER_START, GL43.GL_SHADER_STORAGE_BUFFER_SIZE);
        rangeReads += saveIndexed(saved.uniform, saved.uniformStart, saved.uniformSize, GL31.GL_UNIFORM_BUFFER_BINDING,
                GL31.GL_UNIFORM_BUFFER_START, GL31.GL_UNIFORM_BUFFER_SIZE);
        if (epoch >= 0) {
            recordSlots(epoch, saved.storage, saved.uniform, rangeReads);
        }
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL30.glBindVertexArray(0);
    }

    /**
     * Performance pass 23.10.2026: the boundary of a reticle overlay (ReticleRenderer, SightGlass, SightGlassExtras)
     * and of the rangefinder LCD, drawn every first-person frame with a collimator, at the hip too. Those passes change
     * only: the enables, blend and colour/depth masks (pushed here), the current program, the active unit, the
     * 2D textures and samplers of units 0 and 1, the vertex array and the ARRAY_BUFFER binding (saved here), uniforms
     * of their own programs, their own buffer's data and their own vertex array's attributes - no framebuffer,
     * viewport, element/pixel/indexed buffer, renderbuffer or other unit (ReticleRenderer additionally restores all of
     * its own). The inner code sees what it saw inside the full boundary (scissor off, vertex array 0), and close()
     * ends with the same engine cache resets, so the state after it equals the full boundary's - without its sweep of
     * every unit and binding (~1,900 queries; a query can wait for the driver thread).
     */
    static ScopePipGL reticle() {
        return new ScopePipGL(true);
    }

    private ScopePipGL(boolean light) {
        this.light = light;
        drawFbo = readFbo = -1; // not read: the light passes keep the bound framebuffers
        elementBuffer = renderbuffer = 0;
        saved = null;
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        unitTextures = new int[2];
        unitSamplers = new int[2];
        for (int i = 0; i < 2; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            unitTextures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            unitSamplers[i] = GLCompat.sampler(i);
        }
        GL13.glActiveTexture(active);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL30.glBindVertexArray(0);
    }

    /** The targets (and their binding queries) of a list that this context has: {targets, bindings}. */
    private static int[][] present(int[] all, int[] queries) {
        int[] keep = new int[all.length], query = new int[all.length];
        int n = 0;
        for (int i = 0; i < all.length; i++) {
            if (present(all[i])) {
                keep[n] = all[i];
                query[n++] = queries[i];
            }
        }
        return new int[][] {Arrays.copyOf(keep, n), Arrays.copyOf(query, n)};
    }

    private static boolean present(int target) {
        switch (target) {
            case GL30.GL_TEXTURE_2D_ARRAY: return GLCompat.textureArrays();
            case GL31.GL_TEXTURE_BUFFER: return GLCompat.textureBuffers();
            case GL31.GL_COPY_READ_BUFFER: case GL31.GL_COPY_WRITE_BUFFER: return GLCompat.copyBuffers();
            case GL40.GL_DRAW_INDIRECT_BUFFER: return GLCompat.drawIndirect();
            case GL43.GL_DISPATCH_INDIRECT_BUFFER: return GLCompat.dispatchIndirect();
            case GL43.GL_SHADER_STORAGE_BUFFER: return GLCompat.storageBuffers();
            case GL31.GL_UNIFORM_BUFFER: return GLCompat.uniformBuffers();
            default: return true;
        }
    }

    /** Every slot's binding, and the range of a bound one (of every one with emptyRangeReads); returns the range reads. */
    private static int saveIndexed(int[] values, long[] start, long[] size, int binding, int startKey, int sizeKey) {
        boolean emptyRanges = emptyRangeReads;
        int reads = 0;
        for (int i = 0; i < values.length; i++) {
            values[i] = GL30.glGetIntegeri(binding, i);
            // W13: restoreIndexed binds an empty slot with glBindBufferBase(target, i, 0) and never reads its range.
            if (values[i] == 0 && !emptyRanges) {
                start[i] = 0;
                size[i] = 0;
                continue;
            }
            start[i] = GL32.glGetInteger64i(startKey, i);
            size[i] = GL32.glGetInteger64i(sizeKey, i);
            reads += 2;
        }
        return reads;
    }

    /**
     * One boundary's slots counted locally, then added in one batch to the record of its epoch if that is still the
     * active one (a snapshot never sees half of a boundary, a stopped or restarted record never gets a late one).
     */
    private static void recordSlots(long epoch, int[] storage, int[] uniform, int rangeReads) {
        int empty = 0;
        for (int value : storage) {
            empty += value == 0 ? 1 : 0;
        }
        for (int value : uniform) {
            empty += value == 0 ? 1 : 0;
        }
        int slots = storage.length + uniform.length;
        synchronized (slotStats) {
            if (!countSlots || slotEpoch != epoch) {
                return;
            }
            slotStats[0]++;
            slotStats[1] += slots;
            slotStats[2] += empty;
            slotStats[3] += slots - empty;
            slotStats[4] += rangeReads;
        }
    }

    /**
     * W13 QA (TWZB.qaPipIndexedStats): record true starts a new record from 0, false stops recording; either returns the
     * record as one consistent snapshot.
     */
    static String slotStats(boolean record) {
        synchronized (slotStats) {
            slotEpoch++;
            if (record) {
                Arrays.fill(slotStats, 0);
            }
            countSlots = record;
            return "boundaries=" + slotStats[0] + " slots=" + slotStats[1] + " empty=" + slotStats[2] + " bound=" + slotStats[3]
                    + " rangeQueries=" + slotStats[4] + " legacy=" + emptyRangeReads + " recording=" + record;
        }
    }

    private static void restoreIndexed(int target, int[] values, long[] start, long[] size) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] != 0 && size[i] > 0) {
                GL30.glBindBufferRange(target, i, values[i], start[i], size[i]);
            } else {
                GL30.glBindBufferBase(target, i, values[i]);
            }
        }
    }

    /** Sparse QA read into a PBO; preserve the pass's read target and pixel-pack state. */
    static void readCenter(int framebuffer, int picture, int size, int buffer, long offset) {
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int pack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int[] keys = READ_PACK_KEYS;
        int[] values = new int[keys.length];
        for (int i = 0; i < keys.length; i++) {
            values[i] = GL11.glGetInteger(keys[i]);
        }
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
        int readBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        try {
            if (picture != 0) {
                GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, picture, 0);
            }
            if (GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("PIP probe read target incomplete");
            }
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            for (int i = 0; i < keys.length; i++) {
                GL11.glPixelStorei(keys[i], i == 0 ? 1 : 0);
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer);
            GL11.glReadPixels((size - 16) / 2, (size - 16) / 2, 16, 16, GL11.GL_RGBA, GL11.GL_FLOAT, offset);
        } finally {
            if (picture != 0) {
                GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
            }
            GL11.glReadBuffer(readBuffer);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pack);
            for (int i = 0; i < keys.length; i++) {
                GL11.glPixelStorei(keys[i], values[i]);
            }
        }
    }

    @Override
    public void close() {
        if (light) {
            GL11.glPopAttrib();
            for (int i = 1; i >= 0; i--) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, unitTextures[i]);
                GLCompat.restoreSampler(i, unitSamplers[i]);
            }
            GL13.glActiveTexture(active);
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GLCompat.useProgram(program);
            Texture.lastTextureID = -1;
            SpriteRenderer.ringBuffer.restoreBoundTextures = true;
            SpriteRenderer.ringBuffer.restoreVbos = true;
            GLStateRenderThread.restore();
            return;
        }
        GL11.glPopAttrib();
        int[] textures = saved.textures;
        for (int i = 0; i < units; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            for (int j = 0; j < targets.length; j++) {
                GL11.glBindTexture(targets[j], textures[i * targets.length + j]);
            }
            GLCompat.restoreSampler(i, saved.samplers[i]);
        }
        restoreIndexed(GL43.GL_SHADER_STORAGE_BUFFER, saved.storage, saved.storageStart, saved.storageSize);
        restoreIndexed(GL31.GL_UNIFORM_BUFFER, saved.uniform, saved.uniformStart, saved.uniformSize);
        for (int i = 0; i < saved.buffers.length; i++) {
            GL15.glBindBuffer(bufferTargets[i], saved.buffers[i]);
        }
        for (int i = 0; i < PIXEL_KEYS.length; i++) {
            GL11.glPixelStorei(PIXEL_KEYS[i], saved.pixelValues[i]);
        }
        GL13.glActiveTexture(active);
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, elementBuffer);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, renderbuffer);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        GLCompat.useProgram(program);
        Texture.lastTextureID = -1;
        SpriteRenderer.ringBuffer.restoreBoundTextures = true;
        SpriteRenderer.ringBuffer.restoreVbos = true;
        GLStateRenderThread.restore();
        pool.push(saved);
    }

    /**
     * Performance pass 23.10.2026: uniform locations of one linked program, looked up once (glGetUniformLocation by
     * name every frame before). A relink - also one that reuses the program id - clears them through the
     * ShaderProgram's compile listener, so a location always belongs to the program it is used with.
     */
    static final class Uniforms implements IShaderProgramListener {
        private final String[] names;
        private final int[] locations;
        private int program = -1;
        private ShaderProgram listened;

        Uniforms(String... names) {
            this.names = names;
            locations = new int[names.length];
        }

        /** The locations in the order of the names, for this ShaderProgram's current program. */
        int[] of(ShaderProgram shader) {
            listen(shader);
            return of(shader.getShaderID());
        }

        /** Forget the locations whenever this ShaderProgram links again. */
        void listen(ShaderProgram shader) {
            if (shader != listened) {
                if (listened != null) listened.removeCompileListener(this);
                shader.addCompileListener(this);
                listened = shader;
                program = -1;
            }
        }

        /** The locations for a program id (a standalone fixture's program has no ShaderProgram). */
        int[] of(int id) {
            if (id != program) {
                for (int i = 0; i < names.length; i++) {
                    locations[i] = GL20.glGetUniformLocation(id, names[i]);
                }
                program = id;
            }
            return locations;
        }

        @Override
        public void callback(ShaderProgram shader) {
            program = -1;
        }
    }
}
