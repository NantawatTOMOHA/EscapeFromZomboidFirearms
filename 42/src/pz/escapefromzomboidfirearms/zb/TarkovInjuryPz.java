package pz.escapefromzomboidfirearms.zb;

import zombie.characters.BodyDamage.BodyDamage;
import zombie.characters.BodyDamage.BodyPart;
import zombie.characters.BodyDamage.BodyPartType;
import zombie.characters.CharacterStat;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.Moodles.MoodleStat;
import zombie.characters.Stats;

/** Read-only native B42 adapter. Remote players, dead players, a disabled option
 * or an unavailable/invalid observation give neutral inputs, never stale injury.
 */
public final class TarkovInjuryPz {
    public static long failures;
    public static String lastError = "";
    private TarkovInjuryPz() { }

    public static TarkovInjury.Context read(IsoGameCharacter c, boolean enabled) {
        if (!enabled || !(c instanceof IsoPlayer p) || !p.isLocalPlayer() || p.isDead()) return TarkovInjury.Context.NEUTRAL;
        try {
            BodyDamage body = c.getBodyDamage();
            Stats stats = c.getStats();
            if (body == null || stats == null || c.getInventory() == null || c.getMaxWeight() <= 0) return TarkovInjury.Context.NEUTRAL;
            var left = arm(body, BodyPartType.Hand_L, BodyPartType.ForeArm_L, BodyPartType.UpperArm_L);
            var right = arm(body, BodyPartType.Hand_R, BodyPartType.ForeArm_R, BodyPartType.UpperArm_R);
            var snapshot = new TarkovInjury.Snapshot(left, right, Math.max(0, stats.get(CharacterStat.PAIN)),
                    normalized(stats, CharacterStat.FATIGUE), normalized(stats, CharacterStat.THIRST),
                    normalized(stats, CharacterStat.ENDURANCE), clamp(body.getHealth() / TarkovInjuryData.PZ_FULL_HEALTH),
                    Math.max(0, c.getInventory().getCapacityWeight() / c.getMaxWeight()), body.getNumPartsBleeding());
            var thresholds = new TarkovInjury.Thresholds(MoodleStat.PAIN.getLowestThreshold(),
                    MoodleStat.HEAVY_LOAD.getLowestThreshold(), MoodleStat.HEAVY_LOAD.getMaximumThreshold());
            return TarkovInjury.map(snapshot, true, thresholds);
        } catch (RuntimeException | LinkageError e) {
            failures++;
            lastError = e.toString();
            return TarkovInjury.Context.NEUTRAL;
        }
    }

    private static TarkovInjury.Arm arm(BodyDamage body, BodyPartType hand, BodyPartType forearm, BodyPartType upperArm) {
        return new TarkovInjury.Arm(part(body.getBodyPart(hand)), part(body.getBodyPart(forearm)), part(body.getBodyPart(upperArm)));
    }

    private static TarkovInjury.Part part(BodyPart p) {
        if (p == null || !Float.isFinite(p.getFractureTime()) || !Float.isFinite(p.getHealth())) {
            throw new IllegalArgumentException("Missing/invalid arm body part");
        }
        return new TarkovInjury.Part(p.getFractureTime() > 0, p.getHealth() <= 0, p.deepWounded(), p.bleeding());
    }

    private static double normalized(Stats stats, CharacterStat stat) {
        return clamp((stats.get(stat) - stat.getMinimumValue()) / (stat.getMaximumValue() - stat.getMinimumValue()));
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite native stat");
        return Math.max(0, Math.min(1, value));
    }
}
