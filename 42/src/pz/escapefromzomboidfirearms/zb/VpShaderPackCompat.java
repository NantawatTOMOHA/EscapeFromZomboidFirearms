package pz.escapefromzomboidfirearms.zb;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import me.zed_0xff.zombie_buddy.Exposer;

/**
 * Viewpoint shader-pack compatibility and runtime inspection (W11 item 4: shaders).
 *
 * When a Minecraft (Iris/OptiFine) shader pack is active in Viewpoint, WorldRenderer.begin()
 * calls IrisMode.draw(), sets WorldRenderer.irisDrawn = true, and skips WorldRenderer.drawWorld(),
 * drawTranslucent(), drawWeather(), and FrameEnd.draw(). The live scene and hand/weapon depth is
 * written to IrisMode.pipeline.targets.depth (IrisTargets.depth, GL_DEPTH_COMPONENT32F) rather than
 * WorldRenderer.targets.depth, and WorldRenderer.targets.hdrFbo is never composited to the screen.
 *
 * Every runtime entry point (isIrisDrawn, sceneDepth, overrideTargets) checks WorldRenderer.irisDrawn
 * first so native Viewpoint packs (default, vivid, trueweathers) execute their exact original path.
 */
@Exposer.LuaClass
public final class VpShaderPackCompat {
    private static boolean bound;
    private static boolean failed;
    private static Field graphicsPack;
    private static Field graphicsMode;
    private static Field choiceOptions;
    private static Method choiceGet;
    private static Method choiceSet;
    private static Method choiceText;
    private static Method settingsEdited;
    private static Field settingsDirty;
    private static Method shaderPacksActive;
    private static Method shaderPacksDrawn;
    private static Method shaderPacksFailure;
    private static Field shaderActivePack;
    private static Field shaderActiveGen;
    private static Field shaderPackId;
    private static Method irisPacksActive;
    private static Method irisPacksDrawn;
    private static Method irisPacksFailure;
    private static Method irisActivePack;
    private static Method irisActiveGen;
    private static Field irisPackId;

    private static boolean irisBound;
    private static boolean irisFailed;
    private static MethodHandle irisDrawnGetter;
    private static Field irisModePipeline;
    private static Field irisPipelineTargets;
    private static Field irisTargetsDepth;
    private static Field irisTargetsWidth;
    private static Field irisTargetsHeight;

    public static volatile boolean compatEnabled = true;
    public static volatile long irisDepthReads;
    public static volatile long irisTargetOverrides;
    public static volatile int lastIrisDepth;
    public static volatile int lastIrisWidth;
    public static volatile int lastIrisHeight;

    private VpShaderPackCompat() {}

    public static void setCompatEnabled(boolean on) {
        compatEnabled = on;
    }

    public static boolean isCompatEnabled() {
        return compatEnabled;
    }

    private static boolean bindIris() {
        if (irisBound) return true;
        if (irisFailed) return false;
        try {
            ClassLoader cl = ViewpointBridge.class.getClassLoader();
            Class<?> wr = Class.forName("viewpoint.render.WorldRenderer", false, cl);
            Field irisDrawnField = field(wr, "irisDrawn");
            irisDrawnGetter = MethodHandles.lookup().unreflectGetter(irisDrawnField)
                    .asType(MethodType.methodType(boolean.class));
            Class<?> im = Class.forName("viewpoint.render.IrisMode", false, cl);
            irisModePipeline = field(im, "pipeline");
            Class<?> ip = Class.forName("viewpoint.render.IrisPipeline", false, cl);
            irisPipelineTargets = field(ip, "targets");
            Class<?> it = Class.forName("viewpoint.render.IrisTargets", false, cl);
            irisTargetsDepth = field(it, "depth");
            irisTargetsWidth = field(it, "width");
            irisTargetsHeight = field(it, "height");
            irisBound = true;
            return true;
        } catch (Throwable t) {
            irisFailed = true;
            return false;
        }
    }

