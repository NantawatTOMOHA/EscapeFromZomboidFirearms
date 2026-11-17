package pz.escapefromzomboidfirearms.zb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import zombie.gameStates.ChooseGameInfo;

/**
 * Muzzle velocity of an assembly for the Tarkov weapon screens' characteristics (TW/TarkovUI/Stats):
 * the cartridge's initial speed from the ballistics resource times the assembly's speed factor,
 * exactly as the first-person shot computes v0 (TarkovBallisticsData.assemblySpeedFactor). The
 * resource is read once on its own here, so the screens never touch the shot owner's state.
 */
final class UiBallistics {
    private UiBallistics() {
    }

    private static TarkovBallisticsData data;
    private static boolean failed;

    static synchronized TarkovBallisticsData data() {
        if (data != null || failed) return data;
        try {
            var mod = ChooseGameInfo.getModDetails("EscapeFromZomboidFirearms");
            if (mod != null) {
                for (String root : new String[] {mod.getVersionDir(), mod.getCommonDir(), mod.getDir()}) {
                    if (root == null || root.isBlank()) continue;
                    Path file = Path.of(root).resolve(TarkovBallisticsData.RESOURCE);
                    if (Files.isRegularFile(file)) {
                        data = TarkovBallisticsData.load(file);
                        return data;
                    }
                }
            }
            failed = true;
            System.out.println("[TWZB] Weapon screens: ballistics resource unavailable, no muzzle velocity row");
        } catch (Throwable t) {
            failed = true;
            System.out.println("[TWZB] Weapon screens: ballistics resource unreadable: " + t);
        }
        return data;
    }

    /** m/s; 0 when the weapon, the cartridge or a part is unknown to the resource. */
    static double muzzleVelocity(String weaponKey, String ammoTpl, List<String> parts) {
        TarkovBallisticsData d = data();
        if (d == null || weaponKey == null) return 0;
        var weapon = d.weapons.get(weaponKey);
        if (weapon == null) return 0;
        String tpl = ammoTpl != null && d.ammo.containsKey(ammoTpl) ? ammoTpl : weapon.defaultAmmoTpl();
        var ammo = d.ammo.get(tpl);
        if (ammo == null) return 0;
        try {
            return ammo.initialSpeed() * d.assemblySpeedFactor(weaponKey, parts);
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }
}
