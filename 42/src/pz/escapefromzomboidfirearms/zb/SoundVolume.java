package pz.escapefromzomboidfirearms.zb;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

import me.zed_0xff.zombie_buddy.Exposer;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.GameSounds;
import zombie.audio.GameSound;
import zombie.audio.GameSoundClip;

/**
 * Per-client volume of the mod's own sounds (W10, the player's item 20: an "Audio" page with sliders for
 * gunshots, reloading, the bolt, loading magazines and so on).
 *
 * <p>The engine has one per-sound multiplier that every playback path reads each tick:
 * {@code FMODSoundEmitter$Sound.getVolume()} = the instance volume times
 * {@code GameSoundClip.getEffectiveVolume()} = the clip volume (the scripts' {@code volume}, 1 by default)
 * times {@code GameSound.getUserVolume()}. The last one looks like the right knob but the engine gates it:
 * {@code GameSound.getUserVolume()} returns 1.0 unless {@code SystemDisabler.getEnableAdvancedSoundOptions()}
 * is set, and nothing in the game sets that debug flag - so {@code setUserVolume} changes nothing a player
 * hears. The clip volume is read unconditionally, so the sliders scale it here.
 *
 * <p>The base (the volume the sound scripts give the clip, i.e. what 100 % must be) is remembered per clip
 * object, so applying a slider again is idempotent and a sound script reload (a new clip object, see
 * {@code GameSoundClip.checkReloaded()}) starts from its own script volume again. Only {@code TW_} sounds are
 * accepted: vanilla sounds, music and the world sounds that attract zombies are never touched here.
 *
 * <p>Lua sees it as TWSoundVolume (TW_Audio.lua):
 * <pre>
 *   TWSoundVolume.set(name, scale)   - scale one sound, 1 = the script volume; returns the clips scaled
 *   TWSoundVolume.applyAll(rules, scales, prefixes) - the same pass over every loaded mod sound
 *   TWSoundVolume.volumeOf(name)     - the volume the emitter multiplies for that sound now (clip x user)
 *   TWSoundVolume.baseOf(name)       - the script volume of that sound's first clip (the 100 % value)
 *   TWSoundVolume.info()             - counters and the last failure, so a silent module is visible
 * </pre>
 */
@Exposer.LuaClass(name = "TWSoundVolume")
public final class SoundVolume {
    /** The highest slider value: the Audio page ends at 200 percent. */
    private static final float MAX = 2.0f;
    private static final Map<GameSoundClip, Float> base = new IdentityHashMap<>();
    private static int calls, failures, scaled;
    private static String last = "";

    private SoundVolume() { }

    /** GameSound of a name, or null (an unknown name, or the scripts are not loaded yet). */
    private static GameSound sound(String name) {
        if (name == null || !name.startsWith("TW_")) {
            return null;
        }
        return GameSounds.getSound(name);
    }

    /** Scales one sound of the mod: 0 = silent, 1 = the script volume, 2 = twice as loud. Returns the clips. */
    public static int set(String name, double value) {
        calls++;
        GameSound sound = sound(name);
        if (sound == null || sound.clips == null) {
            return 0;
        }
        float scale = (float) Math.max(0.0, Math.min(MAX, value));
        int done = 0;
        try {
            for (GameSoundClip clip : sound.clips) {
                if (clip == null) {
                    continue;
                }
                Float kept = base.get(clip);
                if (kept == null) {
                    kept = clip.volume;
                    base.put(clip, kept);
                }
                clip.volume = kept * scale;
                done++;
            }
            scaled += done;
        } catch (Throwable failure) {
            failures++;
            last = "set: " + failure;
            System.out.println("[TW-AUDIO] sound volume off: " + failure);
        }
        return done;
    }

    private record Rule(String prefix, String category) { }
    private record Row(String name, String category) { }

    private static KahluaTable table(KahluaTable owner, String key) {
        return owner.rawget(key) instanceof KahluaTable value ? value : null;
    }

