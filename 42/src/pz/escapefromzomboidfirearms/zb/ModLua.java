package pz.escapefromzomboidfirearms.zb;

import java.util.List;

import zombie.ZomboidFileSystem;
import zombie.Lua.LuaManager;

/**
 * Whether this mod's Lua runs in the current Lua state (W10 task host). ZombieBuddy loads the JAR once, at the game
 * start, with the main menu's mods; a server whose mod list lacks the mod reloads the Lua without it (the player's
 * report: a Host server with an empty WorkshopItems, "loadMods(0 mods)"), and the JAR stays. The patches that would
 * still do work there (frame hooks with no request of the Lua behind them) return at once when this is false;
 * tools/check_patch_inert.py keeps every patch of the package classified.
 *
 * The answer is the mod list of ZomboidFileSystem: the mods the current Lua state loaded (loadModAndRequired adds only
 * the mods it found; B42 writes "\ModID" as well). A few string compares; the callers run once a frame or rarer.
 */
public final class ModLua {
    public static final String MOD = "EscapeFromZomboidFirearms";

    private ModLua() {}

    /**
     * True while this mod is in the mod list of the current Lua state; inlined into advice, so public. A list being
     * rewritten under a reader on another thread (a Lua reload) counts as present: the patches then behave as before.
     */
    public static boolean present() {
        try {
            ZomboidFileSystem fs = ZomboidFileSystem.instance;
            return LuaManager.env != null && fs != null && listed(fs.getModIDs());
        } catch (RuntimeException e) {
            return true;
        }
    }

    static boolean listed(List<String> ids) {
        if (ids == null) return false;
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            if (id != null && MOD.equals(strip(id))) return true;
        }
        return false;
    }

    static String strip(String id) {
        int i = 0;
        while (i < id.length() && id.charAt(i) == '\\') i++;
        return id.substring(i).trim();
    }
}
