package pz.escapefromzomboidfirearms.zb;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Metric, player-projectile mathematics from the vanilla Tarkov B1/B2 research.
 * No game, renderer, collision, damage, clock, random generator or event hooks.
 * Coordinates are right/up/forward; distances are metres and time is seconds.
 */
public final class TarkovBallistics {
    public static final float STEP_SECONDS = 0.01f;
    public static final int CACHE_NODES = 1300;
    public static final Vec3 CLIENT_GRAVITY = new Vec3(0, -9.8100004196167f, 0);
    public static final double METRES_PER_CELL = 1.1534025374855825;
    public static final double METRES_PER_LEVEL = 2.8252479815455596;

    private TarkovBallistics() { }

    public record Vec3(float x, float y, float z) {
        public static final Vec3 ZERO = new Vec3(0, 0, 0);
        public Vec3 {
            finite(x, "vector x"); finite(y, "vector y"); finite(z, "vector z");
        }
        public Vec3 plus(Vec3 b) { return new Vec3(x + b.x, y + b.y, z + b.z); }
        public Vec3 minus(Vec3 b) { return new Vec3(x - b.x, y - b.y, z - b.z); }
        public Vec3 times(float s) { return new Vec3(x * s, y * s, z * s); }
        public Vec3 dividedBy(float s) { return new Vec3(x / s, y / s, z / s); }
        public float dot(Vec3 b) { return (x * b.x + y * b.y) + z * b.z; }
        public Vec3 cross(Vec3 b) { return new Vec3(y*b.z-z*b.y, z*b.x-x*b.z, x*b.y-y*b.x); }
        public float length() { return (float)Math.sqrt(dot(this)); }
        public Vec3 unit() {
            float n = length();
            if (!(n > 1e-5f)) throw new IllegalArgumentException("Direction is zero");
            return dividedBy(n);
        }
    }

    /** Inert profiles remain identifiable in data but cannot start a flight. */
    public record Ammo(String tpl, float initialSpeed, float massGrams, float diameterMillimetres,
                       float ballisticCoefficient, float lifeSeconds, int projectileCount, boolean inert) {
        public Ammo {
            Objects.requireNonNull(tpl);
            positive(initialSpeed, "initial speed"); positive(massGrams, "mass");
            positive(diameterMillimetres, "diameter"); positive(ballisticCoefficient, "BC");
            positive(lifeSeconds, "lifetime");
            if (projectileCount < 1 || projectileCount > 1024) throw new IllegalArgumentException("Projectile count");
        }
    }

    /** Immutable original rows. The lookup deliberately does NOT search adjacent Mach values. */
    public static final class G1 {
        private final float[] mach, coefficients;
        public G1(float[] mach, float[] coefficients) {
            if (mach.length != 79 || coefficients.length != mach.length) throw new IllegalArgumentException("G1 rows");
            this.mach = mach.clone(); this.coefficients = coefficients.clone();
            for (int i = 0; i < mach.length; i++) {
                finite(mach[i], "Mach"); positive(coefficients[i], "G1 coefficient");
                if ((i == 0 && mach[i] != 0) || (i > 0 && mach[i] <= mach[i-1])) {
                    throw new IllegalArgumentException("G1 Mach order");
                }
            }
        }
        public int size() { return mach.length; }
        public float mach(int index) { return mach[index]; }
        public float coefficientAtRow(int index) { return coefficients[index]; }
        public float coefficient(float speed) {
            finite(speed, "speed");
            if (speed < 0) throw new IllegalArgumentException("Negative speed");
            int index = (int)Math.floor(speed / 343f / 0.05f);
            if (index <= 0) return 0;
            if (index >= mach.length) return coefficients[mach.length-1];
            float low = mach[index-1] * 343f, high = mach[index] * 343f;
            return (coefficients[index]-coefficients[index-1]) / (high-low)
                    * (speed-low) + coefficients[index-1];
        }
    }

    public static float speedFactor(float weaponVelocityPercent, float... partVelocityPercent) {
        finite(weaponVelocityPercent, "weapon velocity");
        float sum = weaponVelocityPercent;
        for (float part : partVelocityPercent) { finite(part, "part velocity"); sum += part; }
        float factor = 1f + sum / 100f;
        positive(factor, "assembly speed factor");
        return factor;
    }

    public static float initialSpeed(Ammo ammo, float factor) {
        positive(factor, "assembly speed factor");
        float speed = ammo.initialSpeed * factor;
        positive(speed, "assembled muzzle speed");
        return speed;
    }

    public record Sample(float timeSeconds, Vec3 position, Vec3 velocity, boolean continuation) { }
    public record RangeSample(float rangeMetres, Sample sample, int lowerCacheIndex, float dropMetres) { }

