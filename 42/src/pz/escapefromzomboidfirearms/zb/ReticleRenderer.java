package pz.escapefromzomboidfirearms.zb;

import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import zombie.core.SpriteRenderer;
import zombie.core.textures.Texture;
import static pz.escapefromzomboidfirearms.zb.ReticleProfile.*;

/**
 * Draws only our reticles into the caller's bound framebuffer, on the render thread.
 * No Viewpoint, events, hooks, camera state or automatic registration. The caller
 * supplies a captured profile, current vertical FOV and viewport-local lens pixels.
 * Shader-specific angular projection and authored illumination are captured in the profile.
 */
public final class ReticleRenderer {
    private static TwProgram program;

    /** Render thread: the reticle program, at the first use or ahead of it (ShaderWarmup). */
    static void shaders() {
        if (program == null) program = ShaderWarmup.made(TwProgram.create("tw_reticle"));
    }
    private static int vao, vbo, sampler;
    private static FloatBuffer buffer;
    private static boolean failed;
    /**
     * Performance pass 23.10.2026 (render thread only): the unit-lens stream of each layer
     * (ReticleGeometry.unitStream: the mesh, its morph and near clip rebuilt with heavy allocation for every layer,
     * every frame), kept while its profile, mesh and FOV stay; only the aperture map runs per frame, into placed.
     * The drawn vertices are the same floats as ReticleGeometry.coloredVertices'.
     */
    private record Unit(ReticleProfile profile, ReticleMeshData.Mesh mesh, int fov, float[] stream) { }
    private static final java.util.IdentityHashMap<Layer, Unit> units = new java.util.IdentityHashMap<>();
    private static long unitFloats;
    private static float[] placed = new float[0];
    private static final int[] viewport = new int[4];
    private static final org.joml.Matrix4f ortho = new org.joml.Matrix4f();
    private static final ScopePipGL.Uniforms glass = new ScopePipGL.Uniforms(GLCompat.elements("GlassBoundary", 64));

    private ReticleRenderer() { }

