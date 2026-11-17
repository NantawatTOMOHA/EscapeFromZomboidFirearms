package pz.escapefromzomboidfirearms.zb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.skinnedmodel.animation.AnimationMultiTrack;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.animation.AnimationTrack;
import zombie.gameStates.ChooseGameInfo;
import zombie.iso.IsoCamera;
import zombie.iso.IsoWorld;

/**
 * Tarkov recoil, sway, breath and arm stamina in Viewpoint's first person (VP-W7-R): the
 * recoil core (TarkovRecoil, VP-W5-B6) and the handling core (TarkovHandling, VP-W5-B7) laid
 * over the first-person pose layer (FpPose). In Viewpoint's third person the weapon carries the same track's recoil
 * channels (thirdPerson, W10 item 2); the isometric view stays as before.
 *
 *   timeline  one handling state per local player (arms, oxygen, hold breath: transient,
 *             never saved or sent; PZ's own endurance is not touched) and one recoil state per
 *             held weapon item, both on the cores' fixed 1/60 s step by the pose layer's clock.
 *             Frames (inputs) and shots carry their time and hold from the first step after
 *             it, so one timeline gives the same states at any frame rate. The drawn pose is
 *             the state between the last two steps (one step behind, linear by the clock)
 *   shot      PatchMuzzle (the muzzle path: once per cartridge, with or without our shot
 *             owner) for a local player in the first person whose request names a weapon of
 *             the recoil data, posed by the layer within a few world frames (frames, not wall
 *             time: a long frame is no gap); a deployed underbarrel has none; each refusal has
 *             its reason (stats). The held trigger (AutoFireOn) is
 *             a next shot of an automatic or burst mode within the cycle (60 / rpm) plus a margin
 *   weapon    the cores' channels are metres and degrees of Tarkov's weapon root (the clips'
 *             weapon bone, Weapon_root_anim at rest: +X right, -Y bore, +Z up), turned into
 *             Prop1 by the source basis of the poses (fp_poses prop_space: x U, PZ_SOURCE_BASIS,
 *             grip offset, the pistol roll): position, then the procedural rotation (Euler
 *             x,z then y) about RotationCenter, then the recoil rotation (Unity Euler) about
 *             RecoilCenter, as ProceduralWeaponAnimation.ApplyComplexRotation. The hands and the
 *             magazine follow the weapon and the arms are solved again (FpPose.place), so the
 *             drawn weapon frame (WeaponFrame, ShotOwner) already has the recoil
 *   camera    Tarkov's camera (LerpCamera and its camera-to-weapon follow, then the camera
 *             recoil added to its Euler angles) into Viewpoint's camera additive of the frame
 *             (CameraShot.addYaw/addPitch/addRoll, optional bridge members), written once per
 *             frame at the exit of IsoWorld.render (FpRecoilPatch); for these shots Viewpoint's
 *             own camera kick (Impulses.shot) is not handed over: no double kick
 *   ADS       the camera follows the sight (CameraSmoothBlender rate): the weapon moves back by
 *             the part the camera has followed; the follow is per step, not per rendered frame
 *   handling  the aim time of the pose layer, arms drain while aiming only (Physical.Aim),
 *             restoration, oxygen and hold breath (ModOptions key, CapsLock by default);
 *             support: none (no detection rule), QA can name one
 */
public final class FpRecoil {
    static final double TICK = TarkovRecoil.TICK_SECONDS;
    /** A gap between two frames this long is a pause or a hitch: the timeline holds still over it. */
    static final double PAUSE_S = 0.25;
    /** A next shot within the cycle (60 / rpm) plus this belongs to the held trigger (own margin: PZ fires on frames). */
    static final double BURST_MARGIN_S = 0.1;
    /**
     * A player's pose is current for this many world frames (IsoWorld's frame number, one per world update however
     * long it took; own value). The shot comes in the world update before or after that frame's pose; a long frame
     * on the main thread (a screenshot, a save, a hitch) is one frame, not a gap (VP-W7-R2: a wall-clock limit of
     * 0.5 s refused the shot after a QA screenshot).
     */
    static final int FRESH_FRAMES = 3;

    /** Why shot refused a shot: the last reason and a count per reason go to the stats (QA). */
    enum Refusal {
        OFF("off"),           // the integration off (QA)
        OPTION("option"),     // the player's option off
        VIEW("view"),         // not Viewpoint's first person (isometric, third person, no bridge)
        PLAYER("player"),     // no state from Lua for this character (not a local player, no Viewpoint)
        REQUEST("request"),   // no pose request (no own firearm in the hands)
        RECOIL("recoil"),     // the request names no weapon of the recoil data (a deployed underbarrel, a spent tube)
        HELD("held"),         // the request's item is not the one in the primary hand
        TRACK("track"),       // no recoil track (the layer has not posed this weapon, a part not in the data)
        ITEM("item"),         // the track is another item's than the request's
        POSE("pose"),         // the layer has not posed this player within FRESH_FRAMES world frames
        TIMELINE("timeline"); // the timeline took no shot

        final String key;

        Refusal(String key) {
            this.key = key;
        }
    }

    public static volatile boolean enabled = true, camera = true, option = true;
    /** W10 item 2: Tarkov's recoil on the third-person weapon (thirdPerson); false restores Viewpoint's arm kick there (QA). */
    public static volatile boolean tp = true;
    /** The local animation variable that holds the attack nodes at the grip's aim pose (tools/real_mode_nodes.py *_TWTp). */
    static final String TP_VARIABLE = "TWTpRecoil";
    /** The aim time while the handling core has none yet (third person; it feeds only the ADS follow, not used there). */
    static final double TP_ADS_FALLBACK_S = 0.35;
    public static volatile long tpFrames, tpShots;
    public static volatile long frames, shots, refused, kicksReplaced, cameraWrites, errors, nanos;
    /** Main thread: the refusals of shot per reason (Refusal order). */
    static final long[] refusedBy = new long[Refusal.values().length];
    public static volatile String lastError = "", lastRefusal = "-";
    static volatile String status = "not loaded";
    private static boolean attempted;
    private static TarkovRecoilData recoilData;
    private static TarkovHandlingData handlingData;
    private static TarkovHandling handling;

    private FpRecoil() {
    }

    // ------------------------------------------------------------------ data

    /** First call only: both resources of the active mod through PZ, then cached. */
    static synchronized boolean data() {
        if (!attempted) {
            attempted = true;
            try {
                ChooseGameInfo.Mod mod = ChooseGameInfo.getModDetails("EscapeFromZomboidFirearms");
                if (mod == null) {
                    throw new IllegalStateException("mod information unavailable");
                }
                Path recoil = null, hands = null;
                for (String root : new String[] {mod.getVersionDir(), mod.getCommonDir(), mod.getDir()}) {
                    if (root == null || root.isBlank()) {
                        continue;
                    }
                    Path r = Path.of(root).resolve(TarkovRecoilData.RESOURCE), h = Path.of(root).resolve(TarkovHandlingData.RESOURCE);
                    if (recoil == null && Files.isRegularFile(r)) {
                        recoil = r;
                    }
                    if (hands == null && Files.isRegularFile(h)) {
                        hands = h;
                    }
                }
                if (recoil == null || hands == null) {
                    throw new IllegalStateException("resources missing from the mod directories");
                }
                load(TarkovRecoilData.load(recoil), TarkovHandlingData.load(hands));
            } catch (Exception | LinkageError e) {
                status = "refused: " + e;
                System.out.println("[TWZB] first-person recoil " + status + "; Tarkov recoil in Viewpoint off");
            }
        }
        return handling != null;
    }

