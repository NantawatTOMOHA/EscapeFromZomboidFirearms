package pz.escapefromzomboidfirearms.zb;

import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import pz.escapefromzomboidfirearms.zb.ShotSnapshot.Id;
import pz.escapefromzomboidfirearms.zb.ShotSnapshot.ImpactKey;

/** Transient authority ledger. Expiration frees memory, never the replay window.
 * No inventory, callbacks, game classes or persistent ModData are reachable here.
 */
public final class ShotLedger implements ShotSnapshot.Ledger {
    public static final int REPLAY_WINDOW = 4096;
    public static final long MAX_LIFETIME_NANOS = 120_000_000_000L;
    private final LongSupplier clock;
    private final int capacity;
    private String session;
    private final Map<Integer, Stream> streams = new HashMap<>();
    private final Map<Id, Entry> entries = new HashMap<>();
    private final Map<NativeKey, Id> nativeIds = new HashMap<>();
    private long reserved, nativeFires, impacts, rejected, finished;

    public record NativeKey(int shooter, long epoch, long serial) {
        public NativeKey { if (epoch == 0 || serial == 0) throw new IllegalArgumentException("Native identity"); }
    }
    public record Stats(int active, long reserved, long nativeFires, long impacts, long rejected, long finished) { }
    private static final class Stream {
        final long epoch;
        final Window shots = new Window(), natives = new Window();
        boolean connected = true;
        Stream(long epoch) { this.epoch = epoch; }
    }
    private static final class Window {
        final BitSet seen = new BitSet(REPLAY_WINDOW);
        long high;
        boolean take(long sequence) {
            if (high == 0) { high = sequence; seen.set(0); return true; }
            int order = Long.compareUnsigned(sequence, high);
            if (order > 0) {
                long distance = sequence - high;
                BitSet previous = (BitSet) seen.clone();
                seen.clear();
                if (Long.compareUnsigned(distance, REPLAY_WINDOW) < 0) {
                    int shift = (int) distance;
                    for (int i = previous.nextSetBit(0); i >= 0 && i + shift < REPLAY_WINDOW;
                            i = previous.nextSetBit(i + 1)) seen.set(i + shift);
                }
                high = sequence; seen.set(0); return true;
            }
            long age = high - sequence;
            if (Long.compareUnsigned(age, REPLAY_WINDOW) >= 0 || seen.get((int) age)) return false;
            seen.set((int) age); return true;
        }
    }
    private static final class Entry {
        long expiry;
        int nativeByte = -1, pellets;
        NativeKey nativeKey;
        final HashSet<ImpactKey> attempted = new HashSet<>();
        Entry(long expiry) { this.expiry = expiry; }
    }

    public ShotLedger(String session) { this(session, 2048, System::nanoTime); }
    public ShotLedger(String session, int capacity, LongSupplier clock) {
        ShotSnapshot.text(session);
        if (capacity < 1 || capacity > 65536) throw new IllegalArgumentException("Ledger capacity");
        this.session = session; this.capacity = capacity; this.clock = Objects.requireNonNull(clock);
    }
    public synchronized String session() { return session; }

