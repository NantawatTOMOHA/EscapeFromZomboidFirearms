package pz.escapefromzomboidfirearms.zb;

import java.lang.reflect.Field;
import me.zed_0xff.zombie_buddy.Exposer;
import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.BaseCharacterSoundEmitter;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoObject;
import zombie.network.GameServer;

/**
 * The local player's own weapon handling sounds stay audible while that player is invisible
 * (debug invisibility, cheat "ghost" modes that call setInvisible every tick).
 *
 * CharacterSoundEmitter.playSound returns 0 for an invisible character unless the debug option
 * Character.Debug.PlaySoundWhenInvisible is on, while PZ itself plays the player's own shot
 * through playSoundImpl (CombatManager), which mutes only remote invisible players. Every mod
 * event (TW_*) the emitter silenced that way - reload, top-off, rack, inspection, fire mode,
 * devices, whoever queued it (vanilla actions, Gunworks, this mod) - is played the same local
 * way. playSoundImpl sends no network packet, exactly as FMODSoundEmitter.playSound sends none
 * for an invisible player: other players still hear nothing.
 */
@Exposer.LuaClass(name="TWActionSoundNative")
public final class ActionSound {
    private static Field character;
    private static boolean failed;
    private static int restored, calls;
    private static String last = "";
    /** QA receipts: the last RECENT restored events "name:handle:playing". */
    private static final String[] recent = new String[32];
    private static int next;

    private ActionSound() { }

    public static long audible(Object emitter, String name, IsoObject source, long handle) {
        calls++;
        if (handle != 0L || failed || name == null || !name.startsWith("TW_") || GameServer.server) return handle;
        try {
            if (!(emitter instanceof BaseCharacterSoundEmitter e)) return handle;
            Field f = character;
            if (f == null) {
                f = BaseCharacterSoundEmitter.class.getDeclaredField("character");
                f.setAccessible(true);
                character = f;
            }
            if (!(f.get(e) instanceof IsoPlayer p) || !p.isLocalPlayer() || !p.isInvisible()) return handle;
            long played = e.playSoundImpl(name, source);
            if (played != 0L) {
                restored++;
                last = name;
                synchronized (recent) {
                    recent[next] = name + ":" + played + ":" + e.isPlaying(name);
                    next = (next + 1) % recent.length;
                }
            }
            return played;
        } catch (Throwable failure) {
            failed = true;
            System.out.println("[TW-SOUND] own sounds of an invisible player off: " + failure);
            return handle;
        }
    }

    /** QA receipt: emitter calls seen (the patch is applied), silenced own events played locally, the last one, a failure. */
    public static String info() { return "calls=" + calls + " restored=" + restored + " last=" + last + " failed=" + failed; }

    /** QA receipts: the restored events since the last call, "name:handle:playing" joined by spaces (then forgotten). */
    public static String recent() {
        StringBuilder out = new StringBuilder();
        synchronized (recent) {
            for (int i = 0; i < recent.length; i++) {
                String r = recent[(next + i) % recent.length];
                if (r != null) {
                    out.append(out.length() == 0 ? "" : " ").append(r);
                    recent[(next + i) % recent.length] = null;
                }
            }
        }
        return out.toString();
    }

    @Patch(className="zombie.characters.CharacterSoundEmitter", methodName="playSound", strictMatch=true)
    public static final class Play {
        @Patch.OnExit
        public static void exit(@Patch.This Object emitter, @Patch.Argument(0) String name,
                @Patch.Return(readOnly=false) long handle) {
            handle = ActionSound.audible(emitter, name, null, handle);
        }
    }

    @Patch(className="zombie.characters.CharacterSoundEmitter", methodName="playSound", strictMatch=true)
    public static final class PlayFlag {
        @Patch.OnExit
        public static void exit(@Patch.This Object emitter, @Patch.Argument(0) String name,
                @Patch.Argument(1) boolean flag, @Patch.Return(readOnly=false) long handle) {
            handle = ActionSound.audible(emitter, name, null, handle);
        }
    }

    @Patch(className="zombie.characters.CharacterSoundEmitter", methodName="playSound", strictMatch=true)
    public static final class PlayAt {
        @Patch.OnExit
        public static void exit(@Patch.This Object emitter, @Patch.Argument(0) String name,
                @Patch.Argument(1) IsoObject source, @Patch.Return(readOnly=false) long handle) {
            handle = ActionSound.audible(emitter, name, source, handle);
        }
    }
}
