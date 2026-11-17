package pz.escapefromzomboidfirearms.zb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import pz.escapefromzomboidfirearms.zb.ShotSnapshot.*;

/** Authority and ledger boundary only. Native health/armour remain PZ's. A/C must
 * explicitly enable this instance after their suppression/authority integration.
 * The ledger consumes a key BEFORE a native mutation, even if that mutation throws.
 */
public final class ShotDamage implements Damage {
    public interface Native {
        boolean ready();
        /** Resolve a live identity and frozen weapon, without health/world mutation. Null rejects safely. */
        Prepared prepare(ShotSnapshot shot, Contact contact) throws Exception;
    }
    public interface Prepared { boolean apply() throws Exception; }
    private final Native adapter;
    private final Ledger ledger;
    private final Predicate<ShotSnapshot> authority;
    private final Consumer<String> failure;
    private volatile boolean enabled, failed;
    private boolean committing;
    private final HashMap<Id,HashSet<PelletId>> terminal=new HashMap<>();
    private final HashMap<Id,HashSet<String>> attemptedContacts=new HashMap<>();

    public ShotDamage(Native adapter, Ledger ledger, Predicate<ShotSnapshot> authority, Consumer<String> failure) {
        this.adapter=Objects.requireNonNull(adapter);this.ledger=Objects.requireNonNull(ledger);
        this.authority=Objects.requireNonNull(authority);this.failure=Objects.requireNonNull(failure);
    }
    public void enabled(boolean value) { enabled=value && !failed; }
    /** C calls this after it has retired/tombstoned the shot in its authority ledger. */
    public synchronized void finish(Id id) { terminal.remove(id);attemptedContacts.remove(id); }
    public synchronized void clear() { enabled=false;terminal.clear();attemptedContacts.clear(); }
    @Override public boolean ready() { return enabled && !failed && adapter.ready(); }
    @Override public synchronized List<ImpactKey> commit(ShotSnapshot shot,List<Contact> contacts) throws Exception {
        Objects.requireNonNull(shot);contacts=List.copyOf(contacts);
        if (committing) throw new IllegalStateException("Nested damage commit");
        committing=true;
        try {
            if (!ready() || !authority.test(shot)) return List.of();
            if (!terminal.containsKey(shot.id()) && terminal.size()>=4096) throw new IllegalStateException("Damage shot capacity");
            HashSet<PelletId> stopped=terminal.computeIfAbsent(shot.id(),id->new HashSet<>());
            // Only a first stopping contact per pellet is accepted, including a
            // non-damaging wall/unloaded hit. A batch cannot shoot through it.
            HashMap<PelletId,Contact> first=new HashMap<>();
            ArrayList<Contact> continuing=new ArrayList<>();
            for (Contact c:contacts) {
                if (!c.key().pellet().shot().equals(shot.id()) || c.key().pellet().index()>=shot.ammo().projectileCount()
                        || c.effect()==null && c.key().contactOrdinal()!=0 || c.key().contactOrdinal()>=4096
                        || !c.forwardHit()) throw new IllegalArgumentException("Foreign/continuing impact");
                if (c.kind()==TargetKind.CHARACTER && (c.ragdollPart()<0 || c.bodyPart()<0)) {
                    throw new IllegalArgumentException("Unknown native zone");
                }
                if (c.effect()!=null) continuing.add(c);
                else first.merge(c.key().pellet(),c,(a,b)->ShotContacts.ORDER.compare(a,b)<=0?a:b);
            }
            ArrayList<Contact> ordered=new ArrayList<>(first.values());
            ordered.removeIf(c->stopped.contains(c.key().pellet()));
            ordered.addAll(continuing);
            ordered.sort(java.util.Comparator.<Contact>comparingInt(c->c.key().pellet().index())
                    .thenComparingInt(c->c.key().contactOrdinal()).thenComparing(ShotContacts.ORDER));
            ArrayList<Prepared> prepared=new ArrayList<>();
            for (Contact c:ordered) prepared.add(adapter.prepare(shot,c));
            ArrayList<ImpactKey> applied=new ArrayList<>(); HashSet<ImpactKey> seen=new HashSet<>();
            for (int i=0;i<ordered.size();i++) {
                Contact c=ordered.get(i);Prepared p=prepared.get(i);
                if (c.effect()==null) stopped.add(c.key().pellet());
                HashSet<String> attempts=attemptedContacts.computeIfAbsent(shot.id(),id->new HashSet<>());
                String ordinal=c.key().pellet().index()+"/"+c.key().contactOrdinal();
                if (attempts.size()>=4096) throw new IllegalStateException("Damage contact capacity");
                if (attempts.add(ordinal) && p!=null && seen.add(c.key()) && ledger.impact(c.key()) && p.apply()) applied.add(c.key());
            }
            return List.copyOf(applied);
        } catch (Exception | LinkageError ex) {
            enabled=false;
            if (!failed) { failed=true;failure.accept("shot damage: "+ex); }
            throw ex;
        } finally { committing=false; }
    }
}
