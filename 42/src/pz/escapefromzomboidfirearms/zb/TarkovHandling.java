package pz.escapefromzomboidfirearms.zb;

import java.util.Objects;

import pz.escapefromzomboidfirearms.zb.TarkovHandlingData.Assembly;
import pz.escapefromzomboidfirearms.zb.TarkovHandlingData.Bipod;
import pz.escapefromzomboidfirearms.zb.TarkovHandlingData.Mount;

/** Detached handling maths and caller-owned local transient state (VP-W5-B7).
 * Units: kilograms, seconds, original stamina points. No game state is accessed.
 * Tarkov-only skill/buff inputs are neutral zero, not mapped to PZ skills/moodles.
 * This class does not register itself or change the existing pose/recoil layer.
 */
public final class TarkovHandling {
    public static final double EXHAUSTED_POINTS = 15;
    public static final double HOLD_INTENSITY = 0.15;

    /** Source playersuperior.Breath: TremorY/XRandom amplitudes, X/YRandom
     * delays, Intensity and AmplitudeGain.Speed (tarkov_gunplay_curves.json).
     * A continuous local oscillator is our PZ adaptation, not Unity's PRNG.
     * The result rotates the actual pose; it is never an extra spread roll.
     */
    public record PanicOffset(double yawDegrees, double pitchDegrees, double multiplier) { }
    public static final class PanicMotion {
        public static final double YAW_AMPLITUDE = 0.5, PITCH_AMPLITUDE = 0.10000000149011612;
        public static final double INTENSITY = 0.6000000238418579, GAIN = 5;
        public static final double YAW_PERIOD = 0.10000000149011612 + 0.5;
        public static final double PITCH_PERIOD = 0.30000001192092896 + 0.6000000238418579;
        private double time, level;
        public PanicOffset advance(double dt, int stress, int panic, double strength) {
            if (!Double.isFinite(dt) || dt < 0 || dt > 1 || stress < 0 || stress > 4 || panic < 0 || panic > 4
                    || !Double.isFinite(strength) || strength < 0 || strength > 2) throw new IllegalArgumentException("Panic input");
            time += dt;
            double target = 1 - (1 - stress / 4.0) * (1 - panic / 4.0);
            level += (target - level) * -Math.expm1(-GAIN * dt);
            // At zero moodles there is exactly zero added motion, including a
            // recovered player; no residual error or random accuracy penalty.
            if (target == 0 || strength == 0) { level = 0; return new PanicOffset(0, 0, 0); }
            double amount = level * strength;
            return new PanicOffset(Math.sin(time * 2 * Math.PI / YAW_PERIOD) * YAW_AMPLITUDE * INTENSITY * amount,
                    Math.sin(time * 2 * Math.PI / PITCH_PERIOD) * PITCH_AMPLITUDE * INTENSITY * amount, amount);
        }
    }

    public enum Pose {
        PRONE("x"), CROUCH("y"), STAND("z");
        final String axis;
        Pose(String axis) { this.axis = axis; }
    }
    public enum SupportKind { NONE, VERTICAL, HORIZONTAL, BIPOD }
    public enum BreathState { NORMAL, HOLDING, RECOVERING, EXHAUSTED }

    /** BIPOD denotes deployed horizontal support, not possession of the part.
     * The named path must be in this assembly's installedBipods map.
     */
    public record Support(SupportKind kind, String bipodPath) {
        public static final Support NONE = new Support(SupportKind.NONE, "");
        public static final Support VERTICAL = new Support(SupportKind.VERTICAL, "");
        public static final Support HORIZONTAL = new Support(SupportKind.HORIZONTAL, "");
        public Support {
            Objects.requireNonNull(kind); Objects.requireNonNull(bipodPath);
            if ((kind == SupportKind.BIPOD) != !bipodPath.isBlank()) throw new IllegalArgumentException("Support/bipod path");
        }
    }

