package pz.escapefromzomboidfirearms.zb;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import zombie.characters.IsoGameCharacter;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.model.SkinningData;

/**
 * Natural arms (user report 23.10.2026: "the arm turns over within a second - after entering ADS the wrist twists out,
 * the elbow too, and they stay so"; first person and the other views). The last layer over both arms of a character
 * holding an own weapon, whatever placed them (the clips, GripIK, FpPose, the grenade throw, the panic sway): the
 * shoulder, elbow and wrist points and the hand stay, the upper arm and the forearm turn about their own lengths as the
 * game's arm does.
 *
 * Measured (tools/arm_natural.py, tools/check_arm_natural.py): every vanilla clip keeps the elbow a hinge - the forearm
 * turns against its upper arm about its own local Y only (6786 frames of 264 clips, 0.0 degrees off it), so the upper
 * arm's roll is the one the elbow's plane gives and the forearm's pronation is the hand's turn at the wrist (left
 * -87..+100 degrees, right -83..+82: p1..p99 of 22926 frames). The bodies weight the elbow's skin ring half upper arm,
 * half forearm and the wrist's 70-78 % forearm (MaleBody.X, FemaleBody.X; Bob_Jacket's cuff 65 %): a forearm turned by
 * r against its upper arm narrows the elbow's ring by cos(r/2), to nothing at a half turn. The own clips (Blender
 * retargets) turned it by more than 20 degrees in 2069 of 3438, the layers by rule (FB4 twistShare up to 270 degrees,
 * GripIK's twist cap up to 90), each measured from a forearm swung from its clip's - no anatomical reference, so a
 * branch change there spun the arm.
 *
 *   hinge   the elbow's flexion about the forearm's Y from the angle at the elbow (the bind's own bend counted, 11.7
 *           degrees)
 *   plane   the upper arm along shoulder-elbow, its flexion side towards the wrist. A nearly straight arm's points give
 *           no plane: up to BLEND the flexion side comes in from where the arm's elbow is meant to be - for an arm a
 *           layer solved (solved(): FpPose, GripIK, the grenade throw, the panic sway) the side its IK took (the pole
 *           and the elbow's turns out of the torso; the first-person hip ready holds the support arm straight, and the
 *           elbow's plane, undefined there, turned 94 degrees in one step as the arm bent raising for ADS), for a
 *           clip's arm its previous flexion side carried by the turn of the upper arm that came in since (a clip's
 *           straight arm swung its points' plane 40 degrees between frames) - and the forearm is turned onto its wrist.
 *           A solved arm bends towards its elbow; a clip's arm may be straighter than straight, up to HYPER_MAX, where
 *           its previous flexion side says so (vanilla Bob_Signal_1H_MoveOut: 3.2 degrees)
 *   wrist   the hand's twist about the forearm against the bind (left supination, right pronation positive; 0: the
 *           thumb to the flexion side, anatomically neutral) stays at the wrist up to WRIST_FREE; past it the forearm
 *           takes ELBOW_SHARE of the rest about its own length (eased in over SOFT), at most ELBOW_MAX - the wrist keeps
 *           the vanilla look and the elbow's ring turns by half of the forearm's share
 *   branch  the twist is the shortest one; near the half turn the previous frame's branch is kept up to
 *           GripIK.HALF_TURN_BAND past it (GripIK.continuousTwist), and the forearm's share moves at most ROLL_SPEED
 */
public final class ArmNatural {
    public static volatile boolean enabled = true;
    static final double WRIST_FREE = Math.toRadians(90), SOFT = Math.toRadians(30), ELBOW_SHARE = 0.75, ELBOW_MAX = Math.toRadians(75);
    static final double ROLL_SPEED = Math.toRadians(720), BLEND = Math.toRadians(8), HYPER_MAX = Math.toRadians(10);
    static final double[] Y = {0, 1, 0};
    public static volatile long calls, applied, errors, nanos;
    public static volatile double maxTauDeg, maxRhoDeg, maxOffHingeDeg;
    public static volatile String lastError = "";
    /** QA: arms left as they came because the rebuild was not finite, and the first such arm's inputs. */
    public static volatile long skipped;
    static volatile String firstSkip = "";

