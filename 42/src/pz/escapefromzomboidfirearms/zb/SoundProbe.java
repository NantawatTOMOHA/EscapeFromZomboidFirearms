package pz.escapefromzomboidfirearms.zb;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import fmod.fmod.FMODAudio;
import fmod.javafmod;
import me.zed_0xff.zombie_buddy.Exposer;

/**
 * QA receipts for the audio options (W10, player items 20 + 21): the numbers the engine actually gives FMOD
 * for a playing mod sound - the channel audibility (the volume after the 3D distance attenuation FMOD applies),
 * the volume the emitter computed for the instance (GameSoundClip.getEffectiveVolume, i.e. clip volume times the
 * per-sound user volume the sliders write) and the sound's own 3D settings. Never used by the mod itself.
 *
 * Lua sees it as TWSoundProbe:
 *   TWSoundProbe.audibility(audio)      - fmod.fmod.Audio from SoundManager.PlayWorldSound
 *   TWSoundProbe.audibilityHandle(h)    - the long handle of player:playSound / emitter:playSound
 *   TWSoundProbe.describe(audio)        - "name=.. vol=.. aud=.. gs3d=.. dmin=.. dmax=.."
 *   TWSoundProbe.info()                 - counters and the last failure, so a silent probe is visible
 */
@Exposer.LuaClass(name = "TWSoundProbe")
public final class SoundProbe {
    private static int calls, failures;
    private static String last = "";
    private static Field instances, toStart, clipField, nameField, channelField;
    private static Method volume;

    private SoundProbe() { }

    private static void prepare() {
        if (instances != null || failures > 0) {
            return;
        }
        try {
            instances = Class.forName("fmod.fmod.FMODSoundEmitter").getDeclaredField("instances");
            instances.setAccessible(true);
            toStart = Class.forName("fmod.fmod.FMODSoundEmitter").getDeclaredField("toStart");
            toStart.setAccessible(true);
            Class<?> sound = Class.forName("fmod.fmod.FMODSoundEmitter$Sound");
            clipField = sound.getField("clip");
            clipField.setAccessible(true);
            nameField = sound.getField("name");
            nameField.setAccessible(true);
            volume = sound.getMethod("getVolume");
            volume.setAccessible(true);
        } catch (Throwable failure) {
            failures++;
            last = "reflect: " + failure;
            instances = null;
        }
    }

