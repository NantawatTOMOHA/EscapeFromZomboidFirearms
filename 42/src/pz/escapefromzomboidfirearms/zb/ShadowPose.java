package pz.escapefromzomboidfirearms.zb;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

import org.joml.Matrix4f;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.core.skinnedmodel.model.SkinningData;

/**
 * FB7 item 1 (22.10.2026, user's report: "the shadow of the character in Viewpoint's first person looks strange, in
 * third person it becomes normal"). Viewpoint captures the local player once per frame (ModelCapture.capture): the
 * skinned entries get a colour and a shadow palette built from the same skin transforms, the rigid weapon and its parts
 * one matrix for both passes. Our first-person pose layer (FpPose) holds the weapon at the eye with Tarkov's first-person
 * arms, made for the camera: cast on the ground, that pose showed a hand at the head and the muzzle down, or the arms
 * spread. The shadow now casts the third-person pose of the same update:
 *
 *   record   FpPose, local player in first person: the model transforms before the layer (GripIK and Viewpoint's arm
 *            pitch, what third person shows) and after it; D_i = M_tp_i * M_fp_i^-1 of every bone the layer moved
 *   skinned  each skinned entry of that capture (body, clothing, hair) with the SHADOW flag: a copy of its shadow palette
 *            with A * D_i * A^-1 laid over the moved bones (A: Viewpoint's carried body adjustment, LegReach.apply, read
 *            from the palette itself: palette(head) * skin(head)^-1); the copy is added with ModelDraws.palette and
 *            becomes the entry's shadow palette. The colour palette is not touched
 *   rigid    each rigid entry with SHADOW hanging from a moved bone (Prop1, Prop2, a hand): the entry keeps its colour,
 *            loses SHADOW, and a shadow-only copy is appended with place * A * D_bone * A^-1 * place^-1 * matrix
 *            (the same record otherwise: fade, sphere, palettes, SELF; previous -1, no motion key). The copy is written
 *            into Viewpoint's arrays the way ModelDraws.add writes a record, without calling add: our other advice on
 *            add (lens glass, iron sight probe, drawn frame) must not see a second weapon root
 *
 * Third person, isometric view and other characters are untouched (FpPose holds no first-person pose there). Viewpoint's
 * classes are not patched: one guarded advice on ModelCapture.capture (enter: the first record of the capture; exit: the
 * records it added) and the members of MEMBERS. A member of another type or a missing one turns this feature off with
 * one log line; the bridge and the rest of the mod stay on. Main thread only (FpPose's update and the capture).
 */
public final class ShadowPose {
    static final String[][] MEMBERS = {
        {"viewpoint.models.ModelCapture", "capture", "(Lviewpoint/core/Frame;Lzombie/core/skinnedmodel/model/ModelSlotRenderData;Lzombie/characters/IsoGameCharacter;ZZLviewpoint/models/ModelCapture$Pose;FFFF)Z", "static"},
        {"viewpoint.models.ModelCapture", "place", "Lorg/joml/Matrix4f;", "static"},
        {"viewpoint.render.SceneData", "models", "Lviewpoint/render/ModelDraws;", ""},
        {"viewpoint.render.ModelDraws", "meshes", "[Lzombie/core/skinnedmodel/model/ModelMesh;", ""},
        {"viewpoint.render.ModelDraws", "instances", "[Lzombie/core/skinnedmodel/model/ModelInstance;", ""},
        {"viewpoint.render.ModelDraws", "textures", "[Lzombie/core/textures/Texture;", ""},
        {"viewpoint.render.ModelDraws", "items", "[Lzombie/core/skinnedmodel/model/ItemModelRenderer;", ""},
        {"viewpoint.render.ModelDraws", "values", "[F", ""},
        {"viewpoint.render.ModelDraws", "palette", "[I", ""},
        {"viewpoint.render.ModelDraws", "shadowPalette", "[I", ""},
        {"viewpoint.render.ModelDraws", "cull", "[I", ""},
        {"viewpoint.render.ModelDraws", "flags", "[I", ""},
        {"viewpoint.render.ModelDraws", "material", "[I", ""},
        {"viewpoint.render.ModelDraws", "keys", "[Ljava/lang/Object;", ""},
        {"viewpoint.render.ModelDraws", "previous", "[I", ""},
        {"viewpoint.render.ModelDraws", "count", "I", ""},
        {"viewpoint.render.ModelDraws", "palettes", "[F", ""},
        {"viewpoint.render.ModelDraws", "bones", "I", ""},
        {"viewpoint.render.ModelDraws", "palette", "([FI)I", ""},
        {"viewpoint.render.ModelDraws", "VALUES", "I", "static"},
        {"viewpoint.render.ModelDraws", "INDICES", "I", "static"},
        {"viewpoint.render.ModelDraws", "BONE_FLOATS", "I", "static"},
        {"viewpoint.render.ModelDraws", "COLOUR", "I", "static"},
        {"viewpoint.render.ModelDraws", "SHADOW", "I", "static"},
        {"viewpoint.render.ModelDraws", "MOTION", "I", "static"},
        {"viewpoint.render.ModelDraws", "OUTLINE_BEHIND", "I", "static"},
        {"viewpoint.render.ModelDraws", "TARGET", "I", "static"},
    };
    /** QA A/B: false leaves the first-person pose in the shadow (the behaviour before FB7). */
    public static volatile boolean enabled = true;
    /** A bone counts as moved by the layer above this difference of its matrix entries. */
    static final double MOVED = 1e-6;
    /** Bones of the QA check: where the shadow places them against the third-person pose. */
    static final String[] CHECKED = {"Bip01_L_Hand", "Bip01_R_Hand", "Bip01_L_Forearm", "Bip01_R_Forearm", "Bip01_Prop1"};

