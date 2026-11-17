package org.escapefromzomboidfirearms.realgl;

import java.nio.ByteBuffer;

import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.EXTFramebufferBlit;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GLCapabilities;

import zombie.core.ShaderHelper;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.ShaderProgram;
import zombie.core.textures.IGLFramebufferObject;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureFBO;

/**
 * GL of the game's own context, for the parts of the mod that belong to it while the Viewpoint Metal shim (VPMetal)
 * answers the GL calls of the mod's classes.
 *
 * The shim rewrites the classes of an add-on's JAR except those under a few package roots, "org/" among them
 * (vpshim.Rewriter.NOT_ADD_ONS): this package keeps the real context. That context is OpenGL 2.1 on macOS, so what is here
 * is GL 2.1 only: the framebuffers go through PZ's own dispatch (TextureFBO.getFuncs), the textures are RGBA8, and a
 * full-screen quad has no vertex array. tools/check_vpmetal_programs.py keeps this file to that.
 *
 * Frosted backdrop (FrostedBackdrop, player logs 03.11 and 04.11.2026, "the weapon builder is black"): the frame is copied
 * from the real context, where the game composites it - also Viewpoint's, which the shim hands over before the composite.
 * Through the shim that copy read the shim's other screen texture, never drawn, and the glPopAttrib after it showed that
 * texture over the world (vpshim.gl.GlDraw.glBlitFramebuffer, Present.screen, Present.viewpointFrameDone): a black world
 * behind the builder.
 *
 * The isometric passes (OpticRenderer, HeatRenderer, ModelHeat, VehicleHeat, MuzzleFx.Drawer, MuzzleHeatModel.drawIso) belong
 * to the real context too: their programs, framebuffers and model draws are PZ's. They call GL as RealGL.glX, the static
 * method this class inherits from GL21. The class file then names this class as the owner of the call, and the shim redirects
 * only owners under org/lwjgl/opengl/ (vpshim.Rewriter.redirects), whatever package the caller is in; on every other context
 * it is the same call. A call newer than GL 2.1 does not compile that way: framebuffers go through the methods below.
 */
public final class RealGL extends GL21 {
    private static final int LEVELS = 5;
    private static final int FRAMEBUFFER_BINDING = 36006;
    // GL 3.0 and EXT_framebuffer_blit have the same numbers.
    private static final int READ_FRAMEBUFFER = 36008, DRAW_FRAMEBUFFER = 36009, READ_FRAMEBUFFER_BINDING = 36010;
    private static final int[] textures = new int[LEVELS], framebuffers = new int[LEVELS], widths = new int[LEVELS], heights = new int[LEVELS];
    private static int copy, copyWidth, copyHeight;
    /** -1: not read yet; 1: the context binds separate framebuffers for drawing and reading, and can copy between them. */
    private static int separate = -1;

    private RealGL() {
    }

    /** Render thread: GL 3.0, or macOS's OpenGL 2.1 context with its extension. */
    private static boolean separateFramebuffers() {
        if (separate < 0) {
            GLCapabilities caps = GL.getCapabilities();
            separate = caps.OpenGL30 || caps.GL_ARB_framebuffer_object || caps.GL_EXT_framebuffer_blit ? 1 : 0;
        }
        return separate == 1;
    }

    /** The framebuffer drawn to (GL_FRAMEBUFFER_BINDING: the same number on every context). */
    public static int drawFramebuffer() {
        return GL11.glGetInteger(FRAMEBUFFER_BINDING);
    }

    /** The framebuffer read from: the one drawn to on a context without separate ones. */
    public static int readFramebuffer() {
        return separateFramebuffers() ? GL11.glGetInteger(READ_FRAMEBUFFER_BINDING) : drawFramebuffer();
    }

    /** Binds a framebuffer for drawing and reading. */
    public static void bindFramebuffer(int framebuffer) {
        IGLFramebufferObject funcs = TextureFBO.getFuncs();
        funcs.glBindFramebuffer(funcs.GL_FRAMEBUFFER(), framebuffer);
    }

