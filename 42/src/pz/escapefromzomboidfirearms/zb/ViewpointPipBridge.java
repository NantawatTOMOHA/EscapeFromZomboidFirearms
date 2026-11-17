package pz.escapefromzomboidfirearms.zb;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

/**
 * Optional, separately guarded PIP interaction with Viewpoint. No Viewpoint
 * resources or code are bundled. Each direct member, including constructors
 * and the complete mutable FrameContext, is checked before the first use.
 * A failure latches PIP off; the ordinary aiming/pose bridge stays usable.
 */
public final class ViewpointPipBridge {
    // owner, name, JVM descriptor, static. Constructors use <init>.
    static final String[][] MEMBERS = {
        {"viewpoint.render.WorldRenderer", "frame", "Lviewpoint/render/FrameContext;", "static"},
        {"viewpoint.render.WorldRenderer", "targets", "Lviewpoint/render/Targets;", "static"},
        {"viewpoint.render.WorldRenderer", "indirect", "Lviewpoint/render/IndirectPass;", "static"},
        {"viewpoint.render.WorldRenderer", "temporal", "Lviewpoint/render/TemporalPass;", "static"},
        {"viewpoint.render.WorldRenderer", "far", "Lviewpoint/render/FarPass;", "static"},
        {"viewpoint.render.WorldRenderer", "floors", "Lviewpoint/render/FloorBakes;", "static"},
        {"viewpoint.render.WorldRenderer", "NEAR", "F", "static"},
        {"viewpoint.render.WorldRenderer", "FAR", "F", "static"},
        {"viewpoint.render.WorldRenderer", "bindTarget", "()V", "static"},
        {"viewpoint.render.WorldRenderer", "cullFromPlayer", "Z", "static"},
        {"viewpoint.render.WorldRenderer", "shadows", "Lviewpoint/render/ShadowPass;", "static"},
        {"viewpoint.render.WorldRenderer", "weatherMap", "Lviewpoint/render/WeatherMap;", "static"},
        {"viewpoint.render.ShadowPass", "farMaps", "Lviewpoint/render/FarShadow;", ""},
        {"viewpoint.render.FrameContext", "<init>", "()V", ""},
        {"viewpoint.render.Targets", "<init>", "()V", ""},
        {"viewpoint.render.Targets", "ensure", "(IIIII)Z", ""},
        {"viewpoint.render.Targets", "delete", "()V", ""},
        {"viewpoint.render.Targets", "depth", "I", ""},
        {"viewpoint.render.Targets", "hdr", "I", ""},
        {"viewpoint.render.Targets", "lightFbo", "I", ""},
        {"viewpoint.render.Targets", "halfWidth", "I", ""},
        {"viewpoint.render.Targets", "halfHeight", "I", ""},
        {"viewpoint.render.Targets", "exposure", "I", ""},
        {"viewpoint.render.Targets", "rooms", "I", ""},
        {"viewpoint.render.Targets", "water", "I", ""},
        {"viewpoint.render.Targets", "noise", "I", ""},
        {"viewpoint.render.Targets", "noise2d", "I", ""},
        {"viewpoint.render.Targets", "cloudNoise", "Lviewpoint/render/CloudNoise;", ""},
        {"viewpoint.render.Targets", "starField", "Lviewpoint/render/StarField;", ""},
        {"viewpoint.render.Targets", "toneLut", "Lviewpoint/render/ToneLut;", ""},
        {"viewpoint.render.Targets", "floorPlan", "Lviewpoint/render/FloorPlan;", ""},
        {"viewpoint.render.Targets", "lampFields", "Lviewpoint/render/LampFields;", ""},
        {"viewpoint.render.Targets", "air", "Lviewpoint/render/AirLuts;", ""},
        {"viewpoint.render.Targets", "rainTextures", "Lviewpoint/render/RainTextures;", ""},
        {"viewpoint.render.SceneData", "projection", "Lorg/joml/Matrix4f;", ""},
        {"viewpoint.render.SceneData", "originX", "D", ""},
        {"viewpoint.render.SceneData", "originY", "D", ""},
        {"viewpoint.render.SceneData", "originZ", "D", ""},
        {"viewpoint.render.SceneData", "relief", "Lviewpoint/render/FarRelief$Lift;", ""},
        {"viewpoint.render.FarRelief$Lift", "levels", "()F", ""},
        {"viewpoint.render.FarRelief$Lift", "ground", "()F", ""},
        {"viewpoint.render.FarRelief$Lift", "camX", "()D", ""},
        {"viewpoint.render.FarRelief$Lift", "camY", "()D", ""},
        {"viewpoint.render.FarRelief$Lift", "sceneAt", "(FF)F", ""},
        {"viewpoint.render.FarRelief", "frame", "(Lviewpoint/render/SceneData;)V", "static"},
        {"viewpoint.render.FarRelief", "drawing", "Lviewpoint/render/SceneData;", "static"},
        {"viewpoint.render.FarRelief", "bound", "I", "static"},
        {"viewpoint.platform.GlProgram", "onUse", "Ljava/util/function/Consumer;", "static"},
        {"viewpoint.platform.GlProgram", "forEach", "(Ljava/util/function/Consumer;)V", "static"},
        {"viewpoint.platform.GlProgram", "id", "()I", ""},
        {"viewpoint.platform.GlProgram", "name", "Ljava/lang/String;", ""},
        {"viewpoint.render.SceneData", "panini", "F", ""},
        {"viewpoint.render.SceneData", "cameraCut", "Z", ""},
        {"viewpoint.render.SceneData", "models", "Lviewpoint/render/ModelDraws;", ""},
        {"viewpoint.render.SceneData", "meshCount", "I", ""},
        {"viewpoint.render.SceneData", "meshFade", "[F", ""},
        {"viewpoint.render.SceneData", "meshPlan", "[I", ""},
        {"viewpoint.render.SceneData", "meshFlags", "[B", ""},
        {"viewpoint.render.SceneData", "VISIBLE", "B", "static"},
        {"viewpoint.render.SceneData", "farCount", "I", ""},
        {"viewpoint.render.SceneData", "shellCount", "I", ""},
        {"viewpoint.render.SceneData", "rooms", "[B", ""},
        {"viewpoint.render.Meshes", "chosen", "[Z", "static"},
        {"viewpoint.visibility.Rooms", "mode", "I", "static"},
        {"viewpoint.visibility.Rooms", "hiding", "Z", "static"},
        {"viewpoint.visibility.Rooms", "captureFailed", "Z", "static"},
        {"viewpoint.visibility.Rooms", "state", "Ljava/lang/String;", "static"},
        {"viewpoint.visibility.DrawPlans", "culledVertices", "J", "static"},
        {"viewpoint.render.Meshes", "prepare", "(Lviewpoint/render/SceneData;)V", "static"},
        {"viewpoint.render.MeshArena", "records", "Ljava/nio/FloatBuffer;", "static"},
        {"viewpoint.render.MeshArena", "recordBuffer", "I", "static"},
        {"viewpoint.render.MeshArena", "recordsAt", "J", "static"},
        {"viewpoint.render.MeshArena", "recordBytes", "J", "static"},
        {"viewpoint.render.ModelDraws", "instances", "[Lzombie/core/skinnedmodel/model/ModelInstance;", ""},
        {"viewpoint.render.ModelDraws", "meshes", "[Lzombie/core/skinnedmodel/model/ModelMesh;", ""},
        {"viewpoint.render.ModelDraws", "values", "[F", ""},
        {"viewpoint.render.ModelDraws", "VALUES", "I", "static"},
        {"viewpoint.render.ModelDraws", "palette", "[I", ""},
        {"viewpoint.render.ModelDraws", "palettes", "[F", ""},
        {"viewpoint.render.ModelDraws", "BONE_FLOATS", "I", "static"},
        {"viewpoint.render.ModelDraws", "flags", "[I", ""},
        {"viewpoint.render.ModelDraws", "count", "I", ""},
        {"viewpoint.render.ModelDraws", "COLOUR", "I", "static"},
        {"viewpoint.render.ModelDraws", "SHADOW", "I", "static"},
        {"viewpoint.render.ModelDraws", "STAND", "I", "static"},
        {"viewpoint.render.ModelDraws", "UPRIGHT", "I", "static"},
        {"viewpoint.render.ModelDraws", "PLACED", "I", "static"},
        {"viewpoint.render.CameraClear", "on", "Z", "static"},
        {"viewpoint.render.Panini", "NONE", "Lviewpoint/render/Panini;", "static"},
        {"viewpoint.render.Panini", "toScreen", "(FF[F)V", ""},
        {"viewpoint.render.FrameUniforms", "set", "(Lviewpoint/render/FrameContext;Lviewpoint/render/Targets;)V", "static"},
        {"viewpoint.render.FrameUniforms", "eyeSky", "F", "static"},
        {"viewpoint.render.FarPass", "trees", "Lviewpoint/render/TreeBaker;", ""},
        {"viewpoint.render.ModelPass", "<init>", "(Lviewpoint/render/Targets;)V", ""},
        {"viewpoint.render.ModelPass", "prepare", "(Lviewpoint/render/SceneData;)V|(Lviewpoint/render/SceneData;JFFFF)V", ""},
        {"viewpoint.render.ModelPass", "gbuffer", "(Lviewpoint/render/FrameContext;)V", ""},
        {"viewpoint.render.ModelPass", "endFrame", "()V", ""},
        {"viewpoint.render.WeatherMap", "<init>", "(I)V", ""},
        {"viewpoint.render.FarShadow", "<init>", "(Lviewpoint/render/TreeBaker;)V", ""},
        {"viewpoint.render.ShadowPass", "<init>", "(Lviewpoint/render/ModelPass;Lviewpoint/render/FarShadow;Lviewpoint/render/WeatherMap;)V|(Lviewpoint/render/ModelPass;Lviewpoint/render/FarShadow;Lviewpoint/render/WeatherMap;Lviewpoint/render/SnowPass;)V", ""},
        {"viewpoint.render.ShadowPass", "complete", "()Z", ""},
        {"viewpoint.render.ShadowPass", "draw", "(Lviewpoint/render/FrameContext;)V", ""},
        {"viewpoint.render.SurfacePass", "<init>", "(Lviewpoint/render/Targets;Lviewpoint/render/ShadowPass;)V", ""},
        {"viewpoint.render.SurfacePass", "gbuffer", "(Lviewpoint/render/FrameContext;)V", ""},
        {"viewpoint.render.SurfacePass", "light", "(Lviewpoint/render/FrameContext;)V", ""},
        {"viewpoint.render.FarPass", "<init>", "(Lviewpoint/render/Targets;Lviewpoint/render/ShadowPass;Lviewpoint/render/FarShadow;Lviewpoint/render/TreeBaker;)V", ""},
        {"viewpoint.render.FarPass", "prepare", "(Lviewpoint/render/FrameContext;Z)V", ""},
        {"viewpoint.render.FarPass", "gbuffer", "(Lviewpoint/render/FrameContext;)V", ""},
        {"viewpoint.render.FarPass", "draw", "(Lviewpoint/render/FrameContext;)V", ""},
        {"viewpoint.render.IndirectPass", "<init>", "(Lviewpoint/render/Targets;Lviewpoint/render/ShadowPass;Lviewpoint/render/FloorBakes;)V", ""},
        {"viewpoint.render.IndirectPass", "draw", "(Lviewpoint/render/FrameContext;ZZ)V|(Lviewpoint/render/FrameContext;Z)V", ""},
        {"viewpoint.render.SkyPass", "<init>", "(Lviewpoint/render/Targets;Lviewpoint/render/WeatherMap;)V", ""},
        {"viewpoint.render.SkyPass", "prepare", "(Lviewpoint/render/FrameContext;)V", ""},
        {"viewpoint.render.SkyPass", "clouds", "(Lviewpoint/render/FrameContext;)V", ""},
        {"viewpoint.render.SkyPass", "sky", "(Lviewpoint/render/FrameContext;)V", ""},
        {"viewpoint.render.TranslucentPass", "<init>", "(Lviewpoint/render/Targets;)V", ""},
        {"viewpoint.render.TranslucentPass", "draw", "(Lviewpoint/render/FrameContext;Lviewpoint/render/SurfacePass;Lviewpoint/render/ModelPass;Lviewpoint/render/ShadowPass;)V", ""},
        {"viewpoint.render.WeatherPass", "<init>", "(Lviewpoint/render/Targets;Lviewpoint/render/ShadowPass;Lviewpoint/render/WeatherMap;)V", ""},
        {"viewpoint.render.WeatherPass", "draw", "(Lviewpoint/render/FrameContext;)V", ""},
        {"viewpoint.render.WeatherPass", "raining", "(Lviewpoint/render/FrameContext;)Z", ""},
        {"viewpoint.render.WeatherPass", "falling", "(Lviewpoint/render/FrameContext;I)V", ""},
        {"viewpoint.render.WeatherPass", "overlay", "(Lviewpoint/render/FrameContext;)V", ""},
        {"viewpoint.render.BlurPass", "<init>", "()V", ""},
        {"viewpoint.render.VolumePass", "<init>", "(Lviewpoint/render/Targets;Lviewpoint/render/ShadowPass;Lviewpoint/render/BlurPass;)V", ""},
        {"viewpoint.render.VolumePass", "draw", "(Lviewpoint/render/FrameContext;)V", ""},
        {"viewpoint.render.PostPass", "<init>", "(Lviewpoint/render/Targets;)V", ""},
        {"viewpoint.render.PostPass", "bloom", "(Lviewpoint/render/FrameContext;)V", ""},
        {"viewpoint.render.PostPass", "composite", "(Lviewpoint/render/FrameContext;)V", ""},
        {"viewpoint.render.EyeMeter", "exposure", "(Lviewpoint/render/FrameContext;)F", "static"},
        {"viewpoint.render.TemporalPass", "<init>", "(Lviewpoint/render/Targets;)V", ""},
        {"viewpoint.render.AirLuts", "<init>", "()V", ""},
        {"viewpoint.render.AirLuts", "update", "()V", ""},
        {"viewpoint.render.AirLuts", "uploaded", "Z", ""},
        {"viewpoint.render.FrameContext", "scene", "Lviewpoint/render/SceneData;", ""},
        {"viewpoint.render.FrameContext", "pipeline", "Lviewpoint/platform/Pipeline;", ""},
        {"viewpoint.render.FrameContext", "view", "Lorg/joml/Matrix4f;", ""},
        {"viewpoint.render.FrameContext", "eyeX", "F", ""},
        {"viewpoint.render.FrameContext", "eyeY", "F", ""},
        {"viewpoint.render.FrameContext", "eyeZ", "F", ""},
        {"viewpoint.render.FrameContext", "viewRotation", "Lorg/joml/Matrix3f;", ""},
        {"viewpoint.render.FrameContext", "jitteredProjection", "Lorg/joml/Matrix4f;", ""},
        {"viewpoint.render.FrameContext", "viewProjection", "Lorg/joml/Matrix4f;", ""},
        {"viewpoint.render.FrameContext", "invViewProjection", "Lorg/joml/Matrix4f;", ""},
        {"viewpoint.render.FrameContext", "plainViewProjection", "Lorg/joml/Matrix4f;", ""},
        {"viewpoint.render.FrameContext", "invPlainViewProjection", "Lorg/joml/Matrix4f;", ""},
        {"viewpoint.render.FrameContext", "previousViewProjection", "Lorg/joml/Matrix4f;", ""},
        {"viewpoint.render.FrameContext", "reprojection", "Lorg/joml/Matrix4f;", ""},
        {"viewpoint.render.FrameContext", "frameSeconds", "F", ""},
        {"viewpoint.render.FrameContext", "previousValid", "Z", ""},
        {"viewpoint.render.FrameContext", "farNear", "F", ""},
        {"viewpoint.render.FrameContext", "farFar", "F", ""},
        {"viewpoint.render.FrameContext", "farInvPlainViewProjection", "Lorg/joml/Matrix4f;", ""},
        {"viewpoint.render.FrameContext", "index", "J", ""},
        {"viewpoint.render.FrameContext", "jitterPhase", "I", ""},
        {"viewpoint.render.FrameContext", "noiseFrame", "I", ""},
        {"viewpoint.render.FrameContext", "jitterX", "F", ""},
        {"viewpoint.render.FrameContext", "jitterY", "F", ""},
        {"viewpoint.render.FrameContext", "sun", "Z", ""},
        {"viewpoint.render.FrameContext", "width", "I", ""},
        {"viewpoint.render.FrameContext", "height", "I", ""},
        {"viewpoint.render.FrameContext", "halfWidth", "I", ""},
        {"viewpoint.render.FrameContext", "halfHeight", "I", ""},
        {"viewpoint.render.FrameContext", "outputWidth", "I", ""},
        {"viewpoint.render.FrameContext", "outputHeight", "I", ""},
        {"viewpoint.render.FrameContext", "upscaled", "Z", ""},
        {"viewpoint.render.FrameContext", "panini", "Lviewpoint/render/Panini;", ""},
        {"viewpoint.render.FrameContext", "outputDrawFbo", "I", ""},
        {"viewpoint.render.FrameContext", "outputReadFbo", "I", ""},
        {"viewpoint.render.FrameContext", "outputScissor", "Z", ""},
        {"viewpoint.render.FrameContext", "viewport", "[I", ""},
        {"viewpoint.platform.Pipeline", "cloudResolution", "I", ""},
        {"viewpoint.platform.Pipeline", "clouds", "Z", ""},
        {"viewpoint.platform.Pipeline", "occlusion", "Z", ""},
        {"viewpoint.platform.Pipeline", "bounce", "Z", ""},
        {"viewpoint.platform.Pipeline", "volumetrics", "Z", ""},
        {"viewpoint.platform.Pipeline", "bloom", "Z", ""},
        {"viewpoint.platform.Pipeline", "reflections", "Z", ""},
        {"viewpoint.platform.Pipeline", "reflectionScale", "I", ""},
        {"viewpoint.render.Reflections", "LEVELS", "I", "static"},
        {"viewpoint.platform.Gl", "resetState", "()V", "static"},
    };

