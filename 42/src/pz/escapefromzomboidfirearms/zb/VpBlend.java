package pz.escapefromzomboidfirearms.zb;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

import org.joml.Matrix4f;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.sprite.GenericSpriteRenderState;
import zombie.core.textures.TextureDraw;

/**
 * Viewpoint 0.1.7's frame, the parts 0.1.6 does not have (user's task 06.11.2026: one mod for both versions).
 *
 * 0.1.7 runs single player at a fixed 30 steps a second and draws frames of its own in between (FrameBetween, replaying
 * the last step's draw list). The drawn records are no longer the captured ones: ModelBlend blends each record between
 * the last two steps, carries riders on their bones and turns the aiming arms to the newest mouse (ArmsTurn). Every hook
 * below targets a class or a method that exists only there, or binds an argument only there: on 0.1.6 ZombieBuddy applies
 * none of them. Reflection only, nothing of 0.1.7 is compiled in: this class loads alike with 0.1.6 and without Viewpoint.
 * A table mismatch turns this class off with one log line; the bridge stays on.
 *
 * Found with the patch Vali (RomaVale) and Claude sent for EFZ 1.1.1 (docs/REPORT_RU.md).
 */
public final class VpBlend {
    /** The advice targets. */
    static final String[][] MEMBERS = {
        {"viewpoint.render.WorldRenderer", "begin", "(Lviewpoint/render/SceneData;JFLorg/joml/Matrix4f;FFF)Z", "static"},
        {"viewpoint.render.FarOcclusion", "hides", "(Lviewpoint/render/ModelDraws;I)Z", "static"},
        {"viewpoint.render.ModelPass", "prepare", "(Lviewpoint/render/SceneData;JFFFF)V", ""},
        {"viewpoint.models.ArmsCapture", "capture", "(Lviewpoint/render/ModelDraws;Lzombie/characters/IsoGameCharacter;I)V", "static"},
        {"viewpoint.render.ModelBlend", "rides", "(Lviewpoint/render/ModelDraws;F)V", ""},
        {"viewpoint.render.ModelBlend", "drawn", "(Lviewpoint/render/ModelDraws;JFFFFFFFFFF)Lviewpoint/render/ModelDraws;", ""},
        {"viewpoint.FrameBetween", "check", "(Lzombie/core/sprite/SpriteRenderState;)Z", "static"},
        {"viewpoint.sim.SkeletonPort", "updating", "(Lzombie/core/skinnedmodel/ModelManager$ModelSlot;)V", "static"},
        {"viewpoint.models.CharacterRate", "skip", "(Lzombie/characters/IsoGameCharacter;Z)V", "static"},
    };

    /** What the hooks read and call. */
    static final String[][] DATA = {
        {"viewpoint.render.ModelBlend", "drawn", "Lviewpoint/render/ModelDraws;", ""},
        {"viewpoint.render.ModelDraws", "instances", "[Lzombie/core/skinnedmodel/model/ModelInstance;", ""},
        {"viewpoint.render.ModelDraws", "values", "[F", ""},
        {"viewpoint.render.ModelDraws", "count", "I", ""},
        {"viewpoint.render.ModelDraws", "VALUES", "I", "static"},
        {"viewpoint.render.ModelDraws", "SPHERE", "I", "static"},
        {"viewpoint.render.ModelDraws", "STAND", "I", "static"},
        {"viewpoint.render.ModelDraws", "UPRIGHT", "I", "static"},
        {"viewpoint.render.ModelDraws", "arms", "Lviewpoint/render/HeldArms;", ""},
        {"viewpoint.render.ModelDraws", "find", "(Ljava/lang/Object;)I", ""},
        {"viewpoint.render.ModelDraws", "key", "(I)Ljava/lang/Object;", ""},
        {"viewpoint.render.HeldArms", "first", "I", ""},
        {"viewpoint.render.HeldArms", "heldCount", "I", ""},
        {"viewpoint.render.HeldArms", "held", "[I", ""},
        {"viewpoint.render.HeldArms", "hold", "(I)V", ""},
        {"viewpoint.render.ModelPass", "looks", "Lviewpoint/render/LayeredLooks;", ""},
        {"viewpoint.render.LayeredLooks", "masks", "Lviewpoint/render/LookMasks;", ""},
        {"viewpoint.render.LayeredLooks", "generation", "I", ""},
        {"viewpoint.render.WorldRenderer", "models", "Lviewpoint/render/ModelPass;", "static"},
        {"viewpoint.FrameBetween", "pendingBody", "Z", "static"},
        {"viewpoint.sim.SkeletonPort", "leave", "(Lzombie/characters/IsoGameCharacter;Lzombie/core/skinnedmodel/animation/AnimationPlayer;)V", "static"},
    };