    private static boolean checked;
    private static volatile boolean ready;
    private static Field place, models, meshes, instances, textures, items, values, palette, shadowPalette, cull, flags,
            material, keys, previous, count, palettes, bones;
    private static Method addPalette;
    private static int stride, indices, colour, shadow, motion, behind, target;
    private static volatile String status = "awaiting-first-person";

    /** The newest first-person update of the local player: per bone D or null (not moved), the third-person matrices. */
    static final class Deltas {
        final AnimationPlayer ap;
        final SkinningData sd;
        final long frame;
        final int n;
        final double[][] d, tp;
        Deltas(AnimationPlayer ap, SkinningData sd, long frame, int n, double[][] d, double[][] tp) {
            this.ap = ap; this.sd = sd; this.frame = frame; this.n = n; this.d = d; this.tp = tp;
        }
    }
    private static volatile Deltas latest;
    private static final Map<SkinningData, String[]> NAMES = new IdentityHashMap<>();
    private static final Map<SkinningData, Map<SkinningData, int[]>> MAPS = new IdentityHashMap<>();

    // the capture in progress (enter -> exit)
    private static Object openFrame;
    private static Object openDraws;
    private static int openStart = -1;
    private static IsoGameCharacter openCharacter;
    public static volatile boolean snowHookEnabled = true;
    private static boolean snowHookChecked;
    private static Method snowCaptureExit;
    private static volatile String snowHookStatus = "unchecked";
    private static volatile long snowHookCalls;

    // QA counters and the last capture's measurements; stale = staleNone + staleOld + stalePlayer
    public static volatile long captures, stale, staleNone, staleOld, stalePlayer, misses, errors;
    /** A String, or the last capture's measurements (Measured), turned into text only when QA reads info(). */
    private static volatile Object last = "none";

    /** Performance pass 23.10.2026: the last capture's QA values; their String.format ran every first-person frame. */
    private record Measured(int skinned, int rigid, int copies, int moved, double handDev, double rootDev, double rootFp,
                            boolean colourKept) {
        @Override public String toString() {
            return String.format(Locale.ROOT, "skinned=%d rigid=%d copies=%d moved=%d handDevCm=%.4f rootDevCm=%.4f rootFpDevCm=%.4f colourKept=%b",
                    skinned, rigid, copies, moved, handDev, rootDev, rootFp, colourKept);
        }
    }

    private ShadowPose() { }

