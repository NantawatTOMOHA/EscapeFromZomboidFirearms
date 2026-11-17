package pz.escapefromzomboidfirearms.zb;

import java.util.List;
import java.util.Objects;
import pz.escapefromzomboidfirearms.zb.TarkovBallistics.Vec3;

/** CPU-only copy of the final drawn firearm, never a live VP frame or GL object.
 * World points use PZ x/y/z-level; directions use metric X/right,Y/up,Z/forward.
 * Flight positions are metric offsets from muzzlePz, avoiding large-world floats.
 */
public record ShotPoseSnapshot(long frameNumber, long takenNanos, long poseGeneration,
        int stateIndex, int weaponId, String assemblyRevision, String channel, int mode,
        float zeroMetres, Pz muzzlePz, Vec3 boreForward, Vec3 boreUp,
        List<Double> worldWeaponFrame, Zero zero) {
    public record Pz(double x, double y, double z) {
        public Pz { finite(x); finite(y); finite(z); }
        public Pz offset(Vec3 metres) {
            return new Pz(x + metres.x()/TarkovBallistics.METRES_PER_CELL,
                    y + metres.z()/TarkovBallistics.METRES_PER_CELL,
                    z + metres.y()/TarkovBallistics.METRES_PER_LEVEL);
        }
    }

    public enum ZeroKind { NONE, OPTIC, IRON }
    /** Correction already applied to the drawn pose; never rotate the bullet again. */
    public record Zero(ZeroKind kind, String defaultAmmoTpl, double pitchRadians,
                       double yawRadians, boolean appliedToPose) {
        public Zero {
            Objects.requireNonNull(kind); Objects.requireNonNull(defaultAmmoTpl);
            finite(pitchRadians); finite(yawRadians);
        }
    }

    public ShotPoseSnapshot {
        if (frameNumber < 0 || stateIndex < 0 || stateIndex > 2 || poseGeneration < 0
                || !Float.isFinite(zeroMetres) || zeroMetres < 0) {
            throw new IllegalArgumentException("Invalid shot frame identity/zero");
        }
        Objects.requireNonNull(assemblyRevision); Objects.requireNonNull(channel);
        Objects.requireNonNull(muzzlePz); Objects.requireNonNull(zero);
        unit(boreForward); unit(boreUp);
        double dot = boreForward.x()*boreUp.x() + boreForward.y()*boreUp.y() + boreForward.z()*boreUp.z();
        if (Math.abs(dot) > 0.001) throw new IllegalArgumentException("Nonorthogonal bore frame");
        worldWeaponFrame = List.copyOf(worldWeaponFrame);
        if (worldWeaponFrame.size() != 12) throw new IllegalArgumentException("Weapon frame needs 12 values");
        for (double value : worldWeaponFrame) finite(value);
    }

    static void unit(Vec3 value) {
        Objects.requireNonNull(value);
        double length = Math.sqrt(value.x()*value.x()+value.y()*value.y()+value.z()*value.z());
        if (!Double.isFinite(length) || Math.abs(length-1) > 0.001) {
            throw new IllegalArgumentException("Nonunit projectile/bore direction");
        }
    }
    static void finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite shot value");
    }
}