    private static volatile boolean checked, on;
    public static volatile String status = "unchecked";
    private static Field blendDrawn, drawInstances, drawValues, drawCount, drawArms;
    private static Field armsFirst, armsHeldCount, armsHeld;
    private static Field passLooks, looksMasks, looksGeneration, mainModels, pendingBody;
    private static int stride, sphereAt, standAt, upright;
    private static MethodHandle drawFind, drawKey, armsHold, portLeave;

    /** The frame's number and blend WorldRenderer.begin received: the scope picture's ModelPass.prepare takes the same. */
    public static volatile long frameNumber;
    public static volatile float frameBlend = 1f;
    /** Render thread: the main pass's drawn records and their scene (records). */
    private static Object drawn, drawnScene;

    public static volatile long heldAdded, partsPlaced, replays, reactionsKept;

    private VpBlend() {
    }

    /** Answers without the lock once checked: a hook asks for every zombie's update, from the render thread too. */
    static boolean on() {
        return checked ? on : check();
    }

    private static synchronized boolean check() {
        if (checked) {
            return on;
        }
        try {
            ClassLoader cl = VpBlend.class.getClassLoader();
            for (String[] row : MEMBERS) {
                if (ViewpointBridge.resolve(row, cl) == null) {
                    return off(row);
                }
            }
            Member[] m = new Member[DATA.length];
            for (int i = 0; i < m.length; i++) {
                m[i] = ViewpointBridge.resolve(DATA[i], cl);
                if (m[i] == null) {
                    return off(DATA[i]);
                }
            }
            blendDrawn = (Field) m[0];
            drawInstances = (Field) m[1];
            drawValues = (Field) m[2];
            drawCount = (Field) m[3];
            stride = ((Field) m[4]).getInt(null);
            sphereAt = ((Field) m[5]).getInt(null);
            standAt = ((Field) m[6]).getInt(null);
            upright = ((Field) m[7]).getInt(null);
            drawArms = (Field) m[8];
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            drawFind = lookup.unreflect((Method) m[9]).asType(MethodType.methodType(int.class, Object.class, Object.class));
            drawKey = lookup.unreflect((Method) m[10]).asType(MethodType.methodType(Object.class, Object.class, int.class));
            armsFirst = (Field) m[11];
            armsHeldCount = (Field) m[12];
            armsHeld = (Field) m[13];
            armsHold = lookup.unreflect((Method) m[14]).asType(MethodType.methodType(void.class, Object.class, int.class));
            passLooks = (Field) m[15];
            looksMasks = (Field) m[16];
            looksGeneration = (Field) m[17];
            mainModels = (Field) m[18];
            pendingBody = (Field) m[19];
            portLeave = lookup.unreflect((Method) m[20]).asType(MethodType.methodType(void.class, Object.class, Object.class));
            on = true;
            status = "on";
        } catch (Throwable t) {
            status = "lookup failed: " + t;
            System.out.println("[TWZB] Viewpoint blended frames: " + status + ", off (bridge stays on)");
        } finally {
            checked = true;
        }
        return on;
    }

    private static boolean off(String[] row) {
        status = "member " + row[0] + "." + row[1] + " " + row[2] + " missing or changed";
        System.out.println("[TWZB] Viewpoint blended frames: " + status + ", off (bridge stays on)");
        return false;
    }

    private static void fail(String where, Throwable t) {
        on = false;
        status = "off after error: " + where + ": " + t;
        System.out.println("[TWZB] Viewpoint blended frames: " + status);
    }

    // ------------------------------------------------------------------ the records as drawn

    public static void frame(long number, float blend) {
        frameNumber = number;
        frameBlend = blend;
    }

    /** Render thread, FpViewLatch.blendExit: the records the main pass draws this frame, for the readers of a scene's drawn records. */
    static void drawing(Object scene, Object records) {
        drawnScene = scene;
        drawn = records;
    }

    /** Render thread: the records of a scene as the main pass draws them: the blended ones on 0.1.7, the captured ones on 0.1.6. */
    static Object records(Object scene, Object captured) {
        Object d = drawn;
        return d != null && drawnScene == scene ? d : captured;
    }

    // ------------------------------------------------------------------ the scope picture

    /** True while the scope picture's world is drawn (the advice is inlined into Viewpoint: public). */
    public static boolean inPicture() {
        return ViewpointPipBridge.picture();
    }

