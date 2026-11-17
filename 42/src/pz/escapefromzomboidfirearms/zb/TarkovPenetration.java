package pz.escapefromzomboidfirearms.zb;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import pz.escapefromzomboidfirearms.zb.TarkovBallistics.Vec3;

/** Pure vanilla Tarkov contact/armor mathematics, B1/B2 and W6-B5 evidence.
 * No PZ, Viewpoint, health mutation, damage conversion, collision query or clock.
 * Each input is an immutable snapshot. Distances are metres; speeds are m/s.
 */
public final class TarkovPenetration {
    private TarkovPenetration() { }

    public record Ammo(String tpl, float initialSpeed, float massGrams, float damage,
                       float penetration, float penetrationDeviation, float armorDamagePercent,
                       float obstacleChance, float ricochetChance, float fragmentationChance,
                       float penetrationDamageMod, float lightBleedingDelta, float heavyBleedingDelta,
                       float lifeSeconds, int minFragments, int maxFragments, boolean inert,
                       boolean grenade) {
        public Ammo {
            nonempty(tpl, "ammo ID");
            positive(initialSpeed, "initial speed"); positive(massGrams, "mass");
            nonnegative(damage, "damage"); nonnegative(penetration, "penetration");
            nonnegative(penetrationDeviation, "penetration deviation");
            nonnegative(armorDamagePercent, "armor damage percent");
            // Some original obstacle chances are 100, not fractions. Keep them.
            nonnegative(obstacleChance, "obstacle chance");
            probability(ricochetChance); probability(fragmentationChance);
            nonnegative(penetrationDamageMod, "penetration damage mod");
            finite(lightBleedingDelta, "light bleed delta"); finite(heavyBleedingDelta, "heavy bleed delta");
            positive(lifeSeconds, "lifetime");
            if (minFragments < 0 || maxFragments < minFragments || maxFragments > 1024)
                throw new IllegalArgumentException("Fragment bounds");
        }
    }

    /** World material and tissue override are distinct from armor destructibility. */
    public record Material(String name, int presetIndex, int materialType, float penetrationLevel,
                           float penetrationChance, float ricochetChance, float fragmentationChance,
                           float deviationChance, float deviation, boolean tissue, float tissueDamageMod) {
        public Material {
            nonempty(name, "material name");
            if (presetIndex < 0 || materialType < 0) throw new IllegalArgumentException("Material index");
            nonnegative(penetrationLevel, "penetration level");
            probability(penetrationChance); probability(ricochetChance); probability(fragmentationChance);
            probability(deviationChance); probability(deviation);
            nonnegative(tissueDamageMod, "tissue damage modifier");
        }
        public Material body(String zone, float level, float chance, float damageMod) {
            return new Material(zone, presetIndex, materialType, level, chance, ricochetChance,
                    fragmentationChance, deviationChance, deviation, true, damageMod);
        }
    }

    /** Samples at the SAME seed are reused by all contact decisions, as in F158.
     * The caller supplies a frozen cache, not a callback that consumes a PRNG.
     * Unity's native Random.InitState cache construction is deliberately outside
     * this mathematics core: do not claim a Java seed recreates Unity's cache.
     */
    public static final class RandomTable {
        private final float[] uniform, penetration;
        private final Vec3[] directions;
        public RandomTable(float[] uniform, float[] penetration, Vec3[] directions) {
            Objects.requireNonNull(uniform); Objects.requireNonNull(penetration); Objects.requireNonNull(directions);
            if (uniform.length < 1 || uniform.length > 4096 || penetration.length != uniform.length
                    || directions.length != uniform.length) throw new IllegalArgumentException("Random cache size");
            this.uniform = uniform.clone(); this.penetration = penetration.clone(); this.directions = directions.clone();
            for (int i = 0; i < uniform.length; i++) {
                probability(uniform[i]); finite(penetration[i], "bounded penetration sample");
                if (penetration[i] < -1 || penetration[i] > 1) throw new IllegalArgumentException("Penetration sample range");
                Vec3 d = Objects.requireNonNull(directions[i]);
                if (Math.abs(d.length()-1f) > 1e-4f) throw new IllegalArgumentException("Random direction must be unit");
            }
        }
        public int size() { return uniform.length; }
        private int index(int seed) {
            if (seed < 0) throw new IllegalArgumentException("Negative random cache index");
            return seed % uniform.length;
        }
        public float uniform(int seed) { return uniform[index(seed)]; }
        public float penetration(int seed) { return penetration[index(seed)]; }
        public Vec3 direction(int seed) { return directions[index(seed)]; }
    }