    private static final String[] BORROWED = {"exposure", "rooms", "water", "noise", "noise2d", "cloudNoise", "starField",
            "toneLut", "floorPlan", "lampFields", "rainTextures"};
    /**
     * Performance pass 23.10.2026: the lookup keys of the per-frame loops, built once, and the member key of every
     * short lookup key ("Targets.depth" -> "viewpoint.render.Targets.depth") kept in fullKeys - it was concatenated on
     * every access, ~150 times a scope frame. members itself keeps only the full keys, as before.
     */
    private static final String[] BORROWED_KEYS = java.util.Arrays.stream(BORROWED).map(k -> "Targets." + k).toArray(String[]::new);
    /** Viewpoint 0.1.7: the snow's tile data borrowed like the rest (BLEND_MEMBERS). */
    private static final String[] BORROWED_BLEND_KEYS = java.util.stream.Stream.concat(java.util.Arrays.stream(BORROWED_KEYS),
            java.util.stream.Stream.of("Targets.snowTiles", "Targets.snowGrid")).toArray(String[]::new);

    /**
     * Viewpoint 0.1.7's frame on top of MEMBERS (user's task 06.11.2026, one mod for both versions): the snow pass the world
     * draws and shadows need, and the FrameContext fields added there (copied like the others, see the review in lookUp).
     */
    static final String[][] BLEND_MEMBERS = {
        {"viewpoint.render.Targets", "snowTiles", "Lviewpoint/render/SnowTiles;", ""},
        {"viewpoint.render.Targets", "snowGrid", "I", ""},
        {"viewpoint.render.WorldRenderer", "snow", "Lviewpoint/render/SnowPass;", "static"},
        {"viewpoint.render.WorldRenderer", "nearLight", "Lviewpoint/render/NearLight;", "static"},
        {"viewpoint.render.SnowPass", "<init>", "(Lviewpoint/render/Targets;Lviewpoint/render/NearLight;)V", ""},
        {"viewpoint.render.SnowPass", "prepare", "(Lviewpoint/render/FrameContext;Lviewpoint/render/ModelPass;)V", ""},
        {"viewpoint.render.SnowPass", "layer", "(Lviewpoint/render/FrameContext;)V", ""},
        {"viewpoint.render.SnowPass", "draw", "(Lviewpoint/render/FrameContext;)V", ""},
        {"viewpoint.platform.Pipeline", "flashBounce", "Z", ""},
        {"viewpoint.render.FrameContext", "time", "F", ""},
        {"viewpoint.render.FrameContext", "cloudTime", "F", ""},
        {"viewpoint.render.FrameContext", "since", "F", ""},
        {"viewpoint.render.FrameContext", "scissorBox", "[I", ""},
    };

