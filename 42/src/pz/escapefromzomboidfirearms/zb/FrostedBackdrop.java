package pz.escapefromzomboidfirearms.zb;

import org.escapefromzomboidfirearms.realgl.RealGL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

import zombie.core.Core;
import zombie.core.ShaderHelper;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.ShaderProgram;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureFBO;

/**
 * Frosted glass of the Tarkov weapon screens (TW/TarkovUI/Frosted): the game's own frame behind a
 * full-screen screen, blurred and darkened, as Escape from Tarkov's in-raid inventory.
 *
 * The world frame is copied where the game composites it to the screen (PatchComposite, the exit
 * of MultiTextureFBO2.render): with the UI drawn offscreen (uiRenderOffscreen, the default) the UI
 * pass runs before the frame's world, in its own buffer that holds no world, so the copy cannot be
 * taken from the UI. The copy is reduced to half size by the blit and blurred by a dual Kawase
 * chain (1/4, 1/8, 1/16, 1/32 and back up to 1/4, half-float targets). The screen's prerender
 * queues the draw of the result in UI order: opaque (alpha 1), so the UI buffer's premultiplied
 * composite shows the frost instead of the world. Copies are made only while a screen asked for
 * one in the last 300 ms. Without the copy the Lua side draws a plain dark plate.
 */
public final class FrostedBackdrop {
    private FrostedBackdrop() {
    }

    static final int LEVELS = 5;
    static final Level[] levels = new Level[LEVELS];
    static ShaderProgram down, up, comp;
    static int vao, linear;
    static volatile long wantedUntil;
    static volatile boolean ready, failed;
    static volatile long captures, draws;
    static volatile String lastError = "";
    static long seed;

    static final class Level {
        int texture, fbo, width, height;
    }

    /** Main thread (a screen's prerender): queue the frost over (x, y, w, h) of the screen. */
    public static boolean request(double x, double y, double w, double h, float[] look) {
        if (failed) return false;
        wantedUntil = System.nanoTime() + 300_000_000L;
        Core core = Core.getInstance();
        Draw draw = new Draw();
        draw.x = (float) x; draw.y = (float) y; draw.w = (float) w; draw.h = (float) h;
        draw.screenW = core.getScreenWidth(); draw.screenH = core.getScreenHeight();
        draw.look = look;
        SpriteRenderer.instance.drawGeneric(draw);
        return ready;
    }

    /** Main thread, right after the world composite was queued (PatchComposite). */
    public static void captureFrame() {
        if (failed || System.nanoTime() >= wantedUntil) return;
        Core core = Core.getInstance();
        Capture capture = new Capture();
        capture.screenW = core.getScreenWidth();
        capture.screenH = core.getScreenHeight();
        SpriteRenderer.instance.drawGeneric(capture);
    }

    public static String info() {
        return "frost ready=" + ready + " failed=" + failed + " captures=" + captures + " draws=" + draws
                + (lastError.isEmpty() ? "" : " error=" + lastError);
    }

    static void fail(String where, Throwable t) {
        failed = true;
        ready = false;
        lastError = where + ": " + t;
        System.out.println("[TWZB] Frosted backdrop off (" + where + "): " + t);
    }

    /** Render thread: the frost programs, at the first use or ahead of it (ShaderWarmup). */
    static void shaders() {
        if (down == null) {
            down = ShaderWarmup.made(ShaderProgram.createShaderProgram("tw_frost_down", false, false, true));
            up = ShaderWarmup.made(ShaderProgram.createShaderProgram("tw_frost_up", false, false, true));
            comp = ShaderWarmup.made(ShaderProgram.createShaderProgram("tw_frost_comp", false, false, true));
        }
    }