    /**
     * Clothing comes from an atlas built once from files taken out of a cache as they are used (LookFiles.take). The scope's
     * own ModelPass has its own LayeredLooks that could never build it, so every character with layered looks was left out
     * of the picture (animals showed). The scope's looks keep their own records and read the main pass's atlas; nothing of
     * the main pass is written. Sharing the whole object crashed natively.
     */
    public static void shareLooks(Object pass) {
        if (!ViewpointPipBridge.picture() || !on()) {
            return;
        }
        try {
            Object main = mainModels.get(null);
            if (main == null || main == pass) {
                return;
            }
            Object mine = passLooks.get(pass), theirs = passLooks.get(main);
            if (mine == null || theirs == null || mine == theirs) {
                return;
            }
            Object masks = looksMasks.get(theirs);
            if (masks != null) {
                looksMasks.set(mine, masks);
                looksGeneration.setInt(mine, looksGeneration.getInt(theirs));
            }
        } catch (Throwable t) {
            fail("looks", t);
        }
    }

    // ------------------------------------------------------------------ a weapon's parts

    /**
     * ArmsTurn turns only what ArmsCapture holds: static models hung straight on an arm bone. Our parts (scope, magazine,
     * rails) hang on the weapon, so they stayed behind while the arms turned. Every model whose parent chain reaches a
     * held one is held too. Main thread, the local aiming player.
     */
    public static void heldParts(Object draws, int first) {
        if (!on()) {
            return;
        }
        try {
            Object arms = drawArms.get(draws);
            int base = armsHeldCount.getInt(arms);
            if (base == 0 || armsFirst.getInt(arms) != first) {
                return;
            }
            int[] held = (int[]) armsHeld.get(arms);
            int count = drawCount.getInt(draws);
            ModelInstance[] inst = (ModelInstance[]) drawInstances.get(draws);
            // The gun itself, if its bone is not among the arms' it has no reason to stay behind them
            IsoPlayer player = IsoPlayer.getInstance();
            ModelInstance weapon = player == null ? null : player.primaryHandModel;
            for (int i = first; i < count && i < inst.length && weapon != null; i++) {
                if (inst[i] == weapon && !contains(held, armsHeldCount.getInt(arms), i)) {
                    armsHold.invokeExact(arms, i);
                    held = (int[]) armsHeld.get(arms);
                    base = armsHeldCount.getInt(arms);
                    heldAdded++;
                }
            }
            for (int i = first; i < count && i < inst.length; i++) {
                ModelInstance m = inst[i];
                if (m == null || m.parent == null || contains(held, armsHeldCount.getInt(arms), i)) {
                    continue;
                }
                int depth = 0;
                for (ModelInstance p = m.parent; p != null && depth < 32; p = p.parent, depth++) {
                    if (holds(draws, held, base, p)) {
                        armsHold.invokeExact(arms, i);
                        held = (int[]) armsHeld.get(arms);
                        heldAdded++;
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            fail("held parts", t);
        }
    }

    private static boolean contains(int[] held, int n, int index) {
        for (int k = 0; k < n; k++) {
            if (held[k] == index) {
                return true;
            }
        }
        return false;
    }

    private static boolean holds(Object draws, int[] held, int n, Object instance) throws Throwable {
        for (int k = 0; k < n; k++) {
            if ((Object) drawKey.invokeExact(draws, held[k]) == instance) {
                return true;
            }
        }
        return false;
    }

    private static int[] parentOf = new int[64], depthOf = new int[64];
    private static final Matrix4f parentRaw = new Matrix4f(), parentDrawn = new Matrix4f(), child = new Matrix4f();

    /**
     * ModelBlend blends each record alone, and only a rigid model hung on a skinned model's bone follows another (a rider).
     * A static model hung on a static one (our parts on the weapon, of any character) is no rider and drifts off it while
     * walking. After the riders are placed, and before the arms turn, each such part is put back where it hangs on its
     * parent as drawn, parents first. Render thread.
     */
    public static void placeParts(Object blend, Object raw) {
        if (!on()) {
            return;
        }
        try {
            ModelInstance[] inst = (ModelInstance[]) drawInstances.get(raw);
            int count = Math.min(drawCount.getInt(raw), inst.length);
            int most = 0;
            if (parentOf.length < count) {
                parentOf = new int[count * 2];
                depthOf = new int[count * 2];
            }
            for (int i = 0; i < count; i++) {
                depthOf[i] = 0;
                ModelInstance m = inst[i];
                if (m == null || m.parent == null || m.model == null || !m.model.isStatic || m.parent.model == null || !m.parent.model.isStatic) {
                    continue;
                }
                int parent = (int) drawFind.invokeExact(raw, (Object) m.parent);
                if (parent < 0) {
                    continue;
                }
                int d = 1;
                for (ModelInstance p = m.parent; p.parent != null && p.parent.model != null && p.parent.model.isStatic && d < 32; p = p.parent) {
                    d++;
                }
                parentOf[i] = parent;
                depthOf[i] = d;
                most = Math.max(most, d);
            }
            if (most == 0) {
                return;
            }
            Object drawnNow = blendDrawn.get(blend);
            if (drawnNow == null || drawnNow == raw) {
                return;
            }
            float[] rv = (float[]) drawValues.get(raw), dv = (float[]) drawValues.get(drawnNow);
            for (int d = 1; d <= most; d++) {
                for (int i = 0; i < count; i++) {
                    if (depthOf[i] == d && placePart(rv, dv, i * stride, parentOf[i] * stride, sphereAt, standAt, upright)) {
                        partsPlaced++;
                    }
                }
            }
        } catch (Throwable t) {
            fail("parts", t);
        }
    }

    /** Pure: one part's drawn record from its parent's: parent drawn x parent captured^-1 x part captured; its sphere and stand along. */
    public static boolean placePart(float[] rv, float[] dv, int at, int parentAt, int sphere, int stand, int upright) {
        parentRaw.set(rv, parentAt);
        if (Math.abs(parentRaw.determinant3x3()) < 1.0e-12f) {
            return false;
        }
        parentDrawn.set(dv, parentAt).mul(parentRaw.invertAffine()).mul(child.set(rv, at)).get(dv, at);
        float dx = dv[at + 12] - rv[at + 12], dy = dv[at + 13] - rv[at + 13], dz = dv[at + 14] - rv[at + 14];
        dv[at + sphere] = rv[at + sphere] + dx;
        dv[at + sphere + 1] = rv[at + sphere + 1] + dy;
        dv[at + sphere + 2] = rv[at + sphere + 2] + dz;
        if (rv[at + stand + 3] == upright) {
            dv[at + stand] = rv[at + stand] + dx;
            dv[at + stand + 1] = rv[at + stand + 1] + dy;
            dv[at + stand + 2] = rv[at + stand + 2] + dz;
        }
        return true;
    }

    // ------------------------------------------------------------------ frames between the game's steps

    /**
     * FrameBetween replays the last step's draw list with the newest view, but only drawers it knows; our effects batch,
     * scope picture and sight marks kept the picture at 30 frames a second. They all draw again from what they hold: the
     * game runs postRender only when it reuses a state for populating, and the batch's primitives and the scope's snapshot
     * stay until then. A list whose only unknown drawers are ours is replayed too; anything else stays refused. Under the
     * Metal shim our drawers run once inside the frame (MetalFrame.takeTails), so there it stays refused.
     */
    public static boolean replayable(Object state) {
        if (!on() || !(state instanceof GenericSpriteRenderState s) || GLCompat.metalPath()) {
            return false;
        }
        try {
            if (pendingBody.getBoolean(null)) {
                return false;
            }
            boolean scene = false, ours = false;
            for (int i = 0; i < s.numSprites; i++) {
                TextureDraw d = s.sprite[i];
                if (d == null) {
                    continue;
                }
                if (d.type == TextureDraw.Type.DrawImGui) {
                    return false;
                }
                if (d.type != TextureDraw.Type.DrawModel || d.drawer == null) {
                    continue;
                }
                String n = d.drawer.getClass().getName();
                if (n.equals("viewpoint.SceneDrawer")) {
                    scene = true;
                } else if (n.startsWith("pz.escapefromzomboidfirearms.")) {
                    ours = true;
                } else if (!n.equals("viewpoint.OverWorldDrawer") && !n.equals("viewpoint.ViewSwitch$Picture")
                        && !n.equals("zombie.core.skinnedmodel.model.ModelOutlines$Drawer")) {
                    return false;
                }
            }
            if (scene && ours) {
                replays++;
            }
            return scene && ours;
        } catch (Throwable t) {
            fail("replay", t);
            return false;
        }
    }

    // ------------------------------------------------------------------ zombies shot

    /**
     * 0.1.7 works out zombies' skeletons itself (SkeletonPort) and turns the game's update off for them (CharacterRate), so a
     * zombie shot with our rounds kept walking where it staggered on 0.1.6. While it reacts, it is the game's again, the way
     * Viewpoint treats ragdolls. leave: hand it back now (SkeletonPort.updating); either way Viewpoint's own step is skipped.
     */
    public static boolean reacting(IsoGameCharacter c, boolean leave) {
        if (!(c instanceof IsoZombie z) || !on()) {
            return false;
        }
        try {
            boolean reacts = z.hasHitReaction() || z.isStaggerBack()
                    || z.getCurrentState() instanceof zombie.ai.states.ZombieHitReactionState
                    || z.getCurrentState() instanceof zombie.ai.states.StaggerBackState;
            if (!reacts) {
                return false;
            }
            if (leave) {
                AnimationPlayer player = z.getAnimationPlayer();
                if (player != null) {
                    portLeave.invokeExact((Object) z, (Object) player);
                    player.updateBones = true;
                }
                reactionsKept++;
            }
            return true;
        } catch (Throwable t) {
            fail("reaction", t);
            return false;
        }
    }

    public static String stats() {
        return "status=" + status + " blended=" + ViewpointBridge.blended() + " heldAdded=" + heldAdded + " partsPlaced=" + partsPlaced
                + " replays=" + replays + " reactionsKept=" + reactionsKept + " frame=" + frameNumber + " blend=" + frameBlend
                + " player=" + (IsoPlayer.getInstance() != null);
    }

    // ------------------------------------------------------------------ advice (inlined: public members only)

    @Patch(className = "viewpoint.render.WorldRenderer", methodName = "begin")
    public static final class Begin {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(1) long number, @Patch.Argument(2) float blend) {
            VpBlend.frame(number, blend);
        }
    }

    /** FarOcclusion tests far models against the main view's depth: through the scope, that is the gun, so everything behind it was left out. */
    @Patch(className = "viewpoint.render.FarOcclusion", methodName = "hides")
    public static final class Hides {
        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) boolean hidden) {
            if (VpBlend.inPicture()) {
                hidden = false;
            }
        }
    }