    /** The frame's carried lights: reviewed apart, the scope's context keeps its own arrays and gets a copy (copyLights). */
    static final String[][] LIGHT_MEMBERS = {
        {"viewpoint.render.FrameContext", "lights", "Lviewpoint/render/CarriedLights;", ""},
        {"viewpoint.render.CarriedLights", "torches", "[F", ""},
        {"viewpoint.render.CarriedLights", "lamps", "[F", ""},
        {"viewpoint.render.CarriedLights", "carMaps", "[F", ""},
        {"viewpoint.render.CarriedLights", "flashItem", "[F", ""},
    };

    /** ModelPass.prepare takes the frame's number and blend: Viewpoint 0.1.7's blended frame. */
    private static boolean blendedFrame;
    /** The rows of the version found, in order: MEMBERS, then BLEND_MEMBERS. */
    private static String[][] rows = MEMBERS;

    static String[][] rows() {
        return rows;
    }
    private static final String[] SIZE_KEYS = {"FrameContext.width", "FrameContext.height", "FrameContext.outputWidth", "FrameContext.outputHeight"};
    private static final String[] VP_KEYS = {"FrameContext.viewProjection", "FrameContext.plainViewProjection", "FrameContext.previousViewProjection"};
    private static final String[] INVERSE_KEYS = {"FrameContext.invViewProjection", "FrameContext.invPlainViewProjection"};
    private static final String RENDER = "viewpoint.render.";
    private static final java.util.concurrent.ConcurrentHashMap<String, String> fullKeys = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Member> members = new LinkedHashMap<>();
    /** Performance pass 23.10.2026: Panini.toScreen and FarRelief.Lift.sceneAt, invoked per projected point / per record. */
    private static java.lang.invoke.MethodHandle toScreen, sceneAt;
    private static boolean checked, detecting;
    /** Performance pass 23.10.2026: checked and settled (set after the members): detect() without the class lock. */
    private static volatile boolean settled;
    private static volatile boolean ready;
    private static volatile String status = "not checked";
    private static String version = "?", sha256 = "?";
    private static Bank bank;
    private static volatile int excludedModels;
    private static volatile int scopeMeshes, fadedMeshes, plannedMeshes;
    private static volatile double exclusionMillis;
    private static final ScopePipDrawStats draws = new ScopePipDrawStats();
    private static volatile String visibility = "visibility=unmeasured";
    private static volatile String worldComparison = "worldComparison=unmeasured";
    /**
     * QA (the user's performance question, 20.10.2026; A/B in one run): parts of the scope's second world left out or
     * made cheaper - 1: the indirect light without occlusion and bounce, 2: no clouds, 4: no volumes, 8: no bloom,
     * 16: the scope's own shadow maps (sharedShadows off) drawn every other scope frame (kept between), 32: no weather.
     * 0: all drawn.
     */
    static volatile int qaSkip;
    private static long shadowFrames;
    /** QA (A/B in one run): the scope drawn under the main view's shadow maps (false: its own shadow pass, as before). */
    static volatile boolean sharedShadows = true;
    private static volatile String shadowSource = "none";
    /**
     * W8 item 12 (the user's "weather conditions such as rain, snow etc. are not shown in the scopes"): Viewpoint draws
     * the falling rain and snow and their splashes in FrameEnd alone - into its own rain target with the frame's camera,
     * then over the finished main picture after the temporal resolve and the tone mapping. The scope picture ends with
     * PostPass.composite and ScopePipHistory, so only the world's weather (fog, wet ground, puddles, the distant rain
     * veil) reached it and the near streaks were missing. weather() draws the same precipitation into the picture, at
     * its own size and with the scope camera; the Lit of the picture drawn last (renderScene) supplies the WeatherPass
     * and the shadow pass whose rain map holds the frame's occluders.
     */
    private static volatile Lit scopeLit;
    /** QA/perf: pictures with precipitation, without it (not raining or the QA skip), and the stage's failures. */
    private static volatile long weatherDrawn, weatherSkipped, weatherErrors;
    /** Set by the first weather failure: the picture keeps working without the layer, as before (one log line). */
    private static volatile boolean weatherOff;
    /** QA (A/B in one run): false - the picture without precipitation, exactly as before the layer existed. */
    static volatile boolean qaWeather = true;
    /** Channels whose picture shows falling precipitation: rain and snow drops, not the heat a thermal camera sees. */
    static final java.util.Set<String> WEATHER_KINDS = java.util.Set.of("optic", "nv");
    /** GPU milliseconds of the layer, from its own timestamp pair (never waited for), and its samples; QA only. */
    static volatile double weatherGpuMillis;
    static volatile long weatherGpuSamples;
    private static int[] weatherTimer;
    private static boolean weatherTimerPending;

