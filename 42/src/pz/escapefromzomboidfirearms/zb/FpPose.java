package pz.escapefromzomboidfirearms.zb;

import java.util.concurrent.ConcurrentHashMap;

import org.lwjgl.util.vector.Matrix4f;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoGameCharacter;
import zombie.core.Core;
import zombie.core.skinnedmodel.animation.AnimationMultiTrack;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.animation.AnimationTrack;
import zombie.core.skinnedmodel.animation.AnimatorsBoneTransform;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.SkinningData;
import zombie.iso.Vector3;

/**
 * First-person pose layer (VP-W4-A). For the local player in Viewpoint's first-person
 * view the weapon is held relative to Viewpoint's eye as in Tarkov's first person
 * (tools/fp_pose_runtime.py data from the Tarkov clips), on the PZ skeleton.
 * Stationary contacts come from Tarkov markers/FK, mapped to the installed assembly; the PZ hand
 * and its two finger bones lie on Tarkov's hand landmarks there (VP-W12-H4, fp_pose_runtime).
 * Patrol also carries its source hand motion; actions release to their animation targets.
 * Both arms solve again from PZ shoulders (lengths kept); the elbows follow Tarkov's bend goals,
 * which our own ready turns carry with the grip only, without the retreat's outward push.
 * Other characters,
 * Viewpoint's third person and the isometric view keep their poses; there only the
 * obstacle retreat (TW/WeaponObstacle) is laid over the pose.
 *
 *   eye     Viewpoint's eye (ViewpointBridge.eyeNow: the newest frame's eye and the camera
 *           ahead of it at the current Look.yaw) kept relative to Bip01_Head: the frame is
 *           snapshotted after this update, so the pairing uses the head, position and angle
 *           this layer saw on the update before it; Look.yaw/pitch give the orientation - in
 *           Viewpoint's free look the body's yaw and the pitch of the press (FpFreeLook, bug wave 324)
 *   stance  aiming: the hip (idle) pose; carry low: own low ready (the hip turned muzzle
 *           down about the grip); carry high: the full retreat pose (P1); patrol: Tarkov's
 *           patrol loop with its in/out clips; else the idle (hip ready). Smoothed
 *   ADS     the selected sight channel's camera (Lua: TWSight.current, own Prop1 space) onto
 *           the eye, over Tarkov's aim time by ergonomics and weight (globals.Aiming,
 *           VP-W3-B1 section 7); at the end the screen centre is the channel's axis
 *   retreat TWObstacle ratio, rifles: back along the view up to maxBack, then up into
 *           high ready; handguns: back to the chest, muzzle down (TW/Data/WeaponObstacle);
 *           the ADS fades out over the pull back; the weapon's rear stays clear of the chest
 *   actions the action clips (reload, magazine drop/check, rack, launchers) by the clip
 *           the engine plays and its time: Tarkov's weapon motion relative to its idle on
 *           the hip pose, with the clip's elbow goals; underbarrel clips move the host
 *   recoil  Tarkov's recoil, sway and breath over the pose (FpRecoil, VP-W7-R), its aim time;
 *           a trigger press or a shot leaves a carry stance at once (the hip bore is the view's)
 *   hip     the hip trigger (TW_HipFire.lua, VP-W12-H3; no aiming): out of patrol Tarkov's
 *           PATROL OUT on its controller's timeline, out of the own low/high ready a blend over
 *           that same ready time (TW/Data/HipFire); "ready" once the weapon is at the hip pose,
 *           which is when Lua starts the native attack (Tarkov: FIRE only from IDLE); an action
 *           interrupts the transition; a weapon that fires only aimed (RSHG-2) is refused
 *
 * Applied at the entry of AnimationPlayer.UpdateSkinTransforms, one update after its model
 * transforms are complete (GripIK.BeforeSkin; the mark of updateModelTransforms' exit) - so after
 * Viewpoint's ArmsPitch whatever the mod load order (its advice is on the exit of
 * updateModelTransforms, which runs before UpdateSkinTransforms in either order): the arms are
 * placed once, no second turn by the view pitch; Viewpoint's arm
 * kick is replaced while the layer holds the arms (its camera kick too, by Tarkov's camera
 * recoil, when FpRecoil takes the shot). Model transforms and the bone locals of
 * the moved bones are written, as ArmsPitch does: skinning, Viewpoint's capture and the
 * weapon model read the result. Matrices are true 4x4 (row-major, column vectors) as in
 * GripIK.
 */
public final class FpPose {
    static final double U = GripIK.U, CM = GripIK.CM;
    /** Stance changes (idle, low, high ready, patrol) go through this time constant: no jumps. */
    static final double STANCE_TAU_S = 0.12;
    /** The weight of an action clip follows the engine's blend weight of its track over this. */
    static final double ACTION_TAU_S = 0.05;
    /**
     * Another timed action (eating, equipping, a vanilla rack...) or the layer not allowed: it
     * fades out over this to the pose the clips give, and back in afterwards.
     */
    static final double FADE_TAU_S = 0.12;
    /**
     * The corrections that keep the weapon in reach (the support hand's slide back along the view, the high-ready
     * pull, the support reach, the body's look turns) release to an action and come back after it over this time
     * constant (FB4-A, user report 19.10.2026, items 13 and 18): switched by the action flag in one frame, the slide
     * moved the weapon up to 15 cm forward and back at a fire-mode switch and at every action's end.
     */
    static final double ACTING_TAU_S = 0.12;
    /** FB4-A: a fire-mode gesture restarted by a press fades from its previous pose over this (seconds). */
    static final double GESTURE_FADE_S = 0.1;
    /** QA (FB4-A A/B): false restores the former switch of the reach corrections by the action flag in one frame. */
    public static volatile boolean actingRamp = true;
    /** The patrol clip's elbow goals fade out over this much of the ADS (and action) weight (natural arms, 23.10.2026). */
    static final double GOALS_FADE = 0.6;
    /** Deploying or stowing an underbarrel moves the support hand between the host's contact and the launcher hold over this. */
    static final double HOLD_SWITCH_S = 0.25;
    /** The reload pull of an underbarrel's action falls back with this time constant (5 % left after 0.3 s); it rises at once. */
    static final double RELOAD_RELEASE_S = 0.1;
    /**
     * Animation states (action context, AnimSets/player folders) whose clips need the body's own
     * arms (climbing, falling, getting up, sitting, shoving, carrying bodies...): the layer fades
     * out while one plays.
     */
    static final String[] BODY_STATES = {"climb", "fall", "getup", "knockeddown", "onground", "bumped", "hitreaction", "death", "deadbody",
        "sit", "onbed", "grapple", "dragging", "pickupbody", "laydownbody", "throwbody", "dropbody", "grabcorpse", "fishing", "fitness",
        "emote", "shove", "stomp", "melee", "smashwindow", "openwindow", "closewindow", "collide", "animal"};
    /** Own low ready (no Tarkov clip): the hip pose turned muzzle down about the grip, a little lower and back. */
    static final double LOW_PITCH = Math.toRadians(-26), LOW_DROP = 3 * CM, LOW_BACK = 2 * CM;
    /** Own FP ready envelope, metres scaled once: the actual right wrist, not the Prop1 datum.
     * At the chest, under the right shoulder (VP-W12-H4): from the former 22/28/32 cm the 62 degree
     * pitch put the support hand at eye height right of the centre and its arm across the view;
     * here the support hand stays below the eye with its elbow out of the frame. P1's pitch stays.
     * Wrists stay outside the central 15 degree cone and ahead of the near plane.
     */
    static final double[] HIGH_WRIST = {-14 * CM, -36 * CM, -22 * CM};
    static final double HIGH_EYE_GAP = 20 * CM, READY_CONE = Math.toRadians(15);
    /** Own pistol high ready: keep its depth/retraction and muzzle-down rotation, but put
     * the wrist on the lower 15-degree ray. Its lateral offset keeps it outside the cone.
     * Checked with original assembled LOD0 vertices at vFOV 50/75/90, both PZ bodies.
     */
    static final double[] PISTOL_WRIST = {-12 * CM, -22 * CM * Math.tan(READY_CONE), -22 * CM};
    /** First-person pistol ready (VP-W12-H4, coordinator decision A): a shallow muzzle-down turn at that visible
     * endpoint; the handgun data's -65 degrees (TW/Data/WeaponObstacle) there bent the wrists 102/138 degrees.
     * The obstacle retreat blends back to the data's pitch by the start of its turn (pullUntil), so a retreat from
     * a wall is the data's; third person keeps the data. This is the retreat's endpoint only: the pistol's own High
     * Ready stance is the muzzle-up PISTOL_HIGH_PITCH below (user 24.10.2026, item 1). */
    static final double PISTOL_READY_PITCH = Math.toRadians(-10);
    /**
     * The own pistol High Ready (user 24.10.2026, item 1: "in first person the pistol looks down in High Ready instead of
     * up as it does in the isometric view"): the bore 35 degrees above the view - the isometric carry clip's HIGH_PITCH
     * (tools/center_handgun_carry.py, HANDGUN_CARRY high ready) - with the hands up at the chin, as that clip has them.
     * The right wrist target keeps the source grip 20 cm ahead of the eye, 10 cm under it and 12 cm to the right: clear of
     * the screen centre (38 degrees off the view, READY_CONE is 15) and outside Viewpoint's near plane. It is a stance of
     * its own (like the own low ready), separate from the wall retreat, which keeps TW/Data/WeaponObstacle's handgun pitch.
     */
    static final double PISTOL_HIGH_PITCH = Math.toRadians(35);
    static final double[] PISTOL_HIGH_WRIST = {-12 * CM, -10 * CM, -20 * CM};
    /**
     * Item 4 (user 24.10.2026: "on some pistols the hands come too close to the camera while aiming and break"): the
     * nearest of a pistol's two hands kept this far in front of the eye in ADS (model units). Tarkov's own camera point
     * of a pistol sight can lie at or ahead of the grip (a collimator on the slide, barrel or rail, the P226's book
     * assembly), and putting that point on the eye put the hands at or behind Viewpoint's near plane, where the depth
     * clamp flattened them into one huge hand (FpViewLatch). The hands are 0-13 cm out in 98 of 137 catalogue
     * pistol/sight assemblies, -19.6 cm at worst. 22 cm is the distance the pistols whose hands already read as hands
     * hold them at in Tarkov's own clips (M1911A1 22.3, RSh-12 19.6), and it is inside the arm's reach for every one of
     * the 137 assemblies on both PZ bodies (tools/check_fp_pistol_ads.py solves them with the real body and arms). The
     * weapon slides ahead along its own line of sight only, so the alignment, the iron picture and the collimator's dot
     * stay exact and only the eye relief grows.
     */
    static final double PISTOL_ADS_HANDS = 22 * CM;
    /** QA/gate bound of that slide ahead: more means wrong sight or hand data, never a pose. The longest sight mount
     * (the P226R's book with a collimator) needs 41.6 cm at 22 cm; nothing else needs more than 25.2. */
    static final double PISTOL_ADS_MAX = 42 * CM;

    static final double PULL_WRIST_NEAR = 8 * CM;
    static final double ELBOW_CLEAR_MAX = Math.PI;
    /**
     * The elbow's turn about shoulder-wrist where the wrist's anatomical twist (ArmNatural.tau) is past this (user
     * 23.10.2026): the compact SMGs and bolt rifles in ADS (145-151 degrees), the MSGL's and PKM's Patrol (135-178), an
     * underbarrel launcher's hold (144-171) - the elbow goes where the hand can be held, by the least turn that brings the
     * twist to this (GripIK.relieveTwist; ~7 cm, 15-18 cm in the MSGL's Patrol), no deeper in the torso nor across the
     * view, at most GripIK.CLEAR_SPEED; below it the elbow stays Tarkov's. A hold turned fully half (angled grips: 139-172)
     * no turn reaches (60-150 degrees, the elbow 10-33 cm, mostly through the torso) - TW/GripTargets gives the hand.
     */
    static final double FP_RELIEF_FROM = Math.toRadians(130);
    /** How far the elbow plane turns towards Tarkov's bend goal of the pose. */
    static final double POLE_K = 1;
    /**
     * FB7 item 2 (user's report of 22.10.2026: the left hand "teleports" while running, most in patrol): near full extension
     * the two-bone solve is singular - the elbow's distance from the shoulder-wrist line, sqrt(l1^2 - x^2), leaves 0 with an
     * infinite slope - and supportReach slides an out-of-reach support contact exactly to the edge of the reach, so the arm
     * popped 1.5-2.5 cm in one frame each time the run cycle brought the handguard back within reach (QA run-grips3-long,
     * AK-74N high walk at 262/900/1544/2173/2817 ms, elbowDeg leaving 179.9). The last SOFT_REACH of the reach is approached
     * exponentially (soft IK, softGoal): the arm straightens continuously; a goal at full reach is met 0.37 SOFT_REACH short.
     * With a deployed launcher (hard holds) only the last REACH_MARGIN is soft (softZone).
     */
    static final double SOFT_REACH = 1.5 * CM;
    /** FB7 item 2: the first-person clavicle assist starts this far before the reach and takes the excess over smoothly. */
    static final double CLAVICLE_SOFT = 2 * CM;
    /**
     * FB7 item 2: the obstacle retreat follows TWObstacle's ratio (eased in Lua by Tarkov's inRate/outRate, a first-order lag
     * that starts at full speed: a target step of 0.5 moved a rifle 2.4 cm in the first 60 Hz frame) as a critically damped
     * spring of this angular frequency (obstacleStep): it starts at rest, at most OBSTACLE_RATE a second - at a rifle's pull
     * (14 cm by the ratio 0.35) 1 cm a 60 Hz frame.
     */
    static final double OBSTACLE_OMEGA = 12, OBSTACLE_RATE = 1.5;
    /**
     * FB7 item 2: the elbow's body clearance in first person (arm(), fpClearTurn). The run's bob brought the chest onto
     * a pistol elbow every step: inside the body the turn jumped at once to the first clearing 15-degree sample, and with
     * the body gone it fell back over CLEAR_FREE_S - elbowTurn went 6 <-> 25 degrees a step, the elbow 1.6-2.2 cm a
     * 1/60 s in the camera's frame (3.4 at most; QA run-fp1). Here the turn clears the body by FP_CLEAR_MARGIN ahead of
     * contact, at the exact angle (clearFine, not the sample), holds after the body leaves for FP_CLEAR_HOLD_S (a step),
     * then comes back at FP_CLEAR_RELEASE; it follows that goal at most FP_SWIVEL_SPEED. An elbow the margin did not
     * keep out of the body still leaves it as before (SWIVEL_INSIDE_SPEED and the snap): no sinking.
     */
    static final double FP_CLEAR_MARGIN = 2 * CM, FP_CLEAR_HOLD_S = 0.45, FP_CLEAR_RELEASE = Math.toRadians(40),
            FP_SWIVEL_SPEED = Math.toRadians(240);
    /**
     * W9 item 12 (user 26.10.2026: allow the arms to pass through the body when avoiding it twists or breaks them):
     * body clearance is a soft preference bounded by natural elbow swing (FP_CLEAR_NATURAL_MAX = 115 deg), natural wrist
     * bend (FP_CLEAR_BEND_MAX, below limitWrist's MAX_BEND so fingers are never torn off the grip), natural anatomical
     * wrist twist (FP_CLEAR_TWIST_MAX) and elbow height at or below the shoulder (FP_CLEAR_SHOULDER_CM). Where no
     * natural turn clears, the remaining turn tapers smoothly to 0 over FP_SOFT_PASS of penetration so the arm keeps
     * its natural IK solution through the torso without snaps or flips.
     */
    static final double FP_CLEAR_NATURAL_MAX = Math.toRadians(115), FP_CLEAR_BEND_MAX = Math.toRadians(80),
            FP_CLEAR_TWIST_MAX = Math.toRadians(115), FP_CLEAR_SHOULDER_CM = 2 * CM, FP_SOFT_PASS = 4 * CM,
            FP_SAFE_SNAP = Math.toRadians(6);
    /**
     * W9-arms2 (item 12 follow-up): pre-clearance support-hand wrist feasibility on the shoulder-wrist IK circle.
     * In first person at intermediate pitch (-30..-65 deg), FpPose.pole rotates Tarkov's Bend_Goal_Left with camera
     * pitch, placing the raw two-bone elbow ahead of the support wrist so wristBend(FAn, Hn) exceeds GripIK.MAX_BEND
     * (85 deg) and GripIK.limitWrist clamps the support hand off the grip before body clearance ever activates.
     * wristSwivel smoothly swivels the left elbow onto the anatomical below-shoulder feasible arc once bend0 > 82.2 deg.
     */
    static final double FP_WRIST_BEND_START = Math.toRadians(82.2),
            FP_WRIST_BEND_CAP = GripIK.MAX_BEND - Math.toRadians(0.2),
            FP_WRIST_BEND_SOFT = FP_WRIST_BEND_CAP - FP_WRIST_BEND_START,
            FP_WRIST_SWIVEL_SPEED = Math.toRadians(420.0);
    /** Out of reach (outside ADS): the weapon with both hand targets slides back along the view, at most this. */
    static final double SLIDE_MAX = 15 * CM;
    /** The magazine bone stays in the support hand while it is within these of it (and not seated). */
    static final double MAG_HELD_NEAR = 6 * CM, MAG_HELD_FAR = 14 * CM;
    // Kept for the recoil check's old wall-clock negative control; admission uses world frames.
    static final long STALE_NS = 500_000_000L, RESET_NS = 200_000_000L;
    static final int FRESH_FRAMES = 3;
    static final int CARRY_IDLE = 0, CARRY_LOW = 1, CARRY_HIGH = 2, CARRY_PATROL = 3;

    public static volatile boolean enabled = true;
    /** QA: scales Tarkov's aim time (the B1 formula gives 0.4-1.9 s; the in-game feel is not verified). */
    public static volatile double adsTimeScale = 1;
    public static volatile long calls, applied, fpFrames, retreats, eyePairs, eyeMissing, slides, clavicles, elbowTurns, actions, errors, nanos;
    /** FB7 item 1: updates of another AnimationPlayer reporting the character (the character info window's avatar), skipped. */
    public static volatile long foreign;
    public static volatile long hipRaises, hipReady, hipInterrupted, reliefs;
    /** QA: the elbow's relief (FP_RELIEF_FROM) on or off. */
    public static volatile boolean reliefOn = true;
    /** W10 manualeye (QA A/B): Tarkov first-person eye-level poses for manual cycling, round reloads and unloads. */
    public static volatile boolean manualEye = true;
    /** W11 torchfp (QA A/B): true = legacy lower-left corner FP torch pose; false = 3P Harries hold under gun hand. */
    public static volatile boolean qaLegacyTorchFp = false;
    /** W12 patrol (QA A/B): true = former camera-pitched eye frame in Patrol; false = torso-aligned yaw-only eye frame. */
    public static volatile boolean qaPatrolPitch = false;

    /**
     * Tarkov's trigger of a pose family (tools/tarkov_hip_fire.py -> TW/Data/HipFire, handed over once):
     * whether the trigger alone fires from its ready state, the seconds of PATROL IN / PATROL OUT
     * (clip / state speed), the cross-fade into PATROL OUT, PATROL OUT's exit, its fade into IDLE
     * and the trigger-to-IDLE ready time; NaN where the family has no patrol (an underbarrel).
     */
    static final class Hip {
        final boolean fire;
        final double patrolIn, patrolOut, enter, exit, fade, ready;

        Hip(boolean fire, double patrolIn, double patrolOut, double enter, double exit, double fade, double ready) {
            this.fire = fire;
            this.patrolIn = patrolIn;
            this.patrolOut = patrolOut;
            this.enter = enter;
            this.exit = exit;
            this.fade = fade;
            this.ready = ready;
        }

        boolean patrol() {
            return ready > 0 && patrolOut > 0;
        }
    }

    static volatile java.util.Map<String, Hip> HIP = java.util.Map.of();
    /** A deployed underbarrel's template -> its family's Hip. */
    static volatile java.util.Map<String, Hip> HIP_LAUNCHERS = java.util.Map.of();
    public static volatile String lastError = "";
    private static volatile boolean dataLogged;

    static final class Request {
        final Object item;
        final KahluaTable source;
        final String weapon, cls, launcher;
        final boolean spent;
        final double[] dR, rear, mount, launcherRange;
        final double[] left, right, handA, bounds;
        /** Pistol in both hands (TW/PistolGripData via the handgun params): both hand bones on this class's grip (Prop1, 4x4)
         *  and their fingers (rotations relative to the hand, rows: left Finger0, left Finger1, right Finger0, right Finger1). */
        final double[][] hgHands, hgFingers;
        /** QA: the weights of the grip fingers applied last frame {left, right}. */
        final double[] hgW = new double[2];
        double patrolSurfaceW;
        /** The support knuckles' miss of their place on the grip last frame (cm: the wrist on its target, the hand turned off it -
         *  hip fire looking down -85, 12-14 cm) and the support grip's weight that follows it (handgunReach) with its clock. */
        double hgMiss, hgReach = 1;
        long hgReachAt;
        double[] sight;
        /** A deployed underbarrel: the support wrist holding the launcher (own Prop1 space, male/female; launcherHold), else null. */
        double[][] launcherHold;
        /** The support contact last placed (own Prop1 space) and, after the launcher was deployed or stowed, the one it
         *  moves from over HOLD_SWITCH_S (supportFade 0..1). */
        double[] supportSeen, supportFrom;
        double supportFade = 1;
        /**
         * FB5 items 5, 7: the selected sight's eyepiece rim and its part's box corners (own Prop1 space, x, y, z rows) and
         * how near each may come to the eye (model units: the near plane and a margin, or less where the ADS pose itself
         * has it nearer - an eyecup round the eye); null without them. QA: the last push (cm) and its count.
         */
        final double[][] nearPoints;
        final double[] nearAllowed;
        /** W7: the sight's own aperture (the eyepiece rim, or an iron rear feature) - the ADS depth clamp's decision. */
        final double[][] nearSight;
        double nearPushCm, nearCutCm;
        long nearPushes;
        long shotGeneration;
        boolean shotApplied;
        final double ergonomics, weight, pullUntil, back, up, pitch, elbowOut, chest;
        // Tarkov recoil (FpRecoil): the weapon of the recoil data ("" none, a deployed underbarrel),
        // its installed parts ("path=tpl"), the grip offset (source metres), the pistol roll, rpm
        final String recoilKey;
        final java.util.List<String> parts;
        final double gripOffset, rpm;
        final double supportBack;
        final boolean rolled;
        /** The weapon is a handgun of the class (Lua: TW/WeaponLength pistol or revolver, t.handgun): its own muzzle-up
         * High Ready (PISTOL_HIGH_PITCH) and the ADS hand clearance (PISTOL_ADS_HANDS) apply. */
        final boolean handgun;
        /** W7: the selected channel is an iron/built-in sight of the weapon (Lua: TW/SightState view.kind 'iron'/'aim').
         * It has no eyepiece tube round the eye, so the ADS depth clamp may flatten the weapon body behind it instead of
         * cutting it open (FpViewLatch.adsClampSafe); its own rear feature (t.iron) stays beyond the near plane. */
        final boolean ironSight;
        /** QA: the slide ahead (cm) of the last ADS hand clearance and the sight's own offset (cm) from the eye. */
        double adsReliefCm, sightReliefCm;
        /** QA item 4: the nearest hand point in the drawn first-person pose, cm in front of the eye. */
        double handNearCm;
        /** W7 QA: the nearest of the sight's own near points in the drawn pose (cm in front of the eye) and whether the
         * ADS depth clamp is on for this sight (FpViewLatch.adsClampSafe). */
        double sightNearCm = Double.POSITIVE_INFINITY;
        boolean clampSafe;
        volatile long stamp = System.nanoTime();
        volatile long stampFrame = FpRecoil.worldFrame();
        // fpPoseState, every tick
        volatile boolean aiming, acting, allowed = true;
        volatile int carry;
        volatile double obstacle;
        /** FB7 item 2: the retreat ratio as the pose uses it (obstacleStep), its rate per second, the update it advanced at. */
        double obstacleNow, obstacleRate;
        long obstacleAt;
        // hip fire (fpPoseTrigger, every tick while held): the trigger and the layer's answer
        volatile boolean trigger;
        volatile long triggerFrame = -1;
        volatile String hip = "off:released";
        // the ready transition the trigger started (animation update): the stance left, when, how long, from where
        boolean triggerOn;
        int readyFrom = -1;
        long readyAt;
        double readySeconds, readyHigh, readyPatrol;
        double[] readyPose;
        // pose state (animation update)
        long at, carryAt;
        int lastCarry = -1, prevCarry = -1;
        double ads, highW, patrolW, runW, actionW, actionU, fpW;
        /** FB8 item 6 (QA): the aim time T of the last update (s; Tarkov aimingSpeed = 1 / T). */
        double adsTime;
        boolean utilitySwitch;
        /** place()'s reach corrections: 0 held, 1 released to an action (ACTING_TAU_S; a fire-mode switch keeps them). */
        double actingW;
        /** FB4-A: the fire-mode gesture (TW/WeaponInspect, no engine track): its clip, fraction, number, world frame. */
        volatile String gestureClip;
        volatile double gestureU;
        volatile long gestureToken, gestureFrame = -1;
        /** Its drawn relative pose, the pose a restart fades from and the fade (GESTURE_FADE_S), the number seen. */
        double[] gestureLast, gestureFrom;
        double gestureFade = 1;
        long gestureSeen;
        double[] stance;
        double[][] handDelta;
        double[] handoff;
        double handoffW, handoffAds;
        FpPoseData.Track action;
        AnimationTrack manualAnimTrack;
        FpPoseData.Track manualFpTrack;
        double actionHoldS;
        double[] neutral;
        long neutralAt;
        long neutralFrame = -1;
        // eye pairing: the head, position and angle seen on the last update (before Viewpoint's snapshot)
        long eyeNumber;
        boolean stamped, eyeValid;
        double sx, sy, sz, sAngle;
        double[] sHead = new double[3], eyeRel = new double[3];
        /** Bug wave 324 (the user's item 2): Viewpoint's free look - the weapon frame's look while held and on the way back. */
        FpFreeLook.State freeLook = new FpFreeLook.State();
        // arms
        final double[] elbowTurn = new double[2], elbowSide = new double[2];
        /** FB7 item 2: first person - the clearance turn held after the body left the elbow, and how long it has been held (s). */
        final double[] elbowHold = new double[2], elbowHoldS = new double[2];
        long elbowAt;
        /** The elbow's relief turn of each arm (radians, FP_RELIEF_FROM) and the wrist's twist it kept, last frame. */
        final double[] relief = new double[2], reliefTwist = new double[2];
        /** W9-arms2: pre-clearance wrist feasibility swivel (radians), active branch (-1/0/+1), radial pole and wrap flag. */
        final double[] wristSwivel = new double[2];
        final int[] wristBranch = new int[2];
        final double[][] wristPole = new double[2][];
        final boolean[] wristWrapped = new boolean[2];
        // QA
        String mode = "", track = "";
        double[] eye, eyeModel, poseEyeModel, eyePose, wristEye, supportEye, elbowsEye, recoilShift;
        double sightError, sightAxisError, recoilSight, elbowDepth, armNear, contactClear;
        double handMiss, sourceContactMiss, elbowSideMin, sourceContactAdjust, knuckleMiss;
        final double[] fingerFit = new double[2];
        double bodyTurn, bodyGoal, bodyCap, reachBefore, reachAfter;
        /** FB1: the upper body's turn about the eye along a look above level (FpPoseReach.lift). */
        double bodyPitch, pitchGoal;
        double[] highPull = new double[3];
        /** QA (wave 21.10.2026, item 10): the reach pull of an underbarrel's own action (place(), launcherAction). */
        double[] reloadPull = new double[3];
        /** QA (FB5 item 4): the slide back along the view of the last place() (cm). */
        double slideCm;
        /** The slide out of reach held through a shot (slideHeld; model units, before the ADS and ready weights), its clock. */
        double slideHold, slideHoldCm, slideLast;
        long slideAt, slideFiredAt;
        boolean forearmsCross;
        double torchHandDistCm = Double.NaN, torchHlRelErrCm = Double.NaN, torchBeamDeg = Double.NaN, torchOffhandW = 0;

        Request(Object item, KahluaTable t) {
            this.item = item;
            this.source = t;
            weapon = text(t, "weapon");
            cls = text(t, "class");
            launcher = text(t, "launcher");
            spent = Boolean.TRUE.equals(t.rawget("spent"));
            ironSight = Boolean.TRUE.equals(t.rawget("ironSight"));
            double[] m = numbers(t, "mount", 12);
            mount = m == null ? null : GripIK.rows(m, 0);
            double[] lr = numbers(t, "launcherRange", 12);
            launcherRange = lr == null ? null : rigidMove(GripIK.rows(lr, 0));
            double[] d = numbers(t, "dR", 3);
            dR = d == null ? new double[3] : d;
            double[] l = numbers(t, "left", 12), a = numbers(t, "handA", 12);
            left = l == null ? null : GripIK.rows(l, 0);
            handA = a == null ? null : GripIK.rows(a, 0);
            right = numbers(t, "right", 3);
            double[] hh = numbers(t, "hgHands", 24), hf = numbers(t, "hgFingers", 36);
            hgHands = hh == null ? null : new double[][] {GripIK.rows(hh, 0), GripIK.rows(hh, 12)};
            hgFingers = hf == null ? null : new double[][] {java.util.Arrays.copyOfRange(hf, 0, 9), java.util.Arrays.copyOfRange(hf, 9, 18),
                    java.util.Arrays.copyOfRange(hf, 18, 27), java.util.Arrays.copyOfRange(hf, 27, 36)};
            bounds = numbers(t, "bounds", 6);
            supportBack = Math.max(0, number(t, "supportBack", 0));
            double[] s = numbers(t, "sight", 12);
            sight = s == null ? null : GripIK.rows(s, 0);
            double[][] near = nearPoints(numbers(t, "ocular", 7), numbers(t, "sightBox", 24), numbers(t, "iron", 7));
            nearPoints = near;
            nearSight = sightAperture(numbers(t, "ocular", 7), numbers(t, "iron", 7));
            nearAllowed = near == null ? null : nearAllowed(near, sight, ironSight);
            rear = numbers(t, "rear", 3);
            ergonomics = number(t, "ergonomics", 50);
            weight = number(t, "weight", 3.5);
            double[] q = numbers(t, "retreat", 6);
            // pull until (ratio), back, up (model units), pitch (degrees), elbow out, chest clearance (model units)
            pullUntil = q == null ? 0.35 : q[0];
            back = q == null ? 14 * CM : q[1];
            up = q == null ? 8 * CM : q[2];
            pitch = Math.toRadians(q == null ? 62 : q[3]);
            elbowOut = q == null ? 7 * CM : q[4];
            chest = q == null ? 6 * CM : q[5];
            recoilKey = text(t, "recoil");
            java.util.List<String> list = new java.util.ArrayList<>();
            Object ps = t.rawget("parts");
            if (ps instanceof KahluaTable pt) {
                for (int i = 1; pt.rawget(i) instanceof String part && part.indexOf('=') > 0; i++) {
                    list.add(part);
                }
            }
            parts = java.util.List.copyOf(list);
            gripOffset = number(t, "gripOffset", 0);
            rpm = number(t, "rpm", 0);
            rolled = Boolean.TRUE.equals(t.rawget("rolled"));
            handgun = Boolean.TRUE.equals(t.rawget("handgun"));
        }

        void carryOver(Request r) {
            shotGeneration = r.shotGeneration;
            aiming = r.aiming;
            acting = r.acting;
            allowed = r.allowed;
            fpW = r.fpW;
            carry = r.carry;
            obstacle = r.obstacle;
            trigger = r.trigger;
            triggerFrame = r.triggerFrame;
            hip = r.hip;
            triggerOn = r.triggerOn;
            readyFrom = r.readyFrom;
            readyAt = r.readyAt;
            readySeconds = r.readySeconds;
            readyHigh = r.readyHigh;
            readyPatrol = r.readyPatrol;
            readyPose = r.readyPose;
            at = r.at;
            carryAt = r.carryAt;
            lastCarry = r.lastCarry;
            prevCarry = r.prevCarry;
            ads = r.ads;
            highW = r.highW;
            patrolW = r.patrolW;
            runW = r.runW;
            actionW = r.actionW;
            actionU = r.actionU;
            actingW = r.actingW;
            gestureClip = r.gestureClip;
            gestureU = r.gestureU;
            gestureToken = r.gestureToken;
            gestureFrame = r.gestureFrame;
            gestureLast = r.gestureLast;
            gestureFrom = r.gestureFrom;
            gestureFade = r.gestureFade;
            gestureSeen = r.gestureSeen;
            // FB4-A: smoothed states too - a new table mid-gesture restarted the body pitch from zero for a frame
            bodyPitch = r.bodyPitch;
            utilitySwitch = r.utilitySwitch;
            stance = r.stance;
            handDelta = r.handDelta;
            handoff = r.handoff;
            handoffW = r.handoffW;
            handoffAds = r.handoffAds;
            action = r.action;
            eyeNumber = r.eyeNumber;
            stamped = r.stamped;
            eyeValid = r.eyeValid;
            sx = r.sx;
            sy = r.sy;
            sz = r.sz;
            sAngle = r.sAngle;
            sHead = r.sHead;
            eyeRel = r.eyeRel;
            freeLook = r.freeLook;
            System.arraycopy(r.elbowTurn, 0, elbowTurn, 0, 2);
            System.arraycopy(r.elbowSide, 0, elbowSide, 0, 2);
            elbowAt = r.elbowAt;
            System.arraycopy(r.relief, 0, relief, 0, 2);
            System.arraycopy(r.reliefTwist, 0, reliefTwist, 0, 2);
            System.arraycopy(r.elbowHold, 0, elbowHold, 0, 2);
            System.arraycopy(r.elbowHoldS, 0, elbowHoldS, 0, 2);
            System.arraycopy(r.wristSwivel, 0, wristSwivel, 0, 2);
            System.arraycopy(r.wristBranch, 0, wristBranch, 0, 2);
            System.arraycopy(r.wristWrapped, 0, wristWrapped, 0, 2);
            wristPole[0] = r.wristPole[0] == null ? null : r.wristPole[0].clone();
            wristPole[1] = r.wristPole[1] == null ? null : r.wristPole[1].clone();
            bodyTurn = r.bodyTurn;
            hgMiss = r.hgMiss;
            hgReach = r.hgReach;
            hgReachAt = r.hgReachAt;
            reloadPull = r.reloadPull;
            // the launcher deployed or stowed: the support hand moves from where it held (handContact)
            boolean switched = launcher.isEmpty() != r.launcher.isEmpty();
            supportFrom = switched ? r.supportSeen : r.supportFrom;
            supportFade = switched ? (r.supportSeen == null ? 1 : 0) : r.supportFade;
            supportSeen = r.supportSeen;
        }
    }

