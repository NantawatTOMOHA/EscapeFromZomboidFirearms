package pz.escapefromzomboidfirearms.zb;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import me.zed_0xff.zombie_buddy.Exposer;
import me.zed_0xff.zombie_buddy.Patch;

/**
 * Every class this package patches is loaded (not initialized) before ZombieBuddy applies the
 * patches (Main.main runs first), so ZombieBuddy's ByteBuddy transformer finds each of them
 * already loaded and retransforms them all in its install batch.
 *
 * A class that first loads while ByteBuddy is transforming another class on the same thread is
 * skipped by its circularity lock and stays unpatched for the whole session. In a game without
 * another Java mod that links the character classes first (an isometric game without Viewpoint)
 * IsoGameCharacter and IsoPlayer are loaded but not linked when the patches are applied;
 * ZombieBuddy's transform asks them for their methods, which links them, and the verifier loads
 * CharacterSoundEmitter (ActionSound), IsoGameCharacter$TorchInfo (TorchAim) and ParameterDeaf
 * (HandGrenadeNative) inside that lock - those three patches never applied (class load log of
 * 20.10.2026: -XX:LogClassLoadingCauseFor=zombie.characters.CharacterSoundEmitter).
 */
@Exposer.LuaClass(name="TWPatchTargetsNative")
public final class PatchTargets {
    private static final byte[] PATCH = "Lme/zed_0xff/zombie_buddy/Patch;".getBytes(StandardCharsets.UTF_8);
    private static int loaded, absent, failed;
    private static String error = "";

    private PatchTargets() { }

    /** Loads the target class of every @Patch class in this JAR; the number of targets loaded. */
    public static int preload() {
        ClassLoader loader = PatchTargets.class.getClassLoader();
        Set<String> targets = new TreeSet<>();
        try (JarFile jar = new JarFile(new File(PatchTargets.class.getProtectionDomain().getCodeSource().getLocation().toURI()))) {
            for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                String name = e.nextElement().getName();
                if (!name.endsWith(".class")) continue;
                byte[] bytes;
                try (InputStream in = jar.getInputStream(jar.getJarEntry(name))) {
                    bytes = in.readAllBytes();
                }
                if (!contains(bytes, PATCH)) continue;
                try {
                    Class<?> k = Class.forName(name.substring(0, name.length() - 6).replace('/', '.'), false, loader);
                    Patch patch = k.getAnnotation(Patch.class);
                    if (patch != null && !patch.className().contains("*")) targets.add(patch.className());
                } catch (Throwable t) {
                    failed++;
                    error = name + ": " + t;
                }
            }
        } catch (Throwable t) {
            error = String.valueOf(t);
            System.out.println("[TWZB] patch targets not preloaded: " + t);
            return 0;
        }
        for (String target : targets) {
            try {
                Class.forName(target, false, loader);
                loaded++;
            } catch (ClassNotFoundException | NoClassDefFoundError missing) {
                absent++; // a class of an optional mod (Viewpoint) that is not installed
            } catch (Throwable t) {
                failed++;
                error = target + ": " + t;
            }
        }
        System.out.println("[TWZB] patch targets loaded before the patch pass: " + loaded + " of " + targets.size()
                + " (absent " + absent + ", failed " + failed + ")");
        return loaded;
    }

    /** QA receipt: loaded, absent (optional mods) and failed targets, the last error. */
    public static String info() {
        return "loaded=" + loaded + " absent=" + absent + " failed=" + failed + " error=" + error;
    }

    private static boolean contains(byte[] data, byte[] part) {
        outer:
        for (int i = 0; i + part.length <= data.length; i++) {
            for (int j = 0; j < part.length; j++) {
                if (data[i + j] != part[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