    /** Original InitialSpeed survives every child; flight supplies velocity at contact.
     * Local time resets in children, while the future owner must retain root age
     * and its global lifetime/chain ledger. No engine object identity is stored.
     */
    public record Projectile(Ammo ammo, Vec3 origin, Vec3 velocity, float initialSpeed,
                             float damage, float penetration, float obstacleChance, float ricochetChance,
                             float fragmentationChance, float deviationBudget, int seed, int ricochets,
                             int generation, RandomTable randoms) {
        public Projectile {
            Objects.requireNonNull(ammo); Objects.requireNonNull(origin); Objects.requireNonNull(velocity);
            Objects.requireNonNull(randoms); positive(initialSpeed, "original initial speed");
            nonnegative(damage, "projectile damage"); nonnegative(penetration, "projectile penetration");
            nonnegative(obstacleChance, "projectile obstacle chance");
            probability(ricochetChance); probability(fragmentationChance); finite(deviationBudget, "deviation budget");
            randoms.index(seed);
            if (ricochets < 0 || ricochets > 2 || generation < 0) throw new IllegalArgumentException("Projectile ancestry");
            if (ammo.inert) throw new IllegalArgumentException("Inert ammunition");
        }
        public float speed() { return velocity.length(); }
        public double energyJoules() { return 0.5 * (ammo.massGrams / 1000.0) * velocity.dot(velocity); }
        public Projectile at(Vec3 position, Vec3 contactVelocity) {
            return new Projectile(ammo, position, contactVelocity, initialSpeed, damage, penetration,
                    obstacleChance, ricochetChance, fragmentationChance, deviationBudget, seed, ricochets, generation, randoms);
        }
    }

    public static Projectile launch(Ammo ammo, float assemblySpeedFactor, Vec3 origin, Vec3 direction,
                                    int seed, RandomTable randoms) {
        if (ammo.inert) throw new IllegalArgumentException("Inert ammunition");
        positive(assemblySpeedFactor, "assembled speed factor");
        float speed = ammo.initialSpeed * assemblySpeedFactor;
        return new Projectile(ammo, origin, direction.unit().times(speed), speed, ammo.damage,
                ammo.penetration + randoms.penetration(seed)*ammo.penetrationDeviation,
                ammo.obstacleChance, ammo.ricochetChance, ammo.fragmentationChance, 1f, seed, 0, 0, randoms);
    }

    /** Exact signed 32-bit client helper. Reject its INT_MIN absolute-value
     * overflow rather than silently inventing a negative cache-index policy.
     */
    private static int scramble(int value) { value ^= value << 7; return value ^ (value >> 3); }
    private static int absolute(int value) {
        if (value == Integer.MIN_VALUE) throw new IllegalArgumentException("Client random integer overflow");
        return Math.abs(value);
    }
    public static int nextSeed(int seed) {
        if (seed < 0) throw new IllegalArgumentException("Negative seed");
        return absolute(scramble(seed+1));
    }
    /** Equal endpoints return min; otherwise the client's maximum is exclusive. */
    public static int fragmentCount(int seed, int min, int max) {
        if (seed < 0 || min < 0 || max < min || max > 1024) throw new IllegalArgumentException("Fragment count inputs");
        return max == min ? min : min + absolute(scramble(seed)) % (max-min);
    }

    public record ArmorRicochet(float x, float y, float angleDegrees) {
        public ArmorRicochet { probability(x); probability(y); finite(angleDegrees, "armor angle");
            if (angleDegrees < 0 || angleDegrees > 180) throw new IllegalArgumentException("Armor ricochet angle"); }
    }
    public record Armor(String id, int armorClass, float durability, float templateDurability, String material,
                        Set<String> zones, float bluntThroughput, boolean heavy, float heavySkillReduction,
                        boolean softArmorSlot, float repairDamageFactor, boolean plateCollider,
                        ArmorRicochet ricochet) {
        public Armor {
            nonempty(id, "armor ID"); nonempty(material, "armor material");
            positive(templateDurability, "original template durability"); nonnegative(durability, "durability");
            if (durability > templateDurability || armorClass < 0 || armorClass > 10)
                throw new IllegalArgumentException("Armor class/durability");
            zones = Set.copyOf(zones); zones.forEach(z -> nonempty(z, "armor zone"));
            probability(bluntThroughput); probability(heavySkillReduction); probability(repairDamageFactor);
            Objects.requireNonNull(ricochet);
        }
    }
    public enum ArmorDecision { BYPASS, BLOCKED, PENETRATED, DEFLECTED }
    public record ArmorResult(ArmorDecision decision, float resistance, float chancePercent, float coefficient,
                              float nominalWear, float appliedWear, float remainingDurability,
                              float damage, float penetration, float staminaBurnMultiplier) { }