    /** Binds a framebuffer for drawing (for both on a context without separate ones). */
    public static void bindDrawFramebuffer(int framebuffer) {
        if (separateFramebuffers()) {
            TextureFBO.getFuncs().glBindFramebuffer(DRAW_FRAMEBUFFER, framebuffer);
        } else {
            bindFramebuffer(framebuffer);
        }
    }

    /** Binds a framebuffer for reading (nothing on a context without separate ones: it reads what it draws). */
    public static void bindReadFramebuffer(int framebuffer) {
        if (separateFramebuffers()) {
            TextureFBO.getFuncs().glBindFramebuffer(READ_FRAMEBUFFER, framebuffer);
        }
    }

    /**
     * The depth of a rectangle from the read framebuffer to the same rectangle of the draw framebuffer; false where the
     * context cannot copy. ARBFramebufferObject's call is GL 3.0's (GL30C.glBlitFramebuffer).
     */
    public static boolean blitDepth(int x0, int y0, int x1, int y1) {
        GLCapabilities caps = GL.getCapabilities();
        if (caps.OpenGL30 || caps.GL_ARB_framebuffer_object) {
            ARBFramebufferObject.glBlitFramebuffer(x0, y0, x1, y1, x0, y0, x1, y1, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
            return true;
        }
        if (caps.GL_EXT_framebuffer_blit) {
            EXTFramebufferBlit.glBlitFramebufferEXT(x0, y0, x1, y1, x0, y0, x1, y1, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
            return true;
        }
        return false;
    }

    /** Render thread: the whole of a real buffer object into data, from its position to its limit. */
    public static void readBuffer(int buffer, ByteBuffer data) {
        int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, data);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
    }

    /** A texture of the size without data, filtered linearly and clamped; the texture stays bound. */
    private static void storage(int texture, int width, int height) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
    }

    private static void ensure(int screenW, int screenH) {
        IGLFramebufferObject funcs = TextureFBO.getFuncs();
        if (copy == 0) {
            copy = GL11.glGenTextures();
        }
        if (copyWidth != screenW || copyHeight != screenH) {
            storage(copy, screenW, screenH);
            copyWidth = screenW;
            copyHeight = screenH;
        }
        for (int i = 0; i < LEVELS; i++) {
            int w = Math.max(1, screenW >> (i + 1)), h = Math.max(1, screenH >> (i + 1));
            if (textures[i] == 0) {
                textures[i] = GL11.glGenTextures();
                framebuffers[i] = funcs.glGenFramebuffers();
            }
            if (widths[i] == w && heights[i] == h) continue;
            storage(textures[i], w, h);
            funcs.glBindFramebuffer(funcs.GL_FRAMEBUFFER(), framebuffers[i]);
            funcs.glFramebufferTexture2D(funcs.GL_FRAMEBUFFER(), funcs.GL_COLOR_ATTACHMENT0(), GL11.GL_TEXTURE_2D, textures[i], 0);
            if (funcs.glCheckFramebufferStatus(funcs.GL_FRAMEBUFFER()) != funcs.GL_FRAMEBUFFER_COMPLETE()) {
                throw new IllegalStateException("frost target incomplete");
            }
            widths[i] = w;
            heights[i] = h;
        }
    }

    /** The state of the sprite renderer around a pass, on the real context. */
    private static final class Saved implements AutoCloseable {
        final int framebuffer = GL11.glGetInteger(FRAMEBUFFER_BINDING);
        final int last = TextureFBO.lastID;
        final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);