    /** True when the current main frame was drawn by Viewpoint's Iris/Minecraft shader pipeline. */
    public static boolean isIrisDrawn() {
        if (!compatEnabled || !bindIris()) {
            return false;
        }
        try {
            return (boolean) irisDrawnGetter.invokeExact();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Returns the active scene depth texture ID for main-frame passes (reticles, PIP lens composite,
     * rangefinders). Starts with isIrisDrawn() so native Viewpoint packs return defaultDepth unchanged.
     */
    public static int sceneDepth(int defaultDepth) {
        if (!isIrisDrawn() || ViewpointPipBridge.picture()) {
            return defaultDepth;
        }
        try {
            Object pipe = irisModePipeline.get(null);
            if (pipe == null) return defaultDepth;
            Object targets = irisPipelineTargets.get(pipe);
            if (targets == null) return defaultDepth;
            int depth = irisTargetsDepth.getInt(targets);
            if (depth > 0) {
                irisDepthReads++;
                lastIrisDepth = depth;
                return depth;
            }
        } catch (Throwable ignored) {
        }
        return defaultDepth;
    }

    /**
     * Overrides ViewpointBridge.targets(out) with IrisTargets.{depth, width, height} when
     * WorldRenderer.irisDrawn is true on the main frame. No-op for native Viewpoint packs.
     */
    public static void overrideTargets(int[] out) {
        if (!isIrisDrawn() || ViewpointPipBridge.picture() || out == null || out.length < 8) {
            return;
        }
        try {
            Object pipe = irisModePipeline.get(null);
            if (pipe == null) return;
            Object targets = irisPipelineTargets.get(pipe);
            if (targets == null) return;
            int depth = irisTargetsDepth.getInt(targets);
            int w = irisTargetsWidth.getInt(targets);
            int h = irisTargetsHeight.getInt(targets);
            if (depth > 0 && w > 0 && h > 0) {
                out[0] = depth;
                out[1] = w;
                out[2] = h;
                out[3] = w;
                out[4] = h;
                out[7] = 0;
                irisTargetOverrides++;
                lastIrisDepth = depth;
                lastIrisWidth = w;
                lastIrisHeight = h;
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean bind() {
        if (bound) return true;
        if (failed) return false;
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            Class<?> graphics = Class.forName("viewpoint.platform.Graphics", false, cl);
            graphicsPack = field(graphics, "pack");
            graphicsMode = field(graphics, "mode");
            Class<?> choice = Class.forName("viewpoint.platform.LiveSettings$Choice", false, cl);
            choiceOptions = field(choice, "options");
            choiceGet = method(choice, "get");
            choiceSet = method(choice, "set", int.class);
            choiceText = method(choice, "text");
            Class<?> sw = Class.forName("viewpoint.platform.SettingsWindow", false, cl);
            settingsEdited = method(sw, "edited");
            settingsDirty = field(sw, "dirty");

            Class<?> sp = Class.forName("viewpoint.platform.ShaderPacks", false, cl);
            shaderPacksActive = method(sp, "active");
            shaderPacksDrawn = method(sp, "drawn");
            shaderPacksFailure = method(sp, "failure");
            Class<?> spActive = Class.forName("viewpoint.platform.ShaderPacks$Active", false, cl);
            shaderActivePack = field(spActive, "pack");
            shaderActiveGen = field(spActive, "generation");
            Class<?> shaderPack = Class.forName("viewpoint.platform.ShaderPack", false, cl);
            shaderPackId = field(shaderPack, "id");

            Class<?> ip = Class.forName("viewpoint.platform.IrisPacks", false, cl);
            irisPacksActive = method(ip, "active");
            irisPacksDrawn = method(ip, "drawn");
            irisPacksFailure = method(ip, "failure");
            Class<?> ipActive = Class.forName("viewpoint.platform.IrisPacks$Active", false, cl);
            irisActivePack = method(ipActive, "pack");
            irisActiveGen = method(ipActive, "generation");
            Class<?> irisPack = Class.forName("viewpoint.iris.IrisPack", false, cl);
            irisPackId = field(irisPack, "id");

            bound = true;
            return true;
        } catch (Throwable t) {
            failed = true;
            return false;
        }
    }

    public static boolean selectPack(String id) {
        if (id == null || id.isEmpty() || !bind()) return false;
        try {
            Object pack = graphicsPack.get(null);
            Object mode = graphicsMode.get(null);
            if (pack == null || mode == null) return false;
            String[] options = (String[]) choiceOptions.get(pack);
            if (options == null) return false;
            int want = -1;
            for (int i = 0; i < options.length; i++) {
                if (id.equals(options[i])) {
                    want = i;
                    break;
                }
            }
            if (want < 0) return false;
            choiceSet.invoke(pack, want);
            choiceSet.invoke(mode, 1);
            settingsEdited.invoke(null);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isPackReady(String id) {
        if (id == null || id.isEmpty() || !bind()) return false;
        try {
            if (Boolean.TRUE.equals(settingsDirty.get(null))) return false;
            Object pack = graphicsPack.get(null);
            if (pack == null) return false;
            String selected = (String) choiceText.invoke(pack);
            if (!id.equals(selected)) return false;
            if (id.startsWith("minecraft-")) {
                Object fail = irisPacksFailure.invoke(null);
                if (fail != null) return true;
                Object act = irisPacksActive.invoke(null);
                Object drw = irisPacksDrawn.invoke(null);
                if (act == null || drw == null) return false;
                Object actPack = irisActivePack.invoke(act);
                Object drwPack = irisActivePack.invoke(drw);
                String actId = actPack == null ? "" : (String) irisPackId.get(actPack);
                String drwId = drwPack == null ? "" : (String) irisPackId.get(drwPack);
                int actGen = (Integer) irisActiveGen.invoke(act);
                int drwGen = (Integer) irisActiveGen.invoke(drw);
                return id.equals(actId) && id.equals(drwId) && actGen == drwGen;
            } else {
                if (irisPacksActive.invoke(null) != null) return false;
                Object fail = shaderPacksFailure.invoke(null);
                if (fail != null) return true;
                Object act = shaderPacksActive.invoke(null);
                Object drw = shaderPacksDrawn.invoke(null);
                if (act == null || drw == null) return false;
                Object actPack = shaderActivePack.get(act);
                Object drwPack = shaderActivePack.get(drw);
                String actId = actPack == null ? "" : (String) shaderPackId.get(actPack);
                String drwId = drwPack == null ? "" : (String) shaderPackId.get(drwPack);
                int actGen = shaderActiveGen.getInt(act);
                int drwGen = shaderActiveGen.getInt(drw);
                return id.equals(actId) && id.equals(drwId) && actGen == drwGen;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    public static String packInfo() {
        if (!bind()) return "bound=false";
        try {
            Object pack = graphicsPack.get(null);
            Object mode = graphicsMode.get(null);
            String selected = pack == null ? "null" : (String) choiceText.invoke(pack);
            int modeVal = mode == null ? -1 : (Integer) choiceGet.invoke(mode);
            String[] options = pack == null ? null : (String[]) choiceOptions.get(pack);
            Object spAct = shaderPacksActive.invoke(null);
            Object spDrw = shaderPacksDrawn.invoke(null);
            Object spFail = shaderPacksFailure.invoke(null);
            String spActId = spAct == null ? "none" : (String) shaderPackId.get(shaderActivePack.get(spAct));
            String spDrwId = spDrw == null ? "none" : (String) shaderPackId.get(shaderActivePack.get(spDrw));
            Object ipAct = irisPacksActive.invoke(null);
            Object ipDrw = irisPacksDrawn.invoke(null);
            Object ipFail = irisPacksFailure.invoke(null);
            String ipActId = ipAct == null ? "none" : (String) irisPackId.get(irisActivePack.invoke(ipAct));
            String ipDrwId = ipDrw == null ? "none" : (String) irisPackId.get(irisActivePack.invoke(ipDrw));
            return "selected=" + selected
                    + " mode=" + modeVal
                    + " dirty=" + settingsDirty.get(null)
                    + " modernActive=" + spActId
                    + " modernDrawn=" + spDrwId
                    + " modernFail=" + (spFail == null ? "none" : spFail.toString())
                    + " irisActive=" + ipActId
                    + " irisDrawn=" + ipDrwId
                    + " irisFail=" + (ipFail == null ? "none" : ipFail.toString())
                    + " worldIrisDrawn=" + isIrisDrawn()
                    + " irisDepth=" + lastIrisDepth
                    + " irisSize=" + lastIrisWidth + "x" + lastIrisHeight
                    + " irisDepthReads=" + irisDepthReads
                    + " irisTargetOverrides=" + irisTargetOverrides
                    + " options=" + (options == null ? "none" : String.join(",", options));
        } catch (Throwable t) {
            return "error=" + t;
        }
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static Method method(Class<?> c, String name, Class<?>... params) throws NoSuchMethodException {
        Method m = c.getDeclaredMethod(name, params);
        m.setAccessible(true);
        return m;
    }
}