    /** Trusted connection lifecycle only. Epochs strictly increase as unsigned uint64.
     * Retransmitted handshakes do not clear replay state; a retired epoch cannot reopen.
     */
    public synchronized boolean connect(int shooter, long epoch) {
        if (epoch == 0) return false;
        Stream old = streams.get(shooter);
        if (old != null && Long.compareUnsigned(epoch, old.epoch) <= 0) return old.epoch == epoch && old.connected;
        entries.entrySet().removeIf(e -> e.getKey().shooter() == shooter);
        nativeIds.keySet().removeIf(k -> k.shooter() == shooter);
        streams.put(shooter, new Stream(epoch));
        return true;
    }
    public synchronized boolean current(Id id) {
        Stream stream = streams.get(id.shooter());
        return session.equals(id.session()) && stream != null && stream.connected && stream.epoch == id.epoch();
    }
    public synchronized void disconnect(int shooter) {
        // Keep the retired stream's replay/epoch fence until a new world session.
        Stream stream = streams.get(shooter); if (stream != null) stream.connected = false;
        entries.entrySet().removeIf(e -> e.getKey().shooter() == shooter);
        nativeIds.keySet().removeIf(k -> k.shooter() == shooter);
    }
    public synchronized void reset(String newSession) {
        ShotSnapshot.text(newSession);
        if (session.equals(newSession)) throw new IllegalArgumentException("A load needs a new session nonce");
        entries.clear(); nativeIds.clear(); streams.clear(); session = newSession;
    }
    public synchronized void expire() {
        long now = clock.getAsLong();
        entries.entrySet().removeIf(e -> now - e.getValue().expiry >= 0);
        nativeIds.entrySet().removeIf(e -> !entries.containsKey(e.getValue()));
    }
    @Override public synchronized boolean reserve(Id id, long expiresNanos) {
        Objects.requireNonNull(id); expire();
        long remaining = expiresNanos - clock.getAsLong();
        if (!current(id) || remaining <= 0 || remaining > MAX_LIFETIME_NANOS
                || entries.size() >= capacity || !streams.get(id.shooter()).shots.take(id.sequence())) {
            rejected++; return false;
        }
        entries.put(id, new Entry(expiresNanos)); reserved++; return true;
    }
    public synchronized boolean contains(Id id) { expire(); return current(id) && entries.containsKey(id); }
    public synchronized int nativeByte(Id id) { return contains(id) ? entries.get(id).nativeByte : -1; }
    public synchronized boolean extend(Id id,long expiry) {
        long remaining=expiry-clock.getAsLong();
        if (!contains(id) || remaining<=0 || remaining>MAX_LIFETIME_NANOS) return false;
        Entry entry=entries.get(id);
        if (expiry-entry.expiry>0) entry.expiry=expiry;
        return true;
    }
    public synchronized boolean attach(ShotSnapshot shot) {
        Entry entry = entries.get(shot.id());
        if (!contains(shot.id()) || entry == null) return false;
        if (entry.pellets != 0 && entry.pellets != shot.pelletDirections().size()) return false;
        entry.pellets = shot.pelletDirections().size(); return true;
    }
    @Override public synchronized boolean nativeFire(Id id, int nativeShotByte) {
        if (nativeShotByte < -128 || nativeShotByte > 255 || !contains(id)) { rejected++; return false; }
        Entry entry = entries.get(id);
        if (entry.nativeByte != -1) { rejected++; return false; }
        entry.nativeByte = nativeShotByte & 255; nativeFires++; return true;
    }
    /** A byte alone is never a unique network initiation; a trusted extended serial is required. */
    public synchronized boolean bindNative(Id id, long serial, int nativeShotByte) {
        if (serial == 0 || (int)(serial & 255) != (nativeShotByte & 255) || !contains(id)) return false;
        NativeKey key = new NativeKey(id.shooter(), id.epoch(), serial);
        if (nativeIds.containsKey(key) || !streams.get(id.shooter()).natives.take(serial)) return false;
        Entry entry = entries.get(id);
        if (!nativeFire(id, nativeShotByte)) return false;
        entry.nativeKey = key; nativeIds.put(key, id); return true;
    }
    @Override public synchronized boolean impact(ImpactKey key) {
        Objects.requireNonNull(key);
        Id id = key.pellet().shot();
        if (!contains(id)) { rejected++; return false; }
        Entry entry = entries.get(id);
        if (entry.nativeByte == -1 || entry.pellets == 0 || key.pellet().index() >= entry.pellets
                || entry.attempted.size() >= 4096 || !entry.attempted.add(key)) { rejected++; return false; }
        // Mark BEFORE invoking native damage. An exception can never authorize a retry.
        impacts++; return true;
    }
    @Override public synchronized void finish(Id id, String reason) {
        Objects.requireNonNull(reason);
        Entry entry = entries.remove(id);
        if (entry != null) finished++;
        // Keep the native token occupied until expiry of the corresponding protocol slot.
        // Sequence replay remains fenced by Stream.seen even after entry removal.
    }
    public synchronized Stats stats() { expire(); return new Stats(entries.size(), reserved, nativeFires, impacts, rejected, finished); }
}
