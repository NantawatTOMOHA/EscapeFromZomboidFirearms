package pz.escapefromzomboidfirearms.zb;

import java.util.List;
import java.util.Objects;
import java.util.WeakHashMap;

import se.krka.kahlua.vm.KahluaTable;
import zombie.inventory.types.HandWeapon;
import zombie.inventory.types.WeaponPart;

/**
 * The version of a weapon's installed parts for TW/Runtime's snapshot cache (performance pass 23.10.2026): R.read rebuilt
 * the assembly from the part list (5 reflective calls per part, a dozen times per frame). Its inputs are the part objects in
 * list order, their IDs, their mod data tables and each table's TWPath; the number returned changes exactly when one of
 * them does (compared, not hashed), so a cached read with the same number is the read Lua would make.
 */
public final class PartsVersion {
    private static final WeakHashMap<HandWeapon, State> states = new WeakHashMap<>();
    private static long next;

    private static final class State {
        WeaponPart[] parts;
        Object[] data, paths;
        int[] ids;
        long version;
    }

    private PartsVersion() { }

    /** Main thread (Lua). */
    public static synchronized double of(HandWeapon weapon) {
        if (weapon == null) return 0;
        List<WeaponPart> list = weapon.getAllWeaponParts();
        int n = list.size();
        State s = states.get(weapon);
        if (s != null && s.parts.length == n) {
            boolean same = true;
            for (int i = 0; same && i < n; i++) {
                WeaponPart part = list.get(i);
                KahluaTable md = part.getModData();
                same = part == s.parts[i] && md == s.data[i] && part.getID() == s.ids[i] && Objects.equals(md.rawget("TWPath"), s.paths[i]);
            }
            if (same) return s.version;
        }
        if (s == null) { s = new State(); states.put(weapon, s); }
        s.parts = new WeaponPart[n]; s.data = new Object[n]; s.paths = new Object[n]; s.ids = new int[n];
        for (int i = 0; i < n; i++) {
            WeaponPart part = list.get(i);
            KahluaTable md = part.getModData();
            s.parts[i] = part; s.data[i] = md; s.ids[i] = part.getID(); s.paths[i] = md.rawget("TWPath");
        }
        s.version = ++next;
        return s.version;
    }
}
