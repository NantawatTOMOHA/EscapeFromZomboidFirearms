package pz.escapefromzomboidfirearms.zb;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** W11 hostauth: what the shooter's client reports to a server without the mod's Java (variant B,
 * docs/pending/HOST_CLIENT_AUTHORITY_RU.md). The server answered Hello with Authority{session, epoch, policy}; this
 * client flies its claimed rounds (ShotOwner, LocalIntegration with ShotClientDamage) and every contact it applied, with
 * the marks of its world contacts, goes to the server's Lua as one Hits row per round and tick (TW_ShotOwnerNet client,
 * TW_ShotAuthority server). Standard Java only: rows are records, the Lua table is built by ShotClientLua.
 */
public final class ShotClientAuthority {
    /** One applied contact: the target as this client drew it (a character's root; a world object's square; a vehicle's
     * position), the contact point (PZ cells/levels), PZ's zones, damage before (applyRangeHitLocationDamage; a vehicle's
     * damage roll) and after (the Hit's processHitDamage), the hit fields to relay; for players and world objects the
     * weapon copy's damage range, a world object's "sprite|material" or a vehicle's part ID, a door's state (1 open). */
    public record Contact(String kind,int id,double x,double y,double z,double tx,double ty,double tz,int ragdoll,int part,
            float damage,float applied,float range,boolean crit,int pellet,int ordinal,String hit,String object,float min,float max,
            int state) { }
    /** One Hits message: a round's new contacts and marks since the last row. */
    public record Row(String sequence,int weapon,String view,int pellets,double sx,double sy,double sz,
            List<Contact> contacts,List<String> marks,String grenade) { }
    /** Rows a message carries at most (sendClientCommand stays far under its ~32 KB string; the rest goes next tick). */
    static final int ROW_CONTACTS=16, ROW_MARKS=32, MAX_SHOTS=256;
    private static final class Shot {
        final String sequence,view;final int weapon,pellets;final double sx,sy,sz;
        String grenade="";
        final ArrayList<Contact> contacts=new ArrayList<>();final ArrayList<String> marks=new ArrayList<>();
        Shot(ShotSnapshot shot,String view) {
            sequence=shot.id().sequenceText();weapon=shot.weapon().id();pellets=shot.ammo().projectileCount();this.view=view;
            var m=shot.pose().muzzlePz();sx=m.x();sy=m.y();sz=m.z();
        }
    }
    private static final Map<ShotSnapshot.Id,Shot> shots=new LinkedHashMap<>(64,.75f,false) {
        @Override protected boolean removeEldestEntry(Map.Entry<ShotSnapshot.Id,Shot> eldest) { return size()>MAX_SHOTS; }
    };
    private static final ArrayDeque<Shot> pending=new ArrayDeque<>();
    static String session;
    static long epoch;
    static int policy;
    static long fired,rows,contacts,marks,accepted,refused,unrelayed;
    static String lastRefused="none",lastReason="none",lastSequence="0";
    private ShotClientAuthority() { }

    static synchronized void reset(String worldSession,long connectionEpoch,int mode) {
        session=worldSession;epoch=connectionEpoch;policy=mode;shots.clear();pending.clear();
    }
    static synchronized void clear(String reason) { session=null;epoch=0;policy=0;shots.clear();pending.clear();lastReason=reason; }
    static synchronized boolean active() { return session!=null; }
    /** A claimed round left the muzzle (ShotOwner.publish): its record, even before any contact. */
    static synchronized void fired(ShotSnapshot shot,boolean isometric) {
        if (session==null) return;
        shots.put(shot.id(),new Shot(shot,isometric?"isometric":"viewpoint"));fired++;lastSequence=shot.id().sequenceText();
    }
    /** A hand grenade's fragments this client flies for the server (HandGrenadeNative.startFragments): rows name the
     * detonation, whose server ticket checks them. */
    static synchronized void grenade(ShotSnapshot shot,String detonation) {
        if (session==null) return;
        Shot s=new Shot(shot,"grenade");s.grenade=detonation;shots.put(shot.id(),s);fired++;
    }
    static synchronized void contact(ShotSnapshot shot,Contact contact) {
        Shot s=shots.get(shot.id());
        if (s==null) return;
        s.contacts.add(contact);contacts++;
        if (contact.hit()==null) unrelayed++;
        queue(s);
    }
    /** A world or vehicle contact of a claimed round: the same mark for every other client (ShotHitWire.mark). */
    static synchronized void mark(ShotSnapshot shot,ShotPenetration.Diagnostic event,Object struck) throws java.io.IOException {
        Shot s=shots.get(shot.id());
        if (s==null) return;
        String payload=ShotHitWire.mark(shot,event,struck);
        if (payload==null) return;
        s.marks.add(payload);marks++;
        queue(s);
    }
    private static void queue(Shot s) { if (!pending.contains(s)) pending.addLast(s); }
    /** The next Hits row, or null. A round with more than one row's contacts or marks stays queued. */
    static synchronized Row poll() {
        Shot s=pending.peekFirst();
        if (s==null) return null;
        List<Contact> c=List.copyOf(s.contacts.subList(0,Math.min(ROW_CONTACTS,s.contacts.size())));
        List<String> m=List.copyOf(s.marks.subList(0,Math.min(ROW_MARKS,s.marks.size())));
        s.contacts.subList(0,c.size()).clear();s.marks.subList(0,m.size()).clear();
        if (s.contacts.isEmpty() && s.marks.isEmpty()) pending.removeFirst();
        rows++;
        return new Row(s.sequence,s.weapon,s.view,s.pellets,s.sx,s.sy,s.sz,c,m,s.grenade);
    }
    static synchronized void refused(String key,String reason) { refused++;lastRefused=key+":"+reason; }
    static synchronized void accepted(int count) { accepted+=Math.max(0,count); }
    static synchronized String info() {
        return "auth={"+(session==null?"off reason="+lastReason:"session="+session+" epoch="+Long.toUnsignedString(epoch)+" policy="+policy)
                +" fired="+fired+" lastSeq="+lastSequence+" rows="+rows+" contacts="+contacts+" marks="+marks+" accepted="+accepted+" refused="+refused
                +" lastRefused="+lastRefused+" unrelayed="+unrelayed+" pending="+pending.size()+"}";
    }
}
