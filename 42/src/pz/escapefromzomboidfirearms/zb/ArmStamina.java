package pz.escapefromzomboidfirearms.zb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.WeakHashMap;
import me.zed_0xff.zombie_buddy.Exposer;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.gameStates.ChooseGameInfo;

/** Local HUD adapter to the existing Tarkov hands state. No PZ endurance writes. */
@Exposer.LuaClass
public final class ArmStamina {
    /** Caps retained per-player cache arrays against pathological Lua tables; >256 falls back uncached without truncation. */
    private static final int MAX_CACHED_PARTS = 256;
    private static final String[] EMPTY = new String[0];
    private static TarkovHandlingData data;
    private static TarkovHandling core;
    private static final WeakHashMap<IsoPlayer, Local> locals = new WeakHashMap<>();
    private static final class Local {
        long frame = Long.MIN_VALUE, steps;
        TarkovHandling.Input input;
        String owner = "idle";
        TarkovHandlingData assemblyData;
        String weaponKey;
        String[] partPaths = EMPTY, partTpls = EMPTY;
        int partCount;
        String ammoTpl = "";
        int ammoCount;
        TarkovHandlingData.Assembly assembly;
    }
    private ArmStamina() { }
    private static boolean load() throws Exception {
        if (data != null) return true;
        var mod = ChooseGameInfo.getModDetails("EscapeFromZomboidFirearms");
        if (mod == null) return false;
        for (String dir : new String[] {mod.getVersionDir(), mod.getDir()}) {
            if (dir == null) continue;
            Path path = Path.of(dir).resolve(TarkovHandlingData.RESOURCE);
            if (Files.isRegularFile(path)) {
                data = TarkovHandlingData.load(path); core = new TarkovHandling(data); return true;
            }
        }
        return false;
    }
    /** Called by the FP rest guard, including before the first HUD update. */
    public static boolean ownsStep(IsoGameCharacter c) {
        return c instanceof IsoPlayer p && p.isLocalPlayer() && !p.isDead()
                && !FpRecoil.armStaminaFpOwner(c);
    }
    private static boolean partsMatch(Object rawParts, Local local) {
        int idx = 0;
        if (rawParts instanceof KahluaTable table) {
            for (int i = 1; table.rawget(i) instanceof KahluaTable part; i++) {
                String tpl = String.valueOf(part.rawget("tpl"));
                if (!data.items.containsKey(tpl)) continue;
                if (idx >= local.partCount
                        || !tpl.equals(local.partTpls[idx])
                        || !String.valueOf(part.rawget("path")).equals(local.partPaths[idx])) {
                    return false;
                }
                idx++;
            }
        }
        return idx == local.partCount;
    }
    private static TarkovHandlingData.Assembly assembly(Local local, KahluaTable row) {
        String key = String.valueOf(row.rawget("key"));
        if (!data.weapons.containsKey(key)) return null;
        Object rawParts = row.rawget("parts");
        String rawAmmo = String.valueOf(row.rawget("ammo"));
        int rawRounds = row.rawget("rounds") instanceof Number n ? Math.max(0, n.intValue()) : 0;
        boolean hasAmmo = rawRounds > 0 && data.items.containsKey(rawAmmo);
        String effAmmo = hasAmmo ? rawAmmo : "";
        int effRounds = hasAmmo ? rawRounds : 0;
        if (local.assembly != null && local.assemblyData == data && key.equals(local.weaponKey)
                && effRounds == local.ammoCount && effAmmo.equals(local.ammoTpl)
                && partsMatch(rawParts, local)) {
            return local.assembly;
        }
        var parts = new ArrayList<TarkovHandlingData.Installed>();
        if (rawParts instanceof KahluaTable table) {
            for (int i = 1; table.rawget(i) instanceof KahluaTable part; i++) {
                String tpl = String.valueOf(part.rawget("tpl"));
                if (data.items.containsKey(tpl)) parts.add(new TarkovHandlingData.Installed(
                        String.valueOf(part.rawget("path")), tpl, 1));
            }
        }
        int partCount = parts.size();
        if (hasAmmo) parts.add(new TarkovHandlingData.Installed("TW:cartridges", effAmmo, effRounds));
        var built = data.assemble(key, parts);
        if (partCount <= MAX_CACHED_PARTS) {
            if (local.partPaths.length < partCount) {
                local.partPaths = new String[partCount];
                local.partTpls = new String[partCount];
            } else if (local.partCount > partCount) {
                Arrays.fill(local.partPaths, partCount, local.partCount, null);
                Arrays.fill(local.partTpls, partCount, local.partCount, null);
            }
            for (int i = 0; i < partCount; i++) {
                var entry = parts.get(i);
                local.partPaths[i] = entry.path();
                local.partTpls[i] = entry.tpl();
            }
            local.assemblyData = data;
            local.weaponKey = key;
            local.partCount = partCount;
            local.ammoTpl = effAmmo;
            local.ammoCount = effRounds;
            local.assembly = built;
        }
        return built;
    }
    private static TarkovHandling.Input input(IsoPlayer p, Local local, KahluaTable row) {
        TarkovHandlingData.Assembly assembly = assembly(local, row);
        var pose = p.isProne() ? TarkovHandling.Pose.PRONE : p.isSneaking()
                ? TarkovHandling.Pose.CROUCH : TarkovHandling.Pose.STAND;
        return new TarkovHandling.Input(assembly, pose, false,
                assembly != null && Boolean.TRUE.equals(row.rawget("aiming")), false,
                TarkovHandling.Support.NONE, TarkovInjuryPz.read(p, !Boolean.FALSE.equals(row.rawget("injury"))).handling());
    }
    public static KahluaTable update(IsoPlayer p, KahluaTable row, double seconds) {
        if (p == null || !p.isLocalPlayer() || p.isDead() || row == null
                || !Double.isFinite(seconds) || seconds < 0 || seconds > 1) return null;
        try {
            if (!load()) return null;
            Local local = locals.computeIfAbsent(p, k -> new Local());
            // Reuse the same State when entering/leaving FP or changing weapons.
            var fp = FpRecoil.players.computeIfAbsent(p, k -> new FpRecoil.Player(core));
            local.input = input(p, local, row);
            long frame = FpRecoil.worldFrame();
            if (local.frame != frame) {
                local.frame = frame;
                local.owner = FpRecoil.armStaminaFpOwner(p) ? "fp" : "hud";
                if (ownsStep(p) && !FpRecoil.armStaminaPosed(p) && !Boolean.TRUE.equals(row.rawget("paused"))) {
                    fp.timeline.hands.advance(seconds, local.input); local.steps++;
                }
            }
            return snapshot(p);
        } catch (Exception | LinkageError error) {
            System.out.println("[TW-ARM-STAMINA] unavailable: " + error); return null;
        }
    }
    /** Zero-time read: drawing the HUD can never advance physical state. */
    public static KahluaTable snapshot(IsoPlayer p) {
        if (p == null || !p.isLocalPlayer() || p.isDead()) return null;
        Local local = locals.get(p);
        var fp = FpRecoil.players.get(p);
        if (local == null || local.input == null || fp == null) return null;
        var s = fp.timeline.hands.advance(0, local.input);
        KahluaTable out = LuaManager.platform.newTable();
        out.rawset("hands", s.hands()); out.rawset("capacity", s.handsCapacity());
        out.rawset("fraction", s.hands() / s.handsCapacity()); out.rawset("exhausted", s.handsExhausted());
        out.rawset("drain", s.handsDrainPerSecond()); out.rawset("restore", s.handsRestorePerSecond());
        out.rawset("time", s.time()); out.rawset("pending", s.pendingSeconds());
        out.rawset("frame", (double)local.frame); out.rawset("steps", (double)local.steps); out.rawset("owner", local.owner);
        return out;
    }
    public static void reset() {
        for (var player:locals.keySet()) FpRecoil.players.remove(player);
        locals.clear();
    }
    public static void off(IsoPlayer p) {
        if (locals.remove(p)!=null) FpRecoil.players.remove(p);
    }
}
