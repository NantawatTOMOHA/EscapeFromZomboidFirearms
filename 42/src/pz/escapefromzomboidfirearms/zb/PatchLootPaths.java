package pz.escapefromzomboidfirearms.zb;

import me.zed_0xff.zombie_buddy.Patch;
import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.integration.LuaReturn;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import zombie.Lua.LuaManager;
import zombie.characters.IsoGameCharacter;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.InventoryContainer;

/**
 * Container fills of the game that never fire OnFillContainer (W11 loot, part B). The engine sends
 * that event from ItemPickerJava.fillContainerInternal only; the map stashes (StashSystem), the
 * story buildings (RBBasic, RBSafehouse, RBSWATStation, RDSSpecificProfession, RDSCorpsePsycho,
 * RBGunstoreSiege, RBHeatBreakAfternoon, RBPoliceSiege, RandomizedBuildingBase) and the vehicle and
 * zone stories (RVSDeadEnd, RVSRichJerk, RZSRangerSmith, RZSSurvivalistCamp, RZSTragicPicnic) fill a
 * container through fillContainerType, rollItem or rollContainerItem and never send it, so the
 * representatives of the loot tickets stayed in them (a barricaded survivor house gave only AK-104,
 * VPO-215, PM and TOZ-106).
 *
 * The Lua world replacer sweeps the containers it passes anyway (TW_LootWorld + TWLootFill.tickets);
 * this advice sweeps the rest at the moment the engine returns - a story building or a stash placed
 * after the replacer went through the chunk. The sweep is the mod's own: TWLootPaths.fill in
 * TW_LootFill.lua (server only, a container without a representative left costs one scan of its
 * items), and OnFillContainer is never triggered here - the other mods' handlers would run twice.
 * tools/check_loot_paths.py keeps this patch and the game's list of bypass callers together.
 */
public final class PatchLootPaths {
    private static boolean qaOff;
    private static boolean failing;

    private PatchLootPaths() { }

    /** QA (W11 loot, before and after in one run): true is the build without this sweep. */
    public static void qaOff(boolean off) { qaOff = off; }

    /**
     * One container the engine has just filled without the event. Does nothing unless this mod's Lua
     * runs (ModLua), on a client (no authority), or after the QA switch. The call into Lua never
     * leaves an exception to the engine's fill.
     */
    public static void sweep(ItemContainer container) {
        if (!ModLua.present()) return;
        if (qaOff || container == null) return;
        try {
            KahluaTable env = LuaManager.env;
            KahluaThread thread = LuaManager.thread;
            LuaCaller caller = LuaManager.caller;
            if (env == null || thread == null || caller == null) return;
            if (!(env.rawget("TWLootPaths") instanceof KahluaTable api)) return;
            Object fill = api.rawget("fill");
            if (fill == null) return;
            LuaReturn result = caller.protectedCall(thread, fill, container);
            if (!result.isSuccess() && !failing) {
                failing = true;
                System.out.println("[TWZB] loot path sweep failed: " + result.getErrorString());
            }
        } catch (Throwable t) {
            if (!failing) {
                failing = true;
                System.out.println("[TWZB] loot path sweep failed: " + t);
            }
        }
    }

    /** A room container the engine filled from a room's own tables (story buildings, vehicles). */
    @Patch(className = "zombie.inventory.ItemPickerJava", methodName = "fillContainerType", strictMatch = true)
    public static class FillContainerType {
        @Patch.OnExit
        public static void exit(@Patch.Argument(1) ItemContainer container) {
            if (!ModLua.present()) return;
            sweep(container);
        }
    }

    /** A loot list rolled straight into a container (RBBasic, RDSSpecificProfession, map stashes). */
    @Patch(className = "zombie.inventory.ItemPickerJava", methodName = "rollItem", strictMatch = true)
    public static class RollItem {
        @Patch.OnExit
        public static void exit(@Patch.Argument(1) ItemContainer container) {
            if (!ModLua.present()) return;
            sweep(container);
        }
    }

    /** A bag inside a container rolled on its own (StashSystem, the story buildings). */
    @Patch(className = "zombie.inventory.ItemPickerJava", methodName = "rollContainerItem", strictMatch = true)
    public static class RollContainerItem {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) InventoryContainer container, @Patch.Argument(1) IsoGameCharacter chr) {
            if (!ModLua.present()) return;
            if (container != null) sweep(container.getInventory());
        }
    }
}
