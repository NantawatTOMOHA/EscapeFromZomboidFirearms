package pz.escapefromzomboidfirearms.zb;

import java.util.Objects;

/** Pure PZ-to-Tarkov state adaptation (Q23). No game access, writes or extra
 * recoil coefficients: the existing B6/B7 cores consume the mapped conditions.
 */
public final class TarkovInjury {
    private TarkovInjury() { }

    public record Part(boolean fractured, boolean depleted, boolean deepWound, boolean bleeding) {
        public static final Part HEALTHY = new Part(false, false, false, false);
        public boolean damaged() { return fractured || depleted; }
    }

    public record Arm(Part hand, Part forearm, Part upperArm) {
        public static final Arm HEALTHY = new Arm(Part.HEALTHY, Part.HEALTHY, Part.HEALTHY);
        public Arm { Objects.requireNonNull(hand); Objects.requireNonNull(forearm); Objects.requireNonNull(upperArm); }
        public boolean damaged() { return hand.damaged() || forearm.damaged() || upperArm.damaged(); }
        public int deepWounds() { return bit(hand.deepWound) + bit(forearm.deepWound) + bit(upperArm.deepWound); }
    }

    /** Normalized current PZ stats; pain is on PZ's native scale, loadRatio is
     * capacityWeight/maxWeight, never PZ weight interpreted as kilograms.
     */
    public record Snapshot(Arm left, Arm right, double pain, double fatigue, double thirst,
                           double endurance, double health, double loadRatio, int bleedingParts) {
        public Snapshot {
            Objects.requireNonNull(left); Objects.requireNonNull(right);
            nonnegative(pain); unit(fatigue); unit(thirst); unit(endurance); unit(health);
            nonnegative(loadRatio);
            if (bleedingParts < 0) throw new IllegalArgumentException("Negative bleeding part count");
        }
    }

    public record Thresholds(double painStart, double loadStart, double loadMaximum) {
        public static final Thresholds DEFAULT = new Thresholds(TarkovInjuryData.PZ_PAIN_START,
                TarkovInjuryData.PZ_LOAD_START, TarkovInjuryData.PZ_LOAD_MAXIMUM);
        public Thresholds {
            nonnegative(painStart); nonnegative(loadStart); nonnegative(loadMaximum);
            if (loadMaximum <= loadStart) throw new IllegalArgumentException("Invalid load thresholds");
        }
    }

    public record Context(TarkovHandling.Conditions handling, boolean pain, int deepWounds, int bleedingParts) {
        public static final Context NEUTRAL = new Context(TarkovHandling.Conditions.NEUTRAL, false, 0, 0);
        public Context { Objects.requireNonNull(handling); }
        public TarkovRecoil.Condition recoil() {
            return new TarkovRecoil.Condition(1, (float) handling.bodyStamina(), 1, (float) handling.overweight(),
                    handling.damagedArms() / 2f, false, handling.fracture(), false, false, false, false);
        }
    }

    public static Context map(Snapshot s, boolean enabled) { return map(s, enabled, Thresholds.DEFAULT); }

    public static Context map(Snapshot s, boolean enabled, Thresholds thresholds) {
        if (!enabled) return Context.NEUTRAL;
        Objects.requireNonNull(s); Objects.requireNonNull(thresholds);
        int arms = bit(s.left.damaged()) + bit(s.right.damaged());
        double overweight = clamp((s.loadRatio - thresholds.loadStart) / (thresholds.loadMaximum - thresholds.loadStart));
        var conditions = new TarkovHandling.Conditions(1 - s.fatigue, 1 - s.thirst, s.health, s.endurance,
                overweight, arms, false, arms > 0); // Breath.Fracture means either damaged-arm flag in Tarkov.
        return new Context(conditions, s.pain > thresholds.painStart, s.left.deepWounds() + s.right.deepWounds(), s.bleedingParts);
    }

    /** Caller-owned pain clock, advanced on B6/B7's fixed step. An observed Pain
     * state takes 30 s to request Tremor, whose own activation delay is 15 s.
     * Clearing the source/option clears this adapter; no PZ health effect is made.
     */
    public static final class State {
        private double painSeconds;
        public boolean advance(double seconds, boolean pain) {
            nonnegative(seconds);
            double activation = TarkovInjuryData.PAIN_TREMOR_DELAY_SECONDS + TarkovInjuryData.TREMOR_DELAY_SECONDS;
            painSeconds = pain ? Math.min(activation, painSeconds + seconds) : 0;
            return pain && painSeconds + 1e-9 >= activation;
        }
        public double painSeconds() { return painSeconds; }
    }

    private static int bit(boolean value) { return value ? 1 : 0; }
    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    private static void nonnegative(double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid injury input");
    }
    private static void unit(double value) {
        nonnegative(value);
        if (value > 1) throw new IllegalArgumentException("Injury input outside 0..1");
    }
}
