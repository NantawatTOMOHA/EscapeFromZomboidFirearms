package pz.escapefromzomboidfirearms.zb;

import me.zed_0xff.zombie_buddy.Exposer;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;

/** W11 hostauth: client Lua entries of the shooter-side owner on a server without the mod's Java
 * (TW_ShotOwnerNet client). Inert unless the server answered Hello with Authority. */
@Exposer.LuaClass
public final class ShotClientLua {
    private ShotClientLua() { }
    /** The server's Authority{session, epoch (unsigned decimal text), policy 3|4, the Hello's challenge}. */
    public static boolean authority(String session,String epoch,double policy,String challenge) {
        try {
            if (epoch==null || !epoch.matches("[0-9]{1,20}")) return false;
            return ShotOwner.netAuthority(session,Long.parseUnsignedLong(epoch),(int)policy,challenge);
        } catch (NumberFormatException e) { return false; }
    }
    /** The server gave this client the flight of its rounds (the Hello loop stops). */
    public static boolean held() { return ShotOwner.clientAuthority(); }
    /** ... and the live owner is ready to claim the next shot of the weapon in hand. */
    public static boolean ready() { return ShotOwner.clientAuthorityReady(); }
    /** The next Hits row as the Lua table sendClientCommand carries, or nil. */
    public static KahluaTable poll() {
        var row=ShotClientAuthority.poll();
        if (row==null) return null;
        KahluaTable t=LuaManager.platform.newTable(),list=LuaManager.platform.newTable(),marks=LuaManager.platform.newTable();
        t.rawset("seq",row.sequence());t.rawset("weapon",(double)row.weapon());t.rawset("view",row.view());
        t.rawset("pellets",(double)row.pellets());t.rawset("sx",row.sx());t.rawset("sy",row.sy());t.rawset("sz",row.sz());
        if (!row.grenade().isEmpty()) t.rawset("grenade",row.grenade());
        int i=1;
        for (var c:row.contacts()) {
            KahluaTable e=LuaManager.platform.newTable();
            e.rawset("kind",c.kind());e.rawset("id",(double)c.id());
            e.rawset("x",c.x());e.rawset("y",c.y());e.rawset("z",c.z());e.rawset("tx",c.tx());e.rawset("ty",c.ty());e.rawset("tz",c.tz());
            e.rawset("ragdoll",(double)c.ragdoll());e.rawset("part",(double)c.part());
            e.rawset("damage",(double)c.damage());e.rawset("applied",(double)c.applied());e.rawset("range",(double)c.range());
            e.rawset("crit",c.crit());e.rawset("pellet",(double)c.pellet());e.rawset("ordinal",(double)c.ordinal());
            if (c.hit()!=null) e.rawset("hit",c.hit());
            e.rawset("object",c.object());e.rawset("min",(double)c.min());e.rawset("max",(double)c.max());e.rawset("state",(double)c.state());
            list.rawset(i++,e);
        }
        i=1;
        for (String m:row.marks()) marks.rawset(i++,m);
        t.rawset("contacts",list);t.rawset("marks",marks);
        return t;
    }
    /** The server's answer to a row: contacts it accepted, or one it refused with its reason. */
    public static void accepted(double count) { ShotClientAuthority.accepted((int)count); }
    public static void refused(String key,String reason) { ShotClientAuthority.refused(String.valueOf(key),String.valueOf(reason)); }
    public static void baseline(String reason) { ShotOwner.netAuthorityEnd(String.valueOf(reason)); }
    public static String status() { return ShotClientAuthority.info(); }
    /** W11 hostauth stage 3: the server asks this thrower to fly its grenade's fragments (TW_ShotAuthority Fragments). */
    public static String fragments(String id,String grenadeTpl,String fragmentTpl,double count,double seed,double x,double y,double z) {
        var p=zombie.characters.IsoPlayer.players[0];
        if (!ShotOwner.clientAuthority() || p==null) return "native";
        return HandGrenadeNative.startFragments(p,grenadeTpl,fragmentTpl,(int)count,id,(long)seed,x,y,z,3);
    }
    /** The client's fragment batches advance with the game (HandGrenades.tick does it where the grenade is decided). */
    public static void fragmentsTick(double seconds) { if (ShotOwner.clientAuthority()) HandGrenadeNative.tick(seconds); }
}