    /** One mutable cache per immutable shot snapshot. Confine it to its owning thread.
     * predict() is the client's demand-filled predictor: continuation starts at the
     * last COMPUTED node, including when the first query skips directly past 13 s.
     * precompute() makes that anchor independent of later query ordering.
     */
    public static final class Flight {
        private final Ammo ammo;
        private final G1 g1;
        private final Vec3 gravity;
        private final float twiceMass, conversion, airArea;
        private final Sample[] nodes;
        private final boolean drag;
        private int computed;

        public Flight(Ammo ammo, float factor, Vec3 origin, Vec3 direction, G1 g1, Vec3 gravity) {
            this(ammo, factor, origin, direction, g1, gravity, CACHE_NODES, true);
        }

        // Non-runtime controls for analytical and cache-exhaustion tests.
        Flight(Ammo ammo, float factor, Vec3 origin, Vec3 direction, G1 g1, Vec3 gravity,
               int cacheNodes, boolean drag) {
            this.ammo = Objects.requireNonNull(ammo); this.g1 = Objects.requireNonNull(g1);
            this.gravity = Objects.requireNonNull(gravity); this.drag = drag;
            if (ammo.inert) throw new IllegalArgumentException("Inert ammunition cannot fly");
            if (cacheNodes < 2 || cacheNodes > CACHE_NODES) throw new IllegalArgumentException("Cache size");
            float mass = ammo.massGrams / 1000f, diameter = ammo.diameterMillimetres / 1000f;
            twiceMass = mass * 2f;
            conversion = mass * 0.0014223f / (diameter * diameter * ammo.ballisticCoefficient);
            airArea = 1.2f * (diameter * diameter * (float)Math.PI / 4f);
            positive(twiceMass, "converted mass"); positive(conversion, "drag conversion");
            positive(airArea, "air area");
            nodes = new Sample[cacheNodes];
            nodes[0] = new Sample(0, Objects.requireNonNull(origin), direction.unit().times(initialSpeed(ammo, factor)), false);
        }

        public Ammo ammo() { return ammo; }
        public int computedNodes() { return computed + 1; }
        public boolean alive(float time) { return Float.isFinite(time) && time >= 0 && time <= ammo.lifeSeconds; }
        public void precompute() { node(nodes.length-1); }

        public Sample node(int index) {
            if (index < 0 || index >= nodes.length) throw new IllegalArgumentException("Cache index");
            while (computed < index) {
                Sample old = nodes[computed];
                Vec3 v = old.velocity;
                float speed = v.length();
                Vec3 acceleration = gravity;
                if (drag && speed > 1e-5f) {
                    float coefficient = g1.coefficient(speed) * conversion;
                    float scalar = airArea * -coefficient * speed * speed / twiceMass;
                    acceleration = gravity.plus(v.dividedBy(speed).times(scalar));
                }
                Vec3 position = old.position.plus(v.times(STEP_SECONDS)).plus(acceleration.times(0.00005f));
                Vec3 velocity = v.plus(acceleration.times(STEP_SECONDS));
                computed++;
                nodes[computed] = new Sample(old.timeSeconds + STEP_SECONDS, position, velocity, false);
            }
            return nodes[index];
        }

        /** Mathematical prediction may outlive the ammo; live callers use sample(). */
        public Sample predict(float time) {
            finite(time, "time");
            if (time < 0) throw new IllegalArgumentException("Negative time");
            int index = Math.min((int)Math.floor(time / STEP_SECONDS), nodes.length-1);
            if (time > 13f || index + 1 >= nodes.length) {
                Sample anchor = nodes[computed];
                float dt = time - anchor.timeSeconds;
                Vec3 p = anchor.position.plus(anchor.velocity.times(dt)).plus(gravity.times(0.5f * dt * dt));
                return new Sample(time, p, anchor.velocity.plus(gravity.times(dt)), true);
            }
            Sample a = node(index), b = node(index+1);
            float blend = Math.clamp((time-a.timeSeconds) / (b.timeSeconds-a.timeSeconds), 0f, 1f);
            return new Sample(time, a.position.plus(b.position.minus(a.position).times(blend)), a.velocity, false);
        }

        public Sample sample(float time) {
            if (!alive(time)) throw new IllegalArgumentException("Time outside ammo lifetime");
            return predict(time);
        }