    /** One arm's bind: the forearm in its upper arm's frame and the hand in its forearm's (unit 3x3), the bone axes (the
     *  child joint in the bone's own frame), the hinge angle's terms and the upper arm's flexion side. */
    static final class Bind {
        double[] fore, hand, axisU, axisF, flexU;
        double a, b, c, r0, phi;
    }

    /** Per character: the twist and the forearm's share of each arm last frame (radians), when, and QA; whether a layer
     *  solved each arm since the last pass. */
    static final class State {
        final double[] tau = new double[2], rho = new double[2], off = new double[2];
        /** The upper arm's flexion side last frame (model space) and the upper arm's rotation that came in, null before the first. */
        final double[][] flex = new double[2][], in = new double[2][];
        final boolean[] ik = new boolean[2];
        /** The side of its elbow the IK of a layer took this update (model space, across shoulder-wrist), else null. */
        final double[][] pole = new double[2][];
        long at;
        /** The frame's bones (model space), the rewritten ones and the arms' working copies, kept from frame to frame
         *  (sized by the skeleton; every hook of every character's model runs this). */
        double[][] model, next, upper = new double[2][16], fore = new double[2][16];
        boolean[] driver;

        void size(int n) {
            if (model == null || model.length != n) {
                model = new double[n][16];
                next = new double[n][];
                driver = new boolean[n];
            }
            java.util.Arrays.fill(next, null);
            java.util.Arrays.fill(driver, false);
        }
    }