        Saved() {
            GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_ENABLE_BIT | GL11.GL_VIEWPORT_BIT
                    | GL11.GL_TEXTURE_BIT | GL11.GL_SCISSOR_BIT | GL11.GL_STENCIL_BUFFER_BIT);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDepthMask(false);
            GL11.glColorMask(true, true, true, true);
        }

        @Override
        public void close() {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            IGLFramebufferObject funcs = TextureFBO.getFuncs();
            funcs.glBindFramebuffer(funcs.GL_FRAMEBUFFER(), framebuffer);
            TextureFBO.lastID = last;
            GL11.glPopAttrib();
            Texture.lastTextureID = -1;
            ShaderHelper.glUseProgramObjectARB(program);
            SpriteRenderer.ringBuffer.restoreBoundTextures = true;
            SpriteRenderer.ringBuffer.restoreVbos = true;
            GLStateRenderThread.restore();
        }
    }

    /** The whole target as a strip of four corners: aCorner of our full-screen vertex shaders, without a vertex array. */
    private static void corners(ShaderProgram program) {
        int at = GL20.glGetAttribLocation(program.getShaderID(), "aCorner");
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        corner(at, 0f, 0f);
        corner(at, 1f, 0f);
        corner(at, 0f, 1f);
        corner(at, 1f, 1f);
        GL11.glEnd();
    }

    /** Attribute 0 alone ends a vertex between glBegin and glEnd; another attribute needs glVertex for that. */
    private static void corner(int attribute, float x, float y) {
        if (attribute > 0) {
            GL20.glVertexAttrib2f(attribute, x, y);
            GL11.glVertex2f(x, y);
        } else {
            GL20.glVertexAttrib2f(0, x, y);
        }
    }

    private static void pass(ShaderProgram program, int from, int fromWidth, int fromHeight, int to) {
        IGLFramebufferObject funcs = TextureFBO.getFuncs();
        funcs.glBindFramebuffer(funcs.GL_FRAMEBUFFER(), framebuffers[to]);
        GL11.glViewport(0, 0, widths[to], heights[to]);
        program.setVector2("Target", widths[to], heights[to]);
        program.setVector2("HalfPixel", 0.5f / fromWidth, 0.5f / fromHeight);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, from);
        corners(program);
    }

    /** Render thread, after the composite: the frame of the real context reduced and blurred as FrostedBackdrop.Capture does. */
    public static void frostCapture(int screenW, int screenH, ShaderProgram down, ShaderProgram up) {
        try (Saved saved = new Saved()) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            ensure(screenW, screenH);
            IGLFramebufferObject funcs = TextureFBO.getFuncs();
            funcs.glBindFramebuffer(funcs.GL_FRAMEBUFFER(), saved.framebuffer);
            // As FrostedBackdrop.Capture: framebuffer 0 reads the frame from the back buffer, whatever was read last
            // (QA 04.11.2026: without this the copy was black and the backdrop only its dark tint).
            if (saved.framebuffer == 0) GL11.glReadBuffer(GL11.GL_BACK);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, copy);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, screenW, screenH);
            down.Start();
            down.setSamplerUnit("Input", 0);
            pass(down, copy, screenW, screenH, 0);
            for (int i = 1; i < LEVELS; i++) {
                pass(down, textures[i - 1], widths[i - 1], heights[i - 1], i);
            }
            up.Start();
            up.setSamplerUnit("Input", 0);
            for (int i = LEVELS - 2; i >= 1; i--) {
                pass(up, textures[i + 1], widths[i + 1], heights[i + 1], i);
            }
        }
    }

    /** Render thread, in UI order: the blurred frame over the rectangle, opaque, as FrostedBackdrop.Draw does. */
    public static void frostDraw(float x, float y, float w, float h, int screenW, int screenH, float[] look, float seed, ShaderProgram comp) {
        if (textures[1] == 0) {
            return;
        }
        try (Saved saved = new Saved()) {
            GL11.glViewport(0, 0, screenW, screenH);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor((int) Math.floor(x), (int) Math.floor(screenH - (y + h)), (int) Math.ceil(w), (int) Math.ceil(h));
            comp.Start();
            comp.setSamplerUnit("Input", 0);
            comp.setVector2("Screen", screenW, screenH);
            comp.setVector4("Tint", look[0], look[1], look[2], look[3]);
            comp.setVector4("Look", look[4], look[5], look[6], seed);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[1]);
            corners(comp);
        }
    }
}
