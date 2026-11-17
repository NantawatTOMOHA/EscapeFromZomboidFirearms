package pz.escapefromzomboidfirearms.zb;

import java.lang.reflect.Method;

import org.escapefromzomboidfirearms.realgl.RealGL;

import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.ShaderHelper;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.MatrixStack;
import zombie.core.opengl.ShaderProgram;
import zombie.core.opengl.VBORenderer;
import zombie.core.textures.MultiTextureFBO2;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureFBO;
import zombie.iso.IsoCamera;

/**
 * The scope image, drawn after the game composites the world frame to the
 * screen (MultiTextureFBO2.render, see PatchComposite). The image is computed
 * from the raw world frame (before the game's screen shader) into an image
 * buffer of the world frame's size (pass A: Tarkov thermal or night vision
 * shader), then laid over the screen inside the vision cone (pass B). The
 * thermal image is renewed at the scope's own frame rate (StuckFPSUtilities);
 * in between, pass B reprojects the last image to the present camera through
 * the floor plane (exact: a pan or zoom of the iso camera moves every height
 * alike), so a zoom or a camera move does not shift it off the world - only
 * what moves in the scene waits for the next scope frame.
 * Heat of bodies comes from the heat buffer (HeatRenderer).
 */
public final class OpticRenderer {
    private static final int GL_QUADS = 7;
    private static Method renderCursor;
    // Drawers are pooled: the render thread may still draw the previous frame's
    // while the main thread fills the next.
    private static final java.util.ArrayDeque<Drawer> pool = new java.util.ArrayDeque<>();
    // Main-thread textures: palettes and noise of the Tarkov thermal shader.
    private static final Texture[] ramps = new Texture[4];
    private static Texture noise;

    public static volatile long frames;
    /** Composites skipped in Viewpoint's perspective view (QA). */
    public static volatile long vpSkipped;
    /**
     * QA probe of camera sync: largest difference seen between the camera this
     * part maps the image with and the world frame's own (main thread:
     * IsoCamera.frameState; render thread: the render state's PlayerCamera).
     */
    public static final float[] probe = new float[8];
    public static volatile long probeFrames;
    /** QA: composites with no world render since the previous one, and the longest run of them. */
    public static volatile long staleWorld, staleRun, staleRunMax;
    /** QA: capture the frame right after the scope image is laid over it (the back buffer, what is presented). */
    public static volatile String captureName;
    /** QA: the capture also dumps the world frame and the heat buffer. */
    static volatile boolean captureDump;
    private static long lastWorldFrames = -1;
    public static volatile long passes;
    public static volatile long thermalFrames;
    /** QA: composites that took the muzzle effects' field buffer into the mask. */
    public static volatile long fieldComposites;
    public static volatile int errors;
    public static volatile String lastError = "";

    private OpticRenderer() {
    }

    /** One player's frame: its scope view and camera, captured on the main thread. */
    static final class Slot {
        OpticState.View view;
        int vx, vy, w, h, left, top;
        float zoom, offX, offY;
        // world floor (x, y) at the player's level from world-frame texel (tx, ty)
        float mxA, mxB, mxC, myA, myB, myC;
        // the lens centre's texel (inverse of the map above)
        float lensX, lensY;
        // the frame whose muzzle effect field buffer (MuzzleFx) belongs to this composite
        int fieldStamp;
    }

    static Texture texture(String name, int flags) {
        return Texture.getSharedTexture("media/textures/" + name + ".png", flags);
    }

