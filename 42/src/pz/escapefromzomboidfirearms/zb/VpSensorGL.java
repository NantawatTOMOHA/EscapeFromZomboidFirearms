package pz.escapefromzomboidfirearms.zb;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;

/**
 * The render-thread passes of VpSensor: the device image of a night vision or
 * thermal view (tw_vp_sensor_image: Tarkov's thermal and night vision formulas,
 * as tw_thermal / tw_nightvision of the isometric image, and the head
 * devices' own night vision and eyepiece mask), the mean brightness behind
 * the automatic gain (tw_vp_sensor_mean), and the drawers of the 1x eyepiece
 * and of the head device. All targets are ours; Viewpoint's are only read.
 */
final class VpSensorGL {
    static final int THERMAL = 1, NV_SCOPE = 2, NV_HEAD = 3;
    /**
     * Mean brightness the automatic gain brings a dark scene up to (at most
     * the isometric image's gain): an image intensifier's and a thermal
     * imager's own brightness control. Viewpoint adapts its exposure itself,
     * so the night is rarely as dark as the raw isometric frame.
     */
    static final float GAIN_TARGET = 0.3f;
    /** The automatic gain follows the brightness with this time constant (s). */
    static final float GAIN_TIME = 0.25f;

    static TwProgram image, mean;
    static int vao, linear, nearest, repeat;
    /** The scope's output (0), the 1x eyepiece's copy of the frame (1). */
    static final Target[] targets = {new Target(), new Target()};
    /** Successful main-image eyepiece only; diagnostic admission, no rendering control. */
    static volatile String lensPrefix = "";
    static int meanTexture, meanFbo;
    /** Smoothed mean per view: scope, eyepiece, head. */
    static final Target[] means = {new Target(), new Target(), new Target()};
    static final long[] meanTime = new long[3];
    static Texture[] ramps = new Texture[4];
    static Texture thermalNoise, nvNoise;
    static final java.util.Map<String, Texture> masks = new java.util.HashMap<>();

    // the scope's last image
    static String scopeKey = "";
    /** QA (W10 nopip2, frames side by side): the eyepiece (ScopePip.lens layout, GL pixels) of the last scope frame. */
    static volatile float[] scopeLens;
    static long scopeTime, seed;
    static int scopeSize;

    // GPU time of the latest sensor pass (timestamps, never waited for)
    static int[] timer;
    static boolean timerPending;
    public static volatile double gpuMillis;
    public static volatile long gpuSamples;

    static final class Target {
        int texture, fbo, width, height;
    }

    private VpSensorGL() {
    }

    /** Main thread: textures of the images (palettes, noise, masks), as OpticRenderer loads its own. */
    static void textures(String mask) {
        for (int i = 0; i < 4; i++) {
            if (ramps[i] == null) {
                ramps[i] = OpticRenderer.texture("TW_ThermalRamp_" + i, 32);
            }
        }
        if (thermalNoise == null) {
            thermalNoise = OpticRenderer.texture("TW_ThermalNoise", 0);
        }
        if (nvNoise == null) {
            nvNoise = OpticRenderer.texture("TW_NVNoise", 0);
        }
        if (mask != null && !masks.containsKey(mask)) {
            masks.put(mask, OpticRenderer.texture(mask, 32));
        }
    }

    static int id(Texture t) {
        return t != null && t.isReady() ? t.getID() : 0;
    }