        /** First forward (+Z) crossing, matching the B2 node/time interpolation. */
        public RangeSample atHorizontalRange(float distance) {
            positive(distance, "range");
            Sample a = node(0), b;
            float target = a.position.z + distance;
            int index = 1;
            while (true) {
                b = index < nodes.length ? node(index) : predict(a.timeSeconds + STEP_SECONDS);
                if (b.position.z >= target) break;
                if (b.position.z <= a.position.z || b.timeSeconds > ammo.lifeSeconds) {
                    throw new IllegalArgumentException("Forward range not reached during lifetime");
                }
                a = b; index++;
            }
            float ratio = (target-a.position.z) / (b.position.z-a.position.z);
            float time = a.timeSeconds + (b.timeSeconds-a.timeSeconds) * ratio;
            if (!alive(time)) throw new IllegalArgumentException("Range exceeds ammo lifetime");
            Vec3 p = a.position.plus(b.position.minus(a.position).times(ratio));
            boolean continuation = index >= nodes.length;
            Vec3 v = continuation ? predict(time).velocity : a.velocity;
            return new RangeSample(distance, new Sample(time, p, v, continuation), Math.min(index-1, nodes.length-1), nodes[0].position.y-p.y);
        }

        /** Sorted unique radial crossings share the accumulating float32 0.001 s clock;
         * results are then restored to the channel's original order, including repeats.
         */
        public Sample[] opticCalibration(float... distances) {
            float[] ordered = distances.clone();
            for (float distance : ordered) positive(distance, "calibration distance");
            Arrays.sort(ordered);
            Map<Float, Sample> points = new HashMap<>();
            Sample point = predict(0);
            float nextTime = 0;
            for (float distance : ordered) {
                if (points.containsKey(distance)) continue;
                float squared = distance * distance;
                finite(squared, "squared calibration distance");
                while (point.position.minus(nodes[0].position).dot(point.position.minus(nodes[0].position)) < squared) {
                    point = predict(nextTime);
                    float advanced = nextTime + 0.001f;
                    if (advanced <= nextTime) throw new IllegalArgumentException("Calibration exceeds float clock resolution");
                    nextTime = advanced;
                }
                points.put(distance, point);
            }
            Sample[] result = new Sample[distances.length];
            for (int i = 0; i < result.length; i++) result[i] = points.get(distances[i]);
            return result;
        }

        /** Strict lower-node bracket, without optic sampling or interpolated drop. */
        private int ironLower(float distance, boolean radial) {
            int low = 0, high = nodes.length-2;
            while (low <= high) {
                int mid = (low+high) >>> 1;
                Vec3 a = node(mid).position.minus(nodes[0].position);
                Vec3 b = node(mid+1).position.minus(nodes[0].position);
                float va = radial ? a.length() : a.z, vb = radial ? b.length() : b.z;
                if (va < distance && vb > distance) return mid;
                if (va > distance && vb > distance) high = mid-1;
                else low = mid+1;
            }
            return -1;
        }

        public IronDrop ironDrop(float distance) {
            positive(distance, "iron range");
            int radial = ironLower(distance, true), forward = ironLower(distance, false);
            float y = radial >= 0 ? node(radial).position.y-nodes[0].position.y : 0;
            if (forward >= 0) y = node(forward).position.y-nodes[0].position.y;
            return new IronDrop(distance, y, radial, forward);
        }
    }

    public record IronDrop(float rangeMetres, float heightMetres, int radialLowerIndex, int forwardLowerIndex) { }

    /** Unscaled, unanimated model geometry in ONE common metre-space frame.
     * sightForward describes an axis; original aim markers may point at the eye.
     */
    public record SightGeometry(Vec3 muzzle, Vec3 boreForward, Vec3 boreUp, Vec3 sightPoint, Vec3 sightForward) {
        public SightGeometry {
            Objects.requireNonNull(muzzle); Objects.requireNonNull(sightPoint);
            boreForward = boreForward.unit();
            boreUp = boreUp.minus(boreForward.times(boreUp.dot(boreForward))).unit();
            sightForward = sightForward.unit();
        }
        public Vec3 pointInBore(Vec3 point) { return directionInBore(point.minus(muzzle)); }
        public Vec3 directionInBore(Vec3 direction) {
            return new Vec3(direction.dot(boreUp.cross(boreForward)), direction.dot(boreUp), direction.dot(boreForward));
        }
    }

    public record OpticZero(Sample calibration, Vec3 sightRayInBore, double boreElevationRadians,
                            double boreYawRadians, double sightPitchAdjustmentRadians) { }

    /** Supply a fresh horizontal defAmmo flight with the current assembly speed. */
    public static OpticZero opticZero(Flight defaultAmmoFlight, float distance, SightGeometry geometry) {
        requireHorizontalCalibration(defaultAmmoFlight);
        Sample calibration = defaultAmmoFlight.opticCalibration(distance)[0];
        Vec3 ray = calibration.position.minus(geometry.pointInBore(geometry.sightPoint)).unit();
        if (ray.z <= 0) throw new IllegalArgumentException("Sight target is behind the channel");
        Vec3 nominal = geometry.directionInBore(geometry.sightForward);
        // Orient the datum LINE toward the target before measuring its pitch.
        if (nominal.z < 0) nominal = nominal.times(-1);
        double pitch = Math.atan2(ray.y, Math.hypot(ray.x, ray.z));
        double nominalPitch = Math.atan2(nominal.y, Math.hypot(nominal.x, nominal.z));
        return new OpticZero(calibration, ray, -pitch, -Math.atan2(ray.x, ray.z), pitch-nominalPitch);
    }

