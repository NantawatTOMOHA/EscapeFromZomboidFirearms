package pz.escapefromzomboidfirearms.zb;

import me.zed_0xff.zombie_buddy.Exposer;
import zombie.characters.IsoGameCharacter;
import zombie.core.skinnedmodel.advancedanimation.AnimNode;
import zombie.core.skinnedmodel.advancedanimation.AnimState;
import zombie.core.skinnedmodel.advancedanimation.AnimationSet;

/**
 * Exits of the mod's action animations (FB4-A, user report 19.10.2026, item 18: "the exits from animations are too
 * jerky"). The mod's action nodes of player/actions - the grip classes' reload, rack, magazine check and drop nodes
 * (names with "_TW_", generate_animsets.py, reload_clips.py) and the Tarkov utility nodes ("TWInspect_",
 * weapon_inspect.py) - set no m_BlendOutTime, so the engine took their blend-in time (AnimNode.getBlendOutTime):
 * 0.08-0.4 s from a clip's last frame, the reload pose, to the carry or aim stance. They leave over at least EXIT_S,
 * the fixed cross-fade of Tarkov's own exit into its idle (Hands.RELOAD CHARGE END -> Hands.IDLE, 0.25 s,
 * assets/tarkov_clips.json controllers ak74/m4a1); the utility nodes come in over it too (they were cut in over 0.08 s).
 * The node data of the loaded set is changed once per game (OnGameStart, TW_ReloadVisual.lua); vanilla nodes, the
 * mod's overrides of vanilla names and longer times are left as they are.
 */
@Exposer.LuaClass(name = "TWActionExitsNative")
public final class ActionExits {
    public static final float EXIT_S = 0.25f;
    public static volatile int nodes, exits, entries;
    public static volatile String status = "not applied";

    private ActionExits() { }

    /** One of the mod's action nodes (by its generated name). */
    public static boolean ours(String name) {
        return name != null && (name.contains("_TW_") || name.startsWith("TWInspect_"));
    }

    /**
     * A timed utility's clip (inspect, chamber check, mode check; not the fire-mode gesture's): it ends in a hold of
     * its last frame EXIT_S long and its node leaves EXIT_S early (m_EarlyTransitionOut), so the exit is a cross-fade
     * from the clip's last frame, as Tarkov's, instead of the finished track dropping out in one frame
     * (tools/weapon_inspect.py HOLD_S). Sources sampled by the clip's fraction map its motion, not the hold.
     */
    public static boolean held(String clip) {
        return clip != null && clip.startsWith("TWInspect_") && !clip.contains("_Switch");
    }

    /** The exits (and the utility entries) of the mod's action nodes in the loaded player set; the count changed. */
    public static int apply() {
        try {
            AnimationSet set = AnimationSet.GetAnimationSet("player", false);
            AnimState actions = set == null ? null : set.GetState("actions");
            if (actions == null) {
                status = "no player actions state";
                return 0;
            }
            int n = 0, out = 0, in = 0;
            for (AnimNode node : actions.nodes) {
                if (!ours(node.name)) {
                    continue;
                }
                n++;
                if (node.getBlendOutTime() < EXIT_S) {
                    node.blendOutTime = EXIT_S;
                    out++;
                }
                if (node.name.startsWith("TWInspect_") && node.blendTime < EXIT_S) {
                    node.blendTime = EXIT_S;
                    in++;
                }
            }
            nodes = n;
            exits += out;
            entries += in;
            status = "applied";
            return out + in;
        } catch (Throwable t) {
            status = "failed: " + t;
            System.out.println("[TWZB] action exits not changed: " + t);
            return 0;
        }
    }

    /** QA: overrides the blend times of the mod's action nodes (A/B of item 18); returns how many were set. */
    public static int qaBlend(double in, double out) {
        AnimationSet set = AnimationSet.GetAnimationSet("player", false);
        AnimState actions = set == null ? null : set.GetState("actions");
        int n = 0;
        if (actions != null) {
            for (AnimNode node : actions.nodes) {
                if (ours(node.name)) {
                    if (in >= 0 && node.name.startsWith("TWInspect_")) node.blendTime = (float) in;
                    if (out != 0) node.blendOutTime = (float) out;
                    n++;
                }
            }
        }
        return n;
    }

    /** QA (A/B of item 18): the early exit of the held utility nodes on or off; returns how many were set. */
    public static int qaEarly(boolean on) {
        AnimationSet set = AnimationSet.GetAnimationSet("player", false);
        AnimState actions = set == null ? null : set.GetState("actions");
        int n = 0;
        if (actions != null) {
            for (AnimNode node : actions.nodes) {
                if (held(node.animName)) {
                    node.earlyTransitionOut = on;
                    n++;
                }
            }
        }
        return n;
    }

    /** QA: "status= nodes= exits= entries=". */
    public static String info() {
        return "status=" + status + " nodes=" + nodes + " exits=" + exits + " entries=" + entries;
    }

    private static final org.lwjgl.util.vector.Matrix4f BONE = new org.lwjgl.util.vector.Matrix4f();
    private static final org.joml.Matrix4f JW = new org.joml.Matrix4f();