    public static float realResistance(float durability, float templateDurability, float classResistance) {
        nonnegative(durability, "durability"); positive(templateDurability, "original durability");
        positive(classResistance, "class resistance");
        float percent = durability/templateDurability*100f;
        return (121f-5000f/(45f+percent*2f))*classResistance*0.01f;
    }
    /** Raw percent preserves branch ordering and boundary behavior, including R=P. */
    public static float penetrationPercent(float penetration, float resistance) {
        nonnegative(penetration, "penetration"); nonnegative(resistance, "resistance");
        if (resistance >= penetration+15f) return 0;
        if (resistance >= penetration) { float gap = resistance-penetration-15f; return 0.4f*gap*gap; }
        if (resistance <= penetration-15f) return 100;
        return 100f+penetration/(0.9f*resistance-penetration);
    }

    /** DamageInfo calculation for ONE already matched armor layer, never a
     * second modification of a flight child. The owner supplies layer ordering.
     */
    public static ArmorResult armorHit(TarkovPenetrationData data, Projectile shot, Armor armor, String zone,
                                       boolean deflectedByThisArmor) {
        return armorHit(data, shot, armor, zone, deflectedByThisArmor, shot.randoms.uniform(shot.seed));
    }
    public static ArmorResult armorHit(TarkovPenetrationData data, Projectile shot, Armor armor, String zone,
                                       boolean deflectedByThisArmor, float roll) {
        Objects.requireNonNull(data); Objects.requireNonNull(shot); Objects.requireNonNull(armor); probability(roll);
        float classResistance = data.classResistance(armor.armorClass);
        float destructibility = data.armorDestructibility(armor.material);
        if (!armor.zones.contains(zone) || armor.durability <= 0)
            return new ArmorResult(ArmorDecision.BYPASS, 0, 100, 1, 0, 0, armor.durability, shot.damage, shot.penetration, 1);
        float resistance = realResistance(armor.durability, armor.templateDurability, classResistance);
        float chance = penetrationPercent(shot.penetration, resistance);
        // The client blocks only on STRICT percent < 100*roll, so zero roll
        // passes even at zero percent. Do not change that edge to <=.
        boolean blocked = chance-roll*100f < 0;
        float damage = shot.damage, pen = shot.penetration, portion = shot.ammo.armorDamagePercent/100f;
        if (deflectedByThisArmor) { damage /= 2f; pen /= 2f; portion /= 2f; }
        float coefficient = clamp(pen/(resistance+12f), 0.6f, 1f);
        float throughput = armor.bluntThroughput;
        if (armor.heavy) throughput *= 1f-armor.heavySkillReduction;
        if (armor.softArmorSlot) throughput *= data.softArmorBluntMultiplier;
        float wear, stamina = 1;
        ArmorDecision decision;
        if (blocked || deflectedByThisArmor) {
            decision = deflectedByThisArmor ? ArmorDecision.DEFLECTED : ArmorDecision.BLOCKED;
            wear = pen*portion*clamp(pen/classResistance, 0.6f, 1.1f)*destructibility;
            damage *= throughput*clamp(1f-0.03f*(resistance-pen), 0.2f, 1f);
            stamina = throughput > 0 ? 3f/(float)Math.sqrt(throughput) : 1f;
        } else {
            decision = ArmorDecision.PENETRATED;
            if (armor.plateCollider) damage = 0;
            damage *= coefficient;
            float beforeCoefficient = pen;
            pen *= coefficient;
            wear = pen*portion*clamp(beforeCoefficient/classResistance, 0.5f, 0.9f)*destructibility;
        }
        damage *= armor.repairDamageFactor;
        wear = Math.max(1f, wear);
        float remaining = Math.max(0, armor.durability-wear);
        return new ArmorResult(decision, resistance, chance, coefficient, wear,
                armor.durability-remaining, remaining, damage, pen, stamina);
    }