    /** Main thread, right after the composite was queued. */
    public static void queue() {
        TWZB.compositeCount++;
        VehicleHeat.tick();
        long wf = ModelHeat.worldFrames;
        if (wf == lastWorldFrames) {
            staleWorld++;
            staleRun++;
            staleRunMax = Math.max(staleRunMax, staleRun);
        } else {
            staleRun = 0;
        }
        lastWorldFrames = wf;
        MultiTextureFBO2 buffer = Core.getInstance().offscreenBuffer;
        if (buffer == null || buffer.current == null) {
            return;
        }
        // Viewpoint's perspective frame: the texel -> floor map and the cone are isometric, the
        // image would not be where the world is. Not shown there; the perspective devices are
        // VpSensor's (scope picture, 1x eyepiece; the head device queued here, over the composite).
        if (ViewpointBridge.perspective()) {
            vpSkipped++;
            ReticleRendererRangefinder.queue();
            VpSensor.queueHead();
            return;
        }
        // Viewpoint can keep rendering after our guarded bridge is disabled.
        // The isometric floor cone must never be composited over that view.
        if (TWZB.vpViewActive()) {
            vpSkipped++;
            return;
        }
        Drawer drawer;
        synchronized (pool) {
            drawer = pool.isEmpty() ? new Drawer() : pool.pop();
        }
        int count = 0;
        for (int p = 0; p < 4; p++) {
            OpticState.View view = OpticState.views[p];
            IsoPlayer player = IsoPlayer.players[p];
            Slot s = drawer.slots[p];
            s.view = null;
            if (view == null || view.kind == 0 || player == null) {
                continue;
            }
            int w = IsoCamera.getScreenWidth(p), h = IsoCamera.getScreenHeight(p);
            s.view = view;
            s.fieldStamp = IsoCamera.frameState.frameCount;
            s.w = w;
            s.h = h;
            s.left = IsoCamera.getScreenLeft(p);
            s.top = IsoCamera.getScreenTop(p);
            s.vx = w * (p % 2);
            s.vy = p >= 2 ? h : 0;
            s.zoom = Core.getInstance().getZoom(p);
            s.offX = IsoCamera.getOffX(p);
            s.offY = IsoCamera.getOffY(p);
            // texel -> iso screen units (IsoUtils.X/YToScreen minus the camera offset):
            //   x - y = (tx - vx) * zoom / (32 ts) + offX / (32 ts)
            //   x + y = (vy + h - ty) * zoom / (16 ts) + (offY + z * 96 ts) / (16 ts)
            float ts = Core.tileScale;
            float k1 = s.zoom / (32f * ts), c1 = (s.offX - s.vx * s.zoom) / (32f * ts);
            float k2 = s.zoom / (16f * ts), c2 = ((s.vy + h) * s.zoom + s.offY + view.pz * 96f * ts) / (16f * ts);
            s.mxA = 0.5f * k1;
            s.mxB = -0.5f * k2;
            s.mxC = 0.5f * (c1 + c2);
            s.myA = -0.5f * k1;
            s.myB = -0.5f * k2;
            s.myC = 0.5f * (c2 - c1);
            if (IsoCamera.frameState.playerIndex == p) {
                probe[0] = Math.max(probe[0], Math.abs(s.zoom - IsoCamera.frameState.zoom));
                probe[1] = Math.max(probe[1], Math.abs(s.offX - IsoCamera.frameState.offX));
                probe[2] = Math.max(probe[2], Math.abs(s.offY - IsoCamera.frameState.offY));
            }
            float det = s.mxA * s.myB - s.mxB * s.myA;
            float dx = view.lx - s.mxC, dy = view.ly - s.myC;
            s.lensX = (s.myB * dx - s.mxB * dy) / det;
            s.lensY = (s.mxA * dy - s.myA * dx) / det;
            count++;
        }
        if (count == 0) {
            synchronized (pool) {
                pool.push(drawer);
            }
            return;
        }
        for (int i = 0; i < 4; i++) {
            if (ramps[i] == null) {
                ramps[i] = texture("TW_ThermalRamp_" + i, 32);
            }
        }
        if (noise == null) {
            noise = texture("TW_ThermalNoise", 0);
        }
        drawer.world = buffer.getTexture(0);
        drawer.screenW = Core.width;
        drawer.screenH = Core.height;
        drawer.heat = HeatRenderer.capture(drawer.slots);
        SpriteRenderer.instance.drawGeneric(drawer);
        frames++;
        // The aiming reticle and cursor were drawn before this pass: draw them
        // again over the scope image.
        try {
            if (renderCursor == null) {
                renderCursor = MultiTextureFBO2.class.getDeclaredMethod("renderCursor", IsoPlayer.class);
                renderCursor.setAccessible(true);
            }
            for (int p = 0; p < 4; p++) {
                if (drawer.slots[p].view != null && IsoPlayer.players[p] != null) {
                    renderCursor.invoke(null, IsoPlayer.players[p]);
                }
            }
        } catch (Throwable t) {
            fail("cursor", t);
        }
    }

    static void fail(String where, Throwable t) {
        errors++;
        String message = where + ": " + t;
        if (!message.equals(lastError)) {
            lastError = message;
            System.out.println("[TWZB] optic " + message);
        }
    }

