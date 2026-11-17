package pz.escapefromzomboidfirearms.zb;

import me.zed_0xff.zombie_buddy.Exposer;
import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItem;

/** Native input admission during a source-clock utility; no shot/ammo side effects. */
@Exposer.LuaClass(name="TWWeaponUtilityNative")
public final class WeaponUtilityGate {
    public static long blocked;

    private WeaponUtilityGate() { }

    public static boolean active(IsoGameCharacter c) {
        if (!(c instanceof IsoPlayer p) || !p.isLocalPlayer() || p.isDead()) return false;
        InventoryItem item = p.getPrimaryHandItem();
        if (item == null || !item.getFullType().startsWith("EscapeFromZomboidFirearms.")) return false;
        String action = p.getVariableString("TWWeaponUtility");
        return action != null && !action.isEmpty();
    }

    public static boolean reject(Object value) {
        if (!(value instanceof IsoGameCharacter c) || !active(c)) return false;
        blocked++;
        return true;
    }

    public static double blockedCalls() { return blocked; }

    @Patch(className="zombie.CombatManager",methodName="pressedAttack",strictMatch=true)
    public static class Press {
        @Patch.OnEnter(skipOn=true)
        public static boolean enter(@Patch.Argument(0) IsoPlayer player) { return WeaponUtilityGate.reject(player); }
    }

    @Patch(className="zombie.characters.IsoLivingCharacter",methodName="AttemptAttack",strictMatch=true)
    public static class Attempt {
        @Patch.OnEnter(skipOn=true)
        public static boolean enter(@Patch.This Object player) { return WeaponUtilityGate.reject(player); }
    }

    @Patch(className="zombie.characters.IsoPlayer",methodName="AttemptAttack",strictMatch=true)
    public static class PlayerAttempt {
        @Patch.OnEnter(skipOn=true)
        public static boolean enter(@Patch.This Object player) { return WeaponUtilityGate.reject(player); }
    }
}
