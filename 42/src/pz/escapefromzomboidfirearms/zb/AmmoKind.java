package pz.escapefromzomboidfirearms.zb;

import java.util.Optional;
import se.krka.kahlua.vm.KahluaTable;
import zombie.inventory.InventoryItem;
import zombie.scripting.objects.AmmoType;

/** The round kind an item carries in its saved mod data.

 * B42.21 saves an item's mod data but not InventoryItem.ammoType (neither
 * InventoryItem.save/load nor the field sync packets carry it), so every item that comes
 * back from a save, from a server or from the world starts with the script's plain round of
 * its calibre. Gunworks keeps one round item type per loaded round in modData.AmmoList; the
 * mod's own Lua layer keeps the kind of an emptied magazine in modData.TWAmmoKind. This
 * class reads that kind and puts the AmmoType back on the item right after it was loaded
 * (PatchAmmoKind). The round's stats for a weapon stay the Lua layer's business: they must
 * go through Gunworks' Ammo.AmmoAdjustWeaponStats, which a bare setAmmoType cannot do.
 * Only this mod's items are touched: without the mod's scripts and Lua no such item exists,
 * so the advice is inert in a session without it (check_patch_inert: content).
 * Public members only: the advice is inlined into zombie.inventory.InventoryItem. */
public final class AmmoKind {
    /** modData key of the kind an emptied magazine keeps (the Lua module writes it). */
    public static final String MARK = "TWAmmoKind";
    private static final String LIST = "AmmoList";
    /** The item namespace of this mod's weapons, magazines and rounds. */
    private static final String OWN = "EscapeFromZomboidFirearms.";

    private AmmoKind() {
    }

    /** The last round of a Gunworks list (the one that fires next), else the kept mark. */
    public static String kind(InventoryItem item) {
        if (item == null || !item.hasModData()) return null;
        return kind(item.getModData());
    }

    /** Same as {@link #kind(InventoryItem)} for an already fetched mod data table. */
    public static String kind(KahluaTable modData) {
        if (modData == null) return null;
        String last = lastRound(modData.rawget(LIST));
        if (last != null) return last;
        Object mark = modData.rawget(MARK);
        return mark instanceof String text && !text.isEmpty() ? text : null;
    }

    private static String lastRound(Object list) {
        if (!(list instanceof KahluaTable table)) return null;
        int length = table.len();
        String last = null;
        for (int i = 1; i <= length; i++) {
            Object value = table.rawget(i);
            if (value instanceof String text && !text.isEmpty()) last = text;
        }
        return last;
    }

    /** Put the saved kind back on a freshly loaded item; false when it already carries it. */
    public static boolean restore(InventoryItem item) {
        if (item == null || !item.getFullType().startsWith(OWN)) return false;
        String kind = kind(item);
        if (kind == null) return false;
        Optional<AmmoType> type;
        try {
            type = AmmoType.getByItemKey(kind);
        } catch (RuntimeException ignored) {
            return false;
        }
        if (type == null || type.isEmpty()) return false;
        AmmoType ammoType = type.get();
        if (item.getAmmoType() == ammoType) return false;
        item.setAmmoType(ammoType);
        return true;
    }
}
