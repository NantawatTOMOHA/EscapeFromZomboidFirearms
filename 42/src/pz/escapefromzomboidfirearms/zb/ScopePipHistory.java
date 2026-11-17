package pz.escapefromzomboidfirearms.zb;

import java.util.concurrent.atomic.AtomicLong;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.*;

/** Our PIP-only reconstruction/history. No main temporal targets or VP shaders. */
final class ScopePipHistory {
    private final AtomicLong epoch = new AtomicLong();
    private final int[] color = new int[2], depth = new int[2], fbo = new int[2];
    private final Matrix4f previous = new Matrix4f(), previousSky = new Matrix4f(), previousProjection = new Matrix4f();
    private final Vector3f previousOrigin = new Vector3f();
    private TwProgram shader;

    /** Render thread: the history program, at the first use or ahead of it (ShaderWarmup). */
    void shaders() {
        if (shader == null) {
            shader = ShaderWarmup.made(TwProgram.create("tw_pip_history"));
        }
    }
    private String key = "";
    private int size, index, vao, linear, nearest;
    private long lastFrame = Long.MIN_VALUE, lastEpoch = -1;
    private int[] timer;
    private boolean timerPending;
    /**
     * Performance pass 23.10.2026: the program's uniform locations, looked up once per linked program (eight
     * glGetUniformLocation by name every scope frame before); indices follow the names.
     */
    private static final int CURRENT_IMAGE = 0, CURRENT_DEPTH = 1, HISTORY_IMAGE = 2, HISTORY_DEPTH = 3, HISTORY_ON = 4,
            REPROJECT = 5, REPROJECT_SKY = 6, PREVIOUS_DEPTH = 7, IMAGE_SIZE = 8;
    private final ScopePipGL.Uniforms uniforms = new ScopePipGL.Uniforms("CurrentImage", "CurrentDepth", "HistoryImage",
            "HistoryDepth", "HistoryOn", "Reproject", "ReprojectSky", "PreviousDepth", "ImageSize");
    private final float[] matrix = new float[16];
    private volatile long frames, resets, gpuSamples;
    private volatile double cpuMillis, gpuMillis;

    void invalidate() {
        epoch.incrementAndGet();
    }

    static Matrix4f reproject(Matrix4f oldViewProjection, Vector3f oldOrigin, Vector3f origin, Matrix4f viewProjection) {
        // Scene x/z face against world x/y; scene y is height above camZ.
        Vector3f shift = new Vector3f(oldOrigin.x - origin.x,
                (float) ((origin.z - oldOrigin.z) * ViewpointBridge.LEVEL), oldOrigin.y - origin.y);
        return new Matrix4f(oldViewProjection).translate(shift).mul(new Matrix4f(viewProjection).invert());
    }

    void resolve(int input, int sceneDepth, int outputFbo, int wanted, String channel, long frame,
            Matrix4f view, Matrix4f projection, Vector3f origin, boolean cut) {
        shaders();
        if (!shader.isCompiled()) {
            throw new IllegalStateException("tw_pip_history did not compile");
        }
        if (shader.real() != null) uniforms.listen(shader.real()); // a relink of the shader (also under the same id) drops the kept locations
        draw(shader.getShaderID(), input, sceneDepth, outputFbo, wanted, channel, frame, view, projection, origin, cut);
    }