    /** Explicit context, not an automatic PZ mapping. bodyStamina is normalized
     * original general stamina; the neutral context keeps it full. Energy/water,
     * health and injuries also stay neutral until a separate adapter is approved.
     */
    public record Conditions(double energy, double hydration, double health, double bodyStamina,
                             double overweight, int damagedArms, boolean painkillers, boolean fracture) {
        public static final Conditions NEUTRAL = new Conditions(1, 1, 1, 1, 0, 0, false, false);
        public Conditions {
            unit(energy); unit(hydration); unit(health); unit(bodyStamina); unit(overweight);
            if (damagedArms < 0 || damagedArms > 2) throw new IllegalArgumentException("Damaged arm count");
        }
    }

    /** Null assembly means unequipped; raised includes ADS/shouldered holding.
     * Low/patrol carry should pass raised=false. UI/focus handling belongs to the
     * future input adapter. A held button is edge-triggered once, never auto-retried.
     */
    public record Input(Assembly assembly, Pose pose, boolean raised, boolean aiming, boolean holdBreath,
                        Support support, Conditions conditions) {
        public Input {
            Objects.requireNonNull(pose); Objects.requireNonNull(support); Objects.requireNonNull(conditions);
            if (assembly == null && support.kind != SupportKind.NONE) throw new IllegalArgumentException("Unequipped support");
        }
    }

    /** Separate multipliers for the B6 owner; never a blanket recoil/spread scale.
     * motion applies to mouse/movement only for a deployed horizontal bipod.
     */
    public record SupportEffect(double aimDrain, double rotationRecoil, double returnSpeed,
                                double cameraSnap, double outOfBreath, double motion) { }

    /** Normal oxygen-frequency/camera factors are supplied even while holding;
     * the procedural consumer retains its prior frequency/camera values in that
     * branch, as in Tarkov. suppressed means skip breath processing altogether.
     * Random motion, walking and springs remain B6's responsibility.
     */
    public record Breathing(double staminaLevel, double amplitude, double delay, double hardness,
                            double fatiguePitchGain, double oxygenCurve, double normalFrequency,
                            double normalCameraSensitivity, double breathIntensity, double shakeIntensity,
                            double proceduralScale, boolean suppressed) { }

    public record Sample(double time, double pendingSeconds, double hands, double handsCapacity,
                         double oxygen, double oxygenCapacity, boolean handsExhausted, boolean handsEmpty,
                         BreathState breathState, double heldSeconds, double lastHoldSeconds,
                         double handsDrainPerSecond, double handsRestorePerSecond, double oxygenDrainPerSecond,
                         double oxygenRestorePerSecond, double handsRecoveryDelay, double oxygenRecoveryDelay,
                         double bodySpillDrainPerSecond, double adsSeconds, double adsSpeed,
                         SupportEffect support, Breathing breathing) { }

    private final TarkovHandlingData data;

    public TarkovHandling(TarkovHandlingData data) { this.data = Objects.requireNonNull(data); }

    public double effectiveErgonomics(Assembly assembly, Conditions c) {
        Objects.requireNonNull(assembly); Objects.requireNonNull(c);
        // Arm damage nulls ergonomics even when painkillers neutralize its drain penalty.
        return c.damagedArms > 0 ? 0 : Math.max(0, assembly.rawErgonomics());
    }

    public double ergonomicWeight(Assembly assembly, Conditions c) {
        return assembly.massKg() * (1 - Math.sqrt(effectiveErgonomics(assembly, c)) / 25);
    }

    /** Exact B1 skill-neutral blender time; no invented direct hands penalty. */
    public double adsSeconds(double ergonomics, double massKg, double overweight) {
        TarkovHandlingData.finite(ergonomics); TarkovHandlingData.nonnegative(massKg); unit(overweight);
        double e = clamp(ergonomics / 100, 0, 1);
        double w = inverse(p("Aiming.LightWeight"), p("Aiming.HeavyWeight"), massKg);
        double slow = lerp(p("Aiming.MaxTimeLight"), p("Aiming.MaxTimeHeavy"), w);
        double fast = lerp(p("Aiming.MinTimeLight"), p("Aiming.MinTimeHeavy"), w);
        double q = e < 0.25 ? 0.25 + 3*e*e : 2*e - e*e;
        return lerp(slow, fast, q) / lerp(1, p("Stamina.AimingSpeedMultiplier"), overweight);
    }

