package pz.escapefromzomboidfirearms.zb;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import pz.escapefromzomboidfirearms.zb.ShotPoseSnapshot.Pz;
import pz.escapefromzomboidfirearms.zb.TarkovBallistics.Vec3;

/** MP target bodies as the shooting client drew them at its native fire boundary.
 * A dedicated server keeps no bone transforms (AnimationPlayer.updateBones=false,
 * isBoneTransformsNeedFirstFrame stays true), and its characters run ahead of the
 * shooter's interpolated view. The server therefore traces these capsules, after
 * checking each body against its own character: identity, life, position, level
 * and a body-sized envelope. Standard Java only; zone indices stay PZ's enums.
 */
public final class ShotBodies {
    /** ANIMAL (W10 srvlive): an IsoAnimal, its online ID in AnimalInstanceManager's own ID space; reported only to a
     * server that names ShotProtocol.ANIMAL_CAPABILITY (an older one refuses a whole intent with an unknown kind). */
    public static final int MAX_BODIES = 32, MAX_ZONES = 24, ZOMBIE = 0, PLAYER = 1, ANIMAL = 2;
    private ShotBodies() { }

    /** Capsule in metres relative to the shot's muzzle, PZ native zone indices. */
    public record Zone(int ragdoll, int body, Vec3 a, Vec3 b, float radius) {
        public Zone {
            Objects.requireNonNull(a); Objects.requireNonNull(b);
            if (ragdoll < 0 || body < 0 || !finite(a) || !finite(b) || !Float.isFinite(radius) || radius <= 0 || radius > 0.5f)
                throw new IllegalArgumentException("Body zone");
        }
    }
    /** root is the character's PZ position as drawn by the shooter (x,y cells; z level). */
    public record Body(int kind, int onlineId, Pz root, List<Zone> zones) {
        public Body {
            Objects.requireNonNull(root); zones = List.copyOf(zones);
            if (kind != ZOMBIE && kind != PLAYER && kind != ANIMAL || zones.isEmpty() || zones.size() > MAX_ZONES)
                throw new IllegalArgumentException("Body");
        }
    }
    /** Server policy (project limits, measured in docs/pending/MP_SHOT_LIVE_RU.md). */
    public record Limits(double rootMetres, double levels, double reachMetres, double belowMetres, double aboveMetres) {
        public Limits {
            if (!(rootMetres > 0) || !(levels > 0) || !(reachMetres > 0) || !(belowMetres >= 0) || !(aboveMetres > 0))
                throw new IllegalArgumentException("Body limits");
        }
    }
    public static final Limits LIMITS = new Limits(1.5, 0.6, 1.4, 0.6, 2.4);
    /** An animal's capsules reach further from its root along its length: a grown bull is about 2.7 m from rump to
     * muzzle with the root mid-body (B42 cow/bull model at getAnimalSize 1.1); the height band is a person's. */
    public static final Limits ANIMAL_LIMITS = new Limits(1.5, 0.6, 2.2, 0.6, 2.4);
    public static Limits limits(int kind, Limits person) { return kind == ANIMAL ? ANIMAL_LIMITS : person; }

    static boolean finite(Vec3 v) { return Float.isFinite(v.x()) && Float.isFinite(v.y()) && Float.isFinite(v.z()); }

    /** The same metric frame as ShotContactsPz: X east, Y up (levels), Z south. */
    public static Vec3 metric(Pz muzzle, double x, double y, double z) {
        return new Vec3((float)((x - muzzle.x()) * TarkovBallistics.METRES_PER_CELL),
                (float)((z - muzzle.z()) * TarkovBallistics.METRES_PER_LEVEL),
                (float)((y - muzzle.y()) * TarkovBallistics.METRES_PER_CELL));
    }

    /** Null when the report is plausible for this server character, else the reason. */
    public static String check(Body body, Pz muzzle, double serverX, double serverY, double serverZ, Limits limits) {
        double dx = (body.root().x() - serverX) * TarkovBallistics.METRES_PER_CELL;
        double dy = (body.root().y() - serverY) * TarkovBallistics.METRES_PER_CELL;
        if (dx * dx + dy * dy > limits.rootMetres() * limits.rootMetres()) return "body-root";
        if (Math.abs(body.root().z() - serverZ) > limits.levels()) return "body-level";
        Vec3 root = metric(muzzle, body.root().x(), body.root().y(), body.root().z());
        for (Zone zone : body.zones()) {
            for (Vec3 p : new Vec3[]{zone.a(), zone.b()}) {
                double hx = p.x() - root.x(), hz = p.z() - root.z(), up = p.y() - root.y();
                if (hx * hx + hz * hz > limits.reachMetres() * limits.reachMetres()) return "body-reach";
                if (up < -limits.belowMetres() || up > limits.aboveMetres()) return "body-height";
            }
        }
        return null;
    }

    public static void write(DataOutputStream out, List<Body> bodies) throws IOException {
        if (bodies.size() > MAX_BODIES) throw new IOException("Body count");
        out.writeByte(bodies.size());
        for (Body body : bodies) {
            out.writeByte(body.kind()); out.writeInt(body.onlineId());
            out.writeDouble(body.root().x()); out.writeDouble(body.root().y()); out.writeDouble(body.root().z());
            out.writeByte(body.zones().size());
            for (Zone zone : body.zones()) {
                out.writeByte(zone.ragdoll()); out.writeByte(zone.body());
                vec(out, zone.a()); vec(out, zone.b()); out.writeFloat(zone.radius());
            }
        }
    }
    public static List<Body> read(DataInputStream in) throws IOException {
        int count = in.readUnsignedByte();
        if (count > MAX_BODIES) throw new IOException("Body count");
        ArrayList<Body> result = new ArrayList<>(count);
        try {
            for (int i = 0; i < count; i++) {
                int kind = in.readUnsignedByte(), id = in.readInt();
                Pz root = new Pz(in.readDouble(), in.readDouble(), in.readDouble());
                int zones = in.readUnsignedByte();
                if (zones > MAX_ZONES) throw new IOException("Zone count");
                ArrayList<Zone> rows = new ArrayList<>(zones);
                for (int z = 0; z < zones; z++) rows.add(new Zone(in.readUnsignedByte(), in.readUnsignedByte(), vec(in), vec(in), in.readFloat()));
                result.add(new Body(kind, id, root, rows));
            }
        } catch (IllegalArgumentException error) { throw new IOException("Body values", error); }
        return List.copyOf(result);
    }
    private static void vec(DataOutputStream out, Vec3 v) throws IOException { out.writeFloat(v.x()); out.writeFloat(v.y()); out.writeFloat(v.z()); }
    private static Vec3 vec(DataInputStream in) throws IOException { return new Vec3(in.readFloat(), in.readFloat(), in.readFloat()); }
}