    /** Angle is between -incoming direction and outward normal, in degrees. */
    public static float incidenceDegrees(Vec3 velocity, Vec3 normal) {
        float dot = velocity.unit().times(-1).dot(normal.unit());
        return (float)Math.acos(clamp(dot, -1, 1)) * 57.29578f;
    }
    public static float ricochetProbability(float angleDegrees, float ammoChance, float materialChance) {
        finite(angleDegrees, "incidence"); probability(ammoChance); probability(materialChance);
        if (!(angleDegrees > 42.5f && angleDegrees < 80f) || ammoChance <= 0 || materialChance <= 0) return 0;
        float cosine = (float)Math.cos(angleDegrees/57.29578f);
        // This is a raw comparison threshold; it can exceed 1. Clamping would
        // incorrectly change the client's inclusive cache endpoint roll=1.
        return (ammoChance+materialChance)*(1f-Math.abs(cosine));
    }
    public static float armorRicochetProbability(float angleDegrees, ArmorRicochet params) {
        finite(angleDegrees, "armor incidence"); Objects.requireNonNull(params);
        if (params.x <= 0 || !(angleDegrees > params.angleDegrees)) return 0;
        float t = params.angleDegrees == 90 ? 0 : clamp01((angleDegrees-90f)/(params.angleDegrees-90f));
        return params.x+(params.y-params.x)*t;
    }
    public static boolean armorDeflects(Projectile shot, Armor armor, String zone, Vec3 segmentDirection, Vec3 normal) {
        return armor.zones.contains(zone) && shot.randoms.uniform(shot.seed)
                < armorRicochetProbability(incidenceDegrees(segmentDirection, normal), armor.ricochet);
    }

    public enum Outcome { STOP, CONTINUE, RICOCHET, FRAGMENT, GRENADE_CHAIN_FENCE }
    /** forwardHit distinguishes entering tissue from leaving it. Armor CF is
     * optional player-bridge input (use 1 without armor); heavy deflection is an
     * explicit already-resolved skill proposal, never inferred from PZ clothing.
     */
    public record Contact(Material material, Vec3 point, Vec3 normal, Vec3 segmentDirection, boolean forwardHit,
                          float armorCoefficient, boolean armorBlocked, boolean armorDeflected,
                          boolean heavyArmorDeflected) {
        public Contact {
            Objects.requireNonNull(material); Objects.requireNonNull(point); Objects.requireNonNull(normal);
            normal = normal.unit(); segmentDirection = segmentDirection.unit(); probability(armorCoefficient);
            if (!material.tissue && (armorCoefficient != 1 || armorBlocked || armorDeflected || heavyArmorDeflected))
                throw new IllegalArgumentException("Armor bridge inputs require a body collider");
        }
    }
    public record ContactResult(Outcome outcome, Projectile impact, List<Projectile> children,
                                float angleDegrees, double incomingEnergyJoules, double outgoingEnergyJoules) {
        public ContactResult { children = List.copyOf(children); }
    }
    /** Opaque prepared phase: matched armor decisions can use the degraded
     * projectile before continuation is resolved, without degrading it twice.
     */
    public static final class Impact {
        private final Projectile projectile;
        private final boolean forwardHit;
        private Impact(Projectile projectile, boolean forwardHit) {
            this.projectile = projectile; this.forwardHit = forwardHit;
        }
        public Projectile projectile() { return projectile; }
    }

