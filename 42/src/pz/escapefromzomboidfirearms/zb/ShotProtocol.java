package pz.escapefromzomboidfirearms.zb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import pz.escapefromzomboidfirearms.zb.ShotSnapshot.*;
import pz.escapefromzomboidfirearms.zb.TarkovBallistics.Ammo;
import pz.escapefromzomboidfirearms.zb.TarkovBallistics.Vec3;

/** Headless B3-C protocol. Native initiation is observed, never generated here.
 * A server needs BOTH its native preparation proof and an independent authority
 * adapter (flight/obstruction/identity/PVP plus replication) before it can admit.
 * The shipped Lua bridge deliberately has no such adapter installed yet.
 */
public final class ShotProtocol implements ShotSnapshot.Protocol {
    public static final int VERSION = 2, MAX_WIRE_BYTES = 262144, MAX_CONTACTS = 256;
    public static final long PENDING_NANOS = 15_000_000_000L;
    public enum Role { LOCAL, CLIENT, SERVER, OBSERVER }
    public sealed interface Message permits Hello, FireIntent, ImpactProposal, Accepted, Rejected, ImpactConfirmed { }
    public record Hello(String challenge, String session, long epoch, int shooter, String dataHash,
            int schema, int metric, int replay, long nativeBase, boolean capable, String reason) implements Message {
        public Hello {
            ShotSnapshot.text(challenge); ShotSnapshot.text(session); ShotSnapshot.text(dataHash);
            Objects.requireNonNull(reason);
            if (epoch == 0) throw new IllegalArgumentException("Hello epoch");
        }
    }
    /** The view a shot was aimed from: a Viewpoint first-person pose, or the isometric aim (W10 srvlive: the server's
     * "Live: Viewpoint and isometric" policy flies the latter; its "Live: Viewpoint first person" refuses it). */
    public enum View { VIEWPOINT, ISOMETRIC }
    /** launch is the shooter's calibrated central launch direction (the zeroed sight line, the bore
     * at the hip); bodies are the targets the shooter drew (ShotBodies); relief is the drawn world's
     * lift along the launch line (Relief.NONE: flat); view is the view it was aimed from. All are claims the
     * server checks against its own pose, characters, limits and policy; none carries damage or pellet directions. */
    public record FireIntent(ShotSnapshot shot, long nativeSerial, int nativeByte, Vec3 launch, List<ShotBodies.Body> bodies,
            Relief relief, View view) implements Message {
        public FireIntent(ShotSnapshot shot, long nativeSerial, int nativeByte) {
            this(shot, nativeSerial, nativeByte, shot.pose().boreForward(), List.of());
        }
        public FireIntent(ShotSnapshot shot, long nativeSerial, int nativeByte, Vec3 launch, List<ShotBodies.Body> bodies) {
            this(shot, nativeSerial, nativeByte, launch, bodies, Relief.NONE);
        }
        public FireIntent(ShotSnapshot shot, long nativeSerial, int nativeByte, Vec3 launch, List<ShotBodies.Body> bodies, Relief relief) {
            this(shot, nativeSerial, nativeByte, launch, bodies, relief, View.VIEWPOINT);
        }
        public FireIntent {
            Objects.requireNonNull(shot); ShotPoseSnapshot.unit(launch); bodies = List.copyOf(bodies); Objects.requireNonNull(relief);
            Objects.requireNonNull(view);
            if (nativeSerial == 0 || nativeByte < 0 || nativeByte > 255 || (nativeSerial & 255) != nativeByte
                    || bodies.size() > ShotBodies.MAX_BODIES) {
                throw new IllegalArgumentException("Native correlation");
            }
        }
    }
    /** A server whose Hello names this flies an intent's relief; a client sends one only to such a server. */
    public static final String RELIEF_CAPABILITY = "relief-v1";
    /** A server whose Hello names this reads an intent's view (View.ISOMETRIC) and decides it by its policy; a client
     * sends an isometric intent only to such a server (an older one flies every intent as a Viewpoint one). */
    public static final String ISOMETRIC_CAPABILITY = "isometric-v1";
    /** A server whose Hello names this resolves animal bodies (ShotBodies.ANIMAL); a client reports animals only to such
     * a server. */
    public static final String ANIMAL_CAPABILITY = "animal-bodies-v1";
    /** 42.22 removed PZ's shot byte. This names an explicit EFZ-owned witness carried by a real native miss packet. */
    public static final String NATIVE_WITNESS_CAPABILITY = "native-witness-v1";
    public static final class NativeWitness {
        // 1000..1100 is the existing fallback-chance namespace. This adjacent disjoint range is metadata, not chance.
        public static final int BASE=1101, SIZE=256;
        private NativeWitness() { }
        public static int chance(int ordinal) {
            if (ordinal<0 || ordinal>=SIZE) throw new IllegalArgumentException("Witness ordinal");
            return BASE+ordinal;
        }
        public static int ordinal(int chance) { return chance>=BASE && chance<BASE+SIZE?chance-BASE:-1; }
        public static boolean challenge(String value) {
            String prefix=NATIVE_WITNESS_CAPABILITY+":";
            if (value==null || value.length()!=prefix.length()+36 || !value.startsWith(prefix)) return false;
            String nonce=value.substring(prefix.length());
            try { return java.util.UUID.fromString(nonce).toString().equals(nonce); }
            catch (IllegalArgumentException invalid) { return false; }
        }
        public static boolean agreed(String challenge,String reason) {
            return challenge(challenge) && reason!=null && Arrays.asList(reason.split(" ")).contains(NATIVE_WITNESS_CAPABILITY);
        }
        public static final class Token {
            private final Object actor;
            private final int ordinal;
            private Token(Object actor,int ordinal) { this.actor=actor;this.ordinal=ordinal; }
            public int ordinal() { return ordinal; }
        }
        /** One exact local actor's authored stream. Reserve is not an observation; only successful native write commits. */
        public static final class Counter {
            private WeakReference<Object> actor=new WeakReference<>(null);
            private int committed;
            private Token pending;
            public synchronized int last(Object value) {
                Objects.requireNonNull(value);
                if (actor.get()!=value) { actor=new WeakReference<>(value);committed=0;pending=null; }
                return committed;
            }
            /** A validated Hello defines the new epoch's baseline; it does not observe a cartridge. */
            public synchronized void align(Object value,long negotiatedBase) {
                Objects.requireNonNull(value);
                if (negotiatedBase<256) throw new IllegalArgumentException("Witness epoch baseline");
                actor=new WeakReference<>(value);committed=(int)(negotiatedBase&255);pending=null;
            }
            public synchronized void reset() { actor=new WeakReference<>(null);committed=0;pending=null; }
            public synchronized Token reserve(Object value) {
                last(value);
                if (pending!=null) return null;
                return pending=new Token(value,(committed+1)&255);
            }
            public synchronized boolean commit(Token token) {
                if (token==null || token!=pending || actor.get()!=token.actor) return false;
                committed=token.ordinal;pending=null;return true;
            }
            public synchronized void cancel(Token token) { if (token!=null && pending==token) pending=null; }
        }
    }
    /**
     * The drawn world's relief along the launch line (user's report of 27.10.2026, item 13: in MP the server flew the
     * bullet in PZ's flat world while Viewpoint drew the targets lifted onto its far relief, FarRelief$Lift, so far hits
     * landed above the sight). The shooter samples its own flight's Warp (ShotOwner.reliefWarp of the shot's frame): the
     * lift in millimetres relative to the muzzle at horizontal distances (millimetres, rising) along the launch's
     * heading; one row on the line, or, for pellets, three - the rays at -spread, 0 and +spread beside it (spread: the
     * tangent of the pellets' widest angle). The profile starts at the muzzle (0, 0) and holds its last lift beyond its
     * end; between samples and rows it is linear. Integers only, so no NaN travels; the server clamps the values
     * (ShotServer.warp) - a profile can bend a flight only as far as a drawn relief can.
     */
    public record Relief(float spread, int[] millimetres, short[] lifts) {
        public static final Relief NONE = new Relief(0, new int[0], new short[0]);
        /** Bounded size: 512 samples of up to 3 rows (4 KB); 400 m (the reported-body corridor's length). */
        public static final int MAX_SAMPLES = 512, MAX_RANGE_MILLIMETRES = 400_000;
        /** Sampling: 8 m steps (about one flight step), each checked at its eighths; split until the profile stays
         * within 2 mm of the drawn lift (the flight's own splitter keeps 5 mm, ShotPenetration.WARP_TOLERANCE), or beyond
         * 20 m within 0.1 mrad of the distance (0.006 degrees, a third of a bullet's own 1 MOA spread). */
        public static final int STEP_MILLIMETRES = 8000, MIN_STEP_MILLIMETRES = 2, TOLERANCE_MILLIMETRES = 2;
        public static final double TOLERANCE_RADIANS = 1e-4;
        /** The profile's tolerance (millimetres) at a distance (millimetres). */
        public static double tolerance(int millimetres) { return Math.max(TOLERANCE_MILLIMETRES, millimetres * TOLERANCE_RADIANS); }
        public static final float MAX_SPREAD = .25f;
        public Relief {
            Objects.requireNonNull(millimetres); Objects.requireNonNull(lifts);
            millimetres = millimetres.clone(); lifts = lifts.clone();
            if (!Float.isFinite(spread) || spread < 0 || spread > MAX_SPREAD || millimetres.length > MAX_SAMPLES
                    || lifts.length != millimetres.length * (spread > 0 ? 3 : 1)) throw new IllegalArgumentException("Relief");
            for (int i = 0, previous = 0; i < millimetres.length; previous = millimetres[i++]) {
                if (millimetres[i] <= previous || millimetres[i] - previous > 65535 || millimetres[i] > MAX_RANGE_MILLIMETRES)
                    throw new IllegalArgumentException("Relief distance");
            }
        }
        public int rows() { return spread > 0 ? 3 : 1; }
        public int samples() { return millimetres.length; }
        @Override public int[] millimetres() { return millimetres.clone(); }
        @Override public short[] lifts() { return lifts.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Relief r && Float.compare(spread, r.spread) == 0 && Arrays.equals(millimetres, r.millimetres)
                    && Arrays.equals(lifts, r.lifts);
        }
        @Override public int hashCode() { return Objects.hash(spread, Arrays.hashCode(millimetres), Arrays.hashCode(lifts)); }
        @Override public String toString() { return "Relief[spread=" + spread + " samples=" + millimetres.length + "]"; }
        /** The lift (millimetres) of a row at a sample, 0 at the muzzle. */
        public int lift(int sample, int row) { return sample < 0 ? 0 : lifts[sample * rows() + row]; }
        /** The same profile with every lift clamped to +-min(maximum, base + grade * distance) metres. */
        public Relief clamped(double baseMetres, double grade, double maximumMetres) {
            short[] values = lifts.clone(); int rows = rows();
            for (int i = 0; i < millimetres.length; i++) {
                double bound = Math.min(maximumMetres, baseMetres + grade * millimetres[i] / 1000.0) * 1000;
                int limit = (int) Math.max(0, Math.min(Short.MAX_VALUE, Math.floor(bound)));
                for (int k = 0; k < rows; k++) values[i * rows + k] = (short) Math.max(-limit, Math.min(limit, values[i * rows + k]));
            }
            return new Relief(spread, millimetres, values);
        }
        /** The lift (metres relative to the muzzle) at a point given in metres from the muzzle (x, up, z), for a flight
         * launched along launch: its horizontal distance along the heading and its side offset against the rows. */
        public java.util.function.ToDoubleFunction<Vec3> along(Vec3 launch) {
            double h = Math.hypot(launch.x(), launch.z());
            if (millimetres.length == 0 || h < 1e-3) return point -> 0;
            double hx = launch.x() / h, hz = launch.z() / h;
            int rows = rows(), n = millimetres.length;
            return point -> {
                double s = (point.x() * hx + point.z() * hz) * 1000;
                if (!(s > 0)) return 0;
                int i = Arrays.binarySearch(millimetres, (int) Math.min(Integer.MAX_VALUE, Math.ceil(s)));
                if (i < 0) i = -i - 1;
                double a = i == 0 ? 0 : millimetres[i - 1], t = i >= n ? 1 : (s - a) / (millimetres[i] - a);
                int from = i - 1, to = Math.min(i, n - 1);
                double center = row(from, to, t, rows == 1 ? 0 : 1);
                if (rows == 1) return center / 1000;
                double width = spread * s, side = (point.z() * hx - point.x() * hz) * 1000;
                double u = width > 0 ? Math.max(-1, Math.min(1, side / width)) : 0;
                double edge = row(from, to, t, u < 0 ? 0 : 2);
                return (center + (edge - center) * Math.abs(u)) / 1000;
            };
        }
        private double row(int from, int to, double t, int row) {
            double a = lift(from, row), b = lift(to, row);
            return a + (b - a) * t;
        }
        /**
         * The shooter's profile of a drawn lift (metres relative to the muzzle at a point x, z metres east and south of
         * it) along launch to range: 8 m steps near to far, each split while a row at one of its eighths leaves the
         * linear profile by more than its tolerance; past MAX_SAMPLES the profile ends (its last lift holds). NONE for a
         * near-vertical launch.
         */
        public static Relief sample(java.util.function.DoubleBinaryOperator lift, Vec3 launch, float spread, double rangeMetres) {
            double h = Math.hypot(launch.x(), launch.z());
            if (h < 1e-3 || !(rangeMetres > 0)) return NONE;
            float width = Float.isFinite(spread) ? Math.max(0, Math.min(MAX_SPREAD, spread)) : 0;
            double hx = launch.x() / h, hz = launch.z() / h;
            int rows = width > 0 ? 3 : 1, end = (int) Math.min(MAX_RANGE_MILLIMETRES, Math.round(rangeMetres * 1000));
            double[] sides = rows == 1 ? new double[]{0} : new double[]{-width, 0, width};
            java.util.function.IntFunction<short[]> at = mm -> {
                short[] values = new short[rows];
                double s = mm / 1000.0;
                for (int k = 0; k < rows; k++) {
                    double side = sides[k] * s;
                    double value = lift.applyAsDouble(s * hx - side * hz, s * hz + side * hx);
                    values[k] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value * 1000)));
                }
                return values;
            };
            int[] distances = new int[MAX_SAMPLES]; short[] values = new short[MAX_SAMPLES * rows];
            int[] count = {0};
            short[] start = new short[rows];
            for (int a = 0; a < end && count[0] < MAX_SAMPLES; ) {
                int b = Math.min(end, a + STEP_MILLIMETRES);
                short[] fb = at.apply(b);
                start = refine(at, a, start, b, fb, distances, values, count, rows);
                if (start == null) break;
                a = b;
            }
            return new Relief(width, Arrays.copyOf(distances, count[0]), Arrays.copyOf(values, count[0] * rows));
        }
        /** Emits the samples of (a, b] near first; the lift at b, or null once the budget ran out. A piece that leaves
         * the line is split where its two end tangents meet - the kink of a bilinear relief cell or a wall's step
         * (FarRelief$Field.metresAt) - or else at its worst eighth, so a kink costs a sample or two, not a bisection. */
        private static short[] refine(java.util.function.IntFunction<short[]> at, int a, short[] fa, int b, short[] fb,
                int[] distances, short[] values, int[] count, int rows) {
            int worst = -1, row = 0; double far = 1;
            short[][] eighth = new short[9][];
            int[] marks = new int[9];
            if (b - a > MIN_STEP_MILLIMETRES) {
                for (int j = 1; j < 8; j++) {
                    int m = a + (int) ((long) (b - a) * j / 8);
                    marks[j] = m;
                    if (m <= a || m >= b) continue;
                    eighth[j] = at.apply(m);
                    double t = (m - a) / (double) (b - a);
                    double allowed = tolerance(m);
                    for (int k = 0; k < rows; k++) {
                        double off = Math.abs(eighth[j][k] - (fa[k] + (fb[k] - fa[k]) * t)) / allowed;
                        if (off > far) { far = off; worst = j; row = k; }
                    }
                }
            }
            if (worst > 0) {
                int m = marks[worst];
                if (eighth[1] != null && eighth[7] != null && marks[1] > a && marks[7] < b) {
                    double left = (eighth[1][row] - fa[row]) / (double) (marks[1] - a), right = (fb[row] - eighth[7][row]) / (double) (b - marks[7]);
                    double kink = Math.abs(left - right) > 1e-9 ? (fb[row] - fa[row] + left * a - right * b) / (left - right) : Double.NaN;
                    int margin = Math.max(1, (b - a) / 64);
                    if (kink > a + margin && kink < b - margin) m = (int) Math.round(kink);
                }
                short[] fm = refine(at, a, fa, m, at.apply(m), distances, values, count, rows);
                return fm == null ? null : refine(at, m, fm, b, fb, distances, values, count, rows);
            }
            if (count[0] >= MAX_SAMPLES) return null;
            distances[count[0]] = b; System.arraycopy(fb, 0, values, count[0] * rows, rows); count[0]++;
            return fb;
        }
        static void write(DataOutputStream out, Relief relief) throws IOException {
            out.writeFloat(relief.spread); out.writeShort(relief.millimetres.length);
            for (int i = 0, previous = 0; i < relief.millimetres.length; previous = relief.millimetres[i++])
                out.writeShort(relief.millimetres[i] - previous);
            for (short value : relief.lifts) out.writeShort(value);
        }
        static Relief read(DataInputStream in) throws IOException {
            float spread = in.readFloat(); int n = in.readUnsignedShort();
            if (n > MAX_SAMPLES) throw new IOException("Relief samples");
            if (!Float.isFinite(spread) || spread < 0 || spread > MAX_SPREAD) throw new IOException("Relief spread");
            int[] distances = new int[n]; int total = 0;
            for (int i = 0; i < n; i++) {
                int delta = in.readUnsignedShort();
                if (delta == 0) throw new IOException("Relief distance");
                total += delta; distances[i] = total;
            }
            short[] lifts = new short[n * (spread > 0 ? 3 : 1)];
            for (int i = 0; i < lifts.length; i++) lifts[i] = in.readShort();
            return new Relief(spread, distances, lifts);
        }
    }
    public record ImpactProposal(Id id, List<Contact> contacts) implements Message {
        public ImpactProposal { Objects.requireNonNull(id); contacts = ShotProtocol.contacts(id, contacts); }
    }
    public record Accepted(Id id) implements Message { public Accepted { Objects.requireNonNull(id); } }
    public record Rejected(Id id, String reason) implements Message {
        public Rejected { Objects.requireNonNull(id); ShotSnapshot.text(reason); }
    }
    public record ImpactConfirmed(Id id, List<ImpactKey> keys) implements Message {
        public ImpactConfirmed {
            Objects.requireNonNull(id); keys = List.copyOf(keys);
            if (keys.size() > MAX_CONTACTS || new HashSet<>(keys).size() != keys.size()
                    || keys.stream().anyMatch(k -> !k.pellet().shot().equals(id))) throw new IllegalArgumentException("Confirmation keys");
        }
    }
    /** Captured on the native server path BEFORE its ammo preparation is mutated.
     * A client can send no NativeFire message. NativeWeapon bytes never travel on wire.
     */
    public record Preparation(String dataHash, Weapon weapon, List<Part> parts, String actualAmmoTpl,
            String nativeAmmoType, String profile, Ammo ammo, float speedFactor, float initialSpeed,
            float dispersion, NativeWeapon nativeWeapon) {
        public Preparation {
            ShotSnapshot.text(dataHash); Objects.requireNonNull(weapon); parts = List.copyOf(parts);
            ShotSnapshot.text(actualAmmoTpl); ShotSnapshot.text(nativeAmmoType); ShotSnapshot.text(profile);
            Objects.requireNonNull(ammo); Objects.requireNonNull(nativeWeapon);
        }
        public static Preparation of(ShotSnapshot shot) {
            return new Preparation(shot.dataHash(), shot.weapon(), shot.parts(), shot.actualAmmoTpl(),
                    shot.nativeAmmoType(), shot.profile(), shot.ammo(), shot.speedFactor(), shot.initialSpeed(),
                    shot.dispersion(), shot.nativeWeapon());
        }
        boolean matches(ShotSnapshot s) { return mismatch(s) == null; }
        /** The first field where the server's own item and the client's shot disagree, or null when they agree. */
        String mismatch(ShotSnapshot s) {
            // The client's action generation is its own counter; the server cannot observe it.
            Weapon w = s.weapon();
            if (!dataHash.equals(s.dataHash())) return "dataHash";
            if (weapon.id() != w.id()) return "weaponId";
            if (!weapon.fullType().equals(w.fullType())) return "fullType";
            if (!weapon.key().equals(w.key())) return "key";
            if (!weapon.tpl().equals(w.tpl())) return "tpl";
            if (!weapon.assemblyRevision().equals(w.assemblyRevision())) return "assemblyRevision";
            if (!parts.equals(s.parts())) return "parts";
            if (!nativeAmmoType.equals(s.nativeAmmoType())) return "nativeAmmoType";
            if (!actualAmmoTpl.equals(s.actualAmmoTpl())) return "actualAmmoTpl";
            if (!profile.equals(s.profile())) return "profile";
            if (!ammo.equals(s.ammo())) return "ammo";
            if (speedFactor != s.speedFactor()) return "speedFactor";
            if (initialSpeed != s.initialSpeed()) return "initialSpeed";
            if (dispersion != s.dispersion()) return "dispersion";
            return null;
        }
        ShotSnapshot authoritative(ShotSnapshot s) {
            return new ShotSnapshot(s.id(), dataHash, s.seed(), s.replayVersion(), s.startTimeSeconds(), weapon,
                    parts, actualAmmoTpl, nativeAmmoType, profile, ammo, speedFactor, initialSpeed, dispersion,
                    s.pose(), s.pelletDirections(), nativeWeapon);
        }
    }
    public record NativeFire(ShotLedger.NativeKey key, int nativeByte, Preparation prepared) {
        public NativeFire {
            Objects.requireNonNull(key); Objects.requireNonNull(prepared);
            if (nativeByte < 0 || nativeByte > 255 || (key.serial() & 255) != nativeByte) throw new IllegalArgumentException("Native proof");
        }
    }
    public interface Authority {
        /** Includes validated native initiation and native damage replication capabilities. */
        boolean ready();
        boolean admit(FireIntent fire, NativeFire nativeFire) throws Exception;
        default ShotSnapshot authoritative(FireIntent fire,NativeFire proof) throws Exception {
            return proof.prepared().authoritative(fire.shot());
        }
        default boolean serverSimulated() { return false; }
        default void accepted(ShotSnapshot shot) throws Exception { }
        /** The intent of this native shot was refused (the reason names the first field that differs). */
        default void refused(FireIntent fire, NativeFire proof, String reason) { }
        /** Recompute/check flight, time, obstruction, target identity, zones and PVP.
         * Return server-proven contacts only, never client damage/health values.
         */
        List<Contact> validate(ShotSnapshot shot, List<Contact> proposals) throws Exception;
    }
    @FunctionalInterface public interface Transport { void send(int recipient, Message message) throws Exception; }
    @FunctionalInterface public interface Presentation { void confirmed(Message message); }

    /** Byte unwrapping is only valid within a negotiated window smaller than 128.
     * A gap of 128, overflow or a pre-epoch byte fails closed. Repeats retain serial.
     */
    public static final class NativeCounter {
        private long high;
        public NativeCounter(long negotiatedBase) { high = negotiatedBase; }
        public long observe(int nativeByte) {
            if (nativeByte < -128 || nativeByte > 255) throw new IllegalArgumentException("Native byte");
            int delta = ((nativeByte & 255) - (int)(high & 255) + 256) & 255;
            if (delta == 128) throw new IllegalStateException("Ambiguous native byte window");
            long result;
            if (delta < 128) {
                result = high + delta;
                if (Long.compareUnsigned(result, high) < 0) throw new IllegalStateException("Native serial exhausted");
                high = result;
            } else {
                int age = 256 - delta;
                if (Long.compareUnsigned(high, age) < 0) throw new IllegalStateException("Pre-epoch native byte");
                result = high - age;
            }
            if (result == 0) throw new IllegalStateException("Native initiation not advanced");
            return result;
        }
    }
    private static final class Pending {
        long expiry;
        FireIntent fire;
        ShotSnapshot admitted;
        final Map<ImpactKey, Contact> early = new HashMap<>();
        final Map<ImpactKey, Contact> proposed = new HashMap<>();
        boolean failed;
        Pending(long expiry) { this.expiry = expiry; }
    }
    private record NativeSlot(NativeFire proof, long expiry) { }
    private final Role role;
    private final ShotLedger ledger;
    private final ShotLedger presentationLedger;
    private final String dataHash;
    private final Authority authority;
    private final Damage damage;
    private final Transport transport;
    private final Presentation presentation;
    private final LongSupplier clock;
    private final Map<Id, Pending> pending = new HashMap<>();
    private final Map<ShotLedger.NativeKey, NativeSlot> nativeShots = new HashMap<>();
    private final Map<ShotLedger.NativeKey, Id> correlations = new HashMap<>();
    private final HashSet<Id> presentedShots = new HashSet<>();
    private final HashSet<ImpactKey> presentedImpacts = new HashSet<>();
    private NativeCounter nativeCounter;
    private Hello handshake;
    private String challenge, failure = "";
    private boolean disabled;

    public ShotProtocol(Role role, ShotLedger ledger, String dataHash, Authority authority, Damage damage,
            Transport transport, Presentation presentation, LongSupplier clock) {
        this.role = Objects.requireNonNull(role); this.ledger = Objects.requireNonNull(ledger);
        ShotSnapshot.text(dataHash); this.dataHash = dataHash; this.authority = authority; this.damage = damage;
        this.transport = transport; this.presentation = presentation; this.clock = Objects.requireNonNull(clock);
        this.presentationLedger = new ShotLedger(ledger.session(), 4096, clock);
    }
    public Role role() { return role; }
    public ShotLedger ledger() { return ledger; }
    public String status() { return disabled ? "disabled:" + failure : ready(role != Role.LOCAL) ? "ready" : "baseline:server-adapter-unavailable"; }
    @Override public synchronized boolean ready(boolean multiplayer) {
        if (disabled) return false;
        if (role == Role.CLIENT || role == Role.OBSERVER) return multiplayer && transport != null && handshake != null && handshake.capable();
        return (role == Role.LOCAL ? !multiplayer : multiplayer) && authority != null && authority.ready()
                && damage != null && damage.ready() && (role == Role.LOCAL || transport != null);
    }
    /** CLIENT: the handshaken server names this capability (RELIEF_CAPABILITY, ISOMETRIC_CAPABILITY). */
    public synchronized boolean capable(String capability) {
        return handshake != null && Arrays.asList(handshake.reason().split(" ")).contains(capability);
    }
    public synchronized void challenge(String value) {
        ShotSnapshot.text(value); challenge = value; handshake = null; nativeCounter = null;
    }
    public synchronized boolean handshake(Hello hello) {
        if ((role != Role.CLIENT && role != Role.OBSERVER) || challenge == null || !challenge.equals(hello.challenge())
                || !hello.capable() || hello.schema() != ShotSnapshot.SCHEMA || hello.metric() != ShotSnapshot.METRIC_VERSION
                || hello.replay() != ShotSnapshot.REPLAY_VERSION || !dataHash.equals(hello.dataHash())
                || !ledger.session().equals(hello.session())
                || !ledger.connect(hello.shooter(), hello.epoch())) { handshake = null; nativeCounter = null; return false; }
        if (handshake != null) return handshake.equals(hello);
        handshake = hello; nativeCounter = new NativeCounter(hello.nativeBase()); return true;
    }
    public synchronized void expire() {
        long now = clock.getAsLong(); ledger.expire(); presentationLedger.expire();
        pending.entrySet().removeIf(e -> now - e.getValue().expiry >= 0 || !ledger.current(e.getKey()));
        nativeShots.entrySet().removeIf(e -> now - e.getValue().expiry() >= 0);
        correlations.entrySet().removeIf(e -> !pending.containsKey(e.getValue()));
        presentedShots.removeIf(id -> !presentationLedger.contains(id));
        presentedImpacts.removeIf(key -> !presentationLedger.contains(key.pellet().shot()));
    }
    private Pending slot(Id id) {
        expire();
        if (!ledger.current(id) || (!pending.containsKey(id) && pending.size() >= 2048)) return null;
        return pending.computeIfAbsent(id, ignored -> new Pending(clock.getAsLong() + PENDING_NANOS));
    }
    private long flightExpiry(ShotSnapshot shot) {
        long duration=(long)(shot.ammo().lifeSeconds()*1_000_000_000d)+PENDING_NANOS;
        return clock.getAsLong()+Math.min(ShotLedger.MAX_LIFETIME_NANOS,Math.max(PENDING_NANOS,duration));
    }
    private void send(int recipient, Message message) throws Exception { if (transport != null) transport.send(recipient, message); }
    private void fail(Id id, String reason) {
        ledger.finish(id, reason);
        Pending p = pending.get(id); if (p != null) { p.failed = true; p.early.clear(); }
        try { send(id.shooter(), new Rejected(id, reason)); } catch (Exception e) { disabled = true; failure = "transport"; }
    }
    @Override public synchronized void fire(ShotSnapshot shot, int nativeShotByte) throws Exception {
        fire(shot, nativeShotByte, shot.pose().boreForward(), List.of());
    }
    public synchronized void fire(ShotSnapshot shot, int nativeShotByte, Vec3 launch, List<ShotBodies.Body> bodies) throws Exception {
        fire(shot, nativeShotByte, launch, bodies, Relief.NONE);
    }
    /** CLIENT: the intent carries the shooter's launch direction, drawn target bodies and drawn relief
     * (the relief only to a server that flies it: an older one reads no trailing data). */
    public synchronized void fire(ShotSnapshot shot, int nativeShotByte, Vec3 launch, List<ShotBodies.Body> bodies, Relief relief) throws Exception {
        fire(shot, nativeShotByte, launch, bodies, relief, View.VIEWPOINT);
    }
    /** CLIENT: an isometric intent (view) goes only to a server that reads it (capable(ISOMETRIC_CAPABILITY)). */
    public synchronized void fire(ShotSnapshot shot, int nativeShotByte, Vec3 launch, List<ShotBodies.Body> bodies, Relief relief,
            View view) throws Exception {
        if (!ready(role != Role.LOCAL) || role == Role.OBSERVER || role == Role.SERVER) throw new IllegalStateException(status());
        if (!dataHash.equals(shot.dataHash()) || !ledger.current(shot.id())) throw new IllegalArgumentException("Shot identity/data");
        if (role == Role.CLIENT && (shot.id().shooter() != handshake.shooter() || shot.id().epoch() != handshake.epoch())) {
            throw new IllegalArgumentException("Handshake shooter/epoch");
        }
        Pending p = slot(shot.id());
        if (p == null || p.fire != null) throw new IllegalStateException("Duplicate/full shot");
        p.expiry=flightExpiry(shot);
        if (!ledger.contains(shot.id()) && !ledger.reserve(shot.id(), p.expiry)) throw new IllegalStateException("Shot replay");
        if (!ledger.extend(shot.id(),p.expiry) || !ledger.attach(shot)) throw new IllegalStateException("Shot expired");
        if (role == Role.CLIENT) {
            long serial = nativeCounter.observe(nativeShotByte);
            if (view != View.VIEWPOINT && !capable(ISOMETRIC_CAPABILITY)) throw new IllegalStateException("Server reads no intent view");
            p.fire = new FireIntent(shot, serial, nativeShotByte & 255, launch, bodies,
                    capable(RELIEF_CAPABILITY) ? relief : Relief.NONE, view);
            try { send(-1, p.fire); }
            catch (Exception e) { disabled=true; failure="fire-transport"; fail(shot.id(),failure); throw e; }
        } else {
            // LOCAL authority observes the same native boundary, without creating one.
            p.fire = new FireIntent(shot, (nativeShotByte & 255) == 0 ? 256 : nativeShotByte & 255, nativeShotByte & 255);
            NativeFire nativeFire = new NativeFire(new ShotLedger.NativeKey(shot.id().shooter(), shot.id().epoch(), p.fire.nativeSerial()),
                    p.fire.nativeByte(), Preparation.of(shot));
            int recorded=ledger.nativeByte(shot.id());
            if (!authority.admit(p.fire, nativeFire)
                    || (recorded==-1 ? !ledger.nativeFire(shot.id(),nativeShotByte) : recorded!=(nativeShotByte&255))) {
                fail(shot.id(), "local-admission"); return;
            }
            p.admitted = shot;
        }
    }
    /** CLIENT: an own native shot this peer did not claim still advances the negotiated window,
     * as the server's stream does for every native shot of this shooter. */
    public synchronized void nativeShot(int nativeShotByte) {
        if (role != Role.CLIENT || nativeCounter == null) return;
        try { nativeCounter.observe(nativeShotByte); }
        catch (IllegalStateException e) { handshake = null; nativeCounter = null; throw e; }
    }
    /** Authenticated Lua/transport sender. A wire payload cannot supply native proof. */
    public synchronized boolean receive(int sender, Message message) throws Exception {
        if (role != Role.SERVER || !ready(true)) return false;
        if (message instanceof FireIntent fire && sender == fire.shot().id().shooter()) return receiveFire(fire);
        if (message instanceof ImpactProposal proposal && sender == proposal.id().shooter()) return receiveImpact(proposal);
        return false;
    }
    public synchronized boolean nativeFire(NativeFire proof) throws Exception {
        expire();
        if (role != Role.SERVER || !ready(true)
                || !ledger.current(new Id(ledger.session(), proof.key().epoch(), proof.key().shooter(), 1))) return false;
        NativeSlot old = nativeShots.get(proof.key());
        if (old != null) return false;
        if (nativeShots.size() >= 2048) return false;
        nativeShots.put(proof.key(), new NativeSlot(proof, clock.getAsLong() + PENDING_NANOS));
        Id id = correlations.get(proof.key());
        if (id != null) accept(pending.get(id));
        return true;
    }
    private boolean receiveFire(FireIntent fire) throws Exception {
        Id id = fire.shot().id();
        if (!dataHash.equals(fire.shot().dataHash())) return false;
        Pending p = slot(id);
        if (p == null || p.failed) return false;
        if (p.fire != null) {
            if (!Arrays.equals(encode(p.fire), encode(fire))) { fail(id, "changed-fire"); return false; }
            if (p.admitted != null) send(id.shooter(), new Accepted(id));
            return true;
        }
        ShotLedger.NativeKey key = new ShotLedger.NativeKey(id.shooter(), id.epoch(), fire.nativeSerial());
        Id previous = correlations.get(key);
        if (previous != null && !previous.equals(id)) { fail(id, "native-token-reused"); return false; }
        p.expiry=flightExpiry(fire.shot());
        if (!ledger.reserve(id, p.expiry) || !ledger.attach(fire.shot())) { fail(id, "replay-or-capacity"); return false; }
        p.fire = fire; correlations.put(key, id); accept(p); return true;
    }
    private void accept(Pending p) throws Exception {
        if (p == null || p.failed || p.admitted != null || p.fire == null) return;
        FireIntent fire = p.fire; Id id = fire.shot().id();
        NativeSlot nativeSlot = nativeShots.get(new ShotLedger.NativeKey(id.shooter(), id.epoch(), fire.nativeSerial()));
        if (nativeSlot == null) return;
        NativeFire proof = nativeSlot.proof();
        try {
            String differs = proof.nativeByte() != fire.nativeByte() ? "nativeByte" : proof.prepared().mismatch(fire.shot());
            if (differs == null && !authority.admit(fire, proof)) differs = "pose";
            if (differs == null && !ledger.bindNative(id, fire.nativeSerial(), fire.nativeByte())) differs = "bindNative";
            if (differs != null) {
                String reason = "server-admission:" + differs;
                fail(id, reason); authority.refused(fire, proof, reason); return;
            }
            p.admitted = authority.authoritative(fire,proof);
            if (!p.admitted.id().equals(id) || !p.admitted.dataHash().equals(dataHash)) throw new IllegalStateException("Server shot identity");
            authority.accepted(p.admitted);
            send(id.shooter(), new Accepted(id));
            if (!p.early.isEmpty()) {
                List<Contact> contacts = List.copyOf(p.early.values()); p.early.clear(); commit(p, contacts);
            }
        } catch (Exception e) { disabled = true; failure = "admission-or-transport"; fail(id, failure); throw e; }
    }
    private boolean receiveImpact(ImpactProposal proposal) throws Exception {
        if (authority.serverSimulated()) return false;
        Pending p = slot(proposal.id());
        if (p == null || p.failed) return false;
        ArrayList<Contact> fresh = new ArrayList<>();
        for (Contact c : proposal.contacts()) {
            Contact old = p.proposed.get(c.key());
            if (old != null && !old.equals(c)) { fail(proposal.id(), "changed-impact"); return false; }
            if (old == null) fresh.add(c);
        }
        if (p.proposed.size() + fresh.size() > 4096) { fail(proposal.id(), "impact-capacity"); return false; }
        for (Contact c : fresh) p.proposed.put(c.key(), c);
        if (p.admitted == null) for (Contact c : fresh) p.early.put(c.key(), c);
        else commit(p, fresh);
        return true;
    }
    @Override public synchronized void impact(ShotSnapshot shot, List<Contact> contacts) throws Exception {
        if (!ready(role != Role.LOCAL) || (role != Role.CLIENT && role != Role.LOCAL)) throw new IllegalStateException(status());
        ImpactProposal proposal = new ImpactProposal(shot.id(), contacts);
        Pending p = pending.get(shot.id());
        if (p == null || p.fire == null || p.failed || !ledger.current(shot.id())) throw new IllegalStateException("Uncommitted impact");
        if (role == Role.CLIENT) send(-1, proposal); else commit(p, proposal.contacts());
    }
    private void commit(Pending p, List<Contact> proposals) throws Exception {
        if (p.failed || proposals.isEmpty() || p.admitted == null) return;
        Id id = p.admitted.id();
        try {
            List<Contact> validated = contacts(id, authority.validate(p.admitted, List.copyOf(proposals)));
            // The authority may replace geometry/zone with server evidence, never invent an unproposed key.
            HashSet<ImpactKey> proposed = new HashSet<>(); for (Contact c : proposals) proposed.add(c.key());
            if (validated.stream().anyMatch(c -> !proposed.contains(c.key()))) throw new IllegalStateException("Authority invented impact key");
            commitValidated(p,validated);
        } catch (Exception e) { disabled = true; failure = "impact-commit"; fail(id, failure); throw e; }
    }
    /** Java authority only; the Lua/wire boundary exposes no such operation.
     * The server flight may hit a target the client never proposed or saw. */
    public synchronized void serverImpact(Id id,List<Contact> contacts) throws Exception {
        Pending p=pending.get(id);
        if (role!=Role.SERVER || !ready(true) || !authority.serverSimulated() || p==null || p.failed || p.admitted==null
                || !ledger.current(id)) throw new IllegalStateException("Server impact without admission");
        try { commitValidated(p,contacts(id,contacts)); }
        catch (Exception error) { disabled=true;failure="server-impact";fail(id,failure);throw error; }
    }
    private void commitValidated(Pending p,List<Contact> validated) throws Exception {
        ArrayList<Contact> unique=new ArrayList<>();
        for (Contact c:validated) if (ledger.impact(c.key())) unique.add(c);
        if (unique.isEmpty()) return;
        List<ImpactKey> applied=List.copyOf(damage.commit(p.admitted,List.copyOf(unique)));
        HashSet<ImpactKey> attempted=new HashSet<>();for (Contact c:unique) attempted.add(c.key());
        if (new HashSet<>(applied).size()!=applied.size() || !attempted.containsAll(applied)) throw new IllegalStateException("Damage result keys");
        send(-2,new ImpactConfirmed(p.admitted.id(),applied));
    }
    /** Only the server-command receiver calls this; presentation has no Damage reference. */
    public synchronized boolean confirm(Message message) {
        if ((role != Role.CLIENT && role != Role.OBSERVER) || !ready(true)) return false;
        Id id = message instanceof Accepted a ? a.id() : message instanceof Rejected r ? r.id()
                : message instanceof ImpactConfirmed i ? i.id() : null;
        expire();
        if (id == null || !ledger.session().equals(id.session())) return false;
        // The transport authenticated the server. It can introduce another shooter's
        // epoch for presentation, but cannot turn the observer into a damage authority.
        if (!presentationLedger.connect(id.shooter(), id.epoch())) return false;
        if (!presentationLedger.contains(id) && !presentationLedger.reserve(id,clock.getAsLong()+ShotLedger.MAX_LIFETIME_NANOS)) return false;
        if (message instanceof Accepted && !presentedShots.add(id)) return false;
        if (message instanceof ImpactConfirmed confirmed) {
            ArrayList<ImpactKey> fresh = new ArrayList<>();
            for (ImpactKey key : confirmed.keys()) if (presentedImpacts.add(key)) fresh.add(key);
            if (fresh.isEmpty()) return false;
            message = new ImpactConfirmed(id, fresh);
        }
        if (presentedImpacts.size() > 32768) return false;
        if (message instanceof Rejected r) { ledger.finish(id, r.reason()); Pending p = pending.get(id); if (p != null) p.failed = true; }
        if (presentation != null) presentation.confirmed(message);
        return true;
    }
    public synchronized void disconnect(int shooter) {
        ledger.disconnect(shooter); pending.keySet().removeIf(id -> id.shooter() == shooter);
        presentationLedger.disconnect(shooter);
        nativeShots.keySet().removeIf(k -> k.shooter() == shooter); correlations.keySet().removeIf(k -> k.shooter() == shooter);
        if (handshake != null && handshake.shooter() == shooter) { handshake = null; nativeCounter = null; }
    }
    /** Save/load and reconnect must create a fresh epoch/session, never serialize this object. */
    public synchronized void reset(String newSession) {
        ledger.reset(newSession); presentationLedger.reset(newSession); pending.clear(); nativeShots.clear(); correlations.clear();
        presentedShots.clear(); presentedImpacts.clear(); handshake = null; nativeCounter = null; challenge = null;
    }
    private static List<Contact> contacts(Id id, List<Contact> source) {
        List<Contact> result = List.copyOf(source);
        if (result.size() > MAX_CONTACTS || result.stream().anyMatch(c -> !c.key().pellet().shot().equals(id))
                || result.stream().map(Contact::key).distinct().count() != result.size()) throw new IllegalArgumentException("Impact batch");
        return result;
    }

    // Explicit bounded wire schema. All uint64 values remain raw bits, never doubles.
    public static String wire(Message message) throws IOException { return Base64.getEncoder().encodeToString(encode(message)); }
    public static Message unwire(String wire) throws IOException {
        if (wire == null || wire.length() > (MAX_WIRE_BYTES + 2) / 3 * 4) throw new IOException("Wire size");
        try { return decode(Base64.getDecoder().decode(wire)); } catch (IllegalArgumentException e) { throw new IOException("Wire base64", e); }
    }
    public static byte[] encode(Message message) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(0x54575348); out.writeShort(VERSION);
            if (message instanceof Hello h) {
                out.writeByte(1); text(out,h.challenge()); text(out,h.session()); out.writeLong(h.epoch()); out.writeInt(h.shooter());
                text(out,h.dataHash()); out.writeInt(h.schema()); out.writeInt(h.metric()); out.writeInt(h.replay());
                out.writeLong(h.nativeBase()); out.writeBoolean(h.capable()); text(out,h.reason());
            } else if (message instanceof FireIntent f) {
                out.writeByte(2); snapshot(out,f.shot()); out.writeLong(f.nativeSerial()); out.writeByte(f.nativeByte());
                vec(out,f.launch()); ShotBodies.write(out,f.bodies());
                // Optional tails in tag order: an intent without a relief and of the Viewpoint view is byte-identical
                // to the one before the tails existed.
                if (f.relief().samples() > 0) { out.writeByte(1); Relief.write(out,f.relief()); }
                if (f.view() != View.VIEWPOINT) { out.writeByte(2); out.writeByte(f.view().ordinal()); }
            } else if (message instanceof ImpactProposal p) {
                out.writeByte(3); id(out,p.id()); out.writeInt(p.contacts().size()); for (Contact c : p.contacts()) contact(out,c);
            } else if (message instanceof Accepted a) { out.writeByte(4); id(out,a.id());
            } else if (message instanceof Rejected r) { out.writeByte(5); id(out,r.id()); text(out,r.reason());
            } else if (message instanceof ImpactConfirmed c) {
                out.writeByte(6); id(out,c.id()); out.writeInt(c.keys().size()); for (ImpactKey key : c.keys()) key(out,key);
            } else throw new IOException("Message type");
        }
        if (buffer.size() > MAX_WIRE_BYTES) throw new IOException("Wire size");
        return buffer.toByteArray();
    }
    public static Message decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_WIRE_BYTES) throw new IOException("Wire size");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x54575348 || in.readUnsignedShort() != VERSION) throw new IOException("Wire version");
            Message result = switch (in.readUnsignedByte()) {
                case 1 -> new Hello(text(in),text(in),in.readLong(),in.readInt(),text(in),in.readInt(),in.readInt(),in.readInt(),in.readLong(),in.readBoolean(),text(in));
                case 2 -> fireIntent(in);
                case 3 -> { Id id=id(in); int n=count(in,MAX_CONTACTS); ArrayList<Contact> rows=new ArrayList<>();
                    for(int i=0;i<n;i++) rows.add(contact(in)); yield new ImpactProposal(id,rows); }
                case 4 -> new Accepted(id(in));
                case 5 -> new Rejected(id(in),text(in));
                case 6 -> { Id id=id(in); int n=count(in,MAX_CONTACTS); ArrayList<ImpactKey> rows=new ArrayList<>();
                    for(int i=0;i<n;i++) rows.add(key(in)); yield new ImpactConfirmed(id,rows); }
                default -> throw new IOException("Wire message");
            };
            if (in.available() != 0) throw new IOException("Wire trailing data");
            return result;
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) { throw new IOException("Wire values", e); }
    }
    private static FireIntent fireIntent(DataInputStream in) throws IOException {
        ShotSnapshot shot = snapshot(in); long serial = in.readLong(); int nativeByte = in.readUnsignedByte();
        Vec3 launch = vec(in); List<ShotBodies.Body> bodies = ShotBodies.read(in);
        Relief relief = Relief.NONE; View view = View.VIEWPOINT;
        // Tails: 1 relief, 2 view, each at most once and in that order.
        for (int last = 0; in.available() > 0; ) {
            int tag = in.readUnsignedByte();
            if (tag <= last) throw new IOException("Wire tail order");
            last = tag;
            if (tag == 1) {
                relief = Relief.read(in);
                if (relief.samples() == 0) throw new IOException("Wire relief");
            } else if (tag == 2) {
                int value = in.readUnsignedByte();
                if (value == View.VIEWPOINT.ordinal() || value >= View.values().length) throw new IOException("Wire view");
                view = View.values()[value];
            } else throw new IOException("Wire relief");
        }
        return new FireIntent(shot, serial, nativeByte, launch, bodies, relief, view);
    }
    private static int count(DataInputStream in,int maximum) throws IOException {
        int value=in.readInt(); if(value<0 || value>maximum) throw new IOException("Wire count"); return value;
    }
    private static void text(DataOutputStream out,String text) throws IOException {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8); if(bytes.length>4096) throw new IOException("Wire string");
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String text(DataInputStream in) throws IOException {
        int length=count(in,4096); byte[] bytes=in.readNBytes(length);
        if(bytes.length!=length) throw new IOException("Truncated wire string");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static void id(DataOutputStream out,Id id) throws IOException { text(out,id.session()); out.writeLong(id.epoch()); out.writeInt(id.shooter()); out.writeLong(id.sequence()); }
    private static Id id(DataInputStream in) throws IOException { return new Id(text(in),in.readLong(),in.readInt(),in.readLong()); }
    private static void vec(DataOutputStream out,Vec3 v) throws IOException { out.writeFloat(v.x()); out.writeFloat(v.y()); out.writeFloat(v.z()); }
    private static Vec3 vec(DataInputStream in) throws IOException { return new Vec3(in.readFloat(),in.readFloat(),in.readFloat()); }
    private static void key(DataOutputStream out,ImpactKey key) throws IOException { id(out,key.pellet().shot()); out.writeInt(key.pellet().index()); out.writeInt(key.contactOrdinal()); text(out,key.target()); }
    private static ImpactKey key(DataInputStream in) throws IOException { return new ImpactKey(new PelletId(id(in),in.readInt()),in.readInt(),text(in)); }
    private static void contact(DataOutputStream out,Contact c) throws IOException {
        key(out,c.key()); out.writeByte(c.kind().ordinal()); out.writeFloat(c.fraction()); vec(out,c.point()); vec(out,c.normal());
        out.writeInt(c.ragdollPart()); out.writeInt(c.bodyPart()); text(out,c.material()); text(out,c.proof());
    }
    private static Contact contact(DataInputStream in) throws IOException {
        return new Contact(key(in),TargetKind.values()[in.readUnsignedByte()],in.readFloat(),vec(in),vec(in),in.readInt(),in.readInt(),text(in),text(in));
    }
    private static void snapshot(DataOutputStream out,ShotSnapshot s) throws IOException {
        id(out,s.id()); text(out,s.dataHash()); out.writeLong(s.seed()); out.writeInt(s.replayVersion()); out.writeDouble(s.startTimeSeconds());
        Weapon w=s.weapon(); out.writeInt(w.id()); text(out,w.fullType()); text(out,w.key()); text(out,w.tpl()); text(out,w.assemblyRevision()); out.writeLong(w.actionGeneration());
        if(s.parts().size()>1024) throw new IOException("Parts count"); out.writeInt(s.parts().size());
        for(Part p:s.parts()) { text(out,p.path()); text(out,p.tpl()); text(out,p.instance()); }
        text(out,s.actualAmmoTpl()); text(out,s.nativeAmmoType()); text(out,s.profile());
        Ammo a=s.ammo(); text(out,a.tpl()); out.writeFloat(a.initialSpeed()); out.writeFloat(a.massGrams()); out.writeFloat(a.diameterMillimetres());
        out.writeFloat(a.ballisticCoefficient()); out.writeFloat(a.lifeSeconds()); out.writeInt(a.projectileCount()); out.writeBoolean(a.inert());
        out.writeFloat(s.speedFactor()); out.writeFloat(s.initialSpeed()); out.writeFloat(s.dispersion());
        ShotPoseSnapshot p=s.pose(); out.writeLong(p.frameNumber()); out.writeLong(p.takenNanos()); out.writeLong(p.poseGeneration());
        out.writeInt(p.stateIndex()); out.writeInt(p.weaponId()); text(out,p.assemblyRevision()); text(out,p.channel()); out.writeInt(p.mode()); out.writeFloat(p.zeroMetres());
        out.writeDouble(p.muzzlePz().x()); out.writeDouble(p.muzzlePz().y()); out.writeDouble(p.muzzlePz().z()); vec(out,p.boreForward()); vec(out,p.boreUp());
        for(double value:p.worldWeaponFrame()) out.writeDouble(value);
        var z=p.zero(); out.writeByte(z.kind().ordinal()); text(out,z.defaultAmmoTpl()); out.writeDouble(z.pitchRadians()); out.writeDouble(z.yawRadians()); out.writeBoolean(z.appliedToPose());
        out.writeInt(s.pelletDirections().size()); for(Vec3 v:s.pelletDirections()) vec(out,v);
        // NEVER serialize client native weapon bytes or load a client InventoryItem.
    }
    private static ShotSnapshot snapshot(DataInputStream in) throws IOException {
        Id id=id(in); String hash=text(in); long seed=in.readLong(); int replay=in.readInt(); double start=in.readDouble();
        Weapon w=new Weapon(in.readInt(),text(in),text(in),text(in),text(in),in.readLong());
        int n=count(in,1024); ArrayList<Part> parts=new ArrayList<>(); for(int i=0;i<n;i++) parts.add(new Part(text(in),text(in),text(in)));
        String actual=text(in),nativeType=text(in),profile=text(in);
        Ammo a=new Ammo(text(in),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readInt(),in.readBoolean());
        float factor=in.readFloat(),speed=in.readFloat(),dispersion=in.readFloat();
        long frame=in.readLong(),taken=in.readLong(),generation=in.readLong(); int state=in.readInt(),weaponId=in.readInt();
        String revision=text(in),channel=text(in); int mode=in.readInt(); float zero=in.readFloat();
        var muzzle=new ShotPoseSnapshot.Pz(in.readDouble(),in.readDouble(),in.readDouble()); Vec3 forward=vec(in),up=vec(in);
        ArrayList<Double> transform=new ArrayList<>(); for(int i=0;i<12;i++) transform.add(in.readDouble());
        var calibration=new ShotPoseSnapshot.Zero(ShotPoseSnapshot.ZeroKind.values()[in.readUnsignedByte()],text(in),in.readDouble(),in.readDouble(),in.readBoolean());
        var pose=new ShotPoseSnapshot(frame,taken,generation,state,weaponId,revision,channel,mode,zero,muzzle,forward,up,transform,calibration);
        n=count(in,1024); ArrayList<Vec3> directions=new ArrayList<>(); for(int i=0;i<n;i++) directions.add(vec(in));
        // A sentinel only. Server acceptance always replaces it with trusted preparation bytes.
        return new ShotSnapshot(id,hash,seed,replay,start,w,parts,actual,nativeType,profile,a,factor,speed,dispersion,pose,directions,new NativeWeapon(0,new byte[]{0}));
    }
}