    public double adsSeconds(Assembly assembly, Conditions c) {
        return adsSeconds(effectiveErgonomics(assembly, c), assembly.massKg(), c.overweight);
    }

    public SupportEffect support(Assembly assembly, Support request) {
        Objects.requireNonNull(request);
        if (request.kind == SupportKind.NONE) return new SupportEffect(1, 1, 1, 1, 1, 1);
        if (assembly == null) throw new IllegalArgumentException("Missing supported assembly");
        String root = data.weapons.get(assembly.weaponKey());
        if (root == null) throw new IllegalArgumentException("Unknown supported weapon");
        Mount mount = data.items.get(root).mount();
        if (!mount.allowed()) throw new IllegalArgumentException("Weapon is not mountable");
        if (request.kind == SupportKind.VERTICAL) {
            return new SupportEffect(p("Stamina.MountingVerticalAimDrainRateMultiplier"), mount.verticalRecoil(),
                    mount.returnSpeed(), mount.cameraSnap(), mount.verticalBreath(), 1);
        }
        if (request.kind == SupportKind.HORIZONTAL) {
            return new SupportEffect(p("Stamina.MountingHorizontalAimDrainRateMultiplier"), mount.horizontalRecoil(),
                    mount.returnSpeed(), mount.cameraSnap(), mount.horizontalBreath(), 1);
        }
        Bipod bipod = assembly.installedBipods().get(request.bipodPath);
        if (bipod == null) throw new IllegalArgumentException("Bipod is not installed at the support path");
        // The global value replaces serialized MotionReact.BipodModifier at weapon setup.
        return new SupportEffect(p("Stamina.BipodAimDrainRateMultiplier"), bipod.recoil(), bipod.returnSpeed(),
                bipod.cameraSnap(), bipod.breath(), p("Mount.MountingSwayFactorModifier"));
    }

    public double handsDrain(Assembly assembly, Pose pose, Conditions c, Support support) {
        Objects.requireNonNull(pose);
        double effectiveMass = ergonomicWeight(assembly, c);
        if (effectiveMass <= 0) return 0; // vanilla Aim(mass) is inactive here
        double arms = c.painkillers ? 1 : 1 + 0.5*c.damagedArms;
        return Math.sqrt(effectiveMass) * p("Stamina.AimDrainRate") * arms
                * p("Stamina.AimConsumptionByPose." + pose.axis)
                * data.staminaFunction("StaminaDrain", c.hydration) * support(assembly, support).aimDrain;
    }

    public double handsRestoration(Pose pose, Conditions c) {
        Objects.requireNonNull(pose); Objects.requireNonNull(c);
        return p("Stamina.HandsRestoration") * p("Stamina.RestorationMultiplierByPose." + pose.axis)
                * data.staminaFunction("StaminaRestoration", c.energy)
                / data.staminaFunction("StaminaDrain", c.hydration);
    }

    public double oxygenCapacity(Conditions c) {
        return p("Stamina.OxygenCapacity") * (0.5 + 0.5*c.energy*c.health);
    }

    public double holdConsumption(Conditions c) {
        return p("Stamina.BaseHoldBreathConsumption")
                * lerp(p("Stamina.HoldBreathStaminaMultiplier.x"), p("Stamina.HoldBreathStaminaMultiplier.y"), c.bodyStamina);
    }

    public double swayStrength(Assembly assembly, Conditions c) {
        return data.curve("Hard.SWAY_STRENGTH_PER_KG").at(ergonomicWeight(assembly, c) * (1 + c.overweight));
    }

