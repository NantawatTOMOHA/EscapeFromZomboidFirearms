package pz.escapefromzomboidfirearms.zb;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import zombie.Lua.LuaEventManager;
import zombie.characters.IsoGameCharacter;
import zombie.inventory.types.HandWeapon;
import zombie.network.GameClient;
import zombie.network.GameServer;

/**
 * Aiming XP of an owned shot, by the vanilla B42 rules (user report 27.10.2026, item 8: "no experience from
 * shooting our weapons").
 *
 * <p>Vanilla raises one Lua event per confirmed ranged hit, and the vanilla Lua then adds the Aiming XP:
 * <ul>
 *   <li>{@code CombatManager.attackCollisionCheck} (single player only: {@code !GameClient.client &&
 *       !GameServer.server}) and {@code zombie.network.fields.hit.WeaponHit.onHit} (server, MP) both call
 *       {@code triggerEvent("OnWeaponHitXp", owner, weapon, hitObject, damage, 1)};</li>
 *   <li>{@code media/lua/server/XpSystem/XpUpdate.lua} answers with {@code addXp(owner, Perks.Aiming, hitCount
 *       * 2.7)} below Aiming 5 and {@code hitCount} from 5, i.e. 2.7 or 1 XP per event; the XP itself
 *       ({@code LuaManager.GlobalObject.addXp -> IsoGameCharacter$XP.AddXP}) applies the perk multiplier map and
 *       the sandbox global XP multiplier, the halo text and the MP sync to the owner.</li>
 * </ul>
 * Our claimed shot replaces PZ's hit list, so neither vanilla place runs for it. This class raises the very same
 * event with the very same arguments, once per hit event, on the side vanilla raises it on: nothing is
 * recomputed here, so amounts, conditions, multipliers and MP replication stay vanilla's own code.
 *
 * <p>Hit events follow vanilla's per-shot rules: one event per target of the shot, and for a weapon with range
 * falloff (the shotguns) at most one event per shot, whose pellets and targets then count once - vanilla's
 * {@code attackCollisionCheck} latches the same flag for {@code HandWeapon.isRangeFalloff()}. The same target is
 * never granted twice for one shot. Fragments and explosions are not shots: {@code HandGrenadeNative} keeps the
 * adapter flag off, exactly as vanilla raises nothing for an explosion.
 */
public final class ShotXp {
    /** Vanilla's event name and hit count of a ranged hit (CombatManager / WeaponHit pass 1). */
    static final String EVENT = "OnWeaponHitXp";
    static final int HIT_COUNT = 1;
    /** Shots whose grants are remembered; a longer burst must not grow the map without bound. */
    static final int REMEMBERED = 512;

    private static final class Grant {
        /** Target identity, never equality: two zombies of one type are two events. */
        final Set<Object> targets = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean done;
    }

    private static final Map<ShotSnapshot.Id, Grant> granted = new LinkedHashMap<>(64, .75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<ShotSnapshot.Id, Grant> eldest) {
            return size() > REMEMBERED;
        }
    };
    private static long calls, grants, refused, duplicates;
    private static boolean logged;
    /** QA only (TWZB.qaXpOff): reproduce the pre-fix build, which raised no event at all, in the same run. */
    private static boolean qaOff;

    /** Offline gate seam (never used by the game): records the raised event instead of the Lua engine. */
    interface Sink {
        void raise(Object owner, Object weapon, Object target, float damage, int count);
    }
    static Sink sink = ShotXp::raiseVanilla;

    private ShotXp() { }

    /** The one place that raises vanilla's event, with vanilla's argument order and types. */
    static void raise(Object owner, Object weapon, Object target, float damage) {
        sink.raise(owner, weapon, target, damage, HIT_COUNT);
    }

    static void raiseVanilla(Object owner, Object weapon, Object target, float damage, int count) {
        LuaEventManager.triggerEvent(EVENT, owner, weapon, target, Float.valueOf(damage), Integer.valueOf(count));
    }

    /** Vanilla's side rule: the single player and the server grant it, a client never does. */
    static boolean side(boolean client, boolean server) {
        return server || !client;
    }

    /**
     * Whether this confirmed hit raises the event: the side, a ranged weapon (the Lua rule adds Aiming only to a
     * ranged weapon), and vanilla's one-event-per-target / one-event-per-shot-for-falloff rule.
     */
    static boolean claim(boolean client, boolean server, boolean ranged, boolean falloff, ShotSnapshot.Id shot, Object target) {
        calls++;
        if (shot == null || target == null || !ranged || !side(client, server) || qaOff) { refused++; return false; }
        synchronized (granted) {
            Grant grant = granted.computeIfAbsent(shot, id -> new Grant());
            if (grant.done) { duplicates++; return false; }
            if (falloff) {
                // A shotgun's whole blast is one event, as vanilla's latched isRangeFalloff() guard.
                grant.done = true;
                grants++;
                return true;
            }
            if (!grant.targets.add(target)) { duplicates++; return false; }
            grants++;
            return true;
        }
    }

    /**
     * One confirmed native hit of our owned shot on a character. Called from the shot's damage adapter only, on
     * the side that applies the hit ({@link ShotDamagePz}); a refused or thrown hit never reaches it.
     */
    public static void hit(ShotSnapshot shot, IsoGameCharacter owner, HandWeapon weapon, IsoGameCharacter target, float damage) {
        if (shot == null || owner == null || weapon == null || target == null || !Float.isFinite(damage)) { refused++; return; }
        // The vanilla Lua reads the weapon's script item (its favourite-weapon counter); a view without one is refused
        // here instead of raising an error inside the shot.
        if (weapon.getScriptItem() == null) { refused++; return; }
        if (!claim(GameClient.client, GameServer.server, weapon.isRanged(), weapon.isRangeFalloff(), shot.id(), target)) return;
        try {
            raise(owner, weapon, target, damage);
        } catch (RuntimeException error) {
            // An error in the XP handler must never disable the shot's damage: report it once, refuse the event.
            refused++;
            if (!logged) { logged=true;System.out.println("[TW-SHOT] XP event failed: "+error); }
        }
    }

    /** QA and the report: one line of the counters and the last decision. */
    public static String stats() {
        synchronized (granted) {
            return "xp_calls=" + calls + " xp_grants=" + grants + " xp_duplicates=" + duplicates
                    + " xp_refused=" + refused + " xp_shots=" + granted.size();
        }
    }

    /** QA only: starts a new counter window for one case. */
    public static void resetStats() {
        synchronized (granted) {
            calls = 0; grants = 0; refused = 0; duplicates = 0; granted.clear();
        }
    }

    /** QA only: true raises nothing, i.e. the numbers of the build before this fix (user report 27.10.2026). */
    public static void qaOff(boolean off) {
        qaOff = off;
        resetStats();
    }
}