    static final ConcurrentHashMap<SkinningData, Bind[]> BINDS = new ConcurrentHashMap<>();
    static final Map<IsoGameCharacter, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());

    private ArmNatural() {
    }

    /** GripIK.AfterModel, after the pose layers: both arms of a character with an own weapon's request. */
    public static void onModel(Object self) {
        if (AnimOptOut.blocks(self)) return; // W8 animopt: the elbow/wrist pass belongs to vanilla/other mods
        if (!enabled || GripIK.requests.isEmpty() && FpPose.requests.isEmpty() || !(self instanceof AnimationPlayer ap)
                || ap.parentPlayer != null) { // attached models copy the character's bones
            return;
        }
        IsoGameCharacter c = ap.getIsoGameCharacter(); // the cheap test before the clock: a character with a request
        if (c == null || !GripIK.requests.containsKey(c) && !FpPose.requests.containsKey(c)) {
            return;
        }
        long t0 = System.nanoTime();
        try {
            apply(ap, c, t0);
        } catch (Throwable e) {
            errors++;
            lastError = e.toString();
        } finally {
            nanos += System.nanoTime() - t0;
        }
    }

    /** A layer placed this arm's elbow by IK in this animation update (FpPose.place both, GripIK the ones it solves) on the
     *  given side (model space, across shoulder-wrist; null: unknown): the pass after it bends the elbow towards it. */
    static void solved(IsoGameCharacter c, int side, double[] elbowSide) {
        if (c != null) {
            State st = STATES.computeIfAbsent(c, k -> new State());
            st.ik[side] = true;
            st.pole[side] = elbowSide == null ? null : elbowSide.clone();
        }
    }

    static void apply(AnimationPlayer ap, IsoGameCharacter c, long t0) {
        SkinningData sd = ap.getSkinningData();
        if (sd == null || ap.boneTransforms == null) {
            return;
        }
        FpPose.Skeleton s = FpPose.skeleton(sd, Math.min(ap.boneTransforms.length, ap.getModelTransformsCount()));
        Bind[] binds = s == null ? null : binds(sd, s);
        if (binds == null) {
            return;
        }
        calls++;
        State st = STATES.computeIfAbsent(c, k -> new State());
        st.size(s.n);
        double[][] model = st.model;
        for (int i = 0; i < s.n; i++) {
            GripIK.read(ap.getModelTransformAt(i), model[i]);
        }
        boolean fresh = st.at != 0 && t0 - st.at < GripIK.ARM_RESET_NS; // a stalled frame carries over (GripIK.ARM_*)
        double dt = fresh ? Math.min(GripIK.ARM_DT_MAX, (t0 - st.at) / 1e9) : 0;
        st.at = t0;
        double[][] next = st.next;
        boolean[] driver = st.driver;
        for (int side = 0; side < 2; side++) {
            int ua = side == 0 ? s.ua : s.rua, fa = side == 0 ? s.fa : s.rfa, h = side == 0 ? s.lh : s.rh;
            double[] UA = st.upper[side], FA = st.fore[side];
            System.arraycopy(model[ua], 0, UA, 0, 16);
            System.arraycopy(model[fa], 0, FA, 0, 16);
            boolean ik = st.ik[side];
            double[] pole = ik ? st.pole[side] : null;
            st.ik[side] = false;
            st.pole[side] = null;
            if (!solve(UA, FA, model[h], binds[side], st, side, fresh, dt, ik, pole)) {
                continue;
            }
            next[ua] = UA;
            next[fa] = FA;
            next[h] = model[h];
            driver[ua] = driver[fa] = driver[h] = true;
        }
        FpPose.write(ap, s, model, next, driver);
        applied++;
    }

    static final ConcurrentHashMap<SkinningData, Bind> SUPPORT = new ConcurrentHashMap<>();

    /** The support (left) arm's bind of a skeleton (GripIK's relief). */
    static Bind bindOf(SkinningData sd, int ua, int fa, int lh) {
        Bind b = SUPPORT.get(sd);
        if (b == null && hasBind(sd, Math.max(ua, Math.max(fa, lh)) + 1)) {
            b = bind(sd, ua, fa, lh);
            SUPPORT.put(sd, b);
        }
        return b;
    }

    /** The wrist's anatomical twist of an arm about its points (radians, -pi..pi): no state, bent towards its elbow. */
    static double tau(double[] UA, double[] FA, double[] H, Bind b) {
        State st = new State();
        return solve(UA.clone(), FA.clone(), H, b, st, 0, false, 0, true, null) ? st.tau[0] : 0;
    }

    static Bind[] binds(SkinningData sd, FpPose.Skeleton s) {
        Bind[] b = BINDS.get(sd);
        if (b == null) {
            if (!hasBind(sd, s.n)) {
                return null;
            }
            b = new Bind[] {bind(sd, s.ua, s.fa, s.lh), bind(sd, s.rua, s.rfa, s.rh)};
            BINDS.put(sd, b);
        }
        return b;
    }

    /**
     * A bone's bind pose (model space) as its skin is bound: the inverse of the skin offset (SkinningData.boneOffset, the
     * .X SkinWeights matrix - in the game equal to body_mesh.parse's offsets to the last digit). inverseBindPose is the
     * skeleton's rest, 12-19 degrees off the skin's at the arm and without the bones' positions (QA probe 23.10.2026).
     */
    static double[] bindWorld(SkinningData sd, int i) {
        if (sd.boneOffset != null && sd.boneOffset.size() > i && sd.boneOffset.get(i) != null) {
            return GripIK.inverse(GripIK.read(sd.boneOffset.get(i), new double[16]));
        }
        return GripIK.inverse(GripIK.transpose4(GripIK.read(sd.inverseBindPose.get(i), new double[16])));
    }

    static boolean hasBind(SkinningData sd, int count) {
        return sd.boneOffset != null && sd.boneOffset.size() >= count || sd.inverseBindPose != null && sd.inverseBindPose.size() >= count;
    }

    static Bind bind(SkinningData sd, int ua, int fa, int h) {
        return bind(bindWorld(sd, ua), bindWorld(sd, fa), bindWorld(sd, h));
    }

    /** From the bind poses (model space) of the upper arm, the forearm and the hand. */
    static Bind bind(double[] U, double[] F, double[] H) {
        double[] u = rotation(U), f = rotation(F), hh = rotation(H);
        Bind b = new Bind();
        b.fore = GripIK.mul3(GripIK.transpose3(u), f);
        b.hand = GripIK.mul3(GripIK.transpose3(f), hh);
        b.axisU = axis(FpPose.rotate3(GripIK.transpose3(u), GripIK.sub(GripIK.pos(F), GripIK.pos(U))));
        b.axisF = axis(FpPose.rotate3(GripIK.transpose3(f), GripIK.sub(GripIK.pos(H), GripIK.pos(F))));
        b.flexU = GripIK.normalize(GripIK.cross(FpPose.rotate3(b.fore, Y), b.axisU));
        // the upper arm's axis against the forearm's at flexion t: p . RotY(t) q = a cos t + b sin t + c
        double[] p = FpPose.rotate3(GripIK.transpose3(b.fore), b.axisU), q = b.axisF;
        b.a = p[0] * q[0] + p[2] * q[2];
        b.b = p[0] * q[2] - p[2] * q[0];
        b.c = p[1] * q[1];
        b.r0 = Math.hypot(b.a, b.b);
        b.phi = Math.atan2(b.b, b.a);
        return b;
    }

    /** A bone's axis to its child in its own frame; without the bind's positions the PZ arm's local X (its measured axis). */
    static double[] axis(double[] v) {
        return GripIK.norm(v) > 1e-6 ? GripIK.normalize(v) : new double[] {1, 0, 0};
    }

    static double[] rotY(double t) {
        double c = Math.cos(t), s = Math.sin(t);
        return new double[] {c, 0, s, 0, 1, 0, -s, 0, c};
    }

    /** Unit 3x3 with the columns x and the part of y across it, and their cross product. */
    static double[] frame(double[] x, double[] y) {
        double[] ex = GripIK.normalize(x);
        double[] ey = GripIK.normalize(GripIK.sub(y, GripIK.scale(ex, GripIK.dot(ex, y))));
        double[] ez = GripIK.cross(ex, ey);
        return new double[] {ex[0], ey[0], ez[0], ex[1], ey[1], ez[1], ex[2], ey[2], ez[2]};
    }

    static boolean finite(double[] m) {
        for (double v : m) {
            if (!Double.isFinite(v)) {
                return false;
            }
        }
        return true;
    }

    /** A bone's rotation, orthonormal (its X column, then Y across it): skinning matrices may carry scale and shear. */
    static double[] rotation(double[] m) {
        double[] x = GripIK.normalize(new double[] {m[0], m[4], m[8]}), y = new double[] {m[1], m[5], m[9]};
        return frame(x, y);
    }

    /** QA: both arms' binds of a character's skeleton, its arms now and the first arm left as it came. */
    static String probe(IsoGameCharacter c) {
        AnimationPlayer ap = c == null ? null : c.getAnimationPlayer();
        SkinningData sd = ap == null ? null : ap.getSkinningData();
        FpPose.Skeleton s = sd == null || ap.boneTransforms == null ? null : FpPose.skeleton(sd, Math.min(ap.boneTransforms.length, ap.getModelTransformsCount()));
        Bind[] bs = s == null ? null : binds(sd, s);
        if (bs == null) {
            return "probe=none";
        }
        StringBuilder out = new StringBuilder("skipped=" + skipped);
        int[][] arms = {{s.ua, s.fa, s.lh}, {s.rua, s.rfa, s.rh}};
        for (int side = 0; side < 2; side++) {
            Bind b = bs[side];
            out.append(side == 0 ? " L:" : " R:").append(" fore=").append(java.util.Arrays.toString(b.fore)).append(" hand=")
                    .append(java.util.Arrays.toString(b.hand)).append(" axisU=").append(java.util.Arrays.toString(b.axisU)).append(" axisF=")
                    .append(java.util.Arrays.toString(b.axisF)).append(" flexU=").append(java.util.Arrays.toString(b.flexU))
                    .append(String.format(java.util.Locale.ROOT, " a=%.6f b=%.6f c=%.6f r0=%.6f phi=%.6f", b.a, b.b, b.c, b.r0, b.phi));
            for (int k = 0; k < 3; k++) {
                out.append(" bone").append(k).append('=').append(java.util.Arrays.toString(GripIK.read(ap.getModelTransformAt(arms[side][k]), new double[16])));
                out.append(" bind").append(k).append('=').append(java.util.Arrays.toString(bindWorld(sd, arms[side][k])));
                int i = arms[side][k];
                if (sd.boneOffset != null && sd.boneOffset.size() > i) {
                    out.append(" offsetRaw").append(k).append('=').append(java.util.Arrays.toString(GripIK.read(sd.boneOffset.get(i), new double[16])));
                }
                if (sd.bindPose != null && sd.bindPose.size() > i) {
                    out.append(" bindPoseRaw").append(k).append('=').append(java.util.Arrays.toString(GripIK.read(sd.bindPose.get(i), new double[16])));
                }
                out.append(" invBindRaw").append(k).append('=').append(java.util.Arrays.toString(GripIK.read(sd.inverseBindPose.get(i), new double[16])));
            }
        }
        return out.append(" firstSkip=").append(firstSkip).toString();
    }

    /** The turn of a unit rotation about the axis (radians, -pi..pi). */
    static double twist(double[] r, double[] axis) {
        double[] q = GripIK.quaternion(r);
        return Math.IEEEremainder(2 * Math.atan2(q[1] * axis[0] + q[2] * axis[1] + q[3] * axis[2], q[0]), 2 * Math.PI);
    }

    /** The forearm's share of the wrist's twist (radians, the twist's sign). */
    static double share(double tau) {
        double e = Math.abs(tau) - WRIST_FREE, h = SOFT / 2;
        double x = e <= -h ? 0 : e >= h ? e : (e + h) * (e + h) / (2 * SOFT);
        return Math.signum(tau) * Math.min(ELBOW_MAX, ELBOW_SHARE * x);
    }

    /**
     * The arm about its points (model space, in place: UA and FA rotations, scales and positions kept; H read only).
     * st: the previous frame's twist and share of this side when fresh; ik: the elbow placed by IK (it flexes), pole: the
     * side its IK took (null: unknown). False when the points coincide.
     */
    static boolean solve(double[] UA, double[] FA, double[] H, Bind b, State st, int side, boolean fresh, double dt, boolean ik, double[] pole) {
        double[] S = GripIK.pos(UA), E = GripIK.pos(FA), W = GripIK.pos(H);
        double[] su = GripIK.sub(E, S), sf = GripIK.sub(W, E);
        if (GripIK.norm(su) < 1e-9 || GripIK.norm(sf) < 1e-9) {
            return false;
        }
        double[] u = GripIK.normalize(su), f = GripIK.normalize(sf), rh = rotation(H);
        // QA: the forearm's turn against its upper arm as it came (0 on a hinge)
        double[] inU = rotation(UA), inF = rotation(FA);
        st.off[side] = twist(GripIK.mul3(GripIK.transpose3(b.fore), GripIK.mul3(GripIK.transpose3(inU), inF)), b.axisF);
        double k = GripIK.dot(u, f), a = Math.acos(Math.max(-1, Math.min(1, (k - b.c) / b.r0)));
        double[] fPerp = GripIK.sub(f, GripIK.scale(u, k));
        double sine = GripIK.norm(fPerp);
        double[] ref = fresh && st.flex[side] != null && st.in[side] != null
                ? FpPose.rotate3(GripIK.mul3(inU, GripIK.transpose3(st.in[side])), st.flex[side]) : FpPose.rotate3(inU, b.flexU);
        st.in[side] = inU;
        if (ik && pole != null) { // the forearm bends back from the elbow's side: the flexion side is across the upper arm from it
            double[] across = GripIK.sub(pole, GripIK.scale(u, GripIK.dot(pole, u)));
            if (GripIK.norm(across) > 1e-6) {
                ref = GripIK.scale(across, -1);
            }
        }
        ref = GripIK.sub(ref, GripIK.scale(u, GripIK.dot(ref, u)));
        ref = GripIK.norm(ref) > 1e-9 ? GripIK.normalize(ref) : GripIK.normalize(GripIK.cross(u, Math.abs(u[0]) < .8 ? new double[] {1, 0, 0} : Y));
        double[] flex = ref;
        boolean hyper = false;
        if (sine > 1e-12) {
            double[] g = GripIK.scale(fPerp, 1 / sine);
            if (!ik && a <= HYPER_MAX && GripIK.dot(g, ref) < 0) { // a clip's elbow straighter than straight
                g = GripIK.scale(g, -1);
                hyper = true;
            }
            double w = GripIK.smooth(sine / Math.sin(BLEND));
            flex = GripIK.add(GripIK.scale(g, w), GripIK.scale(ref, 1 - w));
            flex = GripIK.norm(flex) > 1e-9 ? GripIK.normalize(flex) : g;
        }
        st.flex[side] = flex;
        double[] bend = GripIK.mul3(b.fore, rotY(b.phi + (hyper ? -a : a)));
        double[] ru = GripIK.mul3(frame(u, flex), GripIK.transpose3(frame(b.axisU, b.flexU)));
        double[] rf = GripIK.mul3(ru, bend);
        // onto the wrist: a nearly straight arm's blended plane, and the bind's own slight lean off the hinge's plane
        rf = GripIK.mul3(GripIK.rotationBetween(FpPose.rotate3(rf, b.axisF), f), rf);
        double tau = twist(GripIK.mul3(GripIK.mul3(GripIK.transpose3(rf), rh), GripIK.transpose3(b.hand)), b.axisF);
        if (fresh) {
            tau = GripIK.continuousTwist(tau, st.tau[side], true);
        }
        double rho = share(tau);
        if (fresh && Double.isFinite(st.rho[side])) {
            rho = st.rho[side] + Math.max(-ROLL_SPEED * dt, Math.min(ROLL_SPEED * dt, rho - st.rho[side]));
        }
        if (Math.abs(rho) > 1e-9) {
            rf = GripIK.mul3(rf, GripIK.unitRotation(GripIK.rigid(b.axisF, rho, new double[3])));
        }
        if (!finite(ru) || !finite(rf) || !Double.isFinite(tau) || !Double.isFinite(rho)) { // never write a broken bone
            skipped++;
            if (firstSkip.isEmpty()) {
                firstSkip = "side=" + side + " fresh=" + fresh + " ik=" + ik + " UA=" + java.util.Arrays.toString(UA) + " FA="
                        + java.util.Arrays.toString(FA) + " H=" + java.util.Arrays.toString(H) + " pole=" + java.util.Arrays.toString(pole)
                        + " ru=" + java.util.Arrays.toString(ru) + " rf=" + java.util.Arrays.toString(rf) + " tau=" + tau + " rho=" + rho;
            }
            st.flex[side] = st.in[side] = null;
            st.tau[side] = st.rho[side] = 0;
            return false;
        }
        st.tau[side] = tau;
        st.rho[side] = rho;
        GripIK.setRotation(UA, ru, GripIK.columnScales(UA));
        GripIK.setRotation(FA, rf, GripIK.columnScales(FA));
        maxTauDeg = Math.max(maxTauDeg, Math.abs(Math.toDegrees(tau)));
        maxRhoDeg = Math.max(maxRhoDeg, Math.abs(Math.toDegrees(rho)));
        maxOffHingeDeg = Math.max(maxOffHingeDeg, Math.abs(Math.toDegrees(st.off[side])));
        return true;
    }

    /** QA: " armL=tau,rho,off armR=..." (degrees: the wrist's twist, the forearm's share, the turn the arm came with). */
    static String describe(IsoGameCharacter c) {
        State st = c == null ? null : STATES.get(c);
        if (st == null) {
            return " arm=none";
        }
        return String.format(java.util.Locale.ROOT, " armL=%.1f,%.1f,%.1f armR=%.1f,%.1f,%.1f", Math.toDegrees(st.tau[0]), Math.toDegrees(st.rho[0]),
                Math.toDegrees(st.off[0]), Math.toDegrees(st.tau[1]), Math.toDegrees(st.rho[1]), Math.toDegrees(st.off[1]));
    }

    public static String stats() {
        return "calls=" + calls + " applied=" + applied + " errors=" + errors + " skipped=" + skipped + " nanos=" + nanos + " maxTauDeg=" + Math.round(maxTauDeg)
                + " maxRhoDeg=" + Math.round(maxRhoDeg) + " maxOffHingeDeg=" + Math.round(maxOffHingeDeg) + " enabled=" + enabled
                + (lastError.isEmpty() ? "" : " error=" + lastError);
    }

    public static void reset() {
        calls = applied = errors = nanos = skipped = 0;
        maxTauDeg = maxRhoDeg = maxOffHingeDeg = 0;
        lastError = "";
    }
}