    public double displacementStrength(Assembly assembly, Conditions c) {
        return data.curve("Hard.DISPLACEMENT_STRENGTH_PER_KG").at(ergonomicWeight(assembly, c) * (1 + c.overweight));
    }

    public double initialAimSway(Assembly assembly, Conditions c) {
        double e = clamp(effectiveErgonomics(assembly, c) / 100, 0, 1);
        return inverse(p("Pwa.AimSwayStartsThreshold"), p("Pwa.AimSwayMaxThreshold"), assembly.massKg()*(1-e));
    }

    private Breathing breathing(Input input, double hands, double oxygen, boolean holding, SupportEffect effect) {
        Conditions c = input.conditions;
        double level = hands / p("Stamina.HandsCapacity");
        if (p("Stamina.StaminaExhaustionCausesJiggle") == 1) level = Math.min(level, c.bodyStamina);
        double lower = p("Breath.EnergyLowerLimit");
        level = clamp(level, lower, c.fracture ? p("Breath.EnergyFractureLimit") : 1);
        double o = oxygen / oxygenCapacity(c);
        if (p("Stamina.StaminaExhaustionRocksCamera") == 1) o = Math.min(o, c.bodyStamina);
        double curve = clamp(data.curve("Breath.Oxygen").at(o), 0, 1);
        boolean suppressed = input.support.kind != SupportKind.NONE && level > lower && !c.fracture;
        double intensity = p("Breath.Intensity") * effect.outOfBreath;
        double normal = clamp(lerp(4, input.aiming ? 0.75 : 1, curve), 1, 1.5) * intensity;
        double breath = suppressed ? 0 : holding ? HOLD_INTENSITY : normal;
        double shake = suppressed ? 0 : (holding ? HOLD_INTENSITY : 1) * intensity;
        double scale = p("Aiming.ProceduralIntensityByPose." + input.pose.axis)
                * (input.aiming ? p("Aiming.AimProceduralIntensity") : 1);
        return new Breathing(level, data.curve("Breath.AmplitudeCurve").at(level),
                data.curve("Breath.Delay").at(level), data.curve("Breath.Hardness").at(level),
                (1-level)*2, curve, clamp(lerp(4, 1, curve), 1, 2.5),
                lerp(2, 0, curve)*intensity, breath, shake, scale, suppressed);
    }

    /** One instance per LOCAL player/session, supplied and discarded by the owner.
     * No static player map, serialization or transport. Preserve across weapon
     * swaps; call cancelBreath on item identity change, disable, death or lost focus.
     * reset is an explicit new-session operation, not an equip/full-heal operation.
     */
    public final class State {
        private double hands, oxygen, time, pending, handsBlockedUntil, oxygenBlockedUntil, held, lastHeld;
        private boolean holding, buttonDown, spill;

        public State() { reset(Conditions.NEUTRAL); }

        public void reset(Conditions c) {
            Objects.requireNonNull(c);
            hands = p("Stamina.HandsCapacity"); oxygen = oxygenCapacity(c);
            time = pending = handsBlockedUntil = oxygenBlockedUntil = held = lastHeld = 0;
            holding = buttonDown = spill = false;
        }

        public void cancelBreath() {
            if (holding) lastHeld = held;
            holding = false; held = 0;
            buttonDown = true; // must see release before a new request
        }

        /** Accumulate real seconds; serialized B2 tick, no discarded frame time.
         * A single call is bounded to one hour to reject accidental millisecond input.
         * Snapshot changes take effect on the next complete tick.
         */
        public Sample advance(double seconds, Input input) {
            TarkovHandlingData.nonnegative(seconds); Objects.requireNonNull(input);
            if (seconds > 3600) throw new IllegalArgumentException("Handling dt exceeds one hour");
            SupportEffect effect = support(input.assembly, input.support); // validate before mutating
            pending += seconds;
            double step = p("Timing.Step");
            while (pending + 1e-12 >= step) {
                tick(step, input, effect);
                pending = Math.max(0, pending-step);
            }
            return sample(input, effect);
        }