    private ViewpointPipBridge() {
    }

    public static boolean detect() {
        if (settled) {
            return ready;
        }
        return check();
    }

    private static synchronized boolean check() {
        if (checked) {
            if (!detecting) {
                settled = true;
            }
            return ready;
        }
        checked = true;
        detecting = true;
        try {
            return lookUp();
        } finally {
            detecting = false;
            settled = true;
        }
    }

    private static boolean lookUp() {
        ClassLoader loader = ViewpointPipBridge.class.getClassLoader();
        try {
            if (!admit(MEMBERS, loader)) {
                return false;
            }
            blendedFrame = ((Method) members.get("viewpoint.render.ModelPass.prepare")).getParameterCount() > 1;
            if (blendedFrame) {
                if (!admit(BLEND_MEMBERS, loader) || !admit(LIGHT_MEMBERS, loader)) {
                    return false;
                }
                rows = java.util.stream.Stream.concat(java.util.Arrays.stream(MEMBERS), java.util.Arrays.stream(BLEND_MEMBERS)).toArray(String[][]::new);
            }
            // The context is restored as a complete transaction, so added
            // instance fields need an explicit review too.
            Class<?> context = Class.forName("viewpoint.render.FrameContext", false, loader);
            for (Field f : context.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && !members.containsKey(context.getName() + "." + f.getName())) {
                    fail("unreviewed FrameContext field " + f.getName(), null);
                    return false;
                }
            }
            java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.lookup();
            toScreen = lookup.unreflect((Method) members.get("viewpoint.render.Panini.toScreen")).asType(
                    java.lang.invoke.MethodType.methodType(void.class, Object.class, float.class, float.class, float[].class));
            sceneAt = lookup.unreflect((Method) members.get("viewpoint.render.FarRelief$Lift.sceneAt")).asType(
                    java.lang.invoke.MethodType.methodType(float.class, Object.class, float.class, float.class));
            version = ViewpointBridge.readVersion(context);
            sha256 = ViewpointBridge.jarSha(context);
            status = "on";
            ready = true;
            return true;
        } catch (Throwable t) {
            fail("lookup", t);
            return false;
        }
    }

    /** Every row of a table into members; false (one log line) at the first missing or changed. */
    private static boolean admit(String[][] table, ClassLoader loader) throws Throwable {
        for (String[] row : table) {
            Member member = row[1].equals("<init>") ? constructor(row, loader) : ViewpointBridge.resolve(row, loader);
            if (member == null) {
                fail("member " + row[0] + "." + row[1] + " " + row[2] + " missing or changed", null);
                return false;
            }
            members.put(row[0] + "." + row[1], member);
        }
        return true;
    }

    /** The constructor of a row; its descriptors are those of the versions we support, oldest first. */
    private static Member constructor(String[] row, ClassLoader loader) throws Throwable {
        Class<?> cls = Class.forName(row[0], false, loader);
        for (String d : row[2].split("\\|")) {
            try {
                Constructor<?> c = cls.getDeclaredConstructor(java.lang.invoke.MethodType.fromMethodDescriptorString(d, loader).parameterArray());
                c.setAccessible(true);
                return c;
            } catch (NoSuchMethodException | TypeNotPresentException e) {
                // the other version's
            }
        }
        return null;
    }

    /** The scope's context gets the main frame's carried lights (flashlights, lamps, headlights moved with their models). */
    private static void copyLights(Object main, Object scope) throws Throwable {
        Object from = get("FrameContext.lights", main), to = get("FrameContext.lights", scope);
        if (from == null || to == null || from == to) {
            return;
        }
        for (String key : new String[] {"CarriedLights.torches", "CarriedLights.lamps", "CarriedLights.carMaps", "CarriedLights.flashItem"}) {
            float[] a = (float[]) get(key, from), b = (float[]) get(key, to);
            System.arraycopy(a, 0, b, 0, Math.min(a.length, b.length));
        }
    }

    /** IndirectPass.draw's one switch on 0.1.7: any of what it used to take apart. */
    private static boolean indirectOn(Object pipeline) throws Throwable {
        return (boolean) get("viewpoint.platform.Pipeline.occlusion", pipeline) || (boolean) get("viewpoint.platform.Pipeline.bounce", pipeline)
                || (boolean) get("viewpoint.platform.Pipeline.flashBounce", pipeline);
    }

    static synchronized void fail(String where, Throwable t) {
        while (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) {
            t = t.getCause();
        }
        if (!status.startsWith("off:")) {
            status = "off: " + where + (t == null ? "" : " " + t.getClass().getSimpleName() + ": " + t.getMessage());
            System.out.println("[TWZB] scope PIP " + status);
        }
        checked = true;
        ready = false;
    }

    public static boolean ready() {
        return ready;
    }

    /**
     * Player report of 24.10.2026 (a black scope and dead collimators at 1920x1080; console.txt: "the reflections' target
     * at level 6 is incomplete: 0x8cdd"): Viewpoint's Reflections (IndirectPass.draw) gives the scope's world a pyramid of
     * Reflections.LEVELS levels at (picture / Pipeline.reflectionScale) >> level, one framebuffer per level, and a level
     * past the 1x1 end is GL_FRAMEBUFFER_UNSUPPORTED (replayed on an RTX 5090: every level k with width >> k == 0). The
     * picture follows the eyepiece (ScopePip.lensFit: 128-192 px for a small eyepiece at 1080p), so reflections "low"
     * (scale 4) failed below 512 px, "medium"/"high" (2) below 256, "ultra" (1) below 128. The smallest picture whose
     * pyramid is whole under the main view's pipeline; 1 with reflections off. Render thread.
     */
    static int minimumSize() throws Throwable {
        Object pipeline = get("FrameContext.pipeline", context());
        if (pipeline == null || !(boolean) get("viewpoint.platform.Pipeline.reflections", pipeline)) {
            return 1;
        }
        int scale = Math.max(1, (int) get("viewpoint.platform.Pipeline.reflectionScale", pipeline));
        int levels = Math.max(1, Math.min(13, (int) get("viewpoint.render.Reflections.LEVELS", null)));
        return scale << (levels - 1);
    }

    /** False once a scope pass could not put Viewpoint's state back (the main view may be wrong): the whole bridge goes off then. */
    private static volatile boolean restoreFailed;

    static boolean restored() {
        return !restoreFailed;
    }

    /** The member key of a lookup key: a full one as is, a viewpoint.render one without its package prefixed (once). */
    private static String full(String key) {
        String full = fullKeys.get(key);
        if (full == null) {
            full = key.startsWith("viewpoint.") ? key : RENDER + key;
            if (fullKeys.size() < 1024) fullKeys.put(key, full); // the code's literal keys; bounded all the same
        }
        return full;
    }

    static Field field(String key) {
        return (Field) members.get(full(key));
    }

    static Object get(String key, Object owner) throws Throwable {
        return field(key).get(owner);
    }

    static void put(String key, Object owner, Object value) throws Throwable {
        field(key).set(owner, value);
    }

    static Object call(String key, Object owner, Object... args) throws Throwable {
        return ((Method) members.get(full(key))).invoke(owner, args);
    }

    /** FarRelief.Lift.sceneAt of a scene's relief (ModelHeatViewpoint, per drawn record). */
    static float sceneAt(Object lift, float x, float z) throws Throwable {
        return (float) sceneAt.invokeExact(lift, x, z);
    }

    static Object make(String name, Object... args) throws Throwable {
        return ((Constructor<?>) members.get("viewpoint.render." + name + ".<init>")).newInstance(args);
    }

    static Object context() throws Throwable {
        return get("WorldRenderer.frame", null);
    }

    static Object currentScene() throws Throwable {
        return get("FrameContext.scene", context());
    }

    static int depth() throws Throwable {
        return bank == null ? 0 : (int) get("Targets.depth", bank.targets);
    }

    static float[] screen(float[] ndc) throws Throwable {
        if (ndc != null) {
            Object panini = get("FrameContext.panini", context());
            if (panini != null) {
                float[] out = new float[2];
                toScreen.invokeExact(panini, ndc[0], ndc[1], out);
                ndc[0] = out[0];
                ndc[1] = out[1];
            }
        }
        return ndc;
    }

    /**
     * FB8 item 1 (22.10.2026, "parts of the mount are still visible in the sight"): true while the scope picture's world
     * is drawn. Its ModelPass.gbuffer / glass are the patched ones of the main view (FpViewLatch.clampOn), whose depth
     * clamp in ADS made the picture's near plane at the scope's front end (FB7 item 5) clip nothing: the mount and the
     * receiver round the tube were drawn into the picture. Render thread only.
     */
    private static boolean picture;

    static boolean picture() {
        return picture;
    }

    /** prewarm()'s time on the render thread, ms (QA, info()); -1: not built ahead. */
    private static volatile double prewarmMillis = -1;

    /**
     * The user's request (23.10.2026, the freeze of the first raise of a magnified scope, ~160-180 ms): the scope's
     * world - Viewpoint's passes with their GL programs and shader sources (HitchSampler in the first raise:
     * GlProgram.create/link and Shaders.load under make, Bank and Lit) - built on a Viewpoint frame before the first
     * raise. The same objects in the same order as render()'s bank and lit() (the main view's shadow maps complete, so
     * the shared Lit); ScopePip calls it inside the same full state boundary; nothing is drawn, so the picture is the
     * same. Render thread; false: not yet (no main targets or complete shadow maps this frame).
     */
    static boolean prewarm() throws Throwable {
        if (!ready) {
            return false;
        }
        Object mainTargets = get("WorldRenderer.targets", null);
        Object mainShadows = get("WorldRenderer.shadows", null);
        if (mainTargets == null || sharedShadows && (mainShadows == null || !(boolean) call("ShadowPass.complete", mainShadows))) {
            return false;
        }
        long start = System.nanoTime();
        if (bank == null) {
            bank = new Bank(mainTargets);
        }
        if (sharedShadows) {
            bank.lit();
        }
        prewarmMillis = (System.nanoTime() - start) / 1e6;
        return true;
    }

    /** Returns false while the independent atmospheric tables are warming. Render thread only. */
    static boolean render(Object scene, Matrix4f view, Matrix4f projection, Vector3f eye, int size, int outputFbo, Object viewer) throws Throwable {
        if (!ready) {
            return false;
        }
        picture = true;
        try (PerfProbe.Scope setup = PerfProbe.cpu(PerfProbe.PIP_VIEW)) {
            return renderScene(scene, view, projection, eye, size, outputFbo, viewer, setup);
        } finally {
            picture = false;
        }
    }

    private static boolean renderScene(Object scene, Matrix4f view, Matrix4f projection, Vector3f eye,
            int size, int outputFbo, Object viewer, PerfProbe.Scope setup) throws Throwable {
        Object main = context(), mainTargets = get("WorldRenderer.targets", null);
        if (mainTargets == null || get("FrameContext.scene", main) != scene) {
            return false;
        }
        // Expensive uniform reads are restricted to the existing one-shot dump.
        String mainWorld = ScopePipDump.wanted() ? ScopePipSceneState.capture(main) : null;
        if (bank == null) {
            bank = new Bank(mainTargets);
        }
        call("AirLuts.update", bank.air);
        Lit lit = bank.lit();
        scopeLit = lit;
        shadowSource = lit.own ? "own" : "main";
        if (!(boolean) get("AirLuts.uploaded", bank.air) || !(boolean) call("ShadowPass.complete", lit.shadows)) {
            return false;
        }
        Object pipeline = get("FrameContext.pipeline", main);
        int clouds = (int) get("viewpoint.platform.Pipeline.cloudResolution", pipeline);
        if (!(boolean) call("Targets.ensure", bank.targets, size, size, size, size, clouds)) {
            throw new IllegalStateException("PIP targets incomplete");
        }
        for (String key : blendedFrame ? BORROWED_BLEND_KEYS : BORROWED_KEYS) {
            put(key, bank.targets, get(key, mainTargets));
        }
        try (Snapshot saved = new Snapshot(main)) {
            Object scope = bank.context;
            saved.copyTo(scope);
            if (blendedFrame) {
                copyLights(main, scope);
            }
            Matrix4f sourceProjection = (Matrix4f) get("SceneData.projection", scene);
            Matrix4f originalProjection = new Matrix4f(sourceProjection);
            Object originalPanini = get("SceneData.panini", scene);
            Object originalIndirect = get("WorldRenderer.indirect", null), originalTemporal = get("WorldRenderer.temporal", null);
            Object originalSnow = blendedFrame ? get("WorldRenderer.snow", null) : null;
            Object originalCull = get("WorldRenderer.cullFromPlayer", null);
            Object eyeSky = get("FrameUniforms.eyeSky", null);
            long exclusionStart = System.nanoTime();
            ScopePipExclusion exclusion;
            try (PerfProbe.Scope perf = PerfProbe.cpu(PerfProbe.PIP_CULL)) {
                exclusion = new ScopePipExclusion(scene, viewer);
            }
            ScopePipGeometryState geometry = new ScopePipGeometryState(scene, ScopePip.diagnostics());
            ScopePipSceneState relief = new ScopePipSceneState();
            boolean prepared = false;
            draws.frame((long) get("FrameContext.index", main), ScopePip.diagnostics());
            try {
                // Main begin installs the scene's relief callback and invalidates its
                // texture-binding cache. A second pass needs the same boundary after
                // intervening mod draws and allocations, even with identical P*V.
                relief.apply(scene);
                try (PerfProbe.Scope perf = PerfProbe.cpu(PerfProbe.PIP_CULL)) {
                    exclusion.apply();
                }
                excludedModels = exclusion.excluded;
                exclusionMillis = (System.nanoTime() - exclusionStart) / 1e6;
                // The scope has no self model. Main-eye proximity clearing would
                // instead dither unrelated nearby world surfaces from this camera.
                sourceProjection.set(projection);
                put("SceneData.panini", scene, 0f);
                put("WorldRenderer.targets", null, bank.targets);
                put("WorldRenderer.indirect", null, lit.indirect);
                put("WorldRenderer.temporal", null, bank.temporal);
                if (blendedFrame) {
                    put("WorldRenderer.snow", null, bank.snow);
                }
                put("WorldRenderer.cullFromPlayer", null, false);
                put("FrameContext.width", main, size);
                put("FrameContext.height", main, size);
                camera(scope, view, projection, eye);
                for (String key : SIZE_KEYS) {
                    put(key, scope, size);
                }
                put("FrameContext.halfWidth", scope, get("Targets.halfWidth", bank.targets));
                put("FrameContext.halfHeight", scope, get("Targets.halfHeight", bank.targets));
                put("FrameContext.upscaled", scope, false);
                put("FrameContext.previousValid", scope, false);
                put("FrameContext.jitterX", scope, 0f);
                put("FrameContext.jitterY", scope, 0f);
                // Own cadence: do not repeatedly select the same stochastic phase
                // when the user updates PIP every other main frame.
                put("FrameContext.noiseFrame", scope, bank.noiseFrame++);
                put("FrameContext.panini", scope, get("Panini.NONE", null));
                put("FrameContext.scene", scope, scene);
                put("FrameContext.outputDrawFbo", scope, outputFbo);
                put("FrameContext.outputReadFbo", scope, outputFbo);
                put("FrameContext.outputScissor", scope, false);
                int[] viewport = (int[]) get("FrameContext.viewport", scope);
                viewport[0] = 0; viewport[1] = 0; viewport[2] = size; viewport[3] = size;
                call("viewpoint.platform.Gl.resetState", null);
                call("FrameUniforms.set", null, scope, bank.targets);
                PerfProbe.end(setup);
                try (PerfProbe.Scope perf = PerfProbe.cpu(PerfProbe.PIP_MESH_UPLOAD)) {
                    geometry.apply();
                }
                scopeMeshes = geometry.meshes;
                fadedMeshes = geometry.faded;
                plannedMeshes = geometry.planned;
                if (ScopePip.diagnostics()) {
                    byte[] flags = (byte[]) get("SceneData.meshFlags", scene);
                    byte visible = (byte) get("SceneData.VISIBLE", null);
                    boolean[] chosen = (boolean[]) get("Meshes.chosen", null);
                    int visibleMeshes = 0, selectedMeshes = 0;
                    for (int i = 0; i < geometry.meshes; i++) {
                        if ((flags[i] & visible) != 0) { visibleMeshes++; }
                        if (chosen == null || i < chosen.length && chosen[i]) { selectedMeshes++; }
                    }
                    Object models = get("SceneData.models", scene);
                    int modelCount = (int) get("ModelDraws.count", models), modelColour = 0;
                    int[] modelFlags = (int[]) get("ModelDraws.flags", models);
                    int colour = (int) get("ModelDraws.COLOUR", null);
                    for (int i = 0; i < modelCount; i++) { if ((modelFlags[i] & colour) != 0) { modelColour++; } }
                    visibility = "surfaceFlagVisible=" + visibleMeshes + " surfaceFlagHidden=" + (geometry.meshes - visibleMeshes)
                            + " surfaceChosen=" + selectedMeshes + " surfaceCandidates=" + geometry.meshes
                            + " modelColourCandidates=" + modelColour + " modelCandidates=" + modelCount
                            + " farCellCandidates=" + get("SceneData.farCount", scene) + " farShellCandidates=" + get("SceneData.shellCount", scene)
                            + " meshFlagsShared=true roomMaskShared=true roomMaskHash=" + java.util.Arrays.hashCode((byte[]) get("SceneData.rooms", scene))
                            + " cutawayState=not-exposed cameraClear=" + get("CameraClear.on", null)
                            + " cullFromPlayer=" + get("WorldRenderer.cullFromPlayer", null)
                            + " screenOcclusion=" + get("viewpoint.platform.Pipeline.occlusion", pipeline)
                            + " mainRoomMode=" + get("viewpoint.visibility.Rooms.mode", null)
                            + " mainRoomHiding=" + get("viewpoint.visibility.Rooms.hiding", null)
                            + " mainRoomCaptureFailed=" + get("viewpoint.visibility.Rooms.captureFailed", null)
                            + " mainPlanCulledVerticesCounter=" + get("viewpoint.visibility.DrawPlans.culledVertices", null)
                            + " mainRoomState=[" + String.valueOf(get("viewpoint.visibility.Rooms.state", null)).replaceAll("\\s+", "_") + "]";
                } else {
                    visibility = "visibility=qa-only meshFlagsShared=true roomMaskShared=true";
                }
                prepared = true;
                try (PerfProbe.Scope perf = PerfProbe.cpu(PerfProbe.PIP_MODELS)) {
                    if (blendedFrame) {
                        // The frame's place between the game's steps. The scope has no self model: its arms take no turn.
                        call("ModelPass.prepare", bank.models, scene, VpBlend.frameNumber, VpBlend.frameBlend, 0f, 0f, 0f);
                    } else {
                        call("ModelPass.prepare", bank.models, scene);
                    }
                }
                // VP performs its own current-scope frustum tests here and in the
                // model gbuffer. Main-eye visibility cannot replace that selection.
                try (PerfProbe.Scope perf = PerfProbe.cpu(PerfProbe.PIP_FAR)) {
                    call("FarPass.prepare", lit.far, scope, true);
                    if (blendedFrame) {
                        call("SnowPass.prepare", bank.snow, scope, bank.models);
                    }
                }
                try (PerfProbe.Scope perf = PerfProbe.cpu(PerfProbe.PIP_SHADOW)) {
                    if (lit.own && ((qaSkip & 16) == 0 || (shadowFrames++ & 1) == 0)) {
                        call("ShadowPass.draw", lit.shadows, scope);
                    }
                }
                try (PerfProbe.Scope perf = PerfProbe.cpu(PerfProbe.PIP_DRAW)) {
                    GL11.glDisable(GL11.GL_SCISSOR_TEST);
                    GL11.glDepthMask(true);
                    GL11.glColorMask(true, true, true, true);
                    GL11.glClearDepth(1);
                    draws.begin(0);
                    try { call("SurfacePass.gbuffer", lit.surfaces, scope); } finally { draws.end(); }
                    draws.begin(1);
                    try { call("ModelPass.gbuffer", bank.models, scope); } finally { draws.end(); }
                    draws.begin(2);
                    try { call("FarPass.gbuffer", lit.far, scope); } finally { draws.end(); }
                    if (blendedFrame) {
                        call("SnowPass.layer", bank.snow, scope);
                        call("SnowPass.draw", bank.snow, scope);
                    }
                    boolean cheapIndirect = (qaSkip & 1) != 0;
                    if (blendedFrame) {
                        call("IndirectPass.draw", lit.indirect, scope, !cheapIndirect && indirectOn(pipeline));
                    } else {
                        call("IndirectPass.draw", lit.indirect, scope, cheapIndirect ? (Object) false : get("viewpoint.platform.Pipeline.occlusion", pipeline),
                                cheapIndirect ? (Object) false : get("viewpoint.platform.Pipeline.bounce", pipeline));
                    }
                    call("SkyPass.prepare", lit.sky, scope);
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, (int) get("Targets.lightFbo", bank.targets));
                    GL11.glViewport(0, 0, size, size);
                    if ((qaSkip & 2) == 0 && (boolean) get("viewpoint.platform.Pipeline.clouds", pipeline)) {
                        call("SkyPass.clouds", lit.sky, scope);
                        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, (int) get("Targets.lightFbo", bank.targets));
                        GL11.glViewport(0, 0, size, size);
                    }
                    call("SkyPass.sky", lit.sky, scope);
                    call("SurfacePass.light", lit.surfaces, scope);
                    draws.begin(3);
                    try { call("FarPass.draw", lit.far, scope); } finally { draws.end(); }
                    ScopePip.Probe.begin(size);
                    if (ScopePip.Probe.active()) {
                        ScopePip.Probe.light((int) get("Targets.lightFbo", bank.targets));
                    }
                    call("WorldRenderer.bindTarget", null);
                    call("TranslucentPass.draw", bank.translucent, scope, lit.surfaces, bank.models, lit.shadows);
                    call("WorldRenderer.bindTarget", null);
                    if ((qaSkip & 32) == 0) {
                        call("WeatherPass.draw", lit.weather, scope);
                    }
                    if ((qaSkip & 4) == 0) {
                        call("VolumePass.draw", lit.volume, scope);
                    }
                    if ((qaSkip & 8) == 0 && (boolean) get("viewpoint.platform.Pipeline.bloom", pipeline)) {
                        call("PostPass.bloom", bank.post, scope);
                    }
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, outputFbo);
                    GL11.glViewport(0, 0, size, size);
                    // PostPass reads shared EyeMeter exposure, not a per-pass texture.
                    // Do not meter/adapt that shared main-eye state again for PIP.
                    // No main TAA, upscaler, picking, FrameGen, outline or UI calls.
                    call("PostPass.composite", bank.post, scope);
                    if (ScopePip.Probe.active()) {
                        ScopePip.Probe.output(outputFbo, (float) call("EyeMeter.exposure", null, scope));
                    }
                    if (mainWorld != null) {
                        worldComparison = "worldComparisonFrame=" + get("FrameContext.index", main)
                                + " mainWorld={" + mainWorld + "} pipWorld={" + ScopePipSceneState.capture(scope) + "}";
                    }
                    return true;
                }
            } finally {
                // Each restoration runs even if a previous one fails. Reflection
                // members are already checked; any failure is latched by the caller.
                PerfProbe.Scope restore = PerfProbe.cpu(PerfProbe.PIP_RESTORE);
                Throwable failure = null;
                try { draws.finish(); } catch (Throwable t) { failure = t; }
                try {
                    if (prepared) {
                        call("ModelPass.endFrame", bank.models);
                    }
                } catch (Throwable t) {
                    failure = t;
                }
                try { geometry.close(); } catch (Throwable t) { failure = t; }
                try { sourceProjection.set(originalProjection); } catch (Throwable t) { failure = t; }
                try { put("SceneData.panini", scene, originalPanini); } catch (Throwable t) { failure = t; }
                try { put("WorldRenderer.targets", null, mainTargets); } catch (Throwable t) { failure = t; }
                try { put("WorldRenderer.indirect", null, originalIndirect); } catch (Throwable t) { failure = t; }
                try { put("WorldRenderer.temporal", null, originalTemporal); } catch (Throwable t) { failure = t; }
                if (blendedFrame) {
                    try { put("WorldRenderer.snow", null, originalSnow); } catch (Throwable t) { failure = t; }
                }
                try { put("WorldRenderer.cullFromPlayer", null, originalCull); } catch (Throwable t) { failure = t; }
                exclusionStart = System.nanoTime();
                try { exclusion.close(); } catch (Throwable t) { failure = t; }
                exclusionMillis += (System.nanoTime() - exclusionStart) / 1e6;
                try { saved.restore(); } catch (Throwable t) { failure = t; }
                try {
                    put("FrameUniforms.eyeSky", null, eyeSky);
                    call("FrameUniforms.set", null, main, mainTargets);
                } catch (Throwable t) {
                    failure = t;
                } finally {
                    try { put("FrameUniforms.eyeSky", null, eyeSky); } catch (Throwable t) { failure = t; }
                }
                // The outer GL boundary restores texture units afterwards. Invalidate
                // relief's CPU cache so it cannot describe bindings from this pass.
                try { relief.close(); } catch (Throwable t) { failure = t; }
                PerfProbe.end(restore);
                if (failure != null) {
                    restoreFailed = true;
                    throw failure;
                }
            }
        }
    }

    /**
     * W8 item 12: the picture's falling precipitation, drawn after ScopePipHistory and before the sensor and composite
     * stages (the main frame's order: WeatherPass.falling after the temporal resolve, WeatherPass.overlay over the
     * resolved picture). The scope camera's narrow field of view goes into the pass (plainViewProjection, and
     * SceneData.projection for the streak width in the picture's own pixels), so the rain and snow keep the world's
     * density and distance - no screen-space copy of the main view. A thermal channel does not call this at all
     * (weatherKind). Render thread; the main FrameContext, SceneData projection and GL state are restored, and the
     * first failure switches the layer off alone with one log line.
     */
    static boolean weather(Object scene, Matrix4f view, Matrix4f projection, Vector3f eye, int size, int outputFbo) {
        Lit lit = scopeLit;
        if (!ready || weatherOff || bank == null || lit == null || (qaSkip & 32) != 0) {
            return false;
        }
        if (!qaWeather) {
            weatherSkipped++;
            return false;
        }
        try {
            Object main = context();
            if (get("FrameContext.scene", main) != scene) {
                return false;
            }
            boolean drawn = false;
            try (Snapshot saved = new Snapshot(main)) {
                Object scope = bank.context;
                saved.copyTo(scope);
                Matrix4f source = (Matrix4f) get("SceneData.projection", scene);
                Matrix4f original = new Matrix4f(source);
                Throwable failure = null;
                try {
                    source.set(projection);
                    put("FrameContext.scene", scope, scene);
                    put("FrameContext.width", scope, size);
                    put("FrameContext.height", scope, size);
                    camera(scope, view, projection, eye);
                    put("FrameContext.upscaled", scope, false);
                    put("FrameContext.panini", scope, get("Panini.NONE", null));
                    put("FrameContext.outputDrawFbo", scope, outputFbo);
                    put("FrameContext.outputReadFbo", scope, outputFbo);
                    put("FrameContext.outputScissor", scope, false);
                    int[] viewport = (int[]) get("FrameContext.viewport", scope);
                    viewport[0] = 0; viewport[1] = 0; viewport[2] = size; viewport[3] = size;
                    call("viewpoint.platform.Gl.resetState", null);
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, outputFbo);
                    GL11.glViewport(0, 0, size, size);
                    // raining(): the frame's weather.draw ran (its one-shot "due") and the rain map of the shared or own
                    // shadow pass holds this frame's heights. Its uniforms are the ones FrameUniforms.set uploaded for
                    // the same scene in renderScene, this frame.
                    drawn = (boolean) call("WeatherPass.raining", lit.weather, scope);
                    if (drawn) {
                        boolean timing = weatherTiming();
                        call("WeatherPass.falling", lit.weather, scope, (int) get("Targets.hdr", bank.targets));
                        // falling() leaves its own rain target bound (Viewpoint rebinds its output in FrameEnd before
                        // overlay): the layer must land in the picture, not in the rain target again.
                        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, outputFbo);
                        GL11.glViewport(0, 0, size, size);
                        call("WeatherPass.overlay", lit.weather, scope);
                        if (timing) {
                            GL33.glQueryCounter(weatherTimer[1], GL33.GL_TIMESTAMP);
                            weatherTimerPending = true;
                        }
                    }
                } catch (Throwable t) {
                    failure = t;
                } finally {
                    try { source.set(original); } catch (Throwable t) { if (failure == null) failure = t; }
                    try { saved.restore(); } catch (Throwable t) { if (failure == null) failure = t; }
                    // The stages after this expect the picture's own writable image, no blend or depth test (the
                    // history resolve left the same state).
                    try {
                        GL11.glColorMask(true, true, true, true);
                        GL11.glDepthMask(false);
                        GL11.glDisable(GL11.GL_DEPTH_TEST);
                        GL11.glDisable(GL11.GL_BLEND);
                        GL11.glDepthFunc(GL11.GL_LESS);
                        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, outputFbo);
                        GL11.glViewport(0, 0, size, size);
                    } catch (Throwable t) { if (failure == null) failure = t; }
                }
                if (failure != null) {
                    throw failure;
                }
            }
            if (drawn) {
                weatherDrawn++;
            } else {
                weatherSkipped++;
            }
            return drawn;
        } catch (Throwable t) {
            weatherOff = true;
            weatherErrors++;
            System.out.println("[TWZB] scope PIP weather off: " + t);
            return false;
        }
    }

    /** True for the pictures that show falling rain and snow: an optical scope and an image intensifier (WEATHER_KINDS). */
    static boolean weatherKind(String kind) {
        return kind != null && WEATHER_KINDS.contains(kind);
    }

    /** Render thread: the layer's GPU timestamp pair, only while the perf probe runs; the previous result is never waited for. */
    private static boolean weatherTiming() {
        if (!PerfProbe.enabled()) {
            weatherTimerPending = false;
            return false;
        }
        if (weatherTimer == null) {
            weatherTimer = new int[] {GLCompat.genQuery(), GLCompat.genQuery()};
        }
        if (weatherTimerPending) {
            if (GL15.glGetQueryObjecti(weatherTimer[1], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
                return false;
            }
            long first = GL33.glGetQueryObjectui64(weatherTimer[0], GL15.GL_QUERY_RESULT);
            long last = GL33.glGetQueryObjectui64(weatherTimer[1], GL15.GL_QUERY_RESULT);
            weatherGpuMillis = (last - first) / 1e6;
            weatherGpuSamples++;
        }
        GL33.glQueryCounter(weatherTimer[0], GL33.GL_TIMESTAMP);
        return true;
    }

    /** Unjittered camera contract, independently callable in the JVM stand-in. */
    static void camera(Object scope, Matrix4f view, Matrix4f projection, Vector3f eye) throws Throwable {
        put("FrameContext.view", scope, new Matrix4f(view));
        put("FrameContext.eyeX", scope, eye.x);
        put("FrameContext.eyeY", scope, eye.y);
        put("FrameContext.eyeZ", scope, eye.z);
        ((Matrix3f) get("FrameContext.viewRotation", scope)).set(view).transpose();
        ((Matrix4f) get("FrameContext.jitteredProjection", scope)).set(projection);
        Matrix4f vp = new Matrix4f(projection).mul(view);
        for (String key : VP_KEYS) {
            ((Matrix4f) get(key, scope)).set(vp);
        }
        Matrix4f inverse = new Matrix4f(vp).invert();
        for (String key : INVERSE_KEYS) {
            ((Matrix4f) get(key, scope)).set(inverse);
        }
        ((Matrix4f) get("FrameContext.reprojection", scope)).identity();
    }

    /** Complete CPU snapshot, including in-place contents of final matrices/arrays. */
    static final class Snapshot implements AutoCloseable {
        private final Object owner;
        private final Map<Field, Object> saved;
        private final ScopePipCpu.Context cached;
        private boolean released;

        Snapshot(Object owner) throws Throwable {
            this.owner = owner;
            cached = ScopePipCpu.context(owner);
            if (cached != null) {
                saved = null;
                return;
            }
            saved = new LinkedHashMap<>();
            for (String[] row : rows()) {
                if (!row[0].equals("viewpoint.render.FrameContext") || row[1].equals("<init>")) {
                    continue;
                }
                Field f = field(row[0] + "." + row[1]);
                Object value = f.get(owner);
                if (Modifier.isFinal(f.getModifiers())) {
                    if (value instanceof Matrix4f m) { value = new Matrix4f(m); }
                    else if (value instanceof Matrix3f m) { value = new Matrix3f(m); }
                    else if (value instanceof int[] a) { value = a.clone(); }
                    else { throw new IllegalStateException("unreviewed final context value " + f); }
                }
                saved.put(f, value);
            }
        }

        void restore() throws Throwable {
            try { copyTo(owner); }
            finally { close(); }
        }

        @Override public void close() {
            if (cached != null && !released) {
                released = true;
                cached.release();
            }
        }

        void copyTo(Object destination) throws Throwable {
            if (cached != null) {
                cached.copyTo(destination);
                return;
            }
            Throwable failure = null;
            for (Map.Entry<Field, Object> e : saved.entrySet()) {
                try {
                    Field f = e.getKey();
                    Object value = e.getValue();
                    if (!Modifier.isFinal(f.getModifiers())) { f.set(destination, value); }
                    else if (value instanceof Matrix4f m) { ((Matrix4f) f.get(destination)).set(m); }
                    else if (value instanceof Matrix3f m) { ((Matrix3f) f.get(destination)).set(m); }
                    else if (value instanceof int[] a) { System.arraycopy(a, 0, f.get(destination), 0, a.length); }
                } catch (Throwable t) {
                    failure = t;
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class Bank {
        final Object context, targets, air, models, trees, translucent, post, temporal, snow;
        Lit shared, own;
        int noiseFrame;

        Bank(Object mainTargets) throws Throwable {
            context = make("FrameContext");
            targets = make("Targets");
            for (String key : blendedFrame ? BORROWED_BLEND_KEYS : BORROWED_KEYS) {
                put(key, targets, get(key, mainTargets));
            }
            air = make("AirLuts");
            put("Targets.air", targets, air);
            trees = get("FarPass.trees", get("WorldRenderer.far", null));
            models = make("ModelPass", targets);
            translucent = make("TranslucentPass", targets);
            post = make("PostPass", targets);
            // Independent history, deliberately never advanced in this first
            // unjittered PIP pass; global historyValid() cannot see the main TAA.
            temporal = make("TemporalPass", targets);
            snow = blendedFrame ? make("SnowPass", targets, get("WorldRenderer.nearLight", null)) : null;
        }

        /** The main view's maps while they are complete (and QA allows); the scope's own shadow pass otherwise, as before. */
        Lit lit() throws Throwable {
            Object main = get("WorldRenderer.shadows", null);
            if (sharedShadows && !VpShaderPackCompat.isIrisDrawn() && main != null && (boolean) call("ShadowPass.complete", main)) {
                if (shared == null || shared.shadows != main) {
                    shared = new Lit(this, main);
                }
                return shared;
            }
            if (own == null) {
                own = new Lit(this, null);
            }
            return own;
        }
    }

    /**
     * The passes of the scope's world that read shadow maps, with the maps they read. The user's performance question
     * (20.10.2026): Viewpoint fits its sun cascades, far shadows and weather map to the scene (SceneData), not to a camera,
     * so the scope's own shadow pass drew the main view's maps once more every frame (run 9: 1.6 ms of the render thread,
     * 2.7 ms of the GPU at 4K). The scope now reads the main view's maps, drawn this frame; its own pass (own) remains for
     * the time the main view's maps are not complete and for QA.
     */
    private static final class Lit {
        final Object shadows, weatherMap, surfaces, far, indirect, sky, weather, volume;
        final boolean own;

        Lit(Bank bank, Object mainShadows) throws Throwable {
            own = mainShadows == null;
            Object farShadow;
            if (own) {
                weatherMap = make("WeatherMap", get("Targets.noise", bank.targets));
                farShadow = make("FarShadow", bank.trees);
                shadows = blendedFrame ? make("ShadowPass", bank.models, farShadow, weatherMap, bank.snow)
                        : make("ShadowPass", bank.models, farShadow, weatherMap);
            } else {
                shadows = mainShadows;
                weatherMap = get("WorldRenderer.weatherMap", null);
                farShadow = get("ShadowPass.farMaps", mainShadows);
            }
            surfaces = make("SurfacePass", bank.targets, shadows);
            far = make("FarPass", bank.targets, shadows, farShadow, bank.trees);
            indirect = make("IndirectPass", bank.targets, shadows, get("WorldRenderer.floors", null));
            sky = make("SkyPass", bank.targets, weatherMap);
            weather = make("WeatherPass", bank.targets, shadows, weatherMap);
            volume = make("VolumePass", bank.targets, shadows, make("BlurPass"));
        }
    }

    public static String info() {
        return "bridge=" + status + " version=" + version + " sha256=" + sha256 + " excludedModels=" + excludedModels
                + " weaponHide=" + ScopePipExclusion.hide.size() + " weaponKept=" + ScopePipExclusion.weaponKept
                + " weaponHidden=" + ScopePipExclusion.weaponHidden
                + " pipWeather=" + (weatherOff ? "off" : "on") + " weatherDrawn=" + weatherDrawn
                + " weatherSkipped=" + weatherSkipped + " weatherErrors=" + weatherErrors
                + String.format(java.util.Locale.ROOT, " weatherGpuMs=%.3f weatherGpuSamples=%d",
                        weatherGpuMillis, weatherGpuSamples)
                + " scopeMeshes=" + scopeMeshes + " fadedMeshes=" + (fadedMeshes < 0 ? "unobserved" : fadedMeshes)
                + " plannedMeshes=" + (plannedMeshes < 0 ? "unobserved" : plannedMeshes)
                + " shadows=" + shadowSource + " " + visibility + " " + draws.info() + " " + worldComparison + " " + ScopePipCpu.info()
                + String.format(java.util.Locale.ROOT, " exclusionCpuMs=%.3f prewarmMs=%.1f", exclusionMillis, prewarmMillis);
    }
}
