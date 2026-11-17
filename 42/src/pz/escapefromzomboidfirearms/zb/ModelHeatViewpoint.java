package pz.escapefromzomboidfirearms.zb;

import java.nio.FloatBuffer;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoZombie;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.ModelMesh;
import zombie.core.skinnedmodel.model.SkinningData;
import zombie.core.skinnedmodel.shader.Shader;

/** Uniform temperature on the captured animated surface. Camera depth carries
 * the native visible silhouette; diffuse inputs never supply temperature.
 * Viewpoint stands its models on its terrain relief while drawing them; the
 * same lift, read through its own FarRelief.Lift, places this surface there.
 * A record the mask cannot draw (a model of another mod without a usable bone
 * palette) is left out and its character gets VpHeat's capsule body for a
 * while; an error of the mask itself leaves every warm character its capsule
 * body. The sensors stay on either way (user's report of 21.10.2026, item 7:
 * one such record turned every sensor off until the game was restarted). */
public final class ModelHeatViewpoint {
    /** Visible-surface match in metres of view distance: fixed, per metre of distance. */
    static final float TOLERANCE = .04f, TOLERANCE_PER_METRE = .006f;
    /** Half step of the relief slope sampled around a record (scene units). */
    static final float SLOPE_STEP = .5f;
    static volatile boolean legacy;
    /** QA only (debug): 1 draws without the relief lift, 2 without the visible-surface test. */
    static volatile int qaMode;
    /** QA only (debug, FB6): 1 every skinned record of a zombie as another mod's model without a usable
     *  bone palette, 2 the mask failing as a whole. */
    static volatile int qaFault;
    public static volatile long draws, errors, reliefErrors, skipped;
    /** Characters with a record left out, and when (ms): VpHeat.capture gives them the capsule body meanwhile. */
    static final java.util.concurrent.ConcurrentHashMap<IsoGameCharacter, Long> capsules = new java.util.concurrent.ConcurrentHashMap<>();
    static final long CAPSULE_MS = 2000, CAPSULE_KEEP_MS = 10000;
    /** The mask itself failed: off for the session, every warm character gets its capsule body. */
    static volatile boolean off;
    static volatile String offReason = "", lastSkip = "";
    private static final java.util.Set<String> logged = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public static volatile int lastSkin, lastClothing, missingOwners, sourceClothing, animationOwners, depthOnly;
    public static volatile int lastUpright, lastPlaced, lastEachVertex;
    public static volatile float lastLiftMax;
    private static Shader skinned, rigid;

    /** Render thread: the heat model shaders, at the first use or ahead of it (ShaderWarmup). */
    static void shaders() {
        if (skinned == null) {
            skinned = ShaderWarmup.made(new Shader("tw_vp_heat_model", false, false));
            rigid = ShaderWarmup.made(new Shader("tw_vp_heat_model", true, false));
        }
    }
    private static FloatBuffer bones;
    private static int sampler;
    /** Render thread: the record's model matrix handed to the uniform. */
    private static final Matrix4f model = new Matrix4f();

    private ModelHeatViewpoint() { }

    @me.zed_0xff.zombie_buddy.Exposer.LuaClass(name = "TWFB2BOpticsQA")
    public static final class QA {
        private QA() { }
        public static boolean legacyHeat(boolean on) {
            if (!zombie.Lua.LuaManager.GlobalObject.getDebug()) return false;
            legacy = on;
            return true;
        }
        /** FB6 diagnostic: 0 production (the mask on again), 1 zombie records without bones, 2 the mask failing. */
        public static boolean fault(int mode) {
            if (!zombie.Lua.LuaManager.GlobalObject.getDebug() || mode < 0 || mode > 2) return false;
            qaFault = mode;
            if (mode == 0) { off = false; offReason = ""; capsules.clear(); }
            return true;
        }
        /** Diagnostic A/B of the model mask: 0 production, 1 no relief lift, 2 no visible-surface test. */
        public static boolean maskMode(int mode) {
            if (!zombie.Lua.LuaManager.GlobalObject.getDebug() || mode < 0 || mode > 2) return false;
            qaMode = mode;
            return true;
        }
    }

