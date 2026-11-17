package pz.escapefromzomboidfirearms.zb;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import me.zed_0xff.zombie_buddy.Exposer;
import me.zed_0xff.zombie_buddy.Patch;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL43;

/**
 * Native Viewpoint DoF while aiming an EFZ weapon: focus through SELF and defocus SELF only.
 * Vivid's original autofocus sees the weapon; its positive far CoC then blurs the world.
 * Two GPU depth copies bracket the existing SELF draw. Changed depth identifies its visible
 * fragments without a screen rectangle, guessed weapon distance, extra model draw or readback.
 * The final depth must still match SELF: later geometry covering it never receives its blur.
 * Shader edits are in memory and exact-source guarded. Other packs, views, weapons and PiP
 * keep their original shaders/behaviour. The user's depthOfField option still owns the passes.
 */
@Exposer.LuaClass
public final class FpDepthOfField {
    /** Exact hook and reflected layout contract; a changed Viewpoint ABI leaves its shaders untouched. */
    static final String[][] MEMBERS = {
        {"viewpoint.platform.ShaderPack", "shader", "(Ljava/lang/String;)Ljava/lang/String;", ""},
        {"viewpoint.render.PackStages", "uniforms", "(Lviewpoint/platform/GlProgram;Lviewpoint/render/FrameContext;II)V", ""},
        {"viewpoint.render.PackStages", "draw", "(Lviewpoint/platform/PackPass;Lviewpoint/render/FrameContext;ILviewpoint/platform/PackPass$Stage;I)V", ""},
        {"viewpoint.platform.ShaderPacks", "active", "()Lviewpoint/platform/ShaderPacks$Active;", "static"},
        {"viewpoint.platform.ShaderPacks$Active", "passes", "Ljava/util/List;", ""},
        {"viewpoint.platform.ShaderPacks$Active", "pack", "Lviewpoint/platform/ShaderPack;", ""},
        {"viewpoint.platform.ShaderPack", "base", "Lviewpoint/platform/ShaderPack;", ""},
        {"viewpoint.platform.PackPass", "id", "Ljava/lang/String;", ""},
        {"viewpoint.platform.PackPass", "inputs", "Ljava/util/List;", ""},
        {"viewpoint.render.FrameContext", "scene", "Lviewpoint/render/SceneData;", ""},
        {"viewpoint.render.FrameContext", "index", "J", ""},
        {"viewpoint.render.WorldRenderer", "frame", "Lviewpoint/render/FrameContext;", "static"},
    };
    static final String FOCUS_SHA = "0ff8fa81e73e2bc5a7eb46efe78c64ee6378fbf0739cfac08b9be34ffabb61af";
    static final String DOF_SHA = "b1ec6fa02f0498a80ca03d676c005043beecdd7a7ab67f5b9bce86cdfcecec5b";
    private static final String UNIFORMS = "\nuniform float uTwDoFWeight;\nuniform sampler2D uTwWorldDepth;\nuniform sampler2D uTwSelfDepth;\nuniform vec4 uTwSharp;\n";
    private static final String OWN = """
        bool twOwn(vec2 uv) {
          float world = texture(uTwWorldDepth, uv).r;
          float self = texture(uTwSelfDepth, uv).r;
          return self < world && texture(uDepth, uv).r == self;
        }
        bool twSharp(vec2 uv) {
          return uTwSharp.z > 0.0 && uTwSharp.w > 0.0 && length((uv - uTwSharp.xy) / uTwSharp.zw) <= 1.0;
        }
        """;
    private static boolean bound, failed;
    private static Method activePack;
    private static Field activePasses, activePackField, packBase, passId, passInputs, frameSceneField, rendererFrame, frameIndex;
    private static final Map<Object, Map<String, Boolean>> sources = new WeakHashMap<>();
    private static final int[] targets = new int[8];
    private static final float[] sharp = new float[4];
    private static int worldDepth, selfDepth, width, height, format;
    private static Object capturedScene;
    private static long capturedIndex;
    private static float capturedWeight;
    private static boolean captureOpen, captured;
    private static boolean unitsChanged, activeChanged;
    private static int oldActive, oldWorld, oldSelf, oldWorldSampler, oldSelfSampler;
    private static volatile boolean enabled = true;
    private static volatile boolean focusPatched, dofPatched;
    private static volatile long copies, passes, errors, sourceMisses;
    private static volatile String lastError = "";

    private FpDepthOfField() {}

