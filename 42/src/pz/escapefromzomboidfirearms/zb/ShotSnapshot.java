package pz.escapefromzomboidfirearms.zb;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import pz.escapefromzomboidfirearms.zb.TarkovBallistics.Ammo;
import pz.escapefromzomboidfirearms.zb.TarkovBallistics.Vec3;

/** Shared immutable B3-A/B/C v1 contract. Standard Java and B4 only: safe headless.
 * Sequence/epoch/seed are raw uint64 bits. Lua/wire must use unsigned decimal text
 * or two uint32 halves, never a Lua double. Prepared IDs may be burned on rejection;
 * an ID denotes a committed cartridge only after the native fire boundary.
 */
public record ShotSnapshot(Id id, String dataHash, long seed, int replayVersion,
        double startTimeSeconds, Weapon weapon, List<Part> parts,
        String actualAmmoTpl, String nativeAmmoType, String profile, Ammo ammo,
        float speedFactor, float initialSpeed, float dispersion, ShotPoseSnapshot pose,
        List<Vec3> pelletDirections, NativeWeapon nativeWeapon) {
    public static final int SCHEMA = 1, METRIC_VERSION = 1, REPLAY_VERSION = 1;

    public record Id(String session, long epoch, int shooter, long sequence) {
        public Id {
            text(session);
            if (epoch == 0 || sequence == 0) throw new IllegalArgumentException("Zero epoch/sequence");
        }
        public String sequenceText() { return Long.toUnsignedString(sequence); }
        public String epochText() { return Long.toUnsignedString(epoch); }
    }
    public record PelletId(Id shot, int index) {
        public PelletId {
            Objects.requireNonNull(shot);
            if (index < 0) throw new IllegalArgumentException("Negative pellet index");
        }
    }
    public record Weapon(int id, String fullType, String key, String tpl,
                         String assemblyRevision, long actionGeneration) {
        public Weapon { text(fullType); text(key); text(tpl); text(assemblyRevision); }
    }
    /** Path distinguishes repeated templates; instance is our stable installed-item identity. */
    public record Part(String path, String tpl, String instance) {
        public Part { text(path); text(tpl); text(instance); }
    }
    /** Bounded saveWithSize bytes. Decode only into an unequipped damage view, never inventory. */
    public record NativeWeapon(int worldVersion, byte[] bytes) {
        public NativeWeapon {
            Objects.requireNonNull(bytes);
            if (worldVersion < 0 || bytes.length == 0 || bytes.length > 262144) {
                throw new IllegalArgumentException("Invalid native weapon snapshot");
            }
            bytes = bytes.clone();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    /** Endpoints and normals are metric, relative to shot.pose.muzzlePz, not absolute float world points. */
    public record Segment(PelletId pellet, int ordinal, float t0, float t1, Vec3 from, Vec3 to) {
        public Segment {
            Objects.requireNonNull(pellet); Objects.requireNonNull(from); Objects.requireNonNull(to);
            if (ordinal < 0 || !Float.isFinite(t0) || !Float.isFinite(t1) || t0 < 0 || t1 < t0) {
                throw new IllegalArgumentException("Invalid swept segment");
            }
        }
    }
    public enum TargetKind { WORLD, VEHICLE, CHARACTER, UNLOADED }
    public record ImpactKey(PelletId pellet, int contactOrdinal, String target) {
        public ImpactKey {
            Objects.requireNonNull(pellet); text(target);
            if (contactOrdinal < 0) throw new IllegalArgumentException("Negative contact ordinal");
        }
    }
    /** Native enum namespaces stay separate; -1 means unknown, never a random-zone damage request. */
    /** LOCAL B5 contribution. Native weapon damage retains its PZ units; only
     * the remaining fraction of the original cartridge budget is transferred.
     * Health samples are separate from the correlated B5 contact cache sample.
     * This is never accepted from a network peer (MP owner remains closed).
     */
    public record Effect(float damageScale, float damage, float penetration, float heavyRoll, float lightRoll) {
        public Effect {
            if (!Float.isFinite(damageScale) || damageScale<0 || !Float.isFinite(damage) || damage<0
                    || !Float.isFinite(penetration) || penetration<0 || !Float.isFinite(heavyRoll)
                    || heavyRoll<0 || heavyRoll>1 || !Float.isFinite(lightRoll) || lightRoll<0 || lightRoll>1)
                throw new IllegalArgumentException("Invalid local impact contribution");
        }
    }
    public record Contact(ImpactKey key, TargetKind kind, float fraction, Vec3 point,
            Vec3 normal, int ragdollPart, int bodyPart, String material, String proof, Effect effect, boolean forwardHit) {
        public Contact(ImpactKey key, TargetKind kind, float fraction, Vec3 point, Vec3 normal,
                int ragdollPart, int bodyPart, String material, String proof) {
            this(key,kind,fraction,point,normal,ragdollPart,bodyPart,material,proof,null,true);
        }
        public Contact {
            Objects.requireNonNull(key); Objects.requireNonNull(kind);
            Objects.requireNonNull(point); Objects.requireNonNull(normal); text(material); text(proof);
            if (!Float.isFinite(fraction) || fraction < 0 || fraction > 1) {
                throw new IllegalArgumentException("Contact outside segment");
            }
        }
    }

    /** B owns read-only ordered tracing and native damage. Not a second flight/ammo owner. */
    public interface Contacts {
        boolean ready();
        List<Contact> trace(ShotSnapshot shot, Segment segment) throws Exception;
        /** B6 uses the same read-only world, with occupied volumes per branch.
         * Old integrations retain their first-contact behavior explicitly. */
        default List<Contact> sweep(ShotSnapshot shot, Segment segment, java.util.Set<String> occupied) throws Exception {
            return trace(shot,segment);
        }
    }
    public interface Damage {
        boolean ready();
        /** One cartridge batch with separate pellet/zone contributions; returns applied keys only. */
        List<ImpactKey> commit(ShotSnapshot shot, List<Contact> contacts) throws Exception;
    }
    /** C owns authority/lifecycle/dedup. Native byte is correlated at fire, not snapshot entry. */
    public interface Ledger {
        boolean reserve(Id id, long expiresNanos);
        boolean nativeFire(Id id, int nativeShotByte);
        boolean impact(ImpactKey key);
        void finish(Id id, String reason);
    }
    public interface Protocol {
        boolean ready(boolean multiplayer);
        void fire(ShotSnapshot shot, int nativeShotByte) throws Exception;
        void impact(ShotSnapshot shot, List<Contact> contacts) throws Exception;
    }

    public ShotSnapshot {
        Objects.requireNonNull(id); text(dataHash); Objects.requireNonNull(weapon);
        text(actualAmmoTpl); text(nativeAmmoType); text(profile); Objects.requireNonNull(ammo);
        Objects.requireNonNull(pose); Objects.requireNonNull(nativeWeapon);
        ShotPoseSnapshot.finite(startTimeSeconds);
        if (replayVersion != REPLAY_VERSION || !actualAmmoTpl.equals(ammo.tpl()) || ammo.inert()
                || !Float.isFinite(speedFactor) || speedFactor <= 0
                || !Float.isFinite(initialSpeed) || initialSpeed <= 0
                || !Float.isFinite(dispersion) || dispersion < 0
                || pose.weaponId() != weapon.id() || !pose.assemblyRevision().equals(weapon.assemblyRevision())) {
            throw new IllegalArgumentException("Invalid/incoherent shot snapshot");
        }
        parts = List.copyOf(parts); pelletDirections = List.copyOf(pelletDirections);
        HashSet<String> paths = new HashSet<>();
        for (Part part : parts) {
            if (!paths.add(part.path())) throw new IllegalArgumentException("Duplicate installed path");
        }
        if (pelletDirections.size() != ammo.projectileCount()) throw new IllegalArgumentException("Pellet count");
        for (Vec3 direction : pelletDirections) ShotPoseSnapshot.unit(direction);
    }

    public PelletId pellet(int index) {
        if (index < 0 || index >= pelletDirections.size()) throw new IndexOutOfBoundsException(index);
        return new PelletId(id, index);
    }
    static void text(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Empty shot identity");
    }
}