    /** VpHeat.capture (main thread): whether this warm character gets the capsule body this frame. */
    static boolean capsule(IsoGameCharacter c, long now) {
        if (off) return true;
        Long at = capsules.get(c);
        return at != null && now - at < CAPSULE_MS;
    }

    /** VpHeat.capture: characters whose records have drawn again for a while are forgotten. */
    static void prune(long now) {
        capsules.values().removeIf(at -> now - at > CAPSULE_KEEP_MS);
    }

    /** A record left out: its character gets the capsule body; one line per kind (reason, mesh, owner), 12 a session. */
    static void skip(IsoGameCharacter character, String reason, ModelInstance instance, ModelMesh mesh, int bones, int palette, int flags) {
        skipped++;
        if (character != null) capsules.put(character, System.currentTimeMillis());
        String what = reason + " mesh=" + (mesh.fullPath != null ? mesh.fullPath : instance.model != null ? instance.model.name : "?")
                + " owner=" + (character == null ? "none" : character.getClass().getSimpleName());
        lastSkip = what + " bones=" + bones + " palette=" + palette + " flags=" + flags;
        if (logged.size() < 12 && logged.add(what))
            System.out.println("[TWZB] viewpoint heat model: record left out, its character gets the capsule body: " + lastSkip);
    }

    /** The mask failed as a whole: off with its cause in the log; the capsule bodies take over (VpHeat.capture). */
    static void fail(Throwable error) {
        errors++;
        if (off) return;
        off = true;
        offReason = VpSensor.describe(error);
        System.out.println("[TWZB] viewpoint heat model mask off, capsule bodies instead: " + offReason);
    }

    /**
     * Bones of a skinned record for the palette: its mesh's skinning data, else its instance's, its
     * model's (Model.tag, where Viewpoint reads it) or its animation player's; 0 without any. Models
     * of other mods carry theirs in any of these.
     */
    static int boneCount(ModelInstance instance, ModelMesh mesh) {
        return boneCount(mesh.skinningData, instance);
    }

    static int boneCount(SkinningData meshData, ModelInstance instance) {
        if (meshData != null && meshData.numBones() > 0) return meshData.numBones();
        if (instance.data != null && instance.data.numBones() > 0) return instance.data.numBones();
        if (instance.model != null && instance.model.tag instanceof SkinningData sd && sd.numBones() > 0) return sd.numBones();
        SkinningData played = instance.animPlayer == null ? null : instance.animPlayer.getSkinningData();
        return played == null ? 0 : played.numBones();
    }

    static IsoGameCharacter owner(ModelInstance instance) {
        for (int i = 0; instance != null && i < 32; i++, instance = instance.parent)
            if (instance.character != null) return instance.character;
        return null;
    }

    static boolean held(ModelInstance instance, IsoGameCharacter owner) {
        for (int i = 0; instance != null && i < 32; i++, instance = instance.parent)
            if (instance == owner.primaryHandModel || instance == owner.secondaryHandModel) return true;
        return false;
    }

    /** Row-major affine bone records -> ordinary column-major GLSL matrices. */
    public static void palette(float[] source, int offset, int count, FloatBuffer output) {
        output.clear();
        for (int i = 0; i < count; i++) {
            int o = (offset + i) * 12;
            if (o < 0 || o + 12 > source.length) throw new IllegalArgumentException("thermal bone palette bounds");
            for (int col = 0; col < 4; col++)
                output.put(source[o + col]).put(source[o + 4 + col]).put(source[o + 8 + col]).put(col == 3 ? 1f : 0f);
        }
        output.flip();
    }

    /** Viewpoint's lift (scene units) at a scene point x, z; 0 without relief. */
    interface Relief { float at(float x, float z) throws Throwable; }