    static void programs() {
        shaders();
        if (!down.isCompiled() || !up.isCompiled() || !comp.isCompiled()) {
            throw new IllegalStateException("tw_frost_down/tw_frost_up/tw_frost_comp did not compile");
        }
        if (vao == 0) {
            vao = ScreenCorners.vertexArray();
            linear = GL33.glGenSamplers();
            GL33.glSamplerParameteri(linear, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL33.glSamplerParameteri(linear, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL33.glSamplerParameteri(linear, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL33.glSamplerParameteri(linear, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        }
    }

    /** Half-float targets at 1/2, 1/4 ... 1/32 of the screen. */
    static void ensure(int screenW, int screenH) {
        for (int i = 0; i < LEVELS; i++) {
            int w = Math.max(1, screenW >> (i + 1)), h = Math.max(1, screenH >> (i + 1));
            Level l = levels[i];
            if (l == null) {
                l = levels[i] = new Level();
                l.texture = GL11.glGenTextures();
                l.fbo = GL30.glGenFramebuffers();
            }
            if (l.width == w && l.height == h) continue;
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, l.texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
            GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GLCompat.texImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, w, h, GL11.GL_RGBA, GL11.GL_FLOAT);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, l.fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, l.texture, 0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("frost target incomplete");
            }
            l.width = w;
            l.height = h;
        }
    }

    static void plain() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
        GL11.glDepthMask(false);
        GL11.glColorMask(true, true, true, true);
        ScreenCorners.bind(vao);
    }

    static void pass(ShaderProgram program, Level from, Level to) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to.fbo);
        GL11.glViewport(0, 0, to.width, to.height);
        program.setVector2("Target", to.width, to.height);
        program.setVector2("HalfPixel", 0.5f / from.width, 0.5f / from.height);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, from.texture);
        GL33.glBindSampler(0, linear);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
    }

    /** The GL state of the sprite renderer around one of our passes (OpticRenderer.Drawer's list). */
    static final class Saved implements AutoCloseable {
        final int drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        final int readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        final int last = TextureFBO.lastID;
        final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        final int vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        final int arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        final int readBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        final int[] viewport = new int[4];

        Saved() {
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_ENABLE_BIT | GL11.GL_VIEWPORT_BIT
                    | GL11.GL_TEXTURE_BIT | GL11.GL_SCISSOR_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        }

        @Override
        public void close() {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL33.glBindSampler(0, 0);
            GL30.glBindVertexArray(vertexArray);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GL11.glReadBuffer(readBuffer);
            TextureFBO.lastID = last;
            GL11.glPopAttrib();
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL14.glBlendEquation(GL14.GL_FUNC_ADD);
            Texture.lastTextureID = -1;
            ShaderHelper.glUseProgramObjectARB(program);
            SpriteRenderer.ringBuffer.restoreBoundTextures = true;
            SpriteRenderer.ringBuffer.restoreVbos = true;
            GLStateRenderThread.restore();
        }
    }

    /** Render thread, after the composite: copy, reduce and blur the frame. */
    static final class Capture extends TextureDraw.GenericDrawer {
        int screenW, screenH;

        @Override
        public void render() {
            if (failed || screenW <= 0 || screenH <= 0) return;
            if (GLCompat.metalPath()) {
                try {
                    shaders();
                    RealGL.frostCapture(screenW, screenH, down, up);
                    captures++;
                    ready = true;
                } catch (Throwable t) {
                    fail("capture", t);
                }
                return;
            }
            try (Saved saved = new Saved()) {
                programs();
                ensure(screenW, screenH);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                // The composite's own target (FBO 0 in the vanilla game, another one under pzopt).
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, saved.drawFbo);
                if (saved.drawFbo == 0) GL11.glReadBuffer(GL11.GL_BACK);
                Level first = levels[0];
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, first.fbo);
                GL30.glBlitFramebuffer(0, 0, screenW, screenH, 0, 0, first.width, first.height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
                plain();
                down.Start();
                down.setSamplerUnit("Input", 0);
                for (int i = 1; i < LEVELS; i++) pass(down, levels[i - 1], levels[i]);
                up.Start();
                up.setSamplerUnit("Input", 0);
                for (int i = LEVELS - 2; i >= 1; i--) pass(up, levels[i + 1], levels[i]);
                captures++;
                ready = true;
            } catch (Throwable t) {
                fail("capture", t);
            }
        }
    }

    /** Render thread, in UI order: the blurred frame over the screen's rectangle, opaque. */
    static final class Draw extends TextureDraw.GenericDrawer {
        float x, y, w, h;
        int screenW, screenH;
        float[] look;

        @Override
        public void render() {
            if (failed || !ready) return;
            float[] given = look != null && look.length >= 8 ? look : new float[] {0.05f, 0.07f, 0.09f, 0.35f, 0.45f, 0.35f, 0.55f, 1f};
            if (GLCompat.metalPath()) {
                try {
                    RealGL.frostDraw(x, y, w, h, screenW, screenH, given, (float) (seed++ % 97), comp);
                    draws++;
                } catch (Throwable t) {
                    fail("draw", t);
                }
                return;
            }
            if (levels[1] == null) return;
            try (Saved saved = new Saved()) {
                programs();
                plain();
                GL11.glViewport(0, 0, screenW, screenH);
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL11.glScissor((int) Math.floor(x), (int) Math.floor(screenH - (y + h)), (int) Math.ceil(w), (int) Math.ceil(h));
                comp.Start();
                comp.setSamplerUnit("Input", 0);
                comp.setVector2("Screen", screenW, screenH);
                float[] l = given;
                comp.setVector4("Tint", l[0], l[1], l[2], l[3]);
                comp.setVector4("Look", l[4], l[5], l[6], (float) (seed++ % 97));
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, levels[1].texture);
                GL33.glBindSampler(0, linear);
                GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
                draws++;
            } catch (Throwable t) {
                fail("draw", t);
            }
        }
    }
}