        private void tick(double dt, Input input, SupportEffect effect) {
            Conditions c = input.conditions;
            oxygen = Math.min(oxygen, oxygenCapacity(c));
            boolean equipped = input.assembly != null;
            boolean canAim = equipped && input.aiming;
            if (!input.holdBreath || !canAim) {
                if (holding) lastHeld = held;
                holding = false; held = 0;
            } else if (!buttonDown && !holding && oxygen > EXHAUSTED_POINTS
                    && c.bodyStamina * p("Stamina.Capacity") >= EXHAUSTED_POINTS) {
                holding = true; held = 0;
            }
            buttonDown = input.holdBreath;
            double drain = equipped && (input.raised || input.aiming)
                    ? handsDrain(input.assembly, input.pose, c, input.support) : 0;
            time += dt;
            if (drain > 0) {
                handsBlockedUntil = time + (1 - hands/p("Stamina.HandsCapacity"));
                hands = Math.max(0, hands - drain*dt);
                if (hands == 0) spill = true;
            } else {
                spill = false;
                if (time > handsBlockedUntil) hands = Math.min(p("Stamina.HandsCapacity"),
                        hands + handsRestoration(input.pose, c)*dt);
            }
            // Vanilla routes Aim to Oxygen at empty hands. General stamina is
            // reported as a request only; this library never writes PZ stamina.
            double oxygenDrain = (spill ? drain : 0) + (holding ? holdConsumption(c) : 0);
            if (oxygenDrain > 0) {
                oxygenBlockedUntil = time + (1 - oxygen/oxygenCapacity(c));
                oxygen = Math.max(0, oxygen - oxygenDrain*dt);
            } else if (time > oxygenBlockedUntil) {
                oxygen = Math.min(oxygenCapacity(c), oxygen + p("Stamina.OxygenRestoration")*dt);
            }
            if (holding) {
                held += dt;
                if (oxygen == 0) { lastHeld = held; held = 0; holding = false; }
            }
        }

        private Sample sample(Input input, SupportEffect effect) {
            Conditions c = input.conditions;
            double capacity = oxygenCapacity(c);
            double drain = input.assembly != null && (input.raised || input.aiming)
                    ? handsDrain(input.assembly, input.pose, c, input.support) : 0;
            double oxygenDrain = (spill ? drain : 0) + (holding ? holdConsumption(c) : 0);
            double ads = input.assembly == null ? 0 : adsSeconds(input.assembly, c);
            BreathState state = holding ? BreathState.HOLDING : oxygen == 0 ? BreathState.EXHAUSTED
                    : oxygen < capacity ? BreathState.RECOVERING : BreathState.NORMAL;
            return new Sample(time, pending, hands, p("Stamina.HandsCapacity"), oxygen, capacity,
                    hands < EXHAUSTED_POINTS, hands == 0, state, held, lastHeld, drain,
                    handsRestoration(input.pose, c), oxygenDrain, p("Stamina.OxygenRestoration"),
                    Math.max(0, handsBlockedUntil-time), Math.max(0, oxygenBlockedUntil-time), spill ? drain : 0,
                    ads, ads == 0 ? 0 : 1/ads, effect, breathing(input, hands, oxygen, holding, effect));
        }
    }

    private double p(String key) { return data.parameter(key); }
    private static double inverse(double low, double high, double value) { return clamp((value-low)/(high-low), 0, 1); }
    private static double lerp(double low, double high, double t) { return low + (high-low)*clamp(t, 0, 1); }
    private static double clamp(double value, double low, double high) { return Math.max(low, Math.min(high, value)); }
    private static void unit(double value) {
        TarkovHandlingData.finite(value);
        if (value < 0 || value > 1) throw new IllegalArgumentException("Handling context outside 0..1");
    }
}