    static final ConcurrentHashMap<IsoGameCharacter, Request> requests = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<SkinningData, Skeleton> SKELETONS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Matrix4f> TMP = ThreadLocal.withInitial(Matrix4f::new);

    private FpPose() {
    }

    // ------------------------------------------------------------------ Lua (TWZB)

    /** Renew a character's request; the table is copied only when Lua hands over a new one (as GripIK). */
    static void set(IsoGameCharacter c, Object item, KahluaTable t) {
        Request r = requests.get(c);
        if (r != null && r.item == item && r.source == t) {
            r.stamp = System.nanoTime();
            r.stampFrame = FpRecoil.worldFrame();
            return;
        }
        Request n = new Request(item, t);
        if (r != null && r.item == item) {
            n.carryOver(r);
        } else {
            handoff(n, r, System.nanoTime());
        }
        requests.put(c, n);
    }

    /** Same tube family only; the new item never inherits the old item's applied generation. */
    static void handoff(Request next, Request old, long now) {
        if (old != null && (old.spent || next.spent) && old.weapon.equals(next.weapon)
                && old.eyePose != null && old.fpW > 0.5 && now - old.at < RESET_NS) {
            next.handoff = old.eyePose.clone();
            next.handoffW = 1;
            next.handoffAds = old.ads + (old.handoffAds - old.ads) * old.handoffW;
            // The viewer/character did not change at disposal. Keep its proven
            // eye pairing so the replacement's first frame can be placed too.
            next.eyeNumber = old.eyeNumber;
            next.stamped = old.stamped;
            next.eyeValid = old.eyeValid;
            next.sx = old.sx; next.sy = old.sy; next.sz = old.sz; next.sAngle = old.sAngle;
            next.sHead = old.sHead.clone();
            next.eyeRel = old.eyeRel.clone();
        }
    }

    static double[] handoffPose(Request r, double[] q) {
        return r.handoff == null ? q : interp(q, r.handoff, r.handoffW);
    }

    /**
     * Every tick: aiming, the carry stance ("low", "high", "patrol", "" none), the obstacle ratio
     * (0..1), whether a timed action runs and whether the first-person poses are allowed.
     */
    static void state(IsoGameCharacter c, boolean aiming, String carry, double obstacle, boolean acting, boolean allowed) {
        Request r = requests.get(c);
        if (r != null) {
            r.aiming = aiming && !r.spent;
            r.acting = acting;
            r.allowed = allowed;
            r.carry = r.spent || "low".equals(carry) ? CARRY_LOW : "high".equals(carry) ? CARRY_HIGH : "patrol".equals(carry) ? CARRY_PATROL : CARRY_IDLE;
            r.obstacle = Double.isFinite(obstacle) ? Math.max(0, Math.min(1, obstacle)) : 0;
            r.stamp = System.nanoTime();
            r.stampFrame = FpRecoil.worldFrame();
        }
    }

    static void clear(IsoGameCharacter c) {
        requests.remove(c);
    }

    /** FB4-A: the fire-mode gesture of a character, renewed every tick while it runs (stale after FRESH_FRAMES); null clip: none. */
    static void gesture(IsoGameCharacter c, String clip, double u, long token) {
        Request r = requests.get(c);
        if (r != null) {
            r.gestureClip = clip;
            r.gestureU = Double.isFinite(u) ? Math.max(0, Math.min(1, u)) : 0;
            r.gestureToken = token;
            r.gestureFrame = FpRecoil.worldFrame();
        }
    }

    /**
     * The hip trigger of a character held (renewed every tick, stale after FRESH_FRAMES world frames)
     * or released; the answer of the layer's last update: "ready", "raising" or "off:<reason>".
     */
    static String trigger(IsoGameCharacter c, boolean held) {
        Request r = c == null ? null : requests.get(c);
        if (r == null) {
            return "off:request";
        }
        r.trigger = held;
        r.triggerFrame = FpRecoil.worldFrame();
        return held ? r.hip : "off:released";
    }

    /** TW/Data/HipFire: {families={<family>={hip=, patrolIn=, patrolOut=, enter=, exit=, fade=, ready=, template=}}}. */
    static void hipData(KahluaTable t) {
        java.util.Map<String, Hip> families = new java.util.HashMap<>(), launchers = new java.util.HashMap<>();
        Object fs = t == null ? null : t.rawget("families");
        if (fs instanceof KahluaTable ft) {
            for (var it = ft.iterator(); it.advance(); ) {
                if (!(it.getKey() instanceof String name) || !(it.getValue() instanceof KahluaTable row)) {
                    continue;
                }
                Hip h = new Hip(Boolean.TRUE.equals(row.rawget("hip")), number(row, "patrolIn", Double.NaN), number(row, "patrolOut", Double.NaN),
                        number(row, "enter", 0), number(row, "exit", 0), number(row, "fade", 0), number(row, "ready", Double.NaN));
                families.put(name, h);
                if (row.rawget("template") instanceof String tpl) {
                    launchers.put(tpl, h);
                }
            }
        }
        HIP = java.util.Map.copyOf(families);
        HIP_LAUNCHERS = java.util.Map.copyOf(launchers);
    }

    /** The trigger timing of a request: its pose family's (a deployed underbarrel moves with its host's); null without data. */
    static Hip hip(FpPoseData.Family f, Request r) {
        return f == null ? null : HIP.get(f.name);
    }

    /** Whether the trigger alone fires this request's weapon: a deployed underbarrel by its own controller, else its family's. */
    static boolean hipFires(FpPoseData.Family f, Request r) {
        Hip h = r.launcher.isEmpty() ? hip(f, r) : HIP_LAUNCHERS.get(r.launcher);
        return h != null && h.fire && !r.spent;
    }

    /**
     * The trigger pressed: the carry stance drawn last (low, high or patrol) moves to the hip pose over
     * the family's Tarkov ready time; from the stance's drawn pose (high: the hip stance and its retreat weight).
     */
    static void startReady(Request r, Hip hd, long t0) {
        r.readyFrom = -1;
        int from = r.lastCarry;
        if (from != CARRY_LOW && from != CARRY_HIGH && from != CARRY_PATROL || r.stance == null || hd == null || !(hd.ready > 0)) {
            return;
        }
        r.readyFrom = from;
        r.readyAt = t0;
        r.readySeconds = hd.ready;
        r.readyPose = r.stance.clone();
        r.readyHigh = r.highW;
        r.readyPatrol = r.patrolW;
        hipRaises++;
    }

    /**
     * Out of patrol on Tarkov's timeline t seconds after the trigger (eye <- own Prop1): PATROL OUT at its
     * state speed, cross-faded in from the patrol pose over enter, faded into the hip (IDLE) from
     * max(enter, exit) over fade; at ready the hip pose itself. Unity's cross-fades are linear weights.
     */
    static double[] patrolReady(FpPoseData.Family f, double[] shift, Hip h, double[] from, double[] hip, double t) {
        if (t >= h.ready) {
            return hip.clone();
        }
        double[] out = GripIK.mulNew(f.patrolOut.pose(Math.min(1, t / h.patrolOut)), shift);
        double[] q = h.enter > 1e-6 && t < h.enter ? interp(from, out, t / h.enter) : out;
        double fadeStart = Math.max(h.enter, h.exit);
        if (t > fadeStart) {
            q = interp(q, hip, h.fade > 1e-6 ? Math.min(1, (t - fadeStart) / h.fade) : 1);
        }
        return q;
    }

    /** The drawn stance is the hip pose: within 1 mm and 0.1 degree, no high-ready retreat left. */
    static boolean atHip(double[] stance, double[] hip, double highW) {
        if (stance == null || highW > 1e-3 || GripIK.dist(GripIK.pos(stance), GripIK.pos(hip)) > 0.1 * CM) {
            return false;
        }
        double[] rel = GripIK.mulNew(GripIK.inverse(hip), stance);
        double cos = (rel[0] + rel[5] + rel[10] - 1) / 2;
        return cos >= Math.cos(Math.toRadians(0.1));
    }

    /** Why the layer holds no first-person pose for the trigger: not allowed, in a vehicle, or another view. */
    static String offView(Request r, IsoGameCharacter c) {
        return !r.allowed ? "off:allowed" : c.getVehicle() != null ? "off:vehicle" : "off:view";
    }

    /** No first-person pose this update: the trigger has nothing to raise. */
    static void releaseTrigger(Request r, String why) {
        r.readyFrom = -1;
        r.triggerOn = false;
        r.hip = r.trigger && freshFrame(r.triggerFrame, FpRecoil.worldFrame()) ? why : "off:released";
    }

    private static String text(KahluaTable t, String key) {
        Object v = t.rawget(key);
        return v instanceof String ? (String) v : "";
    }

    private static double number(KahluaTable t, String key, double def) {
        Object v = t.rawget(key);
        return v instanceof Double && Double.isFinite((Double) v) ? (Double) v : def;
    }