    // Render-thread resources.
    static ShaderProgram thermal, nightVision, composite;

    /** Render thread: the scope sensor programs, at the first use or ahead of it (ShaderWarmup). */
    static void shaders() {
        if (thermal == null) {
            thermal = ShaderWarmup.made(ShaderProgram.createShaderProgram("tw_thermal", false, false, true));
            nightVision = ShaderWarmup.made(ShaderProgram.createShaderProgram("tw_nightvision", false, false, true));
            composite = ShaderWarmup.made(ShaderProgram.createShaderProgram("tw_optic_composite", false, false, true));
        }
    }
    static TextureFBO image;
    static final long[] lastImage = new long[4];
    static final OpticState.View[] imageView = new OpticState.View[4];
    /** Per player: the texel -> world floor map and view rectangle the image was drawn with. */
    static final float[][] imageMap = new float[4][10];
    /** Warm things standing in the field show whole up to this height, in levels (2.449 m): 6.5 m. */
    static final float RISE_HEAT = 6.5f / 2.44949f;
    static long seed;

    /** One frame of the scope image: filled on the main thread, drawn on the render thread. */
    static final class Drawer extends TextureDraw.GenericDrawer {
        final Slot[] slots = {new Slot(), new Slot(), new Slot(), new Slot()};
        Texture world;
        int screenW, screenH;
        HeatRenderer.Frame heat;

        @Override
        public void render() {
            // A drawer may already be queued when the bridge changes state.
            if (TWZB.vpViewActive()) {
                vpSkipped++;
                ModelHeat.frameDone();
                return;
            }
            int prevFbo = RealGL.drawFramebuffer();
            int prevLast = TextureFBO.lastID;
            int prevProgram = RealGL.glGetInteger(RealGL.GL_CURRENT_PROGRAM);
            int[] viewport = new int[4];
            RealGL.glGetIntegerv(RealGL.GL_VIEWPORT, viewport);
            RealGL.glPushAttrib(RealGL.GL_COLOR_BUFFER_BIT | RealGL.GL_DEPTH_BUFFER_BIT | RealGL.GL_ENABLE_BIT | RealGL.GL_VIEWPORT_BIT
                    | RealGL.GL_TEXTURE_BIT | RealGL.GL_SCISSOR_BIT | RealGL.GL_STENCIL_BUFFER_BIT);
            MatrixStack projection = Core.getInstance().projectionMatrixStack;
            MatrixStack modelView = Core.getInstance().modelViewMatrixStack;
            try {
                draw(prevFbo, projection, modelView);
                capture(prevFbo, viewport, world);
            } catch (Throwable t) {
                fail("render", t);
            } finally {
                for (int unit = 3; unit >= 1; unit--) {
                    RealGL.glActiveTexture(RealGL.GL_TEXTURE0 + unit);
                    RealGL.glBindTexture(RealGL.GL_TEXTURE_2D, 0);
                }
                RealGL.glActiveTexture(RealGL.GL_TEXTURE0);
                RealGL.bindFramebuffer(prevFbo);
                TextureFBO.lastID = prevLast;
                RealGL.glPopAttrib();
                RealGL.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
                RealGL.glBlendEquation(RealGL.GL_FUNC_ADD);
                Texture.lastTextureID = -1;
                ShaderHelper.glUseProgramObjectARB(prevProgram);
                SpriteRenderer.ringBuffer.restoreBoundTextures = true;
                SpriteRenderer.ringBuffer.restoreVbos = true;
                GLStateRenderThread.restore();
            }
        }