    @Patch(className = "viewpoint.render.ModelPass", methodName = "prepare")
    public static final class Prepare {
        @Patch.OnEnter
        public static void enter(@Patch.This Object pass, @Patch.Argument(5) float armsPitch) {
            VpBlend.shareLooks(pass);
        }
    }

    @Patch(className = "viewpoint.models.ArmsCapture", methodName = "capture")
    public static final class HeldParts {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) Object draws, @Patch.Argument(2) int first) {
            if (!ModLua.present()) return;
            VpBlend.heldParts(draws, first);
        }
    }

    @Patch(className = "viewpoint.render.ModelBlend", methodName = "rides")
    public static final class Parts {
        @Patch.OnExit
        public static void exit(@Patch.This Object blend, @Patch.Argument(0) Object raw) {
            if (!ModLua.present()) return;
            VpBlend.placeParts(blend, raw);
        }
    }

    /**
     * The twelve-argument ModelBlend.drawn, the last argument binds only there (the short form calls it). Entry: while our
     * pose holds the weapon, the arms take no turn to the newest mouse (FpViewLatch carries the whole frame's own records).
     * Exit: the drawn records are final but for us, and not uploaded yet.
     */
    @Patch(className = "viewpoint.render.ModelBlend", methodName = "drawn")
    public static final class Drawn {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) Object raw, @Patch.Argument(1) long number,
                @Patch.Argument(value = 10, readOnly = false) float armsYaw, @Patch.Argument(value = 11, readOnly = false) float armsPitch) {
            if (FpViewLatch.blendEnter(raw, number)) {
                armsYaw = 0f;
                armsPitch = 0f;
            }
        }

        @Patch.OnExit
        public static void exit(@Patch.Argument(0) Object raw, @Patch.Argument(1) long number, @Patch.Argument(5) float blend,
                @Patch.Argument(11) float armsPitch, @Patch.Return Object drawn) {
            FpViewLatch.blendExit(raw, drawn, number, blend);
        }
    }

    @Patch(className = "viewpoint.FrameBetween", methodName = "check")
    public static final class Between {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) Object state, @Patch.Return(readOnly = false) boolean playable) {
            if (!playable) {
                playable = VpBlend.replayable(state);
            }
        }
    }

    @Patch(className = "viewpoint.sim.SkeletonPort", methodName = "updating")
    public static final class ReactingZombies {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) ModelManager.ModelSlot slot) {
            if (!ModLua.present()) return false;
            return VpBlend.reacting(slot == null ? null : slot.character, true);
        }
    }

    @Patch(className = "viewpoint.models.CharacterRate", methodName = "skip")
    public static final class ReactingBones {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) IsoGameCharacter character) {
            if (!ModLua.present()) return false;
            return VpBlend.reacting(character, false);
        }
    }
}