    private static double[] numbers(KahluaTable t, String key, int n) {
        Object v = t.rawget(key);
        if (!(v instanceof KahluaTable)) {
            return null;
        }
        KahluaTable a = (KahluaTable) v;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            Object x = a.rawget(i + 1);
            if (!(x instanceof Double) || !Double.isFinite((Double) x)) {
                return null;
            }
            out[i] = (Double) x;
        }
        return out;
    }

    // ------------------------------------------------------------------ the advice (GripIK.AfterModel)

    public static void onModel(Object self) {
        if (!enabled || requests.isEmpty()) {
            return;
        }
        if (AnimOptOut.blocksFpPose(self)) return; // W8 animopt: the first-person pose/retreat belongs to vanilla/Viewpoint
        // apply's own first returns, before the clock (every zombie's AnimationPlayer passes here; performance pass 23.10.2026)
        if (!PerfLegacy.on) {
            if (!(self instanceof AnimationPlayer ap) || ap.parentPlayer != null) {
                return;
            }
            IsoGameCharacter character = ap.getIsoGameCharacter();
            if (character == null || !requests.containsKey(character)) {
                return;
            }
        }
        long t0 = System.nanoTime();
        try {
            apply((AnimationPlayer) self, t0);
        } catch (Throwable e) {
            errors++;
            lastError = e.toString();
        } finally {
            nanos += System.nanoTime() - t0;
        }
    }

    static final class Skeleton {
        int n, lc, ua, fa, lh, rc, rua, rfa, rh, prop, prop2, head;
        /** Both arms' skin binds (ArmNatural.binds): the wrist's anatomical twist for the elbow's relief; null - none. */
        ArmNatural.Bind[] binds;
        /** Finger0 (thumb) and Finger1 of each hand, -1 when absent or not the hand's children. */
        int lf0 = -1, lf1 = -1, rf0 = -1, rf1 = -1;
        int[] parent, chain;
    }

    static Skeleton skeleton(SkinningData sd, int count) {
        Skeleton s = SKELETONS.get(sd);
        if (s == null) {
            s = new Skeleton();
            s.n = Math.min(sd.numBones(), count);
            s.parent = new int[s.n];
            for (int i = 0; i < s.n; i++) {
                s.parent[i] = GripIK.parent(sd, i);
            }
            s.lc = GripIK.index(sd, "Bip01_L_Clavicle");
            s.ua = GripIK.index(sd, "Bip01_L_UpperArm");
            s.fa = GripIK.index(sd, "Bip01_L_Forearm");
            s.lh = GripIK.index(sd, "Bip01_L_Hand");
            s.rc = GripIK.index(sd, "Bip01_R_Clavicle");
            s.rua = GripIK.index(sd, "Bip01_R_UpperArm");
            s.rfa = GripIK.index(sd, "Bip01_R_Forearm");
            s.rh = GripIK.index(sd, "Bip01_R_Hand");
            s.prop = GripIK.index(sd, "Bip01_Prop1");
            s.prop2 = GripIK.index(sd, "Bip01_Prop2");
            s.head = GripIK.index(sd, "Bip01_Head");
            s.chain = new int[] {GripIK.index(sd, "Bip01_Pelvis"), GripIK.index(sd, "Bip01_Spine"), GripIK.index(sd, "Bip01_Spine1"), GripIK.index(sd, "Bip01_Neck")};
            boolean ok = s.n <= 128 && s.lc >= 0 && s.ua >= 0 && s.fa >= 0 && s.lh >= 0 && s.rc >= 0 && s.rua >= 0 && s.rfa >= 0 && s.rh >= 0
                    && s.prop >= 0 && s.head >= 0 && s.lh < s.n && s.rh < s.n && s.prop < s.n && s.head < s.n && s.parent[s.prop] >= 0
                    && s.parent[s.ua] == s.lc && s.parent[s.fa] == s.ua && s.parent[s.lh] == s.fa
                    && s.parent[s.rua] == s.rc && s.parent[s.rfa] == s.rua && s.parent[s.rh] == s.rfa && s.parent[s.lc] >= 0 && s.parent[s.rc] >= 0;
            for (int i = 0; ok && i < s.n; i++) {
                ok = s.parent[i] < i; // parents first, as the engine composes them
            }
            for (int b : s.chain) {
                ok &= b >= 0 && b < s.n;
            }
            if (!ok) {
                s.n = -1;
            } else {
                s.lf0 = child(sd, s, "Bip01_L_Finger0", s.lh);
                s.lf1 = child(sd, s, "Bip01_L_Finger1", s.lh);
                s.rf0 = child(sd, s, "Bip01_R_Finger0", s.rh);
                s.rf1 = child(sd, s, "Bip01_R_Finger1", s.rh);
                s.binds = ArmNatural.binds(sd, s);
            }
            SKELETONS.put(sd, s);
        }
        return s.n < 0 ? null : s;
    }

    /** A bone that is the given parent's child, else -1 (the hands then keep their animated fingers). */
    static int child(SkinningData sd, Skeleton s, String name, int parent) {
        int i = GripIK.index(sd, name);
        return i > 0 && i < s.n && s.parent[i] == parent ? i : -1;
    }

    static void apply(AnimationPlayer ap, long t0) {
        if (ap.parentPlayer != null) {
            return;
        }
        IsoGameCharacter c = ap.getIsoGameCharacter();
        Request r = c == null ? null : requests.get(c);
        if (r == null) {
            return;
        }
        if (ap != c.getAnimationPlayer()) { // FB7: another AnimationPlayer of the character (the info window's avatar) keeps its pose
            foreign++;
            return;
        }
        calls++;
        r.shotApplied = false;
        if (!freshFrame(r.stampFrame, FpRecoil.worldFrame()) || c.getPrimaryHandItem() != r.item) {
            r.neutral = null;
            releaseTrigger(r, "off:request");
            return;
        }
        obstacleStep(r, t0);
        SkinningData sd = ap.getSkinningData();
        AnimatorsBoneTransform[] bt = ap.boneTransforms;
        if (sd == null || bt == null) {
            return;
        }
        Skeleton s = skeleton(sd, Math.min(bt.length, ap.getModelTransformsCount()));
        if (s == null) {
            return;
        }
        int view = r.allowed && c.getVehicle() == null ? ViewpointBridge.state() : 0;
        if (view != 1 && r.obstacleNow <= 1e-4) { // nothing to place: the neutral frame is the current one
            r.neutral = GripIK.read(ap.getModelTransformAt(s.prop), new double[16]);
            r.neutralAt = t0;
            r.neutralFrame = FpRecoil.worldFrame();
            r.at = t0;
            r.stance = null;
            r.stamped = false;
            r.eyeValid = false;
            r.fpW = 0;
            r.patrolW = 0;
            r.poseEyeModel = null;
            r.bodyTurn = 0;
            r.bodyPitch = 0;
            r.mode = "none";
            r.track = "";
            releaseTrigger(r, offView(r, c));
            return;
        }
        double[][] model = new double[s.n][];
        for (int i = 0; i < s.n; i++) {
            model[i] = GripIK.read(ap.getModelTransformAt(i), new double[16]);
        }
        double dt = r.at == 0 || t0 - r.at > RESET_NS ? -1 : (t0 - r.at) / 1e9;
        r.at = t0;
        double[] eye = null;
        FpPoseData data = null;
        FpPoseData.Family family = null;
        if (view == 1) {
            data = FpPoseData.get();
            family = data == null ? null : family(data, r);
            if (data == null && !dataLogged) {
                dataLogged = true;
                System.out.println("[TWZB] first-person poses off: " + FpPoseData.status);
            }
            eye = eye(c, ap, model[s.head], r);
            if (eye == null) {
                eyeMissing++;
            }
        } else {
            r.stamped = false;
            r.eyeValid = false;
        }
        if (eye == null || family == null) {
            r.fpW = 0;
            r.patrolW = 0;
            r.poseEyeModel = null;
        }
        if (eye != null && family != null) {
            // FB7 item 1: the shadow casts the third-person pose of this update (ShadowPose)
            double[][] thirdPerson = ShadowPose.wanted(c, ap) ? ShadowPose.copy(model, s.n) : null;
            firstPerson(ap, c, r, s, model, data, family, eye, dt, t0);
            if (thirdPerson != null) {
                ShadowPose.record(ap, sd, s.n, thirdPerson);
            }
        } else {
            retreatOnly(ap, c, r, s, model, dt, t0);
            releaseTrigger(r, view != 1 ? offView(r, c) : "off:eye");
        }
        r.shotGeneration++;
        r.shotApplied = true;
    }

    /** B3 pairs the copied drawn frame with this exact applied pose generation. */
    static long shotGeneration(IsoGameCharacter c,Object item) {
        Request r=requests.get(c);
        return r==null || r.item!=item || !r.shotApplied ? 0 : r.shotGeneration;
    }


    /** The character plays a state of BODY_STATES now. */
    static boolean bodyState(IsoGameCharacter c) {
        // W9-advmove: Viewpoint Advanced Movement owns the arms and weapon in its climb, hang, window vault and
        // prone transitions (and while lying still and not aiming): its pose runs at the start of Viewpoint's
        // skeleton hook, our layers run later, so the first-person pose fades out there. TW_CompatAdvancedMovement
        // sets the flag per tick and clears it on every exit path.
        if (c.getVariableBoolean("TWAdvMoveYield")) {
            return true;
        }
        // W11 buttstroke: an aimed shove - the vanilla one or Buttstroke's gun-stock strike - plays inside the aim state;
        // its clip needs the body's own arms like the shove states below. Its nodes flag ShoveAnim while they play.
        if (c.isPerformingShoveAnimation()) {
            return true;
        }
        String st = c.getCurrentActionContextStateName();
        if (st == null || st.isEmpty()) {
            return false;
        }
        st = st.toLowerCase(java.util.Locale.ROOT);
        for (String b : BODY_STATES) {
            if (st.startsWith(b)) {
                return true;
            }
        }
        return false;
    }

    /** The family of a request: the weapon's own Tarkov reference (launchers, reference weapons), else its grip class's. */
    static FpPoseData.Family family(FpPoseData d, Request r) {
        FpPoseData.Family f = d.byWeapon.get(r.weapon);
        if (f == null || !f.hasIdle()) {
            f = d.byClass.get(r.cls);
        }
        return f != null && f.hasIdle() ? f : null;
    }

    // ------------------------------------------------------------------ eye

    /** Viewpoint's eye in the character's model space: 4x4 model <- eye (+X left, +Y up, -Z forward), or null. */
    static double[] eye(IsoGameCharacter c, AnimationPlayer ap, double[] head, Request r) {
        ViewpointBridge.Eye e = ViewpointBridge.eyeNow();
        double a = ap.getRenderedAngle();
        if (e != null && e.number != r.eyeNumber && r.stamped) {
            double[] base = worldToModel(e.x - r.sx, e.y - r.sy, e.z - r.sz, r.sAngle);
            r.eyeRel = GripIK.sub(base, r.sHead);
            r.eyeNumber = e.number;
            r.eyeValid = true;
            eyePairs++;
        }
        r.sx = c.getX();
        r.sy = c.getY();
        r.sz = c.getZ();
        r.sAngle = a;
        r.sHead = GripIK.pos(head);
        r.stamped = true;
        if (e == null || !r.eyeValid) {
            return null;
        }
        // Bug wave 324 (the user's item 2, 24.10.2026): during Viewpoint's free look and its way back the frame is the
        // weapon's own look (FpFreeLook: the body's yaw and the pitch of the press), its eye where the camera would be
        // along it; otherwise the camera's (look null: this very eye, the former path)
        double[] look = FpFreeLook.look(r, e, System.nanoTime());
        ViewpointBridge.Eye at = look == null ? e : e.at((float) look[0], (float) look[1]);
        double[] p = GripIK.add(GripIK.add(GripIK.pos(head), r.eyeRel), worldToModel(at.aheadX, at.aheadY, at.aheadZ, a));
        double[] m = eyeFrame(p, lookModel(at.yaw, at.pitch, a));
        // the look the pose used (3, 4: FpViewLatch, FpRecoil's sway), the camera's (5, 6) and the free look's mode (7)
        r.eye = new double[] {e.x, e.y, e.z, at.yaw, at.pitch, e.yaw, e.pitch, r.freeLook.mode};
        double[] out = SightDrawFrame.eye(c, ap, m, at);
        if (FpJitterProbe.on) {
            FpJitterProbe.pose(e.number, a, at.yaw, at.pitch, m, out);
        }
        return out;
    }

    /** PZ world offset (x, y tiles, z levels) into the character's model space (inverse of Model.vectorToWorldCoords). */
    static double[] worldToModel(double dx, double dy, double dz, double angle) {
        double cs = Math.cos(angle), sn = Math.sin(angle);
        return new double[] {(-cs * dx - sn * dy) / 1.5, dz * ViewpointBridge.LEVEL / 1.5, (-sn * dx + cs * dy) / 1.5};
    }

    /** Viewpoint's view direction (yaw: world heading, pitch: up) in the model space of a character at this rendered angle. */
    static double[] lookModel(double yaw, double pitch, double angle) {
        double cp = Math.cos(pitch);
        return new double[] {-Math.cos(yaw - angle) * cp, Math.sin(pitch), Math.sin(yaw - angle) * cp};
    }

    /** model <- eye: columns +X = f x up (left), +Y up (no roll), +Z = -f; at p. */
    static double[] eyeFrame(double[] p, double[] f) {
        f = GripIK.normalize(f);
        double[] up = GripIK.sub(new double[] {0, 1, 0}, GripIK.scale(f, f[1]));
        up = GripIK.norm(up) < 1e-6 ? new double[] {0, 0, 1} : GripIK.normalize(up);
        double[] x = GripIK.cross(f, up);
        return new double[] {x[0], up[0], -f[0], p[0], x[1], up[1], -f[1], p[1], x[2], up[2], -f[2], p[2], 0, 0, 0, 1};
    }

    /**
     * W12 patrol: Tarkov's PATROL carries the weapon lowered across the upright torso (41-54 deg below level in eye space),
     * while PZ's spine stays upright at all camera pitches. Rotating that already-lowered pose around the eye by a downward
     * pitch sweeps the receiver and wrists backward through the ribcage and out behind the spine (-30..-85 deg). Leveling
     * the pose frame's pitch about the eye by weight keeps the patrol pose anchored to the character's horizontal heading
     * (matching 3P Patrol) while smoothly restoring full camera pitch for ADS, hip fire, non-patrol stances, and actions.
     */
    static double[] patrolEye(double[] eye, double weight) {
        double w = Math.min(1, Math.max(0, weight));
        if (w <= 0) {
            return eye;
        }
        double theta = Math.atan2(-eye[6], eye[5]);
        if (Math.abs(w * theta) <= 1e-12) {
            return eye;
        }
        double[] x = new double[] {eye[0], eye[4], eye[8]};
        if (GripIK.norm(x) < 1e-6) {
            return eye;
        }
        return GripIK.mulNew(GripIK.rigid(GripIK.normalize(x), -w * theta, GripIK.pos(eye)), eye);
    }

    // ------------------------------------------------------------------ poses in the eye frame

    /** Tarkov's aim time (seconds) by ergonomics and the assembly's weight (VP-W3-B1 section 7, skill 0). */
    static double adsSeconds(FpPoseData d, double ergonomics, double weight) {
        float[] a = d.aiming; // LightWeight, HeavyWeight, MinTimeLight, MinTimeHeavy, MaxTimeLight, MaxTimeHeavy
        double e = clamp(ergonomics / 100);
        double w = a[1] > a[0] ? clamp((weight - a[0]) / (a[1] - a[0])) : 0;
        double slow = a[4] + (a[5] - a[4]) * w, fast = a[2] + (a[3] - a[2]) * w;
        double q = e < 0.25 ? 0.25 + 3 * e * e : 2 * e - e * e;
        return Math.max(0.05, slow + (fast - slow) * q);
    }

    /**
     * own Prop1 -> reference Prop1: none for the family's own reference weapon (a launcher, the
     * AK-74N, ...), else GripIK's grip shift dR (the weapon's right wrist point where the
     * reference's was: Pn = P (x) T(dR)).
     */
    static double[] shift(FpPoseData.Family f, String weapon, double[] dR) {
        return f.weapon.equals(weapon) ? identity() : translation(dR);
    }

    /** Own low ready: the pose turned muzzle down about its grip (the Prop1 origin), lowered and drawn back a little. */
    static double[] lowReady(double[] q) {
        double[] out = GripIK.mulNew(GripIK.rigid(new double[] {1, 0, 0}, LOW_PITCH, GripIK.pos(q)), q);
        out[7] -= LOW_DROP;
        out[11] += LOW_BACK;
        return out;
    }

    /**
     * Obstacle retreat in the eye frame (TW/Data/WeaponObstacle classes): back along the view
     * (+Z) up to back by pullUntil, then the turn about the grip (positive: muzzle up) and the
     * lift; rho 0..1.
     */
    static double[] retreat(double[] q, double rho, double pullUntil, double back, double up, double pitch) {
        if (rho <= 0) {
            return q;
        }
        double b, p = 0, h = 0;
        if (pullUntil > 1e-6 && rho <= pullUntil) {
            b = back * rho / pullUntil;
        } else {
            double s = pullUntil >= 1 ? 1 : GripIK.smooth((rho - pullUntil) / (1 - pullUntil));
            b = back;
            p = pitch * s;
            h = up * s;
        }
        double[] out = Math.abs(p) > 1e-9 ? GripIK.mulNew(GripIK.rigid(new double[] {1, 0, 0}, p, GripIK.pos(q)), q) : q.clone();
        out[7] += h;
        out[11] += b;
        return out;
    }

    /** The ADS pose of a sight: the camera of the channel (own Prop1 <- camera, eye axes) onto the eye. */
    static double[] adsPose(double[] sight) {
        return GripIK.inverse(sight);
    }

    /**
     * Item 4: the own Prop1 points of a handgun's two hands. The pistol grip data's hands on the grip (Request.hgHands,
     * tools/pistol_grip.py) where they are; else the family's two source wrists mapped to this assembly the way
     * handContact maps them (the bare-hand path, for a handgun without its own grip data).
     */
    static double[][] pistolHands(FpPoseData.Family f, double[] shift, double[][] hgHands, boolean female) {
        double[][] out = new double[2][];
        for (int side = 0; side < 2; side++) {
            out[side] = hgHands != null ? GripIK.pos(hgHands[side])
                    : GripIK.pos(GripIK.mulNew(GripIK.inverse(shift), f.wrists[female ? 1 : 0][side]));
        }
        return out;
    }

    /**
     * Item 4 (PISTOL_ADS_HANDS): the sight frame of a handgun's ADS with the weapon slid ahead along its own line of
     * sight until the nearest hand is PISTOL_ADS_HANDS in front of the eye. Moving the sight's camera point back along
     * the sight's own axis by the depth the hands lack keeps the line of sight through the eye, the axis parallel to the
     * view and the iron/collimator picture exact - only the eye relief changes. The weapon's own sight points stay with
     * keepOut (FB5), and the sight line is never re-aimed. Returns `sight` itself when nothing is needed.
     */
    static double[] pistolSight(double[] sight, double[][] hands, double depth) {
        if (sight == null) {
            return null;
        }
        double[] inv = GripIK.inverse(sight);
        double need = 0;
        for (double[] p : hands) {
            need = Math.max(need, depth + GripIK.point(inv, p[0], p[1], p[2])[2]);
        }
        if (need <= 1e-9) {
            return sight;
        }
        need = Math.min(need, PISTOL_ADS_MAX);
        double[] out = sight.clone();
        out[3] += out[2] * need;
        out[7] += out[6] * need;
        out[11] += out[10] * need;
        return out;
    }


    /** Exact installed GL channel in the host Prop1 space, or no ADS if unproven. */
    static double[] launcherSight(FpPoseData data, String tpl, double[] mount) {
        return launcherSight(data, tpl, mount, null);
    }

    /** The same with the launcher channel's range move (TWSight, Tarkov SightingRange) of the part frame. */
    static double[] launcherSight(FpPoseData data, String tpl, double[] mount, double[] range) {
        FpPoseData.LauncherCamera camera = data.launcherCameras.get(tpl);
        if (camera == null || mount == null) {
            return null;
        }
        return GripIK.mulNew(mount, range == null ? camera.camera : GripIK.mulNew(range, camera.camera));
    }

    /** The reload pull after dt seconds (model units): the held one fallen by exp(-dt / RELOAD_RELEASE_S), plus what it
     *  lacks of the hands' need along the need (the hands stay on their targets) - a pull recomputed each frame fell
     *  7 cm and rose again within 0.35 s (GP-25 Load .7-.9). */
    static double[] reloadPull(double[] held, double[] need, double dt) {
        double[] out = held == null ? new double[3] : GripIK.scale(held, Math.exp(-Math.max(0, dt) / RELOAD_RELEASE_S));
        double n = GripIK.norm(need), lack = n > 1e-9 ? n - GripIK.dot(out, need) / n : 0;
        if (lack > 0) {
            out = GripIK.add(out, GripIK.scale(need, lack / n));
        }
        return GripIK.norm(out) < 1e-4 * CM ? new double[3] : out;
    }

    /** An underbarrel's own action clip plays (its host track, tools/fp_pose_runtime launcher windows): the support
     *  hand works at the launcher through it. */
    static boolean launcherAction(Request r) {
        return !r.launcher.isEmpty() && r.action != null && r.actionW > 1e-3 && (r.action.flags & FpPoseData.T_HOST) != 0;
    }

    /**
     * The support wrist of a deployed underbarrel on its mount (own Prop1 space, male/female), or null without data. Tarkov
     * holds an underbarrel by the launcher itself in its IDLE WEAPON pose (the left hand on the launcher body or grip under
     * the barrel); the host's handguard contact (Request.left) put the fingers over the GP-34 sight at 100/150 m and the
     * forearm on the M203 line at 350/400 m (wave 21.10.2026, item 10). The part frame, not the range move: the hand
     * holds the launcher body, the range turns the sight or the aim camera only.
     */
    static double[][] launcherHold(FpPoseData data, String tpl, double[] mount) {
        FpPoseData.LauncherCamera camera = data.launcherCameras.get(tpl);
        if (camera == null || camera.support == null || mount == null) {
            return null;
        }
        return new double[][] {GripIK.mulNew(mount, camera.support[0]), GripIK.mulNew(mount, camera.support[1])};
    }

    /** A 4x4 rigid move (orthonormal, right-handed rotation, finite), else null: no range then. */
    static double[] rigidMove(double[] m) {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double d = m[i * 4] * m[j * 4] + m[i * 4 + 1] * m[j * 4 + 1] + m[i * 4 + 2] * m[j * 4 + 2];
                if (Math.abs(d - (i == j ? 1 : 0)) > 1e-5) {
                    return null;
                }
            }
        }
        double det = m[0] * (m[5] * m[10] - m[6] * m[9]) - m[1] * (m[4] * m[10] - m[6] * m[8]) + m[2] * (m[4] * m[9] - m[5] * m[8]);
        return det > 0 ? m : null;
    }

    // ------------------------------------------------------------------ the near plane (FB5 items 5, 7)

    /** Viewpoint's near plane (0.05 scene units, 5.77 cm) in model units, and the margin kept beyond it. */
    static final double NEAR = 0.05 / 1.5, NEAR_KEEP = NEAR + 0.5 * CM;
    /** Points beside the view by more than this past the frustum cannot show their cut. */
    static final double NEAR_SIDE = 2 * CM;
    /**
     * The ADS weight from which the ADS pose's own allowance takes over (its eyecup round the eye); before it every
     * point stays beyond the plane.
     */
    static final double NEAR_ADS_FROM = 0.75;
    /** QA (FB5, A/B in one run): false lets the sight come through the near plane as before. */
    public static volatile boolean keepOut = true;

    /**
     * The points of the selected sight kept beyond the near plane: the eyepiece rim (8 points and its centre; the
     * scope picture is drawn on it and dropped for a frame once it is cut) and the corners of its part's box (the
     * eyecup and the housing behind the glass). The iron sight's own rear feature (W7: its notch/blade top, a disc
     * round it) when the channel is a built-in sight. Own Prop1 space; null without data.
     */
    static double[][] nearPoints(double[] ocular, double[] box, double[] iron) {
        java.util.List<double[]> points = new java.util.ArrayList<>();
        disc(points, ocular);
        if (box != null) {
            for (int k = 0; k < 8; k++) {
                points.add(new double[] {box[3 * k], box[3 * k + 1], box[3 * k + 2]});
            }
        }
        disc(points, iron);
        return points.isEmpty() ? null : points.toArray(new double[0][]);
    }

    /**
     * The sight's own aperture: the eyepiece rim (or W7 an iron sight's notch/blade top), 8 points and the centre. This
     * is what decides the ADS depth clamp (clampSafe): a ring round the line of sight that the plane cuts is a tunnel;
     * the part's box corners only hold air (an envelope) and are for keepOut's push alone.
     */
    static double[][] sightAperture(double[] ocular, double[] iron) {
        java.util.List<double[]> points = new java.util.ArrayList<>();
        disc(points, ocular);
        disc(points, iron);
        return points.isEmpty() ? null : points.toArray(new double[0][]);
    }

    /** A rim of 8 points and the centre of a disc {x,y,z,radius,nx,ny,nz} (an eyepiece, or W7 an iron sight's rear feature). */
    private static void disc(java.util.List<double[]> points, double[] ocular) {
        if (ocular == null || !(ocular[3] > 0)) {
            return;
        }
        double[] c = {ocular[0], ocular[1], ocular[2]}, n = new double[] {ocular[4], ocular[5], ocular[6]};
        if (GripIK.norm(n) > 1e-9) {
            n = GripIK.normalize(n);
            double[] a = GripIK.normalize(GripIK.cross(n, Math.abs(n[2]) < 0.9 ? new double[] {0, 0, 1} : new double[] {1, 0, 0}));
            double[] b = GripIK.cross(n, a);
            for (int k = 0; k < 8; k++) {
                double angle = k * Math.PI / 4;
                points.add(GripIK.add(c, GripIK.add(GripIK.scale(a, ocular[3] * Math.cos(angle)), GripIK.scale(b, ocular[3] * Math.sin(angle)))));
            }
            points.add(c);
        }
    }

    /**
     * How near each point may come in the ADS pose: the near plane with its margin, or its depth in that pose where
     * that is nearer (Tarkov's camera inside a rubber eyecup: the cup round the eye is cut there by design, FB2 item 8) -
     * so the ADS pose itself is never moved. keepOut takes it over only at the end of the way in. hard (W7: an iron
     * sight's own rear feature, which is the sight picture and has no eyecup allowance) keeps every point at the plane.
     */
    static double[] nearAllowed(double[][] points, double[] sight, boolean hard) {
        double[] allowed = new double[points.length];
        double[] ads = sight == null || hard ? null : adsPose(sight);
        for (int i = 0; i < points.length; i++) {
            double[] p = points[i];
            allowed[i] = ads == null ? NEAR_KEEP : Math.min(NEAR_KEEP, -GripIK.point(ads, p[0], p[1], p[2])[2]);
        }
        return allowed;
    }

    /**
     * FB5 items 5, 7 (20.10.2026: "firing through a thermal the picture vanishes, worst in automatic fire"; "a long scope
     * is cut entering the camera"): a kick back in ADS or the way of the raise brought the eyepiece and the scope's
     * rear through Viewpoint's near plane - the cut housing showed its inside and the scope picture, drawn on the
     * eyepiece, was dropped for that frame (ScopePip: "lens clipped"). The weapon is moved straight ahead along the view
     * by the most any of its points comes nearer than allowed; points beside the view (their cut is off screen) do not
     * count. The ADS alignment stays: the move is along the line of sight. On the way (ADS weight below NEAR_ADS_FROM)
     * every point stays beyond the plane - the eyecup and the tube of a long scope passing the eye off its axis were
     * cut open there (the user's Burris); the ADS pose's own allowance (an eyecup round the eye) blends in after it.
     */
    static double[] keepOut(Request r, double[] q, boolean aiming, double ads) {
        r.nearPushCm = 0;
        r.nearCutCm = 0;
        double need = nearNeed(r, q, aiming, ads);
        if (need <= 1e-7) {
            return q;
        }
        r.nearPushCm = need / CM;
        r.nearPushes++;
        double[] out = q.clone();
        out[11] -= need;
        return out;
    }

    /**
     * How far the weapon (q: Prop1 in the eye frame) has to go ahead along the view to keep the sight's points beyond
     * the near plane, model units (0: nothing, or the keep-out off). QA: r.nearCutCm - how deep the plane cuts where no
     * cut is meant, as drawn (keep-out off) or left after the push (on: none). The frame the game draws with: Viewpoint's
     * vertical field of view and the screen's aspect.
     */
    static double nearNeed(Request r, double[] q, boolean aiming, double ads) {
        double fov = ViewpointBridge.floatOf("viewpoint.core.View.frameFovY", 0f);
        Core core = Core.getInstance();
        double aspect = core == null ? 16. / 9 : (double) core.getScreenWidth() / Math.max(1, core.getScreenHeight());
        return nearNeed(r, q, aiming, ads, fov, aspect);
    }

    /** The same for a given vertical field of view (radians: 0 or out of range = 90 degrees) and aspect (offline checks). */
    static double nearNeed(Request r, double[] q, boolean aiming, double ads, double fov, double aspect) {
        // outside ADS the weapon's passes clamp their depth at the plane (FpViewLatch.clampOn): nothing is cut there
        if (!aiming || r.nearPoints == null || q == null) {
            return 0;
        }
        double w = GripIK.smooth((ads - NEAR_ADS_FROM) / (1 - NEAR_ADS_FROM));
        double tanV = fov > 0 && fov < Math.PI ? Math.tan(fov / 2) : 1;
        double tanH = tanV * aspect, need = 0, cut = 0;
        for (int i = 0; i < r.nearPoints.length; i++) {
            double[] p = r.nearPoints[i];
            double[] e = GripIK.point(q, p[0], p[1], p[2]);
            // beside the view where the plane cuts: a point nearer than the plane shows its cut at the plane's width
            double depth = -e[2], d = Math.max(NEAR, depth);
            if (Math.abs(e[0]) > d * tanH + NEAR_SIDE || Math.abs(e[1]) > d * tanV + NEAR_SIDE) {
                continue;
            }
            double allowed = NEAR_KEEP + (r.nearAllowed[i] - NEAR_KEEP) * w;
            need = Math.max(need, allowed - depth);
            cut = Math.max(cut, Math.min(NEAR, allowed) - depth);
        }
        if (!keepOut) {
            r.nearCutCm = Math.max(r.nearCutCm, Math.max(0, cut) / CM);
            return 0;
        }
        return need;
    }

    /**
     * Whether the ADS depth clamp may stay on for this request's sight (FpViewLatch.adsClampSafe asks it every frame on
     * the render thread): every kept-out point of the selected sight that could show its cut is at least at the near
     * plane in its ADS pose (nearAllowed), so nothing of it is a tunnel round the eye - or the sight is an iron/built-in
     * one without such data (W7: no eyepiece tube, its own rear feature kept by keepOut), where the clamp flattens the
     * weapon body behind the sight onto the plane instead of cutting it open. A point beside the widened frustum cannot
     * show its cut (the same allowance nearNeed makes: the part boxes hold air), and a point behind the eye is clipped
     * whole. Pure: the game passes Viewpoint's field of view and the screen's aspect.
     */
    static boolean clampSafe(Request r) {
        double fov = ViewpointBridge.floatOf("viewpoint.core.View.frameFovY", 0f);
        Core core = Core.getInstance();
        double aspect = core == null ? 16. / 9 : (double) core.getScreenWidth() / Math.max(1, core.getScreenHeight());
        return clampSafe(r, fov, aspect);
    }

    static boolean clampSafe(Request r, double fov, double aspect) {
        return clampSafe(r, null, fov, aspect);
    }

    /**
     * W9-adsnear (the user's item 4, 26.10.2026): the same rule on the pose being drawn (q, eye <- Prop1; null is the
     * steady ADS pose). A single decision taken at the ADS pose is wrong for the frames between: keepOut holds the
     * sight's own aperture at `NEAR_KEEP + (nearAllowed - NEAR_KEEP) * w` on the way in, so the aperture only reaches
     * the plane at the end - a sight whose eyepiece is nearer than it at its relief (an eyecup) must keep the plane
     * then, and the clamp must be on everywhere before, or the weapon body passing the eye is cut open.
     */
    static boolean clampSafe(Request r, double[] q, double fov, double aspect) {
        if (r == null) {
            return false;
        }
        if (r.ironSight) {
            // W7: an iron/built-in sight has no eyepiece round the eye, and its own notch is kept at the near plane by
            // keepOut (hard near points): whatever the body does behind it, the clamp flattens instead of cutting.
            return true;
        }
        double[][] sight = r.nearSight;
        if (sight == null || sight.length == 0) {
            return false;
        }
        double[] ads = q != null ? q : r.sight == null ? null : adsPose(r.sight);
        double tanV = fov > 0 && fov < Math.PI ? Math.tan(fov / 2) : 1, tanH = tanV * aspect;
        for (double[] p : sight) {
            if (ads == null) {
                continue;
            }
            double[] e = GripIK.point(ads, p[0], p[1], p[2]);
            double depth = -e[2], d = Math.max(NEAR, depth);
            // behind the eye: clipped whole. Beside the widened frustum: its cut cannot show (nearNeed's allowance).
            if (depth <= 0 || Math.abs(e[0]) > d * tanH + NEAR_SIDE || Math.abs(e[1]) > d * tanV + NEAR_SIDE) {
                continue;
            }
            if (depth < NEAR - 1e-9) {
                return false;
            }
        }
        return true;
    }

    /** W9-adsnear: the drawn pose (eye <- Prop1) of a request, or null before its first update / without a request. */
    static double[] drawnPose(Object c) {
        Request r = c == null ? null : requests.get(c);
        return r == null ? null : r.eyePose;
    }

    /** W9-adsnear: the decision for the pose this frame draws (Request.eyePose), or the ADS pose before the first one. */
    static boolean clampSafeDrawn(Request r) {
        return clampSafe(r, r == null ? null : r.eyePose);
    }

    /** The same with the drawn pose (eye <- Prop1; null: the steady ADS pose) and Viewpoint's own field of view. */
    static boolean clampSafe(Request r, double[] q) {
        double fov = ViewpointBridge.floatOf("viewpoint.core.View.frameFovY", 0f);
        Core core = Core.getInstance();
        double aspect = core == null ? 16. / 9 : (double) core.getScreenWidth() / Math.max(1, core.getScreenHeight());
        return clampSafe(r, q, fov, aspect);
    }

    /**
     * The user's report of 24.10.2026 ("the weapon is thrown sharply forward by the recoil", first person, any weapon).
     * The hip pose of a long weapon lies beyond the arms' reach and slides back along the view until the hands reach
     * (place(): about 9 cm for an M4A1). The native attack animation (the body's AttackRifle under the layer) brings the
     * shoulders forward for its 0.1-0.2 s and its blend-out, the slide out of reach fell with them (QA vprecoilkick, the real
     * hip trigger: 9.0 -> 1.9 cm) and the drawn weapon went 4.9 cm ahead and 6 cm back within 15 frames - over Tarkov's own
     * 2.3 cm kick back. The slide (out of reach, before the ADS and ready weights) is held at its value from the shot's start
     * while the attack plays and SLIDE_HOLD_S after it, then released over SLIDE_RELEASE_S: a weapon nearer the body than
     * needed stays in reach. The start's value is the frame before the attack's first: a shot during the aim press already
     * has the shoulders forward on that frame (vprecoilkickads: 0.83 -> 0.02 cm). QA: slideHold false - as before.
     */
    static final double SLIDE_HOLD_S = 0.25, SLIDE_RELEASE_S = 0.2;
    public static volatile boolean slideHold = true;

    static double slideHeld(Request r, double slide, boolean firing, long t0) {
        double dt = r.slideAt == 0 ? 0 : Math.max(0, Math.min(1, (t0 - r.slideAt) / 1e9));
        boolean recent = dt > 0 && dt < 0.25;
        r.slideAt = t0;
        double last = r.slideLast;
        r.slideLast = slide;
        if (!slideHold) {
            r.slideHold = 0;
            r.slideHoldCm = 0;
            return slide;
        }
        if (firing) {
            r.slideHold = Math.max(r.slideHold, recent ? Math.max(slide, last) : slide);
            r.slideFiredAt = t0;
        } else if ((t0 - r.slideFiredAt) / 1e9 > SLIDE_HOLD_S) {
            r.slideHold *= Math.exp(-dt / SLIDE_RELEASE_S);
            if (r.slideHold < 1e-5) {
                r.slideHold = 0;
            }
        }
        r.slideHoldCm = r.slideHold / CM;
        return Math.max(slide, r.slideHold);
    }

    /** A chest-height ready endpoint. Source wrist anchors account for each family's Prop1 datum. `wrist` is where the
     * family's right wrist point goes: the rifles' chest-height target, the pistol retreat's lower 15-degree one, or the
     * pistol High Ready's chin-height target (PISTOL_HIGH_WRIST). */
    static double[] highReady(FpPoseData.Family f, double[] shift, double pitch, double[] wrist) {
        double[] hip = GripIK.mulNew(f.idle, shift);
        double[] q = GripIK.mulNew(GripIK.rigid(new double[] {1, 0, 0}, pitch, GripIK.pos(hip)), hip);
        double[] anchor = GripIK.point(GripIK.inverse(shift), f.hands[3], f.hands[4], f.hands[5]);
        GripIK.setPos(q, GripIK.add(GripIK.pos(q), GripIK.sub(wrist, GripIK.point(q, anchor[0], anchor[1], anchor[2]))));
        return q;
    }

    /** The wrist target of the own high ready's endpoint: the pistol's (muzzle up or the retreat's muzzle down) or a
     * rifle's. The handgun flag of the request decides, never the sign of a pitch. */
    static double[] readyWrist(boolean handgun) {
        return handgun ? PISTOL_WRIST : HIGH_WRIST;
    }

    /**
     * W10 sprinthands: while running (runW > 0), long-forend shotguns, launchers and rifles pitched +62 degrees onto
     * HIGH_WRIST (-36 cm) lift the support hand above the eye line; lower HIGH_WRIST[1] only by the excess above
     * PISTOL_WRIST[1] (the existing lower 15-degree READY_CONE ray at -22 cm, matching ak74's reference support height).
     * Standing and walking (runW == 0) keep HIGH_WRIST bit-identically.
     */
    static double[] runReadyWrist(FpPoseData.Family f, double[] shift, Request r, double pitch, boolean female) {
        if (r.handgun) {
            return PISTOL_WRIST;
        }
        if (r.runW <= 0) {
            return HIGH_WRIST;
        }
        double[] end = highReady(f, shift, pitch, HIGH_WRIST);
        double maxHandY = Double.NEGATIVE_INFINITY;
        if (f.ready != null) {
            for (int side = 0; side < 2; side++) {
                maxHandY = Math.max(maxHandY, launcherWrist(f, shift, end, 0, side)[1]);
            }
        } else {
            for (int side = 0; side < 2; side++) {
                double[] local = f.wrists[female ? 1 : 0][side].clone();
                double[] support = side == 0 && r.launcherHold != null ? r.launcherHold[female ? 1 : 0] : r.left;
                if (side == 0 && support != null) {
                    local = support;
                } else if (side == 0 && r.handA != null) {
                    local = GripIK.mulNew(r.handA, local);
                } else {
                    local = GripIK.mulNew(GripIK.inverse(shift), local);
                }
                if (side == 1 && r.right != null) {
                    GripIK.setPos(local, onContact(f, female, side, local, r.right));
                }
                if (side == 0 && r.supportFrom != null && r.supportFade < 1) {
                    local = interp(r.supportFrom, local, GripIK.smooth(r.supportFade));
                }
                maxHandY = Math.max(maxHandY, GripIK.point(end, local[3], local[7], local[11])[1]);
            }
        }
        double excess = maxHandY - PISTOL_WRIST[1];
        if (excess <= 0) {
            return HIGH_WRIST;
        }
        return new double[] {HIGH_WRIST[0], HIGH_WRIST[1] - excess * r.runW, HIGH_WRIST[2]};
    }

    /** The own pistol High Ready: the hip pose turned muzzle up like the isometric carry clip, hands at the chin. */
    static double[] pistolHighReady(FpPoseData.Family f, double[] shift) {
        return highReady(f, shift, PISTOL_HIGH_PITCH, PISTOL_HIGH_WRIST);
    }

    /**
     * Item 1: the elbow goals of the pistol's own High Ready, in model space, by its weight. Tarkov's pistol bend goals
     * carried up with the grip from the hip pose to the chin put the support elbow out to the side and flattened its
     * forearm across the view (20-25 degrees at the chin, the arm solver's goal is a pole from the shoulder-hand
     * midpoint); the isometric carry clip keeps both elbows down and out (tools/center_handgun_carry.py ELBOW_POLE).
     * Each goal therefore goes below and outside its own hand: down along the spine, out along GripIK's chest left.
     */
    static double[] pistolHighGoals(double[] goals, double[][] model, Skeleton s, double[] P, double[][] hands, double weight) {
        if (weight <= 1e-3) {
            return goals;
        }
        double[] down = GripIK.normalize(GripIK.sub(GripIK.pos(model[s.chain[0]]), GripIK.pos(model[s.chain[3]])));
        double[] chest = model[s.chain[2]];
        double[] left = GripIK.normalize(new double[] {chest[1], chest[5], chest[9]});
        double[] out = goals.clone();
        for (int side = 0; side < 2; side++) {
            double[] dir = GripIK.normalize(GripIK.add(down, GripIK.scale(left, side == 0 ? .55 : -.55)));
            double[] hand = GripIK.point(P, hands[side][0], hands[side][1], hands[side][2]);
            for (int j = 0; j < 3; j++) {
                out[3 * side + j] += (hand[j] + dir[j] * 12 * CM - goals[3 * side + j]) * weight;
            }
        }
        return out;
    }

    /** The first-person ready pitch of a retreat: a handgun's (negative, muzzle down) is PISTOL_READY_PITCH without an
     * obstacle and the data's from the obstacle ratio where the retreat starts to turn (pullUntil) on; a rifle's
     * (muzzle up) is the data's. `handgun` is the request's class flag (Lua t.handgun), not the pitch's sign. */
    static double readyPitch(boolean handgun, double pitch, double obstacle, double pullUntil) {
        return !handgun || pitch >= 0 ? pitch : PISTOL_READY_PITCH + (pitch - PISTOL_READY_PITCH) * GripIK.smooth(obstacle / Math.max(1e-6, pullUntil));
    }


    /** FP pull-in then ready: both ends of each phase have zero velocity, one fixed endpoint.
     * The P1 additive lift is for third person; at the eye it put the grip on the near plane.
     */
    static double[] fpRetreat(FpPoseData.Family f, double[] shift, double[] q, double rho, double pull, double back, double pitch,
                              double[] wrist) {
        if (rho <= 0) {
            return q.clone();
        }
        double[] pulled = q.clone();
        double[] anchor = GripIK.point(GripIK.inverse(shift), f.hands[3], f.hands[4], f.hands[5]);
        double forward = -GripIK.point(q, anchor[0], anchor[1], anchor[2])[2];
        // Tarkov's rifle idle wrist is already close to the eye. A blind 14 cm pull
        // took it through the camera before the lift; preserve the original pose at
        // zero, and never pull a source contact closer than this near-plane margin.
        double allowedBack = Math.min(back, Math.max(0, forward - PULL_WRIST_NEAR));
        pulled[11] += allowedBack * GripIK.smooth(pull > 1e-6 ? rho / pull : 1);
        double turn = pull >= 1 ? 0 : GripIK.smooth((rho - pull) / (1 - pull));
        return turn <= 0 ? pulled : interp(pulled, highReady(f, shift, pitch, wrist), turn);
    }


    /** The carry stance in use: none while aiming or firing (a trigger press or a recent shot), else the requested one. */
    static int stanceCarry(boolean aiming, boolean firing, int carry) {
        return aiming || firing ? CARRY_IDLE : carry;
    }

    /** Held aim intent survives an action; ADS leaves and returns on the same continuous clock. */
    static double adsGoal(boolean aiming, boolean acting, double actionWeight) {
        return aiming && !acting && actionWeight <= 1e-3 ? 1 : 0;
    }

    static boolean utilitySwitch(String operation) {
        return operation != null && operation.matches("[a-z0-9_]+:Switch[013]");
    }

    static double adsGoal(boolean aiming, boolean acting, double actionWeight, boolean utilitySwitch) {
        return utilitySwitch && aiming ? 1 : adsGoal(aiming, acting, actionWeight);
    }

    /**
     * FB8 item 6 (user report of 22.10.2026, "increase the speed of entering the sight"): Tarkov's aim time T (the B1
     * formula, adsSeconds) is not the length of a ramp. ProceduralWeaponAnimation keeps aimingSpeed = 1 / T and moves the
     * camera to the sight by Lerp(current, sight, dt * aimingSpeed * CameraSmoothSteady) while aiming and by
     * Lerp(current, rest, dt * CameraSmoothOut) after (LerpCamera): an exponential approach at 8 / T per second, 95 % in
     * 3 T / 8 (0.36 s at T = 0.95 s), and a release at 6 per second. The ramp over the whole T entered 2.7 times slower.
     * The progress snaps to the goal within ADS_SNAP (consumers wait for a full aim).
     */
    static final double CAMERA_SMOOTH_STEADY = 8, CAMERA_SMOOTH_OUT = 6, ADS_SNAP = 2e-3;

    static double advanceAds(double ads, double goal, double seconds, double dt) {
        double rate = goal > ads ? CAMERA_SMOOTH_STEADY / Math.max(0.02, seconds) : CAMERA_SMOOTH_OUT;
        double next = goal + (ads - goal) * Math.exp(-rate * Math.max(0, dt));
        return Math.abs(goal - next) < ADS_SNAP ? goal : next;
    }

    /** A shoulder launcher's support wrist from its own source contact, in model space. */
    static double[] launcherSupport(FpPoseData.Family f, double[] shift, double[] P, double ads) {
        return launcherWrist(f, shift, P, ads, 0);
    }

    /** Both source contacts: a PZ carry wrist is not the shoulder launcher's grip datum. */
    static double[] launcherWrist(FpPoseData.Family f, double[] shift, double[] P, double ads, int side) {
        double[] h = f.hands, a = f.readyHands;
        double k = GripIK.smooth(ads);
        int o = side * 3;
        double[] contact = new double[] {h[o] + (a[o] - h[o]) * k, h[o + 1] + (a[o + 1] - h[o + 1]) * k,
                h[o + 2] + (a[o + 2] - h[o + 2]) * k};
        double[] local = GripIK.point(GripIK.inverse(shift), contact[0], contact[1], contact[2]);
        return GripIK.point(P, local[0], local[1], local[2]);
    }

    /**
     * The eye <- own Prop1 pose of a request before the obstacle/high-ready retreat (the
     * neutral pose) and after it, for offline checks and the layer: {neutral, final, goals (6)}.
     * hip = idle (x) grip shift; stance is the smoothed stance pose; ads the ADS progress (0..1);
     * action: a track at u with weight w (null: none); rho the retreat (obstacle or high ready);
     * handgun: the request's class flag, which picks the pistol's ready wrist target.
     */
    static double[][] compose(FpPoseData.Family f, double[] shift, double[] stance, double[] sight, double ads, double rho,
                              double pullUntil, double back, double up, double pitch, boolean handgun, FpPoseData.Track action,
                              double u, double w) {
        return compose(f, shift, stance, sight, ads, rho, pullUntil, back, up, pitch, handgun, action, u, w, false);
    }

    static double[][] compose(FpPoseData.Family f, double[] shift, double[] stance, double[] sight, double ads, double rho,
                              double pullUntil, double back, double up, double pitch, boolean handgun, FpPoseData.Track action,
                              double u, double w, boolean utilitySwitch) {
        return compose(f, shift, stance, sight, ads, rho, pullUntil, back, up, pitch, handgun, action, u, w, utilitySwitch,
                readyWrist(handgun));
    }

    static double[][] compose(FpPoseData.Family f, double[] shift, double[] stance, double[] sight, double ads, double rho,
                              double pullUntil, double back, double up, double pitch, boolean handgun, FpPoseData.Track action,
                              double u, double w, boolean utilitySwitch, double[] wrist) {
        double[] adsTarget = sight != null ? adsPose(sight) : f.ready != null ? GripIK.mulNew(f.ready.pose(0), shift) : null;
        double s = adsTarget == null ? 0 : GripIK.smooth(ads);
        double[] neutral = s > 0 ? interp(stance, adsTarget, s) : stance.clone();
        double fade = rho <= 0 ? 0 : pullUntil > 1e-6 ? GripIK.smooth(rho / pullUntil) : 1;
        double sEff = s * (1 - fade);
        double[] q = sEff > 0 ? interp(stance, adsTarget, sEff) : stance.clone();
        double[] goals = f.goals.clone();
        double[] source = f.idle;
        if (f.ready != null) {
            double[] readyGoals = f.ready.goals(0);
            if (readyGoals != null) {
                source = interp(f.idle, f.ready.pose(0), sEff);
                for (int i = 0; i < 6; i++) goals[i] += (readyGoals[i] - goals[i]) * sEff;
            }
        }
        goals = poseGoals(source, goals, GripIK.mulNew(q, GripIK.inverse(shift)));
        if (action != null && w > 1e-4) {
            boolean manual = (action.flags & FpPoseData.T_MANUAL) != 0;
            double[] act = manual ? GripIK.mulNew(GripIK.mulNew(f.idle, shift), action.pose(u))
                    : GripIK.mulNew(GripIK.mulNew(f.idle, action.pose(u)), shift);
            double[] placedAction = utilitySwitch ? GripIK.mulNew(GripIK.mulNew(q, GripIK.inverse(shift)), GripIK.mulNew(action.pose(u), shift)) : act;
            double[] neutralAction = utilitySwitch ? GripIK.mulNew(GripIK.mulNew(neutral, GripIK.inverse(shift)), GripIK.mulNew(action.pose(u), shift)) : act;
            q = interp(q, placedAction, w);
            neutral = interp(neutral, neutralAction, w);
            if (!utilitySwitch) rho = rho + (Math.min(rho, pullUntil) - rho) * w; // ordinary actions: the pull back only
            double[] g = action.goals(u);
            if (g != null) {
                if (manual) {
                    double[] gl = GripIK.point(act, g[0], g[1], g[2]), gr = GripIK.point(act, g[3], g[4], g[5]);
                    g = new double[] {gl[0], gl[1], gl[2], gr[0], gr[1], gr[2]};
                } else if (utilitySwitch) {
                    g = poseGoals(act, g, placedAction);
                }
                for (int i = 0; i < 6; i++) {
                    goals[i] += (g[i] - goals[i]) * w;
                }
            }
        }
        double[] placed = fpRetreat(f, shift, q, rho, pullUntil, back, pitch, wrist);
        goals = gripGoals(f, shift, q, goals, placed);
        return new double[][] {neutral, placed, goals, {sEff}};
    }

    /**
     * The own ready turn (retreat/high ready, not a Tarkov clip) carries the bend goals with the grip
     * only: turned with the weapon's 62-65 degree pitch they swung ahead of the face and raised the
     * elbows across the view; Tarkov's goals stay below and beside the body as in its clips.
     */
    static double[] gripGoals(FpPoseData.Family f, double[] shift, double[] from, double[] goals, double[] to) {
        double[] a = GripIK.point(GripIK.inverse(shift), f.hands[3], f.hands[4], f.hands[5]);
        double[] d = GripIK.sub(GripIK.point(to, a[0], a[1], a[2]), GripIK.point(from, a[0], a[1], a[2]));
        double[] out = goals.clone();
        for (int i = 0; i < 6; i++) {
            out[i] += d[i % 3];
        }
        return out;
    }

    /** Bend_Goal points follow the same weapon displacement as their source pose. */
    static double[] poseGoals(double[] source, double[] goals, double[] placed) {
        double[] move = GripIK.mulNew(placed, GripIK.inverse(source)), out = new double[6];
        for (int side = 0; side < 2; side++) {
            int i = side * 3;
            double[] p = GripIK.point(move, goals[i], goals[i + 1], goals[i + 2]);
            System.arraycopy(p, 0, out, i, 3);
        }
        return out;
    }

    /**
     * W10 torchpistol (item 29) & W11 torchfp (item 9):
     * Third-person Harries flashlight hold extracted from vanilla Bob_IdleAimHandgun + Bob_IdleAimHgun_Torch /
     * Bob_IdleAimHgun_AngleTorch (identical across Male and Female skeletons):
     * - Left wrist is crossed right under the right gun wrist in Prop1 space:
     *   HARRIES_HR_TO_HL_P1 = P1^-1 * (pos(LH) - pos(RH)) = (+2.465, +4.891, +4.634) cm (|LH - RH| = 7.172 cm).
     * - Left wrist rotation in Prop1 space (with a 2.25 deg / 2.08 deg pitch alignment so the torch lens axis
     *   +Y_Prop2 / -Z_Prop2 shines directly along the firearm bore +Y_Prop1) and fixed Harries hand-to-Prop2
     *   grip transform LH^-1 * Prop2 (independent of vanilla's non-aim Bob_IdleFlashLight waist-hang mask).
     */
    static final double[] HARRIES_HR_TO_HL_P1 = {2.4650 * CM, 4.8910 * CM, 4.6340 * CM};
    static final double[] HARRIES_P1_TO_LH_TORCH = {
         0.73016477, -0.28920439,  0.61904784,
         0.15842532,  0.95297401,  0.25834463,
        -0.66465091, -0.09056129,  0.74164535
    };
    static final double[] HARRIES_P1_TO_LH_ANGLE = {
         0.20330638, -0.61289891,  0.76355841,
         0.86466343,  0.47826922,  0.15367403,
        -0.45937313,  0.62897812,  0.62718646
    };
    static final double[] HARRIES_TLHP2_TORCH = {
        -0.82042628,  0.15842532,  0.54936522,  0.05401748,
         0.27263226,  0.95297401,  0.13233358, -0.02157766,
        -0.50256579,  0.25834463, -0.82503678,  0.02485396,
         0.0,         0.0,         0.0,         1.0
    };
    static final double[] HARRIES_TLHP2_ANGLE = {
         0.01884381,  0.50199808, -0.86466343,  0.03066680,
         0.27481432, -0.83410769, -0.47826922,  0.02016864,
        -0.96131264, -0.22860948, -0.15367403,  0.03055602,
         0.0,         0.0,         0.0,         1.0
    };
    static final double[] HARRIES_LF0_TORCH = {
         0.40416888,  0.89248168, -0.20030968,
        -0.89127246,  0.43349460,  0.13310088,
         0.20562326,  0.12473527,  0.97064936
    };
    static final double[] HARRIES_LF0_ANGLE = {
        -0.17914458,  0.96321500, -0.20030995,
        -0.97900173, -0.15440098,  0.13310129,
         0.09727711,  0.21994816,  0.97064925
    };
    static final double[] HARRIES_LF1 = {
         0.01381114, -0.02841271, -0.99950086,
        -0.17662199,  0.98380896, -0.03040720,
         0.98418185,  0.17695379,  0.00856921
    };
    static final double[] HARRIES_ELBOW_BIAS = {6 * CM, -8 * CM, 2 * CM};
    // Matched daylight FP/TP ADS: TP eye relief is about 2 cm longer; moving along the view keeps the sight line exact.
    static final double HARRIES_FP_VIEW_FORWARD = 2 * CM;
    // Legacy W10 lower-left corner constants retained for QA A/B comparison (qaLegacyTorchFp)
    static final double[] PROP2_TORCH_EYE = {1, 0, 0, 0, 0, 0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1};
    static final double[] PROP2_ANGLE_EYE = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    static final double[] TORCH_EYE_POS = {15 * CM, -15 * CM, -24 * CM};
    static final double[] TORCH_TOP_SHIFT = {1 * CM, -4 * CM, -4 * CM};
    static final double[] TORCH_ELBOW_BIAS = {6 * CM, -4 * CM, 2 * CM};

    /** Secondary-hand item distinct from the primary firearm, or null (empty offhand or 2H firearm). */
    static zombie.inventory.InventoryItem offhandItem(IsoGameCharacter c, Request r) {
        if (c == null) {
            return null;
        }
        zombie.inventory.InventoryItem sec = c.getSecondaryHandItem();
        return sec != null && sec != c.getPrimaryHandItem() && sec != r.item ? sec : null;
    }

    /** Secondary animation mask name of an item (ItemReplacement or ScriptItem), or null. */
    static String secondaryMaskValue(zombie.inventory.InventoryItem sec) {
        if (sec == null) {
            return null;
        }
        zombie.scripting.objects.ItemReplacement rep = sec.getItemReplacementSecondHand();
        if (rep != null && rep.maskVariableValue != null && !rep.maskVariableValue.isEmpty()) {
            return rep.maskVariableValue;
        }
        return sec.getScriptItem() != null ? sec.getScriptItem().secondaryAnimMask : null;
    }

    /** Whether a secondary item is a directional handheld flashlight (cone torch or torch/flashlight anim mask). */
    static boolean isConeTorch(zombie.inventory.InventoryItem sec) {
        if (sec == null) {
            return false;
        }
        if (sec.isTorchCone()) {
            return true;
        }
        String mask = secondaryMaskValue(sec);
        if (mask == null || mask.isEmpty()) {
            return false;
        }
        String m = mask.toLowerCase(java.util.Locale.ROOT);
        return m.contains("torch") || m.contains("flashlight");
    }

    static boolean isAngleTorchMask(String mask) {
        return mask != null && mask.toLowerCase(java.util.Locale.ROOT).contains("angle");
    }

    static double[] harriesTLhP2(String mask) {
        return (isAngleTorchMask(mask) ? HARRIES_TLHP2_ANGLE : HARRIES_TLHP2_TORCH).clone();
    }

    static double axialLensLength(zombie.inventory.InventoryItem sec) {
        String type = sec == null ? "" : sec.getFullType();
        if ("Base.Torch".equals(type)) return 12.6685 * CM; // FlashLight.x: lens at Z=-12.6685 cm, mapped to +Y_Prop2.
        if ("Base.PenLight".equals(type)) return 7.3889 * CM; // PenLight_Hand.x: lens at Y=+7.3889 cm.
        return 11.9899 * CM; // HandTorch.x: lens at Y=+11.9899 cm.
    }

    static double[][] harriesFingers(String mask) {
        return new double[][] {isAngleTorchMask(mask) ? HARRIES_LF0_ANGLE : HARRIES_LF0_TORCH, HARRIES_LF1};
    }

    /**
     * Left-hand target {HL (16), elbowGoalModel (3)} in model space placing the flashlight hand in the
     * third-person Harries hold crossed right under the gun hand (pos(HR) + rot(P, HARRIES_HR_TO_HL_P1))
     * with the light beam aligned with the firearm bore (+Y_P).
     */
    static double[][] harriesOffhandPose(double[] P, double[] HR, double l2, String mask) {
        boolean angle = isAngleTorchMask(mask);
        double[] p1ToLh = angle ? HARRIES_P1_TO_LH_ANGLE : HARRIES_P1_TO_LH_TORCH;
        double[] pRot = GripIK.unitRotation(P);
        double[] lhRot = GripIK.mul3(pRot, p1ToLh);
        double[] hrPos = GripIK.pos(HR);
        double[] dHl = GripIK.rot(P, HARRIES_HR_TO_HL_P1);
        double[] hlPos = {hrPos[0] + dHl[0], hrPos[1] + dHl[1], hrPos[2] + dHl[2]};
        double[] HL = {
            lhRot[0], lhRot[1], lhRot[2], hlPos[0],
            lhRot[3], lhRot[4], lhRot[5], hlPos[1],
            lhRot[6], lhRot[7], lhRot[8], hlPos[2],
            0, 0, 0, 1
        };
        double[] biasModel = GripIK.rot(P, new double[] {HARRIES_ELBOW_BIAS[0], -HARRIES_ELBOW_BIAS[2], -HARRIES_ELBOW_BIAS[1]});
        double[] goalModel = {
            hlPos[0] - l2 * lhRot[0] + biasModel[0],
            hlPos[1] - l2 * lhRot[3] + biasModel[1],
            hlPos[2] - l2 * lhRot[6] + biasModel[2]
        };
        return new double[][] {HL, goalModel};
    }

    /** Legacy W10 lower-left corner target retained for QA A/B comparison (qaLegacyTorchFp). */
    static double[][] offhandLightTarget(double[] eye, double[] tLhP2, double l2, String mask) {
        boolean angle = isAngleTorchMask(mask);
        double[] p2Eye = (angle ? PROP2_ANGLE_EYE : PROP2_TORCH_EYE).clone();
        double[] invT = GripIK.inverse(tLhP2);
        double[] lhRotEye = GripIK.mulNew(p2Eye, invT);
        double topW = GripIK.smooth((-lhRotEye[4] - 0.1) / 0.6);
        p2Eye[3] = TORCH_EYE_POS[0] + TORCH_TOP_SHIFT[0] * topW;
        p2Eye[7] = TORCH_EYE_POS[1] + TORCH_TOP_SHIFT[1] * topW;
        p2Eye[11] = TORCH_EYE_POS[2] + TORCH_TOP_SHIFT[2] * topW;
        double[] lhEye = GripIK.mulNew(p2Eye, invT);
        double[] goalEye = {
                lhEye[3] - l2 * lhEye[0] + TORCH_ELBOW_BIAS[0],
                lhEye[7] - l2 * lhEye[4] + TORCH_ELBOW_BIAS[1],
                lhEye[11] - l2 * lhEye[8] + TORCH_ELBOW_BIAS[2]
        };
        return new double[][] {GripIK.mulNew(eye, lhEye), GripIK.point(eye, goalEye[0], goalEye[1], goalEye[2])};
    }

    static void firstPerson(AnimationPlayer ap, IsoGameCharacter c, Request r, Skeleton s, double[][] model, FpPoseData d,
                            FpPoseData.Family f, double[] eye, double dt, long t0) {
        r.eyeModel = eye;
        boolean fresh = dt < 0 || r.stance == null;
        double step = fresh ? 0 : dt;
        double[] shift = shift(f, r.weapon, r.dR);
        if (!r.launcher.isEmpty()) {
            r.sight = launcherSight(d, r.launcher, r.mount, r.launcherRange);
            r.launcherHold = launcherHold(d, r.launcher, r.mount);
        }
        // item 4: a handgun's ADS keeps its hands PISTOL_ADS_HANDS in front of the eye by sliding the weapon ahead along
        // its own line of sight (the sight's camera point back along its axis, so the sight line and picture stay exact)
        r.adsReliefCm = 0;
        double[] sight = r.sight;
        if (r.handgun && r.sight != null) {
            double[][] pHands = pistolHands(f, shift, r.hgHands, c.isFemale());
            if (offhandItem(c, r) != null && (r.utilitySwitch || r.actionW < 0.5)) {
                pHands = new double[][] {pHands[1]};
            }
            sight = pistolSight(r.sight, pHands, PISTOL_ADS_HANDS);
            if (sight != r.sight) {
                r.adsReliefCm = GripIK.dist(GripIK.pos(sight), GripIK.pos(r.sight)) / CM;
            }
        }
        r.supportFade = Math.min(1, r.supportFade + step / HOLD_SWITCH_S);
        double[] hip = GripIK.mulNew(f.idle, shift);
        // A trigger press or a shot leaves any carry stance at once (VP-W7-R): the drawn bore is
        // the hip pose's, along the view, before the shot reads it; no patrol-out clip then.
        boolean firing = FpRecoil.firing(c, t0);
        // The hip trigger (VP-W12-H3) leaves a carry stance too, but on Tarkov's time: the weapon
        // reaches the hip pose first, then Lua fires (FIRE only from IDLE in Tarkov's controllers).
        boolean sprinting = c.isSprinting(), running = c.isRunning() || sprinting;
        Hip hd = hip(f, r);
        // W10 sprinthands: Tarkov enters Hands.PATROL on SetSprint(true) and clears the trigger (EFT.Player:12505,12509).
        boolean held = r.trigger && freshFrame(r.triggerFrame, FpRecoil.worldFrame()) && !r.aiming && !r.spent && !sprinting;
        boolean fires = hipFires(f, r);
        // an aim-only weapon (RSHG-2), a running action or its clip still blending out: the trigger
        // changes nothing; on the layer's first frame it waits a frame (its transition starts from the drawn stance)
        boolean trigger = held && fires && hd != null && !r.acting && r.actionW <= 1e-3 && !fresh;
        if (trigger && !r.triggerOn && !firing) {
            startReady(r, hd, t0);
        }
        r.triggerOn = trigger;
        if (!trigger) {
            r.readyFrom = -1;
        }
        double readyT = (t0 - r.readyAt) / 1e9;
        boolean raising = r.readyFrom >= 0 && readyT < r.readySeconds;
        int carry = stanceCarry(r.aiming, firing || trigger, r.carry);
        if (!r.spent && sprinting && !r.aiming && !firing) {
            carry = CARRY_PATROL;
        }
        if (carry != r.lastCarry) {
            r.prevCarry = r.lastCarry;
            r.lastCarry = carry;
            r.carryAt = t0;
        }
        if (firing || r.readyFrom >= 0 && !raising) {
            r.prevCarry = CARRY_IDLE; // a shot, or Tarkov's PATROL OUT already played to its IDLE
        }
        double since = (t0 - r.carryAt) / 1e9;
        double[] target;
        FpPoseData.Track patrolTrack = null;
        double patrolU = 0;
        String stance;
        double inSeconds = hd != null && hd.patrolIn > 0 ? hd.patrolIn : f.patrolIn == null ? 0 : f.patrolIn.seconds;
        double outSeconds = hd != null && hd.patrolOut > 0 ? hd.patrolOut : f.patrolOut == null ? 0 : f.patrolOut.seconds;
        double patrolGoal = 0;
        if (raising && r.readyFrom == CARRY_PATROL && f.patrolOut != null) {
            target = patrolReady(f, shift, hd, r.readyPose, hip, readyT);
            patrolTrack = f.patrolOut; patrolU = Math.min(1, readyT / outSeconds);
            stance = "patrolOut hip";
        } else if (raising) {
            // own low ready: back to the hip pose; own high ready: the hip stance with the retreat weight below
            target = interp(r.readyPose, hip, GripIK.smooth(readyT / r.readySeconds));
            stance = (r.readyFrom == CARRY_HIGH ? "high" : "low") + " hip";
        } else if (carry == CARRY_PATROL && f.patrol != null) {
            if (f.patrolIn != null && since < inSeconds) {
                target = GripIK.mulNew(f.patrolIn.pose(since / inSeconds), shift);
                patrolTrack = f.patrolIn; patrolU = since / inSeconds;
                patrolGoal = GripIK.smooth(patrolU / 0.5);
                stance = "patrolIn";
            } else {
                target = GripIK.mulNew(f.patrol.pose(t0 / 1e9 / Math.max(0.05, f.patrol.seconds)), shift);
                patrolTrack = f.patrol; patrolU = t0 / 1e9 / Math.max(0.05, f.patrol.seconds);
                patrolGoal = 1;
                stance = "patrol";
            }
        } else if (r.prevCarry == CARRY_PATROL && f.patrolOut != null && since < outSeconds) {
            target = GripIK.mulNew(f.patrolOut.pose(since / outSeconds), shift);
            patrolTrack = f.patrolOut; patrolU = since / outSeconds;
            patrolGoal = 1 - GripIK.smooth((patrolU - 0.5) / 0.5);
            stance = "patrolOut";
        } else if (carry == CARRY_LOW || carry == CARRY_PATROL) {
            // FB4: Patrol of a family without Tarkov's patrol clips (a deployed underbarrel) is its own
            // low ready, never the hip pose.
            target = lowReady(hip);
            patrolGoal = carry == CARRY_PATROL ? 1 : 0;
            stance = "low";
        } else if (carry == CARRY_HIGH && r.handgun) {
            // item 1 (user 24.10.2026): the pistol's own High Ready, a stance like the own low ready - muzzle up as in
            // the isometric carry clip, hands at the chin. The wall retreat keeps TW/Data/WeaponObstacle's pitch.
            target = pistolHighReady(f, shift);
            stance = "high";
        } else {
            target = hip;
            stance = carry == CARRY_HIGH ? "high" : r.aiming ? "aim" : trigger ? "hip" : "idle";
        }
        // a shot snaps (Q6); the hip transition follows its timeline exactly to the hip pose (the shot waits for its end)
        double k = fresh || firing || r.readyFrom >= 0 ? 1 : 1 - Math.exp(-step / STANCE_TAU_S);
        r.stance = k >= 1 ? target : interp(r.stance, target, k);
        double[][] sourceHands = patrolTrack == null ? null : patrolTrack.hands(patrolU);
        if (r.handDelta == null) r.handDelta = new double[][] {identity(), identity()};
        for (int side = 0; side < 2; side++) {
            r.handDelta[side] = interp(r.handDelta[side], sourceHands == null ? identity() : sourceHands[side], k);
        }
        // Finish the authored Patrol exit before layering the rifle's procedural High Ready.
        // Driving both at once rotates the weapon twice while the support wrist travels around
        // its contact; looking up amplified that combined step into a hand/weapon snap.
        double highGoal = carry == CARRY_HIGH && (r.handgun || patrolTrack == null || patrolTrack != f.patrolOut) ? 1 : 0;
        r.highW = raising ? (r.readyFrom == CARRY_HIGH ? r.readyHigh * (1 - GripIK.smooth(readyT / r.readySeconds)) : 0)
                : k >= 1 ? highGoal : r.highW + (highGoal - r.highW) * k;
        r.patrolW = raising ? r.readyPatrol * (r.readyFrom == CARRY_PATROL && f.patrolOut != null
                ? 1 - GripIK.smooth((readyT / r.readySeconds - 0.5) / 0.5) : 1 - GripIK.smooth(readyT / r.readySeconds))
                : k >= 1 ? patrolGoal : r.patrolW + (patrolGoal - r.patrolW) * k;
        if (Math.abs(r.patrolW - patrolGoal) < 1e-6 && !raising) {
            r.patrolW = patrolGoal;
        }
        double runTarget = running ? 1 : 0;
        r.runW = k >= 1 ? runTarget : r.runW + (runTarget - r.runW) * k;
        boolean canAds = r.sight != null || f.ready != null;
        // the action clip the engine plays now (heaviest), its fraction and weight
        double[] act = action(ap, d);
        FpPoseData.Track track = act == null ? null : d.tracks.get((int) act[0]);
        if (act == null) {
            // FB4-A: the fire-mode gesture (no engine track) - its Tarkov track by the gesture's own clock
            track = gestureTrack(d, r, step);
            act = track == null ? null : new double[] {-1, track.frames == 1 ? 0 : r.gestureU, 1};
        }
        if (act == null && manualEye) {
            ManualHit mh = manualAction(ap, c, r);
            if (mh != null) {
                track = mh.track;
                act = new double[] {-1, mh.u, mh.w};
            }
        }
        double wTarget = act == null ? 0 : act[2];
        double ka = fresh ? 1 : 1 - Math.exp(-step / ACTION_TAU_S);
        r.actionW = fresh ? wTarget : r.actionW + (wTarget - r.actionW) * ka;
        if (track != null) {
            r.action = track;
            r.actionU = act[1];
            actions++;
        }
        // Retain the selector's ownership through its blended-out tail, but
        // release it immediately to another timed action. All other actions
        // keep the established ADS fade and re-entry.
        r.utilitySwitch = utilitySwitch(c.getVariableString("TWWeaponUtility"))
                || r.utilitySwitch && !r.acting && r.actionW > 1e-3
                && (track == null || track.name.contains("_firemode_") && !track.name.contains("check"));
        double goal = adsGoal(r.aiming && canAds, r.acting, r.actionW, r.utilitySwitch);
        // FB4-A items 13, 18: the reach corrections follow the action over ACTING_TAU_S instead of its flag; the
        // fire-mode switch keeps them (Tarkov's FIREMODE leaves the support hand on the weapon)
        double actingGoal = (r.actionW > 0.5 || r.acting) && !r.utilitySwitch ? 1 : 0;
        r.actingW = fresh ? actingGoal : r.actingW + (actingGoal - r.actingW) * (1 - Math.exp(-step / ACTING_TAU_S));
        if (!actingRamp) {
            r.actingW = r.actionW > 0.5 || r.acting ? 1 : 0;
        }
        // Tarkov's aim time of the whole loaded assembly (TarkovHandling through FpRecoil), else the formula here
        double adsTime = FpRecoil.adsSeconds(c, r, adsSeconds(d, r.ergonomics, r.weight)) * adsTimeScale;
        r.adsTime = adsTime;
        r.ads = fresh ? goal : advanceAds(r.ads, goal, adsTime, step);
        // another timed action (none of our action clips plays) or a state of the body's own arms: the clips' pose;
        // a Tarkov weapon utility (fire-mode switch, inspect, chamber check: TWWeaponUtility) without its own first-person
        // track keeps the layer - the weapon stays at the eye/hip instead of popping to the body clip (FB2, 18.10.2026)
        String utility = c.getVariableString("TWWeaponUtility");
        boolean utilityHold = utility != null && !utility.isEmpty() && track == null;
        boolean manualHold = false;
        if (manualEye && r.acting && track == null && !utilityHold) {
            String ma = c.getVariableString("TWManualAction"), rk = c.getVariableString("TWRoundKind");
            if ((ma != null && !ma.isEmpty() || rk != null && !rk.isEmpty() || c.getVariableBoolean("TWFpActionHold"))
                    && r.actionHoldS < 0.35) {
                r.actionHoldS += Math.max(0, step);
                manualHold = true;
            }
        } else {
            r.actionHoldS = 0;
        }
        double fpTarget = r.acting && track == null && !utilityHold && !manualHold || bodyState(c) ? 0 : 1;
        r.fpW = fresh && r.fpW <= 0 ? fpTarget : r.fpW + (fpTarget - r.fpW) * (1 - Math.exp(-step / FADE_TAU_S));
        // the hip trigger's answer for Lua; an action (reload, check, drop, rack) interrupts its transition
        boolean acted = r.acting || r.actionW > 1e-3;
        if (held && acted) {
            if (r.readyFrom >= 0) {
                hipInterrupted++;
            }
            r.readyFrom = -1;
            r.triggerOn = false;
        }
        String answer = !held ? (r.aiming ? "off:aiming" : r.spent ? "off:spent" : sprinting ? "off:sprint" : "off:released")
                : hd == null ? "off:data" : !fires ? "off:aim-only" : acted ? "off:action" : r.fpW < 0.999 ? "off:layer"
                : raising || !atHip(r.stance, hip, Math.max(r.highW, r.patrolW)) ? "raising" : "ready";
        if (answer.equals("ready") && !r.hip.equals("ready")) {
            hipReady++;
        }
        r.hip = answer;
        // item 1: the pistol's own High Ready is a stance now (muzzle up), so the handgun's High Ready weight no longer
        // reuses the full wall-retreat pose; a handgun's rho is the obstacle alone, a rifle's keeps the High Ready.
        // Tarkov's TurnAwayEffector turns sideways across the chest (which Hands.PATROL already is), never +62 deg muzzle-up:
        // cap a sprinting obstacle to the pull-back phase (pullUntil) so walls do not pitch the sprint pose above the eye.
        double obs = sprinting ? Math.min(r.obstacleNow, r.pullUntil) : r.obstacleNow;
        double rho = r.handgun ? obs : Math.max(obs, r.highW);
        double rPitch = readyPitch(r.handgun, r.pitch, obs, r.pullUntil);
        double[][] out = compose(f, shift, r.stance, sight, r.ads, rho, r.pullUntil, r.back, r.up, rPitch,
                r.handgun, r.actionW > 1e-3 ? r.action : null, r.actionU, r.actionW, r.utilitySwitch,
                runReadyWrist(f, shift, r, rPitch, c.isFemale()));
        // the patrol clip's elbow goals, faded out over the first GOALS_FADE of the ADS and action weights (natural arms,
        // user report 23.10.2026: switched where the weight passed 0.001, the support elbow and its plane jumped in one
        // frame - the wrist's twist by 96 degrees leaving ADS into Patrol, the elbow's clearance turn by 45 entering it)
        double patrolGoals = patrolTrack == null ? 0 : (1 - GripIK.smooth(out[3][0] / GOALS_FADE)) * (1 - GripIK.smooth(r.actionW / GOALS_FADE));
        if (patrolGoals > 0) {
            double[] own = poseGoals(patrolTrack.pose(patrolU), patrolTrack.goals(patrolU), GripIK.mulNew(out[1], GripIK.inverse(shift)));
            for (int i = 0; i < 6; i++) {
                out[2][i] += (own[i] - out[2][i]) * patrolGoals;
            }
        }
        double[] q = out[1];
        if (r.handoff != null) {
            // First applied frame is exactly the preceding tube pose; subsequent
            // frames release continuously to spent low / the replacement's pose.
            r.handoffW *= Math.exp(-step / STANCE_TAU_S);
            q = handoffPose(r, q);
            out[0] = handoffPose(r, out[0]);
            if (r.handoffW < 1e-4) {
                r.handoff = null;
            }
        }
        // Tarkov recoil, sway and breath of this frame (FpRecoil); the neutral frame stays without them
        double[] layered = q;
        q = FpRecoil.pose(ap, c, r, q, out[3][0], adsTime, t0);
        r.recoilShift = q == layered ? null : GripIK.mulNew(GripIK.inverse(layered), q);
        double qaOff = qaPatrolPitch || c != null && c.getVariableBoolean("TWQaPatrolPitch") ? 0 : 1;
        double wPatrol = qaOff * r.patrolW * (1 - GripIK.smooth(r.highW)) * (1 - out[3][0]) * (1 - GripIK.smooth(r.actionW)) * (1 - GripIK.smooth(r.handoffW));
        double[] poseEye = patrolEye(eye, wPatrol);
        r.poseEyeModel = poseEye == eye ? null : poseEye;
        r.neutral = GripIK.mulNew(poseEye, out[0]);
        r.neutralAt = t0;
        r.neutralFrame = FpRecoil.worldFrame();
        // FB5 items 5, 7: the sight kept beyond the near plane on the way into ADS and in its kick back (after the
        // recoil's own shift: that one stays the recoil alone)
        double[] P;
        if (poseEye == eye) {
            q = keepOut(r, q, r.aiming || out[3][0] > 1e-3, out[3][0]);
            P = GripIK.mulNew(eye, q);
        } else {
            P = GripIK.mulNew(poseEye, q);
            double[] qCam = GripIK.mulNew(GripIK.inverse(eye), P);
            double[] qSafe = keepOut(r, qCam, r.aiming || out[3][0] > 1e-3, out[3][0]);
            if (qSafe != qCam) {
                P = GripIK.mulNew(eye, qSafe);
            }
        }
        double[] forward = GripIK.normalize(new double[] {-poseEye[2], -poseEye[6], -poseEye[10]});
        P = chestClear(model, s, P, r.neutral, r, forward, rho, c.isFemale() ? GripIK.TORSO_F : GripIK.TORSO_M);
        if (r.fpW < 1) {
            P = interp(model[s.prop], P, r.fpW);
            r.neutral = interp(model[s.prop], r.neutral, r.fpW);
        }
        double[] g = out[2];
        double[] goals = new double[6];
        for (int i = 0; i < 2; i++) {
            double[] p = GripIK.point(poseEye, g[3 * i], g[3 * i + 1], g[3 * i + 2]);
            System.arraycopy(p, 0, goals, 3 * i, 3);
        }
        // item 1: the elbows of the pistol's own High Ready. Tarkov's pistol bend goals, carried up with the grip from the
        // hip pose to the chin, put the support elbow out to the side and flattened its forearm across the view (20-25
        // degrees at the chin); the isometric carry clip keeps both elbows down and out (tools/center_handgun_carry.py,
        // ELBOW_POLE). Each goal goes below and outside its own hand by the High Ready weight: a pole, not a position.
        if (r.handgun) {
            goals = pistolHighGoals(goals, model, s, P, pistolHands(f, shift, r.hgHands, c.isFemale()), r.highW);
        }
        r.eyePose = GripIK.mulNew(GripIK.inverse(eye), P);
        if (r.fpW <= 1e-3) {
            r.mode = "fp faded";
            r.track = "";
            return;
        }
        r.mode = "fp " + stance + (r.actionW > 1e-3 && r.action != null ? " action" : "") + (rho > 0 ? " retreat" : "");
        r.track = r.actionW > 1e-3 && r.action != null ? r.action.name + "@" + round(r.actionU) + " w=" + round(r.actionW) : "";
        double[] beforeReach = r.eyePose;
        r.slideCm = 0;
        place(ap, c, r, s, model, P, r.fpW > 0.5 ? goals : null, forward, out[3][0], r.actingW, rho, true, t0);
        r.eyePose = GripIK.mulNew(GripIK.inverse(eye), P); // include reach correction in diagnostics
        if (FpJitterProbe.on) {
            FpJitterProbe.reach(beforeReach, r.eyePose, GripIK.norm(r.highPull) / CM, r.slideCm, r.bodyTurn, r.bodyPitch, r.handMiss);
        }
        r.wristEye = GripIK.pos(GripIK.mulNew(GripIK.inverse(eye), GripIK.read(ap.getModelTransformAt(s.rh), new double[16])));
        r.supportEye = GripIK.pos(GripIK.mulNew(GripIK.inverse(eye), GripIK.read(ap.getModelTransformAt(s.lh), new double[16])));
        double[] toEye = GripIK.inverse(eye);
        double[] el = GripIK.pos(GripIK.mulNew(toEye, GripIK.read(ap.getModelTransformAt(s.fa), new double[16])));
        double[] er = GripIK.pos(GripIK.mulNew(toEye, GripIK.read(ap.getModelTransformAt(s.rfa), new double[16])));
        r.elbowSideMin = Math.min(el[0], -er[0]) / CM;
        r.elbowsEye = new double[] {el[0], el[1], el[2], er[0], er[1], er[2]};
        r.forearmsCross = forearmsCross(el, r.supportEye, er, r.wristEye);
        double[] eyePoint = GripIK.pos(eye);
        r.armNear = Math.min(GripIK.segmentDistance(eyePoint, GripIK.pos(GripIK.read(ap.getModelTransformAt(s.fa), new double[16])),
                        GripIK.pos(GripIK.read(ap.getModelTransformAt(s.lh), new double[16]))),
                GripIK.segmentDistance(eyePoint, GripIK.pos(GripIK.read(ap.getModelTransformAt(s.rfa), new double[16])),
                        GripIK.pos(GripIK.read(ap.getModelTransformAt(s.rh), new double[16])))) / CM;
        if (r.sight != null) {
            // the layer's own ADS alignment (Tarkov's recoil and sway taken out exactly), and the drawn offset with them.
            // sightError is the distance off the eye's line of sight (0 when the line goes through the channel: item 4
            // slides the weapon along that line only, so sightReliefCm is its eye relief in front of the eye).
            double[] align = r.recoilShift == null ? r.eyePose : GripIK.mulNew(r.eyePose, GripIK.inverse(r.recoilShift));
            double[] channel = GripIK.mulNew(align, r.sight);
            r.sightError = Math.hypot(channel[3], channel[7]) / CM;
            r.sightReliefCm = -channel[11] / CM;
            r.sightAxisError = Math.toDegrees(Math.atan2(Math.hypot(channel[2], channel[6]), channel[10]));
            r.recoilSight = GripIK.norm(GripIK.pos(GripIK.mulNew(r.eyePose, r.sight))) / CM;
        }
        // item 4 QA: the nearest of a handgun's hands in the drawn ADS pose (eye <- Prop1), cm in front of the eye
        if (r.handgun) {
            double[][] pHands = pistolHands(f, shift, r.hgHands, c.isFemale());
            if (offhandItem(c, r) != null && (r.utilitySwitch || r.actionW < 0.5)) {
                pHands = new double[][] {pHands[1]};
            }
            double nearest = Double.POSITIVE_INFINITY;
            for (double[] p : pHands) {
                nearest = Math.min(nearest, -GripIK.point(r.eyePose, p[0], p[1], p[2])[2]);
            }
            r.handNearCm = nearest / CM;
        }
        // W7 QA: the sight's own nearest near point in the drawn pose (eye <- Prop1), and whether the depth clamp is on
        r.clampSafe = clampSafe(r);
        if (r.nearPoints != null) {
            double nearest = Double.POSITIVE_INFINITY;
            for (double[] p : r.nearPoints) {
                nearest = Math.min(nearest, -GripIK.point(r.eyePose, p[0], p[1], p[2])[2]);
            }
            r.sightNearCm = nearest / CM;
        }
        fpFrames++;
    }

    /** Third person, isometric or no eye yet: only the obstacle retreat over the current pose (its look frame). */
    static void retreatOnly(AnimationPlayer ap, IsoGameCharacter c, Request r, Skeleton s, double[][] model, double dt, long t0) {
        double[] P0 = model[s.prop];
        r.neutral = P0.clone();
        r.neutralAt = t0;
        r.neutralFrame = FpRecoil.worldFrame();
        r.stance = null;
        r.patrolW = 0;
        r.poseEyeModel = null;
        r.track = "";
        double rho = r.obstacleNow;
        if (rho <= 1e-4) {
            r.mode = "none";
            return;
        }
        double[] bore = GripIK.normalize(new double[] {P0[1], P0[5], P0[9]});
        double[] axis = GripIK.cross(bore, new double[] {0, 1, 0});
        if (GripIK.norm(axis) < 1e-6) {
            return;
        }
        axis = GripIK.normalize(axis);
        double b, p = 0, h = 0;
        if (r.pullUntil > 1e-6 && rho <= r.pullUntil) {
            b = r.back * rho / r.pullUntil;
        } else {
            double k = r.pullUntil >= 1 ? 1 : GripIK.smooth((rho - r.pullUntil) / (1 - r.pullUntil));
            b = r.back;
            p = r.pitch * k;
            h = r.up * k;
        }
        double[] P = GripIK.mulNew(GripIK.rigid(axis, p, GripIK.pos(P0)), P0);
        GripIK.setPos(P, GripIK.add(GripIK.sub(GripIK.pos(P), GripIK.scale(bore, b)), new double[] {0, h, 0}));
        P = chestClear(model, s, P, P0, r, bore, rho, c.isFemale() ? GripIK.TORSO_F : GripIK.TORSO_M);
        r.mode = "retreat";
        place(ap, c, r, s, model, P, null, null, 0, 0, rho, false, t0);
        retreats++;
    }

    /**
     * The retreat keeps the weapon's rear (Lua: WeaponLength rearProp) at the chest clearance: it
     * may not bring the rear deeper into the torso (GripIK's ellipse, grown by the clearance)
     * than the neutral pose has it - a shouldered stock already lies inside that simple ellipse,
     * so it is never pushed out at the start of a retreat; the pose moves ahead along dir as needed.
     */
    /** Transverse torso ellipse depth without the hard -1 axial cutoff at the pelvis/neck spine caps. */
    static double torsoRadialDepth(double[][] model, int[] chain, double[] p, double radius, double[][] torso) {
        double best = Double.MAX_VALUE;
        int bi = 0;
        double[] bc = null;
        for (int i = 0; i < 3; i++) {
            double[] a = GripIK.pos(model[chain[i]]), ab = GripIK.sub(GripIK.pos(model[chain[i + 1]]), a);
            double t = Math.max(0, Math.min(1, GripIK.dot(GripIK.sub(p, a), ab) / GripIK.dot(ab, ab)));
            double[] cp = GripIK.add(a, GripIK.scale(ab, t));
            double d = GripIK.dist(p, cp);
            if (d < best) {
                best = d;
                bi = i;
                bc = cp;
            }
        }
        double[] m = model[chain[bi]];
        double[] left = GripIK.normalize(new double[] {m[1], m[5], m[9]});
        double[] ahead = GripIK.normalize(new double[] {m[2], m[6], m[10]});
        double[] d = GripIK.sub(p, bc);
        double x = GripIK.dot(d, left), z = GripIK.dot(d, ahead);
        double hw = torso[bi][0] * CM, hz = (z > 0 ? torso[bi][1] : torso[bi][2]) * CM;
        double rr = Math.hypot(x, z), r = Math.hypot(x / hw, z / hz);
        return rr < 1e-9 ? Math.min(hw, hz) + radius : rr / r - rr + radius;
    }

    /** Smooth C1 fade to 0 over FP_SOFT_PASS at the pelvis and neck spine caps so chestClear never jumps at bt=0/1. */
    static double spineCapWeight(double[][] model, int[] chain, double[] p) {
        if (chain == null || chain.length < 4 || chain[0] < 0 || chain[3] < 0) {
            return 1;
        }
        double[] a0 = GripIK.pos(model[chain[0]]), u0 = GripIK.normalize(GripIK.sub(GripIK.pos(model[chain[1]]), a0));
        double[] a3 = GripIK.pos(model[chain[3]]), u2 = GripIK.normalize(GripIK.sub(a3, GripIK.pos(model[chain[2]])));
        double h = Math.min(GripIK.dot(GripIK.sub(p, a0), u0), GripIK.dot(GripIK.sub(a3, p), u2));
        return GripIK.smooth(h / FP_SOFT_PASS);
    }

    static double[] chestClear(double[][] model, Skeleton s, double[] P, double[] neutral, Request r, double[] dir, double rho, double[][] torso) {
        if (r.rear == null || rho <= 0) {
            return P;
        }
        double allowed = Math.max(0, torsoRadialDepth(model, s.chain, GripIK.point(neutral, r.rear[0], r.rear[1], r.rear[2]), r.chest, torso));
        double[] rear = GripIK.point(P, r.rear[0], r.rear[1], r.rear[2]);
        double d0 = GripIK.torsoDepth(model, s.chain, rear, r.chest, torso);
        if (d0 <= allowed) {
            return P;
        }
        double[] pushDir = dir;
        if (s.chain != null && s.chain.length >= 4 && s.chain[0] >= 0 && s.chain[3] >= 0
                && model[s.chain[0]] != null && model[s.chain[3]] != null) {
            double[] spineUp = GripIK.normalize(GripIK.sub(GripIK.pos(model[s.chain[3]]), GripIK.pos(model[s.chain[0]])));
            double alongUp = GripIK.dot(pushDir, spineUp);
            if (alongUp < -1e-6) { // looking down: push forward out of the chest, never down below the pelvis cutoff
                double[] horiz = GripIK.sub(pushDir, GripIK.scale(spineUp, alongUp));
                if (GripIK.norm(horiz) > 1e-6) {
                    pushDir = GripIK.normalize(horiz);
                } else if (s.chain[2] >= 0 && model[s.chain[2]] != null) {
                    double[] ch = model[s.chain[2]];
                    pushDir = GripIK.normalize(new double[] {ch[2], ch[6], ch[10]});
                }
            }
        }
        double hi = r.back + 10 * CM, shift = 0;
        double prevX = 0, minX = 0, minD = d0;
        boolean cleared = false;
        double frontW = 1;
        if (s.chain != null && s.chain.length >= 4 && s.chain[0] >= 0 && s.chain[3] >= 0) {
            double best = Double.MAX_VALUE;
            double[] bc = null;
            for (int i = 0; i < 3; i++) {
                double[] a = GripIK.pos(model[s.chain[i]]), ab = GripIK.sub(GripIK.pos(model[s.chain[i + 1]]), a);
                double t = Math.max(0, Math.min(1, GripIK.dot(GripIK.sub(rear, a), ab) / GripIK.dot(ab, ab)));
                double[] cp = GripIK.add(a, GripIK.scale(ab, t));
                double d = GripIK.dist(rear, cp);
                if (d < best) {
                    best = d;
                    bc = cp;
                }
            }
            if (bc != null) {
                double zPush = GripIK.dot(GripIK.sub(rear, bc), pushDir);
                frontW = GripIK.smooth((zPush + 2 * FP_SOFT_PASS) / (2 * FP_SOFT_PASS));
            }
        }
        if (frontW <= 0) {
            return P;
        }
        for (int k = 1; k <= 16; k++) {
            double x = hi * k / 16.0;
            double d = torsoRadialDepth(model, s.chain, GripIK.add(rear, GripIK.scale(pushDir, x)), r.chest, torso);
            if (d <= allowed) {
                double lo = prevX, up = x;
                for (int i = 0; i < 10; i++) {
                    double mid = (lo + up) / 2;
                    if (torsoRadialDepth(model, s.chain, GripIK.add(rear, GripIK.scale(pushDir, mid)), r.chest, torso) > allowed) {
                        lo = mid;
                    } else {
                        up = mid;
                    }
                }
                shift = up;
                cleared = true;
                break;
            }
            if (d < minD) {
                minD = d;
                minX = x;
            }
            prevX = x;
        }
        if (!cleared && minD < d0) {
            shift = minX * (1 - GripIK.smooth((minD - allowed) / (2 * FP_SOFT_PASS)));
        }
        shift *= frontW * spineCapWeight(model, s.chain, rear);
        if (shift <= 0) {
            return P;
        }
        double[] out = P.clone();
        GripIK.setPos(out, GripIK.add(GripIK.pos(P), GripIK.scale(pushDir, shift)));
        return out;
    }

    /**
     * FB4-A: the fire-mode gesture's pose track (TW/WeaponInspect, no engine track) at r.gestureU, or null (none, stale,
     * a clip without a track). A press during a gesture restarts it on the rising side of the clip where the hand's reach
     * is the same; the pose there differs (Tarkov's AK74 FIREMODE up to 13 degrees and 5 cm), so it fades from the pose
     * drawn last over GESTURE_FADE_S - then a one-frame track of the blended pose.
     */
    static FpPoseData.Track gestureTrack(FpPoseData d, Request r, double step) {
        String clip = r.gestureClip;
        FpPoseData.Track track = clip == null || !freshFrame(r.gestureFrame, FpRecoil.worldFrame()) ? null : d.aliases.get(clip);
        if (track == null) {
            r.gestureSeen = 0;
            r.gestureLast = null;
            return null;
        }
        if (r.gestureSeen != 0 && r.gestureToken != r.gestureSeen && r.gestureLast != null) {
            r.gestureFrom = r.gestureLast;
            r.gestureFade = 0;
        }
        r.gestureSeen = r.gestureToken;
        double[] pose = track.pose(r.gestureU);
        if (r.gestureFade >= 1 || r.gestureFrom == null) {
            r.gestureLast = pose;
            return track;
        }
        r.gestureFade = Math.min(1, r.gestureFade + Math.max(0, step) / GESTURE_FADE_S);
        pose = interp(r.gestureFrom, pose, GripIK.smooth(r.gestureFade));
        r.gestureLast = pose;
        int frame = (int) Math.round(Math.max(0, Math.min(1, r.gestureU)) * (track.frames - 1));
        float[] data = java.util.Arrays.copyOfRange(track.data, frame * track.stride, (frame + 1) * track.stride);
        for (int i = 0; i < 12; i++) {
            data[i] = (float) pose[i];
        }
        return new FpPoseData.Track(track.name, track.flags & ~FpPoseData.T_LOOP, track.seconds, 1, data);
    }

    /** The heaviest playing track whose clip has a pose track: {track index, fraction, blend weight}, or null. */
    static double[] action(AnimationPlayer ap, FpPoseData d) {
        AnimationMultiTrack mt = ap.getMultiTrack();
        if (mt == null || d.aliases.isEmpty()) {
            return null;
        }
        double[] best = null;
        for (AnimationTrack t : mt.getTracks()) {
            if (t == null || t.getClip() == null) {
                continue;
            }
            FpPoseData.Track track = d.aliases.get(t.getClip().name);
            float w = t.getBlendWeight();
            if (track != null && w > 0 && (best == null || w > best[2])) {
                // FB4-A: a utility clip ends in its exit hold (ActionExits.held) - the source track spans its motion only
                double u = ActionExits.held(t.getClip().name) && t.getDuration() > ActionExits.EXIT_S
                        ? Math.min(1, t.getCurrentTrackTime() / (t.getDuration() - ActionExits.EXIT_S)) : t.getCurrentTimeFraction();
                best = new double[] {d.tracks.indexOf(track), u, Math.min(1, w)};
            }
        }
        return best;
    }

    static final class ManualHit {
        final FpPoseData.Track track;
        final double u, w;
        ManualHit(FpPoseData.Track track, double u, double w) { this.track = track; this.u = u; this.w = w; }
    }

    static ManualHit manualAction(AnimationPlayer ap, IsoGameCharacter c, Request r) {
        AnimationMultiTrack mt = ap == null ? null : ap.getMultiTrack();
        FpManualPoseData md = mt == null ? null : FpManualPoseData.get();
        if (md == null || md.aliases.isEmpty()) {
            return null;
        }
        String manualId = c == null ? null : c.getVariableString("TWManualAction");
        ManualHit best = null;
        boolean seenBound = false;
        for (AnimationTrack t : mt.getTracks()) {
            if (t == null || t.getClip() == null || t.getClip().name == null) {
                continue;
            }
            float w = t.getBlendWeight();
            if (w <= 0) {
                continue;
            }
            String cn = t.getClip().name;
            FpPoseData.Track tr = md.aliases.get(cn);
            if (tr == null && ("Bob_Reload_Bolt_Rack".equals(cn) || "Bob_Reload_Shotgun_Load".equals(cn))) {
                if (manualId != null && !manualId.isEmpty()) {
                    tr = md.aliases.get(manualId);
                    if (tr != null && (r.manualAnimTrack == null || t == r.manualAnimTrack || w >= 0.15f)) {
                        r.manualAnimTrack = t;
                        r.manualFpTrack = tr;
                    }
                } else if (t == r.manualAnimTrack) {
                    tr = r.manualFpTrack;
                }
            }
            if (t == r.manualAnimTrack) {
                seenBound = true;
            }
            if (tr != null && (best == null || w > best.w)) {
                best = new ManualHit(tr, Math.max(0, Math.min(1, t.getCurrentTimeFraction())), Math.min(1, w));
            }
        }
        if (!seenBound && (manualId == null || manualId.isEmpty())) {
            r.manualAnimTrack = null;
            r.manualFpTrack = null;
        }
        return best;
    }

    // ------------------------------------------------------------------ the weapon system and the arms

    /**
     * The weapon to P (model space): stationary FP contacts are source/assembly frames;
     * actions and other views retain the animated hold. Arms solve from their shoulders.
     * forward (first person): out of reach the system slides back along the view (not in ADS).
     * acting: 0..1, how far the reach corrections are released to an action (Request.actingW).
     */
    static void place(AnimationPlayer ap, IsoGameCharacter c, Request r, Skeleton s, double[][] model, double[] P, double[] goals,
                      double[] forward, double adsW, double acting, double rho, boolean fp, long t0) {
        double[] P0 = model[s.prop];
        double[] D = GripIK.mulNew(P, GripIK.inverse(P0));
        double[] HR = GripIK.mulNew(D, model[s.rh]), HL = GripIK.mulNew(D, model[s.lh]);
        zombie.inventory.InventoryItem offhand = offhandItem(c, r);
        double offhandActW = offhand == null ? 1 : (fp ? (r.utilitySwitch ? 0 : GripIK.smooth(r.actionW))
                : GripIK.smooth(GripIK.ownActionTrackWeight(ap)));
        double offhandW = offhand == null ? 0 : 1 - offhandActW;
        boolean coneTorch = fp && offhandW > 0 && isConeTorch(offhand) && s.prop2 >= 0 && s.prop2 < s.n && r.eyeModel != null;
        boolean harriesTorch = coneTorch && !qaLegacyTorchFp && (c == null || !c.getVariableBoolean("TWQaLegacyTorchFp"));
        String offhandMask = coneTorch ? secondaryMaskValue(offhand) : null;
        double[] tLhP2 = offhandW > 0 && s.prop2 >= 0 && s.prop2 < s.n
                ? (harriesTorch ? harriesTLhP2(offhandMask) : GripIK.mulNew(GripIK.inverse(model[s.lh]), model[s.prop2])) : null;
        FpPoseData data = fp ? FpPoseData.get() : null;
        FpPoseData.Family fam = data == null ? null : family(data, r);
        // Tarkov's hand (fingers too) where the hand holds the family contact: the right hand, and the
        // left without an assembly support contact (handguns); a shoulder launcher keeps its clip's hands
        double contactW = r.fpW * (1 - GripIK.smooth(r.actionW));
        // FB4-A: a fire-mode switch keeps the support hand on its contact (Tarkov's FIREMODE moves the shooting hand only)
        double contactL = (r.utilitySwitch ? r.fpW : contactW) * (1 - offhandW);
        // FB2-D: on a bare handguard (the bare reference class) the assembly contact is the family's own kind
        // of hold, so the left fingers close round it in Tarkov's pose too (before: the clip's open hand).
        boolean bareContact = r.left != null && GripIK.BARE_CLASS.equals(r.cls);
        // FB2-N: a support with its own grip surface (GripIK 'hold': a vertical or angled grip, a hand stop) - the fingers
        // close round it as well, from a vertical grip's own rests (Tarkov's pistol-grip fingers, mirrored)
        GripIK.Request gripReq = GripIK.requests.get(c);
        // Reuse the surface-selected hand and its paired elbow direction from this animation update.
        // Patrol's authored hand motion alone does not preserve the assembled weapon's handguard contact.
        GripIK.SurfaceGrip surface = fp && !r.handgun && r.launcherHold == null
                && !(zombie.core.Core.debug && c != null && c.getVariableBoolean("TWQaPatrolRawGrip"))
                ? GripIK.surfaceGrip(ap, r.item) : null;
        double surfaceBlend = surface == null ? 0 : r.patrolW * (1 - adsW) * (1 - GripIK.smooth(r.highW));
        r.patrolSurfaceW = 0;
        boolean holdContact = r.left != null && gripReq != null && gripReq.wrap != null; // (an underbarrel launcher: its stations, no surface)
        FpManualPoseData.RoundTrack rt = manualEye && fam != null && fam.ready == null && r.action != null && r.actionW > 1e-4
                ? FpManualPoseData.round(r.action) : null;
        boolean manualHands = rt != null || manualEye && fam != null && fam.ready == null && r.action != null && r.actionW > 1e-4
                && (r.action.flags & (FpPoseData.T_MANUAL | FpPoseData.T_HANDS)) == (FpPoseData.T_MANUAL | FpPoseData.T_HANDS);
        r.fingerFit[0] = fam != null && fam.ready == null && (r.left == null || bareContact || holdContact || (rt != null && rt.side == 0))
                ? (manualHands ? r.fpW : contactL) : 0;
        r.fingerFit[1] = fam != null && fam.ready == null ? (manualHands ? r.fpW : contactW) : 0;
        if (rt != null) {
            double mw = GripIK.smooth(r.actionW), lw = mw * rt.leave(r.actionU);
            double[] w0L = handContactBase(fam, r, c.isFemale(), 0), w0R = handContactBase(fam, r, c.isFemale(), 1);
            double[] goalL = rt.side == 0 ? interp(w0L, rt.hand(r.actionU, c.isFemale()), lw) : w0L;
            double[] goalR = rt.side == 1 ? interp(w0R, rt.hand(r.actionU, c.isFemale()), lw) : w0R;
            HL = interp(HL, GripIK.mulNew(P, goalL), r.fpW);
            HR = interp(HR, GripIK.mulNew(P, goalR), r.fpW);
        } else if (manualHands) {
            double[][] th = r.action.hands(r.actionU);
            double mw = GripIK.smooth(r.actionW);
            double[] w0L = handContactBase(fam, r, c.isFemale(), 0), w0R = handContactBase(fam, r, c.isFemale(), 1);
            HL = interp(HL, GripIK.mulNew(P, interp(w0L, GripIK.mulNew(th[0], w0L), mw)), r.fpW);
            HR = interp(HR, GripIK.mulNew(P, interp(w0R, GripIK.mulNew(th[1], w0R), mw)), r.fpW);
        } else if (fam != null && fam.ready == null) {
            double[] supportTarget = handContact(fam, r, P, c.isFemale(), 0);
            if (surfaceBlend > 0) {
                supportTarget = interp(supportTarget, GripIK.mulNew(P, surface.hand()), surfaceBlend);
                r.patrolSurfaceW = surfaceBlend * contactL;
            }
            HL = interp(HL, supportTarget, contactL);
            HR = interp(HR, handContact(fam, r, P, c.isFemale(), 1), contactW);
        }
        if (fam != null && fam.ready != null) {
            // The old right carry contact brought the wrist inside the camera's
            // near envelope even after the left contact was corrected.
            // Fade both source contacts with the layer/action instead of switching
            // the left contact abruptly when the action flag changes.
            double[][] hands = {HL, HR};
            for (int side = 0; side < 2; side++) {
                double handAds = r.ads + (r.handoffAds - r.ads) * r.handoffW;
                double[] contact = launcherWrist(fam, shift(fam, r.weapon, r.dR), P, handAds, side);
                double wSide = side == 0 ? contactW * (1 - offhandW) : contactW;
                GripIK.setPos(hands[side], GripIK.lerp(GripIK.pos(hands[side]), contact, wSide));
            }
        }
        if (coneTorch && tLhP2 != null) {
            double l2 = GripIK.dist(GripIK.pos(model[s.lh]), GripIK.pos(model[s.fa]));
            double[][] torchTarget = harriesTorch
                    ? harriesOffhandPose(P, HR, l2, offhandMask)
                    : offhandLightTarget(r.eyeModel, tLhP2, l2, offhandMask);
            HL = interp(HL, torchTarget[0], offhandW);
            if (goals != null) {
                goals = goals.clone();
                for (int i = 0; i < 3; i++) {
                    goals[i] += (torchTarget[1][i] - goals[i]) * offhandW;
                }
            }
        }
        if (harriesTorch && forward != null) {
            double[] ahead = GripIK.scale(forward, HARRIES_FP_VIEW_FORWARD * offhandW);
            for (double[] m : new double[][] {P, HR, HL}) GripIK.setPos(m, GripIK.add(GripIK.pos(m), ahead));
            D = GripIK.mulNew(P, GripIK.inverse(P0));
            if (goals != null) {
                goals = goals.clone();
                for (int side = 0; side < 2; side++) for (int i = 0; i < 3; i++) goals[side * 3 + i] += ahead[i];
            }
        }
        if (forward != null) {
            double reachR = reach(model, s.rua, s.rfa, s.rh), reachL = reach(model, s.ua, s.fa, s.lh);
            double slide = GripIK.slideBack(GripIK.pos(HR), GripIK.pos(model[s.rua]), reachR, forward);
            if (acting < 1 && offhandW < 1 - 1e-4) {
                // the support hand's reach counts while it holds the weapon, released continuously to an action
                double left = GripIK.slideBack(GripIK.pos(HL), GripIK.pos(model[s.ua]), reachL, forward);
                slide += Math.max(0, left - slide) * (1 - acting) * (1 - offhandW);
            }
            double ready = r.pullUntil >= 1 ? 0 : GripIK.smooth((rho - r.pullUntil) / (1 - r.pullUntil));
            slide = slideHeld(r, Math.min(slide, SLIDE_MAX), fp && FpRecoil.firing(c, t0), t0) * (1 - adsW) * (1 - ready);
            r.slideCm = slide / CM;
            if (slide > 1e-6) {
                double[] back = GripIK.scale(forward, -slide);
                if (offhandW <= 0 || harriesTorch) {
                    for (double[] m : new double[][] {P, HR, HL}) {
                        GripIK.setPos(m, GripIK.add(GripIK.pos(m), back));
                    }
                    D = GripIK.mulNew(P, GripIK.inverse(P0));
                    if (goals != null) {
                        goals = goals.clone();
                        for (int side = 0; side < 2; side++) for (int i = 0; i < 3; i++) goals[side * 3 + i] += back[i];
                    }
                } else {
                    GripIK.setPos(P, GripIK.add(GripIK.pos(P), back));
                    GripIK.setPos(HR, GripIK.add(GripIK.pos(HR), back));
                    if (offhandW < 1 - 1e-6) {
                        GripIK.setPos(HL, GripIK.add(GripIK.pos(HL), GripIK.scale(back, 1 - offhandW)));
                    }
                    D = GripIK.mulNew(P, GripIK.inverse(P0));
                    if (goals != null) {
                        goals = goals.clone();
                        for (int i = 0; i < 3; i++) {
                            goals[i] += back[i] * (1 - offhandW);
                            goals[3 + i] += back[i];
                        }
                    }
                }
                slides++;
            }
        }
        boolean fresh = r.elbowAt == 0 || t0 - r.elbowAt > GripIK.ARM_RESET_NS; // a stalled frame carries over (GripIK.ARM_*)
        double dt = fresh ? 0 : Math.min(GripIK.ARM_DT_MAX, (t0 - r.elbowAt) / 1e9);
        r.elbowAt = t0;
        double[][] torso = c.isFemale() ? GripIK.TORSO_F : GripIK.TORSO_M;
        double[][] baseModel = model;
        r.bodyGoal = r.bodyCap = r.reachBefore = r.reachAfter = r.pitchGoal = 0;
        r.highPull = new double[3];
        // FB4 (the user's item 21): where the pull may bring the weapon in (high ready / the retreat, outside
        // ADS) the support hand keeps its point of the handguard while the camera moves - the reach search
        // works on the contact itself, not on the slide back along the bore (params[61]) that followed the
        // camera; supportReach below still slides what the pull's cap leaves out of reach.
        double ready = r.pullUntil >= 1 ? 0 : GripIK.smooth((rho - r.pullUntil) / (1 - r.pullUntil));
        double glue = glue(ready, adsW), back = offhandW <= 0 ? glueBack(r.supportBack, glue) : glueBack(r.supportBack, glue) * (1 - offhandW);
        if (fp) {
            double[] reachEye = r.poseEyeModel != null ? r.poseEyeModel : r.eyeModel;
            double[] savedHL = HL;
            if (offhandW > 0 && !coneTorch) {
                HL = offhandW >= 1 - 1e-4 ? HR.clone() : interp(HL, HR, offhandW);
            }
            // Looking up, the upper body follows the camera first (the smallest share of the look's
            // angle that lets both hands reach); the twist below then works on what remains.
            double[] up = FpPoseReach.lookUp(reachEye);
            double held = 1 - acting;
            if (held > 1e-3 && r.fpW > .5 && up != null) {
                r.pitchGoal = FpPoseReach.lift(model, s, HL, HR, P, back, reachEye) * held;
            }
            r.bodyPitch = Math.min(up == null ? 0 : up[3], advanceBody(r.bodyPitch, r.pitchGoal, Math.PI / 2, fresh ? 1.0 / 60 : dt));
            model = FpPoseReach.pitch(model, s, up, reachEye, r.bodyPitch);
            r.bodyCap = FpPoseReach.budget(ap.getMaxTwistAngle(), ap.getTwistAngle(), ap.getShoulderTwistAngle());
            if (held > 1e-3 && r.fpW > .5) {
                r.reachBefore = FpPoseReach.score(model, s, HL, HR, P, back, reachEye) / CM;
                r.bodyGoal = FpPoseReach.turn(model, s, HL, HR, P, back, reachEye, r.bodyCap) * held;
            }
            if (offhandW > 0 && !coneTorch) {
                HL = savedHL;
            }
            // Body motion releases to actions/the body's own poses. A long animation
            // tick cannot jump to the final twist. Head/eye stay fixed at every weight.
            r.bodyTurn = advanceBody(r.bodyTurn, r.bodyGoal, r.bodyCap, fresh ? 1.0 / 60 : dt);
            model = FpPoseReach.body(model, s, r.bodyTurn);
            if (held > 1e-3) {
                if (offhandW > 0) {
                    HL = offhandW >= 1 - 1e-4 ? HR.clone() : interp(HL, HR, offhandW);
                }
                r.highPull = GripIK.scale(FpPoseReach.pull(model, s, HL, HR, P, back, reachEye, SLIDE_MAX * glue * r.fpW), held);
                if (offhandW > 0) {
                    HL = savedHL;
                }
                if (offhandW <= 0 || harriesTorch) {
                    for (double[] m : new double[][] {P, HL, HR}) GripIK.setPos(m, GripIK.add(GripIK.pos(m), r.highPull));
                    if (goals != null) {
                        goals = goals.clone();
                        for (int side = 0; side < 2; side++) for (int i = 0; i < 3; i++) goals[side * 3 + i] += r.highPull[i];
                    }
                } else {
                    GripIK.setPos(P, GripIK.add(GripIK.pos(P), r.highPull));
                    GripIK.setPos(HR, GripIK.add(GripIK.pos(HR), r.highPull));
                    if (offhandW < 1 - 1e-6) {
                        GripIK.setPos(HL, GripIK.add(GripIK.pos(HL), GripIK.scale(r.highPull, 1 - offhandW)));
                    }
                    if (goals != null) {
                        goals = goals.clone();
                        for (int i = 0; i < 3; i++) {
                            goals[i] += r.highPull[i] * (1 - offhandW);
                            goals[3 + i] += r.highPull[i];
                        }
                    }
                }
                D = GripIK.mulNew(P, GripIK.inverse(P0));
                double[] reachAfterHL = offhandW <= 0 || coneTorch ? HL : (offhandW >= 1 - 1e-4 ? HR : interp(HL, HR, offhandW));
                r.reachAfter = FpPoseReach.score(model, s, reachAfterHL, HR, P, back, reachEye) / CM;
            }
            // Wave 21.10.2026, item 10: an underbarrel's reload works at the launcher itself (the grenade into the muzzle
            // or the breech, the support hand on its target throughout), so the action keeps the hands in reach where
            // other actions release the hold's corrections: the least pull of the weapon with both targets towards the
            // support shoulder that reaches them (FpPoseReach.pull on reach alone, no elbow-circle term), at most
            // SLIDE_MAX, by the action's weight. Tarkov's first-person path put the GP-25 muzzle and the M203 breech up
            // to 9.7 cm past the PZ arm from the game's eye (11.5 cm above and 13.8 cm ahead of Bip01_Head); the host
            // tracks carry that pull ahead of the need (tools/fp_pose_runtime reach lead, at most 0.5 cm a 60 Hz frame),
            // this one keeps what the game's own geometry still lacks - gained at once, released over RELOAD_RELEASE_S.
            double[] need = acting > 1e-3 && launcherAction(r)
                    ? GripIK.scale(FpPoseReach.pull(model, s, HL, HR, P, 0, null, SLIDE_MAX * r.fpW), acting) : new double[3];
            r.reloadPull = reloadPull(fresh ? null : r.reloadPull, need, dt);
            if (GripIK.norm(r.reloadPull) > 0) {
                if (offhandW <= 0 || harriesTorch) {
                    for (double[] m : new double[][] {P, HL, HR}) GripIK.setPos(m, GripIK.add(GripIK.pos(m), r.reloadPull));
                    if (goals != null) {
                        goals = goals.clone();
                        for (int side = 0; side < 2; side++) for (int i = 0; i < 3; i++) goals[side * 3 + i] += r.reloadPull[i];
                    }
                } else {
                    GripIK.setPos(P, GripIK.add(GripIK.pos(P), r.reloadPull));
                    GripIK.setPos(HR, GripIK.add(GripIK.pos(HR), r.reloadPull));
                    if (offhandW < 1 - 1e-6) {
                        GripIK.setPos(HL, GripIK.add(GripIK.pos(HL), GripIK.scale(r.reloadPull, 1 - offhandW)));
                    }
                    if (goals != null) {
                        goals = goals.clone();
                        for (int i = 0; i < 3; i++) {
                            goals[i] += r.reloadPull[i] * (1 - offhandW);
                            goals[3 + i] += r.reloadPull[i];
                        }
                    }
                }
                D = GripIK.mulNew(P, GripIK.inverse(P0));
            }
        } else {
            r.bodyTurn = 0;
            r.bodyPitch = 0;
            r.reloadPull = new double[3];
        }
        // FB5 item 7: the slide back out of reach, the chest's clearance and the pull in above move the weapon after
        // keepOut - the sight is kept beyond the near plane after them too, by the same rule along the view
        if (fp && forward != null && r.eyeModel != null) {
            double need = nearNeed(r, GripIK.mulNew(GripIK.inverse(r.eyeModel), P), r.aiming || adsW > 1e-3, adsW);
            if (need > 1e-7) {
                double[] camFwd = r.poseEyeModel == null ? forward
                        : GripIK.normalize(new double[] {-r.eyeModel[2], -r.eyeModel[6], -r.eyeModel[10]});
                double[] ahead = GripIK.scale(camFwd, need);
                if (offhandW <= 0 || harriesTorch) {
                    for (double[] m : new double[][] {P, HL, HR}) {
                        GripIK.setPos(m, GripIK.add(GripIK.pos(m), ahead));
                    }
                    if (goals != null) {
                        goals = goals.clone();
                        for (int side = 0; side < 2; side++) for (int i = 0; i < 3; i++) goals[side * 3 + i] += ahead[i];
                    }
                } else {
                    GripIK.setPos(P, GripIK.add(GripIK.pos(P), ahead));
                    GripIK.setPos(HR, GripIK.add(GripIK.pos(HR), ahead));
                    if (offhandW < 1 - 1e-6) {
                        GripIK.setPos(HL, GripIK.add(GripIK.pos(HL), GripIK.scale(ahead, 1 - offhandW)));
                    }
                    if (goals != null) {
                        goals = goals.clone();
                        for (int i = 0; i < 3; i++) {
                            goals[i] += ahead[i] * (1 - offhandW);
                            goals[3 + i] += ahead[i];
                        }
                    }
                }
                D = GripIK.mulNew(P, GripIK.inverse(P0));
                r.nearPushCm += need / CM;
                r.nearPushes++;
            }
        }
        double[] sourceLeft = GripIK.pos(HL), sourceRight = GripIK.pos(HR);
        r.sourceContactAdjust = 0;
        if (fp && acting < 1 - 1e-3 && offhandW < 1 - 1e-3) {
            double[] reachEye = r.poseEyeModel != null ? r.poseEyeModel : r.eyeModel;
            double[] corrected = supportReach(model, s, HL, P, r.supportBack, reachEye);
            if (offhandW <= 0) {
                r.sourceContactAdjust = GripIK.dist(GripIK.pos(HL), GripIK.pos(corrected)) / CM;
                HL = acting > 1e-3 ? interp(HL, corrected, 1 - acting) : corrected;
            } else {
                r.sourceContactAdjust = GripIK.dist(GripIK.pos(HL), GripIK.pos(corrected)) / CM * (1 - offhandW);
                double wSupp = (1 - acting) * (1 - offhandW);
                HL = wSupp < 1 - 1e-3 ? interp(HL, corrected, wSupp) : corrected;
            }
        }
        r.contactClear = 0;
        if (fam != null && fam.ready != null) {
            // A low camera/downward view can put a valid Tarkov contact inside the
            // PZ torso. Keep the exact sight/weapon frame and project only that hand
            // to the nearest clear point along the chest's forward direction.
            double[] left = clearLauncherWrist(model, s, HL, torso), right = clearLauncherWrist(model, s, HR, torso);
            r.contactClear = Math.max(GripIK.dist(GripIK.pos(HL), GripIK.pos(left)), GripIK.dist(GripIK.pos(HR), GripIK.pos(right))) / CM;
            HL = left;
            HR = right;
        }
        if (bonesProbe) {
            double[] lv = GripIK.sub(GripIK.pos(model[s.ua]), GripIK.pos(model[s.lc])), rv = GripIK.sub(GripIK.pos(model[s.rua]), GripIK.pos(model[s.rc]));
            double[] lb = GripIK.sub(GripIK.pos(baseModel[s.ua]), GripIK.pos(baseModel[s.lc]));
            probeIn = String.format(java.util.Locale.ROOT, "inL=%.1f,%.1f,%.1f inR=%.1f,%.1f,%.1f baseL=%.1f,%.1f,%.1f bodyTurnDeg=%.1f",
                    lv[0] / CM, lv[1] / CM, lv[2] / CM, rv[0] / CM, rv[1] / CM, rv[2] / CM, lb[0] / CM, lb[1] / CM, lb[2] / CM, Math.toDegrees(r.bodyTurn));
            // Opt-in QA: actual pre-IK surface-fit inputs, in model units. This permits replay
            // against the real part mesh instead of inferring contact from handMiss or bone angles.
            StringBuilder input = new StringBuilder(probeIn);
            String[] labels = {"P", "Pelvis", "Spine", "Spine1", "Neck", "L_Clavicle", "L_UpperArm", "L_Forearm", "L_Hand"};
            int[] ids = {-1, s.chain[0], s.chain[1], s.chain[2], s.chain[3], s.lc, s.ua, s.fa, s.lh};
            for (int j = 0; j < ids.length; j++) {
                double[] matrix = j == 0 ? P : model[ids[j]];
                input.append(" fit_").append(labels[j]).append('=');
                for (int i = 0; i < 12; i++) {
                    if (i > 0) input.append(',');
                    input.append(String.format(java.util.Locale.ROOT, "%.8f", matrix[i]));
                }
            }
            String[] contactLabels = {"surface_H", "target_H", "shown_H", "held_H"};
            double[][] contactMatrices = {surface == null ? null : surface.hand(), GripIK.mulNew(GripIK.inverse(P), HL),
                    gripReq == null ? null : gripReq.holdShown, gripReq == null ? null : gripReq.held};
            for (int j = 0; j < contactMatrices.length; j++) {
                if (contactMatrices[j] == null) continue;
                input.append(" fit_").append(contactLabels[j]).append('=');
                for (int i = 0; i < 12; i++) {
                    if (i > 0) input.append(',');
                    input.append(String.format(java.util.Locale.ROOT, "%.8f", contactMatrices[j][i]));
                }
            }
            probeIn = input.toString();
        }
        double[] out = fp ? null : elbowOut(model, s, rho, r.elbowOut);
        double[][] right = arm(model, s, s.rc, s.rua, s.rfa, HR, pole(goals, 3, model[s.rua], HR, out, 1), torso, r, 1, fresh, dt);
        boolean passiveLeft = offhandW > 0 && !coneTorch;
        double[][] left;
        if (passiveLeft && offhandW >= 1 - 1e-4) {
            double[] neckDelta = model == baseModel ? null : GripIK.mulNew(model[s.chain[3]], GripIK.inverse(baseModel[s.chain[3]]));
            left = neckDelta == null
                    ? new double[][] {baseModel[s.lc], baseModel[s.ua], baseModel[s.fa], baseModel[s.lh], null}
                    : new double[][] {GripIK.mulNew(neckDelta, baseModel[s.lc]), GripIK.mulNew(neckDelta, baseModel[s.ua]),
                    GripIK.mulNew(neckDelta, baseModel[s.fa]), GripIK.mulNew(neckDelta, baseModel[s.lh]), null};
            if (c != null) {
                ArmNatural.State st = ArmNatural.STATES.get(c);
                if (st != null) {
                    st.ik[0] = false;
                    st.pole[0] = null;
                }
            }
        } else {
            double[] supportPole = pole(goals, 0, model[s.ua], HL, out, 0);
            if (surface != null && r.patrolSurfaceW > 0 && supportPole != null) {
                double[] selectedPole = GripIK.holdFromWeapon(P, surface.pole());
                double[] mixed = GripIK.lerp(supportPole, selectedPole, r.patrolSurfaceW);
                if (GripIK.norm(mixed) > 1e-8) supportPole = GripIK.normalize(mixed);
            }
            left = arm(model, s, s.lc, s.ua, s.fa, HL, supportPole, torso, r, 0, fresh, dt);
            if (passiveLeft) {
                double[] neckDelta = model == baseModel ? null : GripIK.mulNew(model[s.chain[3]], GripIK.inverse(baseModel[s.chain[3]]));
                int[] lb = {s.lc, s.ua, s.fa, s.lh};
                for (int k = 0; k < 4; k++) {
                    double[] passive = neckDelta == null ? baseModel[lb[k]] : GripIK.mulNew(neckDelta, baseModel[lb[k]]);
                    left[k] = interp(left[k], passive, offhandW);
                }
            }
            if (!passiveLeft || offhandW < 0.5) {
                ArmNatural.solved(c, 0, left[4]);
            }
        }
        ArmNatural.solved(c, 1, right[4]);
        if (passiveLeft && offhandW >= 0.5) {
            r.handMiss = GripIK.dist(GripIK.pos(right[3]), GripIK.pos(HR)) / CM;
            r.sourceContactMiss = GripIK.dist(GripIK.pos(right[3]), sourceRight) / CM;
        } else {
            r.handMiss = Math.max(GripIK.dist(GripIK.pos(left[3]), GripIK.pos(HL)), GripIK.dist(GripIK.pos(right[3]), GripIK.pos(HR))) / CM;
            r.sourceContactMiss = Math.max(GripIK.dist(GripIK.pos(left[3]), sourceLeft), GripIK.dist(GripIK.pos(right[3]), sourceRight)) / CM;
        }
        r.elbowDepth = Math.max(GripIK.torsoDepth(model, s.chain, GripIK.pos(left[2]), 0, torso),
                GripIK.torsoDepth(model, s.chain, GripIK.pos(right[2]), 0, torso)) / CM;
        // the forearms' turn about their lengths: ArmNatural, the last layer (GripIK.onModel), on these points
        double[][] next = new double[s.n][];
        boolean[] driver = new boolean[s.n];
        boolean driveLeft = !(passiveLeft && !fp && offhandW > 0.5);
        if (driveLeft) {
            int[] ids = {s.rc, s.rua, s.rfa, s.rh, s.lc, s.ua, s.fa, s.lh, s.prop};
            double[][] values = {right[0], right[1], right[2], right[3], left[0], left[1], left[2], left[3], P};
            for (int i = 0; i < ids.length; i++) {
                next[ids[i]] = values[i];
                driver[ids[i]] = true;
            }
        } else {
            int[] rightIds = {s.rc, s.rua, s.rfa, s.rh, s.prop};
            double[][] rightValues = {right[0], right[1], right[2], right[3], P};
            for (int i = 0; i < rightIds.length; i++) {
                next[rightIds[i]] = rightValues[i];
                driver[rightIds[i]] = true;
            }
        }
        if (model != baseModel) {
            for (int b : new int[] {s.chain[2], s.chain[3], s.head}) {
                next[b] = model[b];
                driver[b] = true;
            }
        }
        if (s.prop2 >= 0 && s.prop2 < s.n && s.parent[s.prop2] >= 0) {
            if (offhandW <= 0 || tLhP2 == null) {
                if (rt != null && rt.hasProp) {
                    double[] roundP2 = GripIK.mulNew(P, rt.prop(r.actionU));
                    double[] goalH = rt.side == 0 ? HL : HR, solvedH = rt.side == 0 ? left[3] : right[3];
                    GripIK.setPos(roundP2, GripIK.add(GripIK.pos(roundP2), GripIK.sub(GripIK.pos(solvedH), GripIK.pos(goalH))));
                    double rw = r.fpW * GripIK.smooth(r.actionW);
                    next[s.prop2] = rw >= 1 - 1e-4 ? roundP2 : interp(magazine(c, baseModel, s, D, P0, left[3]), roundP2, rw);
                    driver[s.prop2] = true;
                } else {
                    next[s.prop2] = magazine(c, baseModel, s, D, P0, left[3]);
                    driver[s.prop2] = true;
                }
            } else if (driveLeft) {
                // W12 (user report 30.10.2026, "flashlights float outside the hands"): every light is in the solved fist as
                // the third-person Harries hold has it (tLhP2). W11's lens point (10 cm right of, 6 cm below and 12 cm ahead
                // of the weapon frame) moved Bip01_Prop2 alone, up to 21 cm off the hand: the light lay on the open hand.
                double[] offhandP2 = GripIK.mulNew(left[3], tLhP2);
                next[s.prop2] = offhandW >= 1 - 1e-4 ? offhandP2 : interp(magazine(c, baseModel, s, D, P0, left[3]), offhandP2, offhandW);
                driver[s.prop2] = true;
            }
        }
        r.torchOffhandW = offhandW;
        if (coneTorch && next[s.prop2] != null) {
            r.torchHandDistCm = GripIK.dist(GripIK.pos(left[3]), GripIK.pos(right[3])) / CM;
            double[] dHl = GripIK.rot(P, HARRIES_HR_TO_HL_P1);
            double[] expLhPos = GripIK.add(GripIK.pos(right[3]), dHl);
            r.torchHlRelErrCm = GripIK.dist(GripIK.pos(left[3]), expLhPos) / CM;
            double[] p2 = next[s.prop2];
            double[] beam = isAngleTorchMask(offhandMask) ? new double[] {-p2[2], -p2[6], -p2[10]} : new double[] {p2[1], p2[5], p2[9]};
            beam = GripIK.normalize(beam);
            double[] bore = GripIK.normalize(new double[] {P[1], P[5], P[9]});
            r.torchBeamDeg = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, GripIK.dot(beam, bore)))));
        } else {
            r.torchHandDistCm = Double.NaN;
            r.torchHlRelErrCm = Double.NaN;
            r.torchBeamDeg = Double.NaN;
        }
        int[][] fingers = {{s.lf0, s.lf1}, {s.rf0, s.rf1}};
        int[] handBones = {s.lh, s.rh};
        double[][] solvedHands = {left[3], right[3]}, handTargets = {HL, HR};
        // FB2-E: on a bare handguard the support fingers fit its cross-section from the family's rest (GripIK.wrapFingers)
        double[][][] wrapFit = new double[2][][];
        GripIK.Request grip = GripIK.requests.get(c);
        boolean roundLoadingL = rt != null && rt.side == 0 && GripIK.smooth(r.actionW) * rt.leave(r.actionU) > 0.25;
        if (!roundLoadingL && fam != null && (bareContact || holdContact) && grip != null && grip.wrap != null && r.fingerFit[0] > 1e-4 && s.lf0 >= 0 && s.lf1 >= 0) {
            double[] hand = GripIK.inverse(baseModel[s.lh]);
            double[][] t = {GripIK.pos(GripIK.mulNew(hand, baseModel[s.lf0])), GripIK.pos(GripIK.mulNew(hand, baseModel[s.lf1]))};
            double[][] rest = grip.hold != null && grip.hold.rest != null ? new double[][] {grip.hold.rest[0].clone(), grip.hold.rest[1].clone()}
                    : new double[][] {GripIK.unitRotation(fam.fingers[c.isFemale() ? 1 : 0][0][0]), GripIK.unitRotation(fam.fingers[c.isFemale() ? 1 : 0][0][1])};
            // The surface-selected palm uses GripIK's matching finger rests. Mixing that palm with the weapon
            // family's source thumb can turn the thumb through the handguard even when the wrist is correct.
            FpPoseData.Family surfaceFamily = r.patrolSurfaceW > 0 && (grip.hold == null || grip.hold.rest == null)
                    ? data.byClass.get(GripIK.BARE_CLASS) : null;
            if (surfaceFamily != null && surfaceFamily.hasIdle()) {
                for (int k = 0; k < 2; k++) {
                    rest[k] = GripIK.slerp(rest[k], GripIK.unitRotation(surfaceFamily.fingers[c.isFemale() ? 1 : 0][0][k]), surfaceBlend);
                }
            }
            double[] hp = GripIK.mulNew(GripIK.inverse(P), left[3]);
            wrapFit[0] = GripIK.wrapFingers(grip.hold != null ? GripIK.mulNew(grip.hold.frameInv, hp) : hp, t, rest, false, grip.wrap, grip.fpWrapState, t0);
        }
        // Pistol in both hands (TW/PistolGripData): the fingers round this class's grip, from the free hand's natural rest by
        // the support hand's contact (handgunContact: Tarkov's glock patrol takes it 9.6 cm off the grip) and by where its
        // knuckles actually ended last frame (handgunReach: off the grip the fingers go back to the rest too)
        boolean hg = fam != null && r.hgFingers != null && GripIK.handgunGrip;
        r.hgReach = hg ? handgunReach(r.hgReach, r.hgMiss, r.hgReachAt == 0 || t0 - r.hgReachAt > RESET_NS ? 0 : (t0 - r.hgReachAt) / 1e9) : 1;
        r.hgReachAt = t0;
        r.hgW[0] = hg ? (offhandW <= 0 ? handgunContact(fam.wrists[c.isFemale() ? 1 : 0][0], r.handDelta) * r.hgReach
                : handgunContact(fam.wrists[c.isFemale() ? 1 : 0][0], r.handDelta) * r.hgReach * (1 - offhandW)) : 0;
        r.hgW[1] = hg ? 1 : 0;
        for (int side = 0; side < 2; side++) {
            for (int k = 0; k < 2 && fam != null && r.fingerFit[side] > 1e-4; k++) {
                int b = fingers[side][k];
                if (b >= 0) {
                    double[] fit = wrapFit[side] != null ? rotation4(wrapFit[side][k]) : fam.fingers[c.isFemale() ? 1 : 0][side][k];
                    if (wrapFit[side] == null && r.hgW[side] > 1e-4) {
                        fit = rotation4(GripIK.slerp(GripIK.unitRotation(fit), r.hgFingers[side * 2 + k], r.hgW[side]));
                    }
                    next[b] = finger(baseModel[handBones[side]], baseModel[b], solvedHands[side], fit, r.fingerFit[side]);
                    driver[b] = true;
                }
            }
        }
        if (harriesTorch) {
            double[][] hf = harriesFingers(offhandMask);
            double fw = r.fpW * offhandW;
            for (int k = 0; k < 2 && fw > 1e-4; k++) {
                int b = fingers[0][k];
                if (b >= 0) {
                    next[b] = finger(baseModel[s.lh], baseModel[b], left[3], rotation4(hf[k]), fw);
                    driver[b] = true;
                }
            }
        }
        write(ap, s, baseModel, next, driver);
        if (bonesProbe) {
            double[] solved = GripIK.mulNew(GripIK.inverse(P), left[3]);
            StringBuilder contact = new StringBuilder(probeIn).append(" fit_solved_H=");
            for (int i = 0; i < 12; i++) {
                if (i > 0) contact.append(',');
                contact.append(String.format(java.util.Locale.ROOT, "%.8f", solved[i]));
            }
            probeIn = contact.toString();
            bones = boneProbe(ap, s);
        }
        r.knuckleMiss = 0;
        r.hgMiss = 0;
        for (int side = 0; side < 2; side++) {
            int b = fingers[side][1];
            if (b >= 0 && r.fingerFit[side] > .5) { // the knuckle bone against its place on the contact frame
                double[] t = GripIK.pos(GripIK.mulNew(GripIK.inverse(baseModel[handBones[side]]), baseModel[b]));
                double[] want = GripIK.point(handTargets[side], t[0], t[1], t[2]);
                double miss = GripIK.dist(want, GripIK.pos(GripIK.read(ap.getModelTransformAt(b), new double[16]))) / CM;
                r.knuckleMiss = Math.max(r.knuckleMiss, miss);
                if (side == 0) {
                    r.hgMiss = miss;
                }
            }
        }
        if (offhandW > 0.5) {
            GripIK.expected.remove(ap);
        } else {
            double[] e = GripIK.expected.get(ap);
            if (e != null) { // The FP layer owns this contact; keep the desired point, including a real reach residual.
                if (fp && fam != null) {
                    double[] contact = GripIK.point(GripIK.inverse(P), HL[3], HL[7], HL[11]);
                    System.arraycopy(contact, 0, e, 0, 3);
                }
                e[3] = left[3][3];
                e[4] = left[3][7];
                e[5] = left[3][11];
            }
        }
        applied++;
    }

    /** A stationary contact from Tarkov, mapped to this assembly by the existing grip API.
     * The source's PZ wrist axes are fixed; a PZ carry clip or VP pitch cannot move the hold.
     * Actions release continuously to their existing animation targets in place().
     */
    static double[] handContactBase(FpPoseData.Family f, Request r, boolean female, int side) {
        double[] local = f.wrists[female ? 1 : 0][side].clone();
        double[] support = side == 0 && r.launcherHold != null ? r.launcherHold[female ? 1 : 0] : r.left;
        if (r.hgHands != null && GripIK.handgunGrip) {
            local = r.hgHands[side].clone();
        } else if (side == 0 && support != null) {
            local = support.clone();
        } else if (side == 0 && r.handA != null) {
            local = GripIK.mulNew(r.handA, local);
        } else {
            local = GripIK.mulNew(GripIK.inverse(shift(f, r.weapon, r.dR)), local);
        }
        if (side == 1 && r.right != null) {
            GripIK.setPos(local, onContact(f, female, side, local, r.right));
        }
        if (side == 0) {
            if (r.supportFrom != null && r.supportFade < 1) {
                local = interp(r.supportFrom, local, GripIK.smooth(r.supportFade));
            }
            r.supportSeen = local.clone();
        }
        return local;
    }

    static double[] handContact(FpPoseData.Family f, Request r, double[] P, boolean female, int side) {
        double[] local = f.wrists[female ? 1 : 0][side].clone();
        // a deployed underbarrel: the support hand holds the launcher (launcherHold), else the assembly's contact
        double[] support = side == 0 && r.launcherHold != null ? r.launcherHold[female ? 1 : 0] : r.left;
        if (r.hgHands != null && GripIK.handgunGrip) { // pistol in both hands: the class's hands on its grip, shifted for the thumbs (tools/pistol_grip.py)
            local = r.hgHands[side].clone();
        } else if (side == 0 && support != null) {
            local = support;
        } else if (side == 0 && r.handA != null) {
            local = GripIK.mulNew(r.handA, local);
        } else {
            local = GripIK.mulNew(GripIK.inverse(shift(f, r.weapon, r.dR)), local);
        }
        if (side == 1 && r.right != null) {
            GripIK.setPos(local, onContact(f, female, side, local, r.right));
        }
        if (r.handDelta != null) {
            double[] source = f.wrists[female ? 1 : 0][side];
            local = GripIK.mulNew(GripIK.mulNew(local, GripIK.inverse(source)), GripIK.mulNew(r.handDelta[side], source));
            // Patrol rotates the support hand around its contact, moving the wrist with it.
            // Retarget the complete source motion onto the assembly's base grip; pinning only
            // the wrist afterwards swings the knuckles/palm through the handguard.
            if (side == 1 && r.right != null) GripIK.setPos(local, onContact(f, female, side, local, r.right));
        }
        if (side == 0) {
            if (r.supportFrom != null && r.supportFade < 1) {
                local = interp(r.supportFrom, local, GripIK.smooth(r.supportFade));
            }
            r.supportSeen = local.clone();
        }
        return GripIK.mulNew(P, local);
    }

    /** Pistol in both hands: the support hand's grip weight from its patrol move off the grip (source: its idle wrist; 1 on
     *  the grip, 0 from HG_FAR off it). */
    static final double HG_NEAR = 2 * CM, HG_FAR = 6 * CM;

    /** The support grip's weight by its knuckles' miss of their place on the grip (cm): 1 within HG_NEAR, 0 past HG_FAR, followed
     *  over GripIK.HG_TAU_S from w (dt seconds; 0: at once). Revolver ADS misses ~3 cm (weight ~0.8), hip fire at -85 12-14 cm (0). */
    static double handgunReach(double w, double missCm, double dt) {
        double want = 1 - GripIK.smooth((missCm * CM - HG_NEAR) / (HG_FAR - HG_NEAR));
        return dt <= 0 ? want : w + (want - w) * (1 - Math.exp(-dt / GripIK.HG_TAU_S));
    }

    static double handgunContact(double[] source, double[][] handDelta) {
        if (handDelta == null) {
            return 1;
        }
        double away = GripIK.dist(GripIK.pos(GripIK.mulNew(handDelta[0], source)), GripIK.pos(source));
        return 1 - GripIK.smooth((away - HG_NEAR) / (HG_FAR - HG_NEAR));
    }

    /**
     * The hand bone's place for an assembly contact (TW/GripTargets: the source contact moved onto this
     * weapon): the fitted hand frame lies off its source contact (FpPoseData contacts) as in the source,
     * in the hand's own axes. hand: the frame's rotation; contact: the assembly contact point.
     */
    static double[] onContact(FpPoseData.Family f, boolean female, int side, double[] hand, double[] contact) {
        double[] c = f.contacts[female ? 1 : 0][side];
        double[] o = GripIK.point(GripIK.inverse(f.wrists[female ? 1 : 0][side]), c[0], c[1], c[2]);
        return GripIK.sub(contact, GripIK.sub(GripIK.point(hand, o[0], o[1], o[2]), GripIK.pos(hand)));
    }

    /**
     * A finger bone on its solved hand: the animation's local (translation and scale kept) with its
     * rotation turned towards Tarkov's (fit, relative to the hand) by w.
     */
    /** FB2-E: a row-major 3x3 as a 4x4 transform without translation (the form of FpPoseData fingers). */
    static double[] rotation4(double[] r) {
        return new double[] {r[0], r[1], r[2], 0, r[3], r[4], r[5], 0, r[6], r[7], r[8], 0, 0, 0, 0, 1};
    }

    static double[] finger(double[] hand, double[] finger, double[] handNow, double[] fit, double w) {
        double[] local = GripIK.mulNew(GripIK.inverse(hand), finger);
        double[] target = local.clone();
        GripIK.setRotation(target, GripIK.unitRotation(fit), GripIK.columnScales(local));
        return GripIK.mulNew(handNow, interp(local, target, w));
    }

    /** FB4 (the user's item 21): the share of the support hand's reach the pull takes over - the high ready /
     * retreat weight past pullUntil (ready), outside ADS. */
    static double glue(double ready, double adsW) {
        return GripIK.clamp(ready) * (1 - GripIK.clamp(adsW));
    }

    /** FB4 (the user's item 21): the slide back along the bore the reach search still counts on. */
    static double glueBack(double supportBack, double glue) {
        return supportBack * (1 - glue);
    }

    /** Minimal body retarget on the measured forend/pump interval from GripTargets params[61].
     * An insufficient interval remains an explicit contact miss; no stretched bone or moved ADS.
     */
    static double[] supportReach(double[][] model, Skeleton s, double[] hand, double[] P, double back, double[] eye) {
        if (back <= 0 || supportMiss(model, s, hand, eye) <= 1e-7) return hand;
        double[] bore = GripIK.normalize(new double[] {P[1], P[5], P[9]});
        double[] end = hand.clone();
        GripIK.setPos(end, GripIK.sub(GripIK.pos(hand), GripIK.scale(bore, back)));
        if (supportMiss(model, s, end, eye) >= supportMiss(model, s, hand, eye)) return hand;
        if (supportMiss(model, s, end, eye) > 1e-7) return end;
        double lo = 0, hi = back;
        for (int i = 0; i < 20; i++) {
            double mid = (lo + hi) / 2;
            double[] target = hand.clone();
            GripIK.setPos(target, GripIK.sub(GripIK.pos(hand), GripIK.scale(bore, mid)));
            if (supportMiss(model, s, target, eye) > 1e-7) lo = mid; else hi = mid;
        }
        double[] out = hand.clone();
        GripIK.setPos(out, GripIK.sub(GripIK.pos(hand), GripIK.scale(bore, hi)));
        return out;
    }

    static double reachMiss(double[][] model, Skeleton s, double[] goal) {
        return supportMiss(model, s, goal, null);
    }

    /** Necessary reach and elbow-circle clearance, with a margin derived from the solver's angular step. */
    static double supportMiss(double[][] model, Skeleton s, double[] goal, double[] eye) {
        return FpPoseReach.miss(model, s, goal, eye, 0);
    }

    /** FB7 item 2: r.obstacleNow towards r.obstacle (OBSTACLE_OMEGA, OBSTACLE_RATE); a reset starts at the ratio itself. */
    static void obstacleStep(Request r, long t0) {
        double target = r.obstacle;
        if (r.obstacleAt == 0 || t0 - r.obstacleAt > RESET_NS) {
            r.obstacleNow = target;
            r.obstacleRate = 0;
            r.obstacleAt = t0;
            return;
        }
        double dt = Math.min(.05, (t0 - r.obstacleAt) / 1e9);
        r.obstacleAt = t0;
        if (dt <= 0) {
            return;
        }
        // critically damped: x(t) = (x0 + (v0 + w x0) t) e^-wt about the target, v(t) its derivative
        double x = r.obstacleNow - target, v = r.obstacleRate, w = OBSTACLE_OMEGA, e = Math.exp(-w * dt), c = v + w * x;
        double next = target + (x + c * dt) * e, rate = (v - w * c * dt) * e;
        r.obstacleNow = Math.max(0, Math.min(1, r.obstacleNow + Math.max(-OBSTACLE_RATE * dt, Math.min(OBSTACLE_RATE * dt, next - r.obstacleNow))));
        r.obstacleRate = Math.max(-OBSTACLE_RATE, Math.min(OBSTACLE_RATE, rate));
        if (target <= 0 && r.obstacleNow < 1e-4) {
            r.obstacleNow = 0;
            r.obstacleRate = 0;
        }
    }

    static double advanceBody(double current, double goal, double cap, double dt) {
        double k = 1 - Math.exp(-Math.max(0, Math.min(.05, dt)) / STANCE_TAU_S);
        return Math.max(-cap, Math.min(cap, current + (goal - current) * k));
    }

    static double reach(double[][] model, int ua, int fa, int h) {
        return GripIK.dist(GripIK.pos(model[fa]), GripIK.pos(model[ua])) + GripIK.dist(GripIK.pos(model[h]), GripIK.pos(model[fa])) - GripIK.REACH_MARGIN;
    }

    /** Minimal body-forward correction of a colliding shoulder-launcher wrist.
     * Its sphere uses GripIK's existing body radii/tolerance, with no model scale,
     * sight displacement or per-weapon hand offset. The source contact wins when clear.
     */
    static double[] clearLauncherWrist(double[][] model, Skeleton s, double[] hand, double[][] torso) {
        double[] p = GripIK.pos(hand);
        double d0 = GripIK.torsoDepth(model, s.chain, p, GripIK.R_WRIST, torso);
        if (d0 <= GripIK.CLEAR_TOL) {
            return hand;
        }
        double[] chest = model[s.chain[2]];
        double[] forward = GripIK.normalize(new double[] {chest[2], chest[6], chest[10]});
        double lo = 0, hi = GripIK.MAX_SLIDE, shift;
        double dHi = GripIK.torsoDepth(model, s.chain, GripIK.add(p, GripIK.scale(forward, hi)), GripIK.R_WRIST, torso);
        if (dHi > GripIK.CLEAR_TOL) {
            if (dHi >= d0) {
                return hand;
            }
            shift = hi * (1 - GripIK.smooth((dHi - GripIK.CLEAR_TOL) / (2 * FP_SOFT_PASS)));
        } else {
            for (int i = 0; i < 18; i++) {
                double mid = (lo + hi) / 2;
                if (GripIK.torsoDepth(model, s.chain, GripIK.add(p, GripIK.scale(forward, mid)), GripIK.R_WRIST, torso) > GripIK.CLEAR_TOL) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            shift = hi;
        }
        if (shift <= 0) {
            return hand;
        }
        double[] out = hand.clone();
        GripIK.setPos(out, GripIK.add(p, GripIK.scale(forward, shift)));
        return out;
    }

    /** The outward directions of both elbows in the retreat (character's left +X, right -X in model space of the chest), weighted. */
    static double[] elbowOut(double[][] model, Skeleton s, double rho, double amount) {
        if (rho <= 0 || amount <= 0) {
            return null;
        }
        double[] chest = model[s.chain[2]];
        double[] left = GripIK.normalize(new double[] {chest[1], chest[5], chest[9]}); // spine local Y: to the left (GripIK.torsoDepth)
        return GripIK.scale(left, Math.min(1, rho) * amount / (7 * CM));
    }

    /** Elbow pole: towards Tarkov's bend goal (eye-frame goal placed by the eye), plus the retreat's outward push. */
    static double[] pole(double[] goals, int o, double[] shoulder, double[] hand, double[] out, int side) {
        double[] p = null;
        if (goals != null) {
            double[] mid = GripIK.lerp(GripIK.pos(shoulder), GripIK.pos(hand), 0.5);
            p = GripIK.normalize(GripIK.sub(new double[] {goals[o], goals[o + 1], goals[o + 2]}, mid));
        }
        if (out != null) {
            double[] o2 = side == 0 ? out : GripIK.scale(out, -1);
            p = p == null ? o2 : GripIK.add(p, o2);
        }
        return p == null || GripIK.norm(p) < 1e-9 ? null : GripIK.normalize(p);
    }

    /**
     * FB7 item 2: the goal a two-bone solve gets from the shoulder UA: beyond full reach less soft its distance
     * approaches the full reach exponentially (soft IK) - the same goal inside, continuous and smooth at the seam.
     */
    static double[] softGoal(double[] UA, double[] FA, double[] H, double[] goal, double soft) {
        double[] sh = GripIK.pos(UA), to = GripIK.pos(goal);
        double full = GripIK.dist(sh, GripIK.pos(FA)) + GripIK.dist(GripIK.pos(FA), GripIK.pos(H));
        double d = GripIK.dist(sh, to), reach = softReach(d, full, soft);
        if (reach >= d) {
            return goal;
        }
        double[] out = goal.clone();
        GripIK.setPos(out, GripIK.add(sh, GripIK.scale(GripIK.sub(to, sh), reach / d)));
        return out;
    }

    /**
     * FB7 item 2, first person: the clavicle swing (radians, at most max) that brings the goal to the distance
     * clavicleReach(d) from the shoulder. GripIK.clavicleAngle swings only past the reach, by exactly the excess: its
     * angle left 0 with a finite slope, so a running pistol's support elbow (QA run-fp2: high and low run) extended at
     * ~1 cm a frame and stopped dead at 159.3 degrees - where that reach and the soft reach meet - and started again the
     * same way (elbow pops of 1.0-1.6 cm in the camera's frame). The swing now starts CLAVICLE_SOFT before the reach and
     * takes the excess over smoothly (C1): the elbow eases into the same extension.
     */
    static double clavicleSoft(double[] LC, double[] UA, double[] FA, double[] H, double[] goal, double max) {
        double reach = GripIK.dist(GripIK.pos(FA), GripIK.pos(UA)) + GripIK.dist(GripIK.pos(H), GripIK.pos(FA)) - GripIK.REACH_MARGIN;
        double[] s = GripIK.pos(UA), c = GripIK.pos(LC);
        double want = clavicleReach(GripIK.dist(goal, s), reach);
        if (GripIK.dist(goal, s) <= want) {
            return 0;
        }
        double[] axis = GripIK.cross(GripIK.sub(s, c), GripIK.sub(goal, c));
        if (GripIK.norm(axis) < 1e-9) {
            return 0;
        }
        axis = GripIK.normalize(axis);
        double[] v = GripIK.sub(s, c);
        if (GripIK.dist(goal, GripIK.add(c, GripIK.rotateVec(axis, max, v))) > want) {
            return max;
        }
        double lo = 0, hi = max;
        for (int i = 0; i < 30; i++) {
            double mid = (lo + hi) / 2;
            if (GripIK.dist(goal, GripIK.add(c, GripIK.rotateVec(axis, mid, v))) > want) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return hi;
    }

    /** FB7 item 2: the distance the clavicle brings a goal d from the shoulder to: d up to CLAVICLE_SOFT before the
     * reach, beyond it approaching the reach exponentially (slope 1 at the start, so no kink). */
    static double clavicleReach(double d, double reach) {
        double start = reach - CLAVICLE_SOFT;
        return start <= 0 || d <= start ? d : start + CLAVICLE_SOFT * (1 - Math.exp(-(d - start) / CLAVICLE_SOFT));
    }

    /**
     * The soft zone of an arm's reach: SOFT_REACH; with a deployed launcher both hands are hard holds (check_launcher_reloads:
     * exact, the support grip and the trigger hand) - the clavicle (and supportReach) bring them within REACH_MARGIN of full
     * reach, where SOFT_REACH's zone met them 0.27 cm short (high ready, ADS 350-400 m), so only that margin is soft.
     */
    static double softZone(Request r, int side) {
        return r.launcherHold != null ? GripIK.REACH_MARGIN : SOFT_REACH;
    }

    /** The distance a goal d from the shoulder is reached at by an arm of the full length (soft IK over the last soft; d itself inside). */
    static double softReach(double d, double full, double soft) {
        double start = full - soft;
        return start <= 0 || d <= start ? d : start + soft * (1 - Math.exp(-(d - start) / soft));
    }

    /** One arm to the hand target: clavicle assist, two-bone solve (no stretch), the elbow out of the torso. {clavicle, upper, fore,
     *  hand, the elbow's side across shoulder-wrist (ArmNatural.solved: a straight arm's plane)}. */
    static double[][] arm(double[][] model, Skeleton s, int cl, int ua, int fa, double[] goal, double[] pole, double[][] torso,
                          Request r, int side, boolean fresh, double dt) {
        int hand = side == 0 ? s.lh : s.rh;
        double[] LC = model[cl], UA = model[ua], FA = model[fa], H = model[hand];
        // the soft swing for a pistol in both hands only: a rifle's support arm runs at the clavicle's limit throughout
        // (low run: elbow at a constant 159.3 degrees), where the soft start only moved the shoulder 0.7 cm nearer and
        // added its swing to the stride (QA run-fp3: rifle low-run elbow pops 0.36-0.44 -> 0.53-0.57 cm)
        double theta = r.mode.startsWith("fp") && r.hgHands != null ? clavicleSoft(LC, UA, FA, H, GripIK.pos(goal), GripIK.CLAVICLE_MAX_AIM)
                : GripIK.clavicleAngle(LC, UA, FA, H, GripIK.pos(goal),
                r.mode.startsWith("fp") ? GripIK.CLAVICLE_MAX_AIM : GripIK.CLAVICLE_MAX);
        if (theta > 0) {
            double[] axis = GripIK.cross(GripIK.sub(GripIK.pos(UA), GripIK.pos(LC)), GripIK.sub(GripIK.pos(goal), GripIK.pos(LC)));
            if (GripIK.norm(axis) > 1e-9) {
                double[] rigid = GripIK.rigid(GripIK.normalize(axis), theta, GripIK.pos(LC));
                LC = GripIK.mulNew(rigid, LC);
                UA = GripIK.mulNew(rigid, UA);
                FA = GripIK.mulNew(rigid, FA);
                H = GripIK.mulNew(rigid, H);
                clavicles++;
            }
        }
        double[] UAn = new double[16], FAn = new double[16], Hn = new double[16];
        double[] soft = softGoal(UA, FA, H, goal, softZone(r, side));
        GripIK.solveArm(UA, FA, H, soft, UAn, FAn, Hn, pole, pole == null ? 0 : POLE_K);
        double[] elbowSide = GripIK.armPole(UA, FA, soft, pole, pole == null ? 0 : POLE_K);
        // W9 item 12: natural-first body clearance - bounded elbow swing, wrist flexion/twist and shoulder height;
        // where no natural turn clears, taper smoothly to 0 over FP_SOFT_PASS so the arm passes naturally through the torso.
        ArmNatural.Bind bind = s.binds == null ? null : s.binds[side];
        double[] up = s.chain != null && s.chain.length >= 4 && s.chain[0] >= 0 && s.chain[3] >= 0
                && s.chain[0] < model.length && s.chain[3] < model.length && model[s.chain[0]] != null && model[s.chain[3]] != null
                ? GripIK.normalize(GripIK.sub(GripIK.pos(model[s.chain[3]]), GripIK.pos(model[s.chain[0]])))
                : new double[] {0, 1, 0};
        wristSwivel(UAn, FAn, Hn, elbowSide, bind, up, r, side, fresh, dt);
        final boolean swActive = side == 0 && r.mode.startsWith("fp")
                && (Math.abs(r.wristSwivel[0]) > 1e-6 || r.wristPole[0] != null || r.wristWrapped[0]
                || (r.eyeModel != null && r.eyeModel[6] > 0.42));
        // W9 item 12: natural-first body clearance - bounded elbow swing, wrist flexion/twist and shoulder height;
        // where no natural turn clears, taper smoothly to 0 over FP_SOFT_PASS so the arm passes naturally through the torso.
        double[] sh = GripIK.pos(UAn), el = GripIK.pos(FAn), wr = GripIK.pos(Hn);
        double[] axis = GripIK.normalize(GripIK.sub(wr, sh));
        final double elevCap = Math.max(FP_CLEAR_SHOULDER_CM, GripIK.dot(GripIK.sub(el, sh), up) + 0.5 * CM);
        final double gaze0 = Math.max(0, gazeDepth(el, r, side));
        final double bend0 = bind == null ? 0 : wristBend(FAn, Hn);
        final double bendCap = swActive && bend0 <= GripIK.MAX_BEND - 1e-4
                ? Math.min(GripIK.MAX_BEND - 1e-4, Math.max(FP_CLEAR_BEND_MAX, bend0 + 1e-6))
                : Math.max(FP_CLEAR_BEND_MAX, bend0 + 1e-6);
        final double prevTau = !fresh && Double.isFinite(r.reliefTwist[side]) ? r.reliefTwist[side] : Double.NaN;
        final double tau0 = bind == null ? 0 : GripIK.continuousTwist(ArmNatural.tau(UAn, FAn, Hn, bind), prevTau, !fresh);
        final double tauCap = Math.max(Math.abs(tau0) + Math.toRadians(5), FP_CLEAR_TWIST_MAX);
        final double[] fsh = sh, fel = el, fwr = wr, faxis = axis, fUAn = UAn, fFAn = FAn, fHn = Hn, fup = up;
        java.util.function.DoubleUnaryOperator depthAt = turn -> armDepth(model, s, fsh,
                GripIK.add(fsh, GripIK.rotateVec(faxis, turn, GripIK.sub(fel, fsh))), fwr, torso, r, side);
        java.util.function.DoublePredicate naturalTurn = turn -> {
            if (Math.abs(turn) < 1e-9) {
                return true;
            }
            if (Math.abs(turn) > FP_CLEAR_NATURAL_MAX + 1e-9) {
                return false;
            }
            double[] elT = GripIK.add(fsh, GripIK.rotateVec(faxis, turn, GripIK.sub(fel, fsh)));
            if (GripIK.dot(GripIK.sub(elT, fsh), fup) > elevCap) {
                return false;
            }
            if (gazeDepth(elT, r, side) > gaze0 + 1e-6) {
                return false;
            }
            if (bind != null) {
                double[] rot = GripIK.rigid(faxis, turn, fsh);
                double[] fT = GripIK.mulNew(rot, fFAn);
                if (wristBend(fT, fHn) > bendCap) {
                    return false;
                }
                double tw = swActive
                        ? unwrappedTurnTwist(fUAn, fFAn, fHn, bind, faxis, fsh, turn, tau0)
                        : GripIK.continuousTwist(ArmNatural.tau(GripIK.mulNew(rot, fUAn), fT, fHn, bind), tau0, true);
                if (Math.abs(tw) > tauCap) {
                    return false;
                }
            }
            return true;
        };
        double first = !fresh && r.elbowSide[side] != 0 ? r.elbowSide[side] : side == 0 ? -1 : 1;
        boolean fpClear = !fresh && r.mode.startsWith("fp");
        java.util.function.DoubleUnaryOperator wanted = fpClear ? turn -> depthAt.applyAsDouble(turn) + FP_CLEAR_MARGIN : depthAt;
        double need = naturalClearTurn(wanted, depthAt, naturalTurn, fresh ? Double.NaN : r.elbowTurn[side], first, fpClear);
        if (need != 0 && depthAt.applyAsDouble(need) <= 0) {
            r.elbowSide[side] = Math.signum(need);
        }
        double negNat = 0, posNat = 0;
        if (swActive) {
            negNat = naturalLimit(naturalTurn, -1);
            posNat = naturalLimit(naturalTurn, +1);
        } else {
            for (double mag = Math.toRadians(5); mag <= FP_CLEAR_NATURAL_MAX + 1e-9; mag += Math.toRadians(5)) {
                if (!naturalTurn.test(-mag)) break;
                negNat = -mag;
            }
            for (double mag = Math.toRadians(5); mag <= FP_CLEAR_NATURAL_MAX + 1e-9; mag += Math.toRadians(5)) {
                if (!naturalTurn.test(mag)) break;
                posNat = mag;
            }
        }
        double safe = fpClear && depthAt.applyAsDouble(need) <= 0 ? coreClear(depthAt, need) : need;
        if (fresh) {
            r.elbowTurn[side] = need;
            r.elbowHold[side] = need;
            r.elbowHoldS[side] = 0;
        } else if (fpClear) {
            r.elbowHold[side] = Math.max(negNat, Math.min(posNat, r.elbowHold[side]));
            double nextTurn = Math.max(negNat, Math.min(posNat, fpClearTurn(r, side, need, safe, depthAt, dt)));
            if (swActive) {
                double maxSnap = FP_SWIVEL_SPEED * dt;
                if (Math.abs(nextTurn - r.elbowTurn[side]) > maxSnap) {
                    double softLim = r.elbowTurn[side] + Math.copySign(maxSnap, nextTurn - r.elbowTurn[side]);
                    double hardBendCap = Math.max(GripIK.MAX_BEND + Math.toRadians(0.5), bend0 + 1e-6);
                    double stepTauCap = Math.max(tauCap, Math.toRadians(165));
                    double stepElevCap = GripIK.dot(GripIK.sub(fel, fsh), fup) > 4 * CM ? elevCap + 2.5 * CM : elevCap;
                    double stepGazeCap = GripIK.dot(GripIK.sub(fel, fsh), fup) > 4 * CM ? gaze0 + 2.5 * CM : gaze0 + 1e-6;
                    java.util.function.DoublePredicate stepOk = cand -> {
                        if (Math.abs(cand) > FP_CLEAR_NATURAL_MAX + 1e-9) return false;
                        double[] elS = GripIK.add(fsh, GripIK.rotateVec(faxis, cand, GripIK.sub(fel, fsh)));
                        if (GripIK.dot(GripIK.sub(elS, fsh), fup) > stepElevCap || gazeDepth(elS, r, side) > stepGazeCap) {
                            return false;
                        }
                        if (bind != null) {
                            double[] rotS = GripIK.rigid(faxis, cand, fsh);
                            double[] fS = GripIK.mulNew(rotS, fFAn);
                            if (wristBend(fS, fHn) > hardBendCap
                                    || Math.abs(unwrappedTurnTwist(fUAn, fFAn, fHn, bind, faxis, fsh, cand, tau0)) > stepTauCap) {
                                return false;
                            }
                        }
                        return true;
                    };
                    if (stepOk.test(softLim)) {
                        nextTurn = softLim;
                    } else if (stepOk.test(nextTurn)) {
                        double lo = softLim, hi = nextTurn;
                        for (int it = 0; it < 10; it++) {
                            double mid = 0.5 * (lo + hi);
                            if (stepOk.test(mid)) hi = mid;
                            else lo = mid;
                        }
                        nextTurn = hi;
                    } else if (stepOk.test(need)) {
                        double lo = softLim, hi = need;
                        for (int it = 0; it < 10; it++) {
                            double mid = 0.5 * (lo + hi);
                            if (stepOk.test(mid)) hi = mid;
                            else lo = mid;
                        }
                        nextTurn = hi;
                    } else {
                        nextTurn = 0;
                    }
                }
            }
            r.elbowTurn[side] = nextTurn;
        } else {
            double tau = Math.abs(need) > Math.abs(r.elbowTurn[side]) ? GripIK.CLEAR_ATTACK_S : need == 0 ? GripIK.CLEAR_FREE_S : GripIK.CLEAR_RELEASE_S;
            double stepTurn = (need - r.elbowTurn[side]) * (1 - Math.exp(-dt / tau));
            r.elbowTurn[side] = Math.max(negNat, Math.min(posNat,
                    r.elbowTurn[side] + Math.max(-GripIK.CLEAR_SPEED * dt, Math.min(GripIK.CLEAR_SPEED * dt, stepTurn))));
        }
        // Close only a tiny exponential-tail lag (< FP_SAFE_SNAP) onto a same-side natural clearing turn; never jump across sides.
        if (depthAt.applyAsDouble(r.elbowTurn[side]) > 0 && depthAt.applyAsDouble(safe) <= 0
                && (fresh || (r.elbowTurn[side] * safe >= 0 && Math.abs(safe - r.elbowTurn[side]) <= FP_SAFE_SNAP))) {
            r.elbowTurn[side] = safe;
            r.elbowHold[side] = safe;
        }
        if (Math.abs(r.elbowTurn[side]) > 1e-4) {
            double[] turn = GripIK.rigid(axis, r.elbowTurn[side], sh);
            UAn = GripIK.mulNew(turn, UAn);
            FAn = GripIK.mulNew(turn, FAn);
            elbowSide = GripIK.rotateVec(axis, r.elbowTurn[side], elbowSide);
            elbowTurns++;
        }
        if (bind != null && reliefOn) { // clear arms stay clear; pass-through arms untwist freely without torso veto
            final double[] rsh = GripIK.pos(UAn), rel = GripIK.pos(FAn), rwr = GripIK.pos(Hn), rFAn = FAn;
            final double[] rax = GripIK.normalize(GripIK.sub(rwr, rsh));
            final double relevCap = elevCap;
            final double rgaze0 = Math.max(0, gazeDepth(rel, r, side));
            final boolean hasBody = s.chain != null && s.chain.length >= 4 && s.chain[0] >= 0 && s.chain[1] >= 0
                    && s.chain[2] >= 0 && s.chain[3] >= 0;
            final double dTorso0 = hasBody ? GripIK.armDepth(model, s.chain, rsh, rel, rwr, torso) : -1;
            final double wTorso = dTorso0 <= 0 ? 1 : 1 - GripIK.smooth(dTorso0 / FP_SOFT_PASS);
            java.util.function.DoubleUnaryOperator depth = turn -> {
                double[] eT = GripIK.add(rsh, GripIK.rotateVec(rax, turn, GripIK.sub(rel, rsh)));
                if (GripIK.dot(GripIK.sub(eT, rsh), fup) > relevCap) {
                    return 1;
                }
                if (Math.abs(turn) > 1e-9 && wristBend(GripIK.mulNew(GripIK.rigid(rax, turn, rsh), rFAn), fHn) > bendCap) {
                    return 1;
                }
                double dTorso = !hasBody ? -1 : dTorso0 <= 0 ? GripIK.armDepth(model, s.chain, rsh, eT, rwr, torso)
                        : dTorso0 + (GripIK.armDepth(model, s.chain, rsh, eT, rwr, torso) - dTorso0) * wTorso;
                double gd = gazeDepth(eT, r, side);
                return Math.max(dTorso, gd > rgaze0 ? gd - rgaze0 : -1);
            };
            double rawInTau = ArmNatural.tau(UAn, FAn, Hn, bind);
            double prevRelTau = swActive && !fresh && r.ads > 0.5 && Math.abs(r.wristSwivel[side]) < 1e-4 && Math.abs(rawInTau) < Math.toRadians(160.0)
                    ? rawInTau : (fresh ? Double.NaN : r.reliefTwist[side]);
            double target = swActive
                    ? relieveSwivelTwist(UAn, FAn, Hn, bind, depth, prevRelTau, fresh ? 0 : r.relief[side], FP_RELIEF_FROM)
                    : GripIK.relieveTwist(UAn, FAn, Hn, bind, depth, prevRelTau, fresh ? 0 : r.relief[side], FP_RELIEF_FROM);
            double relAng = fresh ? target : r.relief[side]
                    + Math.max(-GripIK.CLEAR_SPEED * dt, Math.min(GripIK.CLEAR_SPEED * dt, target - r.relief[side]));
            if (Math.abs(relAng) > 1e-4) {
                double[] eR = GripIK.add(rsh, GripIK.rotateVec(rax, relAng, GripIK.sub(rel, rsh)));
                double[] fR = GripIK.mulNew(GripIK.rigid(rax, relAng, rsh), FAn);
                boolean clearViolated = hasBody && dTorso0 <= 0 && GripIK.armDepth(model, s.chain, rsh, eR, rwr, torso) > 1e-5;
                double gdR = gazeDepth(eR, r, side);
                boolean gazeViolated = gdR > rgaze0 + 1e-5;
                if (GripIK.dot(GripIK.sub(eR, rsh), fup) > relevCap + 1e-9 || wristBend(fR, Hn) > bendCap + 1e-9
                        || clearViolated || gazeViolated) {
                    double lo = 0, hi = 1;
                    for (int i = 0; i < 10; i++) {
                        double mid = (lo + hi) / 2;
                        double[] eM = GripIK.add(rsh, GripIK.rotateVec(rax, relAng * mid, GripIK.sub(rel, rsh)));
                        double[] fM = GripIK.mulNew(GripIK.rigid(rax, relAng * mid, rsh), FAn);
                        boolean okClear = !hasBody || dTorso0 > 0 || GripIK.armDepth(model, s.chain, rsh, eM, rwr, torso) <= 1e-5;
                        boolean okGaze = gazeDepth(eM, r, side) <= rgaze0 + 1e-5;
                        if (GripIK.dot(GripIK.sub(eM, rsh), fup) <= relevCap + 1e-9 && wristBend(fM, Hn) <= bendCap + 1e-9
                                && okClear && okGaze) {
                            lo = mid;
                        } else {
                            hi = mid;
                        }
                    }
                    double clampedRel = relAng * lo;
                    if (swActive && !fresh && dt > 0) {
                        double hardRelBend = Math.max(GripIK.MAX_BEND + Math.toRadians(0.5), bend0 + 1e-6);
                        double maxRelStep = Math.max(GripIK.CLEAR_SPEED, FP_SWIVEL_SPEED) * dt;
                        double softRel = r.relief[side] + Math.max(-maxRelStep, Math.min(maxRelStep, clampedRel - r.relief[side]));
                        double[] eSoft = GripIK.add(rsh, GripIK.rotateVec(rax, softRel, GripIK.sub(rel, rsh)));
                        if (wristBend(GripIK.mulNew(GripIK.rigid(rax, softRel, rsh), FAn), Hn) <= hardRelBend
                                && GripIK.dot(GripIK.sub(eSoft, rsh), fup) <= relevCap + 1e-9
                                && gazeDepth(eSoft, r, side) <= rgaze0 + 1e-5) {
                            clampedRel = softRel;
                        }
                    }
                    relAng = clampedRel;
                }
            }
            r.relief[side] = relAng;
            if (Math.abs(r.relief[side]) > 1e-4) {
                double[] turn = GripIK.rigid(rax, r.relief[side], rsh);
                UAn = GripIK.mulNew(turn, UAn);
                FAn = GripIK.mulNew(turn, FAn);
                elbowSide = GripIK.rotateVec(rax, r.relief[side], elbowSide);
                reliefs++;
            }
            double rawOutTau = ArmNatural.tau(UAn, FAn, Hn, bind);
            r.reliefTwist[side] = swActive && !fresh && r.ads > 0.5 && Math.abs(r.wristSwivel[side]) < 1e-4 && Math.abs(rawOutTau) < Math.toRadians(160.0)
                    ? rawOutTau : GripIK.continuousTwist(rawOutTau, prevRelTau, !fresh);
        }
        if (swActive && Math.abs(r.wristSwivel[0]) <= 1e-6 && r.wristPole[0] == null
                && (!Double.isFinite(r.reliefTwist[0]) || Math.abs(r.reliefTwist[0]) <= Math.PI)
                && Math.abs(r.elbowTurn[0] - need) <= FP_SWIVEL_SPEED * dt + 1e-6) {
            r.wristWrapped[0] = false;
        }
        if (side == 0 || "RSHG2".equals(r.weapon) && r.mode.startsWith("fp")) {
            GripIK.limitWrist(FAn, Hn);
        }
        return new double[][] {LC, UAn, FAn, Hn, elbowSide};
    }

    /**
     * Exact physical twist reached by rigidly rotating (upper, fore) about axis through sh from 0 to angle,
     * unwrapped continuously along 0 -> 0.5 * angle -> angle from initial so single-frame candidate searches
     * never wrap across +-180 deg past continuousTwist's HALF_TURN_BAND.
     */
    static double unwrappedTurnTwist(double[] upper, double[] fore, double[] hand, ArmNatural.Bind arm,
            double[] axis, double[] sh, double angle, double initial) {
        double[] midTurn = GripIK.rigid(axis, 0.5 * angle, sh);
        double rawMid = ArmNatural.tau(GripIK.mulNew(midTurn, upper), GripIK.mulNew(midTurn, fore), hand, arm);
        double midTw = rawMid + 2 * Math.PI * Math.rint((initial - rawMid) / (2 * Math.PI));
        double[] fullTurn = GripIK.rigid(axis, angle, sh);
        double rawFull = ArmNatural.tau(GripIK.mulNew(fullTurn, upper), GripIK.mulNew(fullTurn, fore), hand, arm);
        return rawFull + 2 * Math.PI * Math.rint((midTw - rawFull) / (2 * Math.PI));
    }

    /** Swivel-aware twist relief (identical to GripIK.relieveTwist except candidate twist uses unwrappedTurnTwist). */
    static double relieveSwivelTwist(double[] upper, double[] fore, double[] hand, ArmNatural.Bind arm,
            java.util.function.DoubleUnaryOperator depth, double ref, double prev, double limit) {
        boolean held = Double.isFinite(ref);
        double[] sh = GripIK.pos(upper), wrist = GripIK.pos(hand), axis = GripIK.normalize(GripIK.sub(wrist, sh));
        double initial = GripIK.continuousTwist(ArmNatural.tau(upper, fore, hand, arm), ref, held);
        double excess = Math.max(0, Math.abs(initial) - limit);
        if (excess < 1e-4) return 0;
        double allowed = Math.max(0, depth.applyAsDouble(0)) + 1e-5;
        double score = excess * excess + GripIK.RELIEF_HOLD * prev * prev, best = 0;
        java.util.List<Double> candidates = new java.util.ArrayList<>();
        for (int degrees = 5; degrees <= 120; degrees += 5) {
            candidates.add(Math.toRadians(-degrees));
            candidates.add(Math.toRadians(degrees));
        }
        if (held && Math.abs(prev) > 1e-6 && Math.abs(prev) <= Math.toRadians(120)) {
            candidates.add(prev);
        }
        for (double angle : candidates) {
            double[] turn = GripIK.rigid(axis, angle, sh), f = GripIK.mulNew(turn, fore);
            double bend = Math.acos(Math.max(-1, Math.min(1, GripIK.dot(GripIK.normalize(GripIK.sub(wrist, GripIK.pos(f))),
                    GripIK.normalize(new double[] {hand[0], hand[4], hand[8]})))));
            if (bend > GripIK.MAX_BEND) continue;
            double tw = unwrappedTurnTwist(upper, fore, hand, arm, axis, sh, angle, initial);
            double remaining = Math.max(0, Math.abs(tw) - limit);
            double cost = remaining * remaining + .01 * angle * angle + GripIK.RELIEF_HOLD * (angle - prev) * (angle - prev);
            if (cost >= score || depth.applyAsDouble(angle) > allowed) continue;
            boolean clear = true;
            for (int part = 1; part < 5; part++) {
                if (depth.applyAsDouble(angle * part / 5) > allowed) { clear = false; break; }
            }
            if (clear) { score = cost; best = angle; }
        }
        return best;
    }

    /**
     * W9-arms2: closed-form pre-clearance wrist feasibility swivel on the shoulder-wrist IK circle
     * (cos(wristBend(theta)) = A + B * cos(theta)). Smoothly swivels the support elbow onto the below-shoulder
     * anatomical branch once bend0 > FP_WRIST_BEND_START (82.2 deg) so wristBend stays <= FP_WRIST_BEND_CAP
     * (< GripIK.MAX_BEND) and GripIK.limitWrist never clamps the support hand off the grip.
     */
    static double wristSwivel(double[] UAn, double[] FAn, double[] Hn, double[] elbowSide,
                              ArmNatural.Bind bind, double[] spineUp, Request r, int side, boolean fresh, double dt) {
        if (fresh) {
            r.wristWrapped[side] = false;
        }
        if (side != 0 || !r.mode.startsWith("fp") || "RSHG2".equals(r.weapon)) {
            r.wristSwivel[side] = 0;
            r.wristBranch[side] = 0;
            r.wristPole[side] = null;
            r.wristWrapped[side] = false;
            return 0;
        }
        double bend0 = wristBend(FAn, Hn);
        boolean patrolOrAction = r.mode.startsWith("fp patrol") || r.mode.contains(" action") || handDeltaActive(r.handDelta);
        boolean preActive = !patrolOrAction && bend0 > Math.toRadians(72.0);
        boolean tracking = !fresh && dt > 0 && r.wristPole[side] != null;
        if (!preActive && !tracking) {
            r.wristSwivel[side] = 0;
            r.wristBranch[side] = 0;
            r.wristPole[side] = null;
            return 0;
        }
        double[] sh = GripIK.pos(UAn), el0 = GripIK.pos(FAn), wr = GripIK.pos(Hn);
        double[] dVec = GripIK.sub(wr, sh);
        double d = GripIK.norm(dVec);
        if (d < 1e-6) {
            r.wristSwivel[side] = 0;
            r.wristBranch[side] = 0;
            r.wristPole[side] = null;
            return 0;
        }
        double[] axis = GripIK.scale(dVec, 1 / d);
        double[] es0 = GripIK.sub(el0, sh);
        double axial = GripIK.dot(es0, axis);
        double[] rad0 = GripIK.sub(es0, GripIK.scale(axis, axial));
        double rLen = GripIK.norm(rad0);
        double l2 = GripIK.dist(wr, el0);
        if (rLen < 1e-6 || l2 < 1e-6) {
            r.wristSwivel[side] = 0;
            r.wristBranch[side] = 0;
            r.wristPole[side] = null;
            return 0;
        }
        double[] u0 = GripIK.scale(rad0, 1 / rLen);
        double[] v0 = GripIK.cross(axis, u0);
        double cAxial = (d - axial) / l2;
        double sRad = rLen / l2;
        double[] hx = GripIK.normalize(new double[] {Hn[0], Hn[4], Hn[8]});
        double A = cAxial * GripIK.dot(axis, hx);
        double pu = -GripIK.dot(u0, hx), pv = -GripIK.dot(v0, hx);
        double B = sRad * Math.hypot(pu, pv);
        if (B < 1e-8) {
            r.wristSwivel[side] = 0;
            r.wristBranch[side] = 0;
            r.wristPole[side] = null;
            return 0;
        }
        double phiStar = Math.atan2(pv, pu);
        double[] uStar = GripIK.add(GripIK.scale(u0, Math.cos(phiStar)), GripIK.scale(v0, Math.sin(phiStar)));
        double[] vStar = GripIK.cross(axis, uStar);
        double theta0 = Math.atan2(GripIK.dot(u0, vStar), GripIK.dot(u0, uStar));

        double cosCap = Math.cos(FP_WRIST_BEND_CAP);
        boolean hasWall = cosCap > A - B;
        double thCap = !hasWall ? Math.PI : cosCap >= A + B ? 0 : Math.acos(Math.max(-1, Math.min(1, (cosCap - A) / B)));
        double cosHard = Math.cos(GripIK.MAX_BEND - Math.toRadians(0.05));
        boolean hasHardWall = cosHard > A - B;
        double thHard = !hasHardWall ? Math.PI : cosHard >= A + B ? 0 : Math.acos(Math.max(-1, Math.min(1, (cosHard - A) / B)));
        double bendApex = Math.acos(Math.max(-1.0, Math.min(1.0, A - B)));
        final double[] elevAxis = spineUp;
        double natElevCm = GripIK.dot(es0, elevAxis) / CM;
        double wrElevCm = GripIK.dot(dVec, elevAxis) / CM;
        double anatElevCapCm = Math.max(FP_CLEAR_SHOULDER_CM / CM,
                Math.max(0.30 * Math.max(0.0, wrElevCm), r.ads > 0.5 && natElevCm > 4.0 ? natElevCm : 0.0));
        double effElevCapCm = Math.max(anatElevCapCm, natElevCm + 0.4);
        double[] toEye = r.eyeModel != null ? GripIK.inverse(r.eyeModel) : null;
        double wrEyeYCm = toEye != null ? GripIK.point(toEye, wr[0], wr[1], wr[2])[1] / CM : 0.0;
        double rawElEyeYCm = toEye != null ? GripIK.point(toEye, el0[0], el0[1], el0[2])[1] / CM : 0.0;
        double sightCeilTrimY = Math.max(rawElEyeYCm, Math.max(-3.5, wrEyeYCm + 1.5));
        final double vTan = 0.767;
        final double hTan = r.ads > 0.5 ? 1.05 : 1.38;

        java.util.function.DoubleUnaryOperator spAt = th -> {
            double[] u = GripIK.add(GripIK.scale(uStar, Math.cos(th)), GripIK.scale(vStar, Math.sin(th)));
            double[] e = GripIK.add(GripIK.scale(axis, axial), GripIK.scale(u, rLen));
            return GripIK.dot(e, elevAxis) / CM;
        };
        java.util.function.DoublePredicate frustumOk = th -> {
            if (toEye == null) return true;
            double[] u = GripIK.add(GripIK.scale(uStar, Math.cos(th)), GripIK.scale(vStar, Math.sin(th)));
            double[] el = GripIK.add(sh, GripIK.add(GripIK.scale(axis, axial), GripIK.scale(u, rLen)));
            double[] eEl = GripIK.point(toEye, el[0], el[1], el[2]);
            double ex = eEl[0] / CM, ey = eEl[1] / CM, dep = -eEl[2] / CM;
            if (ex < 4.5 && ey > sightCeilTrimY) return false;
            if (ex < -9.5) return false;
            return !(dep > 8.0 && Math.abs(ex) < hTan * dep + 1.5 && ey > -vTan * dep - 1.5);
        };
        java.util.function.IntToDoubleFunction trimElev = cs -> {
            double baseMag = Math.min(thCap, Math.toRadians(115.0));
            double wTh = cs * baseMag;
            if ((spAt.applyAsDouble(wTh) > effElevCapCm || !frustumOk.test(wTh)) && baseMag > Math.toRadians(15.0)) {
                double minMag = Math.max(Math.toRadians(15.0), baseMag - Math.toRadians(60.0));
                double sample = cs * minMag;
                if (spAt.applyAsDouble(sample) <= effElevCapCm && frustumOk.test(sample)) {
                    double lo = wTh, hi = sample;
                    for (int it = 0; it < 10; it++) {
                        double mid = 0.5 * (lo + hi);
                        if (spAt.applyAsDouble(mid) <= effElevCapCm && frustumOk.test(mid)) hi = mid;
                        else lo = mid;
                    }
                    wTh = hi;
                }
            }
            return wTh;
        };

        double prevTheta = theta0;
        if (tracking) {
            double[] pRad = GripIK.sub(r.wristPole[side], GripIK.scale(axis, GripIK.dot(r.wristPole[side], axis)));
            if (GripIK.norm(pRad) > 1e-6) {
                prevTheta = Math.atan2(GripIK.dot(pRad, vStar), GripIK.dot(pRad, uStar));
            }
        }
        int prevBranch = tracking ? r.wristBranch[side] : 0;
        final double swTau0 = bind == null || r.ads <= 0.5 ? Double.NaN : GripIK.continuousTwist(
                ArmNatural.tau(UAn, FAn, Hn, bind),
                !fresh && Double.isFinite(r.reliefTwist[side]) ? r.reliefTwist[side] : Double.NaN, !fresh);

        double lookThP = trimElev.applyAsDouble(1), lookThM = trimElev.applyAsDouble(-1);
        double cLookPlus = wristSwivelCost(lookThP, theta0, sh, wr, axis, axial, rLen, uStar, vStar, UAn, FAn, Hn, bind, swTau0, elevAxis, natElevCm, anatElevCapCm, toEye, hTan, vTan);
        double cLookMinus = wristSwivelCost(lookThM, theta0, sh, wr, axis, axial, rLen, uStar, vStar, UAn, FAn, Hn, bind, swTau0, elevAxis, natElevCm, anatElevCapCm, toEye, hTan, vTan);
        int lookBranch = cLookPlus <= cLookMinus ? 1 : -1;
        boolean needEarlyCross = !patrolOrAction && bend0 > Math.toRadians(75.0)
                && bendApex > Math.toRadians(80.5) && Math.abs(theta0) > Math.toRadians(125.0)
                && (lookBranch * theta0 < 0);
        boolean zeroPathClear = spAt.applyAsDouble(0.0) <= effElevCapCm + 0.6 && frustumOk.test(0.0)
                && (r.ads <= 0.5 || (frustumOk.test(Math.toRadians(45.0)) && frustumOk.test(Math.toRadians(-45.0))));
        boolean active = !patrolOrAction && (bend0 > FP_WRIST_BEND_START || needEarlyCross);
        if (!active && !tracking) {
            r.wristSwivel[side] = 0;
            r.wristBranch[side] = 0;
            r.wristPole[side] = null;
            return 0;
        }

        double[] candTh = new double[2];
        double thMag;
        {
            double wantBend = bend0 > FP_WRIST_BEND_START ? softReach(bend0, FP_WRIST_BEND_CAP, FP_WRIST_BEND_SOFT) : FP_WRIST_BEND_CAP;
            double cosWant = Math.cos(wantBend);
            thMag = Math.min(thCap, cosWant <= A - B ? Math.PI : cosWant >= A + B ? 0 : Math.acos(Math.max(-1, Math.min(1, (cosWant - A) / B))));
            for (int si = 0; si < 2; si++) {
                int cs = si == 0 ? 1 : -1;
                double wTh = cs * thMag;
                double spWTh = spAt.applyAsDouble(wTh);
                if ((spWTh > effElevCapCm || (r.ads > 0.5 && !frustumOk.test(wTh))) && thMag > Math.toRadians(12.0)) {
                    double minMag = Math.max(Math.toRadians(15.0), thMag - Math.toRadians(55.0));
                    double sample = cs * minMag;
                    double spSample = spAt.applyAsDouble(sample);
                    if (frustumOk.test(sample)) {
                        if (spSample <= effElevCapCm) {
                            double lo = wTh, hi = sample;
                            for (int it = 0; it < 12; it++) {
                                double mid = 0.5 * (lo + hi);
                                if (spAt.applyAsDouble(mid) <= effElevCapCm && frustumOk.test(mid)) hi = mid;
                                else lo = mid;
                            }
                            wTh = hi;
                        } else if (spWTh > effElevCapCm && spSample < spWTh) {
                            double tTrim = Math.min(1.0, (spWTh - spSample) / 1.5);
                            wTh = wTh + tTrim * (sample - wTh);
                        }
                    }
                }
                if (r.ads > 0.5 && bind != null && Double.isFinite(swTau0) && thHard > Math.abs(wTh)) {
                    double twDeg = Math.abs(Math.toDegrees(unwrappedTurnTwist(UAn, FAn, Hn, bind, axis, sh, wrapSigned(wTh - theta0), swTau0)));
                    if (twDeg > 135.0) {
                        double relaxTh = cs * thHard;
                        if (spAt.applyAsDouble(relaxTh) <= effElevCapCm && (frustumOk.test(relaxTh) || !frustumOk.test(wTh))) {
                            double twRelax = Math.abs(Math.toDegrees(unwrappedTurnTwist(UAn, FAn, Hn, bind, axis, sh, wrapSigned(relaxTh - theta0), swTau0)));
                            if (twRelax < twDeg) wTh = relaxTh;
                        }
                    }
                }
                candTh[si] = wTh;
            }
        }

        double wantTheta;
        int targetBranch;
        if (!active) {
            targetBranch = 0;
            wantTheta = theta0;
        } else if (bend0 <= FP_WRIST_BEND_START) {
            targetBranch = 0;
            wantTheta = lookBranch * Math.min(thMag, Math.toRadians(145.0));
        } else {
            double cPlus = wristSwivelCost(candTh[0], theta0, sh, wr, axis, axial, rLen, uStar, vStar, UAn, FAn, Hn, bind, swTau0, elevAxis, natElevCm, anatElevCapCm, toEye, hTan, vTan);
            double cMinus = wristSwivelCost(candTh[1], theta0, sh, wr, axis, axial, rLen, uStar, vStar, UAn, FAn, Hn, bind, swTau0, elevAxis, natElevCm, anatElevCapCm, toEye, hTan, vTan);
            if (prevBranch != 0) {
                double phiZero = wrapSigned(-theta0);
                double[] rotZero = GripIK.rigid(axis, phiZero, sh);
                double tauZeroDeg = bind == null ? 0 : Math.abs(Math.toDegrees(
                        ArmNatural.tau(GripIK.mulNew(rotZero, UAn), GripIK.mulNew(rotZero, FAn), Hn, bind)));
                boolean canCrossZero = !hasHardWall || (zeroPathClear && tauZeroDeg <= 150.0);
                if (!canCrossZero && (Math.abs(prevTheta) <= thHard + Math.toRadians(2.0) || (r.ads > 0.5 && Math.abs(prevTheta) < Math.toRadians(165.0)))) {
                    targetBranch = prevBranch;
                } else {
                    double cCur = prevBranch > 0 ? cPlus : cMinus;
                    double cOther = prevBranch > 0 ? cMinus : cPlus;
                    double hyst = canCrossZero ? 55.0 : (hasHardWall && Math.abs(prevTheta) <= thHard + 1e-4 ? 220.0 : 60.0);
                    targetBranch = cOther + hyst < cCur ? -prevBranch : prevBranch;
                }
            } else {
                targetBranch = Math.abs(cPlus - cMinus) < 1e-3 ? lookBranch : (cPlus <= cMinus ? 1 : -1);
            }
            wantTheta = targetBranch > 0 ? candTh[0] : candTh[1];
        }
        double curTheta = wantTheta;
        if (!fresh && dt > 0) {
            double maxStep = FP_WRIST_SWIVEL_SPEED * dt;
            if (!active && (!hasHardWall || patrolOrAction)) {
                double prevPhi = r.wristSwivel[side];
                double nextPhi = Math.abs(prevPhi) <= maxStep ? 0.0 : prevPhi - Math.copySign(maxStep, prevPhi);
                curTheta = wrapSigned(theta0 + nextPhi);
            } else if (!hasHardWall || patrolOrAction) {
                double dWrap = wrapSigned(wantTheta - prevTheta);
                curTheta = wrapSigned(prevTheta + Math.max(-maxStep, Math.min(maxStep, dWrap)));
            } else if (Math.abs(prevTheta) > thHard + Math.toRadians(1.5)) {
                double dWrap = wrapSigned(wantTheta - prevTheta);
                curTheta = wrapSigned(prevTheta + Math.max(-maxStep, Math.min(maxStep, dWrap)));
                if (cosHard <= A + B && Math.abs(curTheta) > thHard) {
                    boolean largeJumpIn = Math.abs(prevTheta) > thHard + Math.toRadians(16.0);
                    int exitSign = targetBranch != 0
                            && (largeJumpIn || Math.abs(wrapSigned(targetBranch * thHard - prevTheta)) <= Math.toRadians(65.0))
                            ? targetBranch : (curTheta >= 0 ? 1 : -1);
                    curTheta = exitSign * thHard;
                }
            } else {
                curTheta = prevTheta + Math.max(-maxStep, Math.min(maxStep, wantTheta - prevTheta));
                if (cosHard <= A + B && Math.abs(curTheta) > thHard) {
                    curTheta = Math.copySign(thHard, curTheta);
                }
            }
        } else if (!patrolOrAction && hasHardWall && cosHard <= A + B && Math.abs(curTheta) > thHard) {
            int exitSign = targetBranch != 0 ? targetBranch : (wantTheta >= 0 ? 1 : -1);
            curTheta = exitSign * thHard;
        }
        int latchedBranch = hasHardWall && Math.abs(curTheta) <= thHard + Math.toRadians(1.0)
                ? (curTheta >= 0 ? 1 : -1) : targetBranch;
        r.wristBranch[side] = (bend0 > FP_WRIST_BEND_START && thMag <= Math.toRadians(175.0)) ? latchedBranch : 0;
        double bestPhi = wrapSigned(curTheta - theta0);
        if (!active && Math.abs(bestPhi) < 1e-4) {
            r.wristSwivel[side] = 0;
            r.wristBranch[side] = 0;
            r.wristPole[side] = null;
            return 0;
        }
        r.wristSwivel[side] = bestPhi;
        if (Math.abs(bestPhi) > Math.toRadians(20.0)) {
            r.wristWrapped[side] = true;
        }
        r.wristPole[side] = GripIK.add(GripIK.scale(uStar, Math.cos(curTheta)), GripIK.scale(vStar, Math.sin(curTheta)));
        if (Math.abs(bestPhi) > 1e-6) {
            double[] rot = GripIK.rigid(axis, bestPhi, sh);
            double[] ua2 = GripIK.mulNew(rot, UAn), fa2 = GripIK.mulNew(rot, FAn);
            System.arraycopy(ua2, 0, UAn, 0, 16);
            System.arraycopy(fa2, 0, FAn, 0, 16);
            double[] es2 = GripIK.rotateVec(axis, bestPhi, elbowSide);
            System.arraycopy(es2, 0, elbowSide, 0, 3);
        }
        return bestPhi;
    }

    static double wristSwivelCost(double theta, double theta0, double[] sh, double[] wr, double[] axis, double axial,
                                  double rLen, double[] uStar, double[] vStar, double[] UAn, double[] FAn, double[] Hn,
                                  ArmNatural.Bind bind, double swTau0, double[] elevAxis, double natElevCm, double anatElevCapCm,
                                  double[] toEye, double hTan, double vTan) {
        double[] u = GripIK.add(GripIK.scale(uStar, Math.cos(theta)), GripIK.scale(vStar, Math.sin(theta)));
        double[] el = GripIK.add(sh, GripIK.add(GripIK.scale(axis, axial), GripIK.scale(u, rLen)));
        double phi = wrapSigned(theta - theta0);
        double thetaMid = wrapSigned(theta0 + 0.5 * phi);
        double[] uMid = GripIK.add(GripIK.scale(uStar, Math.cos(thetaMid)), GripIK.scale(vStar, Math.sin(thetaMid)));
        double[] elMid = GripIK.add(sh, GripIK.add(GripIK.scale(axis, axial), GripIK.scale(uMid, rLen)));
        double spY = GripIK.dot(GripIK.sub(el, sh), elevAxis) / CM;
        double spMid = GripIK.dot(GripIK.sub(elMid, sh), elevAxis) / CM;
        double effElevCapCm = Math.max(anatElevCapCm, natElevCm + 0.4);
        double cost = 0;
        if (spY > effElevCapCm) {
            cost += (spY - effElevCapCm) * 180.0;
        } else if (spY > anatElevCapCm) {
            cost += (spY - anatElevCapCm) * 18.0;
        }
        if (spMid > effElevCapCm + 1.5) {
            cost += (spMid - effElevCapCm - 1.5) * 40.0;
        }
        cost += spY * 2.5;
        if (toEye != null) {
            double[] eEl = GripIK.point(toEye, el[0], el[1], el[2]);
            double[] eSh = GripIK.point(toEye, sh[0], sh[1], sh[2]);
            double[] eWr = GripIK.point(toEye, wr[0], wr[1], wr[2]);
            double ex = eEl[0] / CM, ey = eEl[1] / CM, depth = -eEl[2] / CM;
            double wrY = eWr[1] / CM;
            double sightCeilY = Math.max(-4.0, wrY + 1.5);
            if (ey > sightCeilY) {
                cost += (ey - sightCeilY) * 120.0;
            }
            if (ex < -10.5) {
                cost += (-10.5 - ex) * 120.0;
            } else if (ex < -2.0) {
                cost += (-2.0 - ex) * 2.0;
            }
            double yBot = depth > 0.0 ? -vTan * depth - 2.0 : -2.0;
            if (ex > 11.0 && ey > yBot - 6.0) {
                cost += (ex - 11.0) * 18.0;
            }
            if (depth > 8.0 && Math.abs(ex) < hTan * depth + 2.0 && ey > yBot) {
                cost += (ey - yBot) * 65.0;
            }
            double chordMidY = 0.5 * (eSh[1] + eWr[1]) / CM;
            cost += Math.max(-25.0, Math.min(25.0, ey - chordMidY)) * 1.5;
        }
        if (bind != null) {
            double rawTau;
            if (Double.isFinite(swTau0)) {
                rawTau = unwrappedTurnTwist(UAn, FAn, Hn, bind, axis, sh, phi, swTau0);
            } else {
                double[] rot = GripIK.rigid(axis, phi, sh);
                rawTau = ArmNatural.tau(GripIK.mulNew(rot, UAn), GripIK.mulNew(rot, FAn), Hn, bind);
            }
            double absTau = Math.abs(Math.toDegrees(rawTau));
            if (absTau > 115.0) {
                cost += (absTau - 115.0) * 10.0;
            }
            if (absTau > 145.0) {
                cost += (absTau - 145.0) * 40.0;
            }
            cost += absTau * 0.5;
        }
        cost += Math.toDegrees(Math.abs(phi)) * 0.45;
        return cost;
    }

    static boolean handDeltaActive(double[][] handDelta) {
        if (handDelta == null || handDelta[0] == null) {
            return false;
        }
        double[] m = handDelta[0];
        return Math.abs(m[0] - 1) > 1e-4 || Math.abs(m[5] - 1) > 1e-4 || Math.abs(m[10] - 1) > 1e-4
                || Math.abs(m[3]) > 1e-4 || Math.abs(m[7]) > 1e-4 || Math.abs(m[11]) > 1e-4;
    }

    static double wrapSigned(double a) {
        return Math.IEEEremainder(a, 2 * Math.PI);
    }

    /** Continuous bisected natural-turn limit on sign's side (-1 or +1), avoiding 5-deg grid steps. */
    static double naturalLimit(java.util.function.DoublePredicate naturalTurn, double sign) {
        double lo = 0;
        final double step = Math.toRadians(5);
        for (double mag = step; mag <= FP_CLEAR_NATURAL_MAX + 1e-9; mag += step) {
            if (!naturalTurn.test(sign * mag)) {
                double hi = mag;
                for (int i = 0; i < 8; i++) {
                    double mid = (lo + hi) / 2;
                    if (naturalTurn.test(sign * mid)) lo = mid;
                    else hi = mid;
                }
                return sign * lo;
            }
            lo = mag;
        }
        return sign * lo;
    }

    /** FB7 item 2: the smallest clearing turn on need's side, bisected below the first clearing CLEAR_SAMPLE step. */
    static double clearFine(java.util.function.DoubleUnaryOperator depth, double need) {
        if (need == 0 || depth.applyAsDouble(need) > 0) {
            return need;
        }
        double sign = Math.signum(need), hi = Math.abs(need), lo = Math.max(0, hi - GripIK.CLEAR_SAMPLE);
        if (lo > 0 && depth.applyAsDouble(sign * lo) <= 0) {
            return need;
        }
        for (int i = 0; i < 12; i++) {
            double mid = (lo + hi) / 2;
            if (depth.applyAsDouble(sign * mid) <= 0) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        return sign * hi;
    }

    /** FB7 item 2: the smallest turn on need's side that clears depth (bisected from 0); 0 when none is needed. */
    static double coreClear(java.util.function.DoubleUnaryOperator depth, double need) {
        if (depth.applyAsDouble(0) <= 0) {
            return 0;
        }
        if (need == 0 || depth.applyAsDouble(need) > 0) {
            return need;
        }
        double sign = Math.signum(need), lo = 0, hi = Math.abs(need);
        for (int i = 0; i < 14; i++) {
            double mid = (lo + hi) / 2;
            if (depth.applyAsDouble(sign * mid) <= 0) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        return sign * hi;
    }

    /**
     * FB7 item 2: the first-person clearance turn of one arm this frame. The goal is need when it grows (or changes
     * side), else the turn held FP_CLEAR_HOLD_S after it fell, then lowered at FP_CLEAR_RELEASE; the turn follows the
     * goal with CLEAR_ATTACK_S, at most FP_SWIVEL_SPEED. An elbow inside the body goes for safe (the smallest clearing
     * turn) at once, at most SWIVEL_INSIDE_SPEED - the former rule.
     */
    static double wristBend(double[] FA, double[] H) {
        double[] f = GripIK.normalize(GripIK.sub(GripIK.pos(H), GripIK.pos(FA)));
        double[] hx = GripIK.normalize(new double[] {H[0], H[4], H[8]});
        return Math.acos(Math.max(-1, Math.min(1, GripIK.dot(f, hx))));
    }

    static double gazeDepth(double[] el, Request r, int side) {
        if (r.eyeModel != null && r.mode.startsWith("fp")) {
            double[] e = r.eyeModel;
            double x = GripIK.dot(GripIK.sub(el, GripIK.pos(e)), new double[] {e[0], e[4], e[8]});
            double cross = side == 0 ? -x : x;
            if (side == 0 && r.ads > 0.5 && Math.abs(r.wristSwivel[0]) > 1e-4) {
                double[] d = GripIK.sub(el, GripIK.pos(e));
                double ex = x / CM;
                double ey = GripIK.dot(d, new double[] {e[1], e[5], e[9]}) / CM;
                double dep = -GripIK.dot(d, new double[] {e[2], e[6], e[10]}) / CM;
                if (dep > 8.0 && ex > 11.5 && ex < 1.15 * dep) {
                    double fEx = (ey - (-0.65 * dep)) * CM;
                    if (fEx > cross) cross = fEx;
                }
            }
            return cross;
        }
        return -1;
    }

    static double naturalClearTurn(java.util.function.DoubleUnaryOperator wanted,
            java.util.function.DoubleUnaryOperator coreDepth,
            java.util.function.DoublePredicate natural, double prev, double first, boolean fpClear) {
        if (wanted.applyAsDouble(0) <= 0) {
            return 0;
        }
        final double step = Math.toRadians(5);
        for (double sign : new double[] {first, -first}) {
            double prevMag = 0;
            for (double mag = step; mag <= FP_CLEAR_NATURAL_MAX + 1e-9; mag += step) {
                if (!natural.test(sign * mag)) {
                    break;
                }
                if (wanted.applyAsDouble(sign * mag) <= 0) {
                    double lo = prevMag, hi = mag;
                    for (int i = 0; i < 12; i++) {
                        double mid = (lo + hi) / 2;
                        if (wanted.applyAsDouble(sign * mid) <= 0) {
                            hi = mid;
                        } else {
                            lo = mid;
                        }
                    }
                    return sign * hi;
                }
                prevMag = mag;
            }
        }
        if (fpClear) {
            if (coreDepth.applyAsDouble(0) <= 0) {
                return 0;
            }
            for (double sign : new double[] {first, -first}) {
                double prevMag = 0;
                for (double mag = step; mag <= FP_CLEAR_NATURAL_MAX + 1e-9; mag += step) {
                    if (!natural.test(sign * mag)) {
                        break;
                    }
                    if (coreDepth.applyAsDouble(sign * mag) <= 0) {
                        double lo = prevMag, hi = mag;
                        for (int i = 0; i < 12; i++) {
                            double mid = (lo + hi) / 2;
                            if (coreDepth.applyAsDouble(sign * mid) <= 0) {
                                hi = mid;
                            } else {
                                lo = mid;
                            }
                        }
                        return sign * hi;
                    }
                    prevMag = mag;
                }
            }
        }
        double d0 = coreDepth.applyAsDouble(0);
        if (d0 <= 0) {
            return 0;
        }
        double[] sides = Double.isFinite(prev) && Math.abs(prev) > 1e-3
                ? new double[] {Math.signum(prev)} : new double[] {first, -first};
        double bestTurn = 0, bestDepth = d0, maxSideMag = 0;
        for (double sign : sides) {
            double sideMax = 0;
            for (double mag = step; mag <= FP_CLEAR_NATURAL_MAX + 1e-9; mag += step) {
                double cand = sign * mag;
                if (!natural.test(cand)) {
                    break;
                }
                sideMax = mag;
                double d = coreDepth.applyAsDouble(cand);
                if (d < bestDepth - 1e-7) {
                    bestDepth = d;
                    bestTurn = cand;
                    maxSideMag = sideMax;
                } else if (bestTurn != 0 && Math.signum(bestTurn) == sign) {
                    maxSideMag = sideMax;
                }
            }
        }
        if (bestTurn == 0 || bestDepth >= FP_SOFT_PASS) {
            return 0;
        }
        double sgn = Math.signum(bestTurn);
        double lo = Math.max(0, Math.abs(bestTurn) - step), hi = Math.min(maxSideMag, Math.abs(bestTurn) + step);
        for (int i = 0; i < 12; i++) {
            double m1 = lo + (hi - lo) / 3, m2 = hi - (hi - lo) / 3;
            if (coreDepth.applyAsDouble(sgn * m1) <= coreDepth.applyAsDouble(sgn * m2)) {
                hi = m2;
            } else {
                lo = m1;
            }
        }
        double refTurn = sgn * (lo + hi) / 2, refDepth = coreDepth.applyAsDouble(refTurn);
        if (refDepth < bestDepth) {
            bestTurn = refTurn;
            bestDepth = refDepth;
        }
        if (bestDepth >= FP_SOFT_PASS) {
            return 0;
        }
        return bestTurn * (1 - GripIK.smooth(bestDepth / FP_SOFT_PASS));
    }

    static double fpClearTurn(Request r, int side, double need, double safe, java.util.function.DoubleUnaryOperator depth, double dt) {
        dt = Math.max(0, Math.min(.05, dt));
        double cur = r.elbowTurn[side], held = r.elbowHold[side], inside = depth.applyAsDouble(cur);
        boolean sameSide = held == 0 || need == 0 || Math.signum(need) == Math.signum(held);
        if (inside > 0 && depth.applyAsDouble(safe) <= 0) {
            held = safe;
            r.elbowHoldS[side] = 0;
        } else if (inside > 0) {
            held = need;
            r.elbowHoldS[side] = 0;
        } else if (!sameSide || Math.abs(need) >= Math.abs(held) || depth.applyAsDouble(held) > 0) {
            // a growing need, the other side, or a held turn the body now enters: the need
            held = need;
            r.elbowHoldS[side] = 0;
        } else {
            r.elbowHoldS[side] += dt;
            if (r.elbowHoldS[side] > FP_CLEAR_HOLD_S) {
                double down = FP_CLEAR_RELEASE * dt;
                held = held > need ? Math.max(need, held - down) : Math.min(need, held + down);
            }
        }
        r.elbowHold[side] = held;
        double step = (held - cur) * (1 - Math.exp(-dt / GripIK.CLEAR_ATTACK_S));
        return cur + Math.max(-FP_SWIVEL_SPEED * dt, Math.min(FP_SWIVEL_SPEED * dt, step));
    }

    /** PZ body clearance plus the source arm's side of the gaze axis in first person. */
    static double armDepth(double[][] model, Skeleton s, double[] sh, double[] el, double[] wr, double[][] torso, Request r, int side) {
        double depth = GripIK.armDepth(model, s.chain, sh, el, wr, torso);
        double gd = gazeDepth(el, r, side);
        return gd > -1 ? Math.max(depth, gd) : depth;
    }

    static double[] rotate3(double[] m, double[] v) {
        return new double[] {m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
                m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
    }

    /**
     * The magazine bone: with the weapon (rigid), or in the support hand while held there
     * (GripIK.mags mode 2: not when seated in this weapon's well).
     */
    static double[] magazine(IsoGameCharacter c, double[][] model, Skeleton s, double[] D, double[] P0, double[] hand) {
        double[] P2 = model[s.prop2];
        double[] rigid = GripIK.mulNew(D, P2);
        double[] mg = GripIK.mags.get(c);
        if (mg == null || mg[0] != 2 || mg.length < 13) {
            return rigid;
        }
        double dHold = GripIK.dist(GripIK.pos(P2), GripIK.pos(model[s.lh]));
        if (mg.length >= 31) {
            double gx = mg[25] + (mg.length >= 37 ? mg[31] : 0), gy = mg[26] + (mg.length >= 37 ? mg[32] : 0), gz = mg[27] + (mg.length >= 37 ? mg[33] : 0);
            double sx = mg[28] + (mg.length >= 37 ? mg[34] : 0), sy = mg[29] + (mg.length >= 37 ? mg[35] : 0), sz = mg[30] + (mg.length >= 37 ? mg[36] : 0);
            dHold = Math.min(GripIK.dist(GripIK.pos(model[s.lh]), GripIK.point(P2, gx, gy, gz)),
                    GripIK.dist(GripIK.pos(model[s.lh]), GripIK.point(P2, sx, sy, sz)));
        }
        double held = GripIK.smooth((MAG_HELD_FAR - dHold) / (MAG_HELD_FAR - MAG_HELD_NEAR));
        double[] seat = GripIK.pos(GripIK.mulNew(P0, GripIK.rows(mg, 1)));
        double seated = GripIK.smooth((GripIK.MAG_FAR - GripIK.dist(GripIK.pos(P2), seat)) / (GripIK.MAG_FAR - GripIK.MAG_NEAR));
        held *= 1 - seated;
        if (held <= 0) {
            return rigid;
        }
        double[] inHand = GripIK.mulNew(hand, GripIK.mulNew(GripIK.inverse(model[s.lh]), P2));
        return interp(rigid, inHand, held);
    }

    /** Model transforms of the drivers and of every bone below a moved one; bone locals of the drivers (as ArmsPitch keeps them). */
    /** QA (FB1): the arm bones as written - position (cm), column scales and the skin matrix's
     * largest translation from its bone, to find a degenerate bone behind an exploded arm mesh. */
    public static volatile boolean bonesProbe;
    static volatile String bones = "", probeIn = "";

    static String boneProbe(AnimationPlayer ap, Skeleton s) {
        StringBuilder b = new StringBuilder();
        String[] names = {"lc", "ua", "fa", "lh", "lf0", "lf1", "rc", "rua", "rfa", "rh", "chest", "neck"};
        int[] ids = {s.lc, s.ua, s.fa, s.lh, s.lf0, s.lf1, s.rc, s.rua, s.rfa, s.rh, s.chain[2], s.chain[3]};
        for (int k = 0; k < ids.length; k++) {
            int id = ids[k];
            if (id < 0 || id >= s.n) continue;
            double[] m = GripIK.read(ap.getModelTransformAt(id), new double[16]);
            double[] cs = GripIK.columnScales(m);
            b.append(String.format(java.util.Locale.ROOT, " %s=%.1f,%.1f,%.1f|%.3f,%.3f,%.3f", names[k], m[3] / CM, m[7] / CM, m[11] / CM, cs[0], cs[1], cs[2]));
        }
        return (probeIn + " " + b.toString()).trim();
    }

    static void write(AnimationPlayer ap, Skeleton s, double[][] model, double[][] next, boolean[] driver) {
        Matrix4f tmp = TMP.get();
        AnimatorsBoneTransform[] bt = ap.boneTransforms;
        boolean[] moved = new boolean[s.n];
        double[][] now = new double[s.n][];
        for (int i = 0; i < s.n; i++) {
            int p = s.parent[i];
            if (driver[i] && next[i] != null) {
                now[i] = next[i];
                moved[i] = true;
            } else if (p >= 0 && moved[p]) {
                bt[i].getMatrix(tmp);
                now[i] = GripIK.mulNew(now[p], GripIK.read(tmp, new double[16]));
                moved[i] = true;
            } else {
                now[i] = model[i];
            }
        }
        for (int i = 0; i < s.n; i++) {
            if (!moved[i]) {
                continue;
            }
            if (driver[i] && next[i] != null) {
                int p = s.parent[i];
                double[] local = GripIK.mulNew(GripIK.inverse(now[p]), now[i]);
                bt[i].set(GripIK.write(local, tmp));
            }
            GripIK.write(now[i], ap.getModelTransformAt(i));
        }
    }

    // ------------------------------------------------------------------ neutral frame, QA

    /**
     * The weapon frame before the retreat (TWZB.weaponFrameNeutral, TW/WeaponObstacle's query):
     * WeaponFrame.look's 12 numbers of the neutral Prop1, or null (no fresh neutral pose).
     */
    static double[] neutralLook(IsoGameCharacter c) {
        Request r = c == null ? null : requests.get(c);
        double[] m = r == null ? null : r.neutral;
        if (m == null || c.getPrimaryHandItem() != r.item || !freshFrame(r.neutralFrame, FpRecoil.worldFrame())) {
            return null;
        }
        ModelInstance mi = c.primaryHandModel;
        AnimationPlayer ap = mi != null && mi.animPlayer != null ? mi.animPlayer : c.getAnimationPlayer();
        if (ap == null) {
            return null;
        }
        float a = ap.getRenderedAngle();
        double[] f = new double[12];
        Vector3 v = new Vector3(0, 0, 0);
        double[][] cols = {{m[3], m[7], m[11]}, {m[0], m[4], m[8]}, {m[1], m[5], m[9]}, {m[2], m[6], m[10]}};
        for (int k = 0; k < 4; k++) {
            v.x = (float) cols[k][0];
            v.y = (float) cols[k][1];
            v.z = (float) cols[k][2];
            if (k == 0) {
                Model.vectorToWorldCoords(c.getX(), c.getY(), c.getZ(), a, v);
            } else {
                Model.vectorToWorldCoords(0, 0, 0, a, v);
            }
            f[3 * k] = v.x;
            f[3 * k + 1] = v.y;
            f[3 * k + 2] = v.z;
        }
        double l = c.getLookAngleRadians(), cs = Math.cos(l), sn = Math.sin(l);
        for (int k = 0; k < 12; k += 3) {
            double ex = f[k] - (k == 0 ? c.getX() : 0), ey = f[k + 1] - (k == 0 ? c.getY() : 0), ez = f[k + 2] - (k == 0 ? c.getZ() : 0);
            f[k] = ex * cs + ey * sn;
            f[k + 1] = -ex * sn + ey * cs;
            f[k + 2] = ez * ViewpointBridge.LEVEL;
        }
        return f;
    }

    static String torchNativeMetrics(IsoGameCharacter c, Request r) {
        AnimationPlayer ap = c.primaryHandModel != null && c.primaryHandModel.animPlayer != null
                ? c.primaryHandModel.animPlayer : c.getAnimationPlayer();
        if (ap == null) return "nativeMetrics=unavailable";
        double[] head = GripIK.read(ap.getModelTransformAt(ap.getSkinningBoneIndex("Bip01_Head", -1)), new double[16]);
        double[] right = GripIK.read(ap.getModelTransformAt(ap.getSkinningBoneIndex("Bip01_R_Hand", -1)), new double[16]);
        double[] left = GripIK.read(ap.getModelTransformAt(ap.getSkinningBoneIndex("Bip01_L_Hand", -1)), new double[16]);
        double[] eye = r.fpW > 0.5 && r.eyeModel != null ? GripIK.pos(r.eyeModel)
                : new double[] {head[3] - 0.22 * CM, head[7] + 11.53 * CM, head[11] - 13.78 * CM};
        double[] dr = GripIK.sub(GripIK.pos(right), eye), dl = GripIK.sub(GripIK.pos(left), eye);
        String script = c.secondaryHandModel == null || c.secondaryHandModel.modelScript == null ? "none"
                : c.secondaryHandModel.modelScript.getScriptObjectFullType();
        int bone = c.secondaryHandModel == null ? -1 : c.secondaryHandModel.parentBone;
        boolean sameAp = c.secondaryHandModel != null && c.secondaryHandModel.animPlayer == ap;
        String axial = "";
        if (r.eyeModel != null && !isAngleTorchMask(secondaryMaskValue(c.getSecondaryHandItem()))) {
            double[] p2 = GripIK.read(ap.getModelTransformAt(ap.getSkinningBoneIndex("Bip01_Prop2", -1)), new double[16]);
            double[] p2Eye = GripIK.mulNew(GripIK.inverse(r.eyeModel), p2);
            double[] beam = GripIK.normalize(new double[] {p2Eye[1], p2Eye[5], p2Eye[9]});
            double lensLength = axialLensLength(c.getSecondaryHandItem());
            double[] lens = {p2Eye[3] + beam[0] * lensLength, p2Eye[7] + beam[1] * lensLength, p2Eye[11] + beam[2] * lensLength};
            axial = String.format(java.util.Locale.ROOT, " torchOriginCm=%.2f,%.2f,%.2f torchHeadCm=%.2f,%.2f,%.2f",
                    -p2Eye[3] / CM, -p2Eye[7] / CM, -p2Eye[11] / CM, -lens[0] / CM, -lens[1] / CM, -lens[2] / CM);
        }
        if (isConeTorch(c.getSecondaryHandItem())) {
            // W12 QA: the light against the Harries fist (0 when it is held as the fist holds it)
            double[] p2 = GripIK.read(ap.getModelTransformAt(ap.getSkinningBoneIndex("Bip01_Prop2", -1)), new double[16]);
            double[] fist = GripIK.mulNew(left, harriesTLhP2(secondaryMaskValue(c.getSecondaryHandItem())));
            axial += String.format(java.util.Locale.ROOT, " torchFistCm=%.3f", GripIK.dist(GripIK.pos(p2), GripIK.pos(fist)) / CM);
        }
        if (!sameAp && c.secondaryHandModel != null && c.secondaryHandModel.animPlayer != null && r.eyeModel != null) {
            AnimationPlayer secondaryAp = c.secondaryHandModel.animPlayer;
            double[] rendered = GripIK.read(secondaryAp.getModelTransformAt(secondaryAp.getSkinningBoneIndex("Bip01_Prop2", -1)), new double[16]);
            double[] renderedEye = GripIK.mulNew(GripIK.inverse(r.eyeModel), rendered);
            axial += String.format(java.util.Locale.ROOT, " renderedOriginCm=%.2f,%.2f,%.2f",
                    -renderedEye[3] / CM, -renderedEye[7] / CM, -renderedEye[11] / CM);
        }
        return String.format(java.util.Locale.ROOT,
                "eyeRCm=%.3f eyeLCm=%.3f eyeRHcm=%.3f eyeLHcm=%.3f secondaryDraw=%s secondaryBone=%d secondarySameAp=%b%s",
                GripIK.norm(dr) / CM, GripIK.norm(dl) / CM, dr[1] / CM, dl[1] / CM, script, bone, sameAp, axial);
    }

    /** QA: what the layer did for a character: mode, track, eye and the camera-relative Prop1 (cm right/down/forward, bore, top). */
    static String describe(IsoGameCharacter c) {
        Request r = c == null ? null : requests.get(c);
        if (r == null) {
            return null;
        }
        StringBuilder s = new StringBuilder();
        s.append("mode=").append(r.mode).append(" weapon=").append(r.weapon).append(" class=").append(r.cls)
                .append(" aiming=").append(r.aiming).append(" acting=").append(r.acting).append(" actingW=").append(round(r.actingW)).append(" allowed=").append(r.allowed)
                .append(" carry=").append(r.carry).append(" fp=").append(round(r.fpW)).append(" state=").append(c.getCurrentActionContextStateName())
                .append(" ads=").append(round(r.ads)).append(String.format(java.util.Locale.ROOT, " adsTime=%.3f", r.adsTime))
                .append(" high=").append(round(r.highW)).append(" patrolW=").append(round(r.patrolW))
                .append(" patrolSurfaceW=").append(round(r.patrolSurfaceW)).append(" obstacle=").append(round(r.obstacle))
                .append(" sight=").append(r.sight != null).append(" action=").append(r.track);
        s.append(" spent=").append(r.spent).append(" launcher=").append(r.launcher).append(" launcherRange=").append(r.launcherRange != null)
                .append(" launcherHold=").append(r.launcherHold != null);
        s.append(String.format(java.util.Locale.ROOT, " nearPoints=%d nearPushCm=%.3f nearCutCm=%.3f nearPushes=%d keepOut=%b",
                r.nearPoints == null ? 0 : r.nearPoints.length, r.nearPushCm, r.nearCutCm, r.nearPushes, keepOut));
        s.append(String.format(java.util.Locale.ROOT, " ironSight=%b sightNearCm=%.2f adsClamp=%b", r.ironSight, r.sightNearCm, r.clampSafe));
        s.append(String.format(java.util.Locale.ROOT, " slideCm=%.3f slideHoldCm=%.3f slideHold=%b", r.slideCm, r.slideHoldCm, slideHold));
        s.append(" trigger=").append(r.trigger && freshFrame(r.triggerFrame, FpRecoil.worldFrame())).append(" hip=").append(r.hip)
                .append(" readyFrom=").append(r.readyFrom < 0 ? "-" : r.readyFrom == CARRY_PATROL ? "patrol" : r.readyFrom == CARRY_HIGH ? "high" : "low")
                .append(String.format(java.util.Locale.ROOT, " readySeconds=%.4f", r.readyFrom < 0 ? 0 : r.readySeconds));
        if (r.wristEye != null) {
            s.append(String.format(java.util.Locale.ROOT, " wristCm=%.2f,%.2f,%.2f elbowDepthCm=%.3f sightErrorCm=%.5f sightAxisErrorDeg=%.5f recoilSightCm=%.4f",
                    -r.wristEye[0] / CM, -r.wristEye[1] / CM, -r.wristEye[2] / CM, r.elbowDepth, r.sightError, r.sightAxisError, r.recoilSight));
        }
        if (Double.isFinite(r.torchHandDistCm)) {
            s.append(String.format(java.util.Locale.ROOT, " torchHandDistCm=%.3f torchHlRelErrCm=%.3f torchBeamDeg=%.4f torchOffhandW=%.3f",
                    r.torchHandDistCm, r.torchHlRelErrCm, r.torchBeamDeg, r.torchOffhandW));
        } else if (r.torchOffhandW > 0) {
            s.append(String.format(java.util.Locale.ROOT, " torchOffhandW=%.3f", r.torchOffhandW));
        }
        if (r.torchOffhandW > 0) {
            s.append(' ').append(torchNativeMetrics(c, r));
        }
        if (r.handgun) {
            s.append(String.format(java.util.Locale.ROOT, " adsReliefCm=%.3f sightReliefCm=%.3f handNearCm=%.3f", r.adsReliefCm, r.sightReliefCm, r.handNearCm));
        }
        if (r.supportEye != null) {
            s.append(String.format(java.util.Locale.ROOT, " supportCm=%.2f,%.2f,%.2f armNearCm=%.3f contactClearCm=%.3f",
                    -r.supportEye[0] / CM, -r.supportEye[1] / CM, -r.supportEye[2] / CM, r.armNear, r.contactClear));
            s.append(String.format(java.util.Locale.ROOT, " handMissCm=%.5f sourceContactAdjustCm=%.5f elbowSideCm=%.3f forearmsCross=%b", r.handMiss,
                    r.sourceContactAdjust, r.elbowSideMin, r.forearmsCross));
            // natural arms (ArmNatural): each wrist's twist, the forearm's share of it, the forearm's turn it came with; the
            // elbows' turns out of the torso (arm(): released over GripIK.CLEAR_RELEASE_S)
            s.append(ArmNatural.describe(c));
            s.append(String.format(java.util.Locale.ROOT, " elbowTurnDeg=%.1f,%.1f reliefDeg=%.1f,%.1f", Math.toDegrees(r.elbowTurn[0]),
                    Math.toDegrees(r.elbowTurn[1]), Math.toDegrees(r.relief[0]), Math.toDegrees(r.relief[1])));
            s.append(String.format(java.util.Locale.ROOT, " wristSwivelDeg=%.1f,%.1f", Math.toDegrees(r.wristSwivel[0]),
                    Math.toDegrees(r.wristSwivel[1])));
            s.append(String.format(java.util.Locale.ROOT, " fingerFit=%.3f,%.3f knuckleMissCm=%.5f", r.fingerFit[0], r.fingerFit[1], r.knuckleMiss));
            if (r.elbowsEye != null) {
                double[] e = r.elbowsEye;
                s.append(String.format(java.util.Locale.ROOT, " elbowsCm=%.2f,%.2f,%.2f,%.2f,%.2f,%.2f", -e[0] / CM, -e[1] / CM, -e[2] / CM,
                        -e[3] / CM, -e[4] / CM, -e[5] / CM));
            }
            s.append(String.format(java.util.Locale.ROOT, " sourceContactMissCm=%.5f sourceContactLimitCm=%.5f bodyReachLimit=%b",
                    r.sourceContactMiss, r.supportBack / CM, r.handMiss > .1 || r.elbowSideMin < -.01));
            s.append(String.format(java.util.Locale.ROOT, " bodyTurnDeg=%.3f bodyGoalDeg=%.3f bodyCapDeg=%.3f highPullCm=%.3f reachBeforeCm=%.5f reachAfterCm=%.5f",
                    Math.toDegrees(r.bodyTurn), Math.toDegrees(r.bodyGoal), Math.toDegrees(r.bodyCap), GripIK.norm(r.highPull) / CM, r.reachBefore, r.reachAfter));
            s.append(String.format(java.util.Locale.ROOT, " bodyPitchDeg=%.3f pitchGoalDeg=%.3f", Math.toDegrees(r.bodyPitch), Math.toDegrees(r.pitchGoal)));
            s.append(String.format(java.util.Locale.ROOT, " reloadPullCm=%.3f", GripIK.norm(r.reloadPull) / CM));
        }
        FpPoseData d = FpPoseData.get();
        FpPoseData.Family f = d == null ? null : family(d, r);
        if (f != null) {
            s.append(" family=").append(f.name).append(" reference=").append(f.weapon)
                    .append(" own=").append(f.weapon.equals(r.weapon)).append(" handgun=").append(r.handgun)
                    .append(" adsSeconds=").append(round(adsSeconds(d, r.ergonomics, r.weight)));
            double[] shift = shift(f, r.weapon, r.dR);
            // item 1: a handgun's own High Ready is its muzzle-up stance, a rifle's the chest-height retreat endpoint
            double[] endpoint = r.handgun ? pistolHighReady(f, shift)
                    : highReady(f, shift, readyPitch(false, r.pitch, r.obstacle, r.pullUntil), HIGH_WRIST);
            double[] readyWrist = handContact(f, r, endpoint, c.isFemale(), 1);
            double highDown = r.eyeModel == null ? 0 : GripIK.dot(r.highPull, new double[] {r.eyeModel[1], r.eyeModel[5], r.eyeModel[9]});
            double[] bore = GripIK.normalize(new double[] {endpoint[1], endpoint[5], endpoint[9]});
            s.append(String.format(java.util.Locale.ROOT, " readyBelowCm=%.3f readyBoreDeg=%.3f", -(readyWrist[7] + highDown) / CM,
                    Math.toDegrees(Math.atan2(bore[1], Math.hypot(bore[0], bore[2])))));
        }
        if (r.eye != null) {
            s.append(String.format(java.util.Locale.ROOT, " eye=%.3f,%.3f,%.3f yaw=%.1f pitch=%.1f", r.eye[0], r.eye[1], r.eye[2],
                    Math.toDegrees(r.eye[3]), Math.toDegrees(r.eye[4])));
            if (r.eye.length > 7) { // bug wave 324: Viewpoint's free look of this pose and the camera's own look
                s.append(String.format(java.util.Locale.ROOT, " freeLook=%s cameraYaw=%.1f cameraPitch=%.1f", FpFreeLook.MODES[(int) r.eye[7]],
                        Math.toDegrees(r.eye[5]), Math.toDegrees(r.eye[6])));
            }
        }
        double[] q = r.eyePose;
        if (q != null && r.mode.startsWith("fp")) {
            double[] b = GripIK.normalize(new double[] {q[1], q[5], q[9]}), t = GripIK.normalize(new double[] {q[2], q[6], q[10]});
            s.append(String.format(java.util.Locale.ROOT, " prop1Cm=%.2f,%.2f,%.2f bore=%.3f,%.3f,%.3f top=%.3f,%.3f,%.3f",
                    -q[3] / CM, -q[7] / CM, -q[11] / CM, b[0], b[1], b[2], t[0], t[1], t[2]));
            s.append(String.format(java.util.Locale.ROOT, " boreElevationDeg=%.5f", Math.toDegrees(Math.atan2(b[1], Math.hypot(b[0], b[2])))));
            if (r.rear != null) {
                s.append(String.format(java.util.Locale.ROOT, " rearBehindCm=%.3f", GripIK.point(q, r.rear[0], r.rear[1], r.rear[2])[2] / CM));
            }
            double fov = ViewpointBridge.floatOf("viewpoint.core.View.frameFovY", 0);
            if (r.bounds != null && fov > 0) {
                double aspect = (double) Core.getInstance().getScreenWidth() / Math.max(1, Core.getInstance().getScreenHeight());
                double[] projection = projectBounds(q, r.bounds, r.rolled, fov, aspect);
                s.append(String.format(java.util.Locale.ROOT, " projectionBox=%.5f projectionCorners=%.3f projectionVfovDeg=%.3f projectionAspect=%.4f",
                        projection[0], projection[1], Math.toDegrees(fov), aspect));
            }
        }
        return s.toString();
    }

    /** QA (W10 manualeye): weapon vertical envelope in Viewpoint's eye frame (+Y up, cm). */
    public static String weaponEnvelope(IsoGameCharacter c) {
        Request r = c == null ? null : requests.get(c);
        if (r == null || r.eyePose == null) {
            return "";
        }
        double[] q = r.eyePose;
        double propY = q[7] / CM, minY = propY, maxY = propY;
        if (r.bounds != null && r.bounds.length >= 6) {
            minY = Double.POSITIVE_INFINITY;
            maxY = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < 8; i++) {
                double x = r.bounds[(i & 1) == 0 ? 0 : 3];
                double y = r.bounds[(i & 2) == 0 ? 1 : 4];
                double z = r.bounds[(i & 4) == 0 ? 2 : 5];
                double ey = GripIK.point(q, r.rolled ? -x : x, y, r.rolled ? -z : z)[1] / CM;
                if (ey < minY) minY = ey;
                if (ey > maxY) maxY = ey;
            }
        }
        return String.format(java.util.Locale.ROOT, "mode=%s fpW=%.4f ads=%.4f propYCm=%.3f minYCm=%.3f maxYCm=%.3f handMissCm=%.4f track=%s",
                r.mode.replace(' ', '_'), r.fpW, r.ads, propY, minY, maxY, r.handMiss, r.track.isEmpty() ? "-" : r.track.replace(' ', '_'));
    }

    public static String stats() {
        return "calls=" + calls + " enabled=" + enabled + " applied=" + applied + " firstPerson=" + fpFrames + " retreats=" + retreats + " eyePairs=" + eyePairs
                + " eyeMissing=" + eyeMissing + " actions=" + actions + " slides=" + slides + " clavicles=" + clavicles + " elbowTurns=" + elbowTurns
                + " requests=" + requests.size() + " nanos=" + nanos + " errors=" + errors + " data=" + FpPoseData.status
                + " hipFamilies=" + HIP.size() + " hipRaises=" + hipRaises + " hipReady=" + hipReady + " hipInterrupted=" + hipInterrupted
                + (lastError.isEmpty() ? "" : " lastError=" + lastError);
    }

    // ------------------------------------------------------------------ small helpers

    /** Lua renews after the animation update. A long tick does not age a request by world frames. */
    static boolean freshFrame(long stamp, long frame) {
        return stamp >= 0 && frame >= stamp && frame - stamp <= FRESH_FRAMES;
    }

    /** Projected forearm centre lines, excluding collinear/shared contact endpoints. */
    static boolean forearmsCross(double[] el, double[] wl, double[] er, double[] wr) {
        double[][] p = {el, wl, er, wr};
        double[][] v = new double[4][2];
        for (int i = 0; i < 4; i++) {
            if (p[i][2] >= -0.05 / 1.5) return false;
            v[i][0] = p[i][0] / -p[i][2]; v[i][1] = p[i][1] / -p[i][2];
        }
        return orient(v[0], v[1], v[2]) * orient(v[0], v[1], v[3]) < -1e-12
                && orient(v[2], v[3], v[0]) * orient(v[2], v[3], v[1]) < -1e-12;
    }

    static double orient(double[] a, double[] b, double[] c) {
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
    }

    /** QA envelope, not a mesh silhouette: projected assembly AABB overlap / its screen rectangle,
     * and the fraction of its eight corners inside the frustum. Clip edges at VP's near plane.
     */
    static double[] projectBounds(double[] q, double[] bounds, boolean rolled, double fov, double aspect) {
        double[][] p = new double[8][];
        for (int i = 0; i < 8; i++) {
            double x = bounds[(i & 1) == 0 ? 0 : 3], y = bounds[(i & 2) == 0 ? 1 : 4], z = bounds[(i & 4) == 0 ? 2 : 5];
            p[i] = GripIK.point(q, rolled ? -x : x, y, rolled ? -z : z);
        }
        java.util.List<double[]> points = new java.util.ArrayList<>();
        double near = 0.05 / 1.5, tan = Math.tan(fov / 2);
        int inside = 0;
        for (int i = 0; i < 8; i++) {
            if (p[i][2] < -near) {
                points.add(p[i]);
                if (Math.abs(p[i][0]) <= -p[i][2] * tan * aspect && Math.abs(p[i][1]) <= -p[i][2] * tan) inside++;
            }
            for (int bit = 1; bit <= 4; bit *= 2) {
                int j = i ^ bit;
                if (j > i && (p[i][2] < -near) != (p[j][2] < -near)) {
                    points.add(GripIK.lerp(p[i], p[j], (-near - p[i][2]) / (p[j][2] - p[i][2])));
                }
            }
        }
        double minX = Double.POSITIVE_INFINITY, minY = minX, maxX = -minX, maxY = maxX;
        for (double[] a : points) {
            double x = a[0] / (-a[2] * tan * aspect), y = a[1] / (-a[2] * tan);
            minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
        double area = (maxX - minX) * (maxY - minY);
        double overlap = Math.max(0, Math.min(1, maxX) - Math.max(-1, minX)) * Math.max(0, Math.min(1, maxY) - Math.max(-1, minY));
        return new double[] {points.isEmpty() || area <= 0 ? 0 : overlap / area, inside / 8.};
    }

    static double[] identity() {
        return new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    static double[] translation(double[] t) {
        double[] m = identity();
        m[3] = t[0];
        m[7] = t[1];
        m[11] = t[2];
        return m;
    }

    /** Rigid blend: position lerp, rotation slerp (GripIK.slerp, stable at 180 degrees). */
    static double[] interp(double[] a, double[] b, double t) {
        if (t <= 0) {
            return a.clone();
        }
        if (t >= 1) {
            return b.clone();
        }
        double[] out = a.clone();
        GripIK.setRotation(out, GripIK.slerp(GripIK.unitRotation(a), GripIK.unitRotation(b), t), GripIK.columnScales(a));
        GripIK.setPos(out, GripIK.lerp(GripIK.pos(a), GripIK.pos(b), t));
        return out;
    }

    static double clamp(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