        private void draw(int screenFbo, MatrixStack projection, MatrixStack modelView) {
            shaders();
            if (!thermal.isCompiled() || !nightVision.isCompiled() || !composite.isCompiled()) {
                throw new IllegalStateException("scope shaders did not compile (tw_thermal/tw_nightvision/tw_optic_composite)");
            }
            if (world == null || !world.isReady()) {
                return;
            }
            int whw = world.getWidthHW(), hhw = world.getHeightHW();
            if (image == null || image.getWidth() != whw || image.getHeight() != hhw) {
                if (image != null) {
                    image.destroy();
                }
                image = new TextureFBO(new Texture(whw, hhw, 16), false);
                java.util.Arrays.fill(imageView, null);
            }
            try {
                zombie.core.sprite.SpriteRenderState state = SpriteRenderer.instance.getRenderingState();
                for (int p = 0; p < 4; p++) {
                    if (slots[p].view != null && state != null && state.playerCamera[p] != null) {
                        zombie.iso.PlayerCamera c = state.playerCamera[p];
                        probe[3] = Math.max(probe[3], Math.abs(slots[p].zoom - c.zoom));
                        probe[4] = Math.max(probe[4], Math.abs(slots[p].offX - c.getOffX()));
                        probe[5] = Math.max(probe[5], Math.abs(slots[p].offY - c.getOffY()));
                        probeFrames++;
                    }
                }
            } catch (Throwable t) {
                fail("probe", t);
            }
            HeatRenderer.render(heat, slots, whw, hhw, projection, modelView);
            RealGL.glDisable(RealGL.GL_SCISSOR_TEST);
            RealGL.glDisable(RealGL.GL_STENCIL_TEST);
            RealGL.glDisable(RealGL.GL_DEPTH_TEST);
            long now = System.nanoTime();
            VBORenderer vbo = VBORenderer.getInstance();
            // Pass A: the scope image into the image buffer (world-frame texels).
            for (int p = 0; p < 4; p++) {
                Slot s = slots[p];
                OpticState.View v = s.view;
                if (v == null) {
                    continue;
                }
                boolean thermalView = v.kind == OpticState.THERMAL;
                boolean fresh = imageView[p] == null || imageView[p].kind != v.kind || imageView[p].palette != v.palette;
                boolean due = fresh || !thermalView || v.fps <= 0 || now - lastImage[p] >= (long) (1e9 / v.fps);
                if (!due) {
                    continue;
                }
                lastImage[p] = now;
                imageView[p] = v;
                float[] m = imageMap[p];
                m[0] = s.mxA; m[1] = s.mxB; m[2] = s.mxC; m[3] = s.myA; m[4] = s.myB; m[5] = s.myC;
                m[6] = s.vx; m[7] = s.vy; m[8] = s.w; m[9] = s.h;
                seed++;
                image.startDrawing(false, false);
                RealGL.glViewport(s.vx, s.vy, s.w, s.h);
                // No motion blur (MotionBlurUtilities): each scope frame stands alone.
                RealGL.glDisable(RealGL.GL_BLEND);
                ShaderProgram program = thermalView ? thermal : nightVision;
                program.Start();
                program.setSamplerUnit("WorldTex", 0);
                program.setSamplerUnit("HeatTex", 1);
                program.setSamplerUnit("RampTex", 2);
                program.setSamplerUnit("NoiseTex", 3);
                program.setVector2("WorldSize", whw, hhw);
                program.setVector3("MapX", s.mxA, s.mxB, s.mxC);
                program.setVector3("MapY", s.myA, s.myB, s.myC);
                program.setVector4("Cone", v.px, v.py, v.base, v.half);
                program.setVector4("Thermal", v.coef, v.minimum, v.shift, v.depthFade);
                program.setVector4("Input", v.env, v.gain, v.noise * 0.785f, (float) (seed % 9973));
                program.setValue("EnvCap", v.envCap);
                program.setVector4("Pixel", v.pixelBlocks, v.pixelAlpha, 0f, 0f);
                program.setVector4("NightVision", v.nvR * v.nvIntensity * 0.45f, v.nvG * v.nvIntensity * 0.45f,
                        v.nvB * v.nvIntensity * 0.45f, v.nvGain);
                program.setVector4("Viewport", s.vx, s.vy, s.w, s.h);
                program.setValue("NvNoise", v.nvNoise * 0.785f);
                // Bloom radius: BloomOptimized.BlurSize over the scope's lens (2/5 of the view height).
                program.setVector4("Bloom", v.bloomIntensity, v.bloomThreshold, v.bloomBlur * s.h / 270f, 0f);
                // Chromatic aberration about the lens centre; the lens spans the view height.
                program.setVector4("Lens", s.lensX, s.lensY, s.h, v.aberration);
                bind(1, HeatRenderer.texture());
                bind(2, thermalView ? ramps[Math.max(0, Math.min(3, v.palette))] : null);
                bind(3, noise);
                RealGL.glActiveTexture(RealGL.GL_TEXTURE0);
                projection.push(projection.alloc().setOrtho2D(0, s.w, s.h, 0));
                modelView.push(modelView.alloc().identity());
                vbo.startRun(vbo.formatPositionColorUv);
                vbo.setMode(GL_QUADS);
                vbo.setDepthTest(false);
                vbo.setTextureID(world.getTextureId());
                vbo.setShaderProgram(program);
                vbo.addQuad(0, 0, 0, 0, s.w, s.h, 1, 1, 0, 1, 1, 1, 1);
                vbo.endRun();
                vbo.flush();
                projection.pop();
                modelView.pop();
                image.endDrawing();
                if (thermalView) {
                    thermalFrames++;
                }
                passes++;
            }
            ModelHeat.frameDone();
            // Pass B: the image over the screen inside the cone.
            RealGL.bindFramebuffer(screenFbo);
            RealGL.glViewport(0, 0, screenW, screenH);
            RealGL.glDisable(RealGL.GL_DEPTH_TEST);
            RealGL.glEnable(RealGL.GL_BLEND);
            RealGL.glBlendFuncSeparate(RealGL.GL_SRC_ALPHA, RealGL.GL_ONE_MINUS_SRC_ALPHA, RealGL.GL_ZERO, RealGL.GL_ONE);
            Texture imageTexture = (Texture) image.getTexture();
            for (int p = 0; p < 4; p++) {
                Slot s = slots[p];
                OpticState.View v = s.view;
                if (v == null || imageView[p] == null) {
                    continue;
                }
                composite.Start();
                composite.setSamplerUnit("ImageTex", 0);
                composite.setVector2("WorldSize", whw, hhw);
                composite.setVector3("MapX", s.mxA, s.mxB, s.mxC);
                composite.setVector3("MapY", s.myA, s.myB, s.myC);
                composite.setVector4("Cone", v.px, v.py, v.base, v.half);
                composite.setValue("Edge", v.edge);
                // world floor -> texel of the image as it was drawn (inverse of its map)
                float[] m = imageMap[p];
                float det = m[0] * m[4] - m[1] * m[3];
                if (Math.abs(det) < 1e-12f) {
                    continue;
                }
                composite.setVector3("ImageX", m[4] / det, -m[1] / det, (m[1] * m[5] - m[4] * m[2]) / det);
                composite.setVector3("ImageY", -m[3] / det, m[0] / det, (m[3] * m[2] - m[0] * m[5]) / det);
                composite.setVector4("ImageView", m[6], m[7], m[8], m[9]);
                composite.setValue("DebugMix", v.debugMix);
                // The field is the cone on the floor; warm things standing in it
                // (bodies, flames, the hot gas above a fire) show whole.
                boolean warmView = v.kind == OpticState.THERMAL;
                composite.setSamplerUnit("HeatTex", 1);
                composite.setValue("Rise", warmView ? RISE_HEAT : 0f);
                bind(1, warmView ? HeatRenderer.texture() : null);
                // Muzzle effects standing in the field above the cone (MuzzleFx field buffer, this frame's).
                Texture field = MuzzleFx.fieldDrawn[p] == s.fieldStamp ? MuzzleFx.fieldTexture() : null;
                composite.setSamplerUnit("FieldTex", 2);
                composite.setValue("FieldOn", field != null ? 1f : 0f);
                bind(2, field);
                if (field != null) {
                    fieldComposites++;
                }
                RealGL.glActiveTexture(RealGL.GL_TEXTURE0);
                projection.push(projection.alloc().setOrtho2D(0, screenW, screenH, 0));
                modelView.push(modelView.alloc().identity());
                vbo.startRun(vbo.formatPositionColorUv);
                vbo.setMode(GL_QUADS);
                vbo.setDepthTest(false);
                vbo.setTextureID(imageTexture.getTextureId());
                vbo.setShaderProgram(composite);
                // Texture.rendershader2 mapping of the game's own composite.
                float u0 = s.left / (float) whw, u1 = (s.left + s.w) / (float) whw;
                float vTop = (s.top + s.h) / (float) hhw, vBottom = s.top / (float) hhw;
                vbo.addQuad(s.left, s.top, u0, vTop, s.left + s.w, s.top + s.h, u1, vBottom, 0, 1, 1, 1, 1);
                vbo.endRun();
                vbo.flush();
                projection.pop();
                modelView.pop();
            }
        }