    public static float degradation(float speed, float initialSpeed, float coefficient) {
        nonnegative(speed, "speed"); positive(initialSpeed, "original initial speed"); finite(coefficient, "degradation coefficient");
        float ratio = clamp01(speed/initialSpeed);
        return ratio+(1f-ratio)*clamp01(coefficient);
    }
    public static float tissueCoefficient(Projectile shot, Material material) {
        if (!material.tissue) return 1;
        return clamp01((shot.penetration-material.penetrationLevel)/100f
                + material.tissueDamageMod+shot.ammo.penetrationDamageMod);
    }
    private static Projectile scaled(Projectile p, Vec3 origin, Vec3 direction, float speed,
                                     float damage, float penetration, float chances, float fragmentChance,
                                     float deviation, int seed, int ricochets, int generation) {
        return new Projectile(p.ammo, origin, direction.unit().times(speed), p.initialSpeed, damage, penetration,
                p.obstacleChance*chances, p.ricochetChance*chances, fragmentChance, deviation,
                seed, ricochets, generation, p.randoms);
    }
    public static Impact prepareImpact(TarkovPenetrationData data, Projectile input, Vec3 point, boolean forwardHit) {
        Objects.requireNonNull(data); Objects.requireNonNull(input); Objects.requireNonNull(point);
        float k = forwardHit ? degradation(input.speed(), input.initialSpeed, data.damageDegradationCoefficient) : 1f;
        Projectile p = new Projectile(input.ammo, point, input.velocity, input.initialSpeed,
                input.damage*k, input.penetration*k, input.obstacleChance, input.ricochetChance,
                input.fragmentationChance, input.deviationBudget, input.seed, input.ricochets, input.generation, input.randoms);
        return new Impact(p, forwardHit);
    }
    public static ContactResult contact(TarkovPenetrationData data, Projectile input, Contact hit) {
        return resolveImpact(prepareImpact(data, input, hit.point, hit.forwardHit), hit);
    }
    public static ContactResult resolveImpact(Impact prepared, Contact hit) {
        Objects.requireNonNull(prepared); Objects.requireNonNull(hit);
        Projectile p = prepared.projectile;
        if (!p.origin.equals(hit.point) || prepared.forwardHit != hit.forwardHit)
            throw new IllegalArgumentException("Prepared impact belongs to a different contact");
        float speed = p.speed();
        if (speed <= 1e-5f) return new ContactResult(Outcome.STOP, p, List.of(), 0, p.energyJoules(), 0);
        Material m = hit.material;
        Vec3 direction = p.velocity.unit();
        // The linecast direction and the rolled-back contact velocity are
        // separate client inputs. Do not silently replace one with the other.
        float cosine = hit.segmentDirection.dot(hit.normal);
        float angle = incidenceDegrees(hit.segmentDirection, hit.normal), roll = p.randoms.uniform(p.seed);
        boolean angular = angle > 42.5f && angle < 80f && p.ricochets < 2;
        boolean heavy = angular && m.tissue && hit.heavyArmorDeflected;
        List<Projectile> children = new ArrayList<>();
        Outcome outcome;
        // BodyPartCollider.Deflects delegates to matched armor. It does not
        // roll the generic flesh material, and armor's own angle z is decisive.
        boolean deflect = hit.forwardHit && p.ricochets < 2 && (m.tissue ? hit.armorDeflected
                : angular && p.ricochetChance > 0 && m.ricochetChance > 0
                    && roll < (p.ricochetChance+m.ricochetChance)*(1f-Math.abs(cosine)));
        if (heavy || deflect) {
            outcome = Outcome.RICOCHET;
            Vec3 reflected = direction.minus(hit.normal.times(2f*cosine));
            if (!heavy) reflected = reflected.plus(p.randoms.direction(p.seed).times(m.deviation));
            float chanceSum = Math.min(1f, p.ricochetChance+m.ricochetChance);
            children.add(scaled(p, hit.point, reflected, speed*0.7f, p.damage*chanceSum*0.5f, p.penetration*chanceSum*0.5f,
                    0.2f, p.fragmentationChance*0.2f, 1, nextSeed(p.seed), p.ricochets+1, p.generation+1));
        } else {
            // BodyPartCollider.IsPenetrated overrides the generic chance roll.
            // Zero tissue chance still permits power above its level; it only
            // controls subsequent speed/chance attenuation in the child.
            boolean penetrates = m.tissue ? !hit.forwardHit || (!hit.armorBlocked
                    && p.penetration*hit.armorCoefficient > m.penetrationLevel)
                    : m.penetrationChance > 0 && p.penetration > m.penetrationLevel
                        && p.obstacleChance+m.penetrationChance > roll;
            if (!penetrates) outcome = Outcome.STOP;
            else if (hit.forwardHit && p.fragmentationChance > 0 && m.fragmentationChance > 0
                    && p.fragmentationChance+m.fragmentationChance > roll) {
                outcome = Outcome.FRAGMENT;
                int count = fragmentCount(p.seed, p.ammo.minFragments, p.ammo.maxFragments);
                if (count > 0) {
                    float tissue = tissueCoefficient(p, m);
                    Vec3 origin = hit.point.plus(direction.times(0.002f));
                    int seed = nextSeed(p.seed);
                    for (int i = 0; i < count; i++) {
                        Vec3 fragmentDirection = direction.plus(p.randoms.direction(seed).times(m.deviation));
                        seed = nextSeed(seed);
                        float armor = m.tissue ? hit.armorCoefficient : 1f;
                        children.add(scaled(p, origin, fragmentDirection, speed*Math.min(1f, (1f/p.ammo.maxFragments)*0.7f),
                                (p.damage*tissue*0.5f/p.ammo.maxFragments)*armor,
                                (p.penetration*tissue*0.5f/p.ammo.maxFragments)*armor,
                                0.2f, 0, 1, seed, p.ricochets, p.generation+1));
                    }
                }
            } else if (p.deviationBudget <= 0) outcome = Outcome.STOP;
            else if (m.materialType == 4 && p.ammo.grenade) outcome = Outcome.GRENADE_CHAIN_FENCE;
            else {
                outcome = Outcome.CONTINUE;
                Vec3 nextDirection = direction;
                if (hit.forwardHit && m.deviationChance > roll)
                    nextDirection = nextDirection.plus(p.randoms.direction(p.seed).times(m.deviation));
                // The client offsets by the unnormalised deviated vector.
                Vec3 origin = hit.point.plus(nextDirection.times(0.002f));
                float factor = hit.forwardHit ? tissueCoefficient(p, m) : 1f;
                float speedFactor = hit.forwardHit && m.tissue ? 0.8f+(1f-0.8f)*m.penetrationChance : 1f;
                float chanceFactor = hit.forwardHit && m.tissue ? 0.2f+0.8f*m.penetrationChance : 1f;
                float armor = hit.forwardHit && m.tissue ? hit.armorCoefficient : 1f;
                children.add(scaled(p, origin, nextDirection, speed*speedFactor,
                        p.damage*factor*armor, p.penetration*factor*armor, chanceFactor,
                        p.fragmentationChance*chanceFactor, p.deviationBudget-0.08f,
                        nextSeed(p.seed), p.ricochets, p.generation));
            }
        }
        double outgoing = 0;
        for (Projectile child : children) outgoing += child.energyJoules();
        return new ContactResult(outcome, p, children, angle, p.energyJoules(), outgoing);
    }