    private static synchronized boolean detect() {
        if (checked) return ready && ViewpointBridge.ready();
        checked = true;
        if (!ViewpointBridge.detect()) return false;
        try {
            Member[] found = new Member[MEMBERS.length];
            for (int i = 0; i < found.length; i++) {
                found[i] = ViewpointBridge.resolve(MEMBERS[i], ShadowPose.class.getClassLoader());
                if (found[i] == null) throw new NoSuchFieldException(MEMBERS[i][0] + "." + MEMBERS[i][1]);
            }
            place = (Field) found[1]; models = (Field) found[2]; meshes = (Field) found[3]; instances = (Field) found[4];
            textures = (Field) found[5]; items = (Field) found[6]; values = (Field) found[7]; palette = (Field) found[8];
            shadowPalette = (Field) found[9]; cull = (Field) found[10]; flags = (Field) found[11]; material = (Field) found[12];
            keys = (Field) found[13]; previous = (Field) found[14]; count = (Field) found[15]; palettes = (Field) found[16];
            bones = (Field) found[17]; addPalette = (Method) found[18];
            stride = ((Field) found[19]).getInt(null);
            indices = ((Field) found[20]).getInt(null);
            int boneFloats = ((Field) found[21]).getInt(null);
            colour = ((Field) found[22]).getInt(null);
            shadow = ((Field) found[23]).getInt(null);
            motion = ((Field) found[24]).getInt(null);
            behind = ((Field) found[25]).getInt(null);
            target = ((Field) found[26]).getInt(null);
            if (boneFloats != 12) throw new IllegalStateException("palette bones of " + boneFloats + " floats, 12 expected");
            if (stride < 16 || stride > 4096 || indices < 16 || indices + 4 > stride) throw new IllegalStateException("model record layout");
            if (shadow == 0 || (shadow & colour) != 0) throw new IllegalStateException("draw flags");
            ready = true;
            status = "on";
        } catch (Throwable error) {
            off("Viewpoint ABI: " + error);
        }
        return ready;
    }

    /** This feature off (the shadow as before), one log line; the Viewpoint bridge stays on. */
    private static void off(String reason) {
        ready = false;
        errors++;
        status = "off: " + reason;
        System.out.println("[TWZB] first-person shadow pose off: " + reason);
    }

    // ------------------------------------------------------------------ FpPose (main thread, animation update)

    /**
     * FpPose asks before its first-person pose: keep the third-person matrices of this update. Only the character's own
     * AnimationPlayer: a UI avatar (the character info window's 3D model) reports the same character from another one,
     * and its update came after the body's in some frames (QA fb7shadowlook: patrol and aim at 180 and 270 degrees);
     * FpPose skips such updates (FpPose.foreign), this guard stays for any other caller.
     */
    static boolean wanted(IsoGameCharacter c, AnimationPlayer ap) {
        return enabled && (!checked || ready) && c instanceof IsoPlayer p && p.isLocalPlayer() && p.getPlayerNum() == 0
                && ap == c.getAnimationPlayer();
    }

    static double[][] copy(double[][] model, int n) {
        double[][] out = new double[n][];
        for (int i = 0; i < n; i++) out[i] = model[i].clone();
        return out;
    }

    /** FpPose after its first-person pose: D of every bone it moved. */
    static void record(AnimationPlayer ap, SkinningData sd, int n, double[][] tp) {
        try {
            double[][] d = new double[n][];
            double[] fp = new double[16];
            for (int i = 0; i < n; i++) {
                GripIK.read(ap.getModelTransformAt(i), fp);
                double moved = 0;
                for (int k = 0; k < 16; k++) moved = Math.max(moved, Math.abs(fp[k] - tp[i][k]));
                if (moved > MOVED) d[i] = GripIK.mulNew(tp[i], GripIK.inverse(fp));
            }
            latest = new Deltas(ap, sd, FpRecoil.worldFrame(), n, d, tp);
        } catch (Throwable error) {
            errors++;
            latest = null;
        }
    }

    // ------------------------------------------------------------------ the capture (main thread)

    /** Advice target: the first record of this capture. */
    public static void enter(Object frame, IsoGameCharacter character) {
        openFrame = null;
        openDraws = null;
        openStart = -1;
        openCharacter = null;
        if (AnimOptOut.blocksLocalView()) return; // W8 animopt: no first-person pose, so no shadow of it
        if (!enabled || !(character instanceof IsoPlayer p) || !p.isLocalPlayer() || p.getPlayerNum() != 0
                || ViewpointBridge.state() != 1 || !detect()) return;
        try {
            Object draws = models.get(ViewpointBridge.sceneOf(frame));
            if (draws == null) return;
            openStart = count.getInt(draws);
            openFrame = frame;
            openDraws = draws;
            openCharacter = character;
        } catch (Throwable error) {
            off("capture start: " + error);
        }
    }