    /**
     * QA (FB4-A items 13, 18, 19): Bip01_Prop1 (the weapon), the right and the left hand of a character in its look
     * frame (u forward, v left, h up, tile units as WeaponFrame.look) and Prop1's bore axis (model +Y), as one line
     * "prop=u,v,h rh=u,v,h lh=u,v,h bore=x,y,z", from the bones after every layer; empty without an animation player.
     * Then the same in the skeleton's own (model) frame, "mprop= mrh= mlh= mbore=" - an action's exit is judged there,
     * a turn of the whole body is not an exit - with Prop1's side axis (model +X, "mside=": a roll about the bore, FB5
     * item 2), and the look and the rendered (body) angle in degrees, "look= face=".
     */
    public static String bones(IsoGameCharacter c) {
        zombie.core.skinnedmodel.animation.AnimationPlayer ap = c == null ? null : c.getAnimationPlayer();
        if (ap == null || !ap.isReady()) {
            return "";
        }
        double l = c.getLookAngleRadians(), cs = Math.cos(l), sn = Math.sin(l);
        float a = ap.getRenderedAngle();
        StringBuilder s = new StringBuilder(), m = new StringBuilder();
        double[] bore = null, mbore = null, mside = null;
        for (String[] bone : new String[][] {{"prop", "Bip01_Prop1"}, {"rh", "Bip01_R_Hand"}, {"lh", "Bip01_L_Hand"}}) {
            int i = ap.getSkinningBoneIndex(bone[1], -1);
            if (i < 0) {
                return "";
            }
            ap.getBoneModelTransform(i, BONE);
            zombie.core.math.PZMath.convertMatrix(BONE, JW).transpose();
            m.append(String.format(java.util.Locale.ROOT, " m%s=%.5f,%.5f,%.5f", bone[0], JW.m30(), JW.m31(), JW.m32()));
            if (mbore == null) {
                double n = Math.sqrt(JW.m10() * JW.m10() + JW.m11() * JW.m11() + JW.m12() * JW.m12());
                mbore = new double[] {JW.m10() / n, JW.m11() / n, JW.m12() / n};
                double k = Math.sqrt(JW.m00() * JW.m00() + JW.m01() * JW.m01() + JW.m02() * JW.m02());
                mside = new double[] {JW.m00() / k, JW.m01() / k, JW.m02() / k};
            }
            zombie.iso.Vector3 v = new zombie.iso.Vector3(JW.m30(), JW.m31(), JW.m32());
            zombie.core.skinnedmodel.model.Model.vectorToWorldCoords(0, 0, 0, a, v);
            double u = v.x * cs + v.y * sn, w = -v.x * sn + v.y * cs, h = v.z * 2.44949;
            s.append(bone[0]).append('=').append(String.format(java.util.Locale.ROOT, "%.5f,%.5f,%.5f ", u, w, h));
            if (bore == null) {
                zombie.iso.Vector3 y = new zombie.iso.Vector3(JW.m10(), JW.m11(), JW.m12());
                zombie.core.skinnedmodel.model.Model.vectorToWorldCoords(0, 0, 0, a, y);
                double n = Math.sqrt(y.x * y.x + y.y * y.y + y.z * y.z * 2.44949 * 2.44949);
                bore = new double[] {(y.x * cs + y.y * sn) / n, (-y.x * sn + y.y * cs) / n, y.z * 2.44949 / n};
            }
        }
        s.append(String.format(java.util.Locale.ROOT, "bore=%.5f,%.5f,%.5f", bore[0], bore[1], bore[2])).append(m);
        return s.append(String.format(java.util.Locale.ROOT, " mbore=%.5f,%.5f,%.5f mside=%.5f,%.5f,%.5f look=%.2f face=%.2f", mbore[0], mbore[1],
                mbore[2], mside[0], mside[1], mside[2], Math.toDegrees(l), Math.toDegrees(a))).toString();
    }

    /**
     * QA (item 18): the live animation nodes of a character's layers after the last update, "root(state){node:weight
     * ...} subN(state){...}", a blending-in node marked ^, a blending-out one v; empty without an animator.
     */
    public static String layers(IsoGameCharacter c) {
        zombie.core.skinnedmodel.advancedanimation.AdvancedAnimator aa = c == null ? null : c.getAdvancedAnimator();
        if (aa == null) {
            return "";
        }
        StringBuilder s = new StringBuilder();
        try {
            layer(s, "root", aa.getRootLayer());
            for (int i = 0; i < aa.getSubLayerCount(); i++) {
                layer(s, "sub" + i, aa.getSubLayerAt(i));
            }
        } catch (RuntimeException e) {
            s.append("(changing)");
        }
        return s.toString();
    }

    private static void layer(StringBuilder s, String label, zombie.core.skinnedmodel.advancedanimation.AnimLayer l) {
        if (l == null) {
            return;
        }
        s.append(label).append('(').append(l.getCurrentStateName()).append("){");
        for (zombie.core.skinnedmodel.advancedanimation.LiveAnimNode n : l.getLiveAnimNodes()) {
            s.append(n.getName()).append(':').append(String.format(java.util.Locale.ROOT, "%.2f", n.getWeight()))
                    .append(n.isBlendingOut() ? "v" : n.isBlendingIn() ? "^" : "").append(' ');
        }
        s.append("} ");
    }
}