        /** QA: the screen as composited now, to Screenshots/TW_Capture_<name>.png. */
        private static void capture(int fbo, int[] viewport, Texture world) {
            String name = captureName;
            if (name == null) {
                return;
            }
            captureName = null;
            int w = viewport[2], h = viewport[3];
            zombie.core.utils.NativeImage image = zombie.core.utils.NativeImage.allocate(w, h);
            try {
                RealGL.bindReadFramebuffer(fbo);
                if (fbo == 0) {
                    RealGL.glReadBuffer(RealGL.GL_BACK);
                }
                RealGL.glPixelStorei(RealGL.GL_PACK_ALIGNMENT, 1);
                RealGL.glReadPixels(viewport[0], viewport[1], w, h, RealGL.GL_RGBA, RealGL.GL_UNSIGNED_BYTE, image.pixels());
                String path = zombie.ZomboidFileSystem.instance.getScreenshotDir() + java.io.File.separator + "TW_Capture_" + name + ".png";
                image.writePng(path, true);
            } finally {
                image.close();
            }
            if (!captureDump) {
                return;
            }
            worldSum = dumpTexture(world, viewport, name + "_world");
            dumpTexture(HeatRenderer.texture(), viewport, name + "_heat");
            TextureFBO frame = Core.getInstance().getOffscreenBuffer();
            String scale = "";
            try {
                scale = String.valueOf(Class.forName("pzopt.RenderScale").getMethod("describe").invoke(null));
            } catch (Throwable ignored) {
                // not the pzopt pipeline
            }
            captureInfo = "worldFbo=" + (frame == null ? -1 : frame.getBufferId()) + " worldTexture=" + (frame == null ? -1 : frame.getTexture().getID())
                    + " sampled=" + (world == null ? -1 : world.getID()) + " sum=" + worldSum + (scale.isEmpty() ? "" : " pzoptRenderScale=" + scale);
        }