    /** Advice target: the records this capture added. */
    public static void exit(ModelSlotRenderData slotData, IsoGameCharacter character, boolean completed) {
        Object frame = openFrame;
        Object draws = openDraws;
        int start = openStart;
        IsoGameCharacter who = openCharacter;
        openFrame = null;
        openDraws = null;
        openStart = -1;
        openCharacter = null;
        if (draws == null || who != character || !completed || !ready) return;
        Deltas d = latest;
        if (d == null || character.getAnimationPlayer() != d.ap || !FpPose.freshFrame(d.frame, FpRecoil.worldFrame())) {
            stale++;
            if (d == null) staleNone++;
            else if (character.getAnimationPlayer() != d.ap) stalePlayer++;
            else staleOld++;
            last = d == null ? "stale:none" : character.getAnimationPlayer() != d.ap ? "stale:player"
                    : "stale:old age=" + (FpRecoil.worldFrame() - d.frame);
            return;
        }
        runSnowCaptureExit(frame, slotData, character);
        try {
            process(draws, start, slotData, d);
            captures++;
        } catch (ViewpointBridge.DataMiss miss) {
            misses++;
            ViewpointBridge.miss("shadow pose", miss.getMessage());
        } catch (Throwable error) {
            off("capture: " + error);
        }
    }

    /**
     * W11 snow (29.10.2026, user item 7): when EscapeFromZomboidFirearms is listed before ImmersiveSnowViewpoint in
     * mods/default.txt, ShadowPose.Capture.exit runs before isnowvp.Patch_ModelCapture.exit. CharacterShells.exit checks
     * models.count() - start == slotData.modelData.size() and returns early if ShadowPose has already appended its
     * shadow-only copies. Invoking isnowvp.Hooks.captureExit here runs CharacterShells.exit while the record count
     * still matches slotData.modelData.size(); CharacterShells.exit resets its start to -1 on entry, so the subsequent
     * isnowvp.Patch_ModelCapture.exit (or this call when ImmersiveSnowViewpoint ran first) is a no-op.
     */
    private static void runSnowCaptureExit(Object frame, ModelSlotRenderData slotData, IsoGameCharacter character) {
        if (!snowHookEnabled || frame == null || slotData == null || character == null) return;
        Method hook = snowCaptureExit;
        if (!snowHookChecked) {
            snowHookChecked = true;
            ClassLoader[] loaders = {
                ShadowPose.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader(),
                frame.getClass().getClassLoader(),
            };
            String[] names = {"mod", "context", "system", "frame"};
            Class<?> hooks = null;
            String loaderName = null;
            for (int i = 0; i < loaders.length; i++) {
                ClassLoader cl = loaders[i];
                if (cl == null) continue;
                try {
                    hooks = Class.forName("isnowvp.Hooks", false, cl);
                    loaderName = names[i] + "(" + cl.getClass().getName() + ")";
                    break;
                } catch (ClassNotFoundException ignored) {
                } catch (Throwable error) {
                    snowHookOff("lookup: " + error);
                    return;
                }
            }
            if (hooks == null) {
                snowHookStatus = "absent";
                return;
            }
            try {
                try {
                    hook = hooks.getMethod("captureExit", Object.class, Object.class, Object.class);
                } catch (NoSuchMethodException ignored) {
                    hook = hooks.getMethod("captureExit", Object.class, ModelSlotRenderData.class, IsoGameCharacter.class);
                }
                snowCaptureExit = hook;
                snowHookStatus = "on:" + loaderName;
                System.out.println("[TWZB] first-person shadow snow hook on: isnowvp.Hooks.captureExit via " + loaderName);
            } catch (Throwable error) {
                snowHookOff("signature: " + error);
                return;
            }
        }
        if (hook == null) return;
        try {
            hook.invoke(null, frame, slotData, character);
            snowHookCalls++;
        } catch (Throwable error) {
            snowHookOff("invoke: " + error);
        }
    }

    private static void snowHookOff(String reason) {
        snowCaptureExit = null;
        snowHookStatus = "off: " + reason;
        System.out.println("[TWZB] first-person shadow snow hook off: " + reason);
    }

    static String snowInfo() {
        return "status=" + snowHookStatus + " enabled=" + snowHookEnabled + " checked=" + snowHookChecked + " calls=" + snowHookCalls;
    }

