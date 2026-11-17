package pz.escapefromzomboidfirearms.zb;

import java.util.concurrent.ConcurrentHashMap;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItem;

/**
 * The game's torch shines along the character's look vector
 * (IsoGameCharacter$TorchInfo.set, called by LightingJNI for every light item
 * of every player). For a Tarkov light on the weapon in hand it shines along
 * the bore instead: TW/LightRender sets the horizontal direction, reach and
 * strength of that weapon's light every frame, on every client, for every
 * player it draws; a stale value (no update for STALE_NS) or another item
 * leaves the game's own. The light stays at the character (the lens may be
 * past a wall the barrel touches). The native light has no vertical axis:
 * Lua shortens it for a bore pointing down and dims it for one pointing up.
 */
public final class TorchAim {
    static final long STALE_NS = 300_000_000L;

    static final class Aim {
        Object item;
        float dx, dy, dist, strength;
        long stamp;
    }

    static final ConcurrentHashMap<Object, Aim> aims = new ConcurrentHashMap<>();
    public static volatile long applied;
    private static long pruned;

    private TorchAim() {
    }

    static void set(IsoPlayer p, Object item, float dx, float dy, float dist, float strength) {
        Aim a = aims.computeIfAbsent(p, k -> new Aim());
        a.item = item;
        a.dx = dx;
        a.dy = dy;
        a.dist = dist;
        a.strength = strength;
        a.stamp = System.nanoTime();
        if (a.stamp - pruned > 50 * STALE_NS) {
            pruned = a.stamp;
            prune();
        }
    }

    static void clear(IsoPlayer p) {
        aims.remove(p);
    }

    /** Players no longer updated (left, light off, weapon put away) are forgotten. */
    static void prune() {
        long now = System.nanoTime();
        aims.values().removeIf(a -> now - a.stamp > 10 * STALE_NS);
    }

    public static void apply(Object info, Object owner, Object item) {
        if (!(owner instanceof IsoPlayer p) || !(info instanceof IsoGameCharacter.TorchInfo t)) {
            return;
        }
        Aim a = aims.get(p);
        if (a == null || a.item != item || System.nanoTime() - a.stamp > STALE_NS) {
            return;
        }
        float h = (float) Math.hypot(a.dx, a.dy);
        if (h < 1e-4f) {
            return;
        }
        t.angleX = a.dx / h;
        t.angleY = a.dy / h;
        if (a.dist > 0) {
            t.dist = Math.min(t.dist, a.dist);
        }
        if (a.strength > 0) {
            t.strength = Math.min(t.strength, a.strength);
        }
        applied++;
    }

    @Patch(className = "zombie.characters.IsoGameCharacter$TorchInfo", methodName = "set")
    public static class AfterSet {
        @Patch.OnExit
        public static void exit(@Patch.This Object self, @Patch.Argument(0) IsoPlayer owner, @Patch.Argument(1) InventoryItem item) {
            TorchAim.apply(self, owner, item);
        }
    }
}