    /** Explicit data (offline checks). */
    static synchronized void load(TarkovRecoilData recoil, TarkovHandlingData hands) {
        recoilData = Objects.requireNonNull(recoil);
        handlingData = Objects.requireNonNull(hands);
        handling = new TarkovHandling(hands);
        attempted = true;
        status = "loaded " + recoil.weapons.size() + " weapons";
    }

    // ------------------------------------------------------------------ the timeline (no game types)

    /** The inputs of one frame; they hold from the first step after its time. */
    record Frame(TarkovRecoil.Inputs inputs, boolean autoMode, double cycle, boolean aiming, double adsSpeed,
                 TarkovHandling.Input handling, TarkovHandling.Support support, boolean pain) {
        Frame(TarkovRecoil.Inputs inputs, boolean autoMode, double cycle, boolean aiming, double adsSpeed,
              TarkovHandling.Input handling, TarkovHandling.Support support) {
            this(inputs, autoMode, cycle, aiming, adsSpeed, handling, support, false);
        }
    }

    record Due<T>(long tick, T value) {
    }

    record Shot(TarkovRecoil.Ammo ammo, long seed) {
    }

    /**
     * One step's result: the cores' channels in source units (weapon root metres / degrees;
     * camera: Tarkov's camera Euler, x pitch down, y yaw right, z roll), the camera's follow of
     * the sight (eye frame, model units) and the handling sample after the step. QA only:
     * rotorX is the rotation process without the hip breathing offset; shotAge is seconds
     * since the last consumed impulse, on the interpolated drawn timeline (-1 before one).
     */
    record State(long tick, double[] position, double[] procedural, double[] recoil, double[] camera, double[] lag,
                 double hipGain, int shotIndex, boolean stable, long shots, TarkovHandling.Sample hands,
                 double rotorX, double shotAge) {
        static State rest(long tick, TarkovHandling.Sample hands) {
            return new State(tick, new double[3], new double[3], new double[3], new double[3], new double[3], 0, 0, false, 0, hands, 0, -1);
        }
    }

    /** One local player's handling and the held weapon's recoil on the cores' fixed step. */
    static final class Timeline {
        final TarkovHandling handling;
        final TarkovHandling.State hands;
        final TarkovInjury.State injury = new TarkovInjury.State();
        boolean tremor;
        Track track;
        long ticks;
        private final ArrayDeque<Due<Frame>> frames = new ArrayDeque<>();
        private Frame frame;
        private TarkovHandling.Sample sample;

        Timeline(TarkovHandling handling) {
            this.handling = handling;
            hands = handling.new State();
        }

        long at(double t) {
            return Math.max(ticks, (long) Math.ceil(t * 60 - 1e-9));
        }

        void frame(double t, Frame f) {
            frames.add(new Due<>(at(t), Objects.requireNonNull(f)));
        }

        /** A new weapon item's recoil from now on; null: none (its pending shots go with it). */
        void track(Track next) {
            track = next;
            if (next != null) {
                next.restAt(ticks, sample);
            }
        }

        boolean shot(double t, TarkovRecoil.Ammo ammo, long seed) {
            if (track == null) {
                return false;
            }
            track.shots.add(new Due<>(at(t), new Shot(ammo, seed)));
            return true;
        }

        void advance(double t) {
            long target = (long) Math.floor(t * 60 + 1e-9);
            while (ticks < target) {
                while (!frames.isEmpty() && frames.peek().tick() <= ticks) {
                    frame = frames.remove().value();
                }
                if (frame != null) {
                    sample = hands.advance(TICK, frame.handling());
                    tremor = injury.advance(TICK, frame.pain());
                    if (track != null) {
                        track.step(ticks, frame, sample, tremor);
                    }
                }
                ticks++;
            }
        }

        TarkovHandling.Sample hands() {
            return sample;
        }

        /** Outside the first person (other views): arms and oxygen rest over real time; the recoil holds still. */
        void rest(double seconds) {
            rest(seconds, TarkovInjury.Context.NEUTRAL);
        }

        void rest(double seconds, TarkovInjury.Context context) {
            if (seconds > 0) {
                sample = hands.advance(Math.min(seconds, 3600), new TarkovHandling.Input(null, TarkovHandling.Pose.STAND,
                        false, false, false, TarkovHandling.Support.NONE, context.handling()));
                tremor = injury.advance(Math.min(seconds, 3600), context.pain());
            }
        }

        /** The drawn state at t (between the last two steps), or null without a weapon. */
        State sample(double t) {
            return track == null ? null : track.sample(t * 60 - ticks);
        }
    }

    /** Weapon root (Tarkov) <-> Prop1 of one weapon: the source basis, its grip offset and pistol roll. */
    record Geometry(double[] prop1FromRoot, double[] rotationCentre, double[] recoilCentre) {
        static Geometry of(TarkovRecoilData data, String key, double gripOffsetMetres, boolean rolled) {
            double u = GripIK.U;
            // fp_poses.prop_space: diag(U) PZ_SOURCE_BASIS (-1, -1, 1), y -= gripOffset U; pistols (x, z) -> (-x, -z)
            double sx = rolled ? 1 : -1, sy = -1, sz = rolled ? -1 : 1;
            double[] a = {sx * u, 0, 0, 0, 0, sy * u, 0, -gripOffsetMetres * u, 0, 0, sz * u, 0, 0, 0, 0, 1};
            var props = TarkovRecoilData.map(TarkovRecoilData.map(data.weapons, key), "props");
            return new Geometry(a, centre(props, "RotationCenter"), centre(props, "RecoilCenter"));
        }

        private static double[] centre(java.util.Map<String, Object> props, String key) {
            if (!props.containsKey(key)) {
                return new double[3];
            }
            var c = TarkovRecoilData.map(props, key);
            return new double[] {TarkovRecoilData.number(c, "x"), TarkovRecoilData.number(c, "y"), TarkovRecoilData.number(c, "z")};
        }
    }

    /** One weapon item's recoil state, its geometry and the camera's follow (per step). */
    static final class Track {
        final TarkovRecoil core;
        final TarkovRecoilData data;
        final Geometry geometry;
        final double[] rootFromProp1;
        final ArrayDeque<Due<Shot>> shots = new ArrayDeque<>();
        /** own Prop1 <- the selected sight's camera (eye axes), or null: no sight to follow. */
        double[] sight;
        TarkovRecoil.Assembly assembly;
        private final double smoothOut;
        private final double[] followRotation = new double[3], previousTarget = new double[3], lag = new double[3];
        /** W10 item 2: the recoil push and the hip offset of the last two steps, and their drawn values (sample). */
        private double[] recoilPositionPrevious = new double[3], recoilPositionCurrent = new double[3];
        private double[] hipOffsetPrevious = new double[3], hipOffsetCurrent = new double[3];
        double[] sampledRecoilPosition = new double[3], sampledHipOffset = new double[3];
        private long lastShotTick = -1, shotCount;
        private State previous, current;

        Track(TarkovRecoilData data, TarkovRecoil.Assembly assembly, long seed, Geometry geometry) {
            this.data = data;
            this.assembly = assembly;
            this.geometry = geometry;
            core = new TarkovRecoil(data, assembly, seed);
            rootFromProp1 = GripIK.inverse(geometry.prop1FromRoot());
            smoothOut = TarkovRecoilData.number(data.player, "CameraSmoothOut");
        }