    static void ensure(Target t, int width, int height, boolean half) {
        if (t.texture == 0) {
            t.texture = GL11.glGenTextures();
            t.fbo = GL30.glGenFramebuffers();
        }
        if (t.width != width || t.height != height) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, t.texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
            GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            if (half) {
                GLCompat.texImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, width, height, GL11.GL_RGBA, GL11.GL_FLOAT);
            } else {
                GLCompat.texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE);
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, t.fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, t.texture, 0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("sensor target incomplete");
            }
            if (half) {
                GL11.glClearColor(0f, 0f, 0f, 0f);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            }
            t.width = width;
            t.height = height;
        }
    }

    /** Render thread: the sensor programs, at the first use or ahead of it (ShaderWarmup). */
    static void shaders() {
        if (image == null) {
            image = ShaderWarmup.made(TwProgram.create("tw_vp_sensor_image"));
            mean = ShaderWarmup.made(TwProgram.create("tw_vp_sensor_mean"));
        }
    }

    static void programs() {
        shaders();
        if (!image.isCompiled() || !mean.isCompiled()) {
            throw new IllegalStateException("tw_vp_sensor_image/tw_vp_sensor_mean did not compile");
        }
        if (vao == 0) {
            vao = ScreenCorners.vertexArray();
            linear = GL33.glGenSamplers();
            nearest = GL33.glGenSamplers();
            repeat = GL33.glGenSamplers();
            for (int s : new int[] {linear, nearest, repeat}) {
                int filter = s == nearest ? GL11.GL_NEAREST : GL11.GL_LINEAR;
                int wrap = s == repeat ? GL11.GL_REPEAT : GL12.GL_CLAMP_TO_EDGE;
                GL33.glSamplerParameteri(s, GL11.GL_TEXTURE_MIN_FILTER, filter);
                GL33.glSamplerParameteri(s, GL11.GL_TEXTURE_MAG_FILTER, filter);
                GL33.glSamplerParameteri(s, GL11.GL_TEXTURE_WRAP_S, wrap);
                GL33.glSamplerParameteri(s, GL11.GL_TEXTURE_WRAP_T, wrap);
            }
        }
    }

    static void bind(int unit, int texture, int sampler) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL33.glBindSampler(unit, sampler);
    }

    static void plain() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
        GL11.glDepthMask(false);
        GL11.glColorMask(true, true, true, true);
        ScreenCorners.bind(vao);
    }

    /**
     * The mean brightness of a texture's rectangle (uv), smoothed in time,
     * into the 1x1 texture of slot (0 scope, 1 eyepiece, 2 head): 32 x 32
     * cells of 4 x 4 samples each, their mipmap, then an exponential average.
     */
    static int mean(int slot, int texture, float u0, float v0, float du, float dv) {
        if (meanTexture == 0) {
            meanTexture = GL11.glGenTextures();
            meanFbo = GL30.glGenFramebuffers();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, meanTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 5);
            GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            for (int level = 0, s = 32; level <= 5; level++, s /= 2) {
                GLCompat.texImage2D(GL11.GL_TEXTURE_2D, level, GL30.GL_RGBA16F, s, s, GL11.GL_RGBA, GL11.GL_FLOAT);
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, meanFbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, meanTexture, 0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        }
        plain();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, meanFbo);
        GL11.glViewport(0, 0, 32, 32);
        mean.Start();
        mean.setValue("Mode", 0f);
        mean.setVector4("Source", u0, v0, du, dv);
        mean.setSamplerUnit("Input", 0);
        bind(0, texture, linear);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, meanTexture);
        GL33.glBindSampler(0, 0);
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        Target t = means[slot];
        boolean first = t.texture == 0;
        ensure(t, 1, 1, true);
        long now = System.nanoTime();
        float dt = first || meanTime[slot] == 0 ? 1e9f : (now - meanTime[slot]) / 1e9f;
        meanTime[slot] = now;
        float k = 1f - (float) Math.exp(-Math.max(0f, dt) / GAIN_TIME);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, t.fbo);
        GL11.glViewport(0, 0, 1, 1);
        GL11.glEnable(GL11.GL_BLEND);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        // The weight k is the source alpha (Gain), not a blend colour: VPMetal has no glBlendColor (player log
        // 03.11.2026). Colour k * new + (1 - k) * old, alpha k + (1 - k) * old - what CONSTANT_ALPHA blended.
        GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        mean.setValue("Mode", 1f);
        mean.setValue("Gain", first ? 1f : k);
        bind(0, meanTexture, 0);
        // macOS (02.11.2026; GLSL 1.20 has no explicit-LOD read in a fragment shader): the 1 x 1 level 5 is the
        // texture's base level for this draw - the shader reads the same texel textureLod(Input, 0.5, 5.0) read - and
        // level 0 again for the next mean's draw and mipmap.
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 5);
        try {
            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        } finally {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        }
        GL11.glDisable(GL11.GL_BLEND);
        return t.texture;
    }

    /** The uniforms of a sensor image of the isometric scope values v. */
    static void values(OpticState.View v, int kind, float blocks, float width, float height, long seed) {
        image.setValue("Kind", (float) kind);
        image.setVector4("Thermal", v.coef, v.minimum, v.shift, v.depthFade);
        // Input: scene term (ENV), gain limit (isometric gain), noise of the scope frame, seed
        image.setVector4("Input", v.env, Math.max(1f, kind == THERMAL ? v.gain : v.nvGain), v.noise * 0.785f, (float) (seed % 9973));
        image.setValue("EnvCap", v.envCap);
        image.setVector4("Pixel", blocks, v.pixelAlpha, 0f, 0f);
        // ScopeEffectsData.BloomOptimized: BlurSize over the lens (the isometric image: per 108 px of lens).
        image.setVector4("Bloom", v.bloomIntensity, v.bloomThreshold, v.bloomBlur * height / 108f, 0f);
        image.setVector4("Lens", width * .5f, height * .5f, height, v.aberration);
        image.setVector4("NightVision", v.nvR * v.nvIntensity * 0.45f, v.nvG * v.nvIntensity * 0.45f, v.nvB * v.nvIntensity * 0.45f,
                Math.max(1f, v.nvGain));
        image.setValue("NvNoise", v.nvNoise * 0.785f);
        image.setValue("GainTarget", GAIN_TARGET);
    }

    static void samplers() {
        image.setSamplerUnit("InputTex", 0);
        image.setSamplerUnit("HeatTex", 1);
        image.setSamplerUnit("DepthTex", 2);
        image.setSamplerUnit("RampTex", 3);
        image.setSamplerUnit("NoiseTex", 4);
        image.setSamplerUnit("MeanTex", 5);
        image.setSamplerUnit("MaskTex", 6);
    }

    /** Performance pass 23.10.2026: timed only while the perf probe runs (VpSensor.info alone reads gpuMillis). */
    static boolean timing() {
        if (!PerfProbe.enabled()) {
            timerPending = false;
            return false;
        }
        if (timer == null) {
            timer = new int[] {GLCompat.genQuery(), GLCompat.genQuery()};
        }
        if (timerPending) {
            if (GL15.glGetQueryObjecti(timer[1], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
                return false;
            }
            gpuMillis = (GL33.glGetQueryObjectui64(timer[1], GL15.GL_QUERY_RESULT) - GL33.glGetQueryObjectui64(timer[0], GL15.GL_QUERY_RESULT)) / 1e6;
            gpuSamples++;
            timerPending = false;
        }
        GL33.glQueryCounter(timer[0], GL33.GL_TIMESTAMP);
        return true;
    }

    static void timed(boolean on) {
        if (on) {
            GL33.glQueryCounter(timer[1], GL33.GL_TIMESTAMP);
            timerPending = true;
        }
    }

    /** The texel size and the render share of a depth texture (its level 0 may be larger than what was drawn). */
    static float depthScale(int depth, int drawn) {
        if (depth == 0) {
            return 1f;
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth);
        int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        return width > 0 ? drawn / (float) width : 1f;
    }

    /**
     * Render thread, ScopePip's sensor stage (inside its saved GL state): the
     * scope picture through the device of a night vision or thermal channel,
     * into our own image of the picture's size. A thermal picture is renewed
     * at the device's frame rate (StuckFPSUtilities); a picture ScopePip did
     * not render anew keeps the last image.
     */
    static int scope(ScopePipStages.Frame f, VpSensor.Scope s, VpHeat.Frame heat) {
        long start = System.nanoTime();
        OpticState.View v = s.view;
        boolean thermal = v.kind == OpticState.THERMAL;
        Target out = targets[0];
        boolean same = f.key.equals(scopeKey) && scopeSize == f.size && out.texture != 0;
        scopeLens = f.lens;
        if (same && (!f.fresh || thermal && v.fps > 0 && start - scopeTime < (long) (1e9 / v.fps))) {
            VpSensor.scopeHeld++;
            return out.texture;
        }
        programs();
        boolean timing = timing();
        int meanTex = mean(0, f.texture, 0f, 0f, 1f, 1f);
        int heatTex = 0;
        float depthScale = depthScale(f.depth, f.size);
        if (thermal) {
            int hs = Math.max(32, Math.round(f.size * VpSensor.heatScale));
            heatTex = VpHeat.renderOrEmpty(0, heat, f.view, f.projection, f.depth, depthScale, depthScale, hs, hs);
        }
        ensure(out, f.size, f.size, false);
        plain();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, out.fbo);
        GL11.glViewport(0, 0, f.size, f.size);
        image.Start();
        samplers();
        seed++;
        values(v, thermal ? THERMAL : NV_SCOPE, s.blocks, f.size, f.size, seed);
        image.setVector4("Clip", -1f, -1f, 1f, 1f);
        image.setVector4("Rect", 0f, 0f, f.size, f.size);
        image.setVector4("InUV", 0f, 0f, 1f, 1f);
        image.setVector4("Depth", depthScale, depthScale, f.projection.m32(), f.projection.m22());
        image.setValue("DepthOn", f.depth != 0 ? 1f : 0f);
        image.setVector4("Mask", 0f, 1f, 1f, 1f);
        image.setVector4("LensOn", 0f, 0f, 0f, 0f);
        bind(0, f.texture, linear);
        bind(1, heatTex, linear);
        bind(2, f.depth, nearest);
        bind(3, thermal ? id(ramps[Math.max(0, Math.min(3, v.palette))]) : 0, linear);
        bind(4, id(thermalNoise), repeat);
        bind(5, meanTex, nearest);
        bind(6, 0, linear);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        timed(timing);
        scopeKey = f.key;
        scopeSize = f.size;
        scopeTime = start;
        VpSensor.scopeImages++;
        VpSensor.scopeMillis = (System.nanoTime() - start) / 1e6;
        return out.texture;
    }

    // W10 nopip2: the camera zoom's sensor picture - colour, depth, and the frame buffers that copy them
    static final Target zoomColour = new Target();
    static int zoomDepth, zoomDepthFbo, zoomReadFbo, zoomDepthFormat, zoomDepthWidth, zoomDepthHeight;
    public static volatile long zoomPictures, zoomDepthless;

    /**
     * W10 nopip2: the square (GL pixels x, y, side) of the eyepiece's bounding box (ScopePip.lens layout: centre, two
     * radius vectors), its larger extent, centred on the eyepiece; null when it misses the viewport.
     */
    static float[] box(float[] lens, int[] viewport) {
        if (lens == null || lens.length < 6) {
            return null;
        }
        float hx = (float) Math.hypot(lens[2], lens[4]), hy = (float) Math.hypot(lens[3], lens[5]);
        float half = Math.max(hx, hy) + 1f;
        if (!(half > 1f) || !Float.isFinite(half)) {
            return null;
        }
        float x = lens[0] - half, y = lens[1] - half, side = 2 * half;
        if (x >= viewport[0] + viewport[2] || y >= viewport[1] + viewport[3] || x + side <= viewport[0] || y + side <= viewport[1]) {
            return null;
        }
        return new float[] {x, y, side};
    }

    /**
     * W10 nopip2, render thread: the picture the sensor stage takes in the camera zoom of a night vision or thermal
     * channel (no second render). The zoomed main frame inside the eyepiece's square (box) is copied into a size x size
     * image, Viewpoint's depth of the same square into a depth image of its own format, and the main camera's projection
     * is cropped to the square: VpSensorGL.scope then turns it into the device's image with the very calls it makes for
     * the scope picture (its mean, heat buffer, values, frame rate). Parts of the square outside the viewport stay
     * black. Returns the frame (fresh: the main frame is new every frame), or null when there is nothing to copy.
     */
    static ScopePipStages.Frame zoomFrame(String key, String kind, Object weapon, float fov, float[] lens, float[] box, int size,
            int[] viewport, int drawFbo, Matrix4f view, Matrix4f projection) throws IllegalAccessException {
        // the square's part inside the viewport, in output pixels and in the picture's texels
        float scale = size / box[2];
        int x0 = Math.max(viewport[0], (int) Math.floor(box[0])), y0 = Math.max(viewport[1], (int) Math.floor(box[1]));
        int x1 = Math.min(viewport[0] + viewport[2], (int) Math.ceil(box[0] + box[2]));
        int y1 = Math.min(viewport[1] + viewport[3], (int) Math.ceil(box[1] + box[2]));
        if (x1 <= x0 || y1 <= y0 || size <= 0) {
            return null;
        }
        int u0 = Math.round((x0 - box[0]) * scale), v0 = Math.round((y0 - box[1]) * scale);
        int u1 = Math.round((x1 - box[0]) * scale), v1 = Math.round((y1 - box[1]) * scale);
        boolean partial = u0 > 0 || v0 > 0 || u1 < size || v1 < size;
        ensure(zoomColour, size, size, false);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
        GL11.glColorMask(true, true, true, true);
        if (partial) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, zoomColour.fbo);
            GL11.glClearColor(0f, 0f, 0f, 1f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        }
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, drawFbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, zoomColour.fbo);
        GL30.glBlitFramebuffer(x0, y0, x1, y1, u0, v0, u1, v1, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        // Viewpoint's depth (render resolution: its texel = the viewport's share times the drawn size) of the same part
        int[] t = new int[8];
        ViewpointBridge.targets(t);
        int depth = t[0] != 0 && t[1] > 0 && t[2] > 0 ? zoomDepth(t[0], size) : 0;
        if (depth != 0) {
            float sx = t[1] / (float) viewport[2], sy = t[2] / (float) viewport[3];
            if (partial) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, zoomDepthFbo);
                GL11.glDepthMask(true);
                GL11.glClearDepth(1.0);
                GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                GL11.glDepthMask(false);
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, zoomReadFbo);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, t[0], 0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, zoomDepthFbo);
            GL30.glBlitFramebuffer(Math.round((x0 - viewport[0]) * sx), Math.round((y0 - viewport[1]) * sy),
                    Math.round((x1 - viewport[0]) * sx), Math.round((y1 - viewport[1]) * sy), u0, v0, u1, v1,
                    GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, 0, 0);
        } else {
            zoomDepthless++;
        }
        zoomPictures++;
        return new ScopePipStages.Frame(key, kind, weapon, fov, zoomColour.texture, depth, size, true, view,
                crop(projection, box, viewport), lens, viewport, 0f, 0f);
    }

    /**
     * W10 nopip2: the projection cropped to the square box (GL pixels x, y, side) of the viewport: a scene point the main
     * projection puts at an output pixel lands at the same place of the square's picture; depth terms (rows z, w) unchanged.
     */
    static Matrix4f crop(Matrix4f projection, float[] box, int[] viewport) {
        // NDC of the square in the viewport (x' = (2x - (a + b)) / (b - a))
        float ax = 2f * (box[0] - viewport[0]) / viewport[2] - 1f, bx = 2f * (box[0] + box[2] - viewport[0]) / viewport[2] - 1f;
        float ay = 2f * (box[1] - viewport[1]) / viewport[3] - 1f, by = 2f * (box[1] + box[2] - viewport[1]) / viewport[3] - 1f;
        return new Matrix4f().m00(2f / (bx - ax)).m11(2f / (by - ay)).m30(-(ax + bx) / (bx - ax)).m31(-(ay + by) / (by - ay))
                .mul(projection);
    }

    /** The camera zoom's depth image of size x size in the format of Viewpoint's depth texture (a depth blit needs it), or 0. */
    private static int zoomDepth(int source, int size) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, source);
        int format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
        int external, type;
        switch (format) {
            case GL30.GL_DEPTH24_STENCIL8 -> { external = GL30.GL_DEPTH_STENCIL; type = GL30.GL_UNSIGNED_INT_24_8; }
            case GL30.GL_DEPTH32F_STENCIL8 -> { external = GL30.GL_DEPTH_STENCIL; type = GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV; }
            case GL30.GL_DEPTH_COMPONENT32F -> { external = GL11.GL_DEPTH_COMPONENT; type = GL11.GL_FLOAT; }
            case org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24, org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT32 -> {
                external = GL11.GL_DEPTH_COMPONENT; type = GL11.GL_UNSIGNED_INT; }
            case org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT16 -> { external = GL11.GL_DEPTH_COMPONENT; type = GL11.GL_UNSIGNED_SHORT; }
            default -> { return 0; }
        }
        if (zoomDepth == 0) {
            zoomDepth = GL11.glGenTextures();
            zoomDepthFbo = GL30.glGenFramebuffers();
            zoomReadFbo = GL30.glGenFramebuffers();
        }
        if (zoomDepthFormat != format || zoomDepthWidth != size || zoomDepthHeight != size) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, zoomDepth);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
            GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GLCompat.texImage2D(GL11.GL_TEXTURE_2D, 0, format, size, size, external, type);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, zoomDepthFbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, zoomDepth, 0);
            GL11.glDrawBuffer(GL11.GL_NONE);
            GL11.glReadBuffer(GL11.GL_NONE);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("zoom depth target incomplete");
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, zoomReadFbo);
            GL11.glDrawBuffer(GL11.GL_NONE);
            GL11.glReadBuffer(GL11.GL_NONE);
            zoomDepthFormat = format;
            zoomDepthWidth = size;
            zoomDepthHeight = size;
        }
        return zoomDepth;
    }

    /**
     * A night vision or thermal sight at 1x (no scope picture): the device's
     * image of the frame itself inside the sight's eyepiece, right after
     * Viewpoint's frame (and our pass) into the world frame buffer, before the
     * game's screen composite.
     */
    static final class LensDrawer extends TextureDraw.GenericDrawer {
        final ViewpointPass.Batch batch;
        final VpSensor.Scope scope;
        final Vector3f[] lens;

        LensDrawer(ViewpointPass.Batch batch, VpSensor.Scope scope, Vector3f[] lens) {
            this.batch = batch;
            this.scope = scope;
            this.lens = lens;
            textures(null);
        }

        @Override
        public void render() {
            if (VpSensor.failed() || !ViewpointBridge.ready()) {
                lensPrefix = "";
                return;
            }
            boolean completed = false;
            long start = System.nanoTime();
            PerfProbe.Scope perf = PerfProbe.render(PerfProbe.SENSOR_LENS);
            try (ScopePipGL saved = new ScopePipGL()) {
                if (ViewpointBridge.currentScene() != batch.scene) {
                    return;
                }
                Matrix4f vp = ViewpointBridge.viewProjection(), view = ViewpointBridge.view(), projection = ViewpointBridge.projection(batch.frame);
                int[] vpt = saved.viewport;
                if (vp == null || view == null || projection == null || vpt[2] <= 0 || vpt[3] <= 0) {
                    return;
                }
                float[] c = project(vp, lens[0], vpt), ex = project(vp, lens[1], vpt), ey = project(vp, lens[2], vpt);
                if (c == null || ex == null || ey == null) {
                    return;
                }
                float ux = ex[0] - c[0], uy = ex[1] - c[1], wx = ey[0] - c[0], wy = ey[1] - c[1];
                float det = ux * wy - uy * wx;
                if (!(Math.abs(det) > .25f)) {
                    return;
                }
                // The frame inside the eyepiece's box, copied: it is read while the buffer is drawn into.
                int rx = (int) Math.floor(c[0] - Math.abs(ux) - Math.abs(wx)) - 2, ry = (int) Math.floor(c[1] - Math.abs(uy) - Math.abs(wy)) - 2;
                int rw = (int) Math.ceil(2 * (Math.abs(ux) + Math.abs(wx))) + 4, rh = (int) Math.ceil(2 * (Math.abs(uy) + Math.abs(wy))) + 4;
                int x0 = Math.max(vpt[0], rx), y0 = Math.max(vpt[1], ry), x1 = Math.min(vpt[0] + vpt[2], rx + rw), y1 = Math.min(vpt[1] + vpt[3], ry + rh);
                if (x1 <= x0 || y1 <= y0) {
                    return;
                }
                programs();
                boolean timing = timing();
                Target copy = targets[1];
                ensure(copy, vpt[2], vpt[3], false);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, saved.drawFbo);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copy.fbo);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL30.glBlitFramebuffer(x0, y0, x1, y1, x0 - vpt[0], y0 - vpt[1], x1 - vpt[0], y1 - vpt[1], GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
                OpticState.View v = scope.view;
                boolean thermal = v.kind == OpticState.THERMAL;
                float u0 = (x0 - vpt[0]) / (float) vpt[2], v0 = (y0 - vpt[1]) / (float) vpt[3];
                int meanTex = mean(1, copy.texture, u0, v0, (x1 - x0) / (float) vpt[2], (y1 - y0) / (float) vpt[3]);
                int[] t = new int[8];
                ViewpointBridge.targets(t);
                int depth = t[0] != 0 && t[1] > 0 && t[2] > 0 ? t[0] : 0;
                float sx = t[3] > 0 ? t[1] / (float) t[3] : 1f, sy = t[4] > 0 ? t[2] / (float) t[4] : 1f;
                int heatTex = 0;
                if (thermal) {
                    heatTex = VpHeat.renderOrEmpty(1, batch.heat, view, projection, depth, sx, sy,
                            Math.max(32, Math.round(vpt[2] * VpSensor.heatScale)), Math.max(32, Math.round(vpt[3] * VpSensor.heatScale)));
                }
                plain();
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, saved.drawFbo);
                GL11.glViewport(vpt[0], vpt[1], vpt[2], vpt[3]);
                GL11.glEnable(GL11.GL_BLEND);
                GL14.glBlendEquation(GL14.GL_FUNC_ADD);
                GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
                GL11.glColorMask(true, true, true, false);
                image.Start();
                samplers();
                seed++;
                float diameter = 2f * (float) Math.sqrt(Math.abs(det));
                values(v, thermal ? THERMAL : NV_SCOPE, scope.blocks * vpt[3] / Math.max(1f, diameter), vpt[2], vpt[3], seed);
                // aberration about the eyepiece's centre, the eyepiece the lens
                image.setVector4("Lens", c[0] - vpt[0], c[1] - vpt[1], diameter, v.aberration);
                image.setVector4("Clip", -1f, -1f, 1f, 1f);
                image.setVector4("Rect", vpt[0], vpt[1], vpt[2], vpt[3]);
                image.setVector4("InUV", 0f, 0f, 1f, 1f);
                image.setVector4("Depth", sx, sy, projection.m32(), projection.m22());
                image.setValue("DepthOn", depth != 0 ? 1f : 0f);
                image.setVector4("Mask", 0f, 1f, 1f, 1f);
                image.setVector4("LensOn", 1f, c[2], 0f, 0f);
                image.setVector4("LensAxes", ux, uy, wx, wy);
                // Window depth is affine across a projected plane. A tilted
                // eyepiece (notably the small T12W screen) cannot use its
                // centre depth for every fragment: its own near half would
                // otherwise be mistaken for foreground covering the image.
                image.setVector3("LensDepth", c[2], ex[2] - c[2], ey[2] - c[2]);
                image.setVector2("LensCentre", c[0] - vpt[0], c[1] - vpt[1]);
                bind(0, copy.texture, linear);
                bind(1, heatTex, linear);
                bind(2, depth, nearest);
                bind(3, thermal ? id(ramps[Math.max(0, Math.min(3, v.palette))]) : 0, linear);
                bind(4, id(thermalNoise), repeat);
                bind(5, meanTex, nearest);
                bind(6, 0, linear);
                GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
                VpSensor.lensImages++;
                lensPrefix = scope.prefix;
                completed = true;
                timed(timing);
                VpSensor.lensMillis = (System.nanoTime() - start) / 1e6;
            } catch (Throwable t) {
                lensPrefix = "";
                VpSensor.fail("lens draw", t);
            } finally {
                if (!completed) lensPrefix = "";
                PerfProbe.end(perf);
            }
        }

        @Override
        public void postRender() {
        }
    }

    /** A scene point to output pixels of the viewport (x, y, depth 0..1), or null behind the camera. */
    static float[] project(Matrix4f vp, Vector3f p, int[] viewport) {
        Vector4f c = new Vector4f(p, 1f).mul(vp);
        if (!c.isFinite() || c.w <= 1e-4f) {
            return null;
        }
        return new float[] {viewport[0] + (c.x / c.w * .5f + .5f) * viewport[2], viewport[1] + (c.y / c.w * .5f + .5f) * viewport[3],
                c.z / c.w * .5f + .5f};
    }

    /**
     * A warm head NV pass touches only seven 2D texture/sampler bindings, its
     * VAO, program, framebuffers and the legacy attributes. It does not use
     * vertex/index buffers, SSBOs or UBOs. Avoid querying/restoring every
     * binding of the context each frame; keep the full boundary for resource
     * creation and for the thermal renderer (whose state is not this subset).
     */
    static final class HeadGL implements AutoCloseable {
        final int drawFbo;
        final ScopePipGL full;
        private final boolean framebufferSrgb = GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB);
        private int readFbo, program, active, vertexArray;
        private int[] textures, samplers;

        HeadGL(boolean thermal) {
            if (thermal || image == null || mean == null || vao == 0 || meanTexture == 0 || means[2].texture == 0) {
                full = new ScopePipGL();
                drawFbo = full.drawFbo;
                return;
            }
            full = null;
            drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            program = GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM);
            active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            textures = new int[7];
            samplers = new int[7];
            for (int i = 0; i < textures.length; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                textures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                samplers[i] = GLCompat.sampler(i); // -1: unreadable on this context, not written back
            }
            GL13.glActiveTexture(active);
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        }

        @Override
        public void close() {
            if (full != null) {
                full.close();
                srgb();
                return;
            }
            GL11.glPopAttrib();
            for (int i = 0; i < textures.length; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[i]);
                GLCompat.restoreSampler(i, samplers[i]);
            }
            GL13.glActiveTexture(active);
            GL30.glBindVertexArray(vertexArray);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GLCompat.useProgram(program);
            Texture.lastTextureID = -1;
            SpriteRenderer.ringBuffer.restoreBoundTextures = true;
            SpriteRenderer.ringBuffer.restoreVbos = true;
            GLStateRenderThread.restore();
            srgb();
        }

        private void srgb() {
            // FRAMEBUFFER_SRGB is not part of the legacy attribute stack.
            if (framebufferSrgb) GL11.glEnable(GL30.GL_FRAMEBUFFER_SRGB);
            else GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
        }
    }

    /**
     * The head device over the screen (after the game's screen composite,
     * before the interface): the raw frame (Viewpoint's image with our pass
     * and the scope picture in it, before the game's own night vision shader)
     * through the device, inside its eyepiece mask in first person.
     */
    static final class HeadDrawer extends TextureDraw.GenericDrawer {
        final ViewpointPass.Batch batch;
        final VpSensor.Head head;
        final Texture world;
        /** left, top, width, height of player 0's view; screen width, height. */
        final int[] rect;
        final boolean firstPerson;

        HeadDrawer(ViewpointPass.Batch batch, VpSensor.Head head, Texture world, int[] rect, boolean firstPerson) {
            this.batch = batch;
            this.head = head;
            this.world = world;
            this.rect = rect;
            this.firstPerson = firstPerson;
            textures(head.mask);
        }

        @Override
        public void render() {
            if (VpSensor.failed() || !ViewpointBridge.ready() || world == null || !world.isReady()) {
                return;
            }
            long start = System.nanoTime();
            PerfProbe.Scope perf = PerfProbe.render(PerfProbe.SENSOR_HEAD);
            boolean thermal = head.kind == OpticState.THERMAL;
            try (HeadGL saved = new HeadGL(thermal)) {
                programs();
                boolean timing = timing();
                int whw = world.getWidthHW(), hhw = world.getHeightHW();
                int left = rect[0], top = rect[1], w = rect[2], h = rect[3], screenW = rect[4], screenH = rect[5];
                float u0 = left / (float) whw, v0 = top / (float) hhw, du = w / (float) whw, dv = h / (float) hhw;
                int meanTex = mean(2, world.getID(), u0, v0, du, dv);
                int[] t = new int[8];
                if (thermal) {
                    ViewpointBridge.targets(t);
                }
                int depth = t[0] != 0 && t[1] > 0 && t[2] > 0 ? t[0] : 0;
                float sx = t[3] > 0 ? t[1] / (float) t[3] : 1f, sy = t[4] > 0 ? t[2] / (float) t[4] : 1f;
                Matrix4f projection = !thermal || batch.frame == null ? null : ViewpointBridge.projection(batch.frame);
                int heatTex = 0;
                if (thermal && projection != null) {
                    heatTex = VpHeat.renderOrEmpty(1, batch.heat, ViewpointBridge.view(), projection, depth, sx, sy,
                            Math.max(32, Math.round(w * VpSensor.heatScale)), Math.max(32, Math.round(h * VpSensor.heatScale)));
                }
                plain();
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, saved.drawFbo);
                int gy = screenH - top - h;
                GL11.glViewport(0, 0, screenW, screenH);
                GL11.glColorMask(true, true, true, false);
                image.Start();
                samplers();
                seed++;
                OpticState.View v = head.view;
                if (thermal) {
                    values(v, THERMAL, v.pixelBlocks, w, h, seed);
                    image.setVector4("Lens", w * .5f, h * .5f, h, 0f);
                } else {
                    values(v, NV_HEAD, 0f, w, h, seed);
                    // Tarkov head night vision: Color * Intensity * 0.45, NoiseIntensity on the grain's red.
                    image.setVector4("NightVision", head.r * head.intensity * .45f, head.g * head.intensity * .45f,
                            head.b * head.intensity * .45f, Math.max(1f, head.gainLimit));
                    image.setValue("NvNoise", head.noise);
                    // NightVision.ApplySettings: NoiseScale * screen height / grain height, by the aspect across;
                    // the grain moves every frame by frac(Time.x * (14345.68, -12345.68)), Time.x = seconds / 20.
                    float scale = head.noiseScale * h / 512f, time = (System.nanoTime() % 1_000_000_000_000L) / 1e9f / 20f;
                    image.setVector4("NoiseUV", scale * w / h, scale, frac(time * 14345.677734f), frac(time * -12345.677734f));
                }
                Texture mask = head.mask == null ? null : masks.get(head.mask);
                boolean masked = firstPerson && id(mask) != 0;
                // TextureMask: 1 / MaskSize, the mask's height / width, the camera's aspect
                image.setVector4("Mask", masked ? 1f : 0f, 1f / Math.max(1e-3f, head.maskSize), head.maskAspect, w / (float) h);
                image.setVector4("LensOn", 0f, 0f, 0f, 0f);
                image.setVector4("Clip", 2f * left / screenW - 1f, 2f * gy / screenH - 1f, 2f * (left + w) / screenW - 1f, 2f * (gy + h) / screenH - 1f);
                image.setVector4("Rect", left, gy, w, h);
                image.setVector4("InUV", u0, v0, du, dv);
                image.setVector4("Depth", sx, sy, projection == null ? 0f : projection.m32(), projection == null ? 1f : projection.m22());
                image.setValue("DepthOn", depth != 0 && projection != null ? 1f : 0f);
                bind(0, world.getID(), linear);
                bind(1, heatTex, linear);
                bind(2, depth, nearest);
                bind(3, thermal ? id(ramps[Math.max(0, Math.min(3, v.palette))]) : 0, linear);
                bind(4, thermal ? id(thermalNoise) : id(nvNoise), repeat);
                bind(5, meanTex, nearest);
                bind(6, masked ? id(mask) : 0, linear);
                GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
                timed(timing);
                VpSensor.headMillis = (System.nanoTime() - start) / 1e6;
            } catch (Throwable t) {
                VpSensor.fail("head draw", t);
            } finally {
                PerfProbe.end(perf);
            }
        }

        @Override
        public void postRender() {
        }
    }

    static float frac(float x) {
        return x - (float) Math.floor(x);
    }
}
