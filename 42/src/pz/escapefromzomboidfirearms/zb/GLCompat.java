package pz.escapefromzomboidfirearms.zb;

import java.nio.ByteBuffer;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLCapabilities;

import zombie.core.Core;
import zombie.core.ShaderHelper;

/**
 * GL whose support differs between the contexts the mod meets (macOS player log 1.1.1, 02.11.2026): Windows and Linux
 * give a GL 3.3+ compatibility context with everything; macOS gives PZ an OpenGL 2.1 context ("2.1 Metal"), and the
 * Viewpoint Metal shim (VPMetal, vpshim) answers our GL calls itself - there GL30.glGetIntegeri, GL32.glGetSynci and
 * GL15.glGetBufferSubData were "not implemented, ignored" (they return nothing). Each answer here is read once on the
 * render thread; on a context that has the call our code does exactly what it did before.
 *
 * Player log 03.11.2026 (1.1.3, every scope picture and the weapon builder's backdrop black): VPMetal answers a GL
 * call by its Java overload, and has the overloads Viewpoint itself calls. GL11.glTexImage2D(..., long) and
 * GL15.glBufferData(int, float[], int) were "not implemented, ignored": our render textures had no storage ("draw with
 * no render target") and the corners of our full-screen passes no data. The helpers below call the overload Viewpoint
 * calls - the same GL function with the same arguments, so nothing changes on a real driver; every overload the mod
 * calls is either one of Viewpoint's or listed with its reason in tools/check_vpmetal_gl.py.
 */
final class GLCompat {
    /** -1: not probed yet; 0: glGetIntegeri does not read an indexed binding back; 1: it does. */
    private static int indexedReads = -1;
    private static boolean[] caps;
    private static final int STORAGE = 0, DRAW_INDIRECT = 1, DISPATCH_INDIRECT = 2, COPY_IMAGE = 3, STENCIL_TEXTURING = 4,
            UNIFORM_BUFFERS = 5, COPY_BUFFERS = 6, TEXTURE_BUFFERS = 7, TEXTURE_ARRAYS = 8, SAMPLER_OBJECTS = 9;

    private GLCompat() {
    }

    /**
     * Render thread: whether glGetIntegeri reads an indexed binding back. Probed once with a sampler of our own on unit
     * 0: the unit's sampler is read, ours bound and read back; a context that answers ours is trusted, and unit 0 gets
     * the sampler it had. A context that does not (or has no such call) never has its indexed bindings read or
     * "restored" by us: a value that could not be read is not written back.
     */
    static boolean indexedReads() {
        if (indexedReads < 0) {
            indexedReads = probe() ? 1 : 0;
            if (indexedReads == 0) {
                System.out.println("[TWZB] GL: indexed bindings cannot be read back on this context (glGetIntegeri);"
                        + " samplers go back to none, buffer slots are left as the passes set them");
            }
        }
        return indexedReads == 1;
    }