    /** QA A/B only; no persistent preference and no change to Viewpoint's settings. */
    public static void setEnabled(boolean value) { enabled = value; }

    /** Share verified sight geometry even when the separate see-through option is off. */
    public static boolean needsSight() { return enabled && focusPatched && dofPatched; }

    public static String info() {
        return "enabled=" + enabled + " bound=" + bound + " focus=" + focusPatched + " dof=" + dofPatched
                + " copies=" + copies + " passes=" + passes + " size=" + width + "x" + height
                + " weight=" + capturedWeight + " sharp=" + sharp[2] + " errors=" + errors + " sourceMisses=" + sourceMisses
                + " lastError=" + lastError;
    }

    /** Pure source adapter, tested against the exact installed resources and changed-source controls. */
    public static String adapt(String name, String source) {
        if (source == null || !("focus.frag".equals(name) || "dof.frag".equals(name))) return source;
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
            boolean focus = "focus.frag".equals(name);
            if (!(focus ? FOCUS_SHA : DOF_SHA).equals(hash)) {
                sourceMisses++;
                return source;
            }
            String result = source.replace("in vec2 vUv;", "in vec2 vUv;" + UNIFORMS);
            if (focus) {
                result = result.replace("texture(uDepth, uv).r", "(uTwDoFWeight > 0.001 ? texture(uTwWorldDepth, uv).r : texture(uDepth, uv).r)");
                focusPatched = true;
            } else {
                result = result.replace("float blur(float depth, float focus) {", OWN + "\nfloat blur(float depth, float focus) {\n"
                        + "  if (uTwDoFWeight > 0.001) return clamp((1.0 / depth - 1.0 / focus) * DOF_PER_DIOPTRE * OPTION_DOF_STRENGTH * uTwDoFWeight, 0.0, DOF_MAX);");
                result = result.replace("float here = blur(depth, focus);", "if (uTwDoFWeight > 0.001 && (!twOwn(vUv) || twSharp(vUv))) { fragColor = vec4(colour, 1.0); return; }\n  float here = blur(depth, focus);");
                result = result.replace("if (there < depth) reach = min(reach, here);", "if (uTwDoFWeight > 0.001 && (!twOwn(uv) || twSharp(uv))) reach = 0.0;\n    if (there < depth) reach = min(reach, here);");
                dofPatched = true;
            }
            return result;
        } catch (Throwable t) { error("source", t); return source; }
    }

    /** Remember the defining pack, including an unknown overriding source in a child pack. */
    public static String shader(Object pack, String name, String source) {
        String result = adapt(name, source);
        if (pack != null && source != null && ("focus.frag".equals(name) || "dof.frag".equals(name))) {
            synchronized (sources) {
                sources.computeIfAbsent(pack, p -> new HashMap<>()).put(name, result != source);
            }
        }
        return result;
    }

    /** Public because ZombieBuddy inlines the advice into Viewpoint's class. */
    public static boolean bind() {
        if (bound) return true;
        if (failed) return false;
        try {
            ClassLoader cl = ViewpointBridge.class.getClassLoader();
            for (String[] row : MEMBERS) {
                if (ViewpointBridge.resolve(row, cl) == null)
                    throw new NoSuchFieldException(row[0] + "." + row[1] + " ABI mismatch");
            }
            Class<?> packs = Class.forName("viewpoint.platform.ShaderPacks", false, cl);
            activePack = packs.getDeclaredMethod("active");
            activePack.setAccessible(true);
            activePasses = field(Class.forName("viewpoint.platform.ShaderPacks$Active", false, cl), "passes");
            activePackField = field(activePasses.getDeclaringClass(), "pack");
            packBase = field(Class.forName("viewpoint.platform.ShaderPack", false, cl), "base");
            Class<?> pass = Class.forName("viewpoint.platform.PackPass", false, cl);
            passId = field(pass, "id");
            passInputs = field(pass, "inputs");
            frameSceneField = field(Class.forName("viewpoint.render.FrameContext", false, cl), "scene");
            frameIndex = field(frameSceneField.getDeclaringClass(), "index");
            rendererFrame = field(Class.forName("viewpoint.render.WorldRenderer", false, cl), "frame");
            bound = true;
            return true;
        } catch (Throwable t) { failed = true; error("bind", t); return false; }
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** Active pass list already applies the user's depthOfField toggle and preset. */
    private static boolean selected() throws Throwable {
        Object active = activePack.invoke(null);
        if (active == null) return false;
        Object pack = activePackField.get(active);
        if (!knownSource(pack, "focus.frag") || !knownSource(pack, "dof.frag")) return false;
        boolean focus = false, dof = false;
        for (Object p : (List<?>) activePasses.get(active)) {
            String id = (String) passId.get(p);
            if ("focus".equals(id) || "depthOfField".equals(id)) {
                // Viewpoint owns input units 52..63. Reserve the last two only on these small passes.
                if (((List<?>) passInputs.get(p)).size() > 10) return false;
                focus |= "focus".equals(id);
                dof |= "depthOfField".equals(id);
            }
        }
        return focus && dof;
    }

    private static boolean knownSource(Object pack, String name) throws IllegalAccessException {
        synchronized (sources) {
            for (int depth = 0; pack != null && depth < 16; depth++, pack = packBase.get(pack)) {
                Map<String, Boolean> state = sources.get(pack);
                if (state != null && state.containsKey(name)) return Boolean.TRUE.equals(state.get(name));
            }
        }
        return false;
    }

    /** Called immediately before the existing SELF draw, after the normal world depth exists. */
    static void beforeSelf() {
        if (ViewpointPipBridge.picture()) return;
        captured = captureOpen = false;
        capturedScene = null;
        capturedWeight = 0;
        java.util.Arrays.fill(sharp, 0f);
        try {
            if (!ModLua.present() || !enabled || !focusPatched || !dofPatched || !ViewpointBridge.ready()
                    || ViewpointBridge.state() != 1 || VpShaderPackCompat.isIrisDrawn() || !bind() || !selected()) return;
            float[] weights = SeeThrough.poseWeight();
            if (weights == null || weights[1] <= 0.001f) return;
            ViewpointBridge.targets(targets);
            if (targets[0] <= 0 || targets[3] <= 0 || targets[4] <= 0) return;
            prepare(targets[0], targets[3], targets[4]);
            copy(targets[0], worldDepth);
            capturedScene = ViewpointBridge.currentScene();
            SeeThrough.frontSharp(capturedScene, targets[1], targets[2], sharp);
            capturedWeight = weights[1];
            capturedIndex = frameIndex.getLong(rendererFrame.get(null));
            captureOpen = capturedScene != null;
        } catch (Throwable t) { error("before", t); }
    }

    /** Called only after a successful SELF draw; failing draws cannot publish an old mask. */
    static void afterSelf() {
        if (ViewpointPipBridge.picture() || !captureOpen) return;
        captureOpen = false;
        try {
            copy(targets[0], selfDepth);
            captured = true;
            copies++;
        } catch (Throwable t) { captured = false; error("after", t); }
    }

    private static int copyRead, copyDraw;

    /**
     * The scene depth into one of ours (same size and format). GL 4.3 / ARB_copy_image copies the image as before;
     * without it (macOS, 02.11.2026: PZ's GL 2.1 context) a framebuffer blit of depth (and stencil, for a packed format)
     * copies the same texels, the read and draw framebuffers and the scissor test as they were after it.
     */
    private static void copy(int from, int to) {
        if (GLCompat.copyImage()) {
            GL43.glCopyImageSubData(from, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                    to, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, width, height, 1);
            return;
        }
        int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean stencil = format == GL30.GL_DEPTH24_STENCIL8 || format == GL30.GL_DEPTH32F_STENCIL8;
        int attachment = stencil ? GL30.GL_DEPTH_STENCIL_ATTACHMENT : GL30.GL_DEPTH_ATTACHMENT;
        try {
            if (copyRead == 0) {
                copyRead = GL30.glGenFramebuffers();
                copyDraw = GL30.glGenFramebuffers();
                // depth only: no colour buffer to read or draw (framebuffer completeness before GL 4.1)
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, copyRead);
                GL11.glReadBuffer(GL11.GL_NONE);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copyDraw);
                GL11.glDrawBuffer(GL11.GL_NONE);
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, copyRead);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, attachment, GL11.GL_TEXTURE_2D, from, 0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copyDraw);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, attachment, GL11.GL_TEXTURE_2D, to, 0);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                    GL11.GL_DEPTH_BUFFER_BIT | (stencil ? GL11.GL_STENCIL_BUFFER_BIT : 0), GL11.GL_NEAREST);
        } finally {
            if (copyRead != 0) {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, copyRead);
                GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, attachment, GL11.GL_TEXTURE_2D, 0, 0);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copyDraw);
                GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, attachment, GL11.GL_TEXTURE_2D, 0, 0);
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
    }

    private static void prepare(int source, int w, int h) {
        int old = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, source);
            int f = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            if (worldDepth != 0 && selfDepth != 0 && width == w && height == h && format == f) return;
            if (f != GL30.GL_DEPTH24_STENCIL8 && f != GL30.GL_DEPTH32F_STENCIL8
                    && f != GL30.GL_DEPTH_COMPONENT32F && f != GL14.GL_DEPTH_COMPONENT24) {
                throw new IllegalStateException("unsupported scene depth format " + f);
            }
            if (worldDepth != 0) GL11.glDeleteTextures(worldDepth);
            if (selfDepth != 0) GL11.glDeleteTextures(selfDepth);
            worldDepth = selfDepth = 0;
            width = w; height = h; format = f;
            worldDepth = texture();
            selfDepth = texture();
        } finally { GL11.glBindTexture(GL11.GL_TEXTURE_2D, old); }
    }

    private static int texture() {
        int t = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        int external = GL11.GL_DEPTH_COMPONENT, type = GL11.GL_FLOAT;
        if (format == GL30.GL_DEPTH24_STENCIL8 || format == GL30.GL_DEPTH32F_STENCIL8) {
            external = GL30.GL_DEPTH_STENCIL;
            type = format == GL30.GL_DEPTH24_STENCIL8 ? GL30.GL_UNSIGNED_INT_24_8 : GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV;
        }
        GLCompat.texImage2D(GL11.GL_TEXTURE_2D, 0, format, width, height, external, type);
        return t;
    }

    /** PackStages has bound its program and inputs. Restore our extra texture units after this draw. */
    public static void uniforms(Object frame) {
        if (!focusPatched || !dofPatched) return;
        try {
            int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int weight = GL20.glGetUniformLocation(program, "uTwDoFWeight");
            if (weight < 0) return;
            GL20.glUniform1f(weight, 0f);
            if (!ModLua.present() || !enabled || !captured || !bound || frame == null || ViewpointPipBridge.picture()
                    || ViewpointBridge.state() != 1 || frameSceneField.get(frame) != capturedScene
                    || frameIndex.getLong(frame) != capturedIndex) return;
            oldActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            activeChanged = true;
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + 62);
            oldWorld = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            oldWorldSampler = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + 63);
            oldSelf = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            oldSelfSampler = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
            unitsChanged = true;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, selfDepth);
            GL33.glBindSampler(63, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + 62);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, worldDepth);
            GL33.glBindSampler(62, 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "uTwWorldDepth"), 62);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "uTwSelfDepth"), 63);
            GL20.glUniform4f(GL20.glGetUniformLocation(program, "uTwSharp"), sharp[0], sharp[1], sharp[2], sharp[3]);
            GL20.glUniform1f(weight, capturedWeight);
            GL13.glActiveTexture(oldActive);
            passes++;
        } catch (Throwable t) { restore(); error("uniforms", t); }
    }

    public static void restore() {
        if (!unitsChanged && !activeChanged) return;
        try {
            if (unitsChanged) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + 62);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldWorld);
                GL33.glBindSampler(62, oldWorldSampler);
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + 63);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldSelf);
                GL33.glBindSampler(63, oldSelfSampler);
            }
        } catch (Throwable t) { error("restore", t); }
        finally {
            unitsChanged = false;
            activeChanged = false;
            try { GL13.glActiveTexture(oldActive); }
            catch (Throwable t) { error("active texture restore", t); }
        }
    }

    private static void error(String where, Throwable t) {
        errors++;
        lastError = where + ": " + t;
        if (errors == 1) System.out.println("[EFZ DoF] " + lastError);
    }

    @Patch(className = "viewpoint.platform.ShaderPack", methodName = "shader")
    public static class Shader {
        @Patch.OnExit
        public static void exit(@Patch.This Object pack, @Patch.Argument(0) String name, @Patch.Return(readOnly = false) String source) {
            if (!FpDepthOfField.bind()) return;
            source = FpDepthOfField.shader(pack, name, source);
        }
    }

    @Patch(className = "viewpoint.render.PackStages", methodName = "uniforms")
    public static class Uniforms {
        @Patch.OnExit
        public static void exit(@Patch.Argument(1) Object frame) { FpDepthOfField.uniforms(frame); }
    }

    @Patch(className = "viewpoint.render.PackStages", methodName = "draw")
    public static class Draw {
        @Patch.OnExit
        public static void exit() { FpDepthOfField.restore(); }
    }
}