    /** The same renderer also accepts our program compiled in a standalone GL fixture. */
    void draw(int program, int input, int sceneDepth, int outputFbo, int wanted, String channel, long frame,
            Matrix4f view, Matrix4f projection, Vector3f origin, boolean cut) {
        long start = System.nanoTime(), generation = epoch.get();
        boolean valid = size == wanted && key.equals(channel) && lastEpoch == generation && !cut
                && frame > lastFrame && frame - lastFrame <= 4 && previousOrigin.distanceSquared(origin) < 4
                && previousProjection.equals(projection, 1e-6f);
        ensure(wanted);
        boolean timing = beginTiming();
        try {
            Matrix4f vp = new Matrix4f(projection).mul(view);
            Matrix4f sky = new Matrix4f(projection).mul(new Matrix4f(view).m30(0).m31(0).m32(0));
            Matrix4f reprojection = valid ? reproject(previous, previousOrigin, origin, vp) : new Matrix4f();
            Matrix4f skyReprojection = valid ? new Matrix4f(previousSky).mul(new Matrix4f(sky).invert()) : new Matrix4f();
            int target = 1 - index;
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo[target]);
            GL11.glViewport(0, 0, size, size);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            GL11.glColorMask(true, true, true, true);
            GL11.glDepthMask(false);
            ScreenCorners.bind(vao);
            GL20.glUseProgram(program);
            int[] at = uniforms.of(program);
            GL20.glUniform1i(at[CURRENT_IMAGE], 0);
            GL20.glUniform1i(at[CURRENT_DEPTH], 1);
            GL20.glUniform1i(at[HISTORY_IMAGE], 2);
            GL20.glUniform1i(at[HISTORY_DEPTH], 3);
            GL20.glUniform1i(at[HISTORY_ON], valid ? 1 : 0);
            GL20.glUniformMatrix4fv(at[REPROJECT], false, reprojection.get(matrix));
            GL20.glUniformMatrix4fv(at[REPROJECT_SKY], false, skyReprojection.get(matrix));
            GL20.glUniform2f(at[PREVIOUS_DEPTH], previousProjection.m22(), previousProjection.m32());
            // the input picture's texels (macOS, GLSL 1.20: the shader has no textureSize); input and depth are size a side
            GL20.glUniform2f(at[IMAGE_SIZE], size, size);
            bind(0, input, nearest);
            bind(1, sceneDepth, nearest);
            bind(2, color[index], linear);
            bind(3, depth[index], nearest);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            // Stages retain their existing writable image. History never sees
            // scene effects, sensor noise or the reticle, nor reads its output.
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo[target]);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, outputFbo);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL30.glBlitFramebuffer(0, 0, size, size, 0, 0, size, size, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            index = target;
            key = channel;
            lastFrame = frame;
            lastEpoch = generation;
            previous.set(vp);
            previousSky.set(sky);
            previousProjection.set(projection);
            previousOrigin.set(origin);
            frames++;
            if (!valid) {
                resets++;
            }
        } finally {
            if (timing) {
                GL33.glQueryCounter(timer[1], GL33.GL_TIMESTAMP);
                timerPending = true;
            }
            cpuMillis = (System.nanoTime() - start) / 1e6;
        }
    }

    private void ensure(int wanted) {
        if (vao == 0) {
            vao = ScreenCorners.vertexArray();
            linear = GL33.glGenSamplers();
            nearest = GL33.glGenSamplers();
            for (int sampler : new int[] {linear, nearest}) {
                GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MIN_FILTER, sampler == linear ? GL11.GL_LINEAR : GL11.GL_NEAREST);
                GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MAG_FILTER, sampler == linear ? GL11.GL_LINEAR : GL11.GL_NEAREST);
            }
        }
        if (size == wanted) {
            return;
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        for (int i = 0; i < 2; i++) {
            if (color[i] == 0) {
                color[i] = GL11.glGenTextures();
                depth[i] = GL11.glGenTextures();
                fbo[i] = GL30.glGenFramebuffers();
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo[i]);
            allocate(color[i], GL30.GL_RGBA16F, GL11.GL_RGBA, wanted, GL30.GL_COLOR_ATTACHMENT0);
            allocate(depth[i], GL30.GL_R32F, GL11.GL_RED, wanted, GL30.GL_COLOR_ATTACHMENT1);
            GL20.glDrawBuffers(new int[] {GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("PIP history target incomplete");
            }
            // The no-history draw must not even sample uninitialized storage.
            GL11.glClearColor(0, 0, 0, 0);
            GL11.glColorMask(true, true, true, true);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        }
        size = wanted;
    }

    private static void allocate(int texture, int format, int channels, int size, int attachment) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GLCompat.texImage2D(GL11.GL_TEXTURE_2D, 0, format, size, size, channels, GL11.GL_FLOAT);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, attachment, GL11.GL_TEXTURE_2D, texture, 0);
    }

    private static void bind(int unit, int texture, int sampler) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL33.glBindSampler(unit, sampler);
    }

    /** Performance pass 23.10.2026: timed only while the perf probe runs (info() alone reads the result). */
    private boolean beginTiming() {
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
            long first = GL33.glGetQueryObjectui64(timer[0], GL15.GL_QUERY_RESULT);
            long last = GL33.glGetQueryObjectui64(timer[1], GL15.GL_QUERY_RESULT);
            gpuMillis = (last - first) / 1e6;
            gpuSamples++;
        }
        GL33.glQueryCounter(timer[0], GL33.GL_TIMESTAMP);
        return true;
    }

    String info() {
        return String.format(java.util.Locale.ROOT,
                "historyFrames=%d historyResets=%d historyCpuMs=%.3f historyGpuMs=%.3f historyGpuSamples=%d",
                frames, resets, cpuMillis, gpuMillis, gpuSamples);
    }
}