    private static long ref(Object sound) {
        try {
            if (channelField == null || !channelField.getDeclaringClass().isInstance(sound)) {
                channelField = sound.getClass().getDeclaredField("channel");
                channelField.setAccessible(true);
            }
            return channelField.getLong(sound);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static Object instance(Object emitter, String name) {
        prepare();
        if (instances == null || emitter == null) {
            return null;
        }
        if (name == null) {
            return newest(emitter);
        }
        try {
            for (Field list : new Field[] {instances, toStart}) {
                Object value = list.get(emitter);
                if (!(value instanceof List<?> sounds)) {
                    continue;
                }
                for (int i = sounds.size() - 1; i >= 0; i--) {
                    Object sound = sounds.get(i);
                    Object soundName = nameField.get(sound);
                    if (name == null ? soundName == null : name.equals(soundName)) {
                        return sound;
                    }
                }
            }
        } catch (Throwable failure) {
            failures++;
            last = "instance: " + failure;
        }
        return null;
    }

    /**
     * The newest sound of an emitter. SoundManager.PlayWorldSound returns an FMODAudio built without a name
     * (FMODAudio.getName() is null), and it plays on an emitter taken from the free pool, so the last started
     * sound of that emitter is the one that was just played.
     */
    private static Object newest(Object emitter) {
        try {
            for (Field list : new Field[] {instances, toStart}) {
                Object value = list.get(emitter);
                if (value instanceof List<?> sounds && !sounds.isEmpty()) {
                    return sounds.get(sounds.size() - 1);
                }
            }
        } catch (Throwable failure) {
            failures++;
            last = "newest: " + failure;
        }
        return null;
    }

    /** The channel audibility FMOD reports: the volume after its 3D distance attenuation, 0 when silent. */
    public static double audibilityHandle(double handle) {
        calls++;
        if (handle <= 0) {
            return 0;
        }
        try {
            return javafmod.FMOD_Channel_GetAudibility((long) handle);
        } catch (Throwable failure) {
            failures++;
            last = "audibility: " + failure;
            return 0;
        }
    }

    private static Object emitterOf(Object audio) {
        return audio instanceof FMODAudio fmod ? fmod.emitter : null;
    }

    public static double audibility(Object audio) {
        calls++;
        if (!(audio instanceof FMODAudio fmod)) {
            last = "audibility: not a fmod Audio: " + audio;
            return 0;
        }
        Object sound = instance(fmod.emitter, fmod.getName());
        return sound == null ? 0 : audibilityHandle(ref(sound));
    }

    /** name, the volume the emitter computed, the audibility FMOD reports and the sound's 3D settings. */
    public static String describe(Object audio) {
        calls++;
        if (!(audio instanceof FMODAudio fmod)) {
            return "not-a-fmod-audio";
        }
        Object sound = instance(fmod.emitter, fmod.getName());
        if (sound == null) {
            return "name=" + fmod.getName() + " no-instance " + counts(audio);
        }
        try {
            Object clip = clipField.get(sound);
            Object gameSound = clip.getClass().getField("gameSound").get(clip);
            boolean is3d = gameSound.getClass().getField("is3d").getBoolean(gameSound);
            float min = (Float) clip.getClass().getField("distanceMin").get(clip);
            float max = (Float) clip.getClass().getField("distanceMax").get(clip);
            float volumeNow = (Float) volume.invoke(sound);
            long channel = ref(sound);
            return "name=" + nameField.get(sound) + " vol=" + volumeNow
                    + " aud=" + audibilityHandle(channel) + " gs3d=" + is3d
                    + " dmin=" + min + " dmax=" + max + " ref=" + channel;
        } catch (Throwable failure) {
            failures++;
            last = "describe: " + failure;
            return "name=" + fmod.getName() + " failed=" + failure;
        }
    }

    /** How many sounds the emitter of an audio holds, for a diagnosis of a measurement that found none. */
    public static String counts(Object audio) {
        if (!(audio instanceof FMODAudio fmod)) {
            return "not-a-fmod-audio";
        }
        prepare();
        try {
            int running = instances == null ? -1 : ((List<?>) instances.get(fmod.emitter)).size();
            int starting = toStart == null ? -1 : ((List<?>) toStart.get(fmod.emitter)).size();
            return "emitter=" + fmod.emitter + " instances=" + running + " toStart=" + starting;
        } catch (Throwable failure) {
            return "counts: " + failure;
        }
    }

    public static String info() {
        return "calls=" + calls + " failures=" + failures + " last=" + last;
    }

    /** The volume the emitter computes for a sound's clip: clip volume times the per-sound slider value. */
    public static double effective(String name) {
        calls++;
        try {
            zombie.audio.GameSound sound = zombie.GameSounds.getSound(name);
            zombie.audio.GameSoundClip clip = sound == null ? null : sound.getRandomClip();
            return clip == null ? -1 : clip.getEffectiveVolume();
        } catch (Throwable failure) {
            failures++;
            last = "effective: " + failure;
            return -1;
        }
    }

    /** The clip's own volume (what 100 percent must give back). */
    public static double clipVolume(String name) {
        calls++;
        try {
            zombie.audio.GameSound sound = zombie.GameSounds.getSound(name);
            zombie.audio.GameSoundClip clip = sound == null ? null : sound.getRandomClip();
            return clip == null ? -1 : clip.volume;
        } catch (Throwable failure) {
            failures++;
            last = "clipVolume: " + failure;
            return -1;
        }
    }

    /**
     * The engine's per-sound volume. It is the multiplier a player would expect the vanilla "advanced sound
     * options" screen to write, but {@code GameSound.getUserVolume()} returns 1.0 unless the debug switch
     * {@code SystemDisabler.setEnableAdvancedSoundOptions()} is set - which nothing in the game sets.
     */
    public static double userVolume(String name) {
        calls++;
        try {
            zombie.audio.GameSound sound = zombie.GameSounds.getSound(name);
            return sound == null ? -1 : sound.getUserVolume();
        } catch (Throwable failure) {
            failures++;
            last = "userVolume: " + failure;
            return -1;
        }
    }
}