    private static boolean probe() {
        int probe = 0;
        try {
            int before = GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, 0);
            probe = GL33.glGenSamplers();
            GL33.glBindSampler(0, probe);
            boolean readable = probe != 0 && GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, 0) == probe;
            GL33.glBindSampler(0, readable ? before : 0);
            return readable;
        } catch (Throwable t) {
            return false;
        } finally {
            if (probe != 0) {
                try {
                    GL33.glDeleteSamplers(probe);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** The sampler bound to a texture unit, or -1 when this context cannot read it back. */
    static int sampler(int unit) {
        return indexedReads() ? GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, unit) : -1;
    }

    /**
     * Puts back a sampler read by sampler(unit). One that could not be read (-1) becomes 0, no sampler object: neither
     * PZ (projectzomboid.jar, B42.21) nor Viewpoint (0.1.6.1) binds sampler objects, so 0 is the state before our pass,
     * and a sampler of ours left on the unit would override the filtering and wrap of their textures.
     */
    static void restoreSampler(int unit, int sampler) {
        if (sampler >= 0 || cap(SAMPLER_OBJECTS)) {
            GL33.glBindSampler(unit, Math.max(0, sampler));
        }
    }

    /** An indexed binding (a uniform or storage buffer slot), or -1 when this context cannot read it back. */
    static int indexed(int binding, int index) {
        return indexedReads() ? GL30.glGetIntegeri(binding, index) : -1;
    }

    private static boolean cap(int which) {
        if (caps == null) {
            GLCapabilities c = GL.getCapabilities();
            boolean[] k = new boolean[10];
            k[STORAGE] = c.OpenGL43 || c.GL_ARB_shader_storage_buffer_object;
            k[DRAW_INDIRECT] = c.OpenGL40 || c.GL_ARB_draw_indirect;
            k[DISPATCH_INDIRECT] = c.OpenGL43 || c.GL_ARB_compute_shader;
            k[COPY_IMAGE] = c.OpenGL43 || c.GL_ARB_copy_image;
            k[STENCIL_TEXTURING] = c.OpenGL43 || c.GL_ARB_stencil_texturing;
            k[UNIFORM_BUFFERS] = c.OpenGL31 || c.GL_ARB_uniform_buffer_object;
            k[COPY_BUFFERS] = c.OpenGL31 || c.GL_ARB_copy_buffer;
            k[TEXTURE_BUFFERS] = c.OpenGL31 || c.GL_ARB_texture_buffer_object;
            k[TEXTURE_ARRAYS] = c.OpenGL30 || c.GL_EXT_texture_array;
            k[SAMPLER_OBJECTS] = c.OpenGL33 || c.GL_ARB_sampler_objects;
            caps = k;
        }
        return caps[which];
    }

    /** GL 4.3 / ARB_shader_storage_buffer_object: the storage buffer target, its slots and their limit. */
    static boolean storageBuffers() {
        return cap(STORAGE);
    }

    /** GL 4.0 / ARB_draw_indirect: the draw-indirect buffer target. */
    static boolean drawIndirect() {
        return cap(DRAW_INDIRECT);
    }

    /** GL 4.3 / ARB_compute_shader: the dispatch-indirect buffer target. */
    static boolean dispatchIndirect() {
        return cap(DISPATCH_INDIRECT);
    }

    /** GL 4.3 / ARB_copy_image: glCopyImageSubData. */
    static boolean copyImage() {
        return cap(COPY_IMAGE);
    }

    /** GL 4.3 / ARB_stencil_texturing: GL_DEPTH_STENCIL_TEXTURE_MODE (without it a depth-stencil texture reads depth). */
    static boolean stencilTexturing() {
        return cap(STENCIL_TEXTURING);
    }

    /** GL 3.1 / ARB_uniform_buffer_object: the uniform buffer target and its slots. */
    static boolean uniformBuffers() {
        return cap(UNIFORM_BUFFERS);
    }

    /** GL 3.1 / ARB_copy_buffer: the copy read/write buffer targets. */
    static boolean copyBuffers() {
        return cap(COPY_BUFFERS);
    }

    /** GL 3.1 / ARB_texture_buffer_object: the buffer texture target. */
    static boolean textureBuffers() {
        return cap(TEXTURE_BUFFERS);
    }

    /** GL 3.0 / EXT_texture_array: the 2D array texture target. */
    static boolean textureArrays() {
        return cap(TEXTURE_ARRAYS);
    }

    /** A texture level of the given size without data (glTexImage2D with no pixels, border 0). */
    static void texImage2D(int target, int level, int internalFormat, int width, int height, int format, int type) {
        GL11.glTexImage2D(target, level, internalFormat, width, height, 0, format, type, (ByteBuffer) null);
    }

    /** One new query object. */
    static int genQuery() {
        int[] one = new int[1];
        GL15.glGenQueries(one);
        return one[0];
    }

    /** The names of an array uniform's elements, name[0] .. name[count - 1]. */
    static String[] elements(String name, int count) {
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            names[i] = name + '[' + i + ']';
        }
        return names;
    }

    /**
     * A vec2 array uniform, element by element at the locations of elements(name, count): pairs (x, y) of values from
     * element 0 on, as glUniform2fv(location of element 0, values) sets them.
     */
    static void uniform2(int[] locations, float[] values) {
        for (int i = 0, n = Math.min(locations.length, values.length / 2); i < n; i++) {
            GL20.glUniform2f(locations[i], values[2 * i], values[2 * i + 1]);
        }
    }

    /** -1: not read yet; 1: the shim's path (metalPath). */
    private static volatile int metal = -1;

    /**
     * The mod's passes go the way the Viewpoint Metal shim needs them (TwProgram, MetalFrame, RealGL): VPMetal is loaded.
     * QA without a Mac (TW_METAL_EMULATE=1, set before the game starts): the same paths on the real context of this
     * machine - they draw the same picture there, so a QA run checks everything of them but the shim itself.
     */
    static boolean metalPath() {
        int m = metal;
        if (m < 0) {
            m = metalShim() || "1".equals(System.getenv("TW_METAL_EMULATE")) ? 1 : 0;
            metal = m;
        }
        return m == 1;
    }

    /**
     * Makes a program current after a pass. A program of the shim's is unknown to the real context, where
     * ShaderHelper would send it (PZ's classes are not rewritten); on any other context this is ShaderHelper as before.
     */
    static void useProgram(int program) {
        if (metalShim()) {
            GL20.glUseProgram(program);
        } else {
            ShaderHelper.glUseProgramObjectARB(program);
        }
    }

    /** Whether the Viewpoint Metal shim (VPMetal) is loaded. */
    static boolean metalShim() {
        int known = shim;
        if (known >= 0) {
            return known == 1;
        }
        boolean found = findShim();
        shim = found ? 1 : 0;
        return found;
    }

    private static volatile int shim = -1;

    private static boolean findShim() {
        for (ClassLoader loader : new ClassLoader[] {GLCompat.class.getClassLoader(), Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader()}) {
            if (loader == null) continue;
            try {
                Class.forName("vpshim.Main", false, loader);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** Render thread: one line of the GL environment, for the log. */
    static String environment() {
        String version, glsl;
        try {
            version = GL11.glGetString(GL11.GL_VERSION);
            glsl = GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION);
        } catch (Throwable t) {
            version = glsl = "unreadable (" + t.getClass().getSimpleName() + ")";
        }
        boolean gl21;
        try {
            gl21 = Core.getInstance().getUseOpenGL21();
        } catch (Throwable t) {
            gl21 = false;
        }
        return "GL_VERSION=" + version + " GLSL=" + glsl + " useOpenGL21=" + gl21 + " vpshim=" + metalShim() + " metalPath=" + metalPath();
    }
}