    public static boolean draw(ReticleProfile profile, float fovDegrees, float lensCenterX, float lensCenterY,
                               float lensRadiusPx, float illuminationLevel, float rollRadians) {
        if (profile == null || failed) return false;
        try {
            return draw(profile, fovDegrees, ReticleGeometry.Lens.circle(lensCenterX, lensCenterY, lensRadiusPx),
                    illuminationLevel, rollRadians, false);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /**
     * Lens pixels use the current viewport, including a PIP target or a split-screen viewport.
     * writeAlpha permits alpha on normal overlays; emissive collimators preserve target alpha.
     * Illumination is a nonnegative continuous multiplier, not invented Tarkov brightness steps.
     * It carries the player's per-sight brightness dial (user's item 14, 26.10.2026) over the
     * authored Tarkov illumination; 1 is the authored brightness, exactly as before the dial.
     * Zero illumination leaves an etched optical mark black; an emissive collimator adds no light.
     */
    public static boolean draw(ReticleProfile profile, float fovDegrees, ReticleGeometry.Lens lens,
                               float illuminationLevel, float rollRadians, boolean writeAlpha) {
        if (lens == null || !Float.isFinite(rollRadians)) return false;
        float c = (float) Math.cos(rollRadians), s = (float) Math.sin(rollRadians);
        ReticleGeometry.Aperture aperture = new ReticleGeometry.Aperture(lens.x(), lens.y(),
                c * lens.halfWidth(), s * lens.halfWidth(), -s * lens.halfHeight(), c * lens.halfHeight(), lens.circle());
        return draw(profile, fovDegrees, aperture, aperture, illuminationLevel, writeAlpha, null, 0, 0f, lens, rollRadians);
    }

    /** Exact projected ocular axes, including foreshortening and shear. */
    public static boolean draw(ReticleProfile profile, float fovDegrees, ReticleGeometry.Aperture aperture,
                               float illuminationLevel, boolean writeAlpha) {
        return draw(profile, fovDegrees, aperture, aperture, illuminationLevel, writeAlpha);
    }

    /** Glass may anchor the mark on the channel direction while clipping to a different aperture. */
    public static boolean draw(ReticleProfile profile, float fovDegrees, ReticleGeometry.Aperture aperture,
                               ReticleGeometry.Aperture mark, float illuminationLevel, boolean writeAlpha) {
        return draw(profile, fovDegrees, aperture, mark, illuminationLevel, writeAlpha, null);
    }

    /** Optional ordered source-glass boundary in normalized aperture coordinates, at most 64 vertices. */
    public static boolean draw(ReticleProfile profile, float fovDegrees, ReticleGeometry.Aperture aperture,
                               ReticleGeometry.Aperture mark, float illuminationLevel, boolean writeAlpha, float[] boundary) {
        return draw(profile, fovDegrees, aperture, mark, illuminationLevel, writeAlpha, boundary, 0, 0f);
    }

    public static boolean draw(ReticleProfile profile, float fovDegrees, ReticleGeometry.Aperture aperture,
                               ReticleGeometry.Aperture mark, float illuminationLevel, boolean writeAlpha, float[] boundary,
                               int sceneDepth, float lensDepth) {
        return draw(profile, fovDegrees, aperture, mark, illuminationLevel, writeAlpha, boundary, sceneDepth, lensDepth, null, 0f);
    }

    private static boolean draw(ReticleProfile profile, float fovDegrees, ReticleGeometry.Aperture aperture,
                                ReticleGeometry.Aperture mark, float illuminationLevel, boolean writeAlpha, float[] boundary,
                                int sceneDepth, float lensDepth, ReticleGeometry.Lens legacyLens, float legacyRoll) {
        return draw(profile, fovDegrees, aperture, mark, illuminationLevel, writeAlpha, boundary, sceneDepth, lensDepth,
                legacyLens, legacyRoll, null);
    }

    /** Tilted electronic screen; slopes use the caller's normalized aperture basis. */
    public static boolean draw(ReticleProfile profile, float fovDegrees, ReticleGeometry.Aperture aperture,
                               ReticleGeometry.Aperture mark, float illuminationLevel, boolean writeAlpha, float[] boundary,
                               int sceneDepth, float lensDepth, float[] depthSlope) {
        return draw(profile, fovDegrees, aperture, mark, illuminationLevel, writeAlpha, boundary, sceneDepth, lensDepth,
                null, 0f, depthSlope);
    }

    private static boolean draw(ReticleProfile profile, float fovDegrees, ReticleGeometry.Aperture aperture,
                                ReticleGeometry.Aperture mark, float illuminationLevel, boolean writeAlpha, float[] boundary,
                                int sceneDepth, float lensDepth, ReticleGeometry.Lens legacyLens, float legacyRoll, float[] depthSlope) {
        if (profile == null || aperture == null || mark == null || failed || profile.layers().isEmpty()
                || !Float.isFinite(illuminationLevel) || illuminationLevel < 0f) return false;
        if (boundary != null) {
            if (boundary.length < 6 || boundary.length > 128 || boundary.length % 2 != 0) return false;
            for (float value : boundary) if (!Float.isFinite(value)) return false;
        }
        int[] viewport = ReticleRenderer.viewport;
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        if (viewport[2] <= 0 || viewport[3] <= 0) return false;
        int oldProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int oldActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int oldVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int oldBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        int oldDepthTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        // -1: a sampler binding this context cannot read back (macOS Metal shim) is not written back (GLCompat)
        int oldSampler = -1, oldDepthSampler = -1;
        GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_ENABLE_BIT
                | GL11.GL_TEXTURE_BIT | GL11.GL_SCISSOR_BIT | GL11.GL_STENCIL_BUFFER_BIT | GL11.GL_POLYGON_BIT);
        try {
            oldSampler = GLCompat.sampler(0);
            oldDepthSampler = GLCompat.sampler(1);
            ensure();
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendEquation(GL14.GL_FUNC_ADD); // RGB and alpha; VPMetal has no glBlendEquationSeparate
            GL11.glColorMask(true, true, true, writeAlpha);
            program.Start();
            program.setValue("ModelViewProjection", ortho.setOrtho2D(0, viewport[2], viewport[3], 0));
            program.setSamplerUnit("MarkTex", 0);
            program.setVector4("Lens", aperture.x(), aperture.y(), aperture.ux(), aperture.uy());
            program.setVector2("OtherAxis", aperture.vx(), aperture.vy());
            program.setValue("RoundLens", aperture.round() ? 1f : 0f);
            program.setValue("GlassCount", boundary == null ? 0 : boundary.length / 2);
            if (boundary != null) GLCompat.uniform2(program.locations(glass), boundary);
            program.setSamplerUnit("SceneDepth", 1);
            program.setValue("DepthOn", sceneDepth != 0 ? 1f : 0f);
            program.setValue("LensDepth", lensDepth);
            program.setVector2("LensDepthSlope", depthSlope == null ? 0f : depthSlope[0], depthSlope == null ? 0f : depthSlope[1]);
            program.setVector4("Viewport", viewport[0], viewport[1], viewport[2], viewport[3]);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneDepth);
            GL33.glBindSampler(1, sampler);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL33.glBindSampler(0, sampler);
            boolean drew = false;
            for (Layer layer : profile.layers()) {
                Texture texture = null;
                ReticleMeshData.Mesh mesh = null;
                if (layer.mesh() == null) {
                    texture = Texture.getSharedTexture(layer.texture());
                    if (texture == null || !texture.isReady()) continue;
                } else {
                    ReticleMeshData data = ReticleMeshData.get();
                    if (data == null || (mesh = data.mesh(layer.mesh())) == null) continue;
                }
                float[] vertices;
                int count;
                try {
                    if (legacyLens != null && layer.collimator()) {
                        float focal = ReticleGeometry.focalPixels(fovDegrees, viewport[3] * .5f);
                        float c = (float) Math.cos(legacyRoll), s = (float) Math.sin(legacyRoll);
                        ReticleGeometry.Aperture angular = new ReticleGeometry.Aperture(legacyLens.x(), legacyLens.y(),
                                c * focal, s * focal, -s * focal, c * focal, false);
                        vertices = ReticleGeometry.coloredVertices(profile, layer, mesh, fovDegrees, angular);
                        count = vertices.length;
                    } else if (legacyLens == null) {
                        // = ReticleGeometry.coloredVertices(profile, layer, mesh, fovDegrees, mark), from the kept stream
                        float[] unit = unitStream(profile, layer, mesh, fovDegrees);
                        count = unit.length;
                        if (placed.length < count) placed = new float[count];
                        ReticleGeometry.place(unit, count, mark, placed);
                        vertices = placed;
                    } else {
                        vertices = ReticleGeometry.coloredVertices(profile, layer, mesh, fovDegrees, legacyLens, legacyRoll);
                        count = vertices.length;
                    }
                } catch (IllegalArgumentException invalid) {
                    continue; // Unknown camera data must not invent a FOV or poison other scopes.
                }
                if (count == 0) continue;
                V4 color = ReticleGeometry.color(layer, illuminationLevel);
                program.setVector4("MarkColor", color.x(), color.y(), color.z(), color.w());
                program.setValue("MarkLightness", layer.opticTexture()
                        ? Math.max(0f, layer.material().lightness()) * illuminationLevel : 0f);
                program.setValue("MarkKind", layer.mesh() != null ? 0f : "alpha".equals(layer.channels()) ? 1f : 2f);
                // These vanilla programs do not use _MarkTex_ST. Retain the source
                // values in the snapshot without applying a transform absent from the GPU program.
                program.setVector4("UVTransform", layer.projection() == null ? layer.uvScale().x() : 1f,
                        layer.projection() == null ? layer.uvScale().y() : 1f,
                        layer.projection() == null ? layer.uvOffset().x() : 0f,
                        layer.projection() == null ? layer.uvOffset().y() : 0f);
                program.setVector2("WrapMode", layer.wrapU(), layer.wrapV());
                program.setVector4("TextureRect", texture == null ? 0f : texture.getXStart(), texture == null ? 0f : texture.getYStart(),
                        texture == null ? 1f : texture.getXEnd(), texture == null ? 1f : texture.getYEnd());
                boolean emissive = "collimator".equals(profile.kind());
                GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, emissive ? GL11.GL_ONE : GL11.GL_ONE_MINUS_SRC_ALPHA,
                        emissive ? GL11.GL_ZERO : GL11.GL_ONE, emissive ? GL11.GL_ONE : GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture == null ? 0 : texture.getID());
                if (buffer == null || buffer.capacity() < count)
                    buffer = BufferUtils.createFloatBuffer(count);
                buffer.clear();
                buffer.put(vertices, 0, count).flip();
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STREAM_DRAW);
                GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 36, 0L);
                GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, 36, 12L);
                GL20.glVertexAttribPointer(3, 4, GL11.GL_FLOAT, false, 36, 20L);
                GL20.glEnableVertexAttribArray(0);
                GL20.glEnableVertexAttribArray(2);
                GL20.glEnableVertexAttribArray(3);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, count / 9);
                drew = true;
            }
            return drew;
        } catch (Exception | LinkageError error) {
            failed = true;
            System.out.println("[TWZB] Reticle renderer disabled: " + error);
            return false;
        } finally {
            // Own VAO, sampler and shader only. No FBO, viewport or engine state-cache restore changes.
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTexture);
            GLCompat.restoreSampler(0, oldSampler);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldDepthTexture);
            GLCompat.restoreSampler(1, oldDepthSampler);
            GL30.glBindVertexArray(oldVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, oldBuffer);
            GL11.glPopAttrib();
            GL13.glActiveTexture(oldActive);
            GLCompat.useProgram(oldProgram);
            Texture.lastTextureID = -1;
            SpriteRenderer.ringBuffer.restoreBoundTextures = true;
            SpriteRenderer.ringBuffer.restoreVbos = true;
        }
    }

    /** The layer's unit-lens stream: kept, or built (and kept) as ReticleGeometry.unitStream builds it; it may throw as that does. */
    private static float[] unitStream(ReticleProfile profile, Layer layer, ReticleMeshData.Mesh mesh, float fov) {
        int bits = Float.floatToRawIntBits(layer.collimator() ? 90f : fov);
        Unit kept = units.get(layer);
        if (kept != null && kept.profile == profile && kept.mesh == mesh && kept.fov == bits) return kept.stream;
        float[] stream = ReticleGeometry.unitStream(profile, layer, mesh, fov);
        if (kept != null) unitFloats -= kept.stream.length;
        // bounded: profiles are captured anew on a sight change; a zooming scope replaces its layer's entry
        if (units.size() >= 64 || unitFloats + stream.length > (1 << 22)) {
            units.clear();
            unitFloats = 0;
        }
        units.put(layer, new Unit(profile, mesh, bits, stream));
        unitFloats += stream.length;
        return stream;
    }

    private static void ensure() {
        shaders();
        if (!program.isCompiled()) throw new IllegalStateException("tw_reticle shader did not compile");
        if (vao != 0) return;
        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();
        sampler = GL33.glGenSamplers();
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }
}