        void restAt(long tick, TarkovHandling.Sample hands) {
            previous = current = State.rest(tick, hands);
            recoilPositionPrevious = recoilPositionCurrent = new double[3];
            hipOffsetPrevious = hipOffsetCurrent = new double[3];
        }

        /** Same weapon, another assembly (parts, a reload): the core resets its rotation process. */
        void assembly(TarkovRecoil.Assembly next) {
            assembly = next;
            core.setAssembly(next);
        }

        void step(long tick, Frame f, TarkovHandling.Sample hands) {
            step(tick, f, hands, false);
        }

        void step(long tick, Frame f, TarkovHandling.Sample hands, boolean tremor) {
            boolean auto = f.autoMode() && lastShotTick >= 0 && (tick - lastShotTick) * TICK <= f.cycle() + BURST_MARGIN_S;
            TarkovRecoil.Inputs in = f.inputs();
            core.inputs(new TarkovRecoil.Inputs(in.aiming(), auto, in.pose(), in.motion(), condition(hands, f.support(), in.condition(), tremor), in.activity()));
            while (!shots.isEmpty() && shots.peek().tick() <= tick) {
                Shot s = shots.remove().value();
                core.shot(assembly, s.ammo(), s.seed());
                lastShotTick = tick;
                shotCount++;
            }
            TarkovRecoil.Output o = core.update(TICK);
            double[] position = vec(o.weaponPositionMetres()), procedural = vec(o.proceduralRotationDegrees()), recoil = vec(o.recoilRotationDegrees());
            double[] cameraRecoil = vec(o.recoilCameraDegrees());
            // LerpCamera: Euler(CameraRotation) between the 180 degree camera offsets, i.e. (x, -y, -z) in the camera's axes
            double[] cam = sub(vec(o.cameraRotationDegrees()), cameraRecoil);
            double[] eff = {cam[0], -cam[1], -cam[2]};
            double[] rotor = vec(core.handRotationRecoil());
            boolean moving = rotor[0] * rotor[0] + rotor[1] * rotor[1] + rotor[2] * rotor[2] > 0;
            double[] shown;
            if (f.aiming() && (!auto || moving)) {
                // camera-to-weapon follow: the camera without its yaw times Euler(the rotation process);
                // a stable held trigger keeps the previous target with the camera's own roll
                boolean keep = auto && o.stable();
                double[] target = keep ? new double[] {previousTarget[0], previousTarget[1], eff[2]}
                        : new double[] {eff[0] + rotor[0], rotor[1], eff[2] + rotor[2]};
                lerp(followRotation, target, o.cameraFollowSpeed());
                if (!keep) {
                    System.arraycopy(target, 0, previousTarget, 0, 3);
                }
                shown = followRotation.clone();
            } else if (!f.aiming()) {
                lerp(followRotation, eff, core.cameraFollowMax());
                System.arraycopy(eff, 0, previousTarget, 0, 3);
                shown = followRotation.clone();
            } else {
                shown = eff; // aiming, the trigger held, the rotation process at rest: no follow in this step
            }
            double[] cameraOut = add(shown, cameraRecoil);
            // the camera's position follows the sight while aiming, else returns to its hip place
            double[] goal = f.aiming() && sight != null ? sightShift(position, procedural, recoil) : new double[3];
            double k = f.aiming() ? clamp01(f.adsSpeed() * o.cameraSmooth() * TICK) : clamp01(smoothOut * TICK);
            lerp(lag, goal, k);
            recoilPositionPrevious = recoilPositionCurrent;
            recoilPositionCurrent = vec(o.recoilPositionMetres());
            hipOffsetPrevious = hipOffsetCurrent;
            hipOffsetCurrent = vec(o.hipOffsetDegrees());
            previous = current;
            current = new State(tick + 1, position, procedural, recoil, cameraOut, lag.clone(), o.hipGain(), o.shotIndex(), o.stable(), shotCount, hands,
                    rotor[0], lastShotTick < 0 ? -1 : (tick + 1 - lastShotTick) * TICK);
        }

        private TarkovRecoil.Condition condition(TarkovHandling.Sample s, TarkovHandling.Support support,
                                               TarkovRecoil.Condition source, boolean tremor) {
            boolean mounted = support.kind() != TarkovHandling.SupportKind.NONE;
            return new TarkovRecoil.Condition(s == null ? 1 : (float) clamp01(s.hands() / s.handsCapacity()), source.stamina(),
                    s == null ? 1 : (float) clamp01(s.oxygen() / s.oxygenCapacity()), source.overweight(), source.armDamage(),
                    s != null && s.breathState() == TarkovHandling.BreathState.HOLDING, source.fracture(), source.tremor() || tremor, mounted,
                    support.kind() == TarkovHandling.SupportKind.VERTICAL, support.kind() == TarkovHandling.SupportKind.BIPOD);
        }

        /** The sight camera's displacement by these channels, in its own (= the eye's in ADS) frame. */
        double[] sightShift(double[] position, double[] procedural, double[] recoil) {
            double[] m = GripIK.mulNew(GripIK.inverse(sight), GripIK.mulNew(delta(position, procedural, recoil), sight));
            return new double[] {m[3], m[7], m[11]};
        }

        /** The drawn state at a fraction of the next step after the last one (0..1). */
        State sample(double alpha) {
            if (current == null) {
                return null;
            }
            double a = clamp01(alpha);
            State p = previous == null ? current : previous, c = current;
            sampledRecoilPosition = mix(previous == null ? recoilPositionCurrent : recoilPositionPrevious, recoilPositionCurrent, a);
            sampledHipOffset = mix(previous == null ? hipOffsetCurrent : hipOffsetPrevious, hipOffsetCurrent, a);
            return new State(c.tick(), mix(p.position(), c.position(), a), mix(p.procedural(), c.procedural(), a), mix(p.recoil(), c.recoil(), a),
                    mix(p.camera(), c.camera(), a), mix(p.lag(), c.lag(), a), p.hipGain() + (c.hipGain() - p.hipGain()) * a,
                    c.shotIndex(), c.stable(), c.shots(), c.hands(), p.rotorX() + (c.rotorX() - p.rotorX()) * a,
                    c.shotAge() < 0 ? -1 : c.shotAge() - (c.tick() - p.tick()) * (1 - a) * TICK);
        }

        /**
         * W10 item 2: own Prop1 <- the weapon moved by the recoil channels of the drawn state only (thirdPerson): the recoil
         * push and the rotation process with its curves about RecoilCenter; no procedural sway or breath and no hip offset
         * (UseOffset adds it to the recoil rotation), so the third-person pose at rest stays the clips' own.
         */
        double[] recoilDelta(State s) {
            return delta(sampledRecoilPosition, new double[3], sub(s.recoil(), sampledHipOffset));
        }

        /** own Prop1 <- the weapon moved by a state's channels (Prop1 model units). */
        double[] delta(State s) {
            return delta(s.position(), s.procedural(), s.recoil());
        }

        double[] delta(double[] position, double[] procedural, double[] recoil) {
            double[] cr = geometry.rotationCentre(), ck = geometry.recoilCentre();
            // ApplyComplexRotation: position; Euler(x, 0, z) Euler(0, y, 0) about RotationCenter; Euler(recoil) about RecoilCenter
            double[] rp = GripIK.mulNew(GripIK.mulNew(rotX(procedural[0]), rotZ(procedural[2])), rotY(procedural[1]));
            double[] rr = GripIK.mulNew(GripIK.mulNew(rotY(recoil[1]), rotX(recoil[0])), rotZ(recoil[2]));
            double[] w = translation(position);
            w = GripIK.mulNew(w, about(rp, cr));
            w = GripIK.mulNew(w, about(rr, ck));
            return GripIK.mulNew(GripIK.mulNew(geometry.prop1FromRoot(), w), rootFromProp1);
        }

