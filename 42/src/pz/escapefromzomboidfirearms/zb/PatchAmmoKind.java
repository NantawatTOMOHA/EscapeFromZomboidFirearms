package pz.escapefromzomboidfirearms.zb;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.inventory.InventoryItem;

/** Only PZ is patched. InventoryItem.load is where a saved item gets its fields back; its
 * ammoType is not among them in B42.21, so the saved round kind is put back here for every
 * item the game loads - the player's inventory, bags, world containers, vehicles, corpses
 * and the items a client receives from a server. A weapon's round stats are applied by the
 * Lua layer after this (TW/AmmoKind). */
public final class PatchAmmoKind {
    @Patch(className = "zombie.inventory.InventoryItem", methodName = "load", strictMatch = true)
    public static class Load {
        @Patch.OnExit
        public static void exit(@Patch.This Object self) {
            if (self instanceof InventoryItem item) AmmoKind.restore(item);
        }
    }
}
