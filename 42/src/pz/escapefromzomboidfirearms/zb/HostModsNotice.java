package pz.escapefromzomboidfirearms.zb;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import me.zed_0xff.zombie_buddy.Patch;
import org.json.JSONObject;
import se.krka.kahlua.integration.LuaReturn;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaClosure;
import zombie.Lua.LuaManager;
import zombie.core.Translator;
import zombie.debug.DebugType;
import zombie.gameStates.ChooseGameInfo;
import zombie.network.GameClient;

/**
 * A joined server without this mod while its Java part runs (W10 task host). The report: a server started with Host
 * from the main menu with an empty WorkshopItems found none of the Workshop mods, the client reloaded its Lua with no
 * mods ("loadMods(0 mods)") and only vanilla weapons were there, while the Java parts loaded at the game start
 * (Viewpoint, this mod) made it look as if the mods ran. When the game starts as a client and ModLua.present() is
 * false: one WARN line in console.txt and a window with the server lists (the "Steam Workshop" page of Host, the ini
 * of a dedicated server).
 *
 * This mod's Lua is not loaded then: the lists are read from TW/Data/Hosting.lua (tools/host_workshop.py, from the
 * Workshop page), the texts from the mod's own Translate files (the game's translations lack them), and the window is
 * TWJavaNotice.showServerMods of TW_JavaNotice.lua, run here when the Lua state lacks it. Single player and a server
 * that has the mod: nothing.
 */
public final class HostModsNotice {
    static final String DATA = "media/lua/shared/TW/Data/Hosting.lua";
    static final String NOTICE = "media/lua/client/TW_JavaNotice.lua";
    static final String TRANSLATE = "media/lua/shared/Translate/";
    public static volatile int shown;
    public static volatile String last = "";

    private HostModsNotice() {}

    @Patch(className = "zombie.gameStates.IngameState", methodName = "enter")
    public static class Enter {
        @Patch.OnExit
        public static void exit() {
            HostModsNotice.gameStarted();
        }
    }

    /** IngameState.enter has run (OnGameStart included). */
    public static void gameStarted() {
        if (!GameClient.client || ModLua.present()) return;
        try {
            notice();
        } catch (Throwable t) {
            System.out.println("[TWZB] server without the mod: notice failed: " + t);
        }
    }

    static void notice() throws Exception {
        Path root = modRoot();
        KahluaTable data = root == null ? null : (KahluaTable) run(root.resolve(DATA), "Hosting.lua");
        String items = data == null ? "" : join(data.rawget("workshopItems"));
        String mods = data == null ? "" : join(data.rawget("mods"));
        String mod = data == null ? ModLua.MOD : String.valueOf(data.rawget("mod"));
        last = "Steam Workshop=" + items + " Mods=" + mods;
        DebugType.Mod.warn("[TW] Escape From Zomboid Firearms: its Java part is loaded but this server's mod list has no "
                + mod + ", so the mod is off in this game. A Host server needs, page \"Steam Workshop\": " + items
                + "; page \"Mods\": " + mods + " (a dedicated server: WorkshopItems=" + items + ", Mods=" + mods + ").");
        if (root == null) return;
        JSONObject en = texts(root, "EN");
        JSONObject own = texts(root, Translator.getLanguage() == null ? "EN" : Translator.getLanguage().name());
        KahluaTable t = LuaManager.platform.newTable();
        t.rawset("title", text(own, en, "UI_TW_ServerModsTitle"));
        t.rawset("cause", text(own, en, "UI_TW_ServerModsText").replace("%1", mod));
        t.rawset("host", text(own, en, "UI_TW_ServerModsHost").replace("%1", items).replace("%2", mods));
        t.rawset("dedicated", text(own, en, "UI_TW_ServerModsDedicated").replace("%1", items).replace("%2", mods));
        t.rawset("hint", text(own, en, "UI_TW_WarningsHint"));
        Object notice = LuaManager.env.rawget("TWJavaNotice");
        if (!(notice instanceof KahluaTable)) notice = run(root.resolve(NOTICE), "TW_JavaNotice.lua");
        Object show = notice instanceof KahluaTable n ? n.rawget("showServerMods") : null;
        if (show == null) throw new IllegalStateException("TWJavaNotice.showServerMods missing");
        LuaReturn r = LuaManager.caller.protectedCall(LuaManager.thread, show, new Object[] {t});
        if (!r.isSuccess()) throw new IllegalStateException(r.getErrorString());
        shown++;
    }

    /** The mod's version folder (42): the folder of this JAR (media/java/client), else the game's record of the mod. */
    static Path modRoot() {
        try {
            Path jar = Path.of(HostModsNotice.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path root = jar.getParent().getParent().getParent().getParent();
            if (Files.isRegularFile(root.resolve(DATA))) return root;
        } catch (Throwable ignored) {
        }
        ChooseGameInfo.Mod mod = ChooseGameInfo.getAvailableModDetails(ModLua.MOD);
        if (mod != null) {
            for (String dir : new String[] {mod.getVersionDir(), mod.getCommonDir(), mod.getDir()}) {
                if (dir != null && !dir.isBlank() && Files.isRegularFile(Path.of(dir).resolve(DATA))) return Path.of(dir);
            }
        }
        return null;
    }

    /** Runs a Lua file of this mod in the current Lua state; its return value. */
    static Object run(Path file, String name) throws Exception {
        LuaClosure closure;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            closure = LuaCompiler.loadis(reader, name, LuaManager.env);
        }
        LuaReturn r = LuaManager.caller.protectedCall(LuaManager.thread, closure, new Object[0]);
        if (!r.isSuccess()) throw new IllegalStateException(name + ": " + r.getErrorString());
        return r.getFirst();
    }

    static JSONObject texts(Path root, String language) {
        try {
            return new JSONObject(Files.readString(root.resolve(TRANSLATE + language + "/UI.json"), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return null;
        }
    }

    static String text(JSONObject own, JSONObject en, String key) {
        if (own != null && own.has(key)) return own.getString(key);
        if (en != null && en.has(key)) return en.getString(key);
        return key;
    }

    static String join(Object list) {
        if (!(list instanceof KahluaTable t)) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 1; i <= t.len(); i++) {
            if (out.length() > 0) out.append(';');
            out.append(t.rawget(i));
        }
        return out.toString();
    }
}