    /** CalculateShotDirectionForIron scalar relation in the client's local axes.
     * The caller owns animated PWA transforms; localY/localZ are NOT PZ coordinates.
     */
    public static Vec3 ironLocalShotDirection(float localY, float localZ, IronDrop drop) {
        finite(localY, "iron local Y"); finite(localZ, "iron local Z");
        if (localZ == 0) throw new IllegalArgumentException("Iron local Z is zero");
        return new Vec3(0, -1, (localY-drop.heightMetres)/localZ);
    }

    public record IronZero(IronDrop drop, Vec3 clientLocalAlignmentTarget, Vec3 clientLocalShotDirection,
                           double boreElevationRadians, double elevationFromAlignedSightRadians) { }

    /** Client front/rear alignment, with geometry supplied in one metric frame.
     * The PWA projection has different local Y/Z offsets from a conventional
     * sight-height atan2. Preserve those offsets as well as its lower-node drop.
     * The returned direction uses the original author axes: forward -Y, up +Z.
     */
    public static IronZero ironZero(Flight defaultAmmoFlight, float distance, SightGeometry geometry,
                                    Vec3 rearPoint, Vec3 frontPoint) {
        requireHorizontalCalibration(defaultAmmoFlight);
        Vec3 rear = geometry.pointInBore(rearPoint), front = geometry.pointInBore(frontPoint);
        Vec3 delta = front.minus(rear);
        Vec3 line = new Vec3(0, delta.y, delta.z).unit();
        if (line.z <= 0) throw new IllegalArgumentException("Iron alignment faces backwards");
        float extension = Math.max(25f, distance);
        Vec3 target = new Vec3(front.x, -front.z + line.y*extension, front.y + line.z*extension);
        if (target.z <= 0) throw new IllegalArgumentException("Iron local target Z is nonpositive");
        IronDrop drop = defaultAmmoFlight.ironDrop(distance);
        Vec3 raw = ironLocalShotDirection(target.y, target.z, drop);
        double pitch = Math.atan2(raw.z, -raw.y);
        return new IronZero(drop, target, raw, pitch, pitch-Math.atan2(line.y, line.z));
    }

    private static void requireHorizontalCalibration(Flight flight) {
        Sample initial = flight.node(0);
        if (!initial.position.equals(Vec3.ZERO) || initial.velocity.x != 0 || initial.velocity.y != 0 || initial.velocity.z <= 0) {
            throw new IllegalArgumentException("Zeroing requires an origin-zero, forward, horizontal defAmmo flight");
        }
    }

    public record Pellet(int index, int fragmentIndex, Vec3 direction) { }

    /** No randomness is hidden here. Supply one replayable unit direction per pellet. */
    public static Pellet[] pellets(Ammo ammo, Vec3 direction, float dispersion, Vec3[] randomDirections) {
        finite(dispersion, "dispersion");
        if (dispersion < 0 || ammo.inert || randomDirections.length != ammo.projectileCount) {
            throw new IllegalArgumentException("Pellet inputs");
        }
        Vec3 base = direction.unit().times(100);
        Pellet[] result = new Pellet[ammo.projectileCount];
        for (int i = 0; i < result.length; i++) {
            Vec3 offset = randomDirections[i];
            if (Math.abs(offset.length()-1f) > 1e-4f) throw new IllegalArgumentException("Pellet random direction must be unit length");
            result[i] = new Pellet(i, i*2, base.plus(offset.times(dispersion)).unit());
        }
        return result;
    }

    /** PZ vector order is X/Y/level; core vector order is right/up/forward. */
    public static Vec3 pzDeltaToMetres(double dx, double dy, double dz) {
        return new Vec3((float)(dx*METRES_PER_CELL), (float)(dz*METRES_PER_LEVEL), (float)(dy*METRES_PER_CELL));
    }
    public static double[] metresToPzDelta(Vec3 metres) {
        return new double[]{metres.x/METRES_PER_CELL, metres.z/METRES_PER_CELL, metres.y/METRES_PER_LEVEL};
    }
    /** Viewpoint scene has camera-relative reversed horizontal signs. */
    public static Vec3 viewpointSceneToMetres(Vec3 scene) {
        return new Vec3((float)(-scene.x*METRES_PER_CELL), (float)(scene.y*METRES_PER_CELL), (float)(-scene.z*METRES_PER_CELL));
    }

    static void finite(float value, String name) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }
    static void positive(float value, String name) {
        finite(value, name);
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }
}