    private static String category(KahluaTable rules, ArrayList<Rule> prefixes, String name) {
        int underscore = name.lastIndexOf('_');
        String event = underscore >= 0 && underscore + 1 < name.length() ? name.substring(underscore + 1) : null;
        for (Rule rule : prefixes) {
            if (name.startsWith(rule.prefix)) {
                if (rule.prefix.equals("TW_HG_") && event != null) {
                    Object handling = table(rules, "grenadeHandling").rawget(event);
                    if (handling instanceof String value) return value;
                }
                return rule.category;
            }
        }
        Object exact = table(rules, "exact").rawget(name);
        if (exact instanceof String value) return value;
        if (event == null) return null;
        Object value = table(rules, name.startsWith("TW_Utility_") ? "utility" : "suffix").rawget(event);
        return value instanceof String text ? text : null;
    }

    /**
     * The same discovery and per-sound set as TW_Audio, with one Lua/Java crossing per refresh.
     * Rules come from TW/Data/Audio, including their prefix iteration order; no category catalogue is duplicated.
     * Discover first, then scale, as the Lua inventory/set passes do. Nothing is cached across refreshes:
     * new sounds and replacement clips are found on the same 500 ms / 30 s schedule as before.
     */
    public static KahluaTable applyAll(KahluaTable rules, KahluaTable scales, KahluaTable orderedPrefixes) {
        ArrayList<Rule> prefixes = new ArrayList<>();
        for (int i = 1; orderedPrefixes.rawget(i) instanceof KahluaTable row; i++) {
            if (row.rawget(1) instanceof String prefix && row.rawget(2) instanceof String category) {
                prefixes.add(new Rule(prefix, category));
            }
        }
        ArrayList<Row> inventory = new ArrayList<>();
        KahluaTable unknown = LuaManager.platform.newTable();
        int unknownCount = 0;
        var categories = GameSounds.getCategories();
        for (int i = 0; i < categories.size(); i++) {
            var sounds = GameSounds.getSoundsInCategory(categories.get(i));
            for (int j = 0; sounds != null && j < sounds.size(); j++) {
                GameSound sound = sounds.get(j);
                String name = sound == null ? null : sound.getName();
                if (name == null || !name.startsWith("TW_")) continue;
                String category = category(rules, prefixes, name);
                if (category == null) unknown.rawset(++unknownCount, name);
                else inventory.add(new Row(name, category));
            }
        }
        int scaledSounds = 0;
        for (Row row : inventory) {
            Object value = scales.rawget(row.category);
            double scale = value instanceof Number number ? number.doubleValue() : 1.0;
            try {
                if (set(row.name, scale) > 0) scaledSounds++;
            } catch (Throwable ignored) {
                // Lua's old per-sound pcall also leaves a failing sound out and continues with the next one.
            }
        }
        KahluaTable result = LuaManager.platform.newTable();
        result.rawset("sounds", (double) inventory.size());
        result.rawset("scaled", (double) scaledSounds);
        result.rawset("unclassified", unknown);
        return result;
    }

    /** The volume the emitter multiplies for the sound now: its first clip's volume times the per-sound volume. */
    public static double volumeOf(String name) {
        GameSound sound = sound(name);
        if (sound == null || sound.clips == null || sound.clips.isEmpty()) {
            return -1;
        }
        GameSoundClip clip = sound.clips.get(0);
        return clip == null ? -1 : clip.volume * sound.getUserVolume();
    }

    /** The volume the sound scripts give the sound (the value 100 percent restores). */
    public static double baseOf(String name) {
        GameSound sound = sound(name);
        if (sound == null || sound.clips == null || sound.clips.isEmpty()) {
            return -1;
        }
        GameSoundClip clip = sound.clips.get(0);
        if (clip == null) {
            return -1;
        }
        Float kept = base.get(clip);
        return kept == null ? clip.volume : kept;
    }

    /** QA receipt: calls, clips scaled, the remembered clips, failures and the last failure. */
    public static String info() {
        return "calls=" + calls + " clips=" + scaled + " remembered=" + base.size() + " failures=" + failures
                + " last=" + last;
    }
}
