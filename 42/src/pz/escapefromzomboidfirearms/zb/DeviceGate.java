package pz.escapefromzomboidfirearms.zb;

import java.util.Stack;

import me.zed_0xff.zombie_buddy.Exposer;
import zombie.characters.CharacterTimedActions.BaseAction;
import zombie.characters.IsoGameCharacter;
import zombie.inventory.InventoryItem;

/**
 * Tactical devices shine from the weapon itself. An action that draws another model or none in
 * the primary hand - a magazine top-off or unloading, eating with the gun equipped - hides the
 * weapon and with it the lights and lasers on it. The device frame (WeaponFrame) is whatever
 * hangs on the hand bone: during the mod's top-off display that is a cartridge re-bound from
 * one hand to the other, and the beams jumped with it (user report 19.10.2026, item 14).
 * Same rule as ModelManager.DoCharacterModelEquipped: the first queued action, when it
 * overrides the hand models, puts its own primary item (or model name) on Bip01_Prop1.
 */
@Exposer.LuaClass(name="TWDeviceGate")
public final class DeviceGate {
    private DeviceGate() { }

    /** True when the character's primary hand item is what its primary hand draws. */
    public static boolean weaponDrawn(IsoGameCharacter c) {
        InventoryItem gun = c == null ? null : c.getPrimaryHandItem();
        if (gun == null) return false;
        Stack<BaseAction> actions = c.getCharacterActions();
        BaseAction a = actions == null || actions.isEmpty() ? null : actions.get(0);
        return a == null || !a.overrideHandModels || a.getPrimaryHandItem() == gun;
    }
}