        /** Viewpoint's camera additive of a state: yaw right, pitch up, roll (its up vector to the right), radians. */
        static double[] viewpoint(State s) {
            double[] c = s.camera();
            return new double[] {Math.toRadians(c[1]), -Math.toRadians(c[0]), -Math.toRadians(c[2])};
        }

        long shotCount() {
            return shotCount;
        }

        private static double[] about(double[] r, double[] c) {
            return GripIK.mulNew(GripIK.mulNew(translation(c), r), translation(new double[] {-c[0], -c[1], -c[2]}));
        }
    }

    static double[] translation(double[] t) {
        return new double[] {1, 0, 0, t[0], 0, 1, 0, t[1], 0, 0, 1, t[2], 0, 0, 0, 1};
    }

    static double[] rotX(double deg) {
        double c = Math.cos(Math.toRadians(deg)), s = Math.sin(Math.toRadians(deg));
        return new double[] {1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0, 0, 0, 0, 1};
    }

    static double[] rotY(double deg) {
        double c = Math.cos(Math.toRadians(deg)), s = Math.sin(Math.toRadians(deg));
        return new double[] {c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0, 0, 0, 0, 1};
    }

    static double[] rotZ(double deg) {
        double c = Math.cos(Math.toRadians(deg)), s = Math.sin(Math.toRadians(deg));
        return new double[] {c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    static double[] vec(TarkovRecoil.Vec3 v) {
        return new double[] {v.x(), v.y(), v.z()};
    }

    static double[] add(double[] a, double[] b) {
        return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    static double[] sub(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    static void lerp(double[] a, double[] b, double t) {
        for (int i = 0; i < 3; i++) {
            a[i] += (b[i] - a[i]) * t;
        }
    }

    static double[] mix(double[] a, double[] b, double t) {
        return new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    static double clamp01(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    // ------------------------------------------------------------------ the game (main thread)

    /** A local player's timeline and what the frames and Lua hand over. */
    static final class Player {
        final Timeline timeline;
        final long seed = System.nanoTime() * 0x9E3779B97F4A7C15L;
        long origin, last;
        boolean started;
        // the held weapon
        Object item;
        String key = "", parts = "", ammoKey = "";
        int roundsAtAssembly, handlingCount = -1;
        boolean supportDirty;
        TarkovHandlingData.Assembly handlingAssembly;
        TarkovHandling.Support support = TarkovHandling.Support.NONE;
        // Lua, every tick (TWZB.recoilState)
        volatile boolean holdKey, chambered, autoMode;
        volatile int rounds;
        volatile String ammo = "", supportName = "";
        volatile TarkovInjury.Context injury = TarkovInjury.Context.NEUTRAL;
        // the frames
        double frameT, yaw, pitch, x, y, z;
        boolean haveFrame;
        long shotCount;
        double lastShot = -1e9;
        boolean kickPending;
        /** W10 item 2: the game is paused (Lua), the local TWTpRecoil variable as last set. */
        volatile boolean paused;
        boolean tpVariable;
        /** The third-person kick thirdPerson last applied (recoilDelta) and the world frame it posed (tpKick). */
        volatile double[] tpKick;
        volatile long tpKickFrame = Long.MIN_VALUE;
        double[] cameraAdd = new double[3], delta;
        long cameraFrame = -1, poseAt, restAt, poseFrame = Long.MIN_VALUE;
        State state;
        String why = "";

        Player(TarkovHandling handling) {
            timeline = new Timeline(handling);
        }

        /** The layer posed this player at a world frame and a time (pose; offline checks give their own). */
        void posed(long frame, long now) {
            poseFrame = frame;
            poseAt = now;
        }

        /** The pose is current at this world frame: posed in it or at most FRESH_FRAMES frames before. */
        boolean posed(long frame) {
            return poseFrame != Long.MIN_VALUE && frame >= poseFrame && frame - poseFrame <= FRESH_FRAMES;
        }

        /** Seconds on this player's timeline; a gap longer than PAUSE_S is skipped (a pause, a hitch, another view). */
        double clock(long now) {
            if (!started) {
                started = true;
                origin = now;
            } else if (now - last > PAUSE_S * 1e9) {
                origin += now - last - (long) (TICK * 1e9);
            }
            last = Math.max(last, now);
            return (now - origin) / 1e9;
        }
    }

    static final ConcurrentHashMap<IsoGameCharacter, Player> players = new ConcurrentHashMap<>();

    /** IsoWorld's frame number (one per world update), -1 without a world. */
    static long worldFrame() {
        IsoWorld w = IsoWorld.instance;
        return w == null ? -1 : w.getFrameNo();
    }

    /** Every tick from Lua (TW_FirstPersonRecoil): hold breath held, rounds, chamber, actual cartridge, automatic mode, the option. */
    static void state(IsoGameCharacter c, KahluaTable t) {
        if (c == null || t == null || !ViewpointBridge.ready() || !data()) {
            return; // without Viewpoint nothing is loaded or kept
        }
        Player p = players.computeIfAbsent(c, k -> new Player(handling));
        p.holdKey = Boolean.TRUE.equals(t.rawget("hold"));
        p.chambered = Boolean.TRUE.equals(t.rawget("chambered"));
        p.autoMode = Boolean.TRUE.equals(t.rawget("auto"));
        Object rounds = t.rawget("rounds");
        p.rounds = rounds instanceof Double d && Double.isFinite(d) ? (int) Math.max(0, Math.min(10000, d)) : 0;
        Object ammo = t.rawget("ammo");
        p.ammo = ammo instanceof String s ? s : "";
        option = !Boolean.FALSE.equals(t.rawget("enabled"));
        p.injury = TarkovInjuryPz.read(c, !Boolean.FALSE.equals(t.rawget("injury")));
        p.paused = Boolean.TRUE.equals(t.rawget("paused"));
        if (!tpActive(c, p, FpPose.requests.get(c))) {
            tpVariable(c, p, false); // a view switch, a weapon change, death, a vehicle, the pause: never left on (W10 item 2)
        }
        long now = System.nanoTime();
        // FB1-C: the isometric/third-person adapter owns the same hands State there.
        if (!Boolean.TRUE.equals(t.rawget("paused")) && !p.posed(worldFrame()) && p.restAt != 0 && !ArmStamina.ownsStep(c)) {
            p.timeline.rest((now - Math.max(p.restAt, p.poseAt)) / 1e9, p.injury);
        }
        p.restAt = now;
    }

    /**
     * FpPose.firstPerson, after the pose of this frame (eye <- own Prop1, before the chest
     * clearance): the same pose with the recoil, sway and breath of this frame, and the ADS
     * camera's follow of the sight (sEff: the smooth ADS weight). Unchanged when off.
     */
    static double[] pose(AnimationPlayer ap, IsoGameCharacter c, FpPose.Request r, double[] q, double sEff, double adsSeconds, long t0) {
        return panic(ap, c, r, recoil(ap, c, r, q, sEff, adsSeconds, t0));
    }

    /** Tarkov's recoil, sway and breath over the pose; the pose unchanged when the layer does not run. */
    static double[] recoil(AnimationPlayer ap, IsoGameCharacter c, FpPose.Request r, double[] q, double sEff, double adsSeconds, long t0) {
        if (!enabled || !option || r.recoilKey.isEmpty() || r.eye == null || !players.containsKey(c) || !data()) {
            return q; // players: only after Lua's state (TW_FirstPersonRecoil) for this character
        }
        long start = System.nanoTime();
        try {
            Player p = players.computeIfAbsent(c, k -> new Player(handling));
            double t = p.clock(t0);
            if (!weapon(p, r)) {
                return q;
            }
            State s = advance(p, frame(ap, c, r, p, t, adsSeconds), t, worldFrame(), t0);
            if (s == null) {
                return q;
            }
            frames++;
            double[] lag = s.lag();
            double[] out = GripIK.mulNew(q, p.delta);
            out[3] -= lag[0] * sEff;
            out[7] -= lag[1] * sEff;
            out[11] -= lag[2] * sEff;
            return out;
        } catch (Throwable e) {
            errors++;
            lastError = e.toString();
            return q;
        } finally {
            nanos += System.nanoTime() - start;
        }
    }

    public static volatile long panicFrames;
    public static volatile double panicYawDeg, panicPitchDeg;
    /** The hands' own float of the last frame, cm: how far the weapon was moved in the eye's plane. */
    public static volatile double panicShiftCm;
    /** The lever arm the source tremor angles are applied over, metres (the drawn muzzle from the hand). */
    static final double PANIC_LEVER_M = 0.55;
    /** QA (W6 item 5, A/B in one run): FB2 item 16's turn of the weapon, which also turned its aim. */
    static volatile boolean legacyPanic;
    public static void qaLegacyPanic(boolean on) { legacyPanic = on; }

    /**
     * The stress/panic sway of the local shooter (PanicSway.sample, degrees, stepped once per world frame).
     * User's decision of 24.10.2026, item 5: in the first person of Viewpoint the moodles must not lower
     * accuracy - the bullet flies where the sight points, only the hands start to float a little. So the
     * tremor is a TRANSLATION of the posed weapon and its hands in the eye's own plane (right/up), never a
     * turn: the sight axis stays parallel to the eye's forward axis, the sight picture and the zeroed shot
     * keep the point of aim, and the arms solve to the moved weapon. The amplitude is the same Tarkov
     * Breath tremor (PanicSway) applied over the muzzle's lever arm, so the visible float is unchanged.
     * (FB2 item 16 turned the weapon and its aim; the user overrides that.) The isometric path
     * (PanicSway.onModel) is not touched.
     */
    static double[] panic(AnimationPlayer ap, IsoGameCharacter c, FpPose.Request r, double[] q) {
        if (!(c instanceof IsoPlayer pl) || q == null || r.eye == null || r.eye.length < 4) {
            return q;
        }
        PanicSway.Sample s = PanicSway.sample(pl);
        if (s.multiplier() == 0 || s.yawDegrees() == 0 && s.pitchDegrees() == 0) {
            return q;
        }
        if (legacyPanic) {
            double[] up = {0, 1, 0};
            double[] heading = FpPose.lookModel(r.eye[3], 0, ap.getRenderedAngle());
            double[] right = GripIK.normalize(GripIK.cross(heading, up));
            double[] pivot = GripIK.pos(q);
            double[] turn = GripIK.mulNew(GripIK.rigid(up, Math.toRadians(s.yawDegrees()), pivot),
                    GripIK.rigid(right, Math.toRadians(s.pitchDegrees()), pivot));
            panicFrames++;
            panicYawDeg = s.yawDegrees();
            panicPitchDeg = s.pitchDegrees();
            panicShiftCm = 0;
            return GripIK.mulNew(turn, q);
        }
        double lever = PANIC_LEVER_M * GripIK.U;
        double lateral = Math.tan(Math.toRadians(s.yawDegrees())) * lever;
        double vertical = Math.tan(Math.toRadians(s.pitchDegrees())) * lever;
        double[] out = q.clone();
        out[3] += lateral;
        out[7] += vertical;
        panicFrames++;
        panicYawDeg = s.yawDegrees();
        panicPitchDeg = s.pitchDegrees();
        panicShiftCm = Math.hypot(lateral, vertical) / GripIK.CM;
        return out;
    }

    /**
     * W10 item 2 (user's report 27.10.2026: in Viewpoint's third person the rifle "jumps out of the hands" on a shot), from
     * GripIK.apply after the clip weapon's own turns: Tarkov's recoil on the weapon of the local player in Viewpoint's third
     * person - the same per-item track as the first person (shot takes the third-person shots too), its recoil channels only
     * (Track.recoilDelta), as a model-space transform for the clip weapon and the right hand (GripIK solves the right arm to
     * it and transfers the support hand onto the kicked weapon). Viewpoint's arm kick of the update is set to zero (its camera
     * kick stays: the camera is not ours in the third person) and the local TWTpRecoil variable holds the attack nodes at
     * the grip's aim pose, so the kick on the weapon is Tarkov's alone. Null, and nothing touched, for any other character
     * or view, with the layer off or without our Lua state (players).
     */
    static double[] thirdPerson(AnimationPlayer ap, IsoGameCharacter c, double[] P, long t0) {
        Player p = c == null ? null : players.get(c);
        if (p == null) {
            return null;
        }
        FpPose.Request r = FpPose.requests.get(c);
        p.tpKick = null;
        if (!tpActive(c, p, r) || !data()) {
            tpVariable(c, p, false);
            return null;
        }
        long start = System.nanoTime();
        try {
            double t = p.clock(t0);
            State s = weapon(p, r) ? tpAdvance(ap, c, r, p, t, t0) : null;
            if (s == null) {
                tpVariable(c, p, false);
                return null;
            }
            tpVariable(c, p, true);
            ViewpointBridge.dropArmKick();
            tpFrames++;
            double[] kick = p.timeline.track.recoilDelta(s);
            p.tpKickFrame = worldFrame();
            p.tpKick = kick;
            return GripIK.mulNew(GripIK.mulNew(P, kick), GripIK.inverse(P));
        } catch (Throwable e) {
            errors++;
            lastError = e.toString();
            return null;
        } finally {
            nanos += System.nanoTime() - start;
        }
    }

    /**
     * The Tarkov kick of the drawn third-person weapon (Prop1 <- the weapon moved, own Prop1 model units, as thirdPerson
     * applied it to c's Prop1) when thirdPerson posed it within FpPose.FRESH_FRAMES of frame; null at rest, in another
     * view, for another character. ShotOwner.thirdPersonAim turns the shot by it: the bullet keeps the bore's kick.
     */
    static double[] tpKick(IsoGameCharacter c, long frame) {
        Player p = c == null ? null : players.get(c);
        double[] kick = p == null ? null : p.tpKick;
        return kick != null && FpPose.freshFrame(p.tpKickFrame, frame) ? kick : null;
    }

    /** The third-person frame on the timeline: Viewpoint's look (the body's facing without it), the engine's aim, the pose current. */
    static State tpAdvance(AnimationPlayer ap, IsoGameCharacter c, FpPose.Request r, Player p, double t, long t0) {
        Double lookYaw = ViewpointBridge.lookYaw(), lookPitch = ViewpointBridge.lookPitch();
        double yaw = lookYaw != null ? lookYaw : Math.atan2(c.getForwardDirectionY(), c.getForwardDirectionX());
        Frame f = frame(ap, c, r, p, t, adsSeconds(c, r, TP_ADS_FALLBACK_S), yaw, lookPitch != null ? lookPitch : 0, c.isAiming() && !r.spent);
        return advance(p, f, t, worldFrame(), t0);
    }

    /** The third-person layer runs for this player now: Viewpoint's third person, the local player, our weapon in the hands. */
    static boolean tpActive(IsoGameCharacter c, Player p, FpPose.Request r) {
        return enabled && option && tp && p != null && r != null && !r.recoilKey.isEmpty() && !p.paused
                && c instanceof IsoPlayer pl && pl.isLocalPlayer() && pl.getPlayerNum() == 0 && !pl.isDead() && pl.getVehicle() == null
                && pl.getPrimaryHandItem() == r.item && ViewpointBridge.ready() && ViewpointBridge.state() == 2;
    }

    /** The local TWTpRecoil animation variable (custom: not network-synced), written only when it changes. */
    static void tpVariable(IsoGameCharacter c, Player p, boolean on) {
        if (p.tpVariable != on) {
            p.tpVariable = on;
            c.setVariable(TP_VARIABLE, on);
        }
    }

    /**
     * A frame of the layer for a player (pose; offline checks give their own): the inputs at t onto the timeline,
     * the drawn state, its offset and camera additive; the pose is current at this world frame. Null without a weapon.
     */
    static State advance(Player p, Frame f, double t, long frame, long t0) {
        p.timeline.frame(t, f);
        p.timeline.advance(t);
        State s = p.timeline.sample(t);
        if (s == null) {
            return null;
        }
        p.state = s;
        p.delta = p.timeline.track.delta(s);
        System.arraycopy(Track.viewpoint(s), 0, p.cameraAdd, 0, 3);
        p.posed(frame, t0);
        return s;
    }

    /** The held item's recoil track (a new one for another item) and both assemblies from the request and Lua's state. */
    static boolean weapon(Player p, FpPose.Request r) {
        if (!recoilData.weapons.containsKey(r.recoilKey) || !handlingData.weapons.containsKey(r.recoilKey)) {
            p.why = "weapon not in the recoil data";
            return false;
        }
        String ammoTpl = recoilData.ammo.containsKey(p.ammo) && handlingData.items.containsKey(p.ammo) ? p.ammo
                : (String) TarkovRecoilData.map(recoilData.weapons, r.recoilKey).get("defaultAmmoTpl");
        int count = p.rounds + (p.chambered ? 1 : 0);
        String sig = r.recoilKey + "|" + String.join(";", r.parts);
        boolean newItem = p.item != r.item || p.timeline.track == null || !p.key.equals(r.recoilKey);
        if (newItem || !sig.equals(p.parts) || !ammoTpl.equals(p.ammoKey) || count != p.handlingCount || p.supportDirty) {
            List<String> tpls = new ArrayList<>();
            List<TarkovHandlingData.Installed> installed = new ArrayList<>();
            for (String part : r.parts) {
                int eq = part.indexOf('=');
                String path = part.substring(0, eq), tpl = part.substring(eq + 1);
                if (!recoilData.modifiers.containsKey(tpl) || !handlingData.items.containsKey(tpl)) {
                    p.why = "part " + tpl + " not in the data";
                    p.timeline.track(null);
                    p.item = null;
                    return false;
                }
                tpls.add(tpl);
                installed.add(new TarkovHandlingData.Installed(path, tpl, 1));
            }
            if (p.rounds > 0) {
                installed.add(new TarkovHandlingData.Installed("TW:magazine", ammoTpl, p.rounds));
            }
            if (p.chambered) {
                installed.add(new TarkovHandlingData.Installed("TW:chamber", ammoTpl, 1));
            }
            p.handlingAssembly = handlingData.assemble(r.recoilKey, installed);
            // The recoil assembly takes the contents at a reload or a part change, not at each shot:
            // a new assembly resets the core's rotation process (its parameter-change path).
            boolean refill = newItem || !sig.equals(p.parts) || !ammoTpl.equals(p.ammoKey) || count > p.roundsAtAssembly;
            if (refill) {
                float contents = (float) (handlingData.items.get(ammoTpl).massKg() * count);
                TarkovRecoil.Assembly a = recoilData.assembly(r.recoilKey, tpls, false, contents, 0);
                if (newItem) {
                    Track track = new Track(recoilData, a, p.seed ^ System.identityHashCode(r.item), Geometry.of(recoilData, r.recoilKey, r.gripOffset, r.rolled));
                    p.timeline.track(track);
                } else {
                    p.timeline.track.assembly(a);
                }
                p.roundsAtAssembly = count;
            }
            p.item = r.item;
            p.key = r.recoilKey;
            p.parts = sig;
            p.ammoKey = ammoTpl;
            p.handlingCount = count;
            p.support = support(p);
            p.supportDirty = false;
        }
        p.timeline.track.sight = r.sight;
        p.why = "";
        return true;
    }

    /** The frame's inputs: aim, stance, mouse and walk, the handling request. */
    static Frame frame(AnimationPlayer ap, IsoGameCharacter c, FpPose.Request r, Player p, double t, double adsSeconds) {
        return frame(ap, c, r, p, t, adsSeconds, r.eye[3], r.eye[4], r.aiming);
    }

    /**
     * The same with the look and the aim given (third person: Viewpoint's look and the engine's aim - the first-person
     * pose's eye and its aim flag are not renewed there).
     */
    static Frame frame(AnimationPlayer ap, IsoGameCharacter c, FpPose.Request r, Player p, double t, double adsSeconds, double yaw, double pitch,
                       boolean aiming) {
        double dt = p.haveFrame ? t - p.frameT : 0;
        double yawRate = 0, pitchRate = 0, right = 0, forward = 0;
        if (dt > 1e-4 && dt < PAUSE_S) {
            yawRate = Math.toDegrees(wrap(yaw - p.yaw)) / dt;
            pitchRate = Math.toDegrees(pitch - p.pitch) / dt;
            double vx = (c.getX() - p.x) / dt * TarkovBallistics.METRES_PER_CELL, vy = (c.getY() - p.y) / dt * TarkovBallistics.METRES_PER_CELL;
            right = -vx * Math.sin(yaw) + vy * Math.cos(yaw);
            forward = vx * Math.cos(yaw) + vy * Math.sin(yaw);
        }
        p.frameT = t;
        p.yaw = yaw;
        p.pitch = pitch;
        p.x = c.getX();
        p.y = c.getY();
        p.z = c.getZ();
        p.haveFrame = true;
        boolean sneaking = c.isSneaking(), sprinting = c.isSprinting();
        boolean walking = c.isPlayerMoving() && Math.hypot(right, forward) > 0.2;
        int preset = sneaking ? 3 : 0;
        // Walk.Speed: the PZ gait has no speed slider; Tarkov's default is the full speed (SetCharacterMovementSpeed(MaxSpeed))
        TarkovRecoil.Activity activity = new TarkovRecoil.Activity(true, walking, preset, walking ? 1 : 0,
                walking ? (float) stepFrequency(ap, sprinting) : 1f, 0f); // head bob: Viewpoint's own eye
        TarkovRecoil.Motion motion = new TarkovRecoil.Motion(new TarkovRecoil.Vec3((float) yawRate, (float) pitchRate, 0),
                new TarkovRecoil.Vec3((float) right, 0, (float) forward), TarkovRecoil.Vec3.ZERO, 0);
        int pose = sneaking ? 1 : 2;
        TarkovRecoil.Inputs inputs = new TarkovRecoil.Inputs(aiming, false, pose, motion, p.injury.recoil(), activity);
        TarkovHandling.Input hands = new TarkovHandling.Input(p.handlingAssembly, sneaking ? TarkovHandling.Pose.CROUCH : TarkovHandling.Pose.STAND,
                false, aiming, p.holdKey, p.support, p.injury.handling());
        double cycle = r.rpm > 0 ? 60 / r.rpm : 0;
        return new Frame(inputs, p.autoMode && r.rpm > 0, cycle, aiming, 1 / Math.max(0.05, adsSeconds), hands, p.support, p.injury.pain());
    }

    /** Tarkov's step frequency (0.5 / the time between two steps, its clamps) from the PZ locomotion clip that plays. */
    static double stepFrequency(AnimationPlayer ap, boolean sprinting) {
        AnimationMultiTrack mt = ap == null ? null : ap.getMultiTrack();
        AnimationTrack best = null;
        if (mt != null) {
            for (AnimationTrack t : mt.getTracks()) {
                if (t == null || t.getClip() == null || !t.isLooping() || t.getBlendWeight() <= 0) {
                    continue;
                }
                String n = t.getClip().name.toLowerCase(Locale.ROOT);
                if ((n.contains("walk") || n.contains("run") || n.contains("sprint") || n.contains("sneak") || n.contains("strafe") || n.contains("jog"))
                        && (best == null || t.getBlendWeight() > best.getBlendWeight())) {
                    best = t;
                }
            }
        }
        if (best == null || best.getDuration() <= 0 || Math.abs(best.getSpeedDelta()) < 1e-3) {
            return 1; // WalkEffector.StepFrequency default
        }
        double step = best.getDuration() / Math.abs(best.getSpeedDelta()) / 2; // a cycle of the clip is two steps
        // Player step coroutines: sprint clamp(0.3, 0.8); run/walk clamp(0.7 - Walk.Speed / 2, 1.2), Walk.Speed 1 here
        return 0.5 / (sprinting ? Math.max(0.3, Math.min(0.8, step)) : Math.max(0.7 - 0.5, Math.min(1.2, step)));
    }

    /** The QA support of a player (none by default: no detection rule), checked against the assembly. */
    static TarkovHandling.Support support(Player p) {
        String kind = p.supportName;
        try {
            TarkovHandling.Support s = switch (kind) {
                case "vertical" -> TarkovHandling.Support.VERTICAL;
                case "horizontal" -> TarkovHandling.Support.HORIZONTAL;
                case "bipod" -> {
                    String path = p.handlingAssembly == null ? null
                            : p.handlingAssembly.installedBipods().keySet().stream().sorted().findFirst().orElse(null);
                    yield path == null ? TarkovHandling.Support.NONE : new TarkovHandling.Support(TarkovHandling.SupportKind.BIPOD, path);
                }
                default -> TarkovHandling.Support.NONE;
            };
            handling.support(p.handlingAssembly, s); // not mountable, no installed bipod: refused
            return s;
        } catch (RuntimeException e) {
            return TarkovHandling.Support.NONE;
        }
    }

    /** The aim time for the pose layer: the handling core's (neutral conditions: the B1 formula of FpPose), else the fallback. */
    static double adsSeconds(IsoGameCharacter c, FpPose.Request r, double fallback) {
        Player p = c == null ? null : players.get(c);
        if (!enabled || !option || p == null || p.handlingAssembly == null || p.item != r.item) {
            return fallback;
        }
        TarkovHandling.Sample s = p.timeline.hands();
        return s != null && s.adsSeconds() > 0 ? s.adsSeconds() : fallback;
    }

    /** An attack leaves the carry pose at once. A confirmed FP round clears the saved choice in Carry. */
    static boolean firing(IsoGameCharacter c, long t0) {
        return c instanceof IsoPlayer pl && !pl.isDoShove() && (pl.isAttackStarted() || pl.isPerformingAttackAnimation());
    }

    /**
     * PatchMuzzle.Flash, after MuzzleFx played a shot of an own weapon: one recoil impulse for
     * a local player in Viewpoint's first person whose weapon has recoil data. True when the
     * shot was taken; ViewpointBridge then hands Viewpoint no camera kick (replacesKick).
     */
    public static boolean shot(IsoGameCharacter c) {
        if (AnimOptOut.blocks(c)) return false; // W8 animopt: no recoil impulse, Viewpoint's own camera kick stays
        try {
            if (c == null) {
                return refuse(Refusal.PLAYER);
            }
            return shot(players.get(c), FpPose.requests.get(c), c.getPrimaryHandItem(), ViewpointBridge.state(), worldFrame(), System.nanoTime());
        } catch (Throwable e) {
            errors++;
            lastError = e.toString();
            return false;
        }
    }

    /** A shot of a player (its pose request, the item in its primary hand, the view) at a world frame and a time. */
    static boolean shot(Player p, FpPose.Request r, Object held, int view, long frame, long now) {
        if (p != null) {
            p.kickPending = false;
        }
        Refusal why = refusal(p, r, held, view, frame);
        if (why != null) {
            return refuse(why);
        }
        double t = p.clock(now);
        TarkovRecoil.Ammo ammo = recoilData.ammunition(p.ammoKey);
        long n = ++p.shotCount;
        long z = p.seed + n * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        if (!p.timeline.shot(t, ammo, z ^ (z >>> 31))) {
            return refuse(Refusal.TIMELINE);
        }
        p.lastShot = t;
        p.kickPending = cameraOwned(p, frame) && ViewpointBridge.ownShotKick(); // one kick per shot in either mod order
        shots++;
        if (view == 2) {
            tpShots++; // its camera kick stays Viewpoint's: the camera is not ours in the third person (cameraOwned)
        }
        return true;
    }

    /** Why a shot is refused now, or null: taken. */
    static Refusal refusal(Player p, FpPose.Request r, Object held, int view, long frame) {
        if (!enabled) {
            return Refusal.OFF;
        }
        if (!option) {
            return Refusal.OPTION;
        }
        if (view != 1 && !(view == 2 && tp)) {
            return Refusal.VIEW;
        }
        if (p == null) {
            return Refusal.PLAYER;
        }
        if (r == null) {
            return Refusal.REQUEST;
        }
        if (r.recoilKey.isEmpty()) {
            return Refusal.RECOIL;
        }
        if (held != r.item) {
            return Refusal.HELD;
        }
        if (p.timeline.track == null) {
            return Refusal.TRACK;
        }
        if (p.item != r.item) {
            return Refusal.ITEM;
        }
        return p.posed(frame) ? null : Refusal.POSE;
    }

    private static boolean refuse(Refusal why) {
        refused++;
        refusedBy[why.ordinal()]++;
        lastRefusal = why.key;
        return false;
    }

    /** QA: one impulse through the shot path, without a round, muzzle effects or a call of Viewpoint. */
    static boolean qaShot(IsoGameCharacter c) {
        boolean taken = shot(c);
        Player p = c == null ? null : players.get(c);
        if (p != null) {
            p.kickPending = false;
        }
        return taken;
    }

    /** ViewpointBridge.hand: this shot's camera kick is ours (taken by shot, our camera writes the frame). */
    static boolean replacesKick(IsoGameCharacter c) {
        return replacesKick(c == null ? null : players.get(c));
    }

    static boolean replacesKick(Player p) {
        if (p == null || !p.kickPending) {
            return false;
        }
        p.kickPending = false;
        kicksReplaced++;
        return true;
    }

    /**
     * Our camera goes to Viewpoint's frame: on, the optional members, Tarkov's lights in charge of the shot, first
     * person, a current pose. The mod order does not enter it (W8, user item 10): the camera is written into
     * Viewpoint's own frame whichever advice wraps the other, and ownShotKick makes the shot's kick ours alone.
     */
    static boolean cameraOwned(Player p, long frame) {
        return enabled && option && camera && p != null && ViewpointBridge.cameraAdditive() && VpLights.replaces()
                && ViewpointBridge.state() == 1 && p.posed(frame);
    }

    /** IsoWorld.render exit (FpRecoilPatch): the camera of the viewed player into the frame Viewpoint snapshotted, once per frame. */
    public static void afterWorld() {
        if (!enabled || !camera || players.isEmpty()) {
            return;
        }
        IsoGameCharacter c = IsoCamera.frameState.camCharacter;
        Player p = c == null ? null : players.get(c);
        if (p == null || !cameraOwned(p, worldFrame())) {
            return;
        }
        Object frame = ViewpointBridge.frameNow();
        if (frame == null) {
            return;
        }
        long n = ViewpointBridge.addCamera(frame, p.cameraFrame, p.cameraAdd);
        if (n >= 0) {
            p.cameraFrame = n;
            cameraWrites++;
        }
    }

    /** The player's weapon offset of the last frame (own Prop1 <- moved Prop1, 16 numbers) and the camera additive (3), or null. */
    static double[] offset(IsoGameCharacter c) {
        Player p = c == null ? null : players.get(c);
        if (p == null || p.delta == null || !p.posed(worldFrame())) {
            return null;
        }
        double[] out = new double[19];
        System.arraycopy(p.delta, 0, out, 0, 16);
        System.arraycopy(p.cameraAdd, 0, out, 16, 3);
        return out;
    }

    static void support(IsoGameCharacter c, String kind) {
        Player p = c == null ? null : players.get(c);
        if (p != null) {
            p.supportName = kind == null ? "" : kind;
            p.supportDirty = true;
        }
    }

    static void reset(IsoGameCharacter c) {
        Player p = c == null ? null : players.get(c);
        if (p != null) {
            p.timeline.hands.reset(TarkovHandling.Conditions.NEUTRAL);
        }
    }

    static void clear(IsoGameCharacter c) {
        if (c != null) {
            Player p = players.remove(c);
            if (p != null) {
                tpVariable(c, p, false);
            }
        }
    }

    // ------------------------------------------------------------------ QA

    static String describe(IsoGameCharacter c) {
        Player p = c == null ? null : players.get(c);
        if (p == null) {
            return null;
        }
        StringBuilder b = new StringBuilder();
        b.append("weapon=").append(p.key).append(" why=").append(p.why.isEmpty() ? "-" : p.why.replace(' ', '_'))
                .append(" ammo=").append(p.ammoKey).append(" rounds=").append(p.rounds).append(" auto=").append(p.autoMode)
                .append(" shots=").append(p.shotCount).append(" support=").append(p.support.kind().name().toLowerCase(Locale.ROOT))
                .append(" camera=").append(cameraOwned(p, worldFrame()));
        State s = p.state;
        if (s != null) {
            // QA: accepted shots above can still be queued. These are consumed impulses and their age on the
            // interpolated (one-step-behind) drawn timeline; rotorX excludes breathing's hip offset in recoil.x.
            b.append(String.format(Locale.ROOT, " applied=%d shotAgeMs=%.3f rotorX=%.6f", s.shots(), s.shotAge() * 1000, s.rotorX()));
            b.append(String.format(Locale.ROOT, " tick=%d index=%d stable=%b hipG=%.4f pos=%.5f,%.5f,%.5f proc=%.4f,%.4f,%.4f recoil=%.4f,%.4f,%.4f"
                            + " cam=%.4f,%.4f,%.4f lagCm=%.3f,%.3f,%.3f", s.tick(), s.shotIndex(), s.stable(), s.hipGain(),
                    s.position()[0], s.position()[1], s.position()[2], s.procedural()[0], s.procedural()[1], s.procedural()[2],
                    s.recoil()[0], s.recoil()[1], s.recoil()[2], s.camera()[0], s.camera()[1], s.camera()[2],
                    s.lag()[0] / GripIK.CM, s.lag()[1] / GripIK.CM, s.lag()[2] / GripIK.CM));
        }
        TarkovHandling.Sample h = p.timeline.hands();
        if (h != null) {
            b.append(String.format(Locale.ROOT, " hands=%.3f/%.0f exhausted=%b oxygen=%.3f/%.1f breath=%s held=%.3f drain=%.4f adsSeconds=%.4f",
                    h.hands(), h.handsCapacity(), h.handsExhausted(), h.oxygen(), h.oxygenCapacity(), h.breathState().name().toLowerCase(Locale.ROOT),
                    h.heldSeconds(), h.handsDrainPerSecond(), h.adsSeconds()));
        }
        TarkovHandling.Conditions cnd = p.injury.handling();
        b.append(String.format(Locale.ROOT, " injuryArms=%d injuryPain=%b injuryTremor=%b injuryPainSeconds=%.3f"
                        + " energy=%.4f hydration=%.4f bodyStamina=%.4f overweight=%.4f injuryHealth=%.4f deepWounds=%d bleedingParts=%d injuryReadErrors=%d",
                cnd.damagedArms(), p.injury.pain(), p.timeline.tremor, p.timeline.injury.painSeconds(),
                cnd.energy(), cnd.hydration(), cnd.bodyStamina(), cnd.overweight(), cnd.health(),
                p.injury.deepWounds(), p.injury.bleedingParts(), TarkovInjuryPz.failures));
        return b.toString();
    }

    public static String stats() {
        return "enabled=" + enabled + " option=" + option + " camera=" + camera + " cameraMembers=" + ViewpointBridge.cameraAdditive()
                + " panicFrames=" + panicFrames + " panicDeg=" + String.format(Locale.ROOT, "%.4f,%.4f", panicYawDeg, panicPitchDeg)
                + " panicShiftCm=" + String.format(Locale.ROOT, "%.4f", panicShiftCm)
                + " players=" + players.size() + " frames=" + frames + " shots=" + shots + " refused=" + refused + " lastRefusal=" + lastRefusal
                + " refusedBy=" + refusals() + " kicksReplaced=" + kicksReplaced + " cameraWrites=" + cameraWrites + " nanos=" + nanos
                + " tp=" + tp + " tpFrames=" + tpFrames + " tpShots=" + tpShots + " armKicksDropped=" + ViewpointBridge.armKicksDropped
                + " errors=" + errors + " data=" + status + (lastError.isEmpty() ? "" : " lastError=" + lastError);
    }

    /** "off:0,option:0,view:3,...": the refusals per reason. */
    static String refusals() {
        StringBuilder b = new StringBuilder();
        for (Refusal r : Refusal.values()) {
            b.append(b.length() == 0 ? "" : ",").append(r.key).append(':').append(refusedBy[r.ordinal()]);
        }
        return b.toString();
    }

    static double wrap(double a) {
        while (a > Math.PI) {
            a -= 2 * Math.PI;
        }
        while (a < -Math.PI) {
            a += 2 * Math.PI;
        }
        return a;
    }

    // FB1-C: read-only ownership of the existing per-player hands timeline.
    public static boolean armStaminaFpOwner(IsoGameCharacter c) {
        return c instanceof IsoPlayer p && p.getPlayerNum() == 0 && enabled && option
                && p.getPrimaryHandItem() instanceof zombie.inventory.types.HandWeapon gun && gun.isRanged()
                && gun.getFullType().startsWith("EscapeFromZomboidFirearms.")
                && FpPose.enabled && ViewpointBridge.ready() && ViewpointBridge.state() == 1;
    }
    public static boolean armStaminaPosed(IsoGameCharacter c) {
        Player p = players.get(c);
        long frame = worldFrame();
        return p != null && p.poseFrame == frame;
    }
}