        /** QA: the ids of the world frame the last dump read, and its checksum. */
        static volatile String captureInfo = "";
        static volatile String worldSum = "";

        /** QA: the bottom-left viewport-sized part of a texture (world frame, heat buffer) as a PNG; its CRC32. */
        private static String dumpTexture(Texture texture, int[] viewport, String name) {
            if (texture == null || !texture.isReady()) {
                return "";
            }
            int tw = texture.getWidthHW(), th = texture.getHeightHW();
            zombie.core.utils.NativeImage full = zombie.core.utils.NativeImage.allocate(tw, th);
            try {
                RealGL.glActiveTexture(RealGL.GL_TEXTURE0);
                RealGL.glBindTexture(RealGL.GL_TEXTURE_2D, texture.getID());
                RealGL.glPixelStorei(RealGL.GL_PACK_ALIGNMENT, 1);
                RealGL.glGetTexImage(RealGL.GL_TEXTURE_2D, 0, RealGL.GL_RGBA, RealGL.GL_UNSIGNED_BYTE, full.pixels());
                RealGL.glBindTexture(RealGL.GL_TEXTURE_2D, 0);
                int w = Math.min(viewport[2], tw), h = Math.min(viewport[3], th);
                zombie.core.utils.NativeImage part = zombie.core.utils.NativeImage.allocate(w, h);
                try {
                    java.nio.ByteBuffer src = full.pixels(), dst = part.pixels();
                    for (int y = 0; y < h; y++) {
                        dst.put(y * w * 4, src, y * tw * 4, w * 4);
                    }
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    crc.update(dst.duplicate().position(0).limit(w * h * 4));
                    part.writePng(zombie.ZomboidFileSystem.instance.getScreenshotDir() + java.io.File.separator + "TW_Capture_" + name + ".png", true);
                    return Long.toHexString(crc.getValue());
                } finally {
                    part.close();
                }
            } finally {
                full.close();
            }
        }

        private static void bind(int unit, Texture texture) {
            RealGL.glActiveTexture(RealGL.GL_TEXTURE0 + unit);
            RealGL.glBindTexture(RealGL.GL_TEXTURE_2D, texture != null && texture.isReady() ? texture.getID() : 0);
        }

        @Override
        public void postRender() {
            HeatRenderer.release(heat);
            heat = null;
            world = null;
            synchronized (pool) {
                pool.push(this);
            }
        }
    }
}