    private static void process(Object draws, int start, ModelSlotRenderData slotData, Deltas d) throws ReflectiveOperationException {
        int end = count.getInt(draws);
        ModelInstance[] inst = (ModelInstance[]) instances.get(draws);
        int[] fl = (int[]) flags.get(draws);
        int[] sp = (int[]) shadowPalette.get(draws);
        int[] cp = (int[]) palette.get(draws);
        double[] a = null, aInv = null;
        // Performance pass 23.10.2026: A * D_k * A^-1 depends on the bone only (A is the capture's one adjustment), so it
        // is made once per bone and shared by every skinned entry and the rigid copies - the same products as before.
        double[][] conjugate = new double[d.n][];
        int skinned = 0, rigid = 0, copies = 0;
        double handDev = -1;
        boolean colourKept = true;
        // skinned entries: the shadow palette with the third-person bones
        for (int i = start; i < end; i++) {
            ModelInstance mi = inst[i];
            Model m = mi == null ? null : mi.model;
            if (m == null || m.isStatic || (fl[i] & shadow) == 0 || !(m.tag instanceof SkinningData sd)) continue;
            int n = sd.numBones(), s = sp[i];
            float[] pal = (float[]) palettes.get(draws);
            if (s < 0 || n <= 0 || (s + n) * 12 > pal.length || s + n > bones.getInt(draws)) throw new ViewpointBridge.DataMiss("shadow palette range");
            if (a == null) {
                a = adjustment(pal, s, sd, d.ap);
                aInv = GripIK.inverse(a);
            }
            int[] map = map(d.sd, sd);
            float[] copy = Arrays.copyOfRange(pal, s * 12, (s + n) * 12);
            int colourBefore = cp[i];
            for (int k = 0; k < d.n && k < map.length; k++) {
                int b = map[k];
                if (d.d[k] == null || b < 0 || b >= n) continue;
                if (conjugate[k] == null) conjugate[k] = GripIK.mulNew(GripIK.mulNew(a, d.d[k]), aInv);
                over(copy, b * 12, conjugate[k]);
            }
            int index = (Integer) addPalette.invoke(draws, copy, n);
            sp[i] = index;
            float[] v = (float[]) values.get(draws);
            v[i * stride + indices + 1] = index;
            colourKept &= cp[i] == colourBefore && cp[i] != index;
            if (skinned == 0) handDev = handDeviation((float[]) palettes.get(draws), index, sd, d, a);
            skinned++;
        }
        if (a == null) throw new ViewpointBridge.DataMiss("no skinned shadow entry");
        // rigid entries hanging from a moved bone: colour stays, a shadow-only copy carries the third-person bone
        Matrix4f placed = (Matrix4f) place.get(null);
        double[] p = rowMajor(placed), pInv = GripIK.inverse(p);
        double rootDev = -1, rootFp = -1;
        IsoPlayer player = (IsoPlayer) d.ap.getIsoGameCharacter();
        int slotCount = slotData != null && slotData.modelData != null ? slotData.modelData.size() : 0;
        for (int i = start; i < end; i++) {
            ModelInstance mi = inst[i];
            if (mi == null && i - start < slotCount) {
                var sub = slotData.modelData.get(i - start);
                if (sub != null) mi = sub.modelInstance;
            }
            Model m = mi == null ? null : mi.model;
            if (m == null || !m.isStatic || (fl[i] & shadow) == 0) continue;
            int bone = attachBone(mi, d.ap, d.n);
            if (bone < 0 || d.d[bone] == null) continue;
            rigid++;
            float[] v = (float[]) values.get(draws);
            double[] matrix = new double[16];
            for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) matrix[r * 4 + c] = v[i * stride + c * 4 + r];
            if (conjugate[bone] == null) conjugate[bone] = GripIK.mulNew(GripIK.mulNew(a, d.d[bone]), aInv);
            double[] conj = conjugate[bone];
            double[] moved = GripIK.mulNew(GripIK.mulNew(GripIK.mulNew(p, conj), pInv), matrix);
            append(draws, i, moved);
            copies++;
            fl = (int[]) flags.get(draws);
            v = (float[]) values.get(draws);
            fl[i] &= ~shadow;
            v[i * stride + indices + 3] = fl[i];
            if (player != null && mi == player.primaryHandModel && bone == d.sd.boneIndices.getOrDefault("Bip01_Prop1", -1)) {
                double[] pa = GripIK.inverse(GripIK.mulNew(p, a));
                rootDev = distance(GripIK.mulNew(pa, moved), d.tp[bone]);
                double[] fpBone = GripIK.read(d.ap.getModelTransformAt(bone), new double[16]);
                rootFp = distance(GripIK.mulNew(pa, matrix), fpBone);
            }
        }
        last = new Measured(skinned, rigid, copies, moved(d), handDev, rootDev, rootFp, colourKept);
    }

    /** A of LegReach.apply (shared by every bone outside the legs): palette(head) * skin(head)^-1, row-major 4x4. */
    private static double[] adjustment(float[] pal, int start, SkinningData sd, AnimationPlayer ap) {
        Integer head = sd.boneIndices == null ? null : sd.boneIndices.get("Bip01_Head");
        org.lwjgl.util.vector.Matrix4f[] skin = ap.getSkinTransforms(sd);
        if (head == null || skin == null || head < 0 || head >= skin.length || skin[head] == null) throw new ViewpointBridge.DataMiss("head bone");
        double[] palHead = palette(pal, (start + head) * 12);
        double[] a = GripIK.mulNew(palHead, GripIK.inverse(GripIK.read(skin[head], new double[16])));
        for (double x : a) if (!Double.isFinite(x)) throw new ViewpointBridge.DataMiss("carried body adjustment");
        return a;
    }

    /** QA: the largest distance (cm) of a checked bone's origin under the new shadow palette from its third-person place. */
    private static double handDeviation(float[] pal, int start, SkinningData sd, Deltas d, double[] a) {
        org.lwjgl.util.vector.Matrix4f[] skin = d.ap.getSkinTransforms(sd);
        double[] aInv = GripIK.inverse(a);
        double worst = 0;
        for (String name : CHECKED) {
            Integer b = sd.boneIndices.get(name), k = d.sd.boneIndices.get(name);
            if (b == null || k == null || b >= skin.length || k >= d.n) continue;
            double[] fpModel = GripIK.read(d.ap.getModelTransformAt(k), new double[16]);
            double[] bind = GripIK.mulNew(GripIK.inverse(GripIK.read(skin[b], new double[16])), fpModel);
            double[] placed = GripIK.mulNew(GripIK.mulNew(aInv, palette(pal, (start + b) * 12)), bind);
            worst = Math.max(worst, distance(placed, d.tp[k]));
        }
        return worst;
    }

    private static double distance(double[] m, double[] n) {
        return Math.sqrt(sq(m[3] - n[3]) + sq(m[7] - n[7]) + sq(m[11] - n[11])) / GripIK.CM;
    }

    private static double sq(double x) { return x * x; }

    private static int moved(Deltas d) {
        int n = 0;
        for (double[] x : d.d) if (x != null) n++;
        return n;
    }

    /**
     * The bone of the character a rigid entry hangs from: the parent bone of its top static instance (ModelManager
     * parents an attachment to the character's model, parts to the weapon), or -1.
     */
    private static int attachBone(ModelInstance mi, AnimationPlayer ap, int n) {
        ModelInstance x = mi;
        for (int depth = 0; depth < 64 && x.parent != null && x.parent.model != null && x.parent.model.isStatic; depth++) x = x.parent;
        boolean onBody = x.parent != null ? x.parent.animPlayer == ap : x.animPlayer == ap;
        return onBody && x.parentBone >= 0 && x.parentBone < n ? x.parentBone : -1;
    }

    /** Bone k of the posed skeleton as a bone of another skinning (clothing), -1 where it has none. */
    private static int[] map(SkinningData from, SkinningData to) {
        Map<SkinningData, int[]> row = MAPS.computeIfAbsent(from, k -> new IdentityHashMap<>());
        int[] map = row.get(to);
        if (map == null) {
            String[] names = NAMES.computeIfAbsent(from, ShadowPose::names);
            map = new int[names.length];
            for (int k = 0; k < names.length; k++) {
                Integer b = names[k] == null || to.boneIndices == null ? null : to.boneIndices.get(names[k]);
                map[k] = b == null ? -1 : b;
            }
            row.put(to, map);
        }
        return map;
    }

    private static String[] names(SkinningData sd) {
        String[] out = new String[sd.numBones()];
        if (sd.boneIndices != null) {
            for (Map.Entry<String, Integer> e : sd.boneIndices.entrySet()) {
                int i = e.getValue();
                if (i >= 0 && i < out.length) out[i] = e.getKey();
            }
        }
        return out;
    }

    /** A palette bone (rows 0-2 of the affine matrix, 12 floats) as a row-major 4x4. */
    private static double[] palette(float[] pal, int at) {
        double[] m = new double[16];
        for (int i = 0; i < 12; i++) m[i] = pal[at + i];
        m[15] = 1;
        return m;
    }

    /** palette bone := t * palette bone (as ModelDraws.move lays a matrix over a bone). */
    private static void over(float[] pal, int at, double[] t) {
        double[] b = palette(pal, at);
        double[] r = GripIK.mulNew(t, b);
        for (int i = 0; i < 12; i++) pal[at + i] = (float) r[i];
    }

    /** JOML (column vectors) as the row-major 4x4 of GripIK. */
    private static double[] rowMajor(Matrix4f m) {
        double[] out = new double[16];
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) out[r * 4 + c] = m.get(c, r);
        return out;
    }

    /**
     * A shadow-only copy of record src with another matrix, appended the way ModelDraws.add appends a record (its
     * arrays doubled when full); the copy keeps src's palettes, cull, material, fade, sphere and flags except colour,
     * motion, outline and target.
     */
    private static void append(Object draws, int src, double[] matrix) throws ReflectiveOperationException {
        int n = count.getInt(draws);
        if (n == ((Object[]) meshes.get(draws)).length) {
            int size = n * 2;
            for (Field f : new Field[] {meshes, instances, textures, items, keys}) {
                Object[] old = (Object[]) f.get(draws);
                f.set(draws, Arrays.copyOf(old, size));
            }
            values.set(draws, Arrays.copyOf((float[]) values.get(draws), size * stride));
            for (Field f : new Field[] {palette, shadowPalette, cull, flags, material, previous}) {
                f.set(draws, Arrays.copyOf((int[]) f.get(draws), size));
            }
        }
        Object[] me = (Object[]) meshes.get(draws), in = (Object[]) instances.get(draws), tx = (Object[]) textures.get(draws),
                it = (Object[]) items.get(draws), ky = (Object[]) keys.get(draws);
        float[] v = (float[]) values.get(draws);
        int[] pa = (int[]) palette.get(draws), sp = (int[]) shadowPalette.get(draws), cu = (int[]) cull.get(draws),
                fl = (int[]) flags.get(draws), ma = (int[]) material.get(draws), pr = (int[]) previous.get(draws);
        int i = n;
        me[i] = me[src];
        in[i] = in[src];
        tx[i] = tx[src];
        it[i] = it[src];
        ky[i] = null;
        System.arraycopy(v, src * stride, v, i * stride, stride);
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) v[i * stride + c * 4 + r] = (float) matrix[r * 4 + c];
        pa[i] = pa[src];
        sp[i] = sp[src];
        cu[i] = cu[src];
        ma[i] = ma[src];
        pr[i] = -1;
        fl[i] = (fl[src] & ~(colour | motion | behind | target)) | shadow;
        v[i * stride + indices] = pa[i];
        v[i * stride + indices + 1] = sp[i];
        v[i * stride + indices + 2] = pr[i];
        v[i * stride + indices + 3] = fl[i];
        count.setInt(draws, n + 1);
    }

    /** QA: the state and the last first-person capture. */
    static String info() {
        Deltas d = latest;
        return "status=" + status + " enabled=" + enabled + " ready=" + ready + " captures=" + captures + " stale=" + stale
                + " staleNone=" + staleNone + " staleOld=" + staleOld + " stalePlayer=" + stalePlayer + " foreign=" + FpPose.foreign
                + " misses=" + misses + " errors=" + errors + " poseAge=" + (d == null ? "none" : String.valueOf(FpRecoil.worldFrame() - d.frame))
                + " last={" + last + "}";
    }

    @Patch(className = "viewpoint.models.ModelCapture", methodName = "capture", strictMatch = true)
    public static class Capture {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) Object frame, @Patch.Argument(2) IsoGameCharacter character) {
            ShadowPose.enter(frame, character);
        }

        @Patch.OnExit
        public static void exit(@Patch.Argument(1) ModelSlotRenderData slotData, @Patch.Argument(2) IsoGameCharacter character,
                                @Patch.Return boolean completed) {
            ShadowPose.exit(slotData, character, completed);
        }
    }
}