    public record BleedSpec(float k, float b, float threshold) {
        public BleedSpec { finite(k, "bleed k"); finite(b, "bleed b"); nonnegative(threshold, "bleed threshold"); }
        public float probability(float damageFraction, float cap, float ammoBias) {
            nonnegative(damageFraction, "damage fraction"); positive(cap, "health random cap"); finite(ammoBias, "ammo bleed bias");
            return damageFraction < threshold ? 0 : clamp01((k*damageFraction+b+ammoBias)/cap);
        }
        public boolean occurs(float damageFraction, float cap, float ammoBias, float roll) {
            probability(damageFraction, cap, ammoBias); TarkovPenetration.probability(roll);
            return damageFraction >= threshold && roll*cap < k*damageFraction+b+ammoBias;
        }
    }
    public enum Bleeding { NONE, LIGHT, HEAVY }
    /** Caller has already admitted a weapon-induced, non-self-inflicted wound
     * and supplies actual post-armor body damage and original part max health.
     * Health rolls are separate explicit samples, unlike correlated contact rolls.
     * This computes proposals; it does not create effects, wounds or health ticks.
     */
    public static Bleeding bleeding(TarkovPenetrationData data, Ammo ammo, float bodyDamage, float partMaxHealth,
                                    float vitalityCap, boolean bleedBlocked, boolean existingHeavy,
                                    float heavyRoll, float lightRoll) {
        nonnegative(bodyDamage, "body damage"); positive(partMaxHealth, "body maximum health");
        probability(heavyRoll); probability(lightRoll); positive(vitalityCap, "vitality cap");
        if (bleedBlocked) return Bleeding.NONE;
        float fraction = bodyDamage/partMaxHealth;
        if (data.heavyBleeding.occurs(fraction, vitalityCap, ammo.heavyBleedingDelta, heavyRoll)) return Bleeding.HEAVY;
        if (!existingHeavy && data.lightBleeding.occurs(fraction, vitalityCap, ammo.lightBleedingDelta, lightRoll)) return Bleeding.LIGHT;
        return Bleeding.NONE;
    }

    static float clamp(float value, float min, float max) { return Math.min(max, Math.max(min, value)); }
    static float clamp01(float value) { return clamp(value, 0, 1); }
    static void finite(float value, String name) { if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite "+name); }
    static void nonnegative(float value, String name) { finite(value, name); if (value < 0) throw new IllegalArgumentException("Negative "+name); }
    static void positive(float value, String name) { finite(value, name); if (value <= 0) throw new IllegalArgumentException("Nonpositive "+name); }
    static void probability(float value) { finite(value, "probability"); if (value < 0 || value > 1) throw new IllegalArgumentException("Probability range"); }
    static void nonempty(String value, String name) { if (value == null || value.isEmpty()) throw new IllegalArgumentException("Empty "+name); }
}
