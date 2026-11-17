package pz.escapefromzomboidfirearms.zb;

import java.util.concurrent.ConcurrentHashMap;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Exposer;
import org.lwjgl.util.vector.Matrix4f;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoGameCharacter;
import zombie.core.skinnedmodel.animation.AnimationMultiTrack;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.animation.AnimationTrack;
import zombie.core.skinnedmodel.animation.AnimatorsBoneTransform;
import zombie.core.skinnedmodel.model.SkinningBone;
import zombie.core.skinnedmodel.model.SkinningData;

/**
 * Runtime grip transfer. A weapon plays the grip clips of the reference class of
 * its support (tools/grip_targets.py, TW_GripIK.lua); right after the engine
 * blended the clips (AnimationPlayer.applyBoneReParenting, before the model and
 * skin transforms) the weapon and both arms are moved onto the weapon's own
 * geometry. tools/grip_targets.transfer is the same computation offline.
 *
 *   right   Bip01_Prop1 keeps its world rotation and moves by Prop1.rot * dR, so
 *           the weapon's right-wrist point sits where the reference one was;
 *   butt    while the reference butt is in the shoulder pocket (R clavicle
 *           space) the weapon slides along its bore until its own butt is there
 *           too; otherwise a longer stock never reaches further back than the
 *           reference stock. The right arm follows (two-bone IK);
 *   left    the reference hand, in weapon space, is carried by the rigid A from
 *           the reference hold to the weapon's support target, weighted by how
 *           close the reference hand is to its hold - within 12..30 cm while a timed
 *           action runs (reload, rack, equip release it), else 40..60 cm (blends
 *           between poses keep it; climbing releases it). A
 *           target out of reach swings the clavicle forward (up to 25 degrees),
 *           then slides back along the bore. Two-bone IK, no stretch; the wrist bends at
 *           most 85 degrees (compact weapons hold the support close to the body). An
 *           optional weapon-space elbow direction turns the elbow plane towards it by
 *           the same weight (PKM/PKP: the forearm passes over the ammo box);
 *   high    optional own high-ready carry (PKM/PKP: held lower, the palm forward on the
 *           gas tube, the elbow hanging): blended in while the clip weapon's bore rises
 *           15..35 degrees above the model horizontal (+Y) and its butt is off the
 *           shoulder - the weapon and the right hand turn about the right wrist to the
 *           own bore, the left target moves to the own hold, the elbow towards a
 *           model-space direction.
 *
 * Parameters (model units, Bip01_Prop1 space) come from Lua as one table:
 * dR(3), A(12, rows of 3x4), hold(3), hasButt, butt(3), refButt(3), pocket(3),
 * hasElbow, elbow(3), hasHigh, highA(12), highElbow(3), highBore(elevation, yaw).
 *
 * Engine convention (projectzomboid.jar): stored matrices are transposed;
 * model = local x parentModel. Here every matrix is the true column-vector
 * transform T with T[r][c] = stored field m{r}{c}.
 */
@Exposer.LuaClass(name = "TWGripIKNative")
public final class GripIK {
    static final double U = 0.578, CM = U / 100;
    /** grip_targets.CONTACT_*: blends between clips drift the reference hand up to ~12 cm; reload/equip leave by 48+. */
    static final double CONTACT_NEAR = 12 * CM, CONTACT_FAR = 30 * CM;
    /** Not acting (no timed action): blends between very different poses drift the hand 20+ cm; only climbing etc. release it. */
    static final double RELEASE_NEAR = 40 * CM, RELEASE_FAR = 60 * CM;
    static final double SHOULDER_NEAR = 4 * CM, SHOULDER_FAR = 12 * CM;
    static final double MAX_BUTT = 15 * CM, MAX_SLIDE = 20 * CM, REACH_MARGIN = 0.5 * CM;
    static final double CLAVICLE_MAX = Math.toRadians(25), MAX_BEND = Math.toRadians(85);
    /**
     * Aiming (butt in the shoulder pocket, bore not in low or high ready) the support shoulder may come
     * forward up to CLAVICLE_MAX_AIM, as a short-armed shooter leans the shoulder into a long weapon
     * (user 2026-10-16; grip_targets.CLAVICLE_MAX_AIM): the hand slides back along the handguard 1-2 cm
     * less on the longest weapons (DVL-10, M870, TX-15, SVD-S). Other stances keep CLAVICLE_MAX.
     */
    static final double CLAVICLE_MAX_AIM = Math.toRadians(35);
    static final double HIGH_FROM = Math.toRadians(15), HIGH_TO = Math.toRadians(35);
    static final long STALE_NS = 500_000_000L;
    /**
     * The held support target, on the weapon, goes through a low-pass of this time constant: the
     * engine blends clips bone by bone, so the blended reference hand drifts 3-5 cm on the blended
     * weapon and snaps back in one frame when a blend ends (QA trace, pistol into aim from low
     * ready: 5-6.5 cm and 19-25 degrees in 16 ms), and the slide back along a long pump follows the
     * arm's reach (M870 into aim: 10 cm in 100 ms). A released hand follows the clip unfiltered.
     */
    static final double HOLD_TAU_S = 0.06;
    /**
     * Turning in place: the engine's 'turning' state plays vanilla turn clips (Bob_IdleTurn90L/R,
     * Bob_IdleAim1Hand_Turn90L/R ...) that no grip class has, and the class nodes require
     * isTurning90 / isTurningAround false - the hands and the weapon jumped onto the turn clip's
     * weapon (arms straight out) and back (user video 2026-10-15). While turning, both arms and the
     * weapon keep the pose they had before relative to the chest (Bip01_Spine1), so they turn with
     * the torso; blended in over TURN_IN_S and out over TURN_OUT_S.
     */
    static final double TURN_IN_S = 0.12, TURN_OUT_S = 0.2;
    public static volatile long turnHolds;
    public static volatile boolean turnHold = true;
    static final long HOLD_RESET_NS = 200_000_000L;
    /**
     * The arms' smoothed states (the elbow out of the torso, the twist relief, ArmNatural's twist branch and forearm share;
     * first and third person) carry over a stalled frame up to ARM_RESET_NS, each step at most ARM_DT_MAX long (user
     * 23.10.2026: the MPX SD's first aim with its scope froze the game 259 ms; past the former 200 ms reset the shooting
     * elbow jumped out of the torso at once and its wrist turned about 100 degrees back over 0.6 s).
     */
    static final long ARM_RESET_NS = 500_000_000L;
    static final double ARM_DT_MAX = 0.05;
    /**
     * Engine blends move the reference hand 3-5 cm (up to 25 degrees) on the clip weapon and back;
     * transferred as they were, the support hand slid along the handguard during every stance
     * change and settled after it ("the left hand wobbles like jelly", user video 2026-10-15; QA
     * trace M4A1 low to high ready: 4 cm out and back over 0.4 s). Within LOCK_NEAR of its hold the
     * reference hand is taken exactly on the hold (params 50-59: flag, hold rotation rows); by
     * LOCK_FAR it follows the clip again (reload, rack and equip leave the hold by 48+ cm).
     */
    static final double LOCK_NEAR = 5 * CM, LOCK_FAR = 15 * CM;
    /**
     * Outside timed actions the hand stays on its hold further (LOCK_IDLE_*): recoil and clips of
     * other systems (MSGL in the launcher checks) moved the reference hand 13 cm, the lock went
     * 1 -> 0.14 -> 1 every two seconds and the arm wobbled with it (QA trace 2026-10-15). The lock
     * weight is smoothed over LOCK_TAU_S, and an action counts until ACTING_HOLD_NS after it ends:
     * between the MSGL's round-by-round loading actions the hand snapped onto the weapon for a frame.
     */
    static final double LOCK_IDLE_NEAR = 20 * CM, LOCK_IDLE_FAR = 35 * CM, LOCK_TAU_S = 0.15;
    static final long ACTING_HOLD_NS = 400_000_000L;
    /**
     * The contact and lock windows go from the action's to the idle one over this time constant, not in the frame
     * the action ends (FB4-A, user report 19.10.2026, item 18): a reference hand 30-60 cm from its hold then
     * snapped onto the weapon in one frame.
     */
    static final double ACT_TAU_S = 0.15;
    /** QA (FB4-A A/B): false restores the former window switch in the frame the action flag changes. */
    public static volatile boolean actRamp = true;
    public static volatile long locks;
    /**
     * Tarkov's round-load clips (bug wave 324, item 1, 24.10.2026, user: "У Винтовки Мосина крайне быстрая скорость
     * перезарядки"; tools/round_reload.py, clips TWRound_*, nodes *_TWRound_*): the support hand loads the tube, gate or
     * chamber on Tarkov's own path on this weapon. The action's contact window (CONTACT_NEAR..FAR of the reference hold)
     * pulled it onto this weapon's support instead - 7 to 27 cm off the loading port on the tube shotguns. While such a
     * clip plays, the hand is released by its blend weight, fading in from ROUND_NEAR to ROUND_FAR of the reference hold
     * (on the hold it keeps the transfer onto this weapon's support); released, it keeps the clip's place relative to
     * this weapon's grip, as the right hand does (the clip weapon moved by the butt slide, not by the seat shift dR);
     * the round it carries (Bip01_Prop2) keeps its clip place on the hand.
     * Any other clip: no change (rf stays 0, every statement of the former path runs as before).
     */
    static final double ROUND_NEAR = 2 * CM, ROUND_FAR = 15 * CM;
    static final String ROUND_CLIP = "TWRound_";
    public static volatile long roundReleases, roundProps;
    /** QA: false keeps the contact window on the round-load clips too (A/B). */
    public static volatile boolean roundRelease = true;
    /**
     * The game's arm has no twist bones: a support hand turned far about its forearm against the
     * bind pose pinched the wrist into a thin neck (bare handguard holds 140-190 degrees, vanilla
     * rifle holds 60-100; reload frames 2026-10-15). The whole arm swivels (relieveTwist) while that
     * turn is beyond WRIST_TWIST_MAX; the hand keeps its world pose. The forearm's own roll is
     * ArmNatural's, the last layer (user report 23.10.2026: rolled here up to 90 degrees against a
     * forearm swung from its clip's, it twisted the elbow's skin).
     */
    static final double WRIST_TWIST_MAX = Math.toRadians(100);
    /**
     * The wrist twist continues the previous frame's branch past the half-turn by up to HALF_TURN_BAND (FB2-D):
     * a bare-handguard hold lies near +-180 degrees from the palm-down bind (female low ready 160-200), where the
     * shortest branch flipped every few frames - the forearm roll swapped sides and the whole-arm relief swung
     * -120..+95 degrees about once a second with the wrist still on the handguard ("the left arm just dangles",
     * user 2026-10-17; QA fb2df, vanilla and DesireNaturalHD female bodies alike). Beyond the band the measured
     * (shortest) branch is taken again, so a -95 degree wrist never becomes +265.
     */
    static final double HALF_TURN_BAND = Math.toRadians(45);
    /** A relief away from the previous frame's costs this per squared radian (one squared radian of twist excess costs 1). */
    static final double RELIEF_HOLD = 0.1;
    /**
     * The first-person layer's elbow clearance (FpPose.arm, FB2-D): the turn of the elbow about the
     * shoulder-to-wrist axis that takes the arm out of the torso (and to its side of the gaze). Where a turn
     * clears, the choice stays the existing one (the side taken first, 15-degree samples up to a half-turn).
     * Looking down 40-85 degrees none cleared: the least deep sample of +-180 changed with the pose's
     * breathing and an elbow put back inside the body jumped to it at once - the support elbow went
     * -180/+140/-120/-166 degrees in consecutive frames, the upper arm across the view (QA fb2d
     * first_idle_down80, both bodies). Now the least deep turn on a SWIVEL_STEP grid within +-SWIVEL_MAX
     * plus SWIVEL_HOLD of its distance from the previous frame's turn (in cm of depth per radian), and
     * inside the body the turn moves at most SWIVEL_INSIDE_SPEED (no jump); a clearing turn is taken at once
     * only within SWIVEL_SNAP of the current one (as before; looking down a clearing turn on the other side,
     * 75 degrees away, was taken in one frame).
     */
    static final double SWIVEL_MAX = Math.toRadians(120), SWIVEL_STEP = Math.toRadians(5), SWIVEL_HOLD = 0.5, CLEAR_SAMPLE = Math.toRadians(15);
    public static final double SWIVEL_INSIDE_SPEED = Math.toRadians(900), SWIVEL_SNAP = Math.toRadians(45);
    /**
     * The clips keep the arms out of the torso for the reference weapon only: a shorter handguard
     * brings the support hand closer, the arm folds more and the elbow sank into the belly (M4A1,
     * MP5 in low ready: 5 cm on the game's body mesh; user frames 2026-10-15). The torso is an
     * ellipse about the spine chain in the spine bones' frame (local Y to the left, Z ahead), per
     * body and segment (pelvis-spine, spine-spine1, spine1-neck) half width, front and back in cm
     * (TORSO_M / TORSO_F, fitted to MaleBody.X / FemaleBody.X); the forearm and the lower upper
     * arm are capsules. The elbow turns about the shoulder-to-wrist
     * axis by the smallest angle that clears (CLEAR_STEP steps up to CLEAR_MAX, then bisected; the
     * side it took is kept), followed fast when more is needed (CLEAR_ATTACK_S), released slowly
     * while some is still needed (CLEAR_RELEASE_S) and sooner when none is (CLEAR_FREE_S); a
     * change of side also goes slowly (the elbow swung 68 degrees across in a stance change),
     * and no faster than CLEAR_SPEED (a goal flicking to the other side for a frame moved it 10
     * degrees at once); the hand stays. Fixed steps sent the goal between 45 and 15
     * degrees as the body breathed and the elbow drifted 28 degrees and back (QA trace M4A1).
     * FB4 (the user's item 17): the fronts hold the 98th percentile of the supported bodies' torso surface
     * (vanilla, DesireNaturalHD, Tomb's Player Body; the female chest was 3 cm beyond the middle one). The elbow
     * clearance (and the twist relief that keeps to it) takes the upper forearm's measured radius (R_FORE_CLEAR:
     * DesireNaturalHD 4.1, Tomb male 5.4 cm), not sinking; the hold's cost and first person keep the contact
     * capsule (R_FORE, CLEAR_TOL) - judged with the measured one the hold search turned the MP18's support hand
     * and its placement slid it 10 cm along the barrel (tools/lowready_clearance.py, the forearm on the meshes).
     */
    static final double[][] TORSO_M = {{16, 10.5, 13}, {16, 10.5, 13}, {16, 11.3, 13}}, TORSO_F = {{14, 5.6, 13}, {12, 8.8, 11}, {13, 11.1, 8}};
    static final double R_UPPER = 4.5 * CM, R_FORE = 3.5 * CM, R_WRIST = 2.5 * CM, CLEAR_TOL_UPPER = 3 * CM, CLEAR_TOL = 1 * CM;
    static final double R_FORE_CLEAR = 4.5 * CM, CLEAR_TOL_CLEAR = 0;
    static final double CLEAR_STEP = Math.toRadians(15), CLEAR_MAX = Math.toRadians(60);
    static final double CLEAR_ATTACK_S = 0.08, CLEAR_RELEASE_S = 1.0, CLEAR_FREE_S = 0.25, CLEAR_SPEED = Math.toRadians(300);
    public static volatile boolean armClear = true;
    public static volatile long elbowTurns;
    /**
     * FB7 item 2 (user's report of 22.10.2026: the left hand "teleports" while running, most in patrol; QA run-fp2: a GLOCK17
     * in both hands, isometric patrol and low run, the support elbow 5-7.5 cm a 1/60 s beyond the clip's). A free arm - a
     * pistol in both hands (hg): no pole of the weapon (q 28), its own high-ready hold (q 32) or a grip hold - took the clip
     * elbow across the shoulder-to-goal axis as the solve's elbow direction. The run's blend carries the clip's support hand 6-13
     * cm off the grip, its elbow then passes within 0.4-1.5 cm of that axis and the direction turned over: 34 and 77 degrees
     * in two frames, the elbow 2.6 + 6.1 cm (and that side put the arm into the torso, the clearance turning it out: 1.7-1.8
     * cm a step in the walk). The clip arm's own swivel - its elbow across its own shoulder-to-wrist axis, 3-15 cm off it
     * there - carried onto the goal axis by the smallest turn (carriedSwivel; the same pole where the goal is the clip hand)
     * is the free arm's pole. Near full reach the solve is singular (the elbow's distance from the axis leaves 0 with an
     * infinite slope: the run's out-of-reach support hand straightened the arm at 179.9 degrees, then bent it 4 cm in 25
     * ms): its last FREE_SOFT_REACH is approached exponentially (soft IK, as FpPose.softGoal) and the clavicle swings from
     * FREE_CLAVICLE_SOFT before the reach, taking the excess over smoothly (as FpPose.clavicleSoft) - both by the contact
     * weight, so a released arm keeps its clip pose. Rifles keep their path: a grip hold's followed pole and elbow limit, and
     * the fallback on the weapon met exactly (tools/check_grip_targets: within 0.05 cm; the soft reach leaves 0.2-0.3 cm).
     * grip_targets.FREE_* / carried_swivel / soft_reach mirror them.
     */
    static final double FREE_SOFT_REACH = 1.5 * CM, FREE_CLAVICLE_SOFT = 2 * CM;
    public static volatile boolean freeArm = true;
    public static volatile boolean twistCap = true;
    /**
     * Bare handguard (param 64, FB2-D; user 2026-10-17: "the fingers do not close round the handguard"):
     * the support hand's two finger bones (Bip01_L_Finger0 thumb, Finger1 the four fingers, one rigid bone)
     * start from the rest of the bare reference class relative to the hand (FpPoseData family, the AK-74N's
     * first-person idle; FB2-E: tools/fp_pose_runtime lays the measured finger skin along Tarkov's fingers,
     * tools/finger_wrap.rest_rotation - FB2-D's bone axis on the chord of the bent finger logged 0 degrees
     * from Tarkov while the skin stood off the part, user 2026-10-18), weighted by the contact; translation
     * and scale stay the clip's. QA: fingerFit off for comparisons.
     *
     * FB2-E (user 2026-10-18: "the fingers do not close round the weapon at all", DesireNaturalHD / Tomb's and
     * the vanilla bodies): with the handguard's cross-section at hand (TW/GripTargets 'wrap', stations along the
     * bore round Tarkov's hand on it) each finger bone then turns from its rest about two hand axes -
     * Finger1 flexes about the knuckle axis (hand Y, towards the palm +Z) and spreads about the palm normal,
     * Finger0 turns about the hand's long axis and the palm normal - until the fingertips of the measured skin
     * touch the section without the skin sinking more than WRAP_SINK_CM into it, for the vanilla male and female
     * bodies and DesireNaturalHD alike (WRAP_SKIN_*: per finger lane the fingertip and palm-side samples of each
     * body's mesh in bone space, cm, tools/finger_wrap; the game does not say which mesh it draws, so the cost
     * is their mean - the vanilla bodies count as much as the detailed one, coordinator 2026-10-18). A coarse grid,
     * then 1-degree steps; the previous angles are held (WRAP_HOLD), followed at most WRAP_SPEED and kept while the
     * hand stays put on the weapon. The spread is kept within +-15 degrees (a +-25 bound had a second, poorer minimum).
     * tools/finger_wrap.wrap_fit is the same computation offline (first frame).
     */
    public static volatile boolean fingerFit = true;
    public static volatile long fingerFits, wrapFits;

    /** QA (TWGripIKNative): the finger grip on or off for before/after frames. */
    public static void fingerFitOn(boolean on) {
        fingerFit = on;
    }

    /** QA (TWGripIKNative, FB2-N): the hold chosen with the arm on or off (off: FB2-E's hold fixed on the weapon) for before/after frames. */
    public static void holdChosenOn(boolean on) {
        holdOn = on;
    }

    /**
     * Player report of 03.11.2026 (five videos, console (22).txt): ~9 FPS while firing a weapon with a vertical foregrip (the
     * m4_block_2_customized preset with its Stubby BGV-MK46K, MP5 SilentOps; taking the grip off ended it). Under recoil the
     * arm moves on the weapon, so a refit came every HOLD_FIT_NS, and on these grips holdSearch's recovery placed every
     * ranked cell of the vertical grid (up to ~1300, both placement modes) - 60-180 ms, GripIK.polySd 61-78 % of the long
     * frames (QA perfpreset, the stack sampler) - because its placement memo was lost: it lived on the request's Hold, and
     * each patrol in / out swap of the Lua target table makes a new request with a new Hold; the finger offset t1 read from
     * the bones is part of its key; the grid of both modes outgrew HOLD_PLACEMENT_CACHE_LIMIT. A refit longer than
     * HOLD_FIT_NS let the very next frame refit again: a steady ~10 FPS. Now (runtime only; what holdSearch chooses is
     * unchanged): t1 is rounded to HOLD_T1_QUANT, the memo is shared by every request of the same assembly geometry
     * (HOLD_PLACEMENT_OWNERS), it holds the whole grid of both modes (holdGridKeys), and a refit waits HOLD_FIT_DUTY times the last fit's
     * own length once a fit took longer than HOLD_FIT_DUTY_MIN_NS. QA: holdFixOn(false) is the behaviour before.
     */
    public static volatile boolean holdFix = true;
    public static volatile long holdSearches, holdRecoveries, holdPlacements, holdMemoNew, holdMemoShared, holdDutyWaits, holdFitMaxNs;

    /** QA (TWGripIKNative): the hold fit's memo sharing, rounded finger offset and duty limit on (default) or off (before). */
    public static void holdFixOn(boolean on) {
        holdFix = on;
    }

    /** holdFix: the finger offset read from the bones rounded to HOLD_T1_QUANT (in place; as read without holdFix). */
    static double[] holdFinger(double[] t1) {
        if (holdFix) {
            for (int i = 0; i < 3; i++) {
                t1[i] = Math.rint(t1[i] / HOLD_T1_QUANT) * HOLD_T1_QUANT;
            }
        }
        return t1;
    }

    /** holdFix: a due refit waits while less than HOLD_FIT_DUTY times the last fit's length has passed since it ended, once
     *  that fit took longer than HOLD_FIT_DUTY_MIN_NS (now and doneAt: System.nanoTime). */
    static boolean holdDutyHolds(long now, long doneAt, long costNs) {
        return holdFix && costNs > HOLD_FIT_DUTY_MIN_NS && now - doneAt < HOLD_FIT_DUTY * costNs;
    }

    /** tools/finger_wrap (assets/finger_wrap.json): [body: vanilla M, vanilla F, DesireNaturalHD][lane][point] = left-hand bone space x, y, z (cm), fingertip? */
    static final double[][][][] WRAP_SKIN_FINGERS = {{{{5.729, -2.527, 1.877, 1}, {5.781, -3.486, 2.053, 1}, {5.881, -2.505, 1.62, 1}, {6.304, -2.776, 0.996, 1}, {6.32, -3.528, -0.436, 1}, {6.426, -3.445, 0.978, 1}, {2.14, -3.591, -0.834, 0}, {4.114, -2.765, 2.55, 0}, {4.684, -3.867, 1.624, 0}}, {{6.058, 0.06, 0.622, 1}, {5.451, -1.221, 1.98, 0}, {5.451, -1.221, 1.98, 0}, {5.451, -1.221, 1.98, 0}}, {{5.133, 0.909, 1.921, 1}, {5.567, 1.519, 1.028, 1}, {5.953, 0.805, 0.587, 1}, {2.158, 1.337, 3.396, 0}, {3.987, 1.517, 0.095, 0}, {4.382, 0.434, 3.292, 0}}, {{5.152, 2.537, 1.433, 1}, {5.372, 2.73, 1.012, 1}, {1.945, 4.047, 3.617, 0}, {3.359, 2.966, 4.117, 0}, {3.676, 3.951, 3.336, 0}}},
            {{{6.018, -3.337, -0.511, 1}, {6.243, -2.261, 0.495, 1}, {6.292, -2.254, 0.366, 1}, {6.406, -3.159, 0.613, 1}, {6.478, -2.546, 0.062, 1}, {6.62, -3.181, 0.073, 1}, {2.978, -3.659, 0.958, 0}, {4.252, -3.4, -0.278, 0}, {5.049, -2.403, 1.088, 0}}, {{5.951, -1.016, 0.506, 1}, {5.043, 0.052, -0.301, 0}}, {{5.234, 1.577, 0.802, 1}, {5.287, 2.003, 0.437, 1}, {5.335, 0.625, 1.111, 1}, {5.519, 1.002, 0.421, 1}, {5.572, 1.538, -0.006, 1}, {5.812, 0.837, -0.207, 1}, {3.364, 1.579, 1.571, 0}, {4.081, 1.579, -0.055, 0}}, {{4.779, 3.775, 0.702, 1}, {5.275, 2.529, 0.174, 1}, {5.32, 2.691, -0.026, 1}, {3.947, 3.531, 0.107, 0}, {4.547, 3.076, 1.468, 0}}},
            {{{3.128, -2.343, 0.368, 1}, {3.322, -3.187, 0.006, 1}, {3.535, -3.371, 0.807, 1}, {3.71, -3.281, 0.852, 1}, {3.857, -3.304, 0.628, 1}, {4.023, -3.034, 0.769, 1}, {1.32, -2.774, 0.617, 0}, {2.21, -2.924, 0.874, 0}, {2.932, -2.89, 1.032, 0}}, {{4.079, -0.945, 1.155, 1}, {4.288, -0.646, 1.244, 1}, {4.524, -0.38, 0.274, 1}, {4.741, -1.142, 0.865, 1}, {4.948, -0.488, 0.497, 1}, {5.168, -0.674, 0.847, 1}, {1.897, -0.577, 0.695, 0}, {2.987, -0.698, 0.936, 0}, {4.072, -0.638, 1.229, 0}}, {{3.34, 1.971, 1.545, 1}, {3.534, 1.034, 1.101, 1}, {3.713, 1.432, 1.156, 1}, {3.877, 1.52, 2.093, 1}, {4.051, 1.355, 2.059, 1}, {4.238, 1.361, 1.742, 1}, {1.513, 1.567, 1.319, 0}, {2.426, 1.493, 1.631, 0}, {3.312, 1.406, 2.018, 0}}, {{2.202, 2.702, 3.012, 1}, {2.306, 2.595, 2.443, 1}, {2.435, 3.4, 2.556, 1}, {2.571, 3.352, 2.767, 1}, {2.702, 3.13, 2.626, 1}, {2.843, 3.025, 2.928, 1}, {0.941, 3.268, 2.713, 0}, {1.568, 3.069, 2.969, 0}, {2.187, 3.008, 3.191, 0}}}};
    static final double[][][][] WRAP_SKIN_THUMB = {{{{4.223, 0.546, -1.317, 1}, {4.904, -1.775, -0.639, 1}, {5.048, -0.842, -1.451, 1}, {5.238, -1.679, -0.3, 1}, {5.423, -0.179, -0.064, 1}, {5.687, 0.034, 0.43, 1}, {1.101, 0.101, -0.008, 0}, {2.494, -2.474, 1.662, 0}, {2.809, -3.051, 1.836, 0}}},
            {{{5.154, 0.832, -0.005, 1}, {5.648, -1.019, -0.328, 1}, {5.801, -1.037, 0.045, 1}, {5.922, -0.539, 0.165, 1}, {6.06, 0.779, 0.753, 1}, {6.304, 0.425, 1.441, 1}, {2.567, -1.462, 1.388, 0}, {3.025, -2.115, 1.482, 0}, {4.916, -0.661, 1.497, 0}}},
            {{{3.184, -0.837, 0.827, 1}, {3.467, -0.58, 0.45, 1}, {3.762, -0.625, -0.049, 1}, {4.006, -0.689, 0.058, 1}, {4.243, -0.865, 0.487, 1}, {4.465, -1.191, 0.221, 1}, {-0.657, -2.333, 1.629, 0}, {0.689, -2.31, 1.343, 0}, {1.915, -1.881, 1.255, 0}}}};
    /** Finger1: flexion about -hand Y (towards the palm) WRAP_F1_A degrees, spread about hand Z +-WRAP_F1_B; Finger0 likewise. */
    static final double[] WRAP_F1_AXIS1 = {0, -1, 0}, WRAP_F0_AXIS1 = {1, 0, 0}, WRAP_AXIS2 = {0, 0, 1};
    /** FB2-L (user: "the fingers look broken and clip through their own hands; an imperfect wrap is fine if they look
     *  natural"): round the natural rest (finger_wrap.NATURAL - bend and spread of the bind pose, no twist) the rigid
     *  fingers bend at most 20 degrees further; before -30..110 broke them at the knuckles. finger_wrap.WRAP_F1/F0.
     *  FB2-N (user 2026-10-18: "a little clipping through the model beats an unrealistic or unnatural hold"): with the hand
     *  now on the surface (holdPlace) the four-finger block bends up to 70 degrees past its rest about the knuckles only (still
     *  no twist) and fans 20 degrees about the palm normal (a diagonal hand's fingers turn across the handguard; at 50 / 8 the
     *  fingertips stayed 1-5 cm off it in game, QA fb2nf), the thumb 30; the skin may sink WRAP_SINK_CM before it costs, at a
     *  third of the old weight - the fingers close round the far side instead of standing off it. */
    static final double[] WRAP_F1_A = {-15, 70}, WRAP_F0_A = {-20, 30};
    static final double WRAP_F1_B = 20, WRAP_F0_B = 15;
    /** WRAP_HOLD only breaks ties towards the previous angles (0.05 kept a minimum found mid-transition after the hand was back, QA run H:
     *  M4A1 iso aim, fingertips 1.9-3.7 cm off); continuity is WRAP_SPEED's. */
    static final double WRAP_COARSE = 10, WRAP_SINK_CM = 0.8, WRAP_PEN = 3, WRAP_REG = 0.02, WRAP_HOLD = 0.002;
    static final double[] WRAP_LEVELS = {5, 1};
    /** Kept angles while the hand moves on the weapon less than WRAP_STILL_ROT (rotation entries) and WRAP_STILL_POS (model units, 0.1 cm). */
    static final double WRAP_SPEED = 360, WRAP_STILL_ROT = 2e-3, WRAP_STILL_POS = 0.1 * CM;
    static final int WRAP_STATE = 40;
    static final long WRAP_FIT_NS = 33_000_000L;

    /**
     * Pistol in both hands (user 2026-10-18: the support fingers stood straight up beside the Glock's grip and the shooting
     * fingers only partly closed round it; "an imperfect wrap is fine if the fingers look natural"; the user's reference
     * photos: thumbs forward along the frame, the support fingers over the shooting ones): tools/pistol_grip.py fits, per
     * handgun class and body sex, both hands' finger bones round the grip of the class's weapon on the measured skin of every
     * body (vanilla male / female, DesireNaturalHD) - Finger1 only bends towards the palm and spreads, never twists about its
     * length, the thumb turns about the hand's long axis and the palm normal (FB2-L's natural rule with a fist's reach).
     * The PZ thumb turns about a fixed pivot in the palm, so for thumbs forward along the frame each hand also shifts on the
     * grip (at most 3 cm, its turn kept) until the pivot comes to Tarkov's thumb knuckle - a little clipping is accepted
     * (user: "better than an unrealistic or unnatural grip"). TW/PistolGripData -> the handgun params' named fields:
     * 'fingers' and 'shift' here (isometric view, VP third person, on the handgun clips' hands: the weapon moves the other
     * way under the shooting hand, as the handgun seat dR does, and the support hand's hold moves by its shift), 'fpFingers'
     * / 'fpHands' in the first-person layer (FpPose). Rotations relative to the hand, rows: left Finger0, left Finger1, right
     * Finger0, right Finger1. Weights: the support hand by its contact while locked on its hold (grip x lock);
     * the shooting hand while no timed action of a local player runs (reload, rack, inspect, fire mode - FB2-A: released to
     * the clip's fingers over HG_TAU_S; a remote player's actions are unknown, TW_GripIK.lua, so it keeps the grip).
     */
    static final double HG_TAU_S = 0.15;
    public static volatile long hgFits;
    /** QA (TWGripIKNative): the pistol grip (TW/PistolGripData - fingers and hand shifts, every view) on or off, for
     *  before/after frames in one world; off is exactly the earlier hold (the clips' / the natural rests' fingers). */
    public static volatile boolean handgunGrip = true;

    public static void handgunGripOn(boolean on) {
        handgunGrip = on;
    }

    /** The handgun params' 'fingers' field (36 numbers: four row-major rotations) or 'shift' (6: two Prop-space shifts), or null. */
    static double[][] handgunFingers(Object o) {
        return handgunRows(o, 4, 9);
    }

    static double[][] handgunRows(Object o, int rows, int width) {
        if (!(o instanceof KahluaTable t) || t.len() != rows * width) {
            return null;
        }
        double[][] out = new double[rows][width];
        for (int i = 0; i < rows * width; i++) {
            if (!(t.rawget(i + 1) instanceof Double d) || !Double.isFinite(d)) {
                return null;
            }
            out[i / width][i % width] = d;
        }
        return out;
    }

    /** The shooting hand's grip weight: 0 while a local player's timed action runs, else 1, followed over HG_TAU_S. */
    static double handgunRight(IsoGameCharacter c, Request r, long t0) {
        boolean local = c instanceof zombie.characters.IsoPlayer p && p.isLocalPlayer();
        double target = local && r.acting ? 0 : 1;
        if (r.hgRightAt != 0 && t0 - r.hgRightAt < HOLD_RESET_NS) {
            target = r.hgRight + (target - r.hgRight) * (1 - Math.exp(-(t0 - r.hgRightAt) / 1e9 / HG_TAU_S));
        }
        r.hgRight = target;
        r.hgRightAt = t0;
        return target;
    }

    /** Both hands' Finger0 / Finger1 turned towards the handgun grip (rot: hg rows) by w {left, right}; translation and scale stay the clip's. */
    static void handgunFingers(SkinningData sd, AnimatorsBoneTransform[] bt, int lh, int rh, double[][] rot, double[] w, Matrix4f tmp) {
        String[][] names = {{"Bip01_L_Finger0", "Bip01_L_Finger1"}, {"Bip01_R_Finger0", "Bip01_R_Finger1"}};
        int[] hands = {lh, rh};
        for (int side = 0; side < 2; side++) {
            if (w[side] <= 1e-4) {
                continue;
            }
            for (int k = 0; k < 2; k++) {
                int f = index(sd, names[side][k]);
                if (f < 0 || f >= bt.length || parent(sd, f) != hands[side]) {
                    continue;
                }
                bt[f].getMatrix(tmp);
                double[] local = read(tmp, new double[16]);
                setRotation(local, slerp(unitRotation(local), rot[side * 2 + k], w[side]), columnScales(local));
                bt[f].set(write(local, tmp));
            }
            hgFits++;
        }
    }

    /** FB2-E: a support's cross-section stations, Prop1 space, model units: y along the bore, counter-clockwise (x, z) polygons. */
    static final class Wrap {
        final double[] y;
        final double[][] poly;
        /** W10 armsrest2: true when a station thinner than 2*HOLD_PALM_SINK made monotone reseat diverge past HOLD_PALM_RANGE. */
        boolean unimodal;

        /** holdFix: each station's bounding box {x0, x1, z0, z1}. */
        final double[][] box;

        Wrap(double[] y, double[][] poly) {
            this.y = y;
            this.poly = poly;
            box = new double[poly.length][];
            for (int i = 0; i < poly.length; i++) {
                double[] b = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
                for (int k = 0; k + 1 < poly[i].length; k += 2) {
                    b[0] = Math.min(b[0], poly[i][k]);
                    b[1] = Math.max(b[1], poly[i][k]);
                    b[2] = Math.min(b[2], poly[i][k + 1]);
                    b[3] = Math.max(b[3], poly[i][k + 1]);
                }
                box[i] = b;
            }
        }

        /** False when wrapSd(x, y, z) cannot be below -sink: the point lies within sink of a side of the bounding box of each
         *  station it is interpolated from, and a convex section's inscribed circle cannot cross that side's supporting line
         *  (holdReseat's bisection only asks whether some palm point lies deeper than its sink). */
        boolean mayLieDeeper(double x, double y, double z, double sink) {
            if (y <= this.y[0]) {
                return within(0, x, z, sink);
            }
            for (int i = 1; i < this.y.length; i++) {
                if (y <= this.y[i]) {
                    return within(i - 1, x, z, sink) || within(i, x, z, sink);
                }
            }
            return within(this.y.length - 1, x, z, sink);
        }

        private boolean within(int i, double x, double z, double sink) {
            double[] b = box[i];
            return x > b[0] + sink && x < b[1] - sink && z > b[2] + sink && z < b[3] - sink;
        }
    }

    /** The 'wrap' field of TW/GripTargets: y, n, n (x, z) pairs per station; null when absent or malformed. */
    static Wrap wrap(Object o) {
        if (!(o instanceof KahluaTable t)) {
            return null;
        }
        int n = t.len();
        double[] f = new double[n];
        for (int i = 0; i < n; i++) {
            if (!(t.rawget(i + 1) instanceof Double d)) {
                return null;
            }
            f[i] = d;
        }
        return wrap(f);
    }

    static Wrap wrap(double[] f) {
        java.util.List<double[]> polys = new java.util.ArrayList<>();
        java.util.List<Double> ys = new java.util.ArrayList<>();
        int i = 0;
        while (i + 1 < f.length) {
            int m = (int) f[i + 1];
            if (m < 3 || i + 2 + 2 * m > f.length || (!ys.isEmpty() && f[i] < ys.get(ys.size() - 1))) {
                return null;
            }
            ys.add(f[i]);
            polys.add(java.util.Arrays.copyOfRange(f, i + 2, i + 2 + 2 * m));
            i += 2 + 2 * m;
        }
        if (ys.isEmpty() || i != f.length) {
            return null;
        }
        double[] y = new double[ys.size()];
        for (int k = 0; k < y.length; k++) {
            y[k] = ys.get(k);
        }
        return new Wrap(y, polys.toArray(new double[0][]));
    }

    /** Signed distance of (x, z) to a counter-clockwise convex polygon (x, z pairs); negative inside. finger_wrap.poly_sd. */
    static double polySd(double x, double z, double[] p) {
        int n = p.length / 2;
        double best = Double.POSITIVE_INFINITY;
        boolean inside = true;
        for (int i = 0; i < n; i++) {
            double ax = p[2 * i], az = p[2 * i + 1], bx = p[(2 * i + 2) % p.length], bz = p[(2 * i + 3) % p.length];
            double ex = bx - ax, ez = bz - az, ll = ex * ex + ez * ez;
            double t = ll <= 0 ? 0 : Math.min(1, Math.max(0, ((x - ax) * ex + (z - az) * ez) / ll));
            double dx = x - ax - t * ex, dz = z - az - t * ez;
            best = Math.min(best, dx * dx + dz * dz);
            if (ex * (z - az) - ez * (x - ax) < 0) {
                inside = false;
            }
        }
        return inside ? -Math.sqrt(best) : Math.sqrt(best);
    }

    /** The section at a Prop-space point: the stations bracketing it along the bore, interpolated. finger_wrap.wrap_sd. */
    static double wrapSd(Wrap w, double x, double y, double z) {
        if (y <= w.y[0]) {
            return polySd(x, z, w.poly[0]);
        }
        for (int i = 1; i < w.y.length; i++) {
            if (y <= w.y[i]) {
                double t = w.y[i] > w.y[i - 1] ? (y - w.y[i - 1]) / (w.y[i] - w.y[i - 1]) : 0;
                return (1 - t) * polySd(x, z, w.poly[i - 1]) + t * polySd(x, z, w.poly[i]);
            }
        }
        return polySd(x, z, w.poly[w.y.length - 1]);
    }

    /** A finger bone's rotation relative to the hand: rest turned b degrees about axis2, then a about axis1 (hand axes). */
    static double[] wrapRotation(double[] rest, double[] axis1, double a, double b) {
        double[] r1 = unitRotation(rigid(axis1, Math.toRadians(a), new double[3])), r2 = unitRotation(rigid(WRAP_AXIS2, Math.toRadians(b), new double[3]));
        return mul3(mul3(r1, r2), rest);
    }

    /**
     * finger_wrap.wrap_cost: for every body's skin sample (equal weights) the closest fingertip point of each lane squared
     * off the section plus WRAP_PEN times every point deeper than WRAP_SINK_CM, in cm. hand: the hand in Prop space (16);
     * t: the bone's translation in the hand (model units); mirror: the right hand (Y negated).
     */
    static double wrapCost(double[] hand, double[] t, double[] r, double[][][][] skin, boolean mirror, Wrap w) {
        return wrapCost(hand, t, r, skin, mirror, w, 0, 0, false, Double.POSITIVE_INFINITY);
    }

    /** wrapCost, or +infinity as soon as the cost with the terms reg (+ hold when held) cannot get below limit: every term of
     *  the sum is >= 0, so its partial sums only grow and the strict comparison of wrapTry is decided the same (performance
     *  pass 23.10.2026: a refit costed every grid cell over every skin point, 1-2 ms every 33 ms while the hand moved). */
    static double wrapCost(double[] hand, double[] t, double[] r, double[][][][] skin, boolean mirror, Wrap w,
                           double reg, double hold, boolean held, double limit) {
        double total = 0;
        for (double[][][] lanes : skin) {
            double tips = 0, pen = 0;
            int n = 0;
            for (double[][] lane : lanes) {
                double best = Double.POSITIVE_INFINITY;
                for (double[] p : lane) {
                    double px = p[0] * CM, py = (mirror ? -p[1] : p[1]) * CM, pz = p[2] * CM;
                    double hx = t[0] + r[0] * px + r[1] * py + r[2] * pz, hy = t[1] + r[3] * px + r[4] * py + r[5] * pz, hz = t[2] + r[6] * px + r[7] * py + r[8] * pz;
                    double d = wrapSd(w, hand[0] * hx + hand[1] * hy + hand[2] * hz + hand[3], hand[4] * hx + hand[5] * hy + hand[6] * hz + hand[7],
                            hand[8] * hx + hand[9] * hy + hand[10] * hz + hand[11]) / CM;
                    if (p[3] > .5) {
                        best = Math.min(best, d);
                    }
                    double e = Math.max(0, -d - WRAP_SINK_CM);
                    pen += e * e;
                    n++;
                }
                tips += best * best;
                double bound = (total + tips / lanes.length) / skin.length + reg;
                if ((held ? bound + hold : bound) >= limit) {
                    return Double.POSITIVE_INFINITY;
                }
            }
            total += tips / lanes.length + WRAP_PEN * pen / n;
        }
        return total / skin.length;
    }

    /**
     * finger_wrap.wrap_fit: the angles {a, b} (degrees) about axis1 (a in aRange) and the palm normal (|b| <= bMax) that put
     * the skin on the section: a 10-degree grid, then 5- and 1-degree grids round its best; prev (last frame's angles) is held by
     * WRAP_HOLD, null for none.
     */
    static double[] wrapFit(double[] hand, double[] t, double[] rest, double[][][][] skin, boolean mirror, Wrap w, double[] axis1,
                            double[] aRange, double bMax, double[] prev) {
        double[] best = {Double.POSITIVE_INFINITY, 0, 0};
        for (double a = aRange[0]; a <= aRange[1] + 1e-9; a += WRAP_COARSE) {
            for (double b = -bMax; b <= bMax + 1e-9; b += WRAP_COARSE) {
                wrapTry(best, hand, t, rest, skin, mirror, w, axis1, a, b, prev);
            }
        }
        double span = WRAP_COARSE;
        for (double step : WRAP_LEVELS) { // finer grids round the best: 5 degrees, then 1
            double a0 = best[1], b0 = best[2];
            for (double a = a0 - span + step; a < a0 + span - 1e-9; a += step) {
                for (double b = b0 - span + step; b < b0 + span - 1e-9; b += step) {
                    if (a >= aRange[0] && a <= aRange[1] && Math.abs(b) <= bMax) {
                        wrapTry(best, hand, t, rest, skin, mirror, w, axis1, a, b, prev);
                    }
                }
            }
            span = step;
        }
        return new double[] {best[1], best[2]};
    }

    static void wrapTry(double[] best, double[] hand, double[] t, double[] rest, double[][][][] skin, boolean mirror, Wrap w, double[] axis1,
                        double a, double b, double[] prev) {
        double reg = WRAP_REG * (a * a + b * b) / 100;
        double hold = prev == null ? 0 : WRAP_HOLD * ((a - prev[0]) * (a - prev[0]) + (b - prev[1]) * (b - prev[1])) / 100;
        double c = wrapCost(hand, t, wrapRotation(rest, axis1, a, b), skin, mirror, w, reg, hold, prev != null,
                PerfLegacy.on ? Double.POSITIVE_INFINITY : best[0]) + reg;
        if (prev != null) {
            c += hold;
        }
        if (c < best[0]) {
            best[0] = c;
            best[1] = a;
            best[2] = b;
        }
    }

    /**
     * The fitted rotations (row-major 3x3 relative to the hand) of a hand's Finger0 and Finger1 on a section. hand: the hand
     * in Prop space; t: the bones' translations in the hand; rest: their rests; state {a0, b0, a1, b1, nanos, hand at the last
     * fit (12), fitted a0, b0, a1, b1, fit nanos, rests at the last fit (2 x 9)} of the caller (WRAP_STATE long): the previous frame's angles held, followed at
     * most WRAP_SPEED towards the last fit (refitted at most every WRAP_FIT_NS), kept while the
     * hand stays within WRAP_STILL_* of where they were fitted, else searched over the whole grid with them held (a search
     * only round them stayed in a poorer minimum after a transition, QA run F: M4A1 iso aim, fingertips 1.7-3.4 cm off).
     */
    static double[][] wrapFingers(double[] hand, double[][] t, double[][] rest, boolean mirror, Wrap w, double[] state, long t0) {
        boolean fresh = state[4] != 0 && t0 - (long) state[4] < HOLD_RESET_NS;
        double step = fresh ? WRAP_SPEED * Math.min(.1, (t0 - (long) state[4]) / 1e9) : Double.POSITIVE_INFINITY;
        boolean still = fresh;
        for (int i = 0; i < 12; i++) {
            still &= Math.abs(hand[i] - state[5 + i]) <= (i % 4 == 3 ? WRAP_STILL_POS : WRAP_STILL_ROT);
        }
        // Patrol can blend finger rests while the surface-selected palm stays still. The fitted angles
        // belong to both inputs; retain their speed limit and refit cadence when either input changes.
        for (int k = 0; k < 2; k++) {
            for (int i = 0; i < 9; i++) {
                still &= Math.abs(rest[k][i] - state[22 + k * 9 + i]) <= WRAP_STILL_ROT;
            }
        }
        // a moving hand is refitted at most every WRAP_FIT_NS (a fit costs ~1-2 ms); in between the angles go on towards the targets
        boolean fit = !still && (!fresh || t0 - (long) state[21] >= WRAP_FIT_NS);
        double[][] out = new double[2][];
        for (int k = 0; k < 2; k++) {
            double[] axis1 = k == 0 ? WRAP_F0_AXIS1 : WRAP_F1_AXIS1;
            if (fit) {
                double[] target = wrapFit(hand, t[k], rest[k], k == 0 ? WRAP_SKIN_THUMB : WRAP_SKIN_FINGERS, mirror, w, axis1,
                        k == 0 ? WRAP_F0_A : WRAP_F1_A, k == 0 ? WRAP_F0_B : WRAP_F1_B, fresh ? new double[] {state[2 * k], state[2 * k + 1]} : null);
                state[17 + 2 * k] = target[0];
                state[18 + 2 * k] = target[1];
            }
            for (int j = 0; j < 2; j++) {
                double goal = state[17 + 2 * k + j];
                state[2 * k + j] = fresh ? state[2 * k + j] + Math.max(-step, Math.min(step, goal - state[2 * k + j])) : goal;
            }
            out[k] = wrapRotation(rest[k], axis1, state[2 * k], state[2 * k + 1]);
        }
        state[4] = t0;
        if (fit) {
            System.arraycopy(hand, 0, state, 5, 12);
            for (int k = 0; k < 2; k++) System.arraycopy(rest[k], 0, state, 22 + k * 9, 9);
            state[21] = t0;
            wrapFits++;
        }
        return out;
    }

    /**
     * FB2-E QA: the measured skin's closest fingertip to the section per Finger1 lane, then the thumb (cm, the mean of the
     * bodies; + off, - in) and, after "wrapPenCm", the deepest sample of any body in it. P: the weapon, F0/F1: the finger bones.
     */
    static String wrapReport(Wrap w, double[] P, double[] F0, double[] F1) {
        double[] ip = inverse(P);
        StringBuilder s = new StringBuilder();
        double pen = 0;
        double[][] bones = {F1, F0};
        double[][][][][] skins = {WRAP_SKIN_FINGERS, WRAP_SKIN_THUMB};
        for (int b = 0; b < 2; b++) {
            double[] m = mulNew(ip, bones[b]);
            int lanes = skins[b][0].length;
            for (int l = 0; l < lanes; l++) {
                double sum = 0;
                for (double[][][] body : skins[b]) {
                    double best = Double.POSITIVE_INFINITY;
                    for (double[] p : body[l]) {
                        double[] q = point(m, p[0] * CM, p[1] * CM, p[2] * CM);
                        double d = wrapSd(w, q[0], q[1], q[2]) / CM;
                        if (p[3] > .5) {
                            best = Math.min(best, d);
                        }
                        pen = Math.max(pen, -d);
                    }
                    sum += best;
                }
                s.append(s.length() == 0 ? "" : "/").append(String.format(java.util.Locale.ROOT, "%.2f", sum / skins[b].length));
            }
        }
        return s + String.format(java.util.Locale.ROOT, " wrapPenCm=%.2f", pen);
    }

    static void fitFingers(SkinningData sd, AnimatorsBoneTransform[] bt, int lh, boolean female, double k, Matrix4f tmp, Request r,
                           double[] hand, double[] prop, long t0) {
        FpPoseData data = FpPoseData.get();
        FpPoseData.Family fam = data == null ? null : data.byClass.get(BARE_CLASS);
        boolean own = r.hold != null && r.hold.rest != null; // FB2-N: a vertical grip's rests (Tarkov's pistol-grip fingers, mirrored)
        if (!own && (fam == null || !fam.hasIdle())) {
            return;
        }
        String[] names = {"Bip01_L_Finger0", "Bip01_L_Finger1"};
        int[] f = new int[2];
        double[][] locals = new double[2][], want = new double[2][], t = new double[2][];
        for (int i = 0; i < 2; i++) {
            f[i] = index(sd, names[i]);
            if (f[i] < 0 || f[i] >= bt.length || parent(sd, f[i]) != lh) {
                f[i] = -1;
                continue;
            }
            bt[f[i]].getMatrix(tmp);
            locals[i] = read(tmp, new double[16]);
            want[i] = own ? r.hold.rest[i].clone() : unitRotation(fam.fingers[female ? 1 : 0][0][i]);
            t[i] = pos(locals[i]);
        }
        if (r.wrap != null && f[0] >= 0 && f[1] >= 0) {
            double[] hp = mulNew(inverse(prop), hand);
            want = wrapFingers(r.hold != null ? mulNew(r.hold.frameInv, hp) : hp, t, want, false, r.wrap, r.wrapState, t0);
        }
        for (int i = 0; i < 2; i++) {
            if (f[i] >= 0) {
                setRotation(locals[i], slerp(unitRotation(locals[i]), want[i], k), columnScales(locals[i]));
                bt[f[i]].set(write(locals[i], tmp));
            }
        }
        fingerFits++;
    }
    // --- FB2-N: the support hand's hold on its grip surface, chosen together with the arm ----------------------------------
    /**
     * FB2-N (user 2026-10-18, five screenshots of the AK-74N and the VS-24 + VS-33c in low and high ready, photos of real holds
     * on a bare handguard, a vertical and an angled foregrip: "the handguard and the grips are held completely unrealistically",
     * "on a bare handguard move the hand further along it if the arm allows", "keep the hands diagonal", "a little clipping
     * through the model beats an unrealistic or unnatural hold"): the support hand kept one rotation on the weapon - Tarkov's
     * first-person hold, the forearm from below and behind - while the PZ stances bring the forearm in from elsewhere; the
     * wrist bent 51 (low ready) to 85 (idle after a shot) degrees and the hand hung under the handguard at its start. Now the
     * hold is chosen on the grip surface (TW/GripTargets 'hold' and 'wrap': stations along the axis Y of a frame - the
     * handguard along the bore, a vertical grip down its own axis) together with the arm: slide s along the axis, roll phi
     * about it through the section centroid, yaw psi about the palm normal (the diagonal), and the elbow's swivel about the
     * shoulder-wrist axis in closed form (the elbow that lays the forearm nearest along the hand, within HOLD_SWIVEL of the
     * clip's). Lowest cost: the wrist's extension / deviation past HOLD_EXT_* / HOLD_DEV_*, the slide off its preferred end
     * (a bare handguard: its front, within the reach of an elbow opened at most HOLD_ELBOW), roll / yaw / swivel off the
     * base, the diagonal outside the kind's band, the arm deeper than HOLD_TORSO_TOL_CM in the torso, the wrist twist past
     * HOLD_TWIST_OK, and (refits) the previous parameters. A HOLD_STEP_* grid keeps its HOLD_STARTS best distinct cells, each
     * refined on a HOLD_FINE_* grid, and the HOLD_FINALS best of everything evaluated are placed and costed again (a rolled copy
     * of the placed base moves a few cm when placed on a square section; none within HOLD_ELBOW: the best whose placed hand the
     * straight arm reaches, else none - the fixed hold); the base hold is placed first (holdPlace: the palm proxy
     * HOLD_PALM_SINK into the surface along its normal, a handguard's knuckles the kind's distance beyond the section across
     * the hand). Refitted at most every HOLD_FIT_NS, and only while the arm moves on the weapon (HOLD_REFIT_*); between fits
     * the chosen hold rides on the moving weapon and its swivel is re-solved every frame. The chosen arm stays: no torso
     * clearance or twist relief after it (both are its cost).
     * tools/grip_targets.hold_fit is the same computation offline.
     */
    public static volatile boolean holdOn = true;
    public static volatile long holdFits, holdNanos, holdFrames;
    static final double HOLD_STEP_S = 2 * CM, HOLD_STEP_ROLL = 15, HOLD_STEP_YAW = 10;
    static final double HOLD_FINE_S = 1 * CM, HOLD_FINE_ROLL = 5, HOLD_FINE_YAW = 5;
    static final int HOLD_STARTS = 3, HOLD_ITERS = 3, HOLD_FINALS = 6;
    static final double HOLD_APART_S = 2.5 * CM, HOLD_APART_ROLL = 20, HOLD_APART_YAW = 15;
    /** grip_targets.HOLD_SPECS per kind (1 bare, 2 vertical, 3 angled, 4 hand stop, 5 underbarrel launcher - FB7, Tarkov's
     *  rifle-mode hold on it): roll lo, hi, yaw lo, hi, diagonal lo, hi (NaN: none), knuckles out (NaN: palm only). */
    static final double[][] HOLD_SPEC = {
            {-90, 30, -30, 65, 20, 70, -.5 * CM},
            {-150, 150, -40, 40, Double.NaN, Double.NaN, Double.NaN},
            {-45, 30, -20, 55, 20, 70, -.5 * CM},
            {-45, 30, -20, 55, 20, 70, -.5 * CM},
            {-90, 45, -60, 60, Double.NaN, Double.NaN, Double.NaN}};
    static final double HOLD_SWIVEL = 45, HOLD_EXT_LO = -10, HOLD_EXT_HI = 30, HOLD_DEV_LO = -22, HOLD_DEV_HI = 12;
    static final double HOLD_ELBOW = Math.toRadians(150);
    /**
     * FB8 item 7 (user 22.10.2026, "the player does not hold the angled foregrip on the PKP and PKM"): an installed belt
     * box (TW/GripTargets 'obstacle': its Prop1 box) is an obstacle of the support arm on every belt-fed weapon
     * (grip_targets.OB_*): the hold search costs the arm's depth in it (OB_K per squared cm), reaches with the elbow
     * opened to OB_ELBOW, swivels the elbow OB_SWIVEL off the clip's and slides OB_SLIDE further down a grip; the elbow
     * clearance turns the arm out of it as out of the torso.
     */
    static final double OB_K = 100, OB_SWIVEL = 90, OB_ELBOW = Math.toRadians(179), OB_SLIDE = 3 * CM;
    static final double HOLD_K_WRIST = .05, HOLD_K_WRIST_IN = .002, HOLD_K_SLIDE = .5, HOLD_K_ROLL = .0015, HOLD_K_YAW = .003, HOLD_K_SWIVEL = .002,
            HOLD_K_DIAG = .05, HOLD_K_TORSO = 4, HOLD_K_TWIST = .01;
    static final double HOLD_TORSO_TOL_CM = 1, HOLD_TWIST_OK = 150;
    /**
     * The palm out of the magazine (user 23.10.2026: "on the MPX the hand goes into the magazine"; tools/palm_mag: vertical
     * grips of the MP5K, Saiga-12K, MP5, SA58 up to 5 cm, angled grips of the M4A1 and MPX 3 cm, the TRG M10's hand stop
     * 1.7): the hold's cost per squared cm of the palm proxy deeper than MAG_TOL_CM in an installed magazine (TW/GripTargets
     * 'mags': its slices MAG_SLICE apart along the bore, grip_targets.MAG_*).
     */
    static final double HOLD_K_MAG = 4, MAG_TOL_CM = .2, MAG_SLICE = .5 * CM;
    static final double HOLD_PALM_SINK = .8 * CM, HOLD_PALM_RANGE = 6 * CM, HOLD_SECTION_STEP = 2 * CM;
    /** Refits hold the previous parameters (runtime only): per squared cm of slide and squared degree of roll / yaw. */
    static final double HOLD_K_PREV_S = .05, HOLD_K_PREV_ANG = .0005;
    static final long HOLD_FIT_NS = 100_000_000L;
    /** holdFix: a refit after a fit longer than HOLD_FIT_DUTY_MIN_NS waits HOLD_FIT_DUTY times its length (at most ~1/8 of
     *  the time in fits); warm fits take 1-3 ms (check_grip_hold_cost), so only a slow one is held back. */
    static final long HOLD_FIT_DUTY = 8, HOLD_FIT_DUTY_MIN_NS = 12_000_000L;
    /** holdFix: the finger offset read from the bones, rounded (model units, ~1.7 um): the same assembly keys the same memo. */
    static final double HOLD_T1_QUANT = 1e-6;
    /** A steady arm keeps its hold (runtime only): a refit waits until the shoulder or the clip's elbow or wrist moved
     *  HOLD_REFIT_CM on the weapon since the last fit, or HOLD_REFIT_MAX_NS passed. */
    static final double HOLD_REFIT_CM = 1;
    static final long HOLD_REFIT_MAX_NS = 1_000_000_000L;
    /**
     * FB7 (the user's item 2, 22.10.2026: "the left hand while running, especially in patrol stance, keeps teleporting instead
     * of moving smoothly"): on a run the shoulder and the clip's elbow move 4-9 cm on the weapon every cycle (high ready up
     * to 20 cm), so a refit every HOLD_FIT_NS took another minimum of the hold's cost several times a cycle - offline roll
     * 0 -> -45 degrees and yaw 27 -> 8 in one frame, the elbow 10.6 cm in a frame - placed it at once, and the elbow pole
     * followed its swivel unsmoothed. Now (runtime only: the fit itself is unchanged; grip_targets.HoldRuntime is the same
     * offline): a new choice replaces the held one only when it is HOLD_HYST cheaper than the held one costs now; the hand
     * goes from the shown hold to the new one on the weapon over HOLD_BLEND_S, longer for a larger turn (HOLD_RATE_DEG_S);
     * the elbow pole (on the weapon) follows over HOLD_POLE_TAU_S and turns at most HOLD_POLE_RATE_DEG_S (moving:
     * HOLD_POLE_MOVE_RATE_DEG_S - a hold change on a run swung the elbow 1-2 cm a frame past the clip's) - its swivel basis is
     * the clip's elbow off the shoulder-wrist line and flipped when that elbow crossed the line (the IK's elbow 20 cm in a frame,
     * offline: the male patrol run); while the character moves, refits wait HOLD_MOVE_FIT_NS, test the arm
     * averaged over HOLD_MOVE_TAU_S (longer than a run cycle) instead of this frame's and choose the hold for that mean arm
     * (holdMeanArm), and a cheaper hold is taken only when it costs HOLD_HYST less than the held one on average over the
     * last HOLD_SNAPS arms (HOLD_SNAP_NS apart: two to three cycles) - one arm decides badly, the torso moves on the weapon within a
     * cycle (offline, high ready: the held hold cost 2, then 340 a fit later) - so one hold carries the whole cycle; a held
     * hold out of reach is left at once.
     */
    static final double HOLD_HYST = 1, HOLD_BLEND_S = 0.45, HOLD_RATE_DEG_S = 90, HOLD_POLE_TAU_S = 0.1, HOLD_POLE_RATE_DEG_S = 360,
            HOLD_POLE_MOVE_RATE_DEG_S = 120, HOLD_MOVE_TAU_S = 0.8;
    static final long HOLD_MOVE_FIT_NS = 400_000_000L, HOLD_SNAP_NS = 100_000_000L;
    static final int HOLD_SNAPS = 16;
    /** FB7: the cost of a hold out of reach of a stored arm (the whole HOLD_K_TORSO of 10 cm in the torso). */
    static final double HOLD_SNAP_MISS = 400;
    public static volatile long holdKeeps, holdBlends;
    /** grip_targets.hold_palm: the palm proxy of the measured bodies (assets/finger_wrap.json proxy.palm), hand frame, cm. */
    static final double[][] HOLD_PALM = {{5.0, -3.0, 2.8629}, {5.0, 0.0, 2.6973}, {5.0, 3.0, 3.1388}, {7.0, -3.0, -0.2067}, {7.0, 0.0, 2.2674},
            {7.0, 3.0, 2.8748}, {9.0, -3.0, 1.0091}, {9.0, 0.0, 1.7213}, {9.0, 3.0, 1.466}, {11.0, -3.0, 0.9507}, {11.0, 0.0, 0.3959},
            {11.0, 3.0, 2.143}, {13.0, -3.0, 1.1402}, {13.0, 0.0, 0.2096}, {13.0, 3.0, 2.0242}};
    static final double[] HOLD_PALM_CENTRE = holdPalmCentre();

    static double[] holdPalmCentre() {
        double[] c = new double[3];
        for (double[] p : HOLD_PALM) {
            for (int i = 0; i < 3; i++) {
                c[i] += p[i] * CM / HOLD_PALM.length;
            }
        }
        return c;
    }

    /** TW/GripTargets 'hold': the grip surface's kind, frame in Prop1 (identity for the handguard kinds), slide along its Y from the base hold, a vertical grip's finger rests. */
    static final class Hold {
        final int kind;
        final double[] frame, frameInv;
        final double sLo, sHi, sPref;
        final double[][] rest;
        /** Assembly-only placement memo; replaced when any placement input changes. No arm or score is stored. */
        volatile HoldPlacementCache placements;

        Hold(int kind, double[] frame, double[] slide, double[][] rest) {
            this.kind = kind;
            this.frame = frame;
            this.frameInv = inverse(frame);
            this.sLo = slide[0];
            this.sHi = slide[1];
            this.sPref = slide[2];
            this.rest = rest;
        }
    }

    static double[] numbers(Object o, int n) {
        if (!(o instanceof KahluaTable t) || t.len() != n) {
            return null;
        }
        double[] f = new double[n];
        for (int i = 0; i < n; i++) {
            if (!(t.rawget(i + 1) instanceof Double d)) {
                return null;
            }
            f[i] = d;
        }
        return f;
    }

    /** The 'hold' field of TW/GripTargets, or null when absent or malformed. */
    static Hold hold(Object o) {
        if (!(o instanceof KahluaTable t) || !(t.rawget("kind") instanceof Double k) || k < 1 || k > HOLD_SPEC.length) {
            return null;
        }
        double[] slide = numbers(t.rawget("slide"), 3);
        if (slide == null) {
            return null;
        }
        double[] frame = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        Object fo = t.rawget("frame");
        if (fo != null) {
            double[] f = numbers(fo, 12);
            if (f == null) {
                return null;
            }
            System.arraycopy(f, 0, frame, 0, 12);
        }
        double[][] rest = null;
        Object ro = t.rawget("rest");
        if (ro != null) {
            double[] r = numbers(ro, 18);
            if (r == null) {
                return null;
            }
            rest = new double[][] {java.util.Arrays.copyOfRange(r, 0, 9), java.util.Arrays.copyOfRange(r, 9, 18)};
        }
        return new Hold(k.intValue(), frame, slide, rest);
    }

    /** grip_targets.HoldArm: the shoulder, the clip's elbow and wrist, the forearm's frame, link lengths, the weapon, the torso and the bind wrist. */
    static final class HoldArm {
        double[] Pn, S, E0, W0, F0, r0, axis;
        /** FB8: the belt box (lo xyz, hi xyz; Prop1, model units) or null. */
        double[] ob;
        double l1, l2;
        double[][] model, torso;
        int[] chain;
        /** The torso chain's segments for armDepth, built once per arm: a search costs ~1600 holds x 7 torso depths,
         *  each allocating ~23 arrays from the same bones (performance pass 23.10.2026: ~10 MB of garbage per search). */
        double[][] seg;
    }

    /** Per segment i of the chain: {a xyz, ab xyz, ab.ab, left xyz, ahead xyz} as torsoDepth computes them. */
    static double[][] torsoSegments(double[][] model, int[] chain) {
        double[][] seg = new double[3][];
        for (int i = 0; i < 3; i++) {
            double[] a = pos(model[chain[i]]), ab = sub(pos(model[chain[i + 1]]), a);
            double[] m = model[chain[i]];
            double[] left = normalize(new double[] {m[1], m[5], m[9]}), ahead = normalize(new double[] {m[2], m[6], m[10]});
            seg[i] = new double[] {a[0], a[1], a[2], ab[0], ab[1], ab[2], dot(ab, ab), left[0], left[1], left[2], ahead[0], ahead[1], ahead[2]};
        }
        return seg;
    }

    /** torsoDepth at (px, py, pz) from torsoSegments, without allocations: the same arithmetic in the same order. */
    static double torsoDepth(double[][] seg, double px, double py, double pz, double radius, double[][] torso) {
        double best = Double.MAX_VALUE, bt = 0, bx = 0, by = 0, bz = 0;
        int bi = 0;
        for (int i = 0; i < 3; i++) {
            double[] g = seg[i];
            double t = Math.max(0, Math.min(1, ((px - g[0]) * g[3] + (py - g[1]) * g[4] + (pz - g[2]) * g[5]) / g[6]));
            double cx = g[0] + g[3] * t, cy = g[1] + g[4] * t, cz = g[2] + g[5] * t;
            double dx = px - cx, dy = py - cy, dz = pz - cz;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < best) {
                best = d;
                bt = t;
                bi = i;
                bx = cx;
                by = cy;
                bz = cz;
            }
        }
        if ((bi == 2 && bt >= 1) || (bi == 0 && bt <= 0)) {
            return -1; // above the neck or below the pelvis
        }
        double[] g = seg[bi];
        double dx = px - bx, dy = py - by, dz = pz - bz;
        double x = dx * g[7] + dy * g[8] + dz * g[9], z = dx * g[10] + dy * g[11] + dz * g[12];
        double hw = torso[bi][0] * CM, hz = (z > 0 ? torso[bi][1] : torso[bi][2]) * CM;
        double rr = Math.hypot(x, z);
        double r = Math.hypot(x / hw, z / hz);
        if (rr < 1e-9) {
            return Math.min(hw, hz) + radius;
        }
        return rr / r - rr + radius;
    }

    /** armDepth(model, chain, sh, e, w, torso) of a hold arm through its cached segments. */
    static double armDepth(HoldArm a, double[] sh, double[] e, double[] w) {
        return armDepth(a, sh, e, w, R_FORE, CLEAR_TOL);
    }

    static double armDepth(HoldArm a, double[] sh, double[] e, double[] w, double rFore, double tolerance) {
        if (a.seg == null) {
            a.seg = torsoSegments(a.model, a.chain);
        }
        double worst = -1;
        for (double t : ARM_UPPER_T) {
            worst = Math.max(worst, torsoDepth(a.seg, sh[0] + (e[0] - sh[0]) * t, sh[1] + (e[1] - sh[1]) * t, sh[2] + (e[2] - sh[2]) * t,
                    R_UPPER, a.torso) - CLEAR_TOL_UPPER);
        }
        for (double t = 0; t <= 1.0001; t += 0.25) {
            worst = Math.max(worst, torsoDepth(a.seg, e[0] + (w[0] - e[0]) * t, e[1] + (w[1] - e[1]) * t, e[2] + (w[2] - e[2]) * t,
                    rFore + (R_WRIST - rFore) * t, a.torso) - tolerance);
        }
        return worst;
    }

    static final double[] ARM_UPPER_T = {0.6, 0.85};

    /** grip_targets.hold_steps: [a, b] in equal steps of at most h, both ends ([a] for an empty range). */
    static double[] holdSteps(double a, double b, double h) {
        if (b <= a + 1e-9) {
            return new double[] {a};
        }
        int n = (int) Math.ceil((b - a) / h - 1e-9);
        double[] out = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            out[i] = a + (b - a) * i / n;
        }
        return out;
    }

    /** grip_targets.hold_candidate: L yawed psi about its palm normal through the palm centre, rolled phi about G.Y through c0, slid s along it. */
    static double[] holdCandidate(double[] L, double[] G, double[] c0, double s, double phi, double psi) {
        double[] pc = point(L, HOLD_PALM_CENTRE[0], HOLD_PALM_CENTRE[1], HOLD_PALM_CENTRE[2]);
        double[] n = normalize(new double[] {L[2], L[6], L[10]});
        double[] T = mulNew(rigid(n, Math.toRadians(psi), pc), L);
        double[] ax = normalize(new double[] {G[1], G[5], G[9]});
        T = mulNew(rigid(ax, Math.toRadians(phi), c0), T);
        T[3] += ax[0] * s;
        T[7] += ax[1] * s;
        T[11] += ax[2] * s;
        return T;
    }

    /** grip_targets.hold_eval: {omega, ext, dev (degrees), depth (model units), twist (degrees)} of the arm reaching hand T (Prop1), or null out of reach. */
    static double[] holdEval(double[] T, HoldArm a, double reach) {
        double[] Hw = mulNew(a.Pn, T);
        double[] R = unitRotation(Hw);
        double[] W = pos(Hw), d = sub(W, a.S);
        double dist = norm(d);
        if (dist > reach || dist < Math.abs(a.l1 - a.l2) + 1e-6) {
            return null;
        }
        double[] axis = scale(d, 1 / dist);
        double x = (a.l1 * a.l1 - a.l2 * a.l2 + dist * dist) / (2 * dist);
        double r = Math.sqrt(Math.max(0, a.l1 * a.l1 - x * x));
        double[] C = add(a.S, scale(axis, x));
        double[] es = sub(a.E0, a.S);
        double[] u = sub(es, scale(axis, dot(es, axis)));
        if (norm(u) < 1e-9) {
            return null;
        }
        u = normalize(u);
        double[] v = cross(axis, u);
        double[] p = sub(sub(W, scale(new double[] {R[0], R[3], R[6]}, a.l2)), C);
        double sw = a.ob != null ? OB_SWIVEL : HOLD_SWIVEL;
        double om = Math.max(-sw, Math.min(sw, Math.toDegrees(Math.atan2(dot(p, v), dot(p, u)))));
        double c = Math.cos(Math.toRadians(om)), sn = Math.sin(Math.toRadians(om));
        double[] E = add(C, scale(add(scale(u, c), scale(v, sn)), r));
        double[] f = normalize(sub(W, E));
        double fx = R[0] * f[0] + R[3] * f[1] + R[6] * f[2], fy = R[1] * f[0] + R[4] * f[1] + R[7] * f[2], fz = R[2] * f[0] + R[5] * f[1] + R[8] * f[2];
        double depth = a.model == null ? -1 : PerfLegacy.on ? armDepth(a.model, a.chain, a.S, E, W, a.torso) : armDepth(a, a.S, E, W);
        double tw = 0;
        if (a.r0 != null) {
            double[] F = new double[16];
            rotate(a.F0, rotationBetween(sub(a.W0, a.E0), sub(W, E)), F);
            setPos(F, E);
            tw = Math.toDegrees(wristTwist(F, Hw, a.r0, a.axis));
        }
        double box = a.ob == null ? -1 : obDepth(a.Pn, a.ob, a.S, E, W);
        return new double[] {om, Math.toDegrees(Math.atan2(fz, fx)), Math.toDegrees(Math.atan2(fy, fx)), depth, tw, box};
    }

    /** grip_targets.ob_depth (FB8): how deep the lower upper arm and the forearm capsules lie in the obstacle box (> 0 inside). */
    static double obDepth(double[] Pn, double[] ob, double[] sh, double[] e, double[] w) {
        double[] inv = inverse(Pn);
        double worst = -1;
        for (double t : new double[] {0.6, 0.85}) {
            worst = Math.max(worst, obSphere(inv, ob, lerp(sh, e, t), R_UPPER));
        }
        for (double t = 0; t <= 1.0001; t += 0.25) {
            worst = Math.max(worst, obSphere(inv, ob, lerp(e, w, t), R_FORE + (R_WRIST - R_FORE) * t));
        }
        return worst;
    }

    static double obSphere(double[] inv, double[] ob, double[] p, double r) {
        double[] q = point(inv, p[0], p[1], p[2]);
        double out = 0, in = -Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            double d = Math.max(ob[i] - q[i], q[i] - ob[i + 3]);
            out += d > 0 ? d * d : 0;
            in = Math.max(in, d);
        }
        return r - (Math.sqrt(out) + Math.min(in, 0));
    }

    /** grip_targets.hold_cost; prev {s, phi, psi} of the last fit or null. */
    static double holdCost(double[] e, double s, double sPref, double phi, double psi, double diag, double[] spec, double[] prev) {
        double ext = e[1], dev = e[2];
        double c = HOLD_K_WRIST * (sq(Math.max(0, HOLD_EXT_LO - ext)) + sq(Math.max(0, ext - HOLD_EXT_HI))
                + sq(Math.max(0, HOLD_DEV_LO - dev)) + sq(Math.max(0, dev - HOLD_DEV_HI)));
        c += HOLD_K_WRIST_IN * (ext * ext + dev * dev);
        c += HOLD_K_SLIDE * Math.abs(sPref - s) / CM;
        c += HOLD_K_ROLL * phi * phi + HOLD_K_YAW * psi * psi + HOLD_K_SWIVEL * e[0] * e[0];
        if (!Double.isNaN(spec[4])) {
            c += HOLD_K_DIAG * (sq(Math.max(0, spec[4] - diag)) + sq(Math.max(0, diag - spec[5])));
        }
        c += HOLD_K_TORSO * sq(Math.max(0, e[3] / CM - HOLD_TORSO_TOL_CM));
        c += HOLD_K_TWIST * sq(Math.max(0, Math.abs(e[4]) - HOLD_TWIST_OK));
        if (e.length > 5) {
            c += OB_K * sq(Math.max(0, e[5] / CM));
        }
        if (prev != null) {
            c += HOLD_K_PREV_S * sq((s - prev[0]) / CM) + HOLD_K_PREV_ANG * (sq(phi - prev[1]) + sq(psi - prev[2]));
        }
        return c;
    }

    static double holdDiag(double[] T, double[] G) {
        double[] x = normalize(new double[] {T[0], T[4], T[8]}), y = normalize(new double[] {G[1], G[5], G[9]});
        return Math.toDegrees(Math.acos(Math.min(1, Math.abs(dot(x, y)))));
    }

    /** Minimum signed distance (model units, < 0 inside) of the palm proxy HOLD_PALM to the stations w in frame Gi. */
    static double holdPalmMinSd(double[] T, Wrap w, double[] Gi) {
        double[] TG = mulNew(Gi, T);
        double min = Double.POSITIVE_INFINITY;
        for (double[] p : HOLD_PALM) {
            double[] q = point(TG, p[0] * CM, p[1] * CM, p[2] * CM);
            min = Math.min(min, wrapSd(w, q[0], q[1], q[2]));
        }
        return min;
    }

    /** A placed hold must touch its surface; 1e-6 is the existing model-space geometry epsilon, not a gap allowance. */
    static boolean holdContactValid(double[] T, Hold h, Wrap w) {
        if (T == null) return false;
        double distance = holdPalmMinSd(T, w, h.frameInv);
        return Double.isFinite(distance) && distance <= 1e-6;
    }

    /** grip_targets.hold_reseat: along the palm normal until the palm proxy lies HOLD_PALM_SINK inside the stations (frame via Gi). */
    static double[] holdReseat(double[] T, Wrap w, double[] Gi) {
        return holdReseat(T, w, Gi, w.unimodal);
    }

    static double[] holdReseat(double[] T, Wrap w, double[] Gi, boolean unimodal) {
        double lo = w.y[0] - HOLD_SECTION_STEP / 2, hi = w.y[w.y.length - 1] + HOLD_SECTION_STEP / 2;
        double[] n = normalize(new double[] {T[2], T[6], T[10]});
        double[] ng = {Gi[0] * n[0] + Gi[1] * n[1] + Gi[2] * n[2], Gi[4] * n[0] + Gi[5] * n[1] + Gi[6] * n[2], Gi[8] * n[0] + Gi[9] * n[1] + Gi[10] * n[2]};
        double[] TG = mulNew(Gi, T);
        java.util.List<double[]> pts = new java.util.ArrayList<>();
        for (double[] p : HOLD_PALM) {
            double[] q = point(TG, p[0] * CM, p[1] * CM, p[2] * CM);
            if (q[1] >= lo && q[1] <= hi) {
                pts.add(q);
            }
        }
        if (pts.isEmpty()) {
            return T;
        }
        double t;
        if (!unimodal) {
            double a = -HOLD_PALM_RANGE, b = HOLD_PALM_RANGE;
            for (int i = 0; i < 24; i++) {
                double m = (a + b) / 2, depth = Double.NEGATIVE_INFINITY;
                for (double[] q : pts) {
                    double x = q[0] + ng[0] * m, y = q[1] + ng[1] * m, z = q[2] + ng[2] * m;
                    if (holdFix && !w.mayLieDeeper(x, y, z, HOLD_PALM_SINK)) {
                        continue; // its depth cannot pass HOLD_PALM_SINK: the step is decided the same without it
                    }
                    depth = Math.max(depth, -wrapSd(w, x, y, z));
                }
                if (depth > HOLD_PALM_SINK) {
                    b = m;
                } else {
                    a = m;
                }
            }
            t = (a + b) / 2;
        } else {
            // Concave signed-distance profile on a thin (< 2 * HOLD_PALM_SINK) station sliver: locate the unimodal
            // depth peak mPeak in [-HOLD_PALM_RANGE, +HOLD_PALM_RANGE] and bisect the monotone palm-approach flank
            // [-HOLD_PALM_RANGE, mPeak] to HOLD_PALM_SINK (or seat at mPeak when peak depth <= HOLD_PALM_SINK).
            double bestM = 0, bestD = Double.NEGATIVE_INFINITY;
            int steps = 48;
            for (int i = 0; i <= steps; i++) {
                double m = -HOLD_PALM_RANGE + 2 * HOLD_PALM_RANGE * i / steps;
                double d = Double.NEGATIVE_INFINITY;
                for (double[] q : pts) {
                    d = Math.max(d, -wrapSd(w, q[0] + ng[0] * m, q[1] + ng[1] * m, q[2] + ng[2] * m));
                }
                if (d > bestD) {
                    bestD = d;
                    bestM = m;
                }
            }
            double stepW = 2 * HOLD_PALM_RANGE / steps;
            double la = Math.max(-HOLD_PALM_RANGE, bestM - stepW), lb = Math.min(HOLD_PALM_RANGE, bestM + stepW);
            for (int i = 0; i < 16; i++) {
                double m1 = la + (lb - la) / 3, m2 = lb - (lb - la) / 3;
                double d1 = Double.NEGATIVE_INFINITY, d2 = Double.NEGATIVE_INFINITY;
                for (double[] q : pts) {
                    d1 = Math.max(d1, -wrapSd(w, q[0] + ng[0] * m1, q[1] + ng[1] * m1, q[2] + ng[2] * m1));
                    d2 = Math.max(d2, -wrapSd(w, q[0] + ng[0] * m2, q[1] + ng[1] * m2, q[2] + ng[2] * m2));
                }
                if (d1 < d2) {
                    la = m1;
                } else {
                    lb = m2;
                }
            }
            double mPeak = (la + lb) / 2, dPeak = Double.NEGATIVE_INFINITY;
            for (double[] q : pts) {
                dPeak = Math.max(dPeak, -wrapSd(w, q[0] + ng[0] * mPeak, q[1] + ng[1] * mPeak, q[2] + ng[2] * mPeak));
            }
            if (dPeak <= 0) {
                return T;
            }
            if (dPeak <= HOLD_PALM_SINK) {
                t = mPeak;
            } else {
                double a = -HOLD_PALM_RANGE, b = mPeak;
                for (int i = 0; i < 24; i++) {
                    double m = (a + b) / 2, depth = Double.NEGATIVE_INFINITY;
                    for (double[] q : pts) {
                        double x = q[0] + ng[0] * m, y = q[1] + ng[1] * m, z = q[2] + ng[2] * m;
                        if (holdFix && !w.mayLieDeeper(x, y, z, HOLD_PALM_SINK)) {
                            continue; // as in the bisection above
                        }
                        depth = Math.max(depth, -wrapSd(w, x, y, z));
                    }
                    if (depth > HOLD_PALM_SINK) {
                        b = m;
                    } else {
                        a = m;
                    }
                }
                t = (a + b) / 2;
            }
        }
        double[] out = T.clone();
        out[3] += n[0] * t;
        out[7] += n[1] * t;
        out[11] += n[2] * t;
        return out;
    }

    /** grip_targets.hold_place: the palm onto the surface; a handguard's knuckles out beyond the section across the hand (HOLD_ITERS times). */
    static double[] holdPlace(double[] T, Hold h, Wrap w, double[] t1) {
        double[] placed = holdPlace(T, h, w, t1, w.unimodal);
        if (!w.unimodal && !holdContactValid(placed, h, w)) {
            // A rotated candidate can cross a thin/non-monotone section even when its base did not.
            // Retry the original candidate, never the already displaced failure or shared Wrap mode.
            double[] recovered = holdPlace(T, h, w, t1, true);
            if (holdContactValid(recovered, h, w)) return recovered;
        }
        return placed;
    }

    static double[] holdPlace(double[] T, Hold h, Wrap w, double[] t1, boolean unimodal) {
        double out = HOLD_SPEC[h.kind - 1][6];
        double[] G = h.frame, Gi = h.frameInv;
        if (!Double.isNaN(out)) {
            for (int it = 0; it < HOLD_ITERS; it++) {
                T = holdReseat(T, w, Gi, unimodal);
                double[] k = point(mulNew(Gi, T), t1[0], t1[1], t1[2]);
                double[] x = normalize(new double[] {T[0], T[4], T[8]});
                double cx = Gi[0] * x[0] + Gi[1] * x[1] + Gi[2] * x[2], cz = Gi[8] * x[0] + Gi[9] * x[1] + Gi[10] * x[2];
                double cn = Math.hypot(cx, cz);
                if (cn < 1e-6) {
                    break;
                }
                cx /= cn;
                cz /= cn;
                double[] poly = w.poly[nearestStation(w, k[1])];
                double hmax = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < poly.length; i += 2) {
                    hmax = Math.max(hmax, poly[i] * cx + poly[i + 1] * cz);
                }
                double shift = hmax + out - (k[0] * cx + k[2] * cz);
                T = T.clone();
                T[3] += (G[0] * cx + G[2] * cz) * shift;
                T[7] += (G[4] * cx + G[6] * cz) * shift;
                T[11] += (G[8] * cx + G[10] * cz) * shift;
            }
        }
        return holdReseat(T, w, Gi, unimodal);
    }

    /** grip_targets.station_at: the station nearest to y along the axis (the first of equals). */
    static int nearestStation(Wrap w, double y) {
        int best = 0;
        for (int i = 1; i < w.y.length; i++) {
            if (Math.abs(w.y[i] - y) < Math.abs(w.y[best] - y)) {
                best = i;
            }
        }
        return best;
    }

    /** Static per request: the placed base hold, the roll centre and the slide range shifted by the placement {L(16), c0(3), lo, hi, pref}. */
    static double[][] holdBase(Hold h, Wrap w, double[] L, double[] t1) {
        w.unimodal = false;
        double[] Lp = holdPlace(L, h, w, t1, w.unimodal);
        double[] kb = point(mulNew(h.frameInv, L), t1[0], t1[1], t1[2]);
        if (holdPalmMinSd(Lp, w, h.frameInv) > HOLD_PALM_RANGE) {
            // W10 armsrest2: 1-Lipschitz wrapSd guarantees a surface-anchored base hold cannot end with every palm
            // sample further than HOLD_PALM_RANGE outside the stations unless both bareHold (+PALM_RANGE along frame Z)
            // and holdPlace (+HOLD_PALM_RANGE along palm +Z) diverged to their upper wall on a top-rail sliver thinner
            // than 2 * HOLD_PALM_SINK (VSK94 6.87 cm, MP153 10.83 cm; all other 531 assemblies <= 2.27 cm).
            double[] poly0 = w.poly[nearestStation(w, kb[1])];
            double minZ0 = Double.POSITIVE_INFINITY;
            for (int i = 1; i < poly0.length; i += 2) {
                minZ0 = Math.min(minZ0, poly0[i]);
            }
            double dz0 = minZ0 - kb[2];
            double[] Lre = L.clone();
            Lre[3] += h.frame[2] * dz0;
            Lre[7] += h.frame[6] * dz0;
            Lre[11] += h.frame[10] * dz0;
            w.unimodal = true;
            Lp = holdPlace(Lre, h, w, t1, w.unimodal);
        }
        double[] k0 = point(mulNew(h.frameInv, Lp), t1[0], t1[1], t1[2]);
        double[] poly = w.poly[nearestStation(w, k0[1])];
        double mx = 0, mz = 0;
        for (int i = 0; i < poly.length; i += 2) {
            mx += poly[i] * 2 / poly.length;
            mz += poly[i + 1] * 2 / poly.length;
        }
        double[] c0 = point(h.frame, mx, k0[1], mz);
        double shift = k0[1] - kb[1];
        return new double[][] {Lp, c0, {h.sLo - shift, h.sHi - shift, h.sPref - shift}};
    }

    /** A searched hold's parameters (List.of(s, phi, psi) before: the same equality, one object instead of four). */
    record HoldKey(double s, double phi, double psi) { }

    /** Bound this geometry-only memo even when many fine-grid cells are visited (holdFix: at least, see holdGridKeys). */
    static final int HOLD_PLACEMENT_CACHE_LIMIT = 2048;
    /** holdFix: the memos of the last assemblies, shared by their requests (a patrol in / out swap keeps its memo). */
    static final int HOLD_PLACEMENT_OWNERS = 4;
    static final java.util.ArrayDeque<HoldPlacementCache> SHARED_PLACEMENTS = new java.util.ArrayDeque<>();

    /** holdFix: the most placements one search of this hold can memo - every coarse cell (the slide range with a belt box's
     *  OB_SLIDE) and HOLD_STARTS whole fine grids, in both placement modes; a vertical grip ~2600, a bare 48 cm handguard ~5600. */
    static int holdGridKeys(Hold h, double[][] base) {
        double[] spec = HOLD_SPEC[h.kind - 1];
        int coarse = holdSteps(base[2][0], base[2][1] + OB_SLIDE, HOLD_STEP_S).length * holdSteps(spec[0], spec[1], HOLD_STEP_ROLL).length
                * holdSteps(spec[2], spec[3], HOLD_STEP_YAW).length;
        int fine = holdSteps(0, 2 * HOLD_STEP_S, HOLD_FINE_S).length * holdSteps(0, 2 * HOLD_STEP_ROLL, HOLD_FINE_ROLL).length
                * holdSteps(0, 2 * HOLD_STEP_YAW, HOLD_FINE_YAW).length;
        return 2 * (coarse + HOLD_STARTS * fine);
    }

    static int holdPlacementLimit(HoldPlacementCache cache) {
        return holdFix ? cache.capacity : HOLD_PLACEMENT_CACHE_LIMIT;
    }

    static final class HoldPlacementKey {
        final double[] matrix;
        final boolean unimodal;
        final int hash;

        HoldPlacementKey(double[] matrix, boolean unimodal) {
            this.matrix = matrix.clone();
            this.unimodal = unimodal;
            hash = 31 * java.util.Arrays.hashCode(this.matrix) + Boolean.hashCode(unimodal);
        }

        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return other instanceof HoldPlacementKey k && unimodal == k.unimodal && java.util.Arrays.equals(matrix, k.matrix);
        }
    }

    static final class HoldPlacementCache {
        final int kind;
        final boolean mode;
        final double out;
        final double[] frame, inverse, stations, finger;
        final double[][] polygons, base;
        /** holdFix: the bound of entries (the whole grid of this hold, at least HOLD_PLACEMENT_CACHE_LIMIT). */
        final int capacity;
        final java.util.LinkedHashMap<HoldPlacementKey, double[]> entries = new java.util.LinkedHashMap<>(32, .75f, true);

        HoldPlacementCache(Hold h, Wrap w, double[][] base, double[] t1) {
            kind = h.kind; mode = w.unimodal; out = HOLD_SPEC[h.kind - 1][6];
            frame = h.frame.clone(); inverse = h.frameInv.clone(); stations = w.y.clone(); finger = t1.clone();
            polygons = copy(w.poly); this.base = copy(base);
            capacity = Math.max(HOLD_PLACEMENT_CACHE_LIMIT, holdGridKeys(h, base));
        }

        static double[][] copy(double[][] values) {
            double[][] out = new double[values.length][];
            for (int i = 0; i < values.length; i++) out[i] = values[i].clone();
            return out;
        }

        boolean matches(Hold h, Wrap w, double[][] b, double[] t1) {
            return kind == h.kind && mode == w.unimodal
                    && Double.doubleToLongBits(out) == Double.doubleToLongBits(HOLD_SPEC[h.kind - 1][6])
                    && java.util.Arrays.equals(frame, h.frame) && java.util.Arrays.equals(inverse, h.frameInv)
                    && java.util.Arrays.equals(stations, w.y) && java.util.Arrays.equals(finger, t1)
                    && java.util.Arrays.deepEquals(polygons, w.poly) && java.util.Arrays.deepEquals(base, b);
        }
    }

    static HoldPlacementCache holdPlacementCache(Hold h, Wrap w, double[][] base, double[] t1) {
        HoldPlacementCache cache = h.placements;
        if (cache != null && cache.matches(h, w, base, t1)) return cache;
        if (holdFix) {
            synchronized (SHARED_PLACEMENTS) {
                for (HoldPlacementCache shared : SHARED_PLACEMENTS) {
                    if (shared.matches(h, w, base, t1)) {
                        SHARED_PLACEMENTS.remove(shared);
                        SHARED_PLACEMENTS.addFirst(shared);
                        holdMemoShared++;
                        return h.placements = shared;
                    }
                }
            }
        }
        h.placements = cache = new HoldPlacementCache(h, w, base, t1);
        holdMemoNew++;
        if (holdFix) {
            synchronized (SHARED_PLACEMENTS) {
                SHARED_PLACEMENTS.addFirst(cache);
                while (SHARED_PLACEMENTS.size() > HOLD_PLACEMENT_OWNERS) {
                    SHARED_PLACEMENTS.removeLast();
                }
            }
        }
        return cache;
    }

    static double[] holdPlacedCached(HoldPlacementCache cache, double[] T, Hold h, Wrap w, double[] t1, boolean unimodal) {
        HoldPlacementKey key = new HoldPlacementKey(T, unimodal);
        synchronized (cache) {
            double[] hit = cache.entries.get(key);
            if (hit != null) return hit.clone();
        }
        double[] placed = holdPlace(T, h, w, t1, unimodal);
        holdPlacements++;
        synchronized (cache) {
            if (cache.entries.size() >= holdPlacementLimit(cache) && !cache.entries.containsKey(key)) {
                cache.entries.remove(cache.entries.keySet().iterator().next());
            }
            cache.entries.put(key, placed.clone());
        }
        return placed.clone();
    }

    /** grip_targets.hold_fit: {s, phi, psi, cost} of the best placed hold (prev {s, phi, psi} held, null for none), or null when nothing is in reach. */
    static double[] holdSearch(Hold h, Wrap w, double[][] base, HoldArm a, double[] prev, double[] t1, double[][] mags) {
        double[] spec = HOLD_SPEC[h.kind - 1];
        double[] L = base[0], c0 = base[1];
        double lo = base[2][0], hi = base[2][1] + (a.ob != null ? OB_SLIDE : 0), pref = base[2][2];
        double reach = holdReach(a);
        holdSearches++;
        HoldPlacementCache placementCache = holdPlacementCache(h, w, base, t1);
        java.util.LinkedHashMap<HoldKey, Double> seen = new java.util.LinkedHashMap<>();
        java.util.List<double[]> cells = new java.util.ArrayList<>();
        for (double s : holdSteps(lo, hi, HOLD_STEP_S)) {
            for (double phi : holdSteps(spec[0], spec[1], HOLD_STEP_ROLL)) {
                for (double psi : holdSteps(spec[2], spec[3], HOLD_STEP_YAW)) {
                    double c = holdTry(holdCandidate(L, h.frame, c0, s, phi, psi), h, s, phi, psi, a, reach, pref, spec, prev, mags);
                    if (!Double.isNaN(c)) {
                        cells.add(new double[] {c, s, phi, psi});
                        seen.put(new HoldKey(s, phi, psi), c);
                    }
                }
            }
        }
        if (cells.isEmpty()) {
            return null;
        }
        cells.sort((x, y) -> Double.compare(x[0], y[0]));
        java.util.List<double[]> starts = new java.util.ArrayList<>();
        for (double[] r : cells) {
            boolean apart = true;
            for (double[] q : starts) {
                apart &= Math.abs(r[1] - q[1]) > HOLD_APART_S || Math.abs(r[2] - q[2]) > HOLD_APART_ROLL || Math.abs(r[3] - q[3]) > HOLD_APART_YAW;
            }
            if (apart) {
                starts.add(r);
                if (starts.size() == HOLD_STARTS) {
                    break;
                }
            }
        }
        for (double[] st : starts) {
            for (double s : holdSteps(Math.max(lo, st[1] - HOLD_STEP_S), Math.min(hi, st[1] + HOLD_STEP_S), HOLD_FINE_S)) {
                for (double phi : holdSteps(Math.max(spec[0], st[2] - HOLD_STEP_ROLL), Math.min(spec[1], st[2] + HOLD_STEP_ROLL), HOLD_FINE_ROLL)) {
                    for (double psi : holdSteps(Math.max(spec[2], st[3] - HOLD_STEP_YAW), Math.min(spec[3], st[3] + HOLD_STEP_YAW), HOLD_FINE_YAW)) {
                        HoldKey key = new HoldKey(s, phi, psi);
                        if (!seen.containsKey(key)) {
                            double c = holdTry(holdCandidate(L, h.frame, c0, s, phi, psi), h, s, phi, psi, a, reach, pref, spec, prev, mags);
                            if (!Double.isNaN(c)) {
                                seen.put(key, c);
                            }
                        }
                    }
                }
            }
        }
        java.util.List<java.util.Map.Entry<HoldKey, Double>> ranked = new java.util.ArrayList<>(seen.entrySet());
        ranked.sort((x, y) -> Double.compare(x.getValue(), y.getValue()));
        // Preserve the exact pre-filter legacy winner, including its straight-arm ordering.
        int finals = Math.min(HOLD_FINALS, ranked.size()), winner = -1;
        double[][] original = new double[finals][];
        double[] best = null;
        for (int i = 0; i < finals; i++) {
            HoldKey k = ranked.get(i).getKey();
            original[i] = holdPlacedCached(placementCache, holdCandidate(L, h.frame, c0, k.s(), k.phi(), k.psi()), h, w, t1, w.unimodal);
            double c = holdTry(original[i], h, k.s(), k.phi(), k.psi(), a, reach, pref, spec, prev, mags);
            if (!Double.isNaN(c) && (best == null || c < best[3])) {
                best = new double[] {k.s(), k.phi(), k.psi(), c}; winner = i;
            }
        }
        for (int i = 0; best == null && i < finals; i++) {
            if (holdEval(original[i], a, a.l1 + a.l2) != null) {
                HoldKey k = ranked.get(i).getKey();
                best = new double[] {k.s(), k.phi(), k.psi(), ranked.get(i).getValue()}; winner = i;
            }
        }
        if (winner >= 0 && holdContactValid(original[winner], h, w)) return best;

        // Recovery only: seating can reverse the unplaced ranking. Score the existing finite
        // candidate set after placement and bounded clearance look-ahead, never stop at the
        // first floating-winner replacement. Slot 4 marks the cost space for later hysteresis.
        holdRecoveries++;
        best = null;
        double[] straight = null;
        for (int i = 0; i < ranked.size(); i++) {
            HoldKey k = ranked.get(i).getKey();
            double[] candidate = holdCandidate(L, h.frame, c0, k.s(), k.phi(), k.psi());
            double[] T = i < finals ? original[i] : holdPlacedCached(placementCache, candidate, h, w, t1, w.unimodal);
            if (!holdContactValid(T, h, w) && !w.unimodal) T = holdPlacedCached(placementCache, candidate, h, w, t1, true);
            if (!holdContactValid(T, h, w)) continue;
            double c = holdTry(T, h, k.s(), k.phi(), k.psi(), a, reach, pref, spec, prev, mags,
                    true, best == null ? Double.POSITIVE_INFINITY : best[3]);
            if (Double.isFinite(c) && (best == null || c < best[3])) {
                best = new double[] {k.s(), k.phi(), k.psi(), c, 1};
            } else if (Double.isNaN(c) && best == null) {
                double sc = holdTry(T, h, k.s(), k.phi(), k.psi(), a, a.l1 + a.l2, pref, spec, prev, mags,
                        true, straight == null ? Double.POSITIVE_INFINITY : straight[3]);
                if (Double.isFinite(sc) && (straight == null || sc < straight[3])) {
                    straight = new double[] {k.s(), k.phi(), k.psi(), sc, 1};
                }
            }
        }
        return best != null ? best : straight;
    }

    static double holdTry(double[] T, Hold h, double s, double phi, double psi, HoldArm a, double reach, double pref, double[] spec, double[] prev,
                          double[][] mags) {
        return holdTry(T, h, s, phi, psi, a, reach, pref, spec, prev, mags, false, Double.POSITIVE_INFINITY);
    }

    static boolean holdClearanceCost(double[] choice) {
        return choice != null && choice.length > 4 && choice[4] != 0;
    }

    /** Recovery-only predictor: the existing bounded clear_elbow rule (both sides up to CLEAR_MAX,
     * five bisections), followed by limitWrist. The live W10 clearance and its temporal state stay untouched. */
    static double[] holdClearanceEval(double[] T, HoldArm a, double[] source) {
        double[] e = source.clone(), hand = mulNew(a.Pn, T), wrist = pos(hand);
        double[] axis = normalize(sub(wrist, a.S));
        double length = dist(wrist, a.S);
        double reach = Math.max(Math.abs(a.l1 - a.l2) + 1e-7, Math.min(length, a.l1 + a.l2 - 1e-7));
        double x = (a.l1 * a.l1 - a.l2 * a.l2 + reach * reach) / (2 * reach);
        double radius = Math.sqrt(Math.max(0, a.l1 * a.l1 - x * x));
        double[] elbow = add(add(a.S, scale(axis, x)), scale(holdPole(a, T, e[0]), radius));
        wrist = add(a.S, scale(axis, reach));
        setPos(hand, wrist);
        final double[] sh = a.S, el = elbow, wr = wrist, ax = axis;
        java.util.function.DoubleUnaryOperator depth = angle -> {
            double[] moved = add(sh, rotateVec(ax, angle, sub(el, sh)));
            double d = a.model == null ? -1 : armDepth(a, sh, moved, wr, R_FORE_CLEAR, CLEAR_TOL_CLEAR);
            return a.ob == null ? d : Math.max(d, obDepth(a.Pn, a.ob, sh, moved, wr));
        };
        double turn = 0;
        if (armClear && depth.applyAsDouble(0) > 0) {
            double best = Double.MAX_VALUE;
            boolean found = false;
            for (double side : new double[] {-1, 1}) {
                double previous = 0;
                for (double mag = CLEAR_STEP; mag <= CLEAR_MAX + 1e-9; mag += CLEAR_STEP) {
                    double d = depth.applyAsDouble(side * mag);
                    if (d <= 0) {
                        double lo = previous, hi = mag;
                        for (int i = 0; i < 5; i++) {
                            double mid = (lo + hi) / 2;
                            if (depth.applyAsDouble(side * mid) <= 0) hi = mid; else lo = mid;
                        }
                        turn = side * hi;
                        found = true;
                        break;
                    }
                    if (d < best) { best = d; turn = side * mag; }
                    previous = mag;
                }
                if (found) break;
            }
        }
        if (turn != 0) elbow = add(a.S, rotateVec(axis, turn, sub(elbow, a.S)));
        double[] fore = a.F0.clone();
        setPos(fore, elbow);
        limitWrist(fore, hand);
        double[] f = normalize(sub(wrist, elbow)), rotation = unitRotation(hand);
        double fx = rotation[0] * f[0] + rotation[3] * f[1] + rotation[6] * f[2];
        double fy = rotation[1] * f[0] + rotation[4] * f[1] + rotation[7] * f[2];
        double fz = rotation[2] * f[0] + rotation[5] * f[1] + rotation[8] * f[2];
        e[1] = Math.toDegrees(Math.atan2(fz, fx));
        e[2] = Math.toDegrees(Math.atan2(fy, fx));
        return e;
    }

    static double holdTry(double[] T, Hold h, double s, double phi, double psi, HoldArm a, double reach, double pref, double[] spec, double[] prev,
                          double[][] mags, boolean clearance, double stopAt) {
        double[] e = holdEval(T, a, reach);
        if (e == null) {
            return Double.NaN;
        }
        double penalty = 0;
        if (mags != null && mags.length > 0) {
            penalty = HOLD_K_MAG * sq(Math.max(0, magDepth(T, mags) / CM - MAG_TOL_CM));
        }
        double diag = holdDiag(T, h.frame);
        if (clearance) {
            // Every wrist cost is nonnegative. This exact lower bound avoids a clearance solve
            // once the unchanged non-wrist terms already exceed the best complete cost.
            double[] lower = e.clone(); lower[1] = lower[2] = 0;
            if (holdCost(lower, s, pref, phi, psi, diag, spec, prev) + penalty >= stopAt) return Double.POSITIVE_INFINITY;
            e = holdClearanceEval(T, a, e);
        }
        return holdCost(e, s, pref, phi, psi, diag, spec, prev) + penalty;
    }

    /**
     * grip_targets.magazine_depth: how deep the palm proxy of a hand T (Prop1) lies inside the magazines (model units; <= 0
     * outside) - in the slice at its place along the bore, the least distance to the slice box's sides. A magazine: the Prop1
     * -> part transform (12), the first slice's y, then x0, x1, z0, z1 per slice (all zero: empty).
     */
    static double magDepth(double[] T, double[][] mags) {
        double worst = Double.NEGATIVE_INFINITY;
        for (double[] m : mags) {
            int n = (m.length - 13) / 4;
            for (double[] p : HOLD_PALM) {
                double[] w = point(T, p[0] * CM, p[1] * CM, p[2] * CM);
                double x = m[0] * w[0] + m[1] * w[1] + m[2] * w[2] + m[3];
                double y = m[4] * w[0] + m[5] * w[1] + m[6] * w[2] + m[7];
                double z = m[8] * w[0] + m[9] * w[1] + m[10] * w[2] + m[11];
                int k = (int) Math.floor((y - m[12]) / MAG_SLICE + .5);
                if (k < 0 || k >= n) {
                    continue;
                }
                int b = 13 + 4 * k;
                if (m[b] == 0 && m[b + 1] == 0 && m[b + 2] == 0 && m[b + 3] == 0) {
                    continue;
                }
                worst = Math.max(worst, Math.min(Math.min(x - m[b], m[b + 1] - x), Math.min(z - m[b + 2], m[b + 3] - z)));
            }
        }
        return worst;
    }

    /** The 'mags' field of TW/GripTargets (one number list per installed magazine), or null when absent or malformed. */
    static double[][] mags(Object o) {
        if (!(o instanceof KahluaTable t) || t.len() == 0) {
            return null;
        }
        double[][] out = new double[t.len()][];
        for (int i = 0; i < out.length; i++) {
            if (!(t.rawget(i + 1) instanceof KahluaTable m) || m.len() < 17 || (m.len() - 13) % 4 != 0) {
                return null;
            }
            out[i] = numbers(m, m.len());
            if (out[i] == null) {
                return null;
            }
        }
        return out;
    }

    /** The elbow direction of a swivel at the hand T (the IK pole). */
    static double[] holdPole(HoldArm a, double[] T, double omega) {
        double[] W = pos(mulNew(a.Pn, T));
        double[] axis = normalize(sub(W, a.S)), es = sub(a.E0, a.S);
        double[] u = normalize(sub(es, scale(axis, dot(es, axis)))), v = cross(axis, u);
        double c = Math.cos(Math.toRadians(omega)), s = Math.sin(Math.toRadians(omega));
        return add(scale(u, c), scale(v, s));
    }

    /** The Finger1 origin in the hand when the skeleton has none to read (grip_targets.reference('bare').knuckle, model units). */
    static final double[] HOLD_T1 = {14.551 * CM, 0, 0};

    /**
     * One frame of the chosen hold (apply): the placed base cached per request, a refit at most every HOLD_FIT_NS while the
     * arm moves on the weapon (the last parameters held), the chosen hold placed once and carried by the weapon; target
     * (world) is set in place. Returns the IK pole of its swivel, or null when nothing is in reach (the slid target stays).
     * FB7: a change only when clearly cheaper, blended from the shown hand, the pole followed on the weapon; moving (the
     * character walks or runs): refits by the arm averaged over a cycle (HOLD_MOVE_*).
     */
    static double[] holdStep(Request r, SkinningData sd, AnimatorsBoneTransform[] bt, Matrix4f tmp, double[][] model, int[] chain, boolean female,
                             int lh, int fa, double[] Pn, double[] UA, double[] FA, double[] H, double[] target, double[] q, long t0, boolean moving) {
        long h0 = System.nanoTime();
        try {
            int lf1 = index(sd, "Bip01_L_Finger1");
            double[] t1 = HOLD_T1;
            if (lf1 >= 0 && lf1 < bt.length && parent(sd, lf1) == lh) {
                bt[lf1].getMatrix(tmp);
                t1 = holdFinger(pos(read(tmp, new double[16])));
            }
            if (r.holdBase == null) { // the Lua target: A carries the reference hold onto it
                double[] A = {q[3], q[4], q[5], q[6], q[7], q[8], q[9], q[10], q[11], q[12], q[13], q[14], 0, 0, 0, 1};
                double[] ref = {q[51], q[52], q[53], q[15], q[54], q[55], q[56], q[16], q[57], q[58], q[59], q[17], 0, 0, 0, 1};
                r.holdBase = holdBase(r.hold, r.wrap, mulNew(A, ref), t1);
            }
            HoldArm a = new HoldArm();
            a.Pn = Pn;
            a.ob = r.obstacle;
            a.S = pos(UA);
            a.E0 = pos(FA);
            a.W0 = pos(H);
            a.F0 = FA;
            a.l1 = dist(a.S, a.E0);
            a.l2 = dist(a.W0, a.E0);
            boolean body = chain[0] >= 0 && chain[1] >= 0 && chain[2] >= 0 && chain[3] >= 0;
            a.model = body ? model : null;
            a.chain = chain;
            a.torso = female ? TORSO_F : TORSO_M;
            a.r0 = bindWrist(sd, fa, lh);
            a.axis = a.r0 == null ? null : bindAxis(sd, fa, lh);
            boolean fresh = r.holdAt != 0 && t0 - r.holdAt < HOLD_RESET_NS;
            double dt = fresh ? (t0 - r.holdAt) / 1e9 : 0;
            double[] arm = holdArm(a);
            // FB7: while the character moves, the arm averaged over a run cycle decides a refit
            if (!moving || !fresh || r.holdArmAvg == null) {
                r.holdArmAvg = arm.clone();
            } else {
                double k = 1 - Math.exp(-dt / HOLD_MOVE_TAU_S);
                for (int i = 0; i < arm.length; i++) {
                    r.holdArmAvg[i] += (arm[i] - r.holdArmAvg[i]) * k;
                }
            }
            double[] armTest = moving ? r.holdArmAvg.clone() : arm;
            if (!moving || !fresh) {
                r.holdSnaps.clear();
            } else if (t0 - r.holdSnapAt >= HOLD_SNAP_NS) { // FB7: about a cycle of arms to cost a change on
                r.holdSnaps.addLast(holdSnap(a));
                r.holdSnapAt = t0;
                while (r.holdSnaps.size() > HOLD_SNAPS) {
                    r.holdSnaps.removeFirst();
                }
            }
            long fitNs = moving ? HOLD_MOVE_FIT_NS : HOLD_FIT_NS;
            boolean invalidHeld = r.holdT != null && !holdContactValid(r.holdT, r.hold, r.wrap);
            boolean due = !fresh || r.holdPrev == null || invalidHeld
                    || t0 - r.holdFitAt >= fitNs && (t0 - r.holdFitAt >= HOLD_REFIT_MAX_NS || holdMoved(r.holdArm, armTest));
            // holdFix: after a slow fit the held (even a floating) hold rides on the weapon a while; with nothing held it fits
            if (due && fresh && r.holdPrev != null && holdDutyHolds(System.nanoTime(), r.holdFitDoneAt, r.holdFitCostNs)) {
                due = false;
                holdDutyWaits++;
            }
            if (due) {
                HoldArm fit = moving ? holdMeanArm(a, armTest) : a; // FB7: moving, the hold of the cycle's mean arm
                long f0 = System.nanoTime();
                double[] best = holdSearch(r.hold, r.wrap, r.holdBase, fit, fresh ? r.holdPrev : null, t1, r.mags);
                r.holdFitDoneAt = System.nanoTime();
                r.holdFitCostNs = r.holdFitDoneAt - f0;
                holdFitMaxNs = Math.max(holdFitMaxNs, r.holdFitCostNs);
                r.holdFitAt = t0;
                r.holdArm = armTest;
                holdFits++;
                // FB7: the held choice stays unless the new one is clearly cheaper than the held one is now
                boolean same = best != null && r.holdPrev != null && best[0] == r.holdPrev[0] && best[1] == r.holdPrev[1] && best[2] == r.holdPrev[2];
                boolean held = best != null && fresh && r.holdPrev != null && r.holdT != null && !invalidHeld;
                int better = !held || same ? 0 : holdBetter(best, r, fit, moving ? r.holdSnaps : null, t1);
                boolean keep = held && better == 0;
                if (keep && !same) {
                    holdKeeps++;
                }
                // nothing in reach: the last hold stays while the arm still reaches it (no flicker), else the fixed hold
                if (!keep && (best != null || !fresh || r.holdT == null || invalidHeld || holdEval(r.holdT, a, a.l1 + a.l2) == null)) {
                    r.holdFrom = fresh && best != null && r.holdShown != null && !invalidHeld
                            ? r.holdShown : null; // FB7: blend from the shown hand, never an invalid cached hold
                    r.holdBlendAt = t0;
                    r.holdPrev = best;
                    r.holdT = null;
                }
            }
            r.holdAt = t0;
            if (r.holdPrev == null) {
                r.holdLast = null;
                r.holdShown = null;
                r.holdFrom = null;
                r.holdPoleW = null;
                return null;
            }
            if (r.holdT == null) { // placed once per choice: the weapon frame carries it
                r.holdT = holdPlace(holdCandidate(r.holdBase[0], r.hold.frame, r.holdBase[1], r.holdPrev[0], r.holdPrev[1], r.holdPrev[2]), r.hold, r.wrap, t1);
                if (r.holdFrom != null) {
                    r.holdBlendS = holdBlendSeconds(r.holdFrom, r.holdT);
                    holdBlends++;
                }
            }
            double[] T = r.holdT;
            if (r.holdFrom != null) { // FB7: from the hand shown before the change, on the weapon
                double u = (t0 - r.holdBlendAt) / 1e9 / r.holdBlendS;
                if (u >= 1) {
                    r.holdFrom = null;
                } else {
                    T = holdBlend(r.holdFrom, r.holdT, smooth(u));
                }
            }
            double[] e = holdEval(T, a, Double.POSITIVE_INFINITY);
            if (e == null) {
                r.holdLast = null;
                r.holdShown = null;
                r.holdPoleW = null;
                return null;
            }
            double[] nt = mulNew(Pn, T);
            setRotation(target, unitRotation(nt), columnScales(target));
            setPos(target, pos(nt));
            r.holdLast = e;
            r.holdShown = T;
            // FB7: the pole on the weapon, followed and rate-limited (the swivel's basis flips with the clip's elbow)
            double[] pw = holdToWeapon(Pn, holdPole(a, T, e[0]));
            r.holdPoleW = holdPoleFollow(fresh ? r.holdPoleW : null, pw, dt, moving ? HOLD_POLE_MOVE_RATE_DEG_S : HOLD_POLE_RATE_DEG_S);
            holdFrames++;
            return holdFromWeapon(Pn, r.holdPoleW);
        } finally {
            holdNanos += System.nanoTime() - h0;
        }
    }

    /** FB7: the arm averaged on the weapon (holdArm numbers, Prop1) as an arm on the weapon now - the forearm's frame turned
     *  onto the mean forearm, the bones' lengths, the torso and the bind wrist as they are. */
    static HoldArm holdMeanArm(HoldArm a, double[] avg) {
        HoldArm m = new HoldArm();
        m.Pn = a.Pn;
        m.S = point(a.Pn, avg[0], avg[1], avg[2]);
        m.E0 = point(a.Pn, avg[3], avg[4], avg[5]);
        m.W0 = point(a.Pn, avg[6], avg[7], avg[8]);
        double[] F = new double[16];
        rotate(a.F0, rotationBetween(sub(a.W0, a.E0), sub(m.W0, m.E0)), F);
        setPos(F, m.E0);
        m.F0 = F;
        m.l1 = a.l1;
        m.l2 = a.l2;
        m.model = a.model;
        m.chain = a.chain;
        m.torso = a.torso;
        m.r0 = a.r0;
        m.axis = a.axis;
        m.ob = a.ob;
        return m;
    }

    /** FB7: how a new choice {s, phi, psi, cost} compares with the held one: 0 not HOLD_HYST cheaper, 1 cheaper, 2 the held
     *  one lacks surface contact or is out of reach even straight (as the fit keeps a contacting hold while the straight arm reaches it). With
     *  snaps (moving) both are costed on every stored arm and averaged (out of reach there: HOLD_SNAP_MISS); else on this arm
     *  (the held one beyond the elbow's HOLD_ELBOW: costed with the arm straight). */
    static int holdBetter(double[] best, Request r, HoldArm a, java.util.ArrayDeque<HoldArm> snaps, double[] t1) {
        if (!holdContactValid(r.holdT, r.hold, r.wrap)) return 2;
        double[] spec = HOLD_SPEC[r.hold.kind - 1];
        double pref = r.holdBase[2][2];
        boolean clearance = holdClearanceCost(best) || holdClearanceCost(r.holdPrev);
        double cur = holdTry(r.holdT, r.hold, r.holdPrev[0], r.holdPrev[1], r.holdPrev[2], a, holdReach(a), pref, spec, r.holdPrev, r.mags,
                clearance, Double.POSITIVE_INFINITY);
        if (Double.isNaN(cur)) {
            cur = holdTry(r.holdT, r.hold, r.holdPrev[0], r.holdPrev[1], r.holdPrev[2], a, a.l1 + a.l2, pref, spec, r.holdPrev, r.mags,
                    clearance, Double.POSITIVE_INFINITY);
            if (Double.isNaN(cur)) {
                return 2;
            }
        }
        double now = best[3];
        double[] B = null;
        if (clearance) {
            B = holdPlace(holdCandidate(r.holdBase[0], r.hold.frame, r.holdBase[1], best[0], best[1], best[2]), r.hold, r.wrap, t1);
            now = holdTry(B, r.hold, best[0], best[1], best[2], a, holdReach(a), pref, spec, r.holdPrev, r.mags,
                    true, Double.POSITIVE_INFINITY);
            if (Double.isNaN(now)) now = holdTry(B, r.hold, best[0], best[1], best[2], a, a.l1 + a.l2, pref, spec, r.holdPrev, r.mags,
                    true, Double.POSITIVE_INFINITY);
            if (Double.isNaN(now)) return 0;
        }
        if (snaps != null && !snaps.isEmpty()) {
            if (B == null) B = holdPlace(holdCandidate(r.holdBase[0], r.hold.frame, r.holdBase[1], best[0], best[1], best[2]), r.hold, r.wrap, t1);
            cur = now = 0;
            for (HoldArm sa : snaps) {
                double reach = holdReach(sa);
                double c = holdTry(r.holdT, r.hold, r.holdPrev[0], r.holdPrev[1], r.holdPrev[2], sa, reach, pref, spec, r.holdPrev, r.mags,
                        clearance, Double.POSITIVE_INFINITY);
                double n = holdTry(B, r.hold, best[0], best[1], best[2], sa, reach, pref, spec, r.holdPrev, r.mags,
                        clearance, Double.POSITIVE_INFINITY);
                cur += (Double.isNaN(c) ? HOLD_SNAP_MISS : c) / snaps.size();
                now += (Double.isNaN(n) ? HOLD_SNAP_MISS : n) / snaps.size();
            }
        }
        return now < cur - HOLD_HYST ? 1 : 0;
    }

    static double holdReach(HoldArm a) {
        double elbow = a.ob != null ? OB_ELBOW : HOLD_ELBOW;
        return Math.sqrt(a.l1 * a.l1 + a.l2 * a.l2 - 2 * a.l1 * a.l2 * Math.cos(elbow));
    }

    /** FB7: a stored arm (moving): its own weapon, points, forearm frame and the four spine bones it was costed against. */
    static HoldArm holdSnap(HoldArm a) {
        HoldArm m = new HoldArm();
        m.Pn = a.Pn.clone();
        m.S = a.S.clone();
        m.E0 = a.E0.clone();
        m.W0 = a.W0.clone();
        m.F0 = a.F0.clone();
        m.l1 = a.l1;
        m.l2 = a.l2;
        if (a.model != null) {
            m.model = new double[4][];
            for (int i = 0; i < 4; i++) {
                m.model[i] = a.model[a.chain[i]].clone();
            }
            m.chain = new int[] {0, 1, 2, 3};
        }
        m.torso = a.torso;
        m.r0 = a.r0;
        m.axis = a.axis;
        m.ob = a.ob;
        return m;
    }

    /** FB7: the length of a change from hand A to hand B (Prop1): HOLD_BLEND_S, longer for a turn beyond HOLD_RATE_DEG_S over it. */
    static double holdBlendSeconds(double[] A, double[] B) {
        double[] ra = unitRotation(A), rb = unitRotation(B);
        double tr = 0;
        for (int i = 0; i < 9; i++) {
            tr += ra[i] * rb[i];
        }
        double turn = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (tr - 1) / 2))));
        return Math.max(HOLD_BLEND_S, turn / HOLD_RATE_DEG_S);
    }

    /** FB7: hand A to hand B at u (0..1) on the weapon: the position lerped, the rotation slerped. */
    static double[] holdBlend(double[] A, double[] B, double u) {
        double[] T = B.clone();
        setPos(T, lerp(pos(A), pos(B), u));
        setRotation(T, slerp(unitRotation(A), unitRotation(B), u), columnScales(B));
        return T;
    }

    /** FB7: the pole direction (unit, on the weapon) followed from prev towards cur over HOLD_POLE_TAU_S, turning at most
     *  rate degrees a second; prev null or no time: cur. */
    static double[] holdPoleFollow(double[] prev, double[] cur, double dt, double rate) {
        if (prev == null || dt <= 0) {
            return cur;
        }
        double angle = Math.acos(Math.max(-1, Math.min(1, dot(prev, cur))));
        if (angle < 1e-9) {
            return cur;
        }
        double step = Math.min(angle * (1 - Math.exp(-dt / HOLD_POLE_TAU_S)), Math.toRadians(rate) * dt);
        double[] axis = cross(prev, cur);
        if (norm(axis) < 1e-9) { // opposite: any axis across
            axis = cross(prev, Math.abs(prev[0]) < .8 ? new double[] {1, 0, 0} : new double[] {0, 1, 0});
        }
        axis = normalize(axis);
        double c = Math.cos(step), sn = Math.sin(step);
        double[] ax = cross(axis, prev);
        return normalize(add(add(scale(prev, c), scale(ax, sn)), scale(axis, dot(axis, prev) * (1 - c))));
    }

    /** A direction from the model into the weapon's (Prop1) frame and back (the rotation only, scale removed). */
    static double[] holdToWeapon(double[] Pn, double[] v) {
        double[] r = unitRotation(Pn);
        return normalize(new double[] {r[0] * v[0] + r[3] * v[1] + r[6] * v[2], r[1] * v[0] + r[4] * v[1] + r[7] * v[2], r[2] * v[0] + r[5] * v[1] + r[8] * v[2]});
    }

    static double[] holdFromWeapon(double[] Pn, double[] v) {
        double[] r = unitRotation(Pn);
        return normalize(new double[] {r[0] * v[0] + r[1] * v[1] + r[2] * v[2], r[3] * v[0] + r[4] * v[1] + r[5] * v[2], r[6] * v[0] + r[7] * v[1] + r[8] * v[2]});
    }

    /** The arm on the weapon (what moves between refits): the shoulder and the clip's elbow and wrist in Prop1, model units. */
    static double[] holdArm(HoldArm a) {
        double[] inv = inverse(a.Pn), out = new double[9];
        double[][] at = {a.S, a.E0, a.W0};
        for (int i = 0; i < 3; i++) {
            System.arraycopy(point(inv, at[i][0], at[i][1], at[i][2]), 0, out, 3 * i, 3);
        }
        return out;
    }

    static boolean holdMoved(double[] was, double[] now) {
        if (was == null) {
            return true;
        }
        for (int i = 0; i < 9; i += 3) {
            double dx = now[i] - was[i], dy = now[i + 1] - was[i + 1], dz = now[i + 2] - was[i + 2];
            if (dx * dx + dy * dy + dz * dz > HOLD_REFIT_CM * CM * HOLD_REFIT_CM * CM) {
                return true;
            }
        }
        return false;
    }

    static final ConcurrentHashMap<SkinningData, double[]> BIND_WRIST = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<SkinningData, double[]> BIND_AXIS = new ConcurrentHashMap<>();
    /**
     * Low ready with a bare handguard (param 61, grip_targets.LOW_*): the support hand moves back
     * along the bore by param 61 (model units) - to the start of the handguard - while the clip
     * weapon's bore points LOW_FROM..LOW_TO below the horizontal (user 2026-10-15: "the previous
     * low ready, but the hands at the very start of the handguard"). At 60 % of the AK's
     * handguard PZ's shorter arm hung straight beside the weapon; the clips held it at the start.
     */
    static final double LOW_FROM = Math.toRadians(30), LOW_TO = Math.toRadians(40);
    /**
     * Low ready (param 62: every rifle; a bare handguard's target is already at its start, LOW_*),
     * grip_targets.LOW_COMFORT..LOW_POCKET_FADE, lowLift): the hand cannot slide off the part, so the
     * weapon comes to the hand. While the support target lies beyond LOW_COMFORT of the arm's reach
     * and beyond what the reference weapon's own hold needs in that frame, the weapon and the right
     * hand turn about the right wrist, muzzle up, by the smallest angle that brings it within (at
     * most LOW_RAISE_MAX), then move back along the bore (at most LOW_PULL_MAX); weighted like LOW_*,
     * smoothed over LOW_LIFT_TAU_S. A lifted weapon leaves the shoulder pocket over the first
     * LOW_POCKET_FADE of the raise. Before, the target slid back along the bore - off the grip by up
     * to 8.8 cm (69 of 278 assemblies with a front support; user 2026-10-16: "the hand cannot just
     * move there").
     */
    static final double LOW_COMFORT = 0.95, LOW_RAISE_MAX = Math.toRadians(30), LOW_PULL_MAX = 10 * CM, LOW_RAISE_STEP = Math.toRadians(1);
    static final double LOW_POCKET_FADE = Math.toRadians(6), LOW_LIFT_TAU_S = 0.15;
    public static volatile long lowLifts;
    /** QA: the low-ready lift on or off (before/after sheets in one world). */
    public static volatile boolean lowLift = true;
    public static volatile double maxLowRaiseDeg;
    /**
     * Bare handguard (param 64, FB2-D; user 2026-10-17: "move the hands further along a bare handguard if
     * the limb allows"): low ready moves the hold back along it only as far as LOW_COMFORT of the reach needs
     * (at most param 61, to the start of its grip span - before always to the start), and an out-of-reach
     * hold slides back to ELBOW_MAX's reach (the support elbow stood at 171-180 degrees). Past the start of
     * the grip span it slides only when even that is out of reach (long pumps in aim: the hand stays on the
     * weapon rather than short of it). grip_targets.ELBOW_MAX, comfort_reach.
     */
    static final double ELBOW_MAX = Math.toRadians(160);
    static final int PARAMS = 64;
    /** The bare-handguard reference grip class (grip_targets.REFS['bare']). */
    public static final String BARE_CLASS = "TW_Contact_P101";

    static final class Request {
        final Object item;
        final KahluaTable source;
        final double[] p;
        volatile long stamp = System.nanoTime();
        /** The world frame of the last renewal: a long frame (a screenshot, a hitch) does not age it (FB2-D). */
        volatile long stampFrame = FpRecoil.worldFrame();
        /** A timed action runs (reload, rack, equip): the contact window releases the hand; else it stays. */
        volatile boolean acting = true;
        /** When a timed action last ran, and the smoothed lock weight with its clock. */
        volatile long actingAt = System.nanoTime();
        double lock;
        long lockAt;
        /** FB4-A: the action weight of the contact and lock windows (ACT_TAU_S) and its clock. */
        double actW = 1;
        long actWAt;
        /** Low-passed support target on the weapon (HOLD_TAU_S) and when it was updated. */
        double[] held;
        long heldAt;
        /** The support wrist's anatomical twist (ArmNatural.tau, radians, followed from frame to frame): the relief's reference. */
        double twist;
        long twistAt;
        double relief;
        long reliefAt;
        /** The left elbow's clearance turn (radians, smoothed), the side it took and when it was updated. */
        double elbowTurn, elbowSide;
        long elbowAt;
        /** Low ready on a front support: the weapon's smoothed raise (radians) and pull back (model units). */
        double liftRaise, liftPull;
        long liftAt;
        /** Arms and weapon relative to the chest before a turn, the turn weight and its clock. */
        double[][] turnHold;
        double turnW;
        long turnAt;
        /** FB2-E: the support's cross-section (TW/GripTargets 'wrap') and the finger fits' angles and clocks (this pass, first person). */
        final Wrap wrap;
        double[] wrapState = new double[WRAP_STATE], fpWrapState = new double[WRAP_STATE];
        /** Pistol in both hands: the handgun class's fingers round its grip (HG_*; isometric / third person), both hands'
         *  shifts on the grip (left, right; Prop space, model units), the shooting hand's weight and its clock, and the
         *  weights applied last frame (QA). */
        final double[][] hg, hgShift;
        double hgRight = 1;
        long hgRightAt;
        final double[] hgW = new double[2];
        /** FB2-N: the grip surface (TW/GripTargets 'hold'; wrap lies in its frame), the placed base {L, c0, slide}, the chosen
         *  {s, phi, psi, cost} and its placed hand (Prop1), the last {omega, ext, dev, depth, twist}, the arm on the weapon at the
         *  last fit and the clocks. */
        final Hold hold;
        /** The installed magazines (TW/GripTargets 'mags'): the hold's palm kept out of them (HOLD_K_MAG). */
        final double[][] mags;
        /** FB8: the installed belt box (GripIK.obstacle), the support arm's obstacle, or null; the arm's last depth in it. */
        final double[] obstacle;
        double obLast = Double.NaN;
        double[][] holdBase;
        double[] holdPrev, holdT, holdLast, holdArm;
        long holdAt, holdFitAt;
        /** holdFix: when the last fit ended (System.nanoTime) and how long it took. */
        long holdFitDoneAt, holdFitCostNs;
        /** FB7: the hand shown last frame (Prop1), the hand a change blends from, its start and length (s), the followed elbow
         *  pole on the weapon (unit; null: none) and the arm averaged while moving. */
        double[] holdShown, holdFrom, holdArmAvg;
        long holdBlendAt;
        double holdBlendS;
        double[] holdPoleW;
        /** FB7: the arms stored while moving (HOLD_SNAP_NS apart, the last HOLD_SNAPS) and when the last was stored. */
        final java.util.ArrayDeque<HoldArm> holdSnaps = new java.util.ArrayDeque<>();
        long holdSnapAt;
        /** W10 pistolgrip: the world frame clear() released the request in (it ends with that frame unless set() takes it back). */
        volatile long releasedFrame;

        Request(Object item, KahluaTable source, double[] p) {
            this.item = item;
            this.source = source;
            this.p = p;
            this.wrap = source == null ? null : wrap(source.rawget("wrap"));
            this.hg = source == null ? null : handgunFingers(source.rawget("fingers"));
            this.hgShift = source == null ? null : handgunRows(source.rawget("shift"), 2, 3);
            this.hold = source == null ? null : hold(source.rawget("hold"));
            this.mags = source == null ? null : mags(source.rawget("mags"));
            this.obstacle = source == null ? null : obstacle(source.rawget("obstacle"));
        }
    }

    /** FB8: TW/GripTargets 'obstacle' - {lo xyz, hi xyz} of an installed belt box on the weapon (Prop1, model units), or null. */
    static double[] obstacle(Object value) {
        if (!(value instanceof KahluaTable t) || t.len() != 6) {
            return null;
        }
        double[] out = new double[6];
        for (int i = 0; i < 6; i++) {
            if (!(t.rawget(i + 1) instanceof Number n) || !Double.isFinite(n.doubleValue())) {
                return null;
            }
            out[i] = n.doubleValue();
        }
        return out[0] <= out[3] && out[1] <= out[4] && out[2] <= out[5] ? out : null;
    }

    static final ConcurrentHashMap<IsoGameCharacter, Request> requests = new ConcurrentHashMap<>();
    /**
     * Magazine visuals of a reload: {mode, ...}. Mode 1 {1, own frame (12: 3x4 rows)}: the hand
     * model bone Bip01_Prop2 (a root child in the PZ skeleton) is placed on the weapon's magazine
     * frame, so a magazine shown in the support hand covers the one on the weapon exactly.
     * Mode 2 {2, own frame (12), reference frame (12), grip (3), slap (3)[, grip shift (3), slap
     * shift (3)[, carry push]]} - the reload clips
     * (tools/reload_clips.py): their Bip01_Prop2 carries the magazine of the reference weapon;
     * near the reference well it moves onto this weapon's well (MAG_NEAR..MAG_FAR), elsewhere
     * (pouch) it stays where the clip has it. The support hand keeps the clip's hold on the
     * magazine while the clip hand is at its grip or slap point (MAG_HOLD_NEAR..MAG_HOLD_FAR),
     * shifted on the magazine by the grip / slap shift (this weapon's magazine is another size).
     * Away from the well a magazine larger than the reference one (an ammo box, a drum) is carried
     * the carry push further ahead (model -Z), the hand with it, so it stays clear of the body.
     * Mode 3 {3, own handle (3), reference handle (3), reference wrist on it (3), pulled (3)} - the
     * rack clips (tools/reload_clips.py, one per reference class, sex and handle style; weapon
     * space, model units): the clip's hand goes onto this weapon's own charging handle by how much
     * nearer it is to its path on the reference handle than to the hold (user 2026-10-15: "reaches
     * to the realistic place of the bolt for each weapon and pulls it"). A fixed distance window
     * let go of the handle too early: an MP5's hand went 13 cm back to the reference handle before
     * it came forward to the hold (QA trace).
     * Mode 3 with a right-hand rack {3, own (3), reference (3), wrist on it (3), pulled (3), 1,
     * reference grip wrist (3)} - Tarkov's AK charge (tools/reload_clips.py TARKOV_RACK): the right
     * hand leaves the pistol grip for the handle, so the same weight moves the clip's right wrist,
     * from its grip, onto this weapon's own handle; the support hand keeps its hold.
     */
    static final ConcurrentHashMap<IsoGameCharacter, double[]> mags = new ConcurrentHashMap<>();
    public static volatile long magPlaced, magHeld, racked;
    static final double MAG_NEAR = 2 * CM, MAG_FAR = 12 * CM, MAG_HOLD_NEAR = 1 * CM, MAG_HOLD_FAR = 8 * CM;
    /** Expected left wrist in weapon space after the transfer, checked on the engine's model transforms. */
    static final ConcurrentHashMap<AnimationPlayer, double[]> expected = new ConcurrentHashMap<>();
    /**
     * W10 torchpistol (item 29): one-handed prop attachments {handIdx, propIdx, parIdx, weight, T0..T15}
     * where T = H_blend^-1 * P_blend. Bip01_Prop1 and Bip01_Prop2 are children of Bip01 (not Bip01_Neck),
     * while Viewpoint's 3P HeadLook and ArmsPitch rotate the Bip01_Neck subtree (including both clavicles and
     * hands) in modelTransforms after GripIK.apply. Re-applying H_posed * T on entry to onModel keeps both
     * one-handed props glued to their hands across every Viewpoint 3P yaw/pitch and PanicSway.
     */
    static final ConcurrentHashMap<AnimationPlayer, double[][]> oneHandExpected = new ConcurrentHashMap<>();
    public static volatile boolean enabled = true;
    public static volatile long calls, applied, nanos, checks, rejStale, rejItem, rejSkeleton, clavicles, slides, butts, wrists, elbows, highs;
    public static volatile double maxContactUnits, sumContactUnits, maxFkUnits, lastWeight, maxResidualUnits, maxButtUnits, lastElevationDeg, lastHigh;
    public static volatile String lastError = "", skeleton = "";
    /** QA trace per character: transfer inputs and the left hand on the weapon as the engine applied it. */
    public static volatile boolean tracing;
    static final ConcurrentHashMap<IsoGameCharacter, double[]> traces = new ConcurrentHashMap<>();
    static final int T_FRAMES = 0, T_W = 1, T_HW = 2, T_ELEV = 3, T_APPLIED = 4, T_WHY = 5, T_HAND = 6, T_AXIS = 9, T_MODELS = 12,
            T_GUN = 13, T_LHAND = 16, T_TURN = 19, T_TWIST = 20, T_ELBOW = 22, T_LOCK = 23,
            T_RHAND = 24, T_RCHEST = 27, T_BORE = 30, T_TOP = 33, T_RACK = 36, T_SIZE = 37;

    private static final ThreadLocal<double[][]> MODEL = ThreadLocal.withInitial(() -> new double[96][16]);
    private static final ThreadLocal<Matrix4f> TMP = ThreadLocal.withInitial(Matrix4f::new);

    private GripIK() {
    }

    /**
     * W10 pistolgrip (the user's item 2): the requests clear() released, kept for a re-equip of the same item in the same instant.
     * Gunworks re-equips the weapon on every shot of a chambered weapon (Animations.lockActionOpen from its canShoot and onShoot
     * hooks, releaseActionLock a cycle later: CallSyncHandWeaponFields = setPrimaryHandItem(nil), then the same weapon) and
     * TW_GripIK.lua's OnEquipPrimary answers with clear() and set(): the new Request started with the equip's acting and no
     * clocks, handgunRight let the shooting hand go at once and the pistol slid back under it by its hand shift (2-3.5 cm,
     * every pistol, isometric and third person, QA 28.10.2026). clear() now releases the request at the end of its world
     * frame (expireReleased); set() for the same character and the same item instance within that frame goes on with it.
     * Another item, or a later frame (a real unequip and equip: a timed action, an item swap), is a new request as before.
     */
    static final ConcurrentHashMap<IsoGameCharacter, Request> released = new ConcurrentHashMap<>();
    /** QA (TWGripIKNative): the same-item re-equip kept (on) or the earlier immediate removal (off), for before/after in one world. */
    public static volatile boolean reequipKeep = true;

    public static void reequipKeepOn(boolean on) {
        reequipKeep = on;
    }

    /** Ends the requests clear() released in an earlier world frame (every animation update while any is released). */
    static void expireReleased() {
        long frame = FpRecoil.worldFrame();
        for (java.util.Iterator<java.util.Map.Entry<IsoGameCharacter, Request>> it = released.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<IsoGameCharacter, Request> e = it.next();
            if (e.getValue().releasedFrame != frame) {
                it.remove();
                requests.remove(e.getKey(), e.getValue());
            }
        }
    }

    /** Renew a character's request; the table is copied only when Lua hands over a new one. */
    static void set(IsoGameCharacter c, Object item, KahluaTable t) {
        Request r = requests.get(c);
        if (!released.isEmpty()) { // W10 pistolgrip: a same-item re-equip in the frame clear() released it takes it back
            Request k = released.remove(c);
            if (k != null && (k != r || k.item != item || k.releasedFrame != FpRecoil.worldFrame())) {
                requests.remove(c, k); // not taken back: released as clear() always did
                if (r == k) {
                    r = null;
                }
            }
            expireReleased();
        }
        if (r != null && r.item == item && r.source == t) {
            r.stamp = System.nanoTime();
            r.stampFrame = FpRecoil.worldFrame();
            return;
        }
        double[] p = new double[PARAMS];
        for (int i = 0; i < PARAMS; i++) {
            Object v = t.rawget(i + 1);
            p[i] = v instanceof Double ? (Double) v : 0;
        }
        Request n = new Request(item, t, p);
        if (r != null && r.item == item) {
            // The same weapon with new targets (its assembly changed, e.g. a magazine seated): the
            // smoothing goes on. A fresh request dropped it - an AKM's support hand snapped 7 cm onto
            // its hold in one frame as the rack began (QA trace 2026-10-15).
            n.acting = r.acting;
            n.actingAt = r.actingAt;
            n.lock = r.lock;
            n.lockAt = r.lockAt;
            n.actW = r.actW;
            n.actWAt = r.actWAt;
            n.held = r.held;
            n.heldAt = r.heldAt;
            n.twist = r.twist;
            n.twistAt = r.twistAt;
            n.elbowTurn = r.elbowTurn;
            n.elbowSide = r.elbowSide;
            n.elbowAt = r.elbowAt;
            n.liftRaise = r.liftRaise;
            n.liftPull = r.liftPull;
            n.liftAt = r.liftAt;
            n.turnHold = r.turnHold;
            n.turnW = r.turnW;
            n.turnAt = r.turnAt;
            n.wrapState = r.wrapState;
            n.fpWrapState = r.fpWrapState;
            n.hgRight = r.hgRight;
            n.hgRightAt = r.hgRightAt;
            if (n.hold != null && r.hold != null && n.hold.kind == r.hold.kind) { // FB2-N: refit at once, the last choice held
                n.holdPrev = r.holdPrev;
                n.holdAt = r.holdAt;
                // FB7: the new placement blends from the hand shown (patrol in / out swaps the table), the swivel filter goes on
                n.holdShown = r.holdShown;
                n.holdPoleW = r.holdPoleW;
                n.holdArmAvg = r.holdArmAvg;
                // holdFix: a table swap refits at once, unless the last fit was slow (the duty limit goes on)
                n.holdFitDoneAt = r.holdFitDoneAt;
                n.holdFitCostNs = r.holdFitCostNs;
            }
        }
        requests.put(c, n);
    }

    static void acting(IsoGameCharacter c, boolean on) {
        Request r = requests.get(c);
        if (r != null) {
            long now = System.nanoTime();
            if (on) {
                r.actingAt = now;
            }
            r.acting = on || now - r.actingAt < ACTING_HOLD_NS;
        }
    }

    static void clear(IsoGameCharacter c) {
        Request r = reequipKeep ? requests.get(c) : requests.remove(c);
        if (r != null && reequipKeep) { // W10 pistolgrip: released at the end of this world frame unless set() takes it back (same item)
            r.releasedFrame = FpRecoil.worldFrame();
            released.put(c, r);
        }
        mags.remove(c);
    }

    /** Read-only QA; a trace can retain the last applied frame after all requests end. */
    public static boolean requested(IsoGameCharacter character) { return requests.containsKey(character); }

    static void mag(IsoGameCharacter c, double[] m) {
        if (m == null) {
            mags.remove(c);
        } else {
            mags.put(c, m);
        }
    }

    /** FB4-A: the fire-mode gesture's hand weight (0..1) of a right-hand selector contact with an explicit weight. */
    static void magWeight(IsoGameCharacter c, double w) {
        double[] m = mags.get(c);
        if (m != null && m.length >= 18) {
            m[17] = w;
        }
    }

    public static String stats() {
        return "calls=" + calls + " applied=" + applied + " nanos=" + nanos + " checks=" + checks
                + " maxContactCm=" + cm(maxContactUnits) + " meanContactCm=" + cm(checks > 0 ? sumContactUnits / checks : 0)
                + " maxFkCm=" + cm(maxFkUnits) + " maxResidualCm=" + cm(maxResidualUnits) + " lastWeight=" + lastWeight
                + " butts=" + butts + " maxButtCm=" + cm(maxButtUnits) + " clavicles=" + clavicles + " slides=" + slides + " wrists=" + wrists + " elbows=" + elbows + " highs=" + highs
                + " lastElevationDeg=" + Math.round(lastElevationDeg) + " lastHigh=" + Math.round(lastHigh * 100) / 100.0
                + " requests=" + requests.size() + " rejStale=" + rejStale + " rejItem=" + rejItem + " rejSkeleton=" + rejSkeleton
                + " mags=" + mags.size() + " magPlaced=" + magPlaced + " magHeld=" + magHeld + " racked=" + racked + " turnHolds=" + turnHolds + " locks=" + locks
                + " elbowTurns=" + elbowTurns + " roundReleases=" + roundReleases + " roundProps=" + roundProps
                + " lowLifts=" + lowLifts + " maxLowRaiseDeg=" + Math.round(maxLowRaiseDeg) + " reseats=" + reseats + " fingerFits=" + fingerFits + " wrapFits=" + wrapFits + " hgFits=" + hgFits
                + " holdFits=" + holdFits + " holdFrames=" + holdFrames + " holdNanos=" + holdNanos
                + " holdFix=" + holdFix + " holdSearches=" + holdSearches + " holdRecoveries=" + holdRecoveries + " holdPlacements=" + holdPlacements
                + " holdMemoNew=" + holdMemoNew + " holdMemoShared=" + holdMemoShared + " holdDutyWaits=" + holdDutyWaits
                + " holdFitMaxMs=" + Math.round(holdFitMaxNs / 1e5) / 10.0
                + " holdKeeps=" + holdKeeps + " holdBlends=" + holdBlends
                + " follows=" + follows + " maxFollowCm=" + cm(maxFollowCm * CM)
                + (skeleton.isEmpty() ? "" : " skeleton[" + skeleton + "]");
    }

    public static void reset() {
        calls = applied = nanos = checks = rejStale = rejItem = rejSkeleton = clavicles = slides = butts = wrists = elbows = highs = 0;
        maxContactUnits = sumContactUnits = maxFkUnits = maxResidualUnits = maxButtUnits = 0;
        lastError = "";
        oneHandExpected.clear();
        handPost.clear();
        follows = 0;
        maxFollowCm = 0;
    }

    private static String cm(double units) {
        return String.format(java.util.Locale.ROOT, "%.3f", units / CM);
    }

    @Patch(className = "zombie.core.skinnedmodel.animation.AnimationPlayer", methodName = "applyBoneReParenting")
    public static class AfterBlend {
        @Patch.OnExit
        public static void exit(@Patch.This Object self) {
            GripIK.onBlend(self); // advice code is inlined into AnimationPlayer: call public members only
        }
    }

    /**
     * Bug wave W8, user item 10 (25.10.2026): the mod order must not matter. Viewpoint's advice on the exit of
     * updateModelTransforms (Patch_SkeletonPosed: ArmsPitch turns the arms by the view pitch, then HeadLook) must
     * run BEFORE our layers. That exit is no place for them: ZombieBuddy wraps the advices in mod load order, so
     * with this mod loaded first our exit runs before Viewpoint's and the arms were turned again after the pose.
     * AnimationPlayer.updateAnimation_StandardAnimation calls updateModelTransforms and then, directly,
     * UpdateSkinTransforms (public; javap -c of projectzomboid.jar), and no mod advises UpdateSkinTransforms
     * (Viewpoint 0.1.6.1 patches updateModelTransforms only). Its entry is therefore after every mod's model
     * transforms in either order, and the engine computes the skin from the model transforms afterwards
     * (UpdateSkinTransforms -> resetSkinTransforms; getSkinTransforms reads modelTransforms). The exit of
     * updateModelTransforms only marks this update here; the layers run once, on the entry of the matching
     * UpdateSkinTransforms (modelPosed/onSkin).
     */
    @Patch(className = "zombie.core.skinnedmodel.animation.AnimationPlayer", methodName = "updateModelTransforms")
    public static class AfterModel {
        @Patch.OnExit
        public static void exit(@Patch.This Object self) {
            GripIK.modelPosed(self);
        }
    }

    @Patch(className = "zombie.core.skinnedmodel.animation.AnimationPlayer", methodName = "UpdateSkinTransforms")
    public static class BeforeSkin {
        @Patch.OnEnter
        public static void enter(@Patch.This Object self) {
            GripIK.onSkin(self);
        }
    }

    /**
     * The player this thread has just updated the model transforms of: set at the exit of updateModelTransforms,
     * taken at the entry of the UpdateSkinTransforms of the same update. Per thread because the two calls are
     * adjacent in updateAnimation_StandardAnimation while another thread may animate another character.
     */
    static final ThreadLocal<AnimationPlayer> MODEL_POSED = new ThreadLocal<>();
    /** Surface-selected grip in own Prop1, paired with its elbow direction before Viewpoint/FP posing. */
    record SurfaceGrip(AnimationPlayer owner, Request request, long frame, double[] hand, double[] pole) { }
    static final ThreadLocal<SurfaceGrip> POSED_SURFACE = new ThreadLocal<>();

    static SurfaceGrip surfaceGrip(AnimationPlayer ap, Object item) {
        SurfaceGrip result = POSED_SURFACE.get();
        if (!holdOn || result == null || ap == null || result.owner() != ap) return null;
        IsoGameCharacter character = ap.getIsoGameCharacter();
        Request current = character == null ? null : requests.get(character);
        return current != null && result.request() == current
                && current.item == item && FpPose.freshFrame(result.frame(), FpRecoil.worldFrame()) ? result : null;
    }

    /** updateModelTransforms exit: this update's model transforms are complete (every mod's advice has run). */
    public static void modelPosed(Object self) {
        MODEL_POSED.set((AnimationPlayer) self);
    }

    /**
     * UpdateSkinTransforms entry: our layers of the update whose model transforms were just finished. Without that
     * mark (updateAnimation_SharedSkeleTrack calls UpdateSkinTransforms without updateModelTransforms) the transforms
     * are the shared track's or a stale update's: nothing is posed then.
     */
    public static void onSkin(Object self) {
        AnimationPlayer ap = (AnimationPlayer) self;
        AnimationPlayer posed = MODEL_POSED.get();
        if (posed == null) {
            POSED_SURFACE.remove();
            return;
        }
        MODEL_POSED.set(null); // one update, one application
        if (posed != ap) {
            POSED_SURFACE.remove();
            return;
        }
        try {
            onModel(ap);
        } finally {
            POSED_SURFACE.remove();
        }
    }

    /** W10 prone: orient Bip01_Prop1 in the right hand while a per-character yield (Hardship Inertia) owns the body. */
    public static volatile boolean floorSeat = true;

    public static void onBlend(Object self) {
        POSED_SURFACE.remove(); // a surface pair belongs to this animation update only
        if (!released.isEmpty()) { // W10 pistolgrip: clear()'s requests end with their world frame
            expireReleased();
        }
        if (AnimOptOut.blocks(self)) { // W8 animopt: this view's hand IK belongs to vanilla/other mods
            if (enabled && floorSeat && !requests.isEmpty() && !AnimOptOut.optionsBlock(self)) {
                seatYielded((AnimationPlayer) self);
            }
            return;
        }
        if (enabled && !requests.isEmpty()) {
            apply((AnimationPlayer) self);
        }
    }

    /**
     * W10 prone / proneback (Hardship Inertia compatibility):
     *   - On prone, all-fours, crawling and front/fours transitions (supine == 0), unarmed floor clips leave
     *     Bip01_Prop1 in the skeleton's melee-bat bind pose relative to Bip01_R_Hand (+Y_Prop1 along +Y_RHand
     *     thumb instead of +X_RHand index finger). Orient and seat ONLY Bip01_Prop1 in Bip01_R_Hand using
     *     Hardship's own HI_Prone_IdleAimRifle / HI_Prone_IdleAimHandgun hand-to-prop offsets ([7.815, 4.591,
     *     4.856] cm / [5.357, 4.979, 3.247] cm) plus the weapon's own grip shift dR, preserving authored firearm
     *     clips (HI_Prone_IdleAim*, HI_Prone_Attack*, HI_Prone_Reload*) and touching zero body/arm bones.
     *   - Lying on the back (supine > 0: Bob_ScrambleFloorBackIdle, Bob_ScrambleFloorBack, HI_BackIdleRot,
     *     HI_BackLand, HI_FenceLand, HI_ScrambleBackIn, HI_BackBiteReact, Zombie_Idle_FloorOnBack and supine
     *     roll/rise transitions while TWHIFloor is true), the unarmed clips place Bip01_R_Hand on the floor
     *     behind the right hip, which left the firearm underneath the right thigh/calf with empty hands. Blend
     *     the weapon onto an upper-chest hold (TORSO_M[2] / TORSO_F[2] sternum + pectoral clearance), solve the
     *     right arm (solveArm + limitWrist + ArmNatural hinged elbow/wrist) onto the weapon's exact Tarkov/EFZF
     *     pistol-grip frame (TW_Contact_P101 / TW_HGCarry_G006 / RSHG2.rightPosition), and close the right
     *     fingers round the grip.
     */
    static final double[][] SUPINE_RIFLE_POS = {
            {-3.0309 * CM, -13.0643 * CM, -11.3157 * CM},
            {-2.4867 * CM, -13.7615 * CM, -10.4673 * CM}};
    static final double[][] SUPINE_RIFLE_ROT = {
            ArmNatural.frame(new double[] {0.095550, 0.994222, 0.048920}, new double[] {0.129100, -0.061107, 0.989747}),
            ArmNatural.frame(new double[] {0.031352, 0.999479, 0.007638}, new double[] {0.075099, -0.009976, 0.997126})};
    static final double[][] SUPINE_HG_POS = {
            {3.1303 * CM, -8.4309 * CM, 7.6207 * CM},
            {3.2670 * CM, -9.2130 * CM, 7.7290 * CM}};
    static final double[][] SUPINE_HG_ROT = {
            ArmNatural.frame(new double[] {-0.029842, 0.999045, -0.031907}, new double[] {-0.093089, -0.034560, -0.995058}),
            ArmNatural.frame(new double[] {-0.017628, 0.995153, -0.096750}, new double[] {-0.096552, -0.098007, -0.990491})};
    static final double[] SUPINE_RSHG2_POS = {-4.5660 * CM, 0.0, -6.2848 * CM};
    static final ConcurrentHashMap<SkinningData, ArmNatural.Bind> RIGHT_BIND = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<AnimationPlayer, SupinePost> supinePost = new ConcurrentHashMap<>();
    /** QA (W11 inertiatp, floorSample): the floor seat's hand and weapon {H_blend, P_seated} of the last front follow. */
    static final ConcurrentHashMap<AnimationPlayer, double[][]> frontPost = new ConcurrentHashMap<>();

    static final class SupinePost {
        final int rc, rua, rfa, rh, prop;
        final double[] RC, UAn, FAn, Hn, Pn;

        SupinePost(int rc, int rua, int rfa, int rh, int prop, double[] RC, double[] UAn, double[] FAn, double[] Hn, double[] Pn) {
            this.rc = rc;
            this.rua = rua;
            this.rfa = rfa;
            this.rh = rh;
            this.prop = prop;
            this.RC = RC;
            this.UAn = UAn;
            this.FAn = FAn;
            this.Hn = Hn;
            this.Pn = Pn;
        }
    }

    static ArmNatural.Bind rightBindOf(SkinningData sd, int rua, int rfa, int rh) {
        ArmNatural.Bind b = RIGHT_BIND.get(sd);
        if (b == null && ArmNatural.hasBind(sd, Math.max(rua, Math.max(rfa, rh)) + 1)) {
            b = ArmNatural.bind(sd, rua, rfa, rh);
            RIGHT_BIND.put(sd, b);
        }
        return b;
    }

    static double[] rot3(double[] r, double[] v) {
        return new double[] {r[0] * v[0] + r[1] * v[1] + r[2] * v[2],
                r[3] * v[0] + r[4] * v[1] + r[5] * v[2],
                r[6] * v[0] + r[7] * v[1] + r[8] * v[2]};
    }

    static boolean isHiFrontPose(String pose, String down) {
        if ("kdfours".equals(down) || "exitfront".equals(down)) {
            return true;
        }
        if (pose == null) {
            return false;
        }
        switch (pose) {
            case "prone":
            case "fours":
            case "s2p":
            case "p2s":
            case "s2f":
            case "f2s":
            case "p2f":
            case "f2p":
            case "p2fm":
            case "f2w":
            case "f2r":
                return true;
            default:
                return false;
        }
    }

    static boolean isHiBackPose(String pose, String down) {
        if ("kdback".equals(down) || "exitback".equals(down)) {
            return true;
        }
        if (pose == null) {
            return false;
        }
        switch (pose) {
            case "back":
            case "bset":
            case "b2r":
            case "fland":
            case "s2b":
            case "b2s":
            case "bk2r":
            case "bk2w":
                return true;
            default:
                return false;
        }
    }

    /** Read-only QA bone & weapon-frame sampler (model units) for W10 floor posture clearance and grip checks. */
    public static String floorSample(IsoGameCharacter character) {
        try {
            AnimationPlayer ap = character == null ? null : character.getAnimationPlayer();
            SkinningData sd = ap == null ? null : ap.getSkinningData();
            if (sd == null || ap.getModelTransformsCount() <= 0) {
                return "";
            }
            int count = ap.getModelTransformsCount();
            StringBuilder s = new StringBuilder(640);
            java.util.Formatter f = new java.util.Formatter(s, java.util.Locale.ROOT);
            String[] bones = {
                    "Bip01_Pelvis", "Bip01_Spine", "Bip01_Spine1", "Bip01_Neck", "Bip01_Head",
                    "Bip01_R_UpperArm", "Bip01_R_Forearm", "Bip01_R_Hand", "Bip01_L_Hand",
                    "Bip01_R_Thigh", "Bip01_R_Calf", "Bip01_R_Foot",
                    "Bip01_L_Thigh", "Bip01_L_Calf", "Bip01_L_Foot", "Bip01_Prop1"};
            double[] m = new double[16];
            for (String name : bones) {
                int i = index(sd, name);
                if (i >= 0 && i < count) {
                    read(ap.getModelTransformAt(i), m);
                    f.format("%s%s=%.5f,%.5f,%.5f", s.length() > 0 ? " " : "", name, m[3], m[7], m[11]);
                }
            }
            int rh = index(sd, "Bip01_R_Hand");
            if (rh >= 0 && rh < count) {
                read(ap.getModelTransformAt(rh), m);
                double[] rx = normalize(new double[] {m[0], m[4], m[8]});
                f.format(" rhx=%.5f,%.5f,%.5f", rx[0], rx[1], rx[2]);
            }
            String[][] segAxes = {{"Bip01_Pelvis", "pel"}, {"Bip01_Spine", "sp0"}, {"Bip01_Spine1", "sp1"}};
            for (String[] pair : segAxes) {
                int bi = index(sd, pair[0]);
                if (bi >= 0 && bi < count) {
                    read(ap.getModelTransformAt(bi), m);
                    double[] ay = normalize(new double[] {m[1], m[5], m[9]});
                    double[] az = normalize(new double[] {m[2], m[6], m[10]});
                    f.format(" %sY=%.5f,%.5f,%.5f %sZ=%.5f,%.5f,%.5f", pair[1], ay[0], ay[1], ay[2], pair[1], az[0], az[1], az[2]);
                }
            }
            int prop = index(sd, "Bip01_Prop1");
            if (prop >= 0 && prop < count) {
                read(ap.getModelTransformAt(prop), m);
                double[] rgt = normalize(new double[] {m[0], m[4], m[8]});
                double[] br = normalize(new double[] {m[1], m[5], m[9]});
                double[] up = normalize(new double[] {m[2], m[6], m[10]});
                f.format(" right=%.5f,%.5f,%.5f bore=%.5f,%.5f,%.5f up=%.5f,%.5f,%.5f",
                        rgt[0], rgt[1], rgt[2], br[0], br[1], br[2], up[0], up[1], up[2]);
            }
            double[][] fp = frontPost.get(ap);
            if (fp != null && rh >= 0 && rh < count && prop >= 0 && prop < count) {
                // W11 inertiatp: the live hand against the hand the live weapon implies by the floor seat's relation
                // (P_now * P_seated^-1 * H_blend), and how far the hand is from the seat's hand (Viewpoint's turn)
                double[] hNow = read(ap.getModelTransformAt(rh), new double[16]);
                double[] pNow = read(ap.getModelTransformAt(prop), new double[16]);
                double[] want = mulNew(pNow, mulNew(inverse(fp[1]), fp[0]));
                f.format(" fpRel=%.5f,%.5f,0", dist(pos(hNow), pos(want)), dist(pos(hNow), pos(fp[0])));
            }
            return s.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    static void seatYielded(AnimationPlayer ap) {
        if (ap.parentPlayer != null) {
            return;
        }
        IsoGameCharacter c = ap.getIsoGameCharacter();
        Request r = c == null ? null : requests.get(c);
        if (r == null) {
            return;
        }
        long t0 = System.nanoTime();
        try {
            if ("InsertBullets".equals(c.getVariableString("PerformingAction"))
                    || "RemoveBullets".equals(c.getVariableString("PerformingAction"))) {
                return;
            }
            if (t0 - r.stamp > STALE_NS && !FpPose.freshFrame(r.stampFrame, FpRecoil.worldFrame())) {
                return;
            }
            if (c.getPrimaryHandItem() != r.item) {
                return;
            }
            SkinningData sd = ap.getSkinningData();
            AnimatorsBoneTransform[] bt = ap.boneTransforms;
            if (sd == null || bt == null) {
                return;
            }
            int n = Math.min(sd.numBones(), bt.length);
            int rh = index(sd, "Bip01_R_Hand"), prop = index(sd, "Bip01_Prop1");
            if (rh < 0 || prop < 0 || n > 96 || parent(sd, rh) < 0 || parent(sd, prop) < 0) {
                return;
            }
            double[][] model = MODEL.get();
            Matrix4f tmp = TMP.get();
            for (int i = 0; i < n; i++) {
                bt[i].getMatrix(tmp);
                double[] local = read(tmp, new double[16]);
                int p = parent(sd, i);
                if (p < 0) {
                    System.arraycopy(local, 0, model[i], 0, 16);
                } else {
                    mul(model[p], local, model[i]);
                }
            }
            double[] P0 = model[prop], HR0 = model[rh], q = r.p;
            boolean hg = r.hg != null;
            double[] rel0 = mulNew(inverse(HR0), P0);
            double boreRhX = rel0[1];
            double[] up0World = hg ? new double[] {-P0[2], -P0[6], -P0[10]} : new double[] {P0[2], P0[6], P0[10]};
            double[] rx = normalize(new double[] {HR0[0], HR0[4], HR0[8]});
            double[] ry = normalize(new double[] {HR0[1], HR0[5], HR0[9]});
            double up0RhY = dot(ry, up0World);
            double distRh = norm(pos(rel0));
            double authored = smooth((boreRhX - 0.50) / 0.30)
                    * smooth((up0RhY - 0.20) / 0.40)
                    * smooth((14 * CM - distRh) / (4 * CM))
                    * smooth((P0[7] - 10 * CM) / (8 * CM));
            // W11 inertia: face down, a gun action (reload, rack, round loading: PerformingAction set) plays Hardship's
            // own HI_Prone_Reload_* clips, which hold the weapon in the right hand at their own raised / rolled angle
            // (boreRhX 0.37..1.00, prop 6.3..13.3 cm from the hand, 24..51 cm over the floor; tools/check_inertia_prone).
            // They are authored for the firearm like the face-down aim clips: keep the clip's prop, seated by dR.
            String acting = c.getVariableString("PerformingAction");
            if (acting != null && !acting.isEmpty() && c.getVariableBoolean("TWHIFloor")) {
                authored = Math.max(authored, smooth((18 * CM - distRh) / (4 * CM)));
            }
            double offB = (hg ? 5.357 : 7.815) * CM, offU = (hg ? 4.979 : 4.591) * CM, offR = (hg ? 3.247 : 4.856) * CM;
            double[] bore = rx.clone();
            double estY = Math.max(5.5 * CM, HR0[7] + offU * 0.4);
            double barrel = (hg ? 18 : 62) * CM;
            double minBoreY = Math.max(-0.98, Math.min(0.06, (4.5 * CM - estY) / barrel));
            double palmLow = smooth((14 * CM - HR0[7]) / (6 * CM));
            minBoreY = Math.max(minBoreY, 0.02 * palmLow);
            if (bore[1] < minBoreY) {
                double horiz = Math.hypot(bore[0], bore[2]);
                if (horiz > 1e-6) {
                    double s = Math.sqrt(Math.max(0, 1 - minBoreY * minBoreY)) / horiz;
                    bore = new double[] {bore[0] * s, minBoreY, bore[2] * s};
                } else {
                    bore = new double[] {0, minBoreY, -Math.sqrt(Math.max(0, 1 - minBoreY * minBoreY))};
                }
            }
            bore = normalize(bore);
            double horizB = Math.hypot(bore[0], bore[2]);
            double[] up = {ry[0], ry[1] + 0.30 * horizB, ry[2]};
            up = sub(up, scale(bore, dot(up, bore)));
            if (norm(up) < 1e-6) {
                up = new double[] {-bore[1] * bore[0], 1 - bore[1] * bore[1], -bore[1] * bore[2]};
            }
            up = normalize(up);
            if (horizB > 0.15) {
                double[] wup = {-bore[1] * bore[0] / horizB, (1 - bore[1] * bore[1]) / horizB, -bore[1] * bore[2] / horizB};
                double cur = dot(up, wup);
                if (cur < 0.30) {
                    double[] side = sub(up, scale(wup, cur));
                    double sn = norm(side);
                    up = sn > 1e-6 ? add(scale(wup, 0.30), scale(side, Math.sqrt(1 - 0.30 * 0.30) / sn)) : wup;
                }
            }
            double[] right = normalize(cross(bore, up));
            double[] posFl = add(pos(HR0), add(scale(bore, offB), add(scale(up, offU), scale(right, offR))));
            double floorMinY = Math.max(5.5 * CM, 2.5 * CM + (hg ? 9 : 15) * CM * Math.max(0, up[1]));
            if (posFl[1] < floorMinY) {
                posFl[1] = floorMinY;
            }
            double[] rFl = hg
                    ? new double[] {-right[0], bore[0], -up[0], -right[1], bore[1], -up[1], -right[2], bore[2], -up[2]}
                    : new double[] {right[0], bore[0], up[0], right[1], bore[1], up[1], right[2], bore[2], up[2]};
            double w = 1 - authored;
            double[] Pn = P0.clone();
            if (w > 1e-6) {
                setRotation(Pn, slerp(unitRotation(P0), rFl, w), columnScales(P0));
                setPos(Pn, lerp(pos(P0), posFl, w));
            }
            double[] dR = {q[0], q[1], q[2]};
            double[] seat = rot(Pn, dR);
            double gripR = 0;
            if (handgunGrip && hg && r.hgShift != null) {
                gripR = handgunRight(c, r, t0);
                if (gripR > 1e-4) {
                    seat = sub(seat, rot(Pn, scale(r.hgShift[1], gripR)));
                }
            }
            setPos(Pn, add(pos(Pn), seat));
            if (w > 1e-6 && Pn[7] < floorMinY) {
                Pn[7] += w * (floorMinY - Pn[7]);
            }
            double supine = 0;
            int pel = -1, ch = -1, rc = -1, rua = -1, rfa = -1;
            if (w > 1e-6 && c.getVariableBoolean("TWHIFloor")) {
                pel = index(sd, "Bip01_Pelvis");
                ch = index(sd, "Bip01_Spine1");
                rc = index(sd, "Bip01_R_Clavicle");
                rua = index(sd, "Bip01_R_UpperArm");
                rfa = index(sd, "Bip01_R_Forearm");
                if (pel >= 0 && ch >= 0 && rc >= 0 && rua >= 0 && rfa >= 0
                        && pel < n && ch < n && rc < n && rua < n && rfa < n
                        && parent(sd, rua) == rc && parent(sd, rfa) == rua && parent(sd, rh) == rfa) {
                    String hiPose = c.getVariableString("HIPose");
                    String hiDown = c.getVariableString("HIDown");
                    if (isHiFrontPose(hiPose, hiDown)) {
                        supine = 0;
                    } else if (isHiBackPose(hiPose, hiDown)) {
                        supine = w;
                    } else {
                        double[] pelZ0 = normalize(new double[] {model[pel][2], model[pel][6], model[pel][10]});
                        supine = smooth((pelZ0[1] - 0.10) / 0.45) * smooth((55 * CM - model[pel][7]) / (25 * CM)) * w;
                    }
                }
            }
            double[] tmpL = new double[16];
            if (supine > 1e-6) {
                boolean fem = c.isFemale();
                int sx = fem ? 1 : 0;
                double[][] torso = fem ? TORSO_F : TORSO_M;
                double[] chM = model[ch], pelM = model[pel];
                double[] chX = normalize(new double[] {chM[0], chM[4], chM[8]});
                double[] chY = normalize(new double[] {chM[1], chM[5], chM[9]});
                double[] chZ = normalize(new double[] {chM[2], chM[6], chM[10]});
                double[] pelX = normalize(new double[] {pelM[0], pelM[4], pelM[8]});
                double[] pelY = normalize(new double[] {pelM[1], pelM[5], pelM[9]});
                double[] pelZ = normalize(new double[] {pelM[2], pelM[6], pelM[10]});
                double supFlat = smooth((pelZ[1] - 0.05) / 0.35) * smooth((chZ[1] + 0.15) / 0.30);
                double fx = -supFlat * pelX[0] + (1.0 - 0.5 * supFlat) * pelZ[0] + 0.5 * (1.0 - supFlat) * chX[0];
                double fz = -supFlat * pelX[2] + (1.0 - 0.5 * supFlat) * pelZ[2] + 0.5 * (1.0 - supFlat) * chX[2];
                double fn = Math.hypot(fx, fz);
                double[] fwdXZ = fn > 1e-6 ? new double[] {fx / fn, 0, fz / fn} : new double[] {0, 0, 1};
                double[] leftXZ = {-fwdXZ[2], 0, fwdXZ[0]};
                double[] rwFlat = add(pos(chM), add(scale(chX, 2.0 * CM),
                        add(scale(chY, -0.95 * torso[2][0] * CM), scale(chZ, (torso[2][1] + 12.2) * CM))));
                double[] rwUp = add(pos(model[rua]), add(scale(fwdXZ, 15.0 * CM),
                        add(scale(leftXZ, -14.5 * CM), new double[] {0, -21.0 * CM, 0})));
                if (rwUp[1] < 24.0 * CM) {
                    rwUp[1] = 24.0 * CM;
                }
                double[] rwSup = lerp(rwUp, rwFlat, supFlat);
                double mid = 4 * supine * (1 - supine);
                double lat = 0.36 * supFlat - 0.12 * (1.0 - supFlat);
                double elev = 0.08 + 0.32 * supFlat + 0.30 * mid;
                double[] boreSup = normalize(new double[] {
                        0.84 * fwdXZ[0] + lat * leftXZ[0],
                        elev,
                        0.84 * fwdXZ[2] + lat * leftXZ[2]});
                double[] upPerp = normalize(new double[] {-boreSup[0] * boreSup[1], 1 - boreSup[1] * boreSup[1], -boreSup[2] * boreSup[1]});
                double[] rightPerp = normalize(cross(boreSup, upPerp));
                double cantCos = 0.52 * supFlat + 0.88 * (1.0 - supFlat);
                double cantSin = Math.sqrt(Math.max(0.0, 1.0 - cantCos * cantCos));
                double[] upSup = normalize(add(scale(upPerp, cantCos), scale(rightPerp, -cantSin)));
                double[] rightSup = normalize(cross(boreSup, upSup));
                double[] rSup = hg
                        ? new double[] {-rightSup[0], boreSup[0], -upSup[0], -rightSup[1], boreSup[1], -upSup[1], -rightSup[2], boreSup[2], -upSup[2]}
                        : new double[] {rightSup[0], boreSup[0], upSup[0], rightSup[1], boreSup[1], upSup[1], rightSup[2], boreSup[2], upSup[2]};
                boolean rocket = !hg && r.source != null && r.source.len() == 0;
                double[] rwLocal;
                if (rocket) {
                    rwLocal = SUPINE_RSHG2_POS.clone();
                } else if (hg) {
                    if (handgunGrip && r.hgShift == null) {
                        gripR = handgunRight(c, r, t0);
                    }
                    rwLocal = sub(SUPINE_HG_POS[sx], dR);
                    if (r.hgShift != null && gripR > 1e-4) {
                        rwLocal = add(rwLocal, scale(r.hgShift[1], gripR));
                    }
                } else {
                    rwLocal = sub(SUPINE_RIFLE_POS[sx], dR);
                }
                double[] refHandRot = hg ? SUPINE_HG_ROT[sx] : SUPINE_RIFLE_ROT[sx];
                double[] rFloorUnit = unitRotation(Pn);
                double[] rwFloorLocal = rot3(transpose3(rFloorUnit), sub(pos(HR0), pos(Pn)));
                double[] rBlend = slerp(rFloorUnit, rSup, supine);
                double[] wGoalPos = lerp(pos(HR0), rwSup, supine);
                wGoalPos[1] += 12 * CM * mid;
                double[] polePref = normalize(add(scale(pelY, -1.0),
                        add(scale(pelZ, -0.7), new double[] {0, -0.8 * (1.0 - supFlat), 0})));
                double[] hGoal = HR0.clone();
                setPos(hGoal, wGoalPos);
                setRotation(hGoal, slerp(unitRotation(HR0), mul3(rBlend, refHandRot), supine), columnScales(HR0));
                double[] UAn = new double[16], FAn = new double[16], Hn = new double[16];
                solveArm(model[rua], model[rfa], HR0, hGoal, UAn, FAn, Hn, polePref, supine);
                limitWrist(FAn, Hn);
                ArmNatural.Bind rBind = rightBindOf(sd, rua, rfa, rh);
                if (rBind != null) {
                    double[] UAnat = UAn.clone(), FAnat = FAn.clone();
                    double[] pole = armPole(model[rua], model[rfa], hGoal, polePref, supine);
                    if (ArmNatural.solve(UAnat, FAnat, Hn, rBind, new ArmNatural.State(), 1, false, 0, true, pole)) {
                        setRotation(UAn, slerp(unitRotation(UAn), unitRotation(UAnat), supine), columnScales(UAn));
                        setRotation(FAn, slerp(unitRotation(FAn), unitRotation(FAnat), supine), columnScales(FAn));
                    }
                }
                setRotation(Pn, rBlend, columnScales(P0));
                setPos(Pn, sub(pos(Hn), rot3(rBlend, lerp(rwFloorLocal, rwLocal, supine))));
                mul(inverse(model[rc]), UAn, tmpL);
                bt[rua].set(write(tmpL, tmp));
                mul(inverse(UAn), FAn, tmpL);
                bt[rfa].set(write(tmpL, tmp));
                mul(inverse(FAn), Hn, tmpL);
                bt[rh].set(write(tmpL, tmp));
                model[rua] = UAn;
                model[rfa] = FAn;
                model[rh] = Hn;
                if (fingerFit) {
                    if (handgunGrip && hg && r.hg != null) {
                        double wF = supine * (r.hgShift != null ? gripR : handgunRight(c, r, t0));
                        if (wF > 1e-4) {
                            handgunFingers(sd, bt, -1, rh, r.hg, new double[] {0, wF}, tmp);
                        }
                    } else {
                        FpPoseData data = FpPoseData.get();
                        FpPoseData.Family fam = data == null ? null : data.byClass.get(BARE_CLASS);
                        if (fam != null && fam.hasIdle()) {
                            String[] rNames = {"Bip01_R_Finger0", "Bip01_R_Finger1"};
                            for (int k = 0; k < 2; k++) {
                                int fi = index(sd, rNames[k]);
                                if (fi >= 0 && fi < n && parent(sd, fi) == rh) {
                                    bt[fi].getMatrix(tmp);
                                    double[] fl = read(tmp, new double[16]);
                                    setRotation(fl, slerp(unitRotation(fl), unitRotation(fam.fingers[sx][1][k]), supine), columnScales(fl));
                                    bt[fi].set(write(fl, tmp));
                                }
                            }
                        }
                    }
                }
                supinePost.put(ap, new SupinePost(rc, rua, rfa, rh, prop,
                        model[rc].clone(), UAn.clone(), FAn.clone(), Hn.clone(), Pn.clone()));
            } else {
                // W11 inertiatp: face down, on all fours and in the front transitions the weapon stays where this pass
                // put it in the right hand when Viewpoint's third person turns the arms afterwards (seatYieldedModel).
                supinePost.put(ap, new SupinePost(-1, -1, -1, rh, prop, null, null, null, model[rh].clone(), Pn.clone()));
            }
            mul(inverse(model[parent(sd, prop)]), Pn, tmpL);
            bt[prop].set(write(tmpL, tmp));
        } catch (Throwable e) {
            lastError = e.toString();
        }
    }

    /**
     * The first-person layers of one animation update, in this order: the single dispatch of the chain (one early
     * return here turns all of them off, W8-animopt).
     */
    public static void onModel(Object self) {
        double[][] oh = oneHandExpected.isEmpty() ? null : oneHandExpected.remove((AnimationPlayer) self);
        if (oh != null) {
            reattachOneHand((AnimationPlayer) self, oh, false);
        }
        double[][] hp = handPost.isEmpty() ? null : handPost.remove((AnimationPlayer) self);
        if (hp != null) {
            followHand((AnimationPlayer) self, hp);
        }
        if (frameLog) {
            frameEnter((AnimationPlayer) self);
        }
        try (PerfProbe.Scope perf = PerfProbe.cpu(PerfProbe.FP_POSE)) {
            FpPose.onModel(self); // first person / obstacle retreat, after Viewpoint's arm pitch (FpPose)
            GrenadeThrowPose.onModel(self); // original grenade actions only; its failure disables only that layer
            PanicSway.onModel(self);
            if (oh != null) {
                reattachOneHand((AnimationPlayer) self, oh, true);
            }
            if (!expected.isEmpty()) {
                check((AnimationPlayer) self); // reseat before ArmNatural so 3P pitched arms get hinged-elbow rebuild too
            }
            if (!supinePost.isEmpty()) {
                seatYieldedModel((AnimationPlayer) self);
            }
            ArmNatural.onModel(self); // the arms about their final points: a hinged elbow, the twist at the wrist
            MagazineLoading.afterPose(self); // cartridge follows the final native loading pose, without resetting tracks
        }
        if (tracing) {
            traceModel((AnimationPlayer) self);
        }
        if (frameLog) {
            frameExit((AnimationPlayer) self);
        }
    }

    /**
     * W10 proneback: in Viewpoint third person (view == 2), Viewpoint's Patch_SkeletonPosed (ArmsPitch) runs on
     * updateModelTransforms exit after onBlend and pitches the right arm off the supine chest weapon without moving
     * Bip01_Prop1 (parented to Bip01). Restore the solved supine right arm + Prop1 on UpdateSkinTransforms entry
     * (after Viewpoint in both mod load orders); no-op when modelTransforms did not drift (Isometric), and in Viewpoint
     * first person (view == 1).
     * W11 inertiatp: on prone/fours and the front transitions (supine == 0; entry with rua == -1) the same pass of
     * Viewpoint (HeadLook / ArmsPitch turn the Bip01_Neck subtree) took the right hand 6-18 cm off the weapon while
     * crawling (QA 29.10.2026, W11INERTIA TP). There the arms stay as Viewpoint turned them and only Bip01_Prop1
     * follows the turned hand: H_posed * (H_blend^-1 * P_seated), the hand-to-weapon relation seatYielded set (the
     * isometric one).
     */
    static void seatYieldedModel(AnimationPlayer ap) {
        SupinePost sp = supinePost.remove(ap);
        if (!frontPost.isEmpty()) {
            frontPost.remove(ap);
        }
        if (sp == null || !enabled || !floorSeat || AnimOptOut.optionsBlock(ap)) {
            return;
        }
        try {
            IsoGameCharacter c = ap.getIsoGameCharacter();
            if (c instanceof zombie.characters.IsoPlayer pl && pl.isLocalPlayer() && AnimOptOut.view() == 1) {
                return;
            }
            SkinningData sd = ap.getSkinningData();
            int count = ap.getModelTransformsCount();
            FpPose.Skeleton s = sd == null || ap.boneTransforms == null || count <= 0 ? null
                    : FpPose.skeleton(sd, Math.min(ap.boneTransforms.length, count));
            if (s == null || sp.rh >= s.n || sp.prop >= s.n || sp.rc >= s.n || sp.rua >= s.n || sp.rfa >= s.n) {
                return;
            }
            double[] curRh = read(ap.getModelTransformAt(sp.rh), new double[16]);
            if (dist(pos(curRh), pos(sp.Hn)) <= 1e-4) {
                return;
            }
            double[][] curModel = new double[s.n][];
            for (int i = 0; i < s.n; i++) {
                curModel[i] = read(ap.getModelTransformAt(i), new double[16]);
            }
            double[][] next = new double[s.n][];
            boolean[] driver = new boolean[s.n];
            if (sp.rua < 0) { // W11 inertiatp: face down / all fours - the weapon follows the turned hand
                next[sp.prop] = mulNew(curRh, mulNew(inverse(sp.Hn), sp.Pn));
                driver[sp.prop] = true;
                FpPose.write(ap, s, curModel, next, driver);
                frontPost.put(ap, new double[][] {sp.Hn, sp.Pn});
                return;
            }
            next[sp.rc] = sp.RC;
            next[sp.rua] = sp.UAn;
            next[sp.rfa] = sp.FAn;
            next[sp.rh] = sp.Hn;
            next[sp.prop] = sp.Pn;
            driver[sp.rc] = driver[sp.rua] = driver[sp.rfa] = driver[sp.rh] = driver[sp.prop] = true;
            FpPose.write(ap, s, curModel, next, driver);
        } catch (Throwable e) {
            lastError = e.toString();
        }
    }

    /**
     * QA (FB2-D): one line per animation update of the local player's support hand through every
     * layer - the GripIK goal and output on the weapon, the hand on the weapon after Viewpoint's arm
     * pitch (entry of this exit chain) and after the first-person layer (final), the measured arm
     * chain, wrist twist against the bind pose and the layers' states. Drained by Lua (drainFrames).
     */
    public static volatile boolean frameLog;
    static final ConcurrentHashMap<IsoGameCharacter, double[]> stages = new ConcurrentHashMap<>(), entries = new ConcurrentHashMap<>(),
            clips = new ConcurrentHashMap<>(), mids = new ConcurrentHashMap<>();
    static final StringBuilder FRAMES = new StringBuilder();
    static final int FRAMES_MAX = 8 << 20;

    public static void frames(boolean on) {
        synchronized (FRAMES) {
            FRAMES.setLength(0);
        }
        stages.clear();
        entries.clear();
        clips.clear();
        mids.clear();
        frameLog = on;
    }

    static volatile String phase = "";

    /** The QA phase tag of the following lines. */
    public static void framePhase(String label) {
        phase = label == null ? "" : label.replace(' ', '_');
    }

    public static String drainFrames() {
        synchronized (FRAMES) {
            String s = FRAMES.toString();
            FRAMES.setLength(0);
            return s;
        }
    }

    static IsoGameCharacter framed(AnimationPlayer ap) {
        if (ap.parentPlayer != null) {
            return null;
        }
        IsoGameCharacter c = ap.getIsoGameCharacter();
        return c instanceof zombie.characters.IsoPlayer p && p.isLocalPlayer() ? c : null;
    }

    static void frameEnter(AnimationPlayer ap) {
        try {
            IsoGameCharacter c = framed(ap);
            SkinningData sd = c == null ? null : ap.getSkinningData();
            int prop = sd == null ? -1 : index(sd, "Bip01_Prop1"), lh = sd == null ? -1 : index(sd, "Bip01_L_Hand");
            if (prop >= 0 && lh >= 0) {
                entries.put(c, pos(mulNew(inverse(read(ap.getModelTransformAt(prop), new double[16])), read(ap.getModelTransformAt(lh), new double[16]))));
                int ch = index(sd, "Bip01_Spine1");
                if (ch >= 0) { // FB7: the support wrist and the weapon on the chest after GripIK, before this exit chain
                    double[] ic = inverse(read(ap.getModelTransformAt(ch), new double[16])), w = pos(mulNew(ic, read(ap.getModelTransformAt(lh), new double[16])));
                    double[] pp = framePose(ic, read(ap.getModelTransformAt(prop), new double[16]));
                    mids.put(c, new double[] {w[0], w[1], w[2], pp[0], pp[1], pp[2], pp[3], pp[4], pp[5], pp[6], pp[7], pp[8]});
                }
            }
        } catch (Throwable e) {
            lastError = e.toString();
        }
    }

    /** QA (FB7): a bone on the chest (ic: the chest's inverse) - position, its y (bore) and z axes. */
    static double[] framePose(double[] ic, double[] M) {
        double[] r = mulNew(ic, M);
        return new double[] {r[3], r[7], r[11], r[1], r[5], r[9], r[2], r[6], r[10]};
    }

    static void frameExit(AnimationPlayer ap) {
        try {
            IsoGameCharacter c = framed(ap);
            SkinningData sd = c == null ? null : ap.getSkinningData();
            if (sd == null) {
                return;
            }
            int prop = index(sd, "Bip01_Prop1"), lc = index(sd, "Bip01_L_Clavicle"), ua = index(sd, "Bip01_L_UpperArm"), fa = index(sd, "Bip01_L_Forearm"),
                    lh = index(sd, "Bip01_L_Hand"), head = index(sd, "Bip01_Head"), chest = index(sd, "Bip01_Spine1");
            if (prop < 0 || ua < 0 || fa < 0 || lh < 0 || head < 0 || chest < 0) {
                return;
            }
            double[] P = read(ap.getModelTransformAt(prop), new double[16]), UA = read(ap.getModelTransformAt(ua), new double[16]),
                    FA = read(ap.getModelTransformAt(fa), new double[16]), H = read(ap.getModelTransformAt(lh), new double[16]),
                    HD = read(ap.getModelTransformAt(head), new double[16]), CH = read(ap.getModelTransformAt(chest), new double[16]);
            double[] on = pos(mulNew(inverse(P), H)), inHead = pos(mulNew(inverse(HD), H)), inChest = pos(mulNew(inverse(CH), H));
            double l1 = dist(pos(UA), pos(FA)), l2 = dist(pos(FA), pos(H));
            double elbow = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot(normalize(sub(pos(UA), pos(FA))), normalize(sub(pos(H), pos(FA))))))));
            double[] r0 = bindWrist(sd, fa, lh);
            double twist = r0 == null ? Double.NaN : Math.toDegrees(wristTwist(FA, H, r0, bindAxis(sd, fa, lh)));
            double bend = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot(normalize(sub(pos(H), pos(FA))), normalize(new double[] {H[0], H[4], H[8]}))))));
            Request rq = requests.get(c);
            double[] q = rq == null ? null : rq.p;
            double[] L = q == null ? null : point(new double[] {q[3], q[4], q[5], q[6], q[7], q[8], q[9], q[10], q[11], q[12], q[13], q[14], 0, 0, 0, 1}, q[15], q[16], q[17]);
            double[] st = stages.get(c), en = entries.get(c);
            boolean fresh = st != null && System.nanoTime() - (long) st[0] < 100_000_000L;
            FpPose.Request fr = FpPose.requests.get(c);
            double[] fl = fr != null && fr.left != null ? new double[] {fr.left[3], fr.left[7], fr.left[11]} : null;
            StringBuilder s = new StringBuilder(640);
            java.util.Formatter f = new java.util.Formatter(s, java.util.Locale.ROOT);
            f.format("FRM %s t=%d view=%d st=%s aim=%b fem=%b gik=%s", phase, System.nanoTime() / 1_000_000L, ViewpointBridge.state(),
                    c.getCurrentActionContextStateName(), c.isAiming(), c.isFemale(), fresh ? "1" : "0");
            Double look = ViewpointBridge.lookPitch();
            if (look != null) {
                f.format(" lookDeg=%.1f", Math.toDegrees(look));
            }
            f.format(" l1=%.2f l2=%.2f elbowDeg=%.1f twistDeg=%.1f bendDeg=%.1f", l1 / CM, l2 / CM, elbow, twist, bend);
            s.append(ArmNatural.describe(c)); // natural arms: the wrists' twists, the forearms' shares, the turns they came with
            f.format(" hand=%.2f,%.2f,%.2f head=%.2f,%.2f,%.2f chest=%.2f,%.2f,%.2f", on[0] / CM, on[1] / CM, on[2] / CM,
                    inHead[0] / CM, inHead[1] / CM, inHead[2] / CM, inChest[0] / CM, inChest[1] / CM, inChest[2] / CM);
            if (L != null) {
                f.format(" errLCm=%.2f", dist(on, L) / CM);
                if (fl != null) {
                    f.format(" errFpCm=%.2f", dist(on, fl) / CM);
                }
                f.format(" shoulderToLCm=%.2f", dist(pos(UA), point(P, L[0], L[1], L[2])) / CM);
            }
            if (en != null) {
                f.format(" entry=%.2f,%.2f,%.2f", en[0] / CM, en[1] / CM, en[2] / CM);
            }
            double[] cl = clips.remove(c), inChestElbow = pos(mulNew(inverse(CH), FA));
            f.format(" elbow=%.2f,%.2f,%.2f", inChestElbow[0] / CM, inChestElbow[1] / CM, inChestElbow[2] / CM);
            if (cl != null) { // FB7: this frame's clip wrist, elbow and weapon on the chest (apply)
                f.format(" clipChest=%.2f,%.2f,%.2f clipElbow=%.2f,%.2f,%.2f", cl[0] / CM, cl[1] / CM, cl[2] / CM, cl[3] / CM, cl[4] / CM, cl[5] / CM);
                f.format(" clipProp=%.2f,%.2f,%.2f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f", cl[6] / CM, cl[7] / CM, cl[8] / CM, cl[9], cl[10], cl[11], cl[12], cl[13], cl[14]);
            }
            double[] mid = mids.remove(c), pf = framePose(inverse(CH), P);
            if (mid != null) { // after GripIK (frameEnter)
                f.format(" midChest=%.2f,%.2f,%.2f midProp=%.2f,%.2f,%.2f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f", mid[0] / CM, mid[1] / CM, mid[2] / CM,
                        mid[3] / CM, mid[4] / CM, mid[5] / CM, mid[6], mid[7], mid[8], mid[9], mid[10], mid[11]);
            }
            f.format(" prop=%.2f,%.2f,%.2f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f", pf[0] / CM, pf[1] / CM, pf[2] / CM, pf[3], pf[4], pf[5], pf[6], pf[7], pf[8]);
            if (fresh) {
                f.format(" goal=%.2f,%.2f,%.2f out=%.2f,%.2f,%.2f w=%.3f lock=%.3f acting=%d resCm=%.2f clavDeg=%.1f slideCm=%.2f reach=%.3f tw=%.1f twKept=%.1f reliefDeg=%.1f elbowTurnDeg=%.1f liftDeg=%.1f hw=%.2f wm=%.2f free=%.0f",
                        st[1] / CM, st[2] / CM, st[3] / CM, st[4] / CM, st[5] / CM, st[6] / CM, st[7], st[8], (int) st[9], st[10] / CM,
                        Math.toDegrees(st[11]), st[12] / CM, st[13], Math.toDegrees(st[14]), Math.toDegrees(st[15]), Math.toDegrees(st[16]),
                        Math.toDegrees(st[17]), Math.toDegrees(st[18]), st[19], st[20], st[21]);
            }
            FpPoseData data = FpPoseData.get();
            // the grip each layer fits: the first-person layer its family's, GripIK the bare reference class's
            FpPoseData.Family fam = data == null ? null : fr != null && fr.mode.startsWith("fp") ? FpPose.family(data, fr) : data.byClass.get(BARE_CLASS);
            int f0 = index(sd, "Bip01_L_Finger0"), f1 = index(sd, "Bip01_L_Finger1");
            if (fam != null && f0 >= 0 && f1 >= 0) { // the left fingers against Tarkov's grip of the family (degrees)
                double[] a = new double[2];
                int[] fingers = {f0, f1};
                for (int k = 0; k < 2; k++) {
                    double[] local = unitRotation(mulNew(inverse(H), read(ap.getModelTransformAt(fingers[k]), new double[16])));
                    double[] rel = mul3(transpose3(local), unitRotation(fam.fingers[c.isFemale() ? 1 : 0][0][k]));
                    a[k] = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (rel[0] + rel[4] + rel[8] - 1) / 2))));
                }
                f.format(" fingerDeg=%.1f,%.1f", a[0], a[1]);
            }
            if (rq != null && rq.wrap != null && f0 >= 0 && f1 >= 0) { // FB2-E: the skin proxy on the handguard section, and the pose for offline skin
                double[] F0 = read(ap.getModelTransformAt(f0), new double[16]), F1 = read(ap.getModelTransformAt(f1), new double[16]);
                f.format(" wrapTipCm=%s", wrapReport(rq.wrap, rq.hold != null ? mulNew(P, rq.hold.frame) : P, F0, F1));
                double[] hp = mulNew(inverse(P), H), w0 = unitRotation(mulNew(inverse(H), F0)), w1 = unitRotation(mulNew(inverse(H), F1));
                if (rq.hold != null) {
                    f.format(" palmMinCm=%.8f palmContact=%d", holdPalmMinSd(hp, rq.wrap, rq.hold.frameInv) / CM,
                            holdContactValid(hp, rq.hold, rq.wrap) ? 1 : 0);
                }
                f.format(" wrapDeg=%.0f,%.0f,%.0f,%.0f/%.0f,%.0f,%.0f,%.0f wrapPose=", rq.wrapState[2], rq.wrapState[3], rq.wrapState[0], rq.wrapState[1],
                        rq.fpWrapState[2], rq.fpWrapState[3], rq.fpWrapState[0], rq.fpWrapState[1]);
                for (int i = 0; i < 12; i++) {
                    f.format(i == 0 ? "%.5f" : ",%.5f", hp[i]);
                }
                for (double v : w0) {
                    f.format(",%.5f", v);
                }
                for (double v : w1) {
                    f.format(",%.5f", v);
                }
            }
            int rh = index(sd, "Bip01_R_Hand"), rf0 = index(sd, "Bip01_R_Finger0"), rf1 = index(sd, "Bip01_R_Finger1");
            if (rq != null && (rq.hg != null || fr != null && fr.hgFingers != null) && f0 >= 0 && f1 >= 0 && rh >= 0 && rf0 >= 0 && rf1 >= 0) {
                // pistol in both hands: the weights (GripIK / first-person layer) and both hands with their fingers on the
                // weapon, for the actual skin offline (tools/pistol_grip_report.py --log)
                double[] HR = read(ap.getModelTransformAt(rh), new double[16]);
                f.format(" hgW=%.2f,%.2f/%.2f,%.2f hgPose=", rq.hgW[0], rq.hgW[1], fr == null ? 0 : fr.hgW[0], fr == null ? 0 : fr.hgW[1]);
                double[] ip = inverse(P), hl = mulNew(ip, H), hr = mulNew(ip, HR);
                for (int i = 0; i < 12; i++) {
                    f.format(i == 0 ? "%.5f" : ",%.5f", hl[i]);
                }
                for (int i = 0; i < 12; i++) {
                    f.format(",%.5f", hr[i]);
                }
                double[][] hands = {H, H, HR, HR};
                int[] bones = {f0, f1, rf0, rf1};
                for (int k = 0; k < 4; k++) {
                    for (double v : unitRotation(mulNew(inverse(hands[k]), read(ap.getModelTransformAt(bones[k]), new double[16])))) {
                        f.format(",%.5f", v);
                    }
                }
            }
            if (rq != null && rq.holdLast != null && rq.holdPrev != null) { // FB2-N: the chosen hold
                double[] e = rq.holdLast, hp = rq.holdPrev;
                f.format(" hold=%d,%.2f,%.0f,%.0f om=%.0f ext=%.0f dev=%.0f depthCm=%.2f holdTw=%.0f holdCost=%.2f", rq.hold.kind, hp[0] / CM, hp[1], hp[2],
                        e[0], e[1], e[2], e[3] / CM, e[4], hp[3]);
            }
            if (rq != null && rq.obstacle != null && Double.isFinite(rq.obLast)) { // FB8: the arm in the belt box (> 0 inside)
                f.format(" obCm=%.2f", rq.obLast / CM);
            }
            if (fr != null) {
                f.format(" fp={mode=%s fpW=%.3f ads=%.3f handMiss=%.3f srcMiss=%.3f srcAdj=%.3f bodyPitch=%.1f bodyTurn=%.1f elbowTurnL=%.1f fit=%.2f",
                        fr.mode.replace(' ', '_'), fr.fpW, fr.ads, fr.handMiss, fr.sourceContactMiss, fr.sourceContactAdjust, Math.toDegrees(fr.bodyPitch),
                        Math.toDegrees(fr.bodyTurn), Math.toDegrees(fr.elbowTurn[0]), fr.fingerFit[0]);
                if (fr.supportEye != null && fr.elbowsEye != null) { // FB7 item 2: the support wrist and elbow as the camera sees them (cm)
                    f.format(" sEye=%.2f,%.2f,%.2f eEye=%.2f,%.2f,%.2f", fr.supportEye[0] / CM, fr.supportEye[1] / CM, fr.supportEye[2] / CM,
                            fr.elbowsEye[0] / CM, fr.elbowsEye[1] / CM, fr.elbowsEye[2] / CM);
                }
                if (fr.eye != null) {
                    f.format(" pitch=%.1f", Math.toDegrees(fr.eye[4]));
                }
                s.append('}');
            }
            s.append('\n');
            synchronized (FRAMES) {
                if (FRAMES.length() + s.length() < FRAMES_MAX) {
                    FRAMES.append(s);
                }
            }
        } catch (Throwable e) {
            lastError = e.toString();
        }
    }

    static double[] trace(IsoGameCharacter c) {
        return traces.computeIfAbsent(c, k -> new double[T_SIZE]);
    }

    /**
     * W10 torchpistol (item 29): right-wrist grip anchor (Prop1^-1 * R_Hand translation, model units) and hand-to-Prop1
     * rotation (R_Hand^-1 * Prop1, row-major 3x3) of the handgun reference clip TW_HGCarry_G006_OwnHG_Held_IdleAimHandgun
     * (male and female; tools/grip_targets.py HANDGUN_REF). One-handed handgun poses with a secondary item in the other
     * hand seat own_R = ref_R - dR (+ gripR * hgShift[1]) directly onto Bip01_R_Hand (or mirrored onto Bip01_L_Hand when
     * passively carried in SecondaryHandItem on Bip01_Prop2), leaving the off-hand's own item and mask untouched.
     */
    static final double[] HANDGUN_REF_R_M = {0.01809302, -0.04873032, 0.04404784};
    static final double[] HANDGUN_REF_R_F = {0.01888334, -0.05325105, 0.04467338};
    static final double[] HANDGUN_REF_HR_P1_M = {
            -0.029842, 0.999045, -0.031907,
            -0.093089, -0.034560, -0.995058,
            -0.995211, -0.026724, 0.094031
    };
    static final double[] HANDGUN_REF_HR_P1_F = {
            -0.017628, 0.995153, -0.096750,
            -0.096552, -0.098007, -0.990491,
            -0.995172, -0.008119, 0.097811
    };
    /** Trace threshold 1 + 2*cos(pi/4): separates rolled handgun Prop1 clips (<=30 deg) from unrolled/unkeyed Prop1 (>=90 deg). */
    static final double HANDGUN_PROP1_TRACE_MIN = 1.0 + 2.0 * Math.cos(Math.PI / 4.0);

    static double[] seatOneHandProp1(double[] P0, double[] HR0, double[] refHrP1, double[] anchorR) {
        double[] Pn = P0.clone();
        double[] hrR = unitRotation(HR0), p0R = unitRotation(P0);
        double[] rel = mul3(transpose3(hrR), p0R);
        double tr = 0;
        for (int i = 0; i < 9; i++) {
            tr += rel[i] * refHrP1[i];
        }
        if (tr < HANDGUN_PROP1_TRACE_MIN) {
            setRotation(Pn, mul3(hrR, refHrP1), columnScales(P0));
        }
        setPos(Pn, sub(pos(HR0), rot(Pn, anchorR)));
        return Pn;
    }

    static double[] makeOneHandRow(int handIdx, int propIdx, int parIdx, double w, double[] H, double[] P) {
        double[] out = new double[20];
        out[0] = handIdx;
        out[1] = propIdx;
        out[2] = parIdx;
        out[3] = w;
        System.arraycopy(mulNew(inverse(H), P), 0, out, 4, 16);
        return out;
    }

    /**
     * W12 (user report 30.10.2026, "pistols float outside the hand in third person"; the MP-153 "hanging along the body with
     * the wrong hands"): after this pass Viewpoint's third person turns the whole Bip01_Neck subtree - clavicles, arms, hands -
     * towards its look (Patch_SkeletonPosed: HeadLook, ArmsPitch), while Bip01_Prop1 (a child of Bip01) stays where this
     * pass put it. With the camera beside or in front of the body the hands left the weapon (QA w12pose3p, GLOCK17 low
     * ready, body 90 degrees across the camera: right hand 3.2 cm and support hand 9.3 cm further from the weapon than in
     * the isometric view). The weapon follows the turned right hand by the relation this pass set:
     * P = H_now * (H_set^-1 * P_set) - the support hand turned with it, the FB2-D reseat (check) settles what is left.
     * The one-handed carry with a light in the other hand had this already (oneHandExpected, W10), face down under
     * Hardship: Inertia too (seatYieldedModel, W11). No drift (the isometric view: H_now = H_set), the local first person
     * and the first-person layer leave it untouched; every weapon and grip class takes the same rule.
     */
    static final ConcurrentHashMap<AnimationPlayer, double[][]> handPost = new ConcurrentHashMap<>();
    public static volatile boolean handFollow = true;
    public static volatile long follows;
    public static volatile double maxFollowCm;

    static void followHand(AnimationPlayer ap, double[][] hp) {
        if (!enabled || !handFollow || hp == null || hp.length < 3 || AnimOptOut.blocks(ap)) {
            return;
        }
        try {
            IsoGameCharacter c = ap.getIsoGameCharacter();
            if (c == null || c instanceof zombie.characters.IsoPlayer pl && pl.isLocalPlayer() && AnimOptOut.view() == 1) {
                return;
            }
            FpPose.Request fr = FpPose.requests.get(c);
            if (fr != null && fr.mode.startsWith("fp")) { // the first-person layer places the arms and the weapon itself
                return;
            }
            int rh = (int) hp[0][0], prop = (int) hp[0][1];
            SkinningData sd = ap.getSkinningData();
            int count = ap.getModelTransformsCount();
            FpPose.Skeleton s = sd == null || ap.boneTransforms == null || count <= 0 ? null
                    : FpPose.skeleton(sd, Math.min(ap.boneTransforms.length, count));
            if (s == null || rh < 0 || prop < 0 || rh >= s.n || prop >= s.n || s.parent[prop] < 0) {
                return;
            }
            double[] curRh = read(ap.getModelTransformAt(rh), new double[16]);
            double drift = dist(pos(curRh), pos(hp[1]));
            if (drift <= 1e-4) {
                return;
            }
            double[][] curModel = new double[s.n][];
            for (int i = 0; i < s.n; i++) {
                curModel[i] = read(ap.getModelTransformAt(i), new double[16]);
            }
            double[][] next = new double[s.n][];
            boolean[] driver = new boolean[s.n];
            next[prop] = mulNew(curRh, mulNew(inverse(hp[1]), hp[2]));
            driver[prop] = true;
            FpPose.write(ap, s, curModel, next, driver);
            follows++;
            maxFollowCm = Math.max(maxFollowCm, drift / CM);
        } catch (Throwable e) {
            lastError = e.toString();
        }
    }

    static void reattachOneHand(AnimationPlayer ap, double[][] list, boolean prop2Only) {
        if (list == null || AnimOptOut.blocks(ap)) {
            return;
        }
        try {
            if (prop2Only) {
                IsoGameCharacter c = ap.getIsoGameCharacter();
                FpPose.Request fr = c == null ? null : FpPose.requests.get(c);
                if (fr != null && fr.mode.startsWith("fp")) {
                    return;
                }
            }
            SkinningData sd = ap.getSkinningData();
            AnimatorsBoneTransform[] bt = ap.boneTransforms;
            if (sd == null || bt == null) {
                return;
            }
            int n = Math.min(Math.min(sd.numBones(), bt.length), ap.getModelTransformsCount());
            int p2 = prop2Only ? index(sd, "Bip01_Prop2") : -1;
            Matrix4f tmp = TMP.get();
            for (double[] oh : list) {
                if (oh == null || oh.length < 20) {
                    continue;
                }
                int handIdx = (int) oh[0], propIdx = (int) oh[1], parIdx = (int) oh[2];
                double w = oh[3];
                if (prop2Only && propIdx != p2) {
                    continue;
                }
                if (handIdx < 0 || handIdx >= n || propIdx < 0 || propIdx >= n || parIdx < 0 || parIdx >= n || w <= 1e-4) {
                    continue;
                }
                double[] H = read(ap.getModelTransformAt(handIdx), new double[16]);
                double[] P = read(ap.getModelTransformAt(propIdx), new double[16]);
                double[] T = new double[16];
                System.arraycopy(oh, 4, T, 0, 16);
                double[] P1h = mulNew(H, T);
                double[] Pwant;
                if (w >= 0.9999) {
                    Pwant = P1h;
                } else {
                    Pwant = P.clone();
                    setPos(Pwant, lerp(pos(P), pos(P1h), w));
                    setRotation(Pwant, slerp(unitRotation(P), unitRotation(P1h), w), columnScales(P));
                }
                double[] Ppar = read(ap.getModelTransformAt(parIdx), new double[16]);
                bt[propIdx].set(write(mulNew(inverse(Ppar), Pwant), tmp));
                write(Pwant, ap.getModelTransformAt(propIdx));
            }
        } catch (Throwable t) {
            lastError = t.toString();
        }
    }

    /** Blend weight [0..1] of any own weapon action clip (TW_* / TWRound_*) currently playing on ap; 0 on vanilla 1H clips. */
    static double ownActionTrackWeight(AnimationPlayer ap) {
        try {
            AnimationMultiTrack mt = ap == null ? null : ap.getMultiTrack();
            if (mt == null) {
                return 0;
            }
            double best = 0;
            for (AnimationTrack t : mt.getTracks()) {
                if (t == null) {
                    continue;
                }
                String name = t.getClip() != null ? t.getClip().name : t.getName();
                if (name != null && (name.startsWith("TW_") || name.startsWith(ROUND_CLIP))) {
                    best = Math.max(best, t.getBlendWeight());
                }
            }
            return Math.max(0, Math.min(1, best));
        } catch (Throwable e) {
            return 0;
        }
    }

    /** Keep the secondary item's LeftHandMask active outside our own two-handed reload/rack/mag actions, and release it during them. */
    static void syncOffhandMask(IsoGameCharacter c, zombie.inventory.InventoryItem sec, boolean ownTrackActive) {
        if (c == null || sec == null) {
            return;
        }
        String var = "LeftHandMask", mask = null;
        zombie.scripting.objects.ItemReplacement rep = sec.getItemReplacementSecondHand();
        if (rep != null && rep.maskVariableValue != null && !rep.maskVariableValue.isEmpty()) {
            if (rep.maskVariableName != null && !rep.maskVariableName.isEmpty()) {
                var = rep.maskVariableName;
            }
            mask = rep.maskVariableValue;
        } else if (sec.getScriptItem() != null && sec.getScriptItem().secondaryAnimMask != null
                && !sec.getScriptItem().secondaryAnimMask.isEmpty()) {
            mask = sec.getScriptItem().secondaryAnimMask;
        }
        if (mask == null || mask.isEmpty()) {
            return;
        }
        boolean ownAct = ownTrackActive || c.getVariableBoolean("isLoading") || c.getVariableBoolean("isRacking")
                || c.getVariableBoolean("isUnloading") || c.getVariableBoolean("TWMagCheck")
                || c.getVariableBoolean("TWMagDrop");
        String cur = c.getVariableString(var);
        if (ownAct) {
            if (cur != null && !cur.isEmpty()) {
                c.clearVariable(var);
            }
        } else if (!mask.equals(cur)) {
            c.setVariable(var, mask);
        }
    }

    /** Proper rotation (det = +1) mapping R_Hand^-1 * Prop1 to L_Hand^-1 * Prop2: diag(1,-1,1) * R * diag(-1,1,1). */
    static double[] mirrorHandToProp(double[] r) {
        return new double[] {
                -r[0], r[1], r[2],
                r[3], -r[4], -r[5],
                -r[6], r[7], r[8]
        };
    }

    /** Proper rotation (det = +1) mapping a right-hand finger rotation to the symmetric left-hand finger: diag(1,-1,1) * R * diag(1,-1,1). */
    static double[] mirrorFinger(double[] r) {
        return new double[] {
                r[0], -r[1], r[2],
                -r[3], r[4], -r[5],
                r[6], -r[7], r[8]
        };
    }

    /** Blend weight of the round-load clips (TWRound_*) playing, at most 1 (bug wave 324, item 1); 0 on any failure. */
    static double roundWeight(AnimationPlayer ap) {
        try {
            AnimationMultiTrack mt = ap == null ? null : ap.getMultiTrack();
            if (mt == null) {
                return 0;
            }
            double sum = 0;
            for (AnimationTrack t : mt.getTracks()) {
                String name = t.getName() != null ? t.getName() : t.getClip() != null ? t.getClip().name : null;
                if (name != null && name.startsWith(ROUND_CLIP)) {
                    sum += t.getBlendWeight();
                }
            }
            return Math.max(0, Math.min(1, sum));
        } catch (Throwable e) {
            return 0;
        }
    }

    /** QA: the clips the character plays, heaviest first, "name:weight;..." (at most n). */
    static String tracks(IsoGameCharacter c, int n) {
        try {
            AnimationPlayer ap = c == null ? null : c.getAnimationPlayer();
            AnimationMultiTrack mt = ap == null ? null : ap.getMultiTrack();
            if (mt == null) {
                return "";
            }
            AnimationTrack[] ts = mt.getTracks().toArray(new AnimationTrack[0]);
            java.util.Arrays.sort(ts, (a, b) -> Float.compare(b.getBlendWeight(), a.getBlendWeight()));
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < ts.length && i < n; i++) {
                AnimationTrack t = ts[i];
                String name = t.getName() != null ? t.getName() : t.getClip() != null ? t.getClip().name : "?";
                s.append(i > 0 ? ";" : "").append(name).append(':').append(Math.round(t.getBlendWeight() * 100) / 100.0);
            }
            return s.toString();
        } catch (Throwable e) {
            return "error " + e;
        }
    }

    /** Why a frame was not transferred (T_WHY): 1 no request, 2 stale, 3 other item. */
    static void untraced(IsoGameCharacter c, int why) {
        if (tracing && c != null) {
            double[] t = trace(c);
            t[T_FRAMES]++;
            t[T_APPLIED] = 0;
            t[T_WHY] = why;
        }
    }

    /** The left hand in the weapon's space from the engine's model transforms (cm, hand X axis). */
    static void traceModel(AnimationPlayer ap) {
        try {
            if (ap.parentPlayer != null) {
                return;
            }
            IsoGameCharacter c = ap.getIsoGameCharacter();
            double[] t = c == null ? null : traces.get(c);
            SkinningData sd = ap.getSkinningData();
            if (t == null || sd == null) {
                return;
            }
            int prop = index(sd, "Bip01_Prop1"), lh = index(sd, "Bip01_L_Hand"), rh = index(sd, "Bip01_R_Hand");
            if (prop < 0 || lh < 0 || rh < 0) {
                return;
            }
            double[] P = read(ap.getModelTransformAt(prop), new double[16]);
            double[] HL = read(ap.getModelTransformAt(lh), new double[16]);
            double[] HR = read(ap.getModelTransformAt(rh), new double[16]);
            double[] XR = mulNew(inverse(P), HR); // the right wrist on the weapon (cm)
            for (int i = 0; i < 3; i++) {
                t[T_RHAND + i] = XR[3 + 4 * i] / CM;
            }
            double[] X = new double[16];
            mul(inverse(P), HL, X);
            t[T_HAND] = X[3] / CM;
            t[T_HAND + 1] = X[7] / CM;
            t[T_HAND + 2] = X[11] / CM;
            double n = Math.sqrt(X[0] * X[0] + X[4] * X[4] + X[8] * X[8]);
            t[T_AXIS] = X[0] / n;
            t[T_AXIS + 1] = X[4] / n;
            t[T_AXIS + 2] = X[8] / n;
            // the weapon and the left hand on the chest: a jump of both together (a clip without a
            // grip class, a turn) does not show on the hand-on-weapon above
            int chest = index(sd, "Bip01_Spine1");
            if (chest >= 0) {
                double[] C = inverse(read(ap.getModelTransformAt(chest), new double[16]));
                double[] G = new double[16], L = new double[16];
                mul(C, P, G);
                mul(C, HL, L);
                double[] RC = mulNew(C, HR);
                double[] gs = columnScales(G);
                for (int i = 0; i < 3; i++) {
                    t[T_GUN + i] = G[3 + 4 * i] / CM;
                    t[T_LHAND + i] = L[3 + 4 * i] / CM;
                    t[T_RCHEST + i] = RC[3 + 4 * i] / CM;
                    t[T_BORE + i] = G[1 + 4 * i] / gs[1]; // the weapon's bore (+Y) and top (+Z) on the chest
                    t[T_TOP + i] = G[2 + 4 * i] / gs[2];
                }
            }
            t[T_TURN] = c.isTurning90() || c.isTurningAround() || "turning".equals(c.getCurrentActionContextStateName()) ? 1 : 0;
            t[T_MODELS]++;
        } catch (Throwable e) {
            lastError = e.toString();
        }
    }

    static void apply(AnimationPlayer ap) {
        // Every AnimationPlayer passes here (every zombie, every frame) while any request exists: without a request
        // of its character and without QA tracing it returns before the two variable lookups and the clock
        // (performance pass 23.10.2026; the same result, as both branches below only return).
        if (ap.parentPlayer != null) {
            return; // attached models copy the character's already transferred bones
        }
        if (!oneHandExpected.isEmpty()) {
            oneHandExpected.remove(ap);
        }
        IsoGameCharacter c = ap.getIsoGameCharacter();
        Request r = c == null ? null : requests.get(c);
        if (r == null && !tracing && !PerfLegacy.on) {
            return;
        }
        long t0 = System.nanoTime();
        try {
            // Loose-magazine actions use both authored hands even while a weapon
            // remains equipped in inventory. Never transfer either hand to that gun.
            if (c != null && ("InsertBullets".equals(c.getVariableString("PerformingAction"))
                    || "RemoveBullets".equals(c.getVariableString("PerformingAction")))) {
                untraced(c, 4);
                return;
            }
            if (r == null) {
                untraced(c, 1);
                return;
            }
            calls++;
            // Stale only when Lua missed both the time and its world frames: after a 0.7 s stall (QA screenshot)
            // the first frame was dropped to the bare clip - the support wrist jumped 6 cm and 40-50 degrees.
            if (t0 - r.stamp > STALE_NS && !FpPose.freshFrame(r.stampFrame, FpRecoil.worldFrame())) {
                rejStale++;
                untraced(c, 2);
                return;
            }
            boolean primaryMatch = c.getPrimaryHandItem() == r.item;
            zombie.inventory.InventoryItem secItem = c.getSecondaryHandItem();
            boolean secondaryPassive = !primaryMatch && secItem == r.item && r.hg != null;
            if (!primaryMatch && !secondaryPassive) {
                rejItem++;
                untraced(c, 3);
                return;
            }
            boolean offhandPrimary = primaryMatch && secItem != null && secItem != r.item;
            double offhandActW = offhandPrimary ? smooth(ownActionTrackWeight(ap)) : 1;
            if (offhandPrimary) {
                syncOffhandMask(c, secItem, offhandActW > 1e-4);
            }
            SkinningData sd = ap.getSkinningData();
            AnimatorsBoneTransform[] bt = ap.boneTransforms;
            if (sd == null || bt == null) {
                return;
            }
            int n = Math.min(sd.numBones(), bt.length);
            int lc = index(sd, "Bip01_L_Clavicle"), ua = index(sd, "Bip01_L_UpperArm"), fa = index(sd, "Bip01_L_Forearm"), lh = index(sd, "Bip01_L_Hand");
            int rc = index(sd, "Bip01_R_Clavicle"), rua = index(sd, "Bip01_R_UpperArm"), rfa = index(sd, "Bip01_R_Forearm"), rh = index(sd, "Bip01_R_Hand");
            int prop = index(sd, "Bip01_Prop1");
            if (lc < 0 || ua < 0 || fa < 0 || lh < 0 || rc < 0 || rua < 0 || rfa < 0 || rh < 0 || prop < 0 || n > 96
                    || parent(sd, ua) != lc || parent(sd, fa) != ua || parent(sd, lh) != fa
                    || parent(sd, rua) != rc || parent(sd, rfa) != rua || parent(sd, rh) != rfa || parent(sd, prop) < 0 || parent(sd, lc) < 0) {
                rejSkeleton++;
                skeleton = "bones=" + n + " lc=" + lc + " ua=" + ua + " fa=" + fa + " lh=" + lh + " rc=" + rc + " rh=" + rh + " prop=" + prop;
                return;
            }
            double[][] model = MODEL.get();
            Matrix4f tmp = TMP.get();
            for (int i = 0; i < n; i++) { // engine order: parents first
                bt[i].getMatrix(tmp);
                double[] local = read(tmp, new double[16]);
                int p = parent(sd, i);
                if (p < 0) {
                    System.arraycopy(local, 0, model[i], 0, 16);
                } else {
                    mul(model[p], local, model[i]);
                }
            }
            if (frameLog) { // QA (FB7): the clip's support wrist, elbow and weapon on the chest, before every layer
                int ch = index(sd, "Bip01_Spine1");
                if (ch >= 0) {
                    double[] ic = inverse(model[ch]), w = pos(mulNew(ic, model[lh])), e = pos(mulNew(ic, model[fa])), pp = framePose(ic, model[prop]);
                    clips.put(c, new double[] {w[0], w[1], w[2], e[0], e[1], e[2], pp[0], pp[1], pp[2], pp[3], pp[4], pp[5], pp[6], pp[7], pp[8]});
                }
            }
            double[] q = r.p;
            double[] P0 = model[prop], HL = model[lh], HR0 = model[rh];
            if (secondaryPassive) {
                int p2 = index(sd, "Bip01_Prop2");
                if (p2 >= 0 && p2 < n && parent(sd, p2) >= 0) {
                    double gripL = handgunRight(c, r, t0);
                    double[] refR = c.isFemale() ? HANDGUN_REF_R_F : HANDGUN_REF_R_M;
                    double[] anchorR = sub(refR, new double[] {q[0], q[1], q[2]});
                    if (handgunGrip && r.hgShift != null && gripL > 1e-4) {
                        anchorR = add(anchorR, scale(r.hgShift[1], gripL));
                    }
                    double[] anchorL = {-anchorR[0], anchorR[1], anchorR[2]};
                    double[] rLhP2 = mirrorHandToProp(c.isFemale() ? HANDGUN_REF_HR_P1_F : HANDGUN_REF_HR_P1_M);
                    double[] P2n = model[p2].clone();
                    setRotation(P2n, mul3(unitRotation(HL), rLhP2), columnScales(model[p2]));
                    setPos(P2n, sub(pos(HL), rot(P2n, anchorL)));
                    double[] tmpL = new double[16];
                    mul(inverse(model[parent(sd, p2)]), P2n, tmpL);
                    bt[p2].set(write(tmpL, tmp));
                    if (fingerFit && handgunGrip) {
                        double[][] mir = {mirrorFinger(r.hg[2]), mirrorFinger(r.hg[3]), r.hg[2], r.hg[3]};
                        r.hgW[0] = gripL;
                        r.hgW[1] = 0;
                        handgunFingers(sd, bt, lh, -1, mir, r.hgW, tmp);
                    }
                    double[] rowP2 = makeOneHandRow(lh, p2, parent(sd, p2), 1.0, HL, P2n);
                    if (c.getPrimaryHandItem() != null && parent(sd, prop) >= 0) {
                        oneHandExpected.put(ap, new double[][] {rowP2, makeOneHandRow(rh, prop, parent(sd, prop), 1.0, HR0, P0)});
                    } else {
                        oneHandExpected.put(ap, new double[][] {rowP2});
                    }
                }
                r.turnW = 0;
                r.turnHold = null;
                r.held = null;
                expected.remove(ap);
                applied++;
                return;
            }
            if (offhandPrimary && offhandActW <= 1e-4) {
                double gripR = r.hg != null ? handgunRight(c, r, t0) : 0;
                double[] refR = c.isFemale() ? HANDGUN_REF_R_F : HANDGUN_REF_R_M;
                double[] refHrP1 = c.isFemale() ? HANDGUN_REF_HR_P1_F : HANDGUN_REF_HR_P1_M;
                double[] anchorR = sub(refR, new double[] {q[0], q[1], q[2]});
                if (handgunGrip && r.hgShift != null && gripR > 1e-4) {
                    anchorR = add(anchorR, scale(r.hgShift[1], gripR));
                }
                double[] Pn = seatOneHandProp1(P0, HR0, refHrP1, anchorR);
                double[] tmpL = new double[16];
                double[] rH1 = HR0;
                double[] mgR = mags.get(c);
                boolean rackRight = mgR != null && mgR[0] == 3 && mgR.length >= 17 && mgR[13] > 0.5;
                if (rackRight) {
                    double[] XR = mulNew(inverse(Pn), HR0);
                    double wr = mgR.length >= 18 ? Math.max(0, Math.min(1, mgR[17])) : 0;
                    if (wr > 0) {
                        double[] shift = new double[] {wr * (mgR[7] + mgR[1] - mgR[4] + q[0] - XR[3]),
                                wr * (mgR[8] + mgR[2] - mgR[5] + q[1] - XR[7]),
                                wr * (mgR[9] + mgR[3] - mgR[6] + q[2] - XR[11])};
                        double[] goal = HR0.clone();
                        setPos(goal, add(pos(HR0), rot(Pn, shift)));
                        double[] UAn = new double[16], FAn = new double[16], Hn = new double[16];
                        solveArm(model[rua], model[rfa], HR0, goal, UAn, FAn, Hn);
                        ArmNatural.solved(c, 1, armPole(model[rua], model[rfa], goal, null, 0));
                        mul(inverse(model[rc]), UAn, tmpL);
                        bt[rua].set(write(tmpL, tmp));
                        mul(inverse(UAn), FAn, tmpL);
                        bt[rfa].set(write(tmpL, tmp));
                        mul(inverse(FAn), Hn, tmpL);
                        bt[rh].set(write(tmpL, tmp));
                        rH1 = Hn;
                        racked++;
                    }
                }
                if (fingerFit && handgunGrip && r.hg != null) {
                    FpPose.Request fpr = FpPose.requests.get(c);
                    boolean fpRight = fpr != null && fpr.fpW > 0.999 && fpr.fingerFit[1] > 0.999;
                    r.hgW[0] = 0;
                    r.hgW[1] = fpRight ? 0 : gripR;
                    handgunFingers(sd, bt, -1, rh, r.hg, r.hgW, tmp);
                } else if (r.hg != null) {
                    r.hgW[0] = r.hgW[1] = 0;
                }
                mul(inverse(model[parent(sd, prop)]), Pn, tmpL);
                bt[prop].set(write(tmpL, tmp));
                int p2Off = mgR == null ? index(sd, "Bip01_Prop2") : -1;
                double[] rowP1 = makeOneHandRow(rh, prop, parent(sd, prop), 1.0, rH1, Pn);
                if (p2Off >= 0 && p2Off < n && parent(sd, p2Off) >= 0) {
                    oneHandExpected.put(ap, new double[][] {rowP1, makeOneHandRow(lh, p2Off, parent(sd, p2Off), 1.0, HL, model[p2Off])});
                } else {
                    oneHandExpected.put(ap, new double[][] {rowP1});
                }
                r.turnW = 0;
                r.turnHold = null;
                r.held = null;
                expected.remove(ap);
                applied++;
                if (tracing) {
                    double[] t = trace(c);
                    t[T_FRAMES]++;
                    t[T_W] = 0;
                    t[T_HW] = 0;
                    t[T_APPLIED] = 1;
                    t[T_WHY] = 0;
                }
                return;
            }
            // Left hand in the clip weapon's space: contact weight and transfer.
            double[] X = new double[16];
            mul(inverse(P0), HL, X);
            // Own high-ready carry: weight from the clip weapon (bore up, butt off the shoulder).
            double hw = 0;
            if (q[32] > 0.5) {
                double ws0 = 0;
                if (q[18] > 0.5) {
                    ws0 = smooth((SHOULDER_FAR - dist(point(P0, q[22], q[23], q[24]), point(model[rc], q[25], q[26], q[27])))
                            / (SHOULDER_FAR - SHOULDER_NEAR));
                }
                double[] bore0 = normalize(new double[] {P0[1], P0[5], P0[9]});
                double elevation = Math.asin(Math.max(-1, Math.min(1, bore0[1])));
                lastElevationDeg = Math.toDegrees(elevation);
                hw = smooth((elevation - HIGH_FROM) / (HIGH_TO - HIGH_FROM)) * (1 - ws0);
                lastHigh = hw;
            }
            double[] P = P0, HR = HR0;
            boolean turned = false;
            if (hw > 0) { // the weapon and the right hand turn about the right wrist to the own bore
                double[] unit = unitRotation(P0);
                double[] turn = mul3(slerp(unit, boreRotation(q[48], q[49]), hw), transpose3(unit));
                double[] wristAt = pos(HR0);
                double[] M = new double[16];
                for (int i = 0; i < 3; i++) {
                    for (int j = 0; j < 3; j++) {
                        M[i * 4 + j] = turn[i * 3 + j];
                    }
                    M[i * 4 + 3] = wristAt[i] - (turn[i * 3] * wristAt[0] + turn[i * 3 + 1] * wristAt[1] + turn[i * 3 + 2] * wristAt[2]);
                }
                M[15] = 1;
                P = mulNew(M, P0);
                HR = mulNew(M, HR0);
                turned = true;
            }
            double liftRaise = 0;
            if (lowLift && q[61] > 0.5) { // low ready on a front support: the weapon to the hand
                double[] need = lowLift(model, q, P, HR, P0, ua, fa, lh);
                double raise = need[0], pull = need[1];
                if (r.liftAt != 0 && t0 - r.liftAt < HOLD_RESET_NS) {
                    double a = 1 - Math.exp(-(t0 - r.liftAt) / 1e9 / LOW_LIFT_TAU_S);
                    raise = r.liftRaise + (raise - r.liftRaise) * a;
                    pull = r.liftPull + (pull - r.liftPull) * a;
                }
                // Patrol keeps its authored down/across bore; lowBack and all other IK stay.
                double liftWeight = 1 - Math.max(0, Math.min(1, q[62]));
                raise *= liftWeight;
                pull *= liftWeight;
                r.liftRaise = raise;
                r.liftPull = pull;
                r.liftAt = t0;
                double[] b1 = normalize(new double[] {P[1], P[5], P[9]});
                double[] axis = cross(b1, new double[] {0, 1, 0});
                if ((raise > 1e-4 || pull > 1e-6) && norm(axis) > 1e-9) {
                    axis = normalize(axis);
                    double[] M = rigid(axis, raise, pos(HR));
                    double[] b2 = rotateVec(axis, raise, b1);
                    M[3] -= b2[0] * pull;
                    M[7] -= b2[1] * pull;
                    M[11] -= b2[2] * pull;
                    P = mulNew(M, P);
                    HR = mulNew(M, HR);
                    turned = true;
                    liftRaise = raise;
                    lowLifts++;
                    maxLowRaiseDeg = Math.max(maxLowRaiseDeg, Math.toDegrees(raise));
                }
            }
            // W10 item 2: Viewpoint's third person - Tarkov's recoil on the weapon; the right hand turns with it (FpRecoil.thirdPerson)
            double[] kick = FpRecoil.thirdPerson(ap, c, P, t0);
            if (kick != null) {
                P = mulNew(kick, P);
                HR = mulNew(kick, HR);
                turned = true;
            }
            double[] bore = normalize(new double[] {P[1], P[5], P[9]});
            double[] seat = rot(P, new double[] {q[0], q[1], q[2]});
            // Pistol in both hands: the shooting hand shifted on the grip (HG_*) - the weapon moves the other way under it,
            // back to the clip's seat while a timed action releases the hand
            double gripR = r.hg != null ? handgunRight(c, r, t0) : 0;
            if (handgunGrip && r.hgShift != null && gripR > 1e-4) {
                seat = sub(seat, rot(P, scale(r.hgShift[1], gripR)));
            }
            if (handgunGrip && r.hg != null && gripR > 1e-4) {
                // W10 pistolgrip: held in the shooting hand, the weapon takes its place from the hand at the reference grip
                // (HANDGUN_REF_R, the aim clip) instead of from the clip's Bip01_Prop1: a blend of two clips (the attack
                // over the aim) interpolates the hand chain and the root-child prop apart and slid the hand 0.4 cm on the
                // grip; every authored G006 clip keeps the hand within 0.22 cm of the reference, a single clip is unchanged
                double[] refR = c.isFemale() ? HANDGUN_REF_R_F : HANDGUN_REF_R_M;
                seat = add(seat, scale(sub(sub(pos(HR), pos(P)), rot(P, refR)), gripR));
            }
            // Butt: shouldered -> into the pocket; otherwise never further back than the reference stock.
            double s = 0, ws = 0;
            if (q[18] > 0.5) {
                double[] pocket = point(model[rc], q[25], q[26], q[27]);
                double[] refButt = point(P, q[22], q[23], q[24]);
                double[] newButt = add(point(P, q[19], q[20], q[21]), seat);
                ws = smooth((SHOULDER_FAR - dist(refButt, pocket)) / (SHOULDER_FAR - SHOULDER_NEAR));
                if (liftRaise > 0) { // lifted to the hand: the butt leaves the pocket
                    ws *= 1 - smooth(liftRaise / LOW_POCKET_FADE);
                }
                s = ws * dot(sub(pocket, newButt), bore) + (1 - ws) * Math.max(0, dot(sub(refButt, newButt), bore));
                s = Math.max(-MAX_BUTT, Math.min(MAX_BUTT, s));
                if (s > 0) {
                    double reach = dist(pos(model[rfa]), pos(model[rua])) + dist(pos(HR), pos(model[rfa])) - REACH_MARGIN;
                    s -= Math.min(s, slideBack(add(pos(HR), scale(bore, s)), pos(model[rua]), reach, bore));
                }
                if (Math.abs(s) > 1e-6) {
                    butts++;
                    maxButtUnits = Math.max(maxButtUnits, Math.abs(s));
                }
            }
            double[] Pn = P.clone();
            setPos(Pn, add(pos(P), add(seat, scale(bore, s))));
            double[] oneHandP1 = null;
            if (offhandPrimary && offhandActW < 1) {
                double[] refR = c.isFemale() ? HANDGUN_REF_R_F : HANDGUN_REF_R_M;
                double[] anchorR = sub(refR, new double[] {q[0], q[1], q[2]});
                if (handgunGrip && r.hgShift != null && gripR > 1e-4) {
                    anchorR = add(anchorR, scale(r.hgShift[1], gripR));
                }
                double[] oneHandPos = sub(pos(HR0), rot(Pn, anchorR));
                oneHandP1 = Pn.clone();
                setPos(oneHandP1, oneHandPos);
                setPos(Pn, lerp(oneHandPos, pos(Pn), offhandActW));
            }
            double[] tmpL = new double[16];
            double[] rUA = model[rua], rFA = model[rfa], rH = model[rh];
            // Right-hand rack (mode 3, right): the clip's right wrist from its grip onto this weapon's handle.
            double[] mgR = mags.get(c);
            boolean rackRight = mgR != null && mgR[0] == 3 && mgR.length >= 17 && mgR[13] > 0.5;
            double[] rackShift = null;
            double rightActW = 0;
            if (rackRight) {
                double[] XR = mulNew(inverse(P0), HR0);
                double wr;
                double[] shift;
                if (mgR.length >= 18) {
                    // FB4-A: the fire-mode gesture (no body clip; TW/WeaponInspect): the hand from where it holds to
                    // Tarkov's selector point (grab) on this weapon (own - reference selector, dR) by the gesture's weight
                    wr = Math.max(0, Math.min(1, mgR[17]));
                    shift = new double[] {wr * (mgR[7] + mgR[1] - mgR[4] + q[0] - XR[3]), wr * (mgR[8] + mgR[2] - mgR[5] + q[1] - XR[7]),
                            wr * (mgR[9] + mgR[3] - mgR[6] + q[2] - XR[11])};
                } else {
                    double d = segmentDistance(pos(XR), new double[] {mgR[7], mgR[8], mgR[9]}, new double[] {mgR[10], mgR[11], mgR[12]});
                    double dHold = dist(pos(XR), new double[] {mgR[14], mgR[15], mgR[16]});
                    wr = smooth(dHold / (dHold + d + 1e-9));
                    shift = new double[] {wr * (mgR[1] - mgR[4] + q[0]), wr * (mgR[2] - mgR[5] + q[1]), wr * (mgR[3] - mgR[6] + q[2])};
                }
                if (tracing) {
                    trace(c)[T_RACK] = wr;
                }
                if (wr > 0) {
                    rightActW = wr;
                    rackShift = rot(P, shift);
                    racked++;
                }
            }
            if (Math.abs(s) > 1e-6 || turned || rackShift != null) {
                double[] goal = HR.clone();
                setPos(goal, add(pos(HR), scale(bore, s)));
                if (rackShift != null) {
                    setPos(goal, add(pos(goal), rackShift));
                }
                double[] UAn = new double[16], FAn = new double[16], Hn = new double[16];
                solveArm(model[rua], model[rfa], HR0, goal, UAn, FAn, Hn);
                ArmNatural.solved(c, 1, armPole(model[rua], model[rfa], goal, null, 0));
                rUA = UAn;
                rFA = FAn;
                rH = Hn;
                mul(inverse(model[rc]), UAn, tmpL);
                bt[rua].set(write(tmpL, tmp));
                mul(inverse(UAn), FAn, tmpL);
                bt[rfa].set(write(tmpL, tmp));
                mul(inverse(FAn), Hn, tmpL);
                bt[rh].set(write(tmpL, tmp));
            }
            // Left: rigid transfer of the reference hand from its hold to the weapon's support.
            double away = Math.sqrt(sq(X[3] - q[15]) + sq(X[7] - q[16]) + sq(X[11] - q[17]));
            double actGoal = r.acting ? 1 : 0;
            r.actW = r.actWAt == 0 || t0 - r.actWAt > HOLD_RESET_NS ? actGoal
                    : r.actW + (actGoal - r.actW) * (1 - Math.exp(-(t0 - r.actWAt) / 1e9 / ACT_TAU_S));
            r.actWAt = t0;
            if (!actRamp) {
                r.actW = actGoal;
            }
            double wIdle = smooth((RELEASE_FAR - away) / (RELEASE_FAR - RELEASE_NEAR));
            double w = wIdle + (smooth((CONTACT_FAR - away) / (CONTACT_FAR - CONTACT_NEAR)) - wIdle) * r.actW;
            // Bug wave 324, item 1: a round-load clip releases the support hand off its hold (ROUND_NEAR..ROUND_FAR).
            double rf = roundRelease && r.acting ? roundWeight(ap) * smooth((away - ROUND_NEAR) / (ROUND_FAR - ROUND_NEAR)) : 0;
            if (rf > 0) {
                w *= 1 - rf;
                roundReleases++;
            }
            if (offhandPrimary && offhandActW < 1) {
                w *= offhandActW;
            }
            lastWeight = w;
            double[] XL = X; // the reference hand, locked on its hold while near it
            if (q[50] > 0.5) {
                double kIdle = smooth((LOCK_IDLE_FAR - away) / (LOCK_IDLE_FAR - LOCK_IDLE_NEAR));
                double k = kIdle + (smooth((LOCK_FAR - away) / (LOCK_FAR - LOCK_NEAR)) - kIdle) * r.actW;
                if (r.lockAt != 0 && t0 - r.lockAt < HOLD_RESET_NS) {
                    k = r.lock + (k - r.lock) * (1 - Math.exp(-(t0 - r.lockAt) / 1e9 / LOCK_TAU_S));
                }
                r.lock = k;
                r.lockAt = t0;
                if (tracing) {
                    trace(c)[T_LOCK] = k;
                }
                if (k > 0) {
                    double[] hold = {q[51], q[52], q[53], q[15], q[54], q[55], q[56], q[16], q[57], q[58], q[59], q[17], 0, 0, 0, 1};
                    XL = X.clone();
                    setPos(XL, lerp(pos(X), pos(hold), k));
                    setRotation(XL, slerp(unitRotation(X), unitRotation(hold), k), columnScales(X));
                    locks++;
                }
            }
            double[] A = {q[3], q[4], q[5], q[6], q[7], q[8], q[9], q[10], q[11], q[12], q[13], q[14], 0, 0, 0, 1};
            double[] AX = new double[16], target = new double[16];
            mul(A, XL, AX);
            mul(Pn, AX, target);
            if (handgunGrip && r.hgShift != null) { // pistol in both hands: the support hand's hold with its shift on the grip
                // while the hand is locked on its hold (a one-hand carry lets it go: third-person idle, the hand ~44 cm off)
                setPos(target, add(pos(target), rot(Pn, scale(r.hgShift[0], q[50] > 0.5 ? r.lock : 1))));
            }
            if (hw > 0) { // the own high-ready hold
                double[] A2 = {q[33], q[34], q[35], q[36], q[37], q[38], q[39], q[40], q[41], q[42], q[43], q[44], 0, 0, 0, 1};
                double[] AX2 = new double[16], high = new double[16];
                mul(A2, XL, AX2);
                mul(Pn, AX2, high);
                setRotation(target, slerp(unitRotation(target), unitRotation(high), hw), columnScales(target));
                setPos(target, lerp(pos(target), pos(high), hw));
                highs++;
            }
            double lowBack = 0;
            if (q[60] > 0) { // low ready, bare handguard: the support hand back along it as far as the reach needs
                double[] b0 = normalize(new double[] {P0[1], P0[5], P0[9]});
                double lw = smooth((-Math.asin(Math.max(-1, Math.min(1, b0[1]))) - LOW_FROM) / (LOW_TO - LOW_FROM));
                if (lw > 0) {
                    lowBack = lw * lowBack(model, q, Pn, pos(target), ua, fa, lh);
                    setPos(target, sub(pos(target), scale(bore, lowBack)));
                }
            }
            double aimW = ws; // butt in the pocket, not lowered nor raised: aiming
            if (aimW > 0) {
                double[] b0 = normalize(new double[] {P0[1], P0[5], P0[9]});
                double el0 = Math.asin(Math.max(-1, Math.min(1, b0[1])));
                aimW *= (1 - smooth((-el0 - LOW_FROM) / (LOW_TO - LOW_FROM))) * (1 - hw);
            }
            double clavMax = CLAVICLE_MAX + (CLAVICLE_MAX_AIM - CLAVICLE_MAX) * aimW;
            double[] PX = new double[16]; // a released hand (reload, equip) moves with this weapon
            mul(Pn, X, PX);
            if (rf > 0) { // released by a round-load clip: its place relative to this weapon's grip (no seat shift dR)
                setPos(PX, sub(pos(PX), scale(seat, rf)));
            }
            double[] LC = model[lc], UA = model[ua], FA = model[fa], H = HL;
            boolean clavicleMoved = false;
            double frameClavicle = 0, frameSlide = 0, frameReach = 0;
            // FB7: a free arm (FREE_*): a pistol in both hands - no pole of the weapon, its high-ready hold or a grip hold
            boolean free = freeArm && r.hg != null && q[28] < 0.5 && q[32] < 0.5 && !(holdOn && r.hold != null && r.wrap != null);
            double clavSoft = free ? FREE_CLAVICLE_SOFT * w : 0;
            if (w > 0) {
                double[] tp = pos(target);
                frameReach = dist(tp, pos(UA)) / (dist(pos(FA), pos(UA)) + dist(pos(H), pos(FA)));
                double theta = clavicleAngle(LC, UA, FA, H, tp, clavMax, clavSoft);
                if (theta > 0) {
                    double[] blended = lerp(pos(PX), tp, w);
                    theta = clavicleAngle(LC, UA, FA, H, blended, clavMax, clavSoft);
                    if (theta > 0) {
                        double[] axis = normalize(cross(sub(pos(UA), pos(LC)), sub(blended, pos(LC))));
                        double[] rigid = rigid(axis, theta, pos(LC));
                        LC = mulNew(rigid, LC);
                        UA = mulNew(rigid, UA);
                        FA = mulNew(rigid, FA);
                        H = mulNew(rigid, H);
                        clavicleMoved = true;
                        clavicles++;
                        frameClavicle = theta;
                    }
                }
                boolean bare = q[63] > 0.5;
                double reach = bare ? comfortReach(UA, FA, H) : dist(pos(FA), pos(UA)) + dist(pos(H), pos(FA)) - REACH_MARGIN;
                double slide = slideBack(tp, pos(UA), reach, bore);
                frameSlide = slide;
                if (slide > 0) {
                    slides++;
                    setPos(target, sub(tp, scale(bore, slide)));
                }
            }
            // FB2-N: the hold chosen on the grip surface together with the arm (holdFit); its swivel becomes the elbow pole
            int[] chain = {index(sd, "Bip01_Pelvis"), index(sd, "Bip01_Spine"), index(sd, "Bip01_Spine1"), index(sd, "Bip01_Neck")};
            double[] holdPoleDir = null;
            if (holdOn && r.hold != null && r.wrap != null && w > 0) {
                holdPoleDir = holdStep(r, sd, bt, tmp, model, chain, c.isFemale(), lh, fa, Pn, UA, FA, H, target, q, t0, c.isPlayerMoving());
            } else {
                r.holdLast = null;
            }
            double[] held = mulNew(inverse(Pn), target);
            if (r.held != null && t0 - r.heldAt < HOLD_RESET_NS) {
                double a = 1 - Math.exp(-(t0 - r.heldAt) / 1e9 / HOLD_TAU_S);
                setPos(held, lerp(pos(r.held), pos(held), a));
                setRotation(held, slerp(unitRotation(r.held), unitRotation(held), a), columnScales(held));
            }
            r.held = held;
            r.heldAt = t0;
            if (c instanceof zombie.characters.IsoPlayer player && player.isLocalPlayer()
                    && holdOn && r.hold != null && r.wrap != null && r.holdLast != null
                    && r.holdShown != null && r.holdPoleW != null && r.holdAt == r.heldAt) {
                POSED_SURFACE.set(new SurfaceGrip(ap, r, FpRecoil.worldFrame(), held.clone(), r.holdPoleW.clone()));
            }
            target = mulNew(Pn, held);
            double[] goal = HL.clone();
            setPos(goal, lerp(pos(PX), pos(target), w));
            setRotation(goal, slerp(unitRotation(PX), unitRotation(target), w), columnScales(HL));
            // Reload: the magazine bone and the support hand holding the magazine (mags, mode 2).
            double[] mg = mags.get(c);
            int p2 = mg == null ? -1 : index(sd, "Bip01_Prop2");
            double[] magWorld = null;
            double wm = 0;
            if (p2 >= 0 && p2 < n && parent(sd, p2) >= 0) {
                if (mg[0] == 1 && mg.length >= 13) {
                    magWorld = mulNew(Pn, rows(mg, 1));
                } else if (mg[0] == 2 && mg.length >= 31) {
                    double[] own = rows(mg, 1), ref = rows(mg, 13), C2 = model[p2];
                    double[] X2 = mulNew(inverse(P0), C2);
                    double ww = mg.length >= 39 && mg[38] > 0.5 ? 1 : smooth((MAG_FAR - dist(pos(X2), pos(ref))) / (MAG_FAR - MAG_NEAR));
                    double[] anchored = mulNew(Pn, mulNew(own, mulNew(inverse(ref), X2)));
                    magWorld = C2.clone();
                    setPos(magWorld, lerp(pos(C2), pos(anchored), ww));
                    if (mg.length >= 38 && mg[37] > 0) {
                        magWorld[11] -= mg[37] * (1 - ww);
                    }
                    setRotation(magWorld, slerp(unitRotation(C2), unitRotation(anchored), ww), columnScales(C2));
                    double dGrip = dist(pos(HL), point(C2, mg[25], mg[26], mg[27])), dSlap = dist(pos(HL), point(C2, mg[28], mg[29], mg[30]));
                    wm = smooth((MAG_HOLD_FAR - Math.min(dGrip, dSlap)) / (MAG_HOLD_FAR - MAG_HOLD_NEAR));
                    if (wm > 0) {
                        double[] onRef = mulNew(inverse(C2), HL);
                        if (mg.length >= 37) { // the hand on this weapon's magazine body: shifted onto its own 3D cross-section
                            double t = dGrip + dSlap > 1e-9 ? dGrip / (dGrip + dSlap) : 0;
                            onRef[3] += mg[31] + (mg[34] - mg[31]) * t;
                            onRef[7] += mg[32] + (mg[35] - mg[32]) * t;
                            onRef[11] += mg[33] + (mg[36] - mg[33]) * t;
                        }
                        double[] onMag = mulNew(magWorld, onRef);
                        setPos(goal, lerp(pos(goal), pos(onMag), wm));
                        setRotation(goal, slerp(unitRotation(goal), unitRotation(onMag), wm), columnScales(HL));
                        magHeld++;
                    }
                }
            }
            if (mg != null && mg[0] == 3 && mg.length >= 13 && !rackRight) { // rack: the support hand onto this weapon's own charging handle
                double d = segmentDistance(pos(X), new double[] {mg[7], mg[8], mg[9]}, new double[] {mg[10], mg[11], mg[12]});
                double dHold = dist(pos(X), new double[] {q[15], q[16], q[17]});
                double wr = smooth(dHold / (dHold + d + 1e-9));
                if (wr > 0) {
                    double[] onOwn = add(pos(PX), rot(Pn, new double[] {mg[1] - mg[4], mg[2] - mg[5], mg[3] - mg[6]}));
                    setPos(goal, lerp(pos(goal), onOwn, wr));
                    setRotation(goal, slerp(unitRotation(goal), unitRotation(PX), wr), columnScales(HL));
                    wm = Math.max(wm, wr);
                    racked++;
                }
                if (tracing) {
                    trace(c)[T_RACK] = wr;
                }
            }
            double[] pole = null;
            if (q[28] > 0.5 && w > 0) { // weapon-space elbow direction
                pole = normalize(rot(Pn, new double[] {q[29], q[30], q[31]}));
                elbows++;
            }
            if (q[32] > 0.5 && hw > 0 && w > 0) { // model-space elbow of the high-ready hold
                double[] down = normalize(new double[] {q[45], q[46], q[47]});
                double[] mix = pole == null ? scale(down, hw) : add(scale(pole, 1 - hw), scale(down, hw));
                if (norm(mix) > 1e-9) {
                    pole = normalize(mix);
                }
            }
            double[] UAn = new double[16], FAn = new double[16], Hn = new double[16];
            if (holdPoleDir != null) {
                pole = holdPoleDir;
            }
            double residual;
            double[] elbowSide; // the solve's side, turned with the elbow below (ArmNatural.solved)
            if (free && pole == null) { // FB7: the clip arm's own swivel carried to the goal, the last of the reach soft
                double[] soft = softGoal(UA, FA, H, goal, FREE_SOFT_REACH * w);
                double[] carried = carriedSwivel(pos(UA), pos(FA), pos(H), pos(soft));
                solveArm(UA, FA, H, soft, UAn, FAn, Hn, carried, carried == null ? 0 : 1);
                residual = dist(pos(Hn), pos(goal));
                elbowSide = armPole(UA, FA, soft, carried, carried == null ? 0 : 1);
            } else {
                residual = solveArm(UA, FA, H, goal, UAn, FAn, Hn, pole, w * (1 - wm));
                elbowSide = armPole(UA, FA, goal, pole, w * (1 - wm));
            }
            ArmNatural.Bind armBind = twistCap ? ArmNatural.bindOf(sd, ua, fa, lh) : null;
            double[] upAxis = chain[0] >= 0 && chain[3] >= 0
                    ? normalize(sub(pos(model[chain[3]]), pos(model[chain[0]]))) : new double[] {0, 1, 0};
            final double[] hxArm = normalize(new double[] {Hn[0], Hn[4], Hn[8]});
            double armElevCap = Math.max(2 * CM, dot(sub(pos(FAn), pos(UAn)), upAxis) + 0.5 * CM);
            // the elbow out of the torso; FB4 (the user's item 17): also after a chosen hold (FB2-N) - its cost let the
            // forearm 3-5 cm into the body in low ready (bare handguards: MP18, VSK94, AK; DesireNaturalHD, vanilla).
            // W10 item 24: 3P bodies are always visible; clear the torso capsule outward up to SWIVEL_MAX (bounded by
            // shoulder elevation armElevCap and without pushing the elbow deeper medially across the sternum), and do
            // not let relieveTwist hike an actively cleared Low Ready elbow back up to shoulder height.
            if (armClear && chain[0] >= 0 && chain[1] >= 0 && chain[2] >= 0 && chain[3] >= 0) {
                double[] sh = pos(UAn), el = pos(FAn), wr = pos(Hn);
                double[] axis = normalize(sub(wr, sh));
                boolean fresh = r.elbowAt != 0 && t0 - r.elbowAt < ARM_RESET_NS;
                double[][] torso = c.isFemale() ? TORSO_F : TORSO_M;
                double need = 0;
                double[] ob = r.obstacle; // FB8: the belt box cleared as the torso
                if (clearAll(model, chain, sh, el, wr, torso, Pn, ob) > 0) {
                    double[] chestM = model[chain[2]];
                    double[] leftAxis = normalize(new double[] {chestM[1], chestM[5], chestM[9]});
                    double shLat = dot(sub(sh, pos(chestM)), leftAxis);
                    double chestLat0 = dot(sub(el, pos(chestM)), leftAxis);
                    double first = fresh && r.elbowSide != 0 ? r.elbowSide : -1, best = Double.MAX_VALUE;
                    boolean found = false;
                    for (double side : new double[] {first, -first}) {
                        double sideMax = side < 0 && ob == null ? SWIVEL_MAX : CLEAR_MAX;
                        double prev = 0;
                        for (double mag = CLEAR_STEP; mag <= sideMax + 1e-9 && !found; mag += CLEAR_STEP) {
                            double[] elT = add(sh, rotateVec(axis, side * mag, sub(el, sh)));
                            if (ob == null && dot(sub(elT, sh), upAxis) > armElevCap) {
                                break;
                            }
                            double chestLat = dot(sub(elT, pos(chestM)), leftAxis);
                            if (ob == null && chestLat < 0.5 * shLat && chestLat < chestLat0 - 1e-6) {
                                break;
                            }
                            double d = clearAll(model, chain, sh, elT, wr, torso, Pn, ob);
                            if (d <= 0) { // the smallest angle between the last two steps
                                double lo = prev, hi = mag;
                                for (int i = 0; i < 5; i++) {
                                    double mid = (lo + hi) / 2;
                                    if (clearAll(model, chain, sh, add(sh, rotateVec(axis, side * mid, sub(el, sh))), wr, torso, Pn, ob) <= 0) {
                                        hi = mid;
                                    } else {
                                        lo = mid;
                                    }
                                }
                                need = side * hi;
                                r.elbowSide = side;
                                found = true;
                            } else if (d < best) {
                                best = d;
                                need = side * mag;
                            }
                            prev = mag;
                        }
                        if (found) {
                            break;
                        }
                    }
                }
                double tau = need * r.elbowTurn < 0 ? CLEAR_RELEASE_S
                        : Math.abs(need) > Math.abs(r.elbowTurn) ? CLEAR_ATTACK_S : need == 0 ? CLEAR_FREE_S : CLEAR_RELEASE_S;
                if (fresh) {
                    double dt = Math.min(ARM_DT_MAX, (t0 - r.elbowAt) / 1e9), step = (need - r.elbowTurn) * (1 - Math.exp(-dt / tau));
                    r.elbowTurn += Math.max(-CLEAR_SPEED * dt, Math.min(CLEAR_SPEED * dt, step));
                } else {
                    r.elbowTurn = need;
                }
                r.elbowAt = t0;
                if (tracing) {
                    trace(c)[T_ELBOW] = Math.toDegrees(r.elbowTurn);
                }
                if (Math.abs(r.elbowTurn) > 1e-4) {
                    double[] turn = rigid(axis, r.elbowTurn, sh);
                    double[] ua2 = mulNew(turn, UAn), fa2 = mulNew(turn, FAn);
                    System.arraycopy(ua2, 0, UAn, 0, 16);
                    System.arraycopy(fa2, 0, FAn, 0, 16);
                    elbowSide = rotateVec(axis, r.elbowTurn, elbowSide);
                    elbowTurns++;
                }
                // W10 armsrest2: when the support wrist goal itself sits inside the upper-chest ellipse in 3P carry
                // (compact SMGs / sawed-off shotguns in Patrol: UZIPROPISTOL, VECTOR9/45, KEDR/KLIN, MP7A2, MP9, P90,
                // MP43SAWED), elbow swivel about shoulder->wrist cannot move the wrist out of the chest. Translate the
                // coupled weapon + both hands horizontally chest-ahead by the minimal distance within both arms'
                // REACH_MARGIN spheres that achieves clearDepth <= 0, leaving Aim, Low and 1P bit-identical.
                if (ob == null) {
                    FpPose.Request fpr0 = FpPose.requests.get(c);
                    double fpFade = fpr0 != null ? 1 - clamp(fpr0.fpW) : 1;
                    double carryClearW = (1 - aimW * (1 - clamp(q[62]))) * w * (1 - wm) * (1 - rightActW) * (oneHandP1 != null ? offhandActW : 1) * fpFade;
                    double postClearD = carryClearW > 1e-4 ? clearDepth(model, chain, pos(UAn), pos(FAn), pos(Hn), torso) : -1;
                    CarryPush pst = carryPushState.get(r);
                    if (postClearD > 0 || pst != null) {
                        double[] chestM = model[chain[2]];
                        double[] fwd = {chestM[2], 0, chestM[10]};
                        fwd = norm(fwd) > 1e-6 ? normalize(fwd) : normalize(new double[] {chestM[2], chestM[6], chestM[10]});
                        double rReach = dist(pos(model[rfa]), pos(model[rua])) + dist(pos(HR0), pos(model[rfa])) - REACH_MARGIN;
                        double lReach = dist(pos(FA), pos(UA)) + dist(pos(H), pos(FA)) - REACH_MARGIN;
                        double dMaxR = raySphereMax(pos(rH), pos(model[rua]), rReach, fwd);
                        double[] lc0 = model[lc], ua0 = model[ua], fa0 = model[fa], h0 = HL;
                        double[] axisMax = cross(sub(pos(ua0), pos(lc0)), sub(add(pos(Hn), scale(fwd, dMaxR)), pos(lc0)));
                        double[] uaMax = norm(axisMax) > 1e-9 ? pos(mulNew(rigid(normalize(axisMax), clavMax, pos(lc0)), ua0)) : pos(UA);
                        double dMax = Math.min(dMaxR, Math.max(raySphereMax(pos(Hn), pos(UA), lReach, fwd),
                                raySphereMax(pos(Hn), uaMax, lReach, fwd)));
                        double firstSide = fresh && r.elbowSide != 0 ? r.elbowSide : -1;
                        double needPush = 0;
                        if (postClearD > 0 && dMax > 1e-4) {
                            double[] lcS = new double[16], uaS = new double[16], faS = new double[16], hS = new double[16], sideS = new double[3];
                            double bestRem = postClearD, bestPush = 0, prevD = 0;
                            boolean done = false;
                            for (double stepD = Math.min(CM, dMax); !done; stepD = Math.min(stepD + CM, dMax)) {
                                if (stepD >= dMax - 1e-9) {
                                    done = true;
                                }
                                double[] res = carryClearedPose(model, chain, lc0, ua0, fa0, h0, LC, UA, FA, H, clavMax, clavSoft,
                                        goal, pole, w * (1 - wm), free, w, upAxis, torso, firstSide, scale(fwd, stepD), lcS, uaS, faS, hS, sideS);
                                if (res[0] == Double.POSITIVE_INFINITY) {
                                    break;
                                }
                                if (res[0] <= 0) {
                                    double lo = prevD, hi = stepD;
                                    for (int bi = 0; bi < 6; bi++) {
                                        double mid = (lo + hi) / 2;
                                        double[] rm = carryClearedPose(model, chain, lc0, ua0, fa0, h0, LC, UA, FA, H, clavMax, clavSoft,
                                                goal, pole, w * (1 - wm), free, w, upAxis, torso, firstSide, scale(fwd, mid), lcS, uaS, faS, hS, sideS);
                                        if (rm[0] <= 0) {
                                            hi = mid;
                                        } else {
                                            lo = mid;
                                        }
                                    }
                                    needPush = hi;
                                    break;
                                } else if (res[0] < bestRem) {
                                    bestRem = res[0];
                                    bestPush = stepD;
                                }
                                prevD = stepD;
                            }
                            if (needPush == 0 && bestPush > 0) {
                                needPush = bestPush;
                            }
                            needPush *= carryClearW;
                        }
                        boolean freshPush = pst != null && t0 - pst.at < ARM_RESET_NS;
                        double pushNow;
                        if (freshPush) {
                            double prevPush = pst.push;
                            double pTau = needPush > prevPush ? CLEAR_ATTACK_S : needPush <= 1e-6 ? CLEAR_FREE_S : CLEAR_RELEASE_S;
                            double dtP = Math.min(ARM_DT_MAX, (t0 - pst.at) / 1e9);
                            pushNow = prevPush + (needPush - prevPush) * (1 - Math.exp(-dtP / pTau));
                        } else {
                            pushNow = needPush;
                        }
                        pushNow = Math.max(0, Math.min(dMax, pushNow));
                        if (needPush <= 1e-6 && pushNow <= 1e-5) {
                            carryPushState.remove(r);
                        } else {
                            carryPushState.put(r, new CarryPush(t0, pushNow));
                            if (pushNow > 1e-5) {
                                double[] pushVec = scale(fwd, pushNow);
                                setPos(Pn, add(pos(Pn), pushVec));
                                if (oneHandP1 != null) {
                                    setPos(oneHandP1, add(pos(oneHandP1), pushVec));
                                }
                                if (magWorld != null && mg != null && mg[0] == 1) {
                                    setPos(magWorld, add(pos(magWorld), pushVec));
                                }
                                double[] rGoal = rH.clone();
                                setPos(rGoal, add(pos(rH), pushVec));
                                double[] rUAn = new double[16], rFAn = new double[16], rHn = new double[16];
                                solveArm(model[rua], model[rfa], HR0, rGoal, rUAn, rFAn, rHn);
                                ArmNatural.solved(c, 1, armPole(model[rua], model[rfa], rGoal, null, 0));
                                rUA = rUAn;
                                rFA = rFAn;
                                rH = rHn;
                                mul(inverse(model[rc]), rUAn, tmpL);
                                bt[rua].set(write(tmpL, tmp));
                                mul(inverse(rUAn), rFAn, tmpL);
                                bt[rfa].set(write(tmpL, tmp));
                                mul(inverse(rFAn), rHn, tmpL);
                                bt[rh].set(write(tmpL, tmp));

                                double[] lcP = new double[16], sideP = new double[3];
                                double[] resP = carryClearedPose(model, chain, lc0, ua0, fa0, h0, LC, UA, FA, H, clavMax, clavSoft,
                                        goal, pole, w * (1 - wm), free, w, upAxis, torso, firstSide, pushVec, lcP, UAn, FAn, Hn, sideP);
                                if (resP[4] > 0) {
                                    LC = lcP;
                                    clavicleMoved = true;
                                    frameClavicle = resP[4];
                                    clavicles++;
                                }
                                setPos(goal, add(pos(goal), pushVec));
                                elbowSide = sideP;
                                r.elbowTurn = resP[1];
                                if (Math.abs(resP[1]) > 1e-4) {
                                    r.elbowSide = Math.signum(resP[1]);
                                }
                                armElevCap = resP[2];
                                residual = resP[3];
                                if (tracing) {
                                    trace(c)[T_ELBOW] = Math.toDegrees(r.elbowTurn);
                                }
                            }
                        }
                    }
                }
                // FB8: the arm's depth in the belt box as drawn (QA: the FRM line's obCm)
                r.obLast = ob == null ? Double.NaN : obDepth(Pn, ob, pos(UAn), pos(FAn), pos(Hn));
            }
            if (armBind != null && q[28] < 0.5 && w > 0.999 && wm < 0.001 && holdPoleDir == null) {
                // Swivel the whole two-link arm before rolling one bone at the
                // elbow, keeping torso depth at or below the post-clearance baseline.
                boolean kept = r.reliefAt != 0 && t0 - r.reliefAt < ARM_RESET_NS && r.twistAt != 0 && t0 - r.twistAt < ARM_RESET_NS;
                final double[] rsh = pos(UAn), rel = pos(FAn), rwr = pos(Hn), rax = normalize(sub(rwr, rsh)), rup = upAxis;
                final double relevCap = Math.abs(r.elbowTurn) > 1e-4 && r.obstacle == null
                        ? Math.min(armElevCap, dot(sub(rel, rsh), rup) + 6 * CM) : armElevCap;
                final double relBend0 = Math.acos(Math.max(-1, Math.min(1, dot(normalize(sub(rwr, rel)), hxArm))));
                final double relBendCap = Math.max(Math.toRadians(80), relBend0 + 1e-6);
                final double[] fOb = r.obstacle, fPn = Pn;
                final boolean bodyOk = chain[0] >= 0 && chain[1] >= 0 && chain[2] >= 0 && chain[3] >= 0;
                final double[][] fTorso = c.isFemale() ? TORSO_F : TORSO_M;
                final double dTorso0 = bodyOk ? clearDepth(model, chain, rsh, rel, rwr, fTorso) : -1;
                java.util.function.DoubleUnaryOperator relDepth = turn -> {
                    double[] eT = add(rsh, rotateVec(rax, turn, sub(rel, rsh)));
                    if (dot(sub(eT, rsh), rup) > relevCap) {
                        return Double.POSITIVE_INFINITY;
                    }
                    if (fOb == null && Math.abs(turn) > 1e-9
                            && Math.acos(Math.max(-1, Math.min(1, dot(normalize(sub(rwr, eT)), hxArm)))) > relBendCap) {
                        return Double.POSITIVE_INFINITY;
                    }
                    double dTorso = !bodyOk ? -1 : clearDepth(model, chain, rsh, eT, rwr, fTorso);
                    return fOb == null ? dTorso : Math.max(dTorso, obDepth(fPn, fOb, rsh, eT, rwr));
                };
                double angle = relieveTwist(UAn, FAn, Hn, armBind, relDepth,
                        kept ? r.twist : Double.NaN, kept ? r.relief : 0, WRIST_TWIST_MAX);
                double dt = r.reliefAt == 0 ? 0 : Math.min(ARM_DT_MAX, (t0 - r.reliefAt) / 1e9);
                double relAng = r.reliefAt == 0 ? angle : r.relief + Math.max(-CLEAR_SPEED * dt, Math.min(CLEAR_SPEED * dt, angle - r.relief));
                if (Math.abs(relAng) > 1e-4 && fOb == null) {
                    double[] eR = add(rsh, rotateVec(rax, relAng, sub(rel, rsh)));
                    double allowClr = Math.max(0, dTorso0) + 1e-5;
                    boolean clearViol = bodyOk && clearDepth(model, chain, rsh, eR, rwr, fTorso) > allowClr;
                    if (dot(sub(eR, rsh), rup) > relevCap + 1e-9
                            || Math.acos(Math.max(-1, Math.min(1, dot(normalize(sub(rwr, eR)), hxArm)))) > relBendCap + 1e-9
                            || clearViol) {
                        double lo = 0, hi = 1;
                        for (int i = 0; i < 10; i++) {
                            double mid = (lo + hi) / 2;
                            double[] eM = add(rsh, rotateVec(rax, relAng * mid, sub(rel, rsh)));
                            boolean okClr = !bodyOk || clearDepth(model, chain, rsh, eM, rwr, fTorso) <= allowClr;
                            if (dot(sub(eM, rsh), rup) <= relevCap + 1e-9
                                    && Math.acos(Math.max(-1, Math.min(1, dot(normalize(sub(rwr, eM)), hxArm)))) <= relBendCap + 1e-9
                                    && okClr) {
                                lo = mid;
                            } else {
                                hi = mid;
                            }
                        }
                        relAng *= lo;
                    }
                }
                r.relief = relAng;
                r.reliefAt = t0;
                double[] sh = pos(UAn), axis = normalize(sub(pos(Hn), sh));
                double[] turn = rigid(axis, r.relief, sh);
                double[] u = mulNew(turn, UAn), f = mulNew(turn, FAn);
                System.arraycopy(u, 0, UAn, 0, 16);
                System.arraycopy(f, 0, FAn, 0, 16);
                elbowSide = rotateVec(axis, r.relief, elbowSide);
            } else { r.relief = 0; r.reliefAt = 0; }
            ArmNatural.solved(c, 0, elbowSide);
            if (limitWrist(FAn, Hn)) { // after the elbow turn: it changes the forearm's direction
                wrists++;
            }
            if (armBind != null) { // the relief's reference; the forearm's roll is ArmNatural's (the last layer, on the final points)
                double tw = continuousTwist(ArmNatural.tau(UAn, FAn, Hn, armBind), r.twist, r.twistAt != 0 && t0 - r.twistAt < ARM_RESET_NS);
                r.twist = tw;
                r.twistAt = t0;
                if (tracing) {
                    double[] t = trace(c);
                    t[T_TWIST] = Math.toDegrees(tw);
                    t[T_TWIST + 1] = Math.toDegrees(tw);
                }
            }
            maxResidualUnits = Math.max(maxResidualUnits, w > 0.999 ? residual : 0);
            if (clavicleMoved) {
                mul(inverse(model[parent(sd, lc)]), LC, tmpL);
                bt[lc].set(write(tmpL, tmp));
            }
            mul(inverse(LC), UAn, tmpL);
            bt[ua].set(write(tmpL, tmp));
            mul(inverse(UAn), FAn, tmpL);
            bt[fa].set(write(tmpL, tmp));
            mul(inverse(FAn), Hn, tmpL);
            bt[lh].set(write(tmpL, tmp));
            double grip = w * (1 - wm);
            FpPose.Request fpr = FpPose.requests.get(c);
            // the first-person layer sets them in full after this pass (fpW: it runs - its fingerFit stays as it was in other views)
            boolean fpFingers = fpr != null && fpr.fpW > 0.999 && fpr.fingerFit[0] > 0.999;
            if (fingerFit && (q[63] > 0.5 || r.hold != null || r.wrap != null) && grip > 1e-4 && !fpFingers) { // fingers closed round the support as in Tarkov (FB2-N: every hold kind; an underbarrel launcher)
                fitFingers(sd, bt, lh, c.isFemale(), grip, tmp, r, Hn, Pn, t0);
            }
            if (fingerFit && handgunGrip && r.hg != null) { // pistol in both hands: both hands' fingers round the grip (HG_*)
                boolean fpRight = fpr != null && fpr.fpW > 0.999 && fpr.fingerFit[1] > 0.999;
                // the support hand only while locked on its hold: the contact weight stays high (RELEASE_*) through a one-hand
                // carry with the hand at the body (QA fb2pf: third-person idle, w 0.92 with the hand 44 cm off the grip)
                r.hgW[0] = fpFingers ? 0 : grip * (q[50] > 0.5 ? r.lock : 1);
                r.hgW[1] = fpRight ? 0 : gripR;
                handgunFingers(sd, bt, lh, rh, r.hg, r.hgW, tmp);
            } else if (r.hg != null) {
                r.hgW[0] = r.hgW[1] = 0;
            }
            mul(inverse(model[parent(sd, prop)]), Pn, tmpL);
            bt[prop].set(write(tmpL, tmp));
            if (magWorld != null) {
                mul(inverse(model[parent(sd, p2)]), magWorld, tmpL);
                bt[p2].set(write(tmpL, tmp));
                magPlaced++;
            }
            // Bug wave 324, item 1: the round a round-load clip carries in the released hand keeps its place on that hand
            // (Bip01_Prop2 is a root child: the butt slide and lift moved the hand 4-7 cm off the shell, reload-5)
            int r2 = rf > 0 && magWorld == null ? index(sd, "Bip01_Prop2") : -1;
            if (rf > 0 && r2 >= 0 && r2 < n && parent(sd, r2) >= 0) {
                double[] onHand = mulNew(Hn, mulNew(inverse(HL), model[r2]));
                double[] round = model[r2].clone();
                setPos(round, lerp(pos(model[r2]), pos(onHand), rf));
                setRotation(round, slerp(unitRotation(model[r2]), unitRotation(onHand), rf), columnScales(model[r2]));
                mul(inverse(model[parent(sd, r2)]), round, tmpL);
                bt[r2].set(write(tmpL, tmp));
                roundProps++;
            }
            double[] postH = rH, postP = Pn; // W12: the right hand and the weapon as this pass leaves them (handPost)
            int chestBone = index(sd, "Bip01_Spine1");
            if (turnHold && chestBone >= 0 && chestBone < n) {
                boolean turning = c.isTurning90() || c.isTurningAround() || "turning".equals(c.getCurrentActionContextStateName());
                double dt = r.turnAt == 0 ? 0 : Math.min(0.1, (t0 - r.turnAt) / 1e9);
                r.turnAt = t0;
                r.turnW = turning ? Math.min(1, r.turnW + dt / TURN_IN_S) : Math.max(0, r.turnW - dt / TURN_OUT_S);
                int[] ids = {lc, ua, fa, lh, rc, rua, rfa, rh, prop};
                double[][] now = {LC, UAn, FAn, Hn, model[rc], rUA, rFA, rH, Pn};
                double[] chest = model[chestBone];
                if (!turning && r.turnW <= 0) {
                    double[] toChest = inverse(chest);
                    double[][] hold = new double[ids.length][];
                    for (int k = 0; k < ids.length; k++) {
                        hold[k] = mulNew(toChest, now[k]);
                    }
                    r.turnHold = hold;
                } else if (r.turnHold != null && r.turnW > 0) {
                    double e = smooth(r.turnW);
                    double[][] out = new double[ids.length][];
                    for (int k = 0; k < ids.length; k++) {
                        double[] kept = mulNew(chest, r.turnHold[k]);
                        out[k] = now[k].clone();
                        setPos(out[k], lerp(pos(now[k]), pos(kept), e));
                        setRotation(out[k], slerp(unitRotation(now[k]), unitRotation(kept), e), columnScales(now[k]));
                    }
                    // locals, parents first: the clavicles hang on unchanged spine bones
                    double[][] parents = {model[parent(sd, lc)], out[0], out[1], out[2], model[parent(sd, rc)], out[4], out[5], out[6], model[parent(sd, prop)]};
                    for (int k = 0; k < ids.length; k++) {
                        mul(inverse(parents[k]), out[k], tmpL);
                        bt[ids[k]].set(write(tmpL, tmp));
                    }
                    postH = out[7];
                    postP = out[8];
                    turnHolds++;
                }
            }
            if (w > 0.999 && residual < 1e-4) {
                double[] Xn = new double[16];
                mul(inverse(Pn), Hn, Xn);
                double[] xr = unitRotation(Xn);
                expected.put(ap, new double[] {Xn[3], Xn[7], Xn[11], Hn[3], Hn[7], Hn[11], lh, prop,
                        xr[0], xr[1], xr[2], xr[3], xr[4], xr[5], xr[6], xr[7], xr[8]});
            } else {
                expected.remove(ap);
            }
            if (oneHandP1 != null && 1 - offhandActW > 1e-4) {
                oneHandExpected.put(ap, new double[][] {makeOneHandRow(rh, prop, parent(sd, prop), 1 - offhandActW, rH, oneHandP1)});
                handPost.remove(ap);
            } else {
                handPost.put(ap, new double[][] {{rh, prop}, postH.clone(), postP.clone()});
            }
            applied++;
            if (frameLog) {
                double[] out = pos(mulNew(inverse(Pn), Hn)), goalOn = pos(held);
                stages.put(c, new double[] {System.nanoTime(), goalOn[0], goalOn[1], goalOn[2], out[0], out[1], out[2], w, r.lock,
                        r.acting ? 1 : 0, residual, frameClavicle, frameSlide, frameReach, r.twist, r.twist, r.relief,
                        r.elbowTurn, liftRaise, hw, wm, free ? 1 : 0});
            }
            if (tracing) {
                double[] t = trace(c);
                t[T_FRAMES]++;
                t[T_W] = w;
                t[T_HW] = hw;
                t[T_ELEV] = lastElevationDeg;
                t[T_APPLIED] = 1;
                t[T_WHY] = 0;
                if (mg == null || mg[0] != 3) {
                    t[T_RACK] = 0;
                }
            }
        } catch (Throwable e) {
            POSED_SURFACE.remove(); // a partially applied grip must not drive the first-person layer
            lastError = e.toString();
        } finally {
            nanos += System.nanoTime() - t0;
        }
    }

    /**
     * grip_targets.low_lift: {raise radians, pull model units} bringing the front support within reach
     * in low ready (before smoothing), or zeros.
     */
    static double[] lowLift(double[][] model, double[] q, double[] P, double[] HR, double[] P0, int ua, int fa, int lh) {
        double[] b0 = normalize(new double[] {P0[1], P0[5], P0[9]});
        double lw = smooth((-Math.asin(Math.max(-1, Math.min(1, b0[1]))) - LOW_FROM) / (LOW_TO - LOW_FROM));
        if (lw <= 0) {
            return new double[] {0, 0};
        }
        double[] bore = normalize(new double[] {P[1], P[5], P[9]});
        double[] seat = rot(P, new double[] {q[0], q[1], q[2]});
        double[] base = add(pos(P), seat);
        if (q[18] > 0.5) { // a lifted weapon: never further back than the reference stock (no pocket)
            double[] refButt = point(P, q[22], q[23], q[24]), newButt = add(point(P, q[19], q[20], q[21]), seat);
            base = add(base, scale(bore, Math.max(0, dot(sub(refButt, newButt), bore))));
        }
        double[] Pn = P.clone();
        setPos(Pn, base);
        double[] A = {q[3], q[4], q[5], q[6], q[7], q[8], q[9], q[10], q[11], q[12], q[13], q[14], 0, 0, 0, 1};
        double[] tgt = point(mulNew(Pn, A), q[15], q[16], q[17]);
        if (q[60] > 0) { // bare handguard: the hold already back along it as far as the reach needs
            tgt = sub(tgt, scale(bore, lw * lowBack(model, q, P, tgt, ua, fa, lh)));
        }
        double[] S = pos(model[ua]);
        double reach = dist(pos(model[fa]), S) + dist(pos(model[lh]), pos(model[fa])) - REACH_MARGIN;
        reach = Math.max(reach * LOW_COMFORT, dist(point(P, q[15], q[16], q[17]), S));
        if (dist(tgt, S) <= reach) {
            return new double[] {0, 0};
        }
        double[] axis = cross(bore, new double[] {0, 1, 0});
        if (norm(axis) < 1e-9) {
            return new double[] {0, 0};
        }
        axis = normalize(axis);
        double[] W = pos(HR), rel = sub(tgt, W);
        double raise = -1, bestD = Double.MAX_VALUE, bestT = 0;
        for (double th = 0; th <= LOW_RAISE_MAX + 1e-9; th += LOW_RAISE_STEP) {
            double d = dist(add(W, rotateVec(axis, th, rel)), S);
            if (d <= reach) {
                raise = th;
                break;
            }
            if (d < bestD) {
                bestD = d;
                bestT = th;
            }
        }
        if (raise < 0) {
            raise = bestT;
        }
        double pull = Math.min(LOW_PULL_MAX, slideBack(add(W, rotateVec(axis, raise, rel)), S, reach, rotateVec(axis, raise, bore)));
        return new double[] {raise * lw, pull * lw};
    }

    /**
     * grip_targets.low_back: how far back along the bore (at most param 61) the bare-handguard hold tgt needs
     * to lie within LOW_COMFORT of the support arm's reach (never less than the reference hold needs).
     */
    static double lowBack(double[][] model, double[] q, double[] P, double[] tgt, int ua, int fa, int lh) {
        double[] S = pos(model[ua]);
        double reach = dist(pos(model[fa]), S) + dist(pos(model[lh]), pos(model[fa])) - REACH_MARGIN;
        reach = Math.max(reach * LOW_COMFORT, dist(point(P, q[15], q[16], q[17]), S));
        return Math.min(q[60], slideBack(tgt, S, reach, normalize(new double[] {P[1], P[5], P[9]})));
    }

    /** grip_targets.comfort_reach: shoulder to wrist with the elbow at ELBOW_MAX (and the reach margin). */
    public static double comfortReach(double[] UA, double[] FA, double[] H) {
        double l1 = dist(pos(UA), pos(FA)), l2 = dist(pos(FA), pos(H));
        return Math.min(l1 + l2 - REACH_MARGIN, Math.sqrt(l1 * l1 + l2 * l2 - 2 * l1 * l2 * Math.cos(ELBOW_MAX)));
    }

    /** Contact as the engine computed it: left wrist in weapon space vs the transferred target. */
    static void check(AnimationPlayer ap) {
        double[] e = expected.remove(ap);
        if (e == null) {
            return;
        }
        try {
            double[] P = read(ap.getModelTransformAt((int) e[7]), new double[16]);
            double[] HL = read(ap.getModelTransformAt((int) e[6]), new double[16]);
            double[] X = new double[16];
            mul(inverse(P), HL, X);
            double contact = Math.sqrt(sq(X[3] - e[0]) + sq(X[7] - e[1]) + sq(X[11] - e[2]));
            double fk = Math.sqrt(sq(HL[3] - e[3]) + sq(HL[7] - e[4]) + sq(HL[11] - e[5]));
            checks++;
            sumContactUnits += contact;
            maxContactUnits = Math.max(maxContactUnits, contact);
            maxFkUnits = Math.max(maxFkUnits, fk);
            if (reseat && contact > RESEAT_MIN && e.length >= 17) {
                reseat(ap, e, P);
            }
        } catch (Throwable t) {
            lastError = t.toString();
        }
    }

    /**
     * A layer after the transfer moved the support hand off the weapon while the transfer held it there
     * (FB2-D: Viewpoint's third person turns the arms by the look pitch after GripIK - the support wrist
     * 6-10 cm off the handguard at -80 degrees, QA fb2dm/fb2df "third_idle_down80"). Outside the
     * first-person layer (it places both arms itself) the support arm is solved again onto its contact on
     * the weapon as that layer left it: clavicle assist, two links from the current elbow, the hand's
     * frame on the weapon as transferred; the bones below follow.
     */
    public static volatile boolean reseat = true;
    public static volatile long reseats;
    static final double RESEAT_MIN = 0.3 * CM;

    static void reseat(AnimationPlayer ap, double[] e, double[] P) {
        IsoGameCharacter c = ap.getIsoGameCharacter();
        FpPose.Request fr = c == null ? null : FpPose.requests.get(c);
        if (c == null || fr != null && !fr.mode.isEmpty() && !"none".equals(fr.mode)) { // the first-person layer or its retreat placed the arms
            return;
        }
        SkinningData sd = ap.getSkinningData();
        FpPose.Skeleton s = sd == null || ap.boneTransforms == null ? null
                : FpPose.skeleton(sd, Math.min(ap.boneTransforms.length, ap.getModelTransformsCount()));
        if (s == null || s.lh != (int) e[6] || s.prop != (int) e[7]) {
            return;
        }
        double[][] model = new double[s.n][];
        for (int i = 0; i < s.n; i++) {
            model[i] = read(ap.getModelTransformAt(i), new double[16]);
        }
        double[] LC = model[s.lc], UA = model[s.ua], FA = model[s.fa], H = model[s.lh];
        double[] goal = H.clone();
        double[] onWeapon = {e[8], e[9], e[10], e[0], e[11], e[12], e[13], e[1], e[14], e[15], e[16], e[2], 0, 0, 0, 1};
        double[] want = mulNew(P, onWeapon);
        setRotation(goal, unitRotation(want), columnScales(H));
        setPos(goal, pos(want));
        boolean clavicle = false;
        double theta = clavicleAngle(LC, UA, FA, H, pos(goal), CLAVICLE_MAX);
        if (theta > 0) {
            double[] axis = cross(sub(pos(UA), pos(LC)), sub(pos(goal), pos(LC)));
            if (norm(axis) > 1e-9) {
                double[] turn = rigid(normalize(axis), theta, pos(LC));
                LC = mulNew(turn, LC);
                UA = mulNew(turn, UA);
                FA = mulNew(turn, FA);
                H = mulNew(turn, H);
                clavicle = true;
            }
        }
        double[] UAn = new double[16], FAn = new double[16], Hn = new double[16];
        solveArm(UA, FA, H, goal, UAn, FAn, Hn);
        ArmNatural.solved(c, 0, armPole(UA, FA, goal, null, 0));
        limitWrist(FAn, Hn);
        double[][] next = new double[s.n][];
        boolean[] driver = new boolean[s.n];
        if (clavicle) {
            next[s.lc] = LC;
            driver[s.lc] = true;
        }
        next[s.ua] = UAn;
        next[s.fa] = FAn;
        next[s.lh] = Hn;
        driver[s.ua] = driver[s.fa] = driver[s.lh] = true;
        FpPose.write(ap, s, model, next, driver);
        reseats++;
    }

    /**
     * grip_targets.limit_wrist: the hand's wrist-to-knuckles axis bends at most MAX_BEND off the
     * forearm; beyond that the hand turns towards the forearm about the wrist (position kept).
     */
    static boolean limitWrist(double[] FA, double[] H) {
        double[] f = normalize(sub(pos(H), pos(FA)));
        double[] sc = columnScales(H);
        double[] r = unitRotation(H);
        double[] hx = {r[0], r[3], r[6]};
        double bend = Math.acos(Math.max(-1, Math.min(1, dot(f, hx))));
        if (bend <= MAX_BEND) {
            return false;
        }
        double[] axis = cross(hx, f);
        if (norm(axis) < 1e-9) {
            return false;
        }
        axis = normalize(axis);
        double turn = bend - MAX_BEND;
        double[] out = new double[9];
        for (int j = 0; j < 3; j++) {
            double[] c = rotateVec(axis, turn, new double[] {r[j], r[3 + j], r[6 + j]});
            out[j] = c[0];
            out[3 + j] = c[1];
            out[6 + j] = c[2];
        }
        setRotation(H, out, sc);
        return true;
    }

    static final class CarryPush {
        final long at;
        final double push;

        CarryPush(long at, double push) {
            this.at = at;
            this.push = push;
        }
    }

    static final java.util.Map<Request, CarryPush> carryPushState = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Maximum forward step >= 0 along unit vector dir keeping p + t * dir inside the sphere (center, radius). */
    static double raySphereMax(double[] p, double[] center, double radius, double[] dir) {
        if (radius <= 0) {
            return 0;
        }
        double[] d = sub(p, center);
        double c = dot(d, d) - radius * radius;
        if (c >= 0) {
            return 0;
        }
        double b = dot(d, dir);
        return Math.max(0, -b + Math.sqrt(b * b - c));
    }

    /**
     * W10 armsrest2: solve the support arm (with left-clavicle assist up to clavMax when needed) at goal + shiftVec
     * and apply the outward torso-clearance swivel, returning {clearDepth, elbowTurnRad, elevCap, residual, clavTheta}.
     */
    static double[] carryClearedPose(double[][] model, int[] chain, double[] lc0, double[] ua0, double[] fa0, double[] h0,
            double[] LC, double[] UA, double[] FA, double[] H, double clavMax, double clavSoft, double[] goal,
            double[] pole, double poleW, boolean free, double w, double[] upAxis, double[][] torso, double firstSide,
            double[] shiftVec, double[] lcOut, double[] uaOut, double[] faOut, double[] hOut, double[] poleSideOut) {
        double[] goalS = goal.clone();
        setPos(goalS, add(pos(goal), shiftVec));
        double lReach0 = dist(pos(FA), pos(UA)) + dist(pos(H), pos(FA)) - REACH_MARGIN;
        double[] lcU = LC, uaU = UA, faU = FA, hU = H;
        double thetaC = 0;
        if (dist(pos(goalS), pos(UA)) > lReach0) {
            thetaC = clavicleAngle(lc0, ua0, fa0, h0, pos(goalS), clavMax, clavSoft);
            if (thetaC > 0) {
                double[] axisC = cross(sub(pos(ua0), pos(lc0)), sub(pos(goalS), pos(lc0)));
                if (norm(axisC) > 1e-9) {
                    double[] rigC = rigid(normalize(axisC), thetaC, pos(lc0));
                    lcU = mulNew(rigC, lc0);
                    uaU = mulNew(rigC, ua0);
                    faU = mulNew(rigC, fa0);
                    hU = mulNew(rigC, h0);
                }
            }
            if (dist(pos(goalS), pos(uaU)) > lReach0 + 1e-4) {
                System.arraycopy(lcU, 0, lcOut, 0, 16);
                return new double[] {Double.POSITIVE_INFINITY, 0, 0, 0, thetaC};
            }
        }
        System.arraycopy(lcU, 0, lcOut, 0, 16);
        double residual;
        double[] side0;
        if (free && pole == null) {
            double[] soft = softGoal(uaU, faU, hU, goalS, FREE_SOFT_REACH * w);
            double[] carried = carriedSwivel(pos(uaU), pos(faU), pos(hU), pos(soft));
            solveArm(uaU, faU, hU, soft, uaOut, faOut, hOut, carried, carried == null ? 0 : 1);
            residual = dist(pos(hOut), pos(goalS));
            side0 = armPole(uaU, faU, soft, carried, carried == null ? 0 : 1);
        } else {
            residual = solveArm(uaU, faU, hU, goalS, uaOut, faOut, hOut, pole, poleW);
            side0 = armPole(uaU, faU, goalS, pole, poleW);
        }
        double[] sh = pos(uaOut), el = pos(faOut), wr = pos(hOut);
        double elevCap = Math.max(2 * CM, dot(sub(el, sh), upAxis) + 0.5 * CM);
        double d0 = clearDepth(model, chain, sh, el, wr, torso);
        double need = 0, finalD = d0;
        if (d0 > 0) {
            double[] axis = normalize(sub(wr, sh));
            double[] chestM = model[chain[2]];
            double[] leftAxis = normalize(new double[] {chestM[1], chestM[5], chestM[9]});
            double shLat = dot(sub(sh, pos(chestM)), leftAxis);
            double chestLat0 = dot(sub(el, pos(chestM)), leftAxis);
            double best = d0;
            boolean found = false;
            for (double side : new double[] {firstSide, -firstSide}) {
                double sideMax = side < 0 ? SWIVEL_MAX : CLEAR_MAX;
                double prev = 0;
                for (double mag = CLEAR_STEP; mag <= sideMax + 1e-9 && !found; mag += CLEAR_STEP) {
                    double[] elT = add(sh, rotateVec(axis, side * mag, sub(el, sh)));
                    if (dot(sub(elT, sh), upAxis) > elevCap) {
                        break;
                    }
                    double chestLat = dot(sub(elT, pos(chestM)), leftAxis);
                    if (chestLat < 0.5 * shLat && chestLat < chestLat0 - 1e-6) {
                        break;
                    }
                    double d = clearDepth(model, chain, sh, elT, wr, torso);
                    if (d <= 0) {
                        double lo = prev, hi = mag;
                        for (int i = 0; i < 5; i++) {
                            double mid = (lo + hi) / 2;
                            if (clearDepth(model, chain, sh, add(sh, rotateVec(axis, side * mid, sub(el, sh))), wr, torso) <= 0) {
                                hi = mid;
                            } else {
                                lo = mid;
                            }
                        }
                        need = side * hi;
                        found = true;
                    } else if (d < best) {
                        best = d;
                        need = side * mag;
                    }
                    prev = mag;
                }
                if (found) {
                    break;
                }
            }
            if (Math.abs(need) > 1e-4) {
                double[] turn = rigid(axis, need, sh);
                double[] ua2 = mulNew(turn, uaOut), fa2 = mulNew(turn, faOut);
                System.arraycopy(ua2, 0, uaOut, 0, 16);
                System.arraycopy(fa2, 0, faOut, 0, 16);
                side0 = rotateVec(axis, need, side0);
                finalD = clearDepth(model, chain, pos(uaOut), pos(faOut), pos(hOut), torso);
            }
        }
        System.arraycopy(side0, 0, poleSideOut, 0, 3);
        return new double[] {finalD, need, elevCap, residual, thetaC};
    }

    /**
     * How deep (model units) the arm's capsules lie in the torso ellipse beyond their tolerances
     * (<= 0 clear): the lower upper arm (shoulder sh to elbow e) and the forearm (to the wrist w).
     */
    static double armDepth(double[][] model, int[] chain, double[] sh, double[] e, double[] w, double[][] torso) {
        return armDepth(model, chain, sh, e, w, torso, R_FORE, CLEAR_TOL);
    }

    /** FB8: clearDepth, and the depth in the belt box (ob on the weapon Pn) when there is one. */
    static double clearAll(double[][] model, int[] chain, double[] sh, double[] e, double[] w, double[][] torso, double[] Pn, double[] ob) {
        double d = clearDepth(model, chain, sh, e, w, torso);
        return ob == null ? d : Math.max(d, obDepth(Pn, ob, sh, e, w));
    }

    /** FB4 (the user's item 17): the elbow clearance's measure - the measured forearm, not sinking (R_FORE_CLEAR). */
    static double clearDepth(double[][] model, int[] chain, double[] sh, double[] e, double[] w, double[][] torso) {
        return armDepth(model, chain, sh, e, w, torso, R_FORE_CLEAR, CLEAR_TOL_CLEAR);
    }

    /** ... with the forearm capsule's radius at the elbow and its tolerance. */
    static double armDepth(double[][] model, int[] chain, double[] sh, double[] e, double[] w, double[][] torso, double rFore, double tol) {
        double worst = -1;
        for (double t : new double[] {0.6, 0.85}) {
            worst = Math.max(worst, torsoDepth(model, chain, lerp(sh, e, t), R_UPPER, torso) - CLEAR_TOL_UPPER);
        }
        for (double t = 0; t <= 1.0001; t += 0.25) {
            worst = Math.max(worst, torsoDepth(model, chain, lerp(e, w, t), rFore + (R_WRIST - rFore) * t, torso) - tol);
        }
        return worst;
    }

    /** grip_targets.torso_depth: a sphere of the radius at p against the torso ellipse (<= 0 clear). */
    static double torsoDepth(double[][] model, int[] chain, double[] p, double radius, double[][] torso) {
        double best = Double.MAX_VALUE, bt = 0;
        int bi = 0;
        double[] bc = null;
        for (int i = 0; i < 3; i++) {
            double[] a = pos(model[chain[i]]), ab = sub(pos(model[chain[i + 1]]), a);
            double t = Math.max(0, Math.min(1, dot(sub(p, a), ab) / dot(ab, ab)));
            double[] cp = add(a, scale(ab, t));
            double d = dist(p, cp);
            if (d < best) {
                best = d;
                bt = t;
                bi = i;
                bc = cp;
            }
        }
        if ((bi == 2 && bt >= 1) || (bi == 0 && bt <= 0)) {
            return -1; // above the neck or below the pelvis
        }
        double[] m = model[chain[bi]];
        double[] left = normalize(new double[] {m[1], m[5], m[9]}), ahead = normalize(new double[] {m[2], m[6], m[10]});
        double[] d = sub(p, bc);
        double x = dot(d, left), z = dot(d, ahead);
        double hw = torso[bi][0] * CM, hz = (z > 0 ? torso[bi][1] : torso[bi][2]) * CM;
        double rr = Math.hypot(x, z);
        double r = Math.hypot(x / hw, z / hz);
        if (rr < 1e-9) {
            return Math.min(hw, hz) + radius;
        }
        return rr / r - rr + radius;
    }

    /**
     * The left hand's bind rotation in its forearm's frame (unit, row-major), from the inverse bind
     * pose - stored transposed against the bone transforms (read as they are, the turns in game
     * came out 180 degrees off the body mesh's own bind: QA trace 2026-10-15).
     */
    static double[] bindWrist(SkinningData sd, int fa, int lh) {
        double[] r = BIND_WRIST.get(sd);
        if (r == null) {
            if (sd.inverseBindPose == null || sd.inverseBindPose.size() <= Math.max(fa, lh)) {
                return null;
            }
            double[] F = inverse(transpose4(read(sd.inverseBindPose.get(fa), new double[16])));
            double[] H = inverse(transpose4(read(sd.inverseBindPose.get(lh), new double[16])));
            r = unitRotation(mulNew(inverse(F), H));
            BIND_WRIST.put(sd, r);
        }
        return r;
    }

    static double[] bindAxis(SkinningData sd, int fa, int lh) {
        return BIND_AXIS.computeIfAbsent(sd, data -> {
            double[] f = inverse(transpose4(read(data.inverseBindPose.get(fa), new double[16])));
            double[] h = inverse(transpose4(read(data.inverseBindPose.get(lh), new double[16])));
            double[] axis = pos(mulNew(inverse(f), h));
            return norm(axis) > 1e-8 ? normalize(axis) : new double[] {1, 0, 0};
        });
    }

    /**
     * The elbow clearance turn (radians) for depth(turn) (> 0: inside, model units); prev: the previous
     * frame's turn, NaN for a fresh arm; first: the side tried first (the side taken before, else -1 / +1).
     */
    public static double clearElbowTurn(java.util.function.DoubleUnaryOperator depth, double prev, double first) {
        if (depth.applyAsDouble(0) <= 0) {
            return 0;
        }
        for (double sign : new double[] {first, -first}) {
            for (double mag = CLEAR_SAMPLE; mag <= Math.PI + 1e-9; mag += CLEAR_SAMPLE) {
                if (depth.applyAsDouble(sign * mag) <= 0) {
                    return sign * mag;
                }
            }
        }
        double ref = Double.isFinite(prev) ? prev : 0, best = ref, bestCost = Double.MAX_VALUE;
        int n = (int) Math.round(SWIVEL_MAX / SWIVEL_STEP);
        for (int i = -n; i <= n; i++) {
            double turn = i * SWIVEL_STEP, cost = depth.applyAsDouble(turn) / CM + SWIVEL_HOLD * Math.abs(turn - ref);
            if (cost < bestCost) {
                bestCost = cost;
                best = turn;
            }
        }
        return best;
    }

    /**
     * The measured twist (-pi..pi) on the branch that continues prev, while that branch stays within
     * pi + HALF_TURN_BAND; otherwise (or without a previous frame) the measured one.
     */
    public static double continuousTwist(double tw, double prev, boolean fresh) {
        if (!fresh || !Double.isFinite(prev)) {
            return tw;
        }
        double near = tw + 2 * Math.PI * Math.rint((prev - tw) / (2 * Math.PI));
        return Math.abs(near) <= Math.PI + HALF_TURN_BAND ? near : tw;
    }

    static double relieveTwist(double[][] model, int[] chain, double[] upper, double[] fore, double[] hand,
            ArmNatural.Bind arm, double[][] torso) {
        return relieveTwist(model, chain, upper, fore, hand, arm, torso, Double.NaN, 0);
    }

    /**
     * The whole-arm swivel against axial strain. ref: the previous frame's (continuous) twist, NaN without
     * one; prev: the previous relief - moving away from it costs RELIEF_HOLD, so a twist near the half-turn
     * keeps the side it took instead of swinging the arm over to the other one. The strain is the wrist's anatomical
     * twist (ArmNatural.tau: the hand against the forearm of a hinged elbow; user report 23.10.2026). Measured against
     * the forearm swung from its clip's, the relief turned the elbow up to 19 cm into a worse twist (Mosin 18 -> -93
     * degrees, PPSh-41 -23 -> -114, TOZ-106 110 -> 173) and left 308 of 3000 catalogue support arms in the 3P mirror
     * past 130 degrees (tools/grip_targets.py, both bodies, Low/High/Patrol/aim); with the anatomical twist 80.
     */
    static double relieveTwist(double[][] model, int[] chain, double[] upper, double[] fore, double[] hand,
            ArmNatural.Bind arm, double[][] torso, double ref, double prev) {
        boolean body = true;
        for (int i : chain) if (i < 0) body = false;
        double[] sh = pos(upper), elbow = pos(fore), wrist = pos(hand), axis = normalize(sub(wrist, sh));
        java.util.function.DoubleUnaryOperator depth = !body ? turn -> -1
                : turn -> clearDepth(model, chain, sh, add(sh, rotateVec(axis, turn, sub(elbow, sh))), wrist, torso);
        return relieveTwist(upper, fore, hand, arm, depth, ref, prev, WRIST_TWIST_MAX);
    }

    /**
     * The swivel itself: depth(turn) - how deep the elbow turned by it about shoulder-wrist lies where it may not
     * (<= 0 clear; GripIK: the torso, FpPose: the torso and the other side of the view); limit: the twist it relieves
     * down to. The arm may end no deeper than it is, nor pass deeper on the way.
     */
    static double relieveTwist(double[] upper, double[] fore, double[] hand, ArmNatural.Bind arm,
            java.util.function.DoubleUnaryOperator depth, double ref, double prev, double limit) {
        boolean held = Double.isFinite(ref);
        double[] sh = pos(upper), wrist = pos(hand), axis = normalize(sub(wrist, sh));
        double initial = continuousTwist(ArmNatural.tau(upper, fore, hand, arm), ref, held);
        double excess = Math.max(0, Math.abs(initial) - limit);
        if (excess < 1e-4) return 0;
        double allowed = Math.max(0, depth.applyAsDouble(0)) + 1e-5;
        double score = excess * excess + RELIEF_HOLD * prev * prev, best = 0;
        java.util.List<Double> candidates = new java.util.ArrayList<>();
        for (int degrees = 5; degrees <= 120; degrees += 5) {
            candidates.add(Math.toRadians(-degrees));
            candidates.add(Math.toRadians(degrees));
        }
        if (held && Math.abs(prev) > 1e-6 && Math.abs(prev) <= Math.toRadians(120)) {
            candidates.add(prev);
        }
        // The smallest clearing swivel that reduces axial strain. This rotates
        // both bones together, so it cannot add twist across their elbow joint.
        for (double angle : candidates) {
            double[] turn = rigid(axis, angle, sh), f = mulNew(turn, fore);
            double bend = Math.acos(Math.max(-1, Math.min(1, dot(normalize(sub(wrist, pos(f))),
                    normalize(new double[] {hand[0], hand[4], hand[8]})))));
            if (bend > MAX_BEND) continue;
            double tw = continuousTwist(ArmNatural.tau(mulNew(turn, upper), f, hand, arm), ref, held);
            double remaining = Math.max(0, Math.abs(tw) - limit);
            double cost = remaining * remaining + .01 * angle * angle + RELIEF_HOLD * (angle - prev) * (angle - prev);
            if (cost >= score || depth.applyAsDouble(angle) > allowed) continue;
            // The interpolation path must also stay clear.
            boolean clear = true;
            for (int part = 1; part < 5; part++) {
                if (depth.applyAsDouble(angle * part / 5) > allowed) { clear = false; break; }
            }
            if (clear) { score = cost; best = angle; }
        }
        return best;
    }

    /** QA (FB2-D): every bone (index:name<parent) and the left arm chain in the bind pose and now (cm). */
    public static String skeletonDump(IsoGameCharacter character) {
        try {
            AnimationPlayer ap = character == null ? null : character.getAnimationPlayer();
            SkinningData sd = ap == null ? null : ap.getSkinningData();
            if (sd == null) return "skeleton=pending";
            StringBuilder s = new StringBuilder("bones=" + sd.numBones());
            java.util.Map<Integer, String> names = new java.util.TreeMap<>();
            sd.boneIndices.forEach((name, i) -> names.put(i, name));
            names.forEach((i, name) -> s.append(' ').append(i).append(':').append(name).append('<').append(parent(sd, i)));
            String[] chain = {"Bip01_L_Clavicle", "Bip01_L_UpperArm", "Bip01_L_Forearm", "Bip01_L_Hand", "Bip01_L_Finger0", "Bip01_L_Finger1"};
            s.append(" | bindCm");
            double[] last = null, lastNow = null;
            for (String name : chain) {
                int i = index(sd, name);
                if (i < 0 || sd.inverseBindPose == null || i >= sd.inverseBindPose.size()) {
                    s.append(' ').append(name).append("=absent");
                    continue;
                }
                double[] b = pos(inverse(transpose4(read(sd.inverseBindPose.get(i), new double[16]))));
                double[] now = pos(read(ap.getModelTransformAt(i), new double[16]));
                s.append(String.format(java.util.Locale.ROOT, " %s=%.2f/%.2f", name.substring(6), last == null ? 0 : dist(b, last) / CM,
                        lastNow == null ? 0 : dist(now, lastNow) / CM));
                last = b;
                lastNow = now;
            }
            return s.toString();
        } catch (Throwable e) { return "skeleton=unavailable " + e; }
    }

    /** Measured bind frame, including rigs with no finger/twist bones. Read-only QA. */
    public static String skeletonInfo(IsoGameCharacter character) {
        try {
            AnimationPlayer ap = character == null ? null : character.getAnimationPlayer();
            SkinningData sd = ap == null ? null : ap.getSkinningData();
            if (sd == null) return "skeleton=pending";
            int fa = index(sd, "Bip01_L_Forearm"), lh = index(sd, "Bip01_L_Hand");
            if (fa < 0 || lh < 0) return "skeleton=missing-arm";
            Request request = requests.get(character);
            return "bones=" + sd.numBones() + " forearmAxis=" + java.util.Arrays.toString(bindAxis(sd, fa, lh))
                    + " reliefDeg=" + (request == null ? 0 : Math.toDegrees(request.relief))
                    + " wristBind=" + java.util.Arrays.toString(bindWrist(sd, fa, lh));
        } catch (Throwable e) { return "skeleton=unavailable " + e; }
    }

    /**
     * grip_targets.wrist_twist: the hand's turn about the forearm against the bind pose
     * (radians, parent-frame rotation: forearm^T * hand * bind^T).
     */
    static double wristTwist(double[] F, double[] H, double[] r0) {
        return wristTwist(F, H, r0, new double[] {1, 0, 0});
    }

    static double wristTwist(double[] F, double[] H, double[] r0, double[] axis) {
        // Express the bind-relative turn in the forearm frame, where its measured
        // wrist axis lives. Bone-local X is not an anatomical axis on every rig.
        double[] relative = mul3(transpose3(unitRotation(F)), unitRotation(H));
        double[] q = quaternion(mul3(relative, transpose3(r0)));
        double turn = q[1] * axis[0] + q[2] * axis[1] + q[3] * axis[2];
        return Math.IEEEremainder(2 * Math.atan2(turn, q[0]), 2 * Math.PI);
    }

    /** grip_targets.slide_back: how far to move a goal back along the bore into an arm's reach. */
    static double slideBack(double[] goal, double[] shoulder, double reach, double[] bore) {
        double[] d = sub(goal, shoulder);
        double dd = dot(d, d);
        if (dd <= reach * reach) {
            return 0;
        }
        double db = dot(d, bore);
        double disc = db * db - dd + reach * reach;
        double t = disc >= 0 ? db - Math.sqrt(disc) : db;
        return Math.min(Math.max(t, 0), MAX_SLIDE);
    }

    /** grip_targets.clavicle_assist: clavicle swing (radians, at most CLAVICLE_MAX) that brings the goal into reach. */
    static double clavicleAngle(double[] LC, double[] UA, double[] FA, double[] H, double[] goal, double max) {
        return clavicleAngle(LC, UA, FA, H, goal, max, 0);
    }

    /**
     * As above; soft > 0 (FB7, a free arm: FREE_CLAVICLE_SOFT): the swing starts soft before the reach and brings the goal to
     * softReach(d, reach, soft) from the shoulder - the excess taken over smoothly (the angle leaves 0 with no kink).
     */
    static double clavicleAngle(double[] LC, double[] UA, double[] FA, double[] H, double[] goal, double max, double soft) {
        double reach = dist(pos(FA), pos(UA)) + dist(pos(H), pos(FA)) - REACH_MARGIN;
        double[] s = pos(UA), c = pos(LC);
        reach = softReach(dist(goal, s), reach, soft);
        if (dist(goal, s) <= reach) {
            return 0;
        }
        double[] axis = cross(sub(s, c), sub(goal, c));
        if (norm(axis) < 1e-9) {
            return 0;
        }
        axis = normalize(axis);
        double[] v = sub(s, c);
        if (dist(goal, add(c, rotateVec(axis, max, v))) > reach) {
            return max;
        }
        double lo = 0, hi = max;
        for (int i = 0; i < 30; i++) {
            double mid = (lo + hi) / 2;
            if (dist(goal, add(c, rotateVec(axis, mid, v))) > reach) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return hi;
    }

    /** The elbow's side solveArm takes (unit, across shoulder-goal): the current elbow's, turned towards prefer by k. */
    static double[] armPole(double[] UA, double[] FA, double[] goal, double[] prefer, double k) {
        double[] s = pos(UA), d = sub(pos(goal), s), es = sub(pos(FA), s);
        double[] axis = norm(d) > 1e-10 ? normalize(d) : normalize(es);
        double[] pole = sub(es, scale(axis, dot(es, axis)));
        if (norm(pole) < 1e-8) {
            double[] z = {UA[2], UA[6], UA[10]};
            pole = sub(z, scale(axis, dot(z, axis)));
        }
        pole = normalize(pole);
        if (prefer != null && k > 0) {
            double[] p1 = sub(prefer, scale(axis, dot(prefer, axis)));
            if (norm(p1) > 1e-8) {
                p1 = normalize(p1);
                double[] mix = add(scale(pole, 1 - k), scale(p1, k));
                pole = norm(mix) > 1e-8 ? normalize(mix) : p1;
            }
        }
        return pole;
    }

    /** FB7: the distance a goal d from the shoulder is reached at: d up to soft before the reach, beyond it approaching the
     *  reach exponentially (slope 1 at the start: no kink); soft 0: min(d, reach). */
    static double softReach(double d, double reach, double soft) {
        double start = reach - soft;
        if (soft <= 0 || start <= 0) {
            return Math.min(d, reach);
        }
        return d <= start ? d : start + soft * (1 - Math.exp(-(d - start) / soft));
    }

    /** FB7: the goal of a two-bone solve brought along the shoulder line to softReach(d, full reach, soft) (soft IK). */
    static double[] softGoal(double[] UA, double[] FA, double[] H, double[] goal, double soft) {
        double[] sh = pos(UA), to = pos(goal);
        double d = dist(sh, to), reach = softReach(d, dist(sh, pos(FA)) + dist(pos(FA), pos(H)), soft);
        if (reach >= d || d < 1e-10) {
            return goal;
        }
        double[] out = goal.clone();
        setPos(out, add(sh, scale(sub(to, sh), reach / d)));
        return out;
    }

    /**
     * FB7: the clip arm's own swivel (its elbow e across its shoulder-to-wrist axis s-w) carried onto the shoulder-to-goal
     * axis s-d by the smallest turn (unit, across s-d); null with no axis or a straight clip arm. Where d lies on the clip
     * arm's axis it is the clip elbow across s-d (solveArm's own pole).
     */
    static double[] carriedSwivel(double[] s, double[] e, double[] w, double[] d) {
        double[] a0 = sub(w, s), axis = sub(d, s);
        if (norm(a0) < 1e-10 || norm(axis) < 1e-10) {
            return null;
        }
        a0 = normalize(a0);
        axis = normalize(axis);
        double[] es = sub(e, s), pc = sub(es, scale(a0, dot(es, a0)));
        if (norm(pc) < 1e-8) {
            return null;
        }
        double[] R = rotationBetween(a0, axis);
        double[] p = {R[0] * pc[0] + R[1] * pc[1] + R[2] * pc[2], R[3] * pc[0] + R[4] * pc[1] + R[5] * pc[2], R[6] * pc[0] + R[7] * pc[1] + R[8] * pc[2]};
        p = sub(p, scale(axis, dot(p, axis)));
        return norm(p) < 1e-8 ? null : normalize(p);
    }

    /** anim_x.solve_arm: two links, elbow pole from the current elbow, no stretch. Returns the residual. */
    static double solveArm(double[] UA, double[] FA, double[] H, double[] goal, double[] UAn, double[] FAn, double[] Hn) {
        return solveArm(UA, FA, H, goal, UAn, FAn, Hn, null, 0);
    }

    /**
     * grip_targets.solve_arm_pole: as above, the elbow pole turned towards a world direction
     * (null: none) by the fraction k.
     */
    static double solveArm(double[] UA, double[] FA, double[] H, double[] goal, double[] UAn, double[] FAn, double[] Hn,
            double[] prefer, double k) {
        double[] s = pos(UA), e = pos(FA), wv = pos(H), d = pos(goal);
        double l1 = dist(e, s), l2 = dist(wv, e);
        double[] delta = sub(d, s);
        double len = norm(delta);
        double[] axis = len > 1e-10 ? scale(delta, 1 / len) : normalize(sub(wv, s));
        double reach = Math.max(Math.abs(l1 - l2) + 1e-7, Math.min(len, l1 + l2 - 1e-7));
        double x = (l1 * l1 - l2 * l2 + reach * reach) / (2 * reach);
        double[] es = sub(e, s);
        double[] pole = sub(es, scale(axis, dot(es, axis)));
        if (norm(pole) < 1e-8) {
            double[] z = {UA[2], UA[6], UA[10]};
            pole = sub(z, scale(axis, dot(z, axis)));
        }
        pole = normalize(pole);
        if (prefer != null && k > 0) {
            double[] p1 = sub(prefer, scale(axis, dot(prefer, axis)));
            if (norm(p1) > 1e-8) {
                p1 = normalize(p1);
                double[] mix = add(scale(pole, 1 - k), scale(p1, k));
                pole = norm(mix) > 1e-8 ? normalize(mix) : p1;
            }
        }
        double h = Math.sqrt(Math.max(0, l1 * l1 - x * x));
        double[] newe = add(add(s, scale(axis, x)), scale(pole, h));
        double[] neww = add(s, scale(axis, reach));
        rotate(UA, rotationBetween(es, sub(newe, s)), UAn);
        rotate(FA, rotationBetween(sub(wv, e), sub(neww, newe)), FAn);
        setPos(FAn, newe);
        System.arraycopy(goal, 0, Hn, 0, 16);
        setPos(Hn, neww);
        return dist(neww, d);
    }

    // --- small linear algebra on row-major true 4x4 (column vectors) ---------------------------------

    static int index(SkinningData sd, String name) {
        Integer i = sd.boneIndices == null ? null : sd.boneIndices.get(name);
        return i == null ? -1 : i;
    }

    static int parent(SkinningData sd, int i) {
        SkinningBone b = sd.getBoneAt(i);
        return b == null || b.parent == null ? -1 : b.parent.index;
    }

    static double[] read(Matrix4f m, double[] t) {
        t[0] = m.m00; t[1] = m.m01; t[2] = m.m02; t[3] = m.m03;
        t[4] = m.m10; t[5] = m.m11; t[6] = m.m12; t[7] = m.m13;
        t[8] = m.m20; t[9] = m.m21; t[10] = m.m22; t[11] = m.m23;
        t[12] = m.m30; t[13] = m.m31; t[14] = m.m32; t[15] = m.m33;
        return t;
    }

    static Matrix4f write(double[] t, Matrix4f m) {
        m.m00 = (float) t[0]; m.m01 = (float) t[1]; m.m02 = (float) t[2]; m.m03 = (float) t[3];
        m.m10 = (float) t[4]; m.m11 = (float) t[5]; m.m12 = (float) t[6]; m.m13 = (float) t[7];
        m.m20 = (float) t[8]; m.m21 = (float) t[9]; m.m22 = (float) t[10]; m.m23 = (float) t[11];
        m.m30 = (float) t[12]; m.m31 = (float) t[13]; m.m32 = (float) t[14]; m.m33 = (float) t[15];
        return m;
    }

    static void mul(double[] a, double[] b, double[] out) {
        double[] r = new double[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                r[i * 4 + j] = a[i * 4] * b[j] + a[i * 4 + 1] * b[4 + j] + a[i * 4 + 2] * b[8 + j] + a[i * 4 + 3] * b[12 + j];
            }
        }
        System.arraycopy(r, 0, out, 0, 16);
    }

    static double[] mulNew(double[] a, double[] b) {
        double[] r = new double[16];
        mul(a, b, r);
        return r;
    }

    /** General affine inverse (bones may carry scale). */
    static double[] inverse(double[] m) {
        double a = m[0], b = m[1], c = m[2], d = m[4], e = m[5], f = m[6], g = m[8], h = m[9], k = m[10];
        double A = e * k - f * h, B = -(d * k - f * g), C = d * h - e * g;
        double det = a * A + b * B + c * C;
        double[] r = new double[16];
        r[0] = A / det; r[1] = -(b * k - c * h) / det; r[2] = (b * f - c * e) / det;
        r[4] = B / det; r[5] = (a * k - c * g) / det; r[6] = -(a * f - c * d) / det;
        r[8] = C / det; r[9] = -(a * h - b * g) / det; r[10] = (a * e - b * d) / det;
        for (int i = 0; i < 3; i++) {
            r[i * 4 + 3] = -(r[i * 4] * m[3] + r[i * 4 + 1] * m[7] + r[i * 4 + 2] * m[11]);
        }
        r[15] = 1;
        return r;
    }

    /** out = (R applied about the matrix origin) * m: rotates the 3x3, keeps the translation. */
    static void rotate(double[] m, double[] R, double[] out) {
        double[] r = m.clone();
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i * 4 + j] = R[i * 3] * m[j] + R[i * 3 + 1] * m[4 + j] + R[i * 3 + 2] * m[8 + j];
            }
        }
        System.arraycopy(r, 0, out, 0, 16);
    }

    /** anim_x.rotation_between, 3x3 row-major. */
    static double[] rotationBetween(double[] a, double[] b) {
        a = normalize(a);
        b = normalize(b);
        double[] v = cross(a, b);
        double cosine = dot(a, b);
        if (cosine < -0.999999) {
            double[] axis = normalize(cross(a, Math.abs(a[0]) < .8 ? new double[] {1, 0, 0} : new double[] {0, 1, 0}));
            double[] r = new double[9];
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    r[i * 3 + j] = 2 * axis[i] * axis[j] - (i == j ? 1 : 0);
                }
            }
            return r;
        }
        double[] s = {0, -v[2], v[1], v[2], 0, -v[0], -v[1], v[0], 0};
        double[] r = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double ss = 0;
                for (int k = 0; k < 3; k++) {
                    ss += s[i * 3 + k] * s[k * 3 + j];
                }
                r[i * 3 + j] = (i == j ? 1 : 0) + s[i * 3 + j] + ss / (1 + cosine);
            }
        }
        return r;
    }

    /** Rodrigues rotation of v about a unit axis. */
    static double[] rotateVec(double[] axis, double theta, double[] v) {
        double c = Math.cos(theta), s = Math.sin(theta);
        double[] kxv = cross(axis, v);
        double kv = dot(axis, v);
        return new double[] {v[0] * c + kxv[0] * s + axis[0] * kv * (1 - c), v[1] * c + kxv[1] * s + axis[1] * kv * (1 - c),
                v[2] * c + kxv[2] * s + axis[2] * kv * (1 - c)};
    }

    /** 4x4 rotation by theta about an axis through the point c. */
    static double[] rigid(double[] axis, double theta, double[] c) {
        double[] r = new double[16];
        double[][] cols = {rotateVec(axis, theta, new double[] {1, 0, 0}), rotateVec(axis, theta, new double[] {0, 1, 0}),
                rotateVec(axis, theta, new double[] {0, 0, 1})};
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i * 4 + j] = cols[j][i];
            }
        }
        double[] rc = {r[0] * c[0] + r[1] * c[1] + r[2] * c[2], r[4] * c[0] + r[5] * c[1] + r[6] * c[2], r[8] * c[0] + r[9] * c[1] + r[10] * c[2]};
        r[3] = c[0] - rc[0];
        r[7] = c[1] - rc[1];
        r[11] = c[2] - rc[2];
        r[15] = 1;
        return r;
    }

    static double[] columnScales(double[] m) {
        return new double[] {Math.sqrt(m[0] * m[0] + m[4] * m[4] + m[8] * m[8]), Math.sqrt(m[1] * m[1] + m[5] * m[5] + m[9] * m[9]),
                Math.sqrt(m[2] * m[2] + m[6] * m[6] + m[10] * m[10])};
    }

    /** Unit 3x3 (row-major) of a transform with column scale removed. */
    static double[] unitRotation(double[] m) {
        double[] s = columnScales(m);
        double[] r = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i * 3 + j] = m[i * 4 + j] / s[j];
            }
        }
        return r;
    }

    static void setRotation(double[] m, double[] r, double[] scale) {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                m[i * 4 + j] = r[i * 3 + j] * scale[j];
            }
        }
    }

    /**
     * grip_targets.bore_rotation: row-major rotation whose Y column (the bore) points elevation
     * above the model horizontal and yaw from ahead (-Z) towards +X, uncanted (Z up-most).
     */
    static double[] boreRotation(double elevation, double yaw) {
        double[] b = {Math.sin(yaw) * Math.cos(elevation), Math.sin(elevation), -Math.cos(yaw) * Math.cos(elevation)};
        double[] top = normalize(sub(new double[] {0, 1, 0}, scale(b, b[1])));
        double[] x = cross(b, top);
        return new double[] {x[0], b[0], top[0], x[1], b[1], top[1], x[2], b[2], top[2]};
    }

    static double[] mul3(double[] a, double[] b) {
        double[] r = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
            }
        }
        return r;
    }

    static double[] transpose4(double[] a) {
        double[] t = new double[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                t[i * 4 + j] = a[j * 4 + i];
            }
        }
        return t;
    }

    static double[] transpose3(double[] a) {
        return new double[] {a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8]};
    }

    /** Unit quaternion (w, x, y, z) of a row-major rotation. */
    static double[] quaternion(double[] r) {
        double tr = r[0] + r[4] + r[8];
        double w, x, y, z;
        if (tr > 0) {
            double s = Math.sqrt(tr + 1) * 2;
            w = 0.25 * s; x = (r[7] - r[5]) / s; y = (r[2] - r[6]) / s; z = (r[3] - r[1]) / s;
        } else if (r[0] > r[4] && r[0] > r[8]) {
            double s = Math.sqrt(1 + r[0] - r[4] - r[8]) * 2;
            w = (r[7] - r[5]) / s; x = 0.25 * s; y = (r[1] + r[3]) / s; z = (r[2] + r[6]) / s;
        } else if (r[4] > r[8]) {
            double s = Math.sqrt(1 + r[4] - r[0] - r[8]) * 2;
            w = (r[2] - r[6]) / s; x = (r[1] + r[3]) / s; y = 0.25 * s; z = (r[5] + r[7]) / s;
        } else {
            double s = Math.sqrt(1 + r[8] - r[0] - r[4]) * 2;
            w = (r[3] - r[1]) / s; x = (r[2] + r[6]) / s; y = (r[5] + r[7]) / s; z = 0.25 * s;
        }
        double n = Math.sqrt(w * w + x * x + y * y + z * z);
        return new double[] {w / n, x / n, y / n, z / n};
    }

    static double[] slerp(double[] ra, double[] rb, double t) {
        if (t <= 0) {
            return ra;
        }
        if (t >= 1) {
            return rb;
        }
        double[] a = quaternion(ra), b = quaternion(rb);
        double d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
        if (d < 0) {
            d = -d;
            b = new double[] {-b[0], -b[1], -b[2], -b[3]};
        }
        double ka, kb;
        if (d > 0.9995) {
            ka = 1 - t;
            kb = t;
        } else {
            double th = Math.acos(Math.min(1, d)), sn = Math.sin(th);
            ka = Math.sin((1 - t) * th) / sn;
            kb = Math.sin(t * th) / sn;
        }
        double w = ka * a[0] + kb * b[0], x = ka * a[1] + kb * b[1], y = ka * a[2] + kb * b[2], z = ka * a[3] + kb * b[3];
        double n = Math.sqrt(w * w + x * x + y * y + z * z);
        w /= n; x /= n; y /= n; z /= n;
        return new double[] {1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
                2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
                2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)};
    }

    static double smooth(double x) {
        x = clamp(x);
        return x * x * (3 - 2 * x);
    }

    static double[] rot(double[] m, double[] v) {
        return new double[] {m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[4] * v[0] + m[5] * v[1] + m[6] * v[2], m[8] * v[0] + m[9] * v[1] + m[10] * v[2]};
    }

    static double[] point(double[] m, double x, double y, double z) {
        return new double[] {m[0] * x + m[1] * y + m[2] * z + m[3], m[4] * x + m[5] * y + m[6] * z + m[7], m[8] * x + m[9] * y + m[10] * z + m[11]};
    }

    static double[] lerp(double[] a, double[] b, double t) {
        return new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    static double[] pos(double[] m) { return new double[] {m[3], m[7], m[11]}; }
    static void setPos(double[] m, double[] p) { m[3] = p[0]; m[7] = p[1]; m[11] = p[2]; }
    static double[] sub(double[] a, double[] b) { return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    static double[] add(double[] a, double[] b) { return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }
    static double[] scale(double[] a, double s) { return new double[] {a[0] * s, a[1] * s, a[2] * s}; }
    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    static double[] cross(double[] a, double[] b) { return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
    static double norm(double[] a) { return Math.sqrt(dot(a, a)); }
    static double[] normalize(double[] a) { double n = norm(a); return n > 0 ? scale(a, 1 / n) : a; }
    static double dist(double[] a, double[] b) { return norm(sub(a, b)); }

    /** Distance from p to the segment a-b. */
    static double segmentDistance(double[] p, double[] a, double[] b) {
        double[] ab = sub(b, a);
        double len = dot(ab, ab);
        double t = len > 1e-12 ? Math.max(0, Math.min(1, dot(sub(p, a), ab) / len)) : 0;
        return dist(p, add(a, scale(ab, t)));
    }

    /** 4x4 from 12 numbers (3x4 rows) at offset. */
    static double[] rows(double[] v, int o) {
        return new double[] {v[o], v[o + 1], v[o + 2], v[o + 3], v[o + 4], v[o + 5], v[o + 6], v[o + 7], v[o + 8], v[o + 9], v[o + 10], v[o + 11], 0, 0, 0, 1};
    }
    static double sq(double a) { return a * a; }
    static double clamp(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
}