    /**
     * The terrain lift of one record into out: lift at (x, z), its slope along
     * x and z, and 1 for a single rigid lift. placed: never lifted; upright:
     * the whole model stands with its feet (x, z); otherwise each point keeps
     * the local ground, here the plane of the relief through (x, z).
     */
    static void lift(Relief relief, float x, float z, boolean upright, boolean placed, float[] out) throws Throwable {
        out[0] = out[1] = out[2] = 0f;
        out[3] = 1f;
        if (relief == null || placed) return;
        out[0] = relief.at(x, z);
        if (upright) return;
        out[1] = (relief.at(x + SLOPE_STEP, z) - relief.at(x - SLOPE_STEP, z)) / (2f * SLOPE_STEP);
        out[2] = (relief.at(x, z + SLOPE_STEP) - relief.at(x, z - SLOPE_STEP)) / (2f * SLOPE_STEP);
        out[3] = 0f;
        for (float v : out) if (!Float.isFinite(v)) throw new IllegalStateException("relief lift not finite");
    }

    static void render(VpHeat.Frame frame, Matrix4f view, Matrix4f projection, int depth,
                       float scaleX, float scaleY, int width, int height) {
        if (off || frame.modelInputs.isEmpty() || frame.scene == null || depth == 0) return;
        try {
            if (qaFault == 2) throw new IllegalStateException("QA fault: the mask fails", new IllegalArgumentException("QA cause"));
            if (!ViewpointPipBridge.ready()) throw new IllegalStateException("thermal model interface unavailable");
            Object data = VpBlend.records(frame.scene, ViewpointPipBridge.get("SceneData.models", frame.scene));
            ModelInstance[] instances = (ModelInstance[]) ViewpointPipBridge.get("ModelDraws.instances", data);
            ModelMesh[] meshes = (ModelMesh[]) ViewpointPipBridge.get("ModelDraws.meshes", data);
            float[] values = (float[]) ViewpointPipBridge.get("ModelDraws.values", data);
            int[] palettes = (int[]) ViewpointPipBridge.get("ModelDraws.palette", data);
            float[] matrices = (float[]) ViewpointPipBridge.get("ModelDraws.palettes", data);
            int[] flags = (int[]) ViewpointPipBridge.get("ModelDraws.flags", data);
            int count = (int) ViewpointPipBridge.get("ModelDraws.count", data);
            int stride = (int) ViewpointPipBridge.get("ModelDraws.VALUES", null);
            int colour = (int) ViewpointPipBridge.get("ModelDraws.COLOUR", null);
            int stand = (int) ViewpointPipBridge.get("ModelDraws.STAND", null);
            int upright = (int) ViewpointPipBridge.get("ModelDraws.UPRIGHT", null);
            int placed = (int) ViewpointPipBridge.get("ModelDraws.PLACED", null);
            if (stride < 16 || stand < 16 || stand + 4 > stride || (int) ViewpointPipBridge.get("ModelDraws.BONE_FLOATS", null) != 12)
                throw new IllegalStateException("thermal model record layout changed");
            Object lift = ViewpointPipBridge.get("SceneData.relief", frame.scene);
            // Performance pass 23.10.2026: the same FarRelief$Lift.sceneAt through a method handle (5 per record, was
            // Method.invoke with boxed arguments).
            Relief relief = lift == null ? null : (x, z) -> ViewpointPipBridge.sceneAt(lift, x, z);
            shaders();
            if (bones == null) {
                bones = BufferUtils.createFloatBuffer(128 * 16);
                sampler = GL33.glGenSamplers();
                GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL33.glSamplerParameteri(sampler, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
            }
            if (!skinned.getShaderProgram().isCompiled() || !rigid.getShaderProgram().isCompiled())
                throw new IllegalStateException("thermal model shader did not compile");
            // The nearest visible surface of all warm models wins (clothing over the
            // skin under it); later heat primitives combine with GL_MAX as before.
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_DEPTH_TEST); GL11.glDepthFunc(GL11.GL_LESS); GL11.glDepthMask(true);
            Matrix4f vp = new Matrix4f(projection).mul(view);
            java.util.IdentityHashMap<Object, IsoGameCharacter> animated = new java.util.IdentityHashMap<>();
            for (IsoGameCharacter character : frame.modelInputs.keySet())
                if (character.getAnimationPlayer() != null) animated.put(character.getAnimationPlayer(), character);
            int skins = 0, clothes = 0, missing = 0, sourceClothes = 0, animationMatches = 0, noColour = 0;
            int uprights = 0, placements = 0, eachVertex = 0;
            float liftMax = 0f;
            float[] l = new float[4];
            for (int i = 0; i < count; i++) {
                ModelInstance instance = instances[i];
                ModelMesh mesh = meshes[i];
                if (instance == null || mesh == null || mesh.vb == null) continue;
                boolean clothing = instance.getItemVisual() != null;
                if (clothing) sourceClothes++;
                IsoGameCharacter character = owner(instance);
                if (character == null && instance.animPlayer != null) {
                    character = animated.get(instance.animPlayer);
                    if (character != null) animationMatches++;
                }
                if (character == null && clothing) missing++;
                Float input = frame.modelInputs.get(character);
                if (input == null || held(instance, character)) continue;
                boolean skin = !mesh.vb.isStatic;
                Shader shader = skin ? skinned : rigid;
                // The game's own skinning shaders take 60 bones, the mask's palette 128.
                int nBones = skin ? Math.min(128, qaFault == 1 && character instanceof IsoZombie ? 0 : boneCount(instance, mesh)) : 0;
                if (skin && (nBones <= 0 || palettes[i] < 0 || (palettes[i] + nBones) * 12 > matrices.length)) {
                    // Shadow-only records may have no colour-pose palette.
                    if ((flags[i] & colour) == 0) continue;
                    skip(character, "no bone palette", instance, mesh, nBones, palettes[i], flags[i]);
                    continue;
                }
                int s = i * stride + stand, mode = (int) values[s + 3];
                boolean standing = mode == upright, fixed = mode == placed;
                // Each-vertex records have no feet: the plane goes through the model's origin.
                float x = standing ? values[s] : values[i * stride + 12], z = standing ? values[s + 2] : values[i * stride + 14];
                int diagnostic = qaMode;
                try {
                    lift(diagnostic == 1 ? null : relief, x, z, standing, fixed, l);
                } catch (Throwable t) {
                    // Drawn where its record stands, without the terrain lift.
                    reliefErrors++;
                    l[0] = l[1] = l[2] = 0f;
                    l[3] = 1f;
                }
                if (fixed) placements++; else if (standing) uprights++; else eachVertex++;
                liftMax = Math.max(liftMax, Math.abs(l[0]));
                shader.Start();
                try {
                shader.getShaderProgram().setValue("ViewProjection", vp);
                // the uniform is copied out at once (ShaderProgram.setTransformMatrix): one scratch matrix serves every record
                shader.getShaderProgram().setValue("Model", model.set(values, i * stride));
                shader.getShaderProgram().setVector4("Lift", l[0], l[1], l[2], l[3]);
                shader.getShaderProgram().setVector2("LiftAt", x, z);
                shader.getShaderProgram().setVector4("Target", width, height, scaleX, scaleY);
                shader.getShaderProgram().setVector2("DepthLinear", projection.m32(), projection.m22());
                shader.getShaderProgram().setVector2("Tolerance", diagnostic == 2 ? 1e6f : TOLERANCE, TOLERANCE_PER_METRE);
                shader.getShaderProgram().setValue("Input", input / HeatRenderer.SCALE
                        * (clothing ? .9f : 1f));
                shader.getShaderProgram().setSamplerUnit("SceneDepth", 1);
                GL13.glActiveTexture(GL13.GL_TEXTURE1); GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth); GL33.glBindSampler(1, sampler);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                if (skin) { palette(matrices, palettes[i], nBones, bones); shader.setMatrixPalette(bones, false); }
                mesh.vb.Draw(shader);
                } finally {
                    shader.End();
                }
                draws++;
                if (clothing) clothes++; else skins++;
                if ((flags[i] & colour) == 0) noColour++;
            }
            GL33.glBindSampler(1, 0);
            lastSkin = skins; lastClothing = clothes; missingOwners = missing;
            sourceClothing = sourceClothes; animationOwners = animationMatches;
            depthOnly = noColour;
            lastUpright = uprights; lastPlaced = placements; lastEachVertex = eachVertex; lastLiftMax = liftMax;
        } catch (Throwable error) {
            fail(error);
        } finally {
            GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_BLEND); GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        }
    }
}
