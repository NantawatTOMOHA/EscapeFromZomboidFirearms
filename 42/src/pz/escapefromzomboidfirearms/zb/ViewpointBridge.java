package pz.escapefromzomboidfirearms.zb;

import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.lwjgl.opengl.GL32;
import zombie.characters.IsoGameCharacter;

/**
 * Optional bridge to Viewpoint (first-person renderer on ZombieBuddy,
 * Workshop 3809306528). It reads public members and, by reflection, a few
 * package-private and private ones (docs/pending/VP_R1_RENDER_RU.md 7.2, 7.3),
 * calls public WorldRenderer.bindTarget, CombatHooks.muzzleFlash,
 * Impulses.shot and OwnedLight.modelLightAt, and has one advice on Viewpoint
 * (PatchViewpoint: the exit of WorldRenderer.drawWeather, allowed by the user
 * with this guard, 16.10.2026). Viewpoint's fields are written only for the
 * Tarkov lights (VpLights, allowed with the same guard): our point-light
 * records after its own in a frame's scene, and its flashlight's origin moved
 * to a Tarkov lens; and for Tarkov's camera recoil (FpRecoil): added to a
 * frame's camera additive, its own camera kick not handed over for those shots.
 * Every member below is looked up once, with its type and static flag;
 * one missing or changed, or an exception in any use, turns the whole bridge off
 * with one log line, and the mod behaves as without Viewpoint. The jar's
 * version and SHA-256 are logged, never required.
 * tools/check_viewpoint_bridge.py checks the same table against the installed
 * Viewpoint.jar (javap) and runs this class without Viewpoint. Classes are
 * found through this class's loader: ZombieBuddy appends every mod jar to the
 * system class path.
 */
public final class ViewpointBridge {
    /** class, member, JVM descriptor (field type or method descriptor), "static" or "" - all required. */
    static final String[][] MEMBERS = {
        {"viewpoint.core.View", "enabled", "Z", "static"},
        {"viewpoint.core.View", "thirdPerson", "Z", "static"},
        {"viewpoint.core.View", "frameFovY", "F", "static"},
        // FpAdsCamera: guarded return advice; radians, the first/third-person projection's user FOV.
        {"viewpoint.core.View", "fovY", "(Z)F", "static"},
        {"viewpoint.input.Look", "yaw", "F", "static"},
        {"viewpoint.input.Look", "pitch", "F", "static"},
        {"viewpoint.input.Camera", "direction", "(FF[F)V", "static"},
        {"viewpoint.FP", "frames", "[Lviewpoint/core/Frame;", "static"},
        {"viewpoint.core.Frame", "number", "J", ""},
        {"viewpoint.core.Frame", "camX", "F", ""},
        {"viewpoint.core.Frame", "camY", "F", ""},
        {"viewpoint.core.Frame", "camZ", "F", ""},
        {"viewpoint.core.Frame", "eyeX", "F", ""},
        {"viewpoint.core.Frame", "eyeY", "F", ""},
        {"viewpoint.core.Frame", "eyeZ", "F", ""},
        {"viewpoint.core.Frame", "scene", "Lviewpoint/render/SceneData;", ""},
        {"viewpoint.render.SceneData", "projection", "Lorg/joml/Matrix4f;", ""},
        {"viewpoint.render.SceneData", "relief", "Lviewpoint/render/FarRelief$Lift;", ""},
        {"viewpoint.render.FarRelief$Lift", "scene", "(DD)F", ""},
        {"viewpoint.SceneDrawer", "viewProjection", "Lorg/joml/Matrix4f;", "static"},
        {"viewpoint.SceneDrawer", "view", "Lorg/joml/Matrix4f;", "static"},
        {"viewpoint.render.WorldRenderer", "frame", "Lviewpoint/render/FrameContext;", "static"},
        {"viewpoint.render.WorldRenderer", "targets", "Lviewpoint/render/Targets;", "static"},
        {"viewpoint.render.WorldRenderer", "drawWeather", "(Lviewpoint/render/SceneData;FFF)V", "static"},
        {"viewpoint.render.WorldRenderer", "bindTarget", "()V", "static"},
        {"viewpoint.render.WorldRenderer", "restoreOutput", "()V", "static"},
        {"viewpoint.SceneDrawer", "drawFrame", "()V", ""},
        {"viewpoint.render.Targets", "depth", "I", ""},
        {"viewpoint.render.FrameContext", "scene", "Lviewpoint/render/SceneData;", ""},
        {"viewpoint.render.FrameContext", "viewProjection", "Lorg/joml/Matrix4f;", ""},
        {"viewpoint.render.FrameContext", "width", "I", ""},
        {"viewpoint.render.FrameContext", "height", "I", ""},
        {"viewpoint.render.FrameContext", "outputWidth", "I", ""},
        {"viewpoint.render.FrameContext", "outputHeight", "I", ""},
        {"viewpoint.CombatHooks", "muzzleFlash", "(Lzombie/characters/IsoGameCharacter;)V", "static"},
        // Tarkov lights in Viewpoint (VpLights): its own flashlight's origin (moved to a Tarkov lens), the light of a
        // square, the camera kick of a shot. The frame's point-light records are in OPTIONAL: without them only our lights go.
        {"viewpoint.render.SceneData", "spots", "Lviewpoint/render/SpotDraws;", ""},
        {"viewpoint.render.SpotDraws", "flashOn", "Z", ""},
        {"viewpoint.render.SpotDraws", "flashAtItem", "Z", ""},
        {"viewpoint.render.SpotDraws", "flashItemX", "F", ""},
        {"viewpoint.render.SpotDraws", "flashItemY", "F", ""},
        {"viewpoint.render.SpotDraws", "flashItemZ", "F", ""},
        {"viewpoint.light.OwnedLight", "modelLightAt", "(III)I", "static"},
        {"viewpoint.camera.Impulses", "shot", "(Lzombie/characters/IsoGameCharacter;)V", "static"},
    };
    /** Optional members: a missing one only turns its own use off (reported by vpInfo). */
    static final String[][] OPTIONAL = {
        // Tarkov lights (VpLights), the frame's point-light records: read, ours appended. A record of 11 floats, 8 at most in
        // both shapes - Viewpoint 0.1.6: a float[] with a count on the scene; 0.1.7: the scene's TorchDraws, which also says
        // what model a record rides on (torchesReady). Missing, only our lights are off; the bridge stays on.
        {"viewpoint.render.SceneData", "torches", "[F|Lviewpoint/render/TorchDraws;", ""},
        {"viewpoint.render.SceneData", "torchCount", "I", ""},
        {"viewpoint.render.SceneData", "TORCH_STRIDE", "I", "static"},
        {"viewpoint.render.SceneData", "MAX_TORCHES", "I", "static"},
        {"viewpoint.render.TorchDraws", "records", "[F", ""},
        {"viewpoint.render.TorchDraws", "rides", "[I", ""},
        {"viewpoint.render.TorchDraws", "count", "I", ""},
        {"viewpoint.render.TorchDraws", "STRIDE", "I", "static"},
        {"viewpoint.render.TorchDraws", "MAX", "I", "static"},
        // The light-only advice is optional and strict-matched; the shot impulse remains Viewpoint's.
        {"viewpoint.light.MuzzleFlashes", "fired", "(Lzombie/characters/IsoGameCharacter;)V", "static"},
        // QA only (TWZB.vpFlipThirdPerson): Viewpoint's own third-person toggle, a persisted setting
        // that ThirdPerson.poll copies into View.thirdPerson every frame of the 3D view
        {"viewpoint.camera.ThirdPerson", "flip", "()V", "static"},
        {"viewpoint.core.View", "ringX", "F", "static"},
        {"viewpoint.core.View", "ringY", "F", "static"},
        {"viewpoint.core.View", "swayRight", "F", "static"},
        {"viewpoint.core.View", "swayUp", "F", "static"},
        {"viewpoint.SceneDrawer", "failed", "Z", "static"},
        {"viewpoint.render.Targets", "width", "I", ""},
        {"viewpoint.render.Targets", "height", "I", ""},
        {"viewpoint.render.FrameContext", "panini", "Lviewpoint/render/Panini;", ""},
        {"viewpoint.render.Panini", "on", "()Z", ""},
        // the camera ahead of the frame's eye at a yaw: the eye of the first-person pose layer (FpPose)
        {"viewpoint.input.Camera", "eye", "(Lviewpoint/core/Frame;F[F)V", "static"},
        // a frame's camera additive (radians, its drawing turns the view by them): Tarkov's camera (FpRecoil)
        {"viewpoint.core.Frame", "cameraShot", "Lviewpoint/core/CameraShot;", ""},
        {"viewpoint.core.CameraShot", "addYaw", "F", ""},
        {"viewpoint.core.CameraShot", "addPitch", "F", ""},
        {"viewpoint.core.CameraShot", "addRoll", "F", ""},
        // the shot's camera kick Viewpoint has queued for the frame (private static, cleared only): the Tarkov camera
        // is the only kick of a shot whatever the mod order (FpRecoil, W8 user item 10)
        {"viewpoint.camera.Impulses", "ownKick", "Lviewpoint/camera/Recoil$Gun;", "static"},
        // Viewpoint's free look (bug wave 324, the user's item 2, 24.10.2026): Look.yaw at the press while its key is held,
        // NaN released (private static volatile, read only) - the first-person pose keeps the weapon there (FpFreeLook)
        {"viewpoint.input.FreeLook", "anchor", "F", "static"},
        // QA only (FpFreeLook.describe): the mouse is Viewpoint's (its free look needs it), read only
        {"viewpoint.input.Look", "captured", "Z", "static"},
        // W9 user item 8 (26.10.2026): aiming from Viewpoint's third person enters its real first person. The
        // camera is Viewpoint's own view, so its own third-person preference is turned off for the aim and put
        // back afterwards (CameraSettings.ON_FOOT, the persisted setting its key edits), and its view-change
        // bookkeeping runs as on any switch. Without these members the feature refuses and the bridge stays on.
        {"viewpoint.camera.CameraSettings", "ON_FOOT", "Lviewpoint/platform/LiveSettings$Toggle;", "static"},
        {"viewpoint.platform.LiveSettings$Toggle", "get", "()Z", ""},
        {"viewpoint.platform.LiveSettings$Toggle", "set", "(Z)V", ""},
        {"viewpoint.input.CrosshairAim", "switched", "()V", "static"},
        // W10 user item 2 (27.10.2026): in its third person the weapon's kick is Tarkov's (FpRecoil.thirdPerson); Viewpoint's
        // arm kick of that update is set to zero (public static, re-set by its GunKick every frame). Without it the arm kick stays.
        {"viewpoint.input.ArmsPitch", "kick", "(FFFFF)V", "static"},
        // W11 camera2 (items 2, 17, 29.10.2026): the crosshair pick (MousePick: one PBO and one fence; aimHit, the gbuffer it
        // reads) tagged with the frame each aim read was taken from, and QA per-frame telemetry (Look.yaw/heldYaw,
        // CrosshairAim.shown/holding/place/held, CameraSettings.SEATED).
        {"viewpoint.render.MousePick", "fence", "J", "static"},
        {"viewpoint.render.MousePick", "buffer", "I", "static"},
        {"viewpoint.render.Targets", "gbufferFbo", "I", ""},
        {"viewpoint.camera.CameraSettings", "SEATED", "Lviewpoint/platform/LiveSettings$Toggle;", "static"},
        {"viewpoint.input.CrosshairAim", "shown", "D", "static"},
        {"viewpoint.input.CrosshairAim", "placeX", "D", "static"},
        {"viewpoint.input.CrosshairAim", "placeY", "D", "static"},
        {"viewpoint.input.CrosshairAim", "holding", "Z", "static"},
        {"viewpoint.input.CrosshairAim", "heldX", "D", "static"},
        {"viewpoint.input.CrosshairAim", "heldY", "D", "static"},
        {"viewpoint.input.Look", "heldYaw", "F", "static"},
        {"viewpoint.input.Look", "heldPitch", "F", "static"},
        {"viewpoint.render.MousePick", "aimHit", "Lviewpoint/render/MousePick$Hit;", "static"},
        {"viewpoint.render.MousePick$Hit", "x", "D", ""},
        {"viewpoint.render.MousePick$Hit", "y", "D", ""},
        {"viewpoint.render.MousePick$Hit", "z", "D", ""},
        // 03.11.2026 (user's report: in the third person the bullet flies along the laser past the reticle): the point
        // its third-person crosshair marks - CrosshairAim's switch, camera place (placeX/Y above) and look of the frame
        // (crosshairAim). Without them our third-person shot keeps the drawn launch.
        {"viewpoint.input.CrosshairAim", "on", "Z", "static"},
        {"viewpoint.input.CrosshairAim", "placeUp", "D", "static"},
        {"viewpoint.input.CrosshairAim", "cosYaw", "D", "static"},
        {"viewpoint.input.CrosshairAim", "sinYaw", "D", "static"},
        {"viewpoint.input.CrosshairAim", "level", "D", "static"},
        {"viewpoint.input.CrosshairAim", "rise", "D", "static"},
    };
    /** A storey in Viewpoint's scene units (PZ level = sqrt 6 tiles, as Viewpoint's own lights). */
    static final double LEVEL = 2.4494897;

    private static boolean detected;
    /**
     * Performance pass 23.10.2026: the detection is over (set last, after every member below): detect() and the
     * per-object hooks answer without the class lock (it was taken on every call, every frame, from two threads).
     */
    private static volatile boolean settled;
    private static volatile boolean ready;
    private static String status = "not checked";
    private static String version = "?", sha256 = "?", order = "?";
    private static volatile boolean blended;
    /** Our advice on EffectsManager.startMuzzleFlash wraps Viewpoint's (we load after it). */
    private static boolean flashOuter = true;
    private static final Map<String, Member> members = new HashMap<>();
    private static final List<String> missingOptional = new ArrayList<>();
    private static MethodHandle liftScene, cameraDirection, muzzleFlash, paniniOn, bindTarget, restoreOutput, cameraEye, cellLight, shotKick;
    private static MethodHandle thirdPersonFlip;
    /** W9 user item 8: Viewpoint's own third-person toggle (get/set) and its view-change bookkeeping. */
    private static MethodHandle toggleGet, toggleSet, crosshairSwitched;
    /** W10 user item 2: Viewpoint's arm kick (ArmsPitch.kick, five floats). */
    private static MethodHandle armKick;
    /** Performance pass 23.10.2026: View.enabled / View.thirdPerson getters, ()boolean - read by many per-object hooks. */
    private static MethodHandle viewEnabled, viewThirdPerson;
    // static final objects of Viewpoint (the same instance every frame)
    private static Object viewProjection, view, frameContext, jitteredViewProjection;

    public static volatile long flashCalls, kicks, kicksReplaced, kicksDropped, errors, eyes;
    /** W10 user item 2: animation updates whose Viewpoint arm kick was set to zero (third person, Tarkov's kick). */
    public static volatile long armKicksDropped;
    public static volatile String lastError = "";

    private ViewpointBridge() {
    }

    // ------------------------------------------------------------------ detection

    /** Once, in game (first world frame or first call): look Viewpoint up. True when the bridge is on. */
    public static boolean detect() {
        if (settled) {
            return ready;
        }
        return detectOnce();
    }

    private static synchronized boolean detectOnce() {
        if (detected) {
            return ready;
        }
        detected = true;
        try {
            return lookUp();
        } finally {
            settled = true;
        }
    }

    private static boolean lookUp() {
        ClassLoader cl = ViewpointBridge.class.getClassLoader();
        Class<?> viewClass;
        try {
            viewClass = Class.forName("viewpoint.core.View", false, cl);
        } catch (Throwable t) {
            status = "absent";
            System.out.println("[TWZB] Viewpoint not loaded: bridge off");
            return false;
        }
        try {
            for (String[] m : MEMBERS) {
                Member found = resolve(m, cl);
                if (found == null) {
                    status = "member " + m[0] + "." + m[1] + " " + m[2] + (m[3].isEmpty() ? "" : " " + m[3]) + " missing or changed";
                    System.out.println("[TWZB] Viewpoint: " + status + ", bridge off");
                    return false;
                }
                members.put(m[0] + "." + m[1], found);
            }
            for (String[] m : OPTIONAL) {
                Member found = resolve(m, cl);
                if (found == null) {
                    missingOptional.add(m[0] + "." + m[1]);
                } else {
                    members.put(m[0] + "." + m[1], found);
                }
            }
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            liftScene = lookup.unreflect((Method) members.get("viewpoint.render.FarRelief$Lift.scene"))
                    .asType(MethodType.methodType(float.class, Object.class, double.class, double.class));
            cameraDirection = lookup.unreflect((Method) members.get("viewpoint.input.Camera.direction"));
            muzzleFlash = lookup.unreflect((Method) members.get("viewpoint.CombatHooks.muzzleFlash"));
            bindTarget = lookup.unreflect((Method) members.get("viewpoint.render.WorldRenderer.bindTarget"));
            restoreOutput = lookup.unreflect((Method) members.get("viewpoint.render.WorldRenderer.restoreOutput"));
            cellLight = lookup.unreflect((Method) members.get("viewpoint.light.OwnedLight.modelLightAt"));
            shotKick = lookup.unreflect((Method) members.get("viewpoint.camera.Impulses.shot"));
            Member flip = members.get("viewpoint.camera.ThirdPerson.flip");
            thirdPersonFlip = flip == null ? null : lookup.unreflect((Method) flip);
            Member on = members.get("viewpoint.render.Panini.on");
            paniniOn = on == null ? null : lookup.unreflect((Method) on).asType(MethodType.methodType(boolean.class, Object.class));
            Member eye = members.get("viewpoint.input.Camera.eye");
            cameraEye = eye == null ? null : lookup.unreflect((Method) eye)
                    .asType(MethodType.methodType(void.class, Object.class, float.class, float[].class));
            Member get = members.get("viewpoint.platform.LiveSettings$Toggle.get");
            toggleGet = get == null ? null : lookup.unreflect((Method) get).asType(MethodType.methodType(boolean.class, Object.class));
            Member set = members.get("viewpoint.platform.LiveSettings$Toggle.set");
            toggleSet = set == null ? null : lookup.unreflect((Method) set).asType(MethodType.methodType(void.class, Object.class, boolean.class));
            Member switched = members.get("viewpoint.input.CrosshairAim.switched");
            crosshairSwitched = switched == null ? null : lookup.unreflect((Method) switched).asType(MethodType.methodType(void.class));
            Member kick = members.get("viewpoint.input.ArmsPitch.kick");
            armKick = kick == null ? null : lookup.unreflect((Method) kick);
            viewEnabled = lookup.unreflectGetter(field("viewpoint.core.View.enabled"));
            viewThirdPerson = lookup.unreflectGetter(field("viewpoint.core.View.thirdPerson"));
            viewProjection = field("viewpoint.SceneDrawer.viewProjection").get(null);
            view = field("viewpoint.SceneDrawer.view").get(null);
            frameContext = field("viewpoint.render.WorldRenderer.frame").get(null);
            jitteredViewProjection = frameContext == null ? null : field("viewpoint.render.FrameContext.viewProjection").get(frameContext);
            if (viewProjection == null || view == null || frameContext == null || jitteredViewProjection == null) {
                status = "SceneDrawer/WorldRenderer matrices not created";
                System.out.println("[TWZB] Viewpoint: " + status + ", bridge off");
                return false;
            }
        } catch (Throwable t) {
            status = "lookup failed: " + t;
            System.out.println("[TWZB] Viewpoint: " + status + ", bridge off");
            return false;
        }
        version = readVersion(viewClass);
        sha256 = jarSha(viewClass);
        order = modOrder();
        blended = classPresent("viewpoint.render.ModelBlend", cl);
        status = "on";
        ready = true;
        System.out.println("[TWZB] Viewpoint " + version + " sha256=" + sha256 + ": bridge on (" + MEMBERS.length + " members"
                + (missingOptional.isEmpty() ? "" : ", optional missing " + missingOptional) + "); load order: " + order);
        return true;
    }

    private static boolean classPresent(String name, ClassLoader cl) {
        try {
            Class.forName(name, false, cl);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Viewpoint 0.1.7's frame: the draw records are a blend (ModelBlend) of the frame's captured records, re-made each
     * drawing and drawn at frames between the game's steps; false on 0.1.6, where the captured records are drawn. Only
     * after the bridge's check; a feature depends on its members, not on a version number.
     */
    static boolean blended() {
        return blended;
    }

    /** The member of one table row with its exact type and static flag, or null. */
    static Member resolve(String[] row, ClassLoader cl) {
        String d = row[2];
        if (d.indexOf('|') < 0) {
            return resolveOne(row, d, cl);
        }
        // Viewpoint 0.1.6 and 0.1.7 differ in this member: the descriptors of the versions we support, oldest first.
        for (String one : d.split("\\|")) {
            Member m = resolveOne(row, one, cl);
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    private static Member resolveOne(String[] row, String d, ClassLoader cl) {
        try {
            Class<?> owner = Class.forName(row[0], false, cl);
            boolean wantStatic = "static".equals(row[3]);
            if (d.startsWith("(")) {
                int close = d.indexOf(')');
                List<Class<?>> params = new ArrayList<>();
                for (int i = 1; i < close; ) {
                    int end = descriptorEnd(d, i);
                    params.add(type(d.substring(i, end), cl));
                    i = end;
                }
                Method m = owner.getDeclaredMethod(row[1], params.toArray(new Class<?>[0]));
                if (m.getReturnType() != type(d.substring(close + 1), cl) || Modifier.isStatic(m.getModifiers()) != wantStatic) {
                    return null;
                }
                m.setAccessible(true);
                return m;
            }
            Field f = owner.getDeclaredField(row[1]);
            if (f.getType() != type(d, cl) || Modifier.isStatic(f.getModifiers()) != wantStatic) {
                return null;
            }
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    static int descriptorEnd(String d, int i) {
        while (d.charAt(i) == '[') {
            i++;
        }
        return d.charAt(i) == 'L' ? d.indexOf(';', i) + 1 : i + 1;
    }

    static Class<?> type(String d, ClassLoader cl) throws ClassNotFoundException {
        switch (d.charAt(0)) {
            case 'Z': return boolean.class;
            case 'B': return byte.class;
            case 'C': return char.class;
            case 'S': return short.class;
            case 'I': return int.class;
            case 'J': return long.class;
            case 'F': return float.class;
            case 'D': return double.class;
            case 'V': return void.class;
            case '[': return java.lang.reflect.Array.newInstance(type(d.substring(1), cl), 0).getClass();
            case 'L': return Class.forName(d.substring(1, d.length() - 1).replace('/', '.'), false, cl);
            default: throw new ClassNotFoundException(d);
        }
    }

    static String readVersion(Class<?> viewClass) {
        try (InputStream in = viewClass.getResourceAsStream("/viewpoint/build.properties")) {
            if (in == null) {
                return "?";
            }
            Properties p = new Properties();
            p.load(in);
            return p.getProperty("version", "?") + " (" + p.getProperty("date", "?") + ")";
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * Performance pass 23.10.2026: the jar is hashed once per path (both bridges log the same Viewpoint.jar; the PIP
     * bridge's detection came at the first ADS, mid-game) and streamed in 64 KB blocks, not read whole (a 34 MB array).
     */
    private static final Map<Path, String> jarShas = new HashMap<>();

    static String jarSha(Class<?> viewClass) {
        try {
            Path jar = Path.of(viewClass.getProtectionDomain().getCodeSource().getLocation().toURI());
            synchronized (jarShas) {
                String known = jarShas.get(jar);
                if (known != null) {
                    return known;
                }
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream in = new java.security.DigestInputStream(Files.newInputStream(jar), digest)) {
                    byte[] block = new byte[64 * 1024];
                    while (in.read(block) >= 0) {
                        // the stream updates the digest
                    }
                }
                String hex = java.util.HexFormat.of().formatHex(digest.digest());
                jarShas.put(jar, hex);
                return hex;
            }
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * ZombieBuddy applies the patches of each Java mod in mod load order; the
     * later mod's OnEnter runs first and its skip also skips the earlier ones.
     * mod.info declares loadModAfter=Viewpoint, so normally our skip of
     * startMuzzleFlash would also skip Viewpoint's advice (we then call it, see
     * keepFlash); loaded before Viewpoint, its advice already ran.
     */
    static String modOrder() {
        try {
            List<String> ids = zombie.ZomboidFileSystem.instance.getModIDs();
            int ours = -1, vp = -1;
            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i).replace("\\", "");
                if (id.equals("EscapeFromZomboidFirearms")) {
                    ours = i;
                } else if (id.equals("Viewpoint")) {
                    vp = i;
                }
            }
            if (ours < 0 || vp < 0) {
                return "unknown (assumed after Viewpoint)";
            }
            flashOuter = ours > vp;
            return ours > vp ? "after Viewpoint" : "before Viewpoint";
        } catch (Throwable t) {
            return "unknown (assumed after Viewpoint)";
        }
    }

    static Field field(String key) {
        return (Field) members.get(key);
    }

    /**
     * A frame whose Viewpoint data one of our own checks could not prove (a matrix block that is
     * ambiguous for a frame, a record reused before its capture completed): not a changed member,
     * so that use skips the frame and the bridge stays on (FB1, 17.10.2026: one such frame right
     * after the world map turned the whole bridge off for the rest of a session).
     */
    public static final class DataMiss extends IllegalArgumentException {
        public DataMiss(String message) {
            super(message);
        }
    }

    private static final Map<String, long[]> misses = new HashMap<>();
    public static volatile long dataMisses;

    /** One unproven frame of a use: counted, logged on its first occurrence and then every 1000th. */
    public static void miss(String where, String detail) {
        dataMisses++;
        long n;
        synchronized (misses) {
            long[] count = misses.computeIfAbsent(where, k -> new long[1]);
            n = ++count[0];
        }
        if (n == 1 || n % 1000 == 0) {
            System.out.println("[TWZB] Viewpoint bridge " + where + ": " + detail + ", frame skipped (" + n + "), bridge stays on");
        }
    }

    static String missInfo() {
        synchronized (misses) {
            StringBuilder s = new StringBuilder();
            for (Map.Entry<String, long[]> e : misses.entrySet()) {
                s.append(s.length() == 0 ? "" : ",").append(e.getKey().replace(' ', '_')).append(':').append(e.getValue()[0]);
            }
            return s.length() == 0 ? "none" : s.toString();
        }
    }

    /** Any error in a use of Viewpoint: the whole bridge off (the mod as without Viewpoint), one log line.
     * A DataMiss is one unproven frame, not an error of the bridge: that frame is skipped only. */
    static void fail(String where, Throwable t) {
        if (t instanceof DataMiss) {
            miss(where, t.getMessage());
            return;
        }
        errors++;
        String message = where + ": " + t;
        if (!message.equals(lastError)) {
            lastError = message;
            System.out.println("[TWZB] Viewpoint bridge " + message + ", bridge off");
        }
        ready = false;
        status = "off after error: " + message;
    }

    // ------------------------------------------------------------------ any thread

    public static boolean ready() {
        return ready;
    }

    /** Viewpoint shows the world (first or third person): the isometric drawing of the mod is off. */
    public static boolean perspective() {
        if (!ready) {
            return false;
        }
        try {
            // The handle of the detection; without one (a harness that fills members itself) the field as before.
            MethodHandle h = viewEnabled;
            return h != null ? (boolean) h.invokeExact() : field("viewpoint.core.View.enabled").getBoolean(null);
        } catch (Throwable t) {
            fail("enabled", t);
            return false;
        }
    }

    /**
     * Viewpoint draws its own 3D view now, whether or not our bridge is on: the mod's isometric
     * overlays (sensor cones, beams) belong to the isometric frame and must not be drawn over it.
     * Only the first checked member, a static boolean, is read; false without Viewpoint.
     */
    public static boolean viewActive() {
        if (!detected) {
            detect();
        }
        if (ready) {
            return perspective();
        }
        Field f = field("viewpoint.core.View.enabled");
        try {
            return f != null && f.getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 0 isometric (or no bridge), 1 Viewpoint first person, 2 Viewpoint third person or another mod's camera that is not
     * the first person the mod's rules belong to. W11 oldpoint: OldPoint's rotating overhead camera keeps Viewpoint's own
     * view flags (its overhead entry sets third person itself, and a short right click of the mod's own aim-from-third
     * person can leave Viewpoint's flag at first person under it) while it overrides the camera, so a first-person flag
     * under that camera is not first person here. The whole class of first-person-only rules stands down then -
     * the eye pose, the ADS camera narrowing, the hidden crosshair dot and aim ring, the sight pictures, the bore-aligned
     * shot - exactly as in Viewpoint's third person, and the marker and the game's own aim stay as with a vanilla firearm.
     * Read from OldPoint's own state through OldPointCompat (inert without OldPoint).
     */
    public static int state() {
        if (!perspective()) {
            return 0;
        }
        try {
            MethodHandle h = viewThirdPerson;
            boolean third = h != null ? (boolean) h.invokeExact() : field("viewpoint.core.View.thirdPerson").getBoolean(null);
            return third || OldPointCompat.overhead() ? 2 : 1;
        } catch (Throwable t) {
            fail("thirdPerson", t);
            return 0;
        }
    }

    static float floatOf(String key, float def) {
        Field f = field(key);
        try {
            return f == null ? def : f.getFloat(null);
        } catch (Throwable t) {
            return def;
        }
    }

    // ------------------------------------------------------------------ main thread

    /** The Viewpoint frame snapshotted for the current main render state, or null (no bridge, isometric view). */
    static Object frameNow() {
        if (!ready && !detect() || !perspective()) {
            return null;
        }
        try {
            Object[] frames = (Object[]) field("viewpoint.FP.frames").get(null);
            int i = zombie.core.SpriteRenderer.instance.getMainStateIndex();
            return frames == null || i < 0 || i >= frames.length ? null : frames[i];
        } catch (Throwable t) {
            fail("frames", t);
            return null;
        }
    }

    static long frameNumber(Object frame) throws IllegalAccessException {
        return field("viewpoint.core.Frame.number").getLong(frame);
    }

    /** camX, camY, camZ, eyeX, eyeY, eyeZ of a frame into out; its relief lift (may be null). */
    static Object origin(Object frame, float[] out) throws IllegalAccessException {
        out[0] = field("viewpoint.core.Frame.camX").getFloat(frame);
        out[1] = field("viewpoint.core.Frame.camY").getFloat(frame);
        out[2] = field("viewpoint.core.Frame.camZ").getFloat(frame);
        out[3] = field("viewpoint.core.Frame.eyeX").getFloat(frame);
        out[4] = field("viewpoint.core.Frame.eyeY").getFloat(frame);
        out[5] = field("viewpoint.core.Frame.eyeZ").getFloat(frame);
        Object scene = field("viewpoint.core.Frame.scene").get(frame);
        return scene == null ? null : field("viewpoint.render.SceneData.relief").get(scene);
    }

    /** A frame's relief lift (FarRelief$Lift, an immutable record of that frame's camera), or null without one. */
    static Object reliefOf(Object frame) throws IllegalAccessException {
        Object scene = frame == null ? null : field("viewpoint.core.Frame.scene").get(frame);
        return scene == null ? null : field("viewpoint.render.SceneData.relief").get(scene);
    }

    /** Viewpoint's relief lift at a world point, scene units (0 without relief). */
    static float lift(Object relief, double x, double y) {
        if (relief == null) {
            return 0f;
        }
        try {
            return (float) liftScene.invokeExact(relief, x, y);
        } catch (Throwable t) {
            return 0f;
        }
    }

    /**
     * QA: turn Viewpoint's view level toward a world direction (PZ dx, dy). Look.yaw 0 looks
     * along +x and pi/2 along +y of the PZ world (measured: the shot owner's drawn bore at
     * yaw 0.785 was (0.707, 0.707), at yaw pi (-1, 0)). A failure here only refuses the
     * turn: the bridge stays on.
     */
    static boolean lookToward(double dx, double dy) {
        if (!ready || !(dx * dx + dy * dy > 0)) {
            return false;
        }
        try {
            field("viewpoint.input.Look.yaw").setFloat(null, (float) Math.atan2(dy, dx));
            field("viewpoint.input.Look.pitch").setFloat(null, 0f);
            return true;
        } catch (Throwable t) {
            System.out.println("[TWZB] Viewpoint look turn refused: " + t);
            return false;
        }
    }

    /** QA: the current look pitch in radians, or null when the guarded member cannot be read. */
    static Double lookPitch() {
        if (!ready) return null;
        try {
            float pitch = field("viewpoint.input.Look.pitch").getFloat(null);
            return Float.isFinite(pitch) ? (double) pitch : null;
        } catch (Throwable t) {
            System.out.println("[TWZB] Viewpoint look pitch read refused: " + t);
            return null;
        }
    }

    /** QA (FB2 item 13): the current look yaw in radians, or null. */
    static Double lookYaw() {
        if (!ready) return null;
        try {
            float yaw = field("viewpoint.input.Look.yaw").getFloat(null);
            return Float.isFinite(yaw) ? (double) yaw : null;
        } catch (Throwable t) {
            System.out.println("[TWZB] Viewpoint look yaw read refused: " + t);
            return null;
        }
    }

    /**
     * Bug wave 324 (the user's item 2, 24.10.2026): Viewpoint's free look held - the yaw its body keeps (FreeLook.anchor,
     * Look.yaw at the press; radians, any thread), NaN when released, without the bridge or the optional member. Only
     * read: Viewpoint's own FreeLook.update owns it (its key, its gate: first person on foot, the mouse captured, not
     * aiming). A failed read is NaN and leaves the bridge on.
     */
    static float freeLookAnchor() {
        Field f = ready ? field("viewpoint.input.FreeLook.anchor") : null;
        try {
            return f == null ? Float.NaN : f.getFloat(null);
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    /** QA (bug wave 324): Viewpoint holds the mouse (Look.captured: its free look needs it), or null without the member. */
    static Boolean lookCaptured() {
        Field f = ready ? field("viewpoint.input.Look.captured") : null;
        try {
            return f == null ? null : f.getBoolean(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** QA only: a mouse turn stand-in; a failed request leaves the bridge and the previous yaw alone. */
    static boolean setLookYaw(double yaw) {
        if (!ready || !Double.isFinite(yaw)) return false;
        try {
            field("viewpoint.input.Look.yaw").setFloat(null, (float) Math.IEEEremainder(yaw, 2 * Math.PI));
            return true;
        } catch (Throwable t) {
            System.out.println("[TWZB] Viewpoint look yaw write refused: " + t);
            return false;
        }
    }

    /** QA only: a failed request leaves the bridge and the previous pitch alone. */
    static boolean setLookPitch(double pitch) {
        if (!ready || !Double.isFinite(pitch) || Math.abs(pitch) > Math.PI / 2) return false;
        try {
            field("viewpoint.input.Look.pitch").setFloat(null, (float) pitch);
            return true;
        } catch (Throwable t) {
            System.out.println("[TWZB] Viewpoint look pitch write refused: " + t);
            return false;
        }
    }

    /**
     * QA: Viewpoint's third-person toggle (its key's action). Third person is a persisted
     * setting re-applied every frame, so View.cycle() cannot leave it once it is on: from the
     * isometric view the next view is third again. A failure only refuses the toggle.
     */
    static boolean flipThirdPerson() {
        if (!ready || thirdPersonFlip == null) {
            return false;
        }
        try {
            thirdPersonFlip.invokeExact();
            return true;
        } catch (Throwable t) {
            System.out.println("[TWZB] Viewpoint third-person toggle refused: " + t);
            return false;
        }
    }

    // ------------------------------------------------------------------ third person aim (W9 user item 8)

    /** Viewpoint's own third-person preference on foot (CameraSettings.ON_FOOT), or null: absent member or read error. */
    static Boolean thirdPersonSetting() {
        if (!ready && !detect()) {
            return null;
        }
        Field f = field("viewpoint.camera.CameraSettings.ON_FOOT");
        if (f == null || toggleGet == null) {
            return null;
        }
        try {
            Object toggle = f.get(null);
            return toggle == null ? null : (boolean) toggleGet.invokeExact(toggle);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The player's third-person preference we replaced for the current aim (null when nothing is ours). */
    private static Boolean adsThirdSaved;
    /** QA: the ADS-from-third-person view override is in effect. */
    public static volatile boolean adsThird;
    public static volatile long adsThirdEnters, adsThirdLeaves, adsThirdRefused;

    /**
     * Main thread, from Lua (TW_AdsFromThird) at the start of a game tick, before the player and animation updates:
     * aiming from Viewpoint's third person shows Viewpoint's real first person - the same view its own toggle gives -
     * for as long as the aim lasts, then the player's third-person preference comes back. Only Viewpoint's own
     * setting (CameraSettings.ON_FOOT) and the view field it copies into every frame are touched, and View.thirdPerson
     * is written together with the setting so ThirdPerson.poll sees no change to log or re-announce; the view-change
     * bookkeeping (CrosshairAim.switched) runs as on Viewpoint's own switch. False when nothing was changed: no
     * bridge, an optional member or the preference missing, or the player already in first person.
     */
    public static boolean firstPersonForAds(boolean on) {
        if (!ready && !detect()) {
            return false;
        }
        Field f = field("viewpoint.camera.CameraSettings.ON_FOOT");
        Field third = field("viewpoint.core.View.thirdPerson");
        if (f == null || third == null || toggleGet == null || toggleSet == null) {
            return false;
        }
        try {
            Object toggle = f.get(null);
            if (toggle == null) {
                return false;
            }
            if (on) {
                if (adsThirdSaved == null && !(boolean) toggleGet.invokeExact(toggle)) {
                    return false; // already Viewpoint's first person: nothing of the player's to override
                }
                adsThirdSaved = Boolean.TRUE;
                toggleSet.invoke(toggle, false);
                third.setBoolean(null, false);
                adsThird = true;
                adsThirdEnters++;
                switched();
                return true;
            }
            boolean ours = adsThirdSaved != null;
            adsThirdSaved = null;
            adsThird = false;
            if (!ours) {
                return false;
            }
            if (!(boolean) toggleGet.invokeExact(toggle)) {
                toggleSet.invoke(toggle, true);
                third.setBoolean(null, true);
            }
            adsThirdLeaves++;
            switched();
            return true;
        } catch (Throwable t) {
            adsThirdSaved = null;
            adsThird = false;
            adsThirdRefused++;
            if (adsThirdRefused == 1) {
                System.out.println("[TWZB] Viewpoint ADS from third person refused: " + t);
            }
            return false;
        }
    }

    /** What Viewpoint does when its own view changes: keeps the crosshair aim so the weapon does not jump. */
    private static void switched() {
        if (crosshairSwitched == null) {
            return;
        }
        try {
            crosshairSwitched.invokeExact();
        } catch (Throwable t) {
            // only the hold of the crosshair aim is lost; the view itself is unchanged
        }
    }

    /** QA (W11 camera2, A/B in one run): true keeps an aim hit read before a vehicle enter/exit, as before. */
    public static volatile boolean qaLegacyVehiclePick;

    /**
     * W11 camera2 (C), pure (check_camera_yaw drives it on stubs): the Viewpoint frame whose depth each aim hit's read
     * requested. MousePick keeps one pixel-pack buffer and one fence it polls without waiting: read() first collects the
     * read in flight (publishing its hit), then may issue a new one for the frame it draws - so a hit's frame is the frame
     * of the read issued before it, however many frames the GPU took. On the main thread, before CrosshairAim.snapshot
     * reads the hit, a hit whose read requested a frame before the last vehicle enter/exit is stale.
     */
    public static final class PickFrames {
        /** One published aim hit and the number of the frame its read requested. */
        record Tag(Object hit, long frame) { }

        // render thread (pendingFrame also read by the main thread: a hit collect() published before exit() tagged it)
        private volatile long pendingFrame = -1;
        private long fenceAtEnter;
        private Object hitAtEnter;
        private boolean signaledAtEnter;
        private volatile Tag tag;
        // main thread
        private long transitionFrame = Long.MIN_VALUE;
        private boolean seen, inVehicle;
        public volatile long reads;
        public long published, transitions, drops;

        /** MousePick.read entry: its fence, whether that fence is signaled (collect() consumes it), the aim hit. */
        public void enter(long fence, boolean signaled, Object hit) {
            fenceAtEnter = fence;
            signaledAtEnter = fence != 0 && signaled;
            hitAtEnter = hit;
        }

        /** MousePick.read exit: a new hit was the read in flight; a new or reissued fence is a read for frame. */
        public void exit(long fence, Object hit, long frame) {
            if (hit != null && hit != hitAtEnter) {
                tag = new Tag(hit, pendingFrame);
                published++;
            }
            // a read is issued only after collect() consumed the fence (or none was pending): the fence decides, never
            // the hit - the main thread may drop aimHit (aimSnapshot) while this read runs
            if (fence != 0 && (fenceAtEnter == 0 || signaledAtEnter || fence != fenceAtEnter)) {
                pendingFrame = frame;
                reads++;
            }
        }

        /** CrosshairAim.snapshot entry of frame: true drops hit (read from a frame before the last vehicle enter/exit). */
        public boolean snapshot(boolean inVehicleNow, long frame, Object hit) {
            if (seen && inVehicleNow != inVehicle) {
                transitionFrame = frame;
                transitions++;
            }
            seen = true;
            inVehicle = inVehicleNow;
            boolean stale = hit != null && reads > 0 && readFrame(hit) < transitionFrame;
            if (stale) {
                drops++;
            }
            return stale;
        }

        /**
         * The frame hit's read requested: its tag, or - for a hit collect() published inside the read now running, before
         * that read's exit() tagged it - the frame of the read in flight (pendingFrame is set after the tag, so a tag that
         * appears meanwhile is read again).
         */
        long readFrame(Object hit) {
            Tag t = tag;
            if (t != null && t.hit() == hit) {
                return t.frame();
            }
            long pending = pendingFrame;
            t = tag;
            return t != null && t.hit() == hit ? t.frame() : pending;
        }

        /** QA: the frame the current hit's read requested (-1 without a hit), and the last transition's. */
        public long frameOf(Object hit) {
            return hit == null || reads == 0 ? -1 : readFrame(hit);
        }

        public long transitionFrame() {
            return transitionFrame;
        }
    }

    static final PickFrames PICKS = new PickFrames();
    private static boolean pickLogged;

    /** Targets.gbufferFbo of a ModelPass's targets, or 0. */
    static int gbufferFbo(Object targets) {
        Field f = field("viewpoint.render.Targets.gbufferFbo");
        try {
            return f == null || targets == null ? 0 : f.getInt(targets);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** The number of the Viewpoint frame (FP.frames) whose scene a FrameContext draws, or -1. */
    static long drawnFrame(Object frameContext) {
        Field scene = field("viewpoint.render.FrameContext.scene");
        Field frames = field("viewpoint.FP.frames");
        Field frameScene = field("viewpoint.core.Frame.scene");
        if (scene == null || frames == null || frameScene == null || frameContext == null) {
            return -1;
        }
        try {
            Object s = scene.get(frameContext);
            for (Object f : (Object[]) frames.get(null)) {
                if (f != null && s != null && frameScene.get(f) == s) {
                    return frameNumber(f);
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /**
     * Entry of MousePick.read (render thread): its fence (signaled: collect() publishes the read in flight) and aim hit.
     * The fence is polled with glClientWaitSync(fence, 0, 0) - no flush, no wait - which every context with sync objects
     * answers (macOS, 02.11.2026: the Metal shim answered nothing to glGetSynci); signaled exactly as SYNC_STATUS was.
     */
    public static void pickEnter(Object frameContext) {
        Field fence = field("viewpoint.render.MousePick.fence");
        Field aimHit = field("viewpoint.render.MousePick.aimHit");
        if (fence == null || aimHit == null) {
            return;
        }
        try {
            long f = fence.getLong(null);
            int status = f != 0 ? GL32.glClientWaitSync(f, 0, 0L) : GL32.GL_TIMEOUT_EXPIRED;
            PICKS.enter(f, status == GL32.GL_ALREADY_SIGNALED || status == GL32.GL_CONDITION_SATISFIED, aimHit.get(null));
        } catch (Throwable t) {
            PICKS.enter(0, false, null);
        }
    }

    /** Exit of MousePick.read (render thread): tag a newly published aim hit, remember the frame of a new read. */
    public static void pickExit(Object frameContext) {
        Field fence = field("viewpoint.render.MousePick.fence");
        Field aimHit = field("viewpoint.render.MousePick.aimHit");
        if (fence == null || aimHit == null) {
            return;
        }
        try {
            PICKS.exit(fence.getLong(null), aimHit.get(null), drawnFrame(frameContext));
            if (!pickLogged) {
                pickLogged = true;
                Field buffer = field("viewpoint.render.MousePick.buffer");
                System.out.println("[TWZB] Viewpoint crosshair pick: MousePick reads through "
                        + (buffer == null ? "an unknown number of" : buffer.getType() == int.class ? "1" : buffer.getType().getSimpleName())
                        + " pixel-pack buffer(s) and one fence polled without waiting; each aim hit is tagged with the frame its read"
                        + " requested, a hit read before a vehicle enter/exit is dropped (no frame count)");
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Entry of CrosshairAim.snapshot (main thread, FP.snapshot of frame): an aim hit read from a frame before the last
     * vehicle enter/exit belongs to the other camera (the seat's, or the street's) - CrosshairAim's own `past` test only
     * covers the frames while seated - so it is dropped and Viewpoint's own shown = Infinity follows.
     */
    public static void aimSnapshot(Object frame, Object character) {
        Field aimHit = field("viewpoint.render.MousePick.aimHit");
        if (aimHit == null || frame == null || !(character instanceof IsoGameCharacter c)) {
            return;
        }
        try {
            Object hit = aimHit.get(null);
            if (PICKS.snapshot(c.getVehicle() != null, frameNumber(frame), hit) && !qaLegacyVehiclePick) {
                aimHit.set(null, null);
            }
        } catch (Throwable ignored) {
        }
    }

    /** QA: sets CameraSettings.SEATED or CameraSettings.ON_FOOT (third-person toggle). */
    public static boolean qaSetThirdPersonToggle(boolean seated, boolean thirdPerson) {
        if (!ready && !detect()) {
            return false;
        }
        try {
            Field f = field(seated ? "viewpoint.camera.CameraSettings.SEATED" : "viewpoint.camera.CameraSettings.ON_FOOT");
            if (f == null || toggleSet == null) {
                return false;
            }
            Object toggle = f.get(null);
            if (toggle == null) {
                return false;
            }
            toggleSet.invoke(toggle, thirdPerson);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The point Viewpoint's third-person crosshair marks, as CrosshairAim.snapshot left it for this frame (main thread):
     * out = camera place x, y (tiles), up (scene units, LEVEL a storey), the look direction (cos yaw * level, sin yaw *
     * level, rise; a unit vector in tiles), the pick's depth along it (Infinity: nothing picked, or the own vehicle),
     * then the picked surface itself (MousePick.aimHit: x, y tiles, z level, as drawn - relief lift included; NaN
     * without a depth). Viewpoint's own gunplay aims its shots at this point (Gunplay.active: CrosshairAim.camera). The
     * place and look are the frame's, a few centimetres off the eye the frame is drawn from (QA 03.11: the pick's depth
     * along them lands 8 px off the ring at 3.4 m); the picked surface is the one under the screen centre, the ring.
     * False while its crosshair aim is off or a member is missing.
     */
    static boolean crosshairAim(double[] out) {
        if (!ready) {
            return false;
        }
        Field on = field("viewpoint.input.CrosshairAim.on"), x = field("viewpoint.input.CrosshairAim.placeX");
        Field y = field("viewpoint.input.CrosshairAim.placeY"), up = field("viewpoint.input.CrosshairAim.placeUp");
        Field cos = field("viewpoint.input.CrosshairAim.cosYaw"), sin = field("viewpoint.input.CrosshairAim.sinYaw");
        Field level = field("viewpoint.input.CrosshairAim.level"), rise = field("viewpoint.input.CrosshairAim.rise");
        Field depth = field("viewpoint.input.CrosshairAim.shown");
        if (on == null || x == null || y == null || up == null || cos == null || sin == null || level == null || rise == null
                || depth == null) {
            return false;
        }
        try {
            if (!on.getBoolean(null)) {
                return false;
            }
            double l = level.getDouble(null);
            out[0] = x.getDouble(null);
            out[1] = y.getDouble(null);
            out[2] = up.getDouble(null);
            out[3] = cos.getDouble(null) * l;
            out[4] = sin.getDouble(null) * l;
            out[5] = rise.getDouble(null);
            out[6] = depth.getDouble(null);
            for (int i = 0; i < 6; i++) {
                if (!Double.isFinite(out[i])) {
                    return false;
                }
            }
            out[7] = out[8] = out[9] = Double.NaN;
            Field fHit = field("viewpoint.render.MousePick.aimHit"), hx = field("viewpoint.render.MousePick$Hit.x");
            Field hy = field("viewpoint.render.MousePick$Hit.y"), hz = field("viewpoint.render.MousePick$Hit.z");
            Object hit = Double.isFinite(out[6]) && fHit != null && hx != null && hy != null && hz != null ? fHit.get(null) : null;
            if (hit != null) {
                out[7] = hx.getDouble(hit);
                out[8] = hy.getDouble(hit);
                out[9] = hz.getDouble(hit);
            }
            return !Double.isNaN(out[6]) && out[6] > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** QA: per-frame sample of Look.yaw/heldYaw, CrosshairAim.shown/holding/place/held, MousePick.aimHit, and pickDepthFbo. */
    public static String cameraYawSample(IsoGameCharacter c) {
        if (!ready && !detect()) {
            return "ready=false";
        }
        try {
            Field fYaw = field("viewpoint.input.Look.yaw");
            Field fPitch = field("viewpoint.input.Look.pitch");
            Field fHeldYaw = field("viewpoint.input.Look.heldYaw");
            Field fHeldPitch = field("viewpoint.input.Look.heldPitch");
            Field fShown = field("viewpoint.input.CrosshairAim.shown");
            Field fHolding = field("viewpoint.input.CrosshairAim.holding");
            Field fPlaceX = field("viewpoint.input.CrosshairAim.placeX");
            Field fPlaceY = field("viewpoint.input.CrosshairAim.placeY");
            Field fHeldX = field("viewpoint.input.CrosshairAim.heldX");
            Field fHeldY = field("viewpoint.input.CrosshairAim.heldY");
            Field fAimHit = field("viewpoint.render.MousePick.aimHit");
            Field fHitX = field("viewpoint.render.MousePick$Hit.x");
            Field fHitY = field("viewpoint.render.MousePick$Hit.y");
            Field fHitZ = field("viewpoint.render.MousePick$Hit.z");
            double yawDeg = fYaw == null ? Double.NaN : Math.toDegrees(fYaw.getFloat(null));
            double pitchDeg = fPitch == null ? Double.NaN : Math.toDegrees(fPitch.getFloat(null));
            double heldYawDeg = fHeldYaw == null ? Double.NaN : Math.toDegrees(fHeldYaw.getFloat(null));
            double heldPitchDeg = fHeldPitch == null ? Double.NaN : Math.toDegrees(fHeldPitch.getFloat(null));
            double shown = fShown == null ? Double.NaN : fShown.getDouble(null);
            boolean holding = fHolding != null && fHolding.getBoolean(null);
            double placeX = fPlaceX == null ? Double.NaN : fPlaceX.getDouble(null);
            double placeY = fPlaceY == null ? Double.NaN : fPlaceY.getDouble(null);
            double heldX = fHeldX == null ? Double.NaN : fHeldX.getDouble(null);
            double heldY = fHeldY == null ? Double.NaN : fHeldY.getDouble(null);
            double pickDist = Double.NaN, pickX = Double.NaN, pickY = Double.NaN, pickZ = Double.NaN;
            Object hit;
            hit = fAimHit == null ? null : fAimHit.get(null);
            if (hit != null && fHitX != null && fHitY != null && fHitZ != null) {
                pickX = fHitX.getDouble(hit);
                pickY = fHitY.getDouble(hit);
                pickZ = fHitZ.getDouble(hit);
                double ox = Double.isFinite(placeX) ? placeX : (c != null ? c.getX() : Double.NaN);
                double oy = Double.isFinite(placeY) ? placeY : (c != null ? c.getY() : Double.NaN);
                pickDist = Math.hypot(pickX - ox, pickY - oy);
            }
            boolean inVeh = c != null && (c.getVehicle() != null
                    || (c instanceof zombie.characters.IsoPlayer p && p.isSeatedInVehicle()));
            double bodyDeg = c == null ? Double.NaN : Math.toDegrees(c.getAnimAngleRadians());
            return String.format(java.util.Locale.ROOT,
                    "vpState=%d layer=%b inVeh=%b bodyDeg=%.2f lookYawDeg=%.2f lookPitchDeg=%.2f heldYawDeg=%.2f heldPitchDeg=%.2f"
                            + " shown=%.4f holding=%b placeX=%.3f placeY=%.3f heldX=%.3f heldY=%.3f"
                            + " pickDist=%.4f pickX=%.3f pickY=%.3f pickZ=%.3f pickFrame=%d transitionFrame=%d"
                            + " selfAtGbuffer=%d selfAtRead=%d selfByFallback=%d selfMissed=%d selfRebinds=%d irisSelfAtRead=%d irisSelfMissed=%d"
                            + " selfInPick=%b ignoreVehLayer=%b legacyVehPick=%b vehTransitions=%d vehPickDrops=%d",
                    state(), FpViewLatch.layer(), inVeh, bodyDeg, yawDeg, pitchDeg, heldYawDeg, heldPitchDeg,
                    shown, holding, placeX, placeY, heldX, heldY,
                    pickDist, pickX, pickY, pickZ, PICKS.frameOf(hit), PICKS.transitionFrame(),
                    FpViewLatch.selfAtGbuffer, FpViewLatch.selfAtRead, FpViewLatch.selfByFallback, FpViewLatch.selfMissed,
                    FpViewLatch.selfRebinds, FpViewLatch.irisSelfAtRead, FpViewLatch.irisSelfMissed, FpViewLatch.qaSelfInPick, FpViewLatch.qaIgnoreVehicleLayer, qaLegacyVehiclePick,
                    PICKS.transitions, PICKS.drops);
        } catch (Throwable t) {
            return "error=" + t;
        }
    }

    public static String cameraYawStats() {
        return "selfInPick=" + FpViewLatch.qaSelfInPick + " ignoreVehLayer=" + FpViewLatch.qaIgnoreVehicleLayer
                + " legacyVehPick=" + qaLegacyVehiclePick + " selfAt=" + FpViewLatch.selfAtGbuffer + "/" + FpViewLatch.selfAtRead
                + "/" + FpViewLatch.selfByFallback + "/" + FpViewLatch.selfMissed + " rebinds=" + FpViewLatch.selfRebinds
                + " pickReads=" + PICKS.reads + " pickPublished=" + PICKS.published + " vehTransitions=" + PICKS.transitions
                + " vehPickDrops=" + PICKS.drops;
    }

    public static void cameraYawReset() {
        FpViewLatch.selfAtGbuffer = FpViewLatch.selfAtRead = FpViewLatch.selfByFallback = FpViewLatch.selfMissed = 0;
        FpViewLatch.selfRebinds = FpViewLatch.irisSelfAtRead = FpViewLatch.irisSelfMissed = 0;
        PICKS.reads = PICKS.published = PICKS.transitions = PICKS.drops = 0;
    }

    /**
     * The player whose aim the plain hold mode keeps latched through a release (null when nothing is latched). The
     * game clears forceAim every frame while its own "toggle to aim" option is off: its toggle handler is muted and
     * calls setForceAim(false) (CharacterInputComponent.updateToggleToAim). PatchViewpoint.AimLatch skips exactly that
     * call while a latch is held, so the engine's own aim state stays on; with the option on the handler is the
     * player's and nothing is skipped.
     */
    private static volatile IsoGameCharacter aimLatch;

    /**
     * W9 item 8: the game's own latched aim (forceAim - the flag its "toggle to aim" option edits) for the aim
     * started from third person, and the latch that keeps the game from clearing it in the plain hold mode.
     * Main thread, from Lua. False when the player or the call refused.
     */
    public static boolean aimForce(IsoGameCharacter c, boolean on) {
        try {
            if (c == null || c.isDead()) {
                return false;
            }
            if (on) {
                aimLatch = c;
                c.setForceAim(true);
                return c.isForceAim();
            }
            c.setForceAim(false);
            if (aimLatch == c) {
                aimLatch = null;
            }
            return !c.isForceAim();
        } catch (Throwable t) {
            aimLatch = null;
            return false;
        }
    }

    /**
     * PatchViewpoint.AimLatch (CharacterInputComponent.updateToggleToAim, main thread): true while the input's
     * character is the latched one and the game's own toggle-to-aim is off - that call would only clear the latch.
     * Never true with the game's option on: its toggle handler is the player's own.
     */
    public static boolean holdsAim(Object input) {
        IsoGameCharacter c = aimLatch;
        if (c == null || input == null) {
            return false;
        }
        try {
            if (onOption()) {
                return false;
            }
            return input == c.getCharacterInputComponent();
        } catch (Throwable t) {
            return false;
        }
    }

    /** The game's toggle-to-aim option; false when it cannot be read. */
    private static boolean onOption() {
        try {
            zombie.core.Core core = zombie.core.Core.getInstance();
            return core != null && core.isToggleToAim();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * QA (W9 item 8): the drawn camera's horizontal offset from the frame's eye in the newest Viewpoint frame,
     * scene units (tiles); -1 without a frame. Third person's distance is a setting, so it must read the same before
     * and after an aim from third person.
     */
    static double cameraDistance() {
        if (!ready && !detect() || !perspective()) {
            return -1;
        }
        try {
            Object[] frames = (Object[]) field("viewpoint.FP.frames").get(null);
            Object newest = null;
            long best = 0;
            for (Object f : frames == null ? new Object[0] : frames) {
                long n = f == null ? 0 : frameNumber(f);
                if (n > best) {
                    best = n;
                    newest = f;
                }
            }
            if (newest == null) {
                return -1;
            }
            float[] o = new float[6];
            origin(newest, o);
            // eyeX/eyeY/eyeZ are the eye's offset from the camera (FP.snapshot puts the character at camX..camZ):
            // first person reads ~0, the third-person boom its distance.
            return Math.sqrt((double) o[3] * o[3] + (double) o[4] * o[4] + (double) o[5] * o[5]);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Viewpoint's camera of a frame at an explicit look (any thread; FpViewLatch on the render thread):
     * Camera.eye (the camera ahead of the frame's eye at the yaw) into eye[0..2] and Camera.direction
     * into dir[0..2], scene space. False without the optional Camera.eye. Errors propagate to the caller.
     */
    static boolean camera(Object frame, float yaw, float pitch, float[] eye, float[] dir) throws Throwable {
        if (cameraEye == null) {
            return false;
        }
        cameraEye.invokeExact(frame, yaw, eye);
        cameraDirection.invoke(yaw, pitch, dir);
        return true;
    }

    /** Look.yaw and Look.pitch now, radians (errors propagate). */
    static void look(float[] out) throws IllegalAccessException {
        out[0] = field("viewpoint.input.Look.yaw").getFloat(null);
        out[1] = field("viewpoint.input.Look.pitch").getFloat(null);
    }

    /** The view direction (Look.yaw/pitch) in scene space into out[0..2]. */
    static boolean lookDirection(float[] out) {
        try {
            float yaw = field("viewpoint.input.Look.yaw").getFloat(null), pitch = field("viewpoint.input.Look.pitch").getFloat(null);
            cameraDirection.invoke(yaw, pitch, out);
            return true;
        } catch (Throwable t) {
            fail("look", t);
            return false;
        }
    }

    /**
     * EffectsManager.startMuzzleFlash of a Tarkov weapon, after MuzzleFx played
     * the shot and the method is skipped (PatchMuzzle.Flash): Viewpoint's own
     * advice there (its muzzle light and camera kick) was skipped with it when
     * we load after Viewpoint - hand it the shot. With the Tarkov lights on
     * (VpLights) the shot's light is Tarkov's: Viewpoint gets only its camera
     * kick (Impulses.shot, in its view, as its own CombatHooks.muzzleFlash
     * does). Loaded before Viewpoint, its advice already ran: no second call.
     */
    public static void keepFlash(IsoGameCharacter c) {
        if (c == null || !ready && !detect() || !flashOuter) {
            return;
        }
        hand(c);
    }

    /**
     * The skipped advice's part for c: the camera kick only (Tarkov lights on), else Viewpoint's
     * whole muzzle flash. A shot whose camera is Tarkov's (FpRecoil took it in the first person)
     * gets no kick of Viewpoint: one kick per shot.
     */
    static void hand(IsoGameCharacter c) {
        try {
            if (VpLights.replaces() || MuzzleFx.suppressLight(c)) {
                if (FpRecoil.replacesKick(c)) {
                    kicksReplaced++;
                } else if (perspective()) {
                    shotKick.invoke(c);
                    kicks++;
                }
            } else {
                muzzleFlash.invoke(c);
            }
            flashCalls++;
        } catch (Throwable t) {
            fail("muzzleFlash", t);
        }
    }

    /**
     * The eye of the newest Viewpoint frame for the first-person pose layer
     * (FpPose; main thread, during the animation update - before this frame's
     * own snapshot): the frame's eye in PZ coordinates (x, y tiles, z levels,
     * relief lift removed as ViewpointPass.eye), the camera's offset ahead of it
     * at the current Look.yaw (Camera.eye, public), and Look.yaw/pitch. Immutable;
     * the latest one is also published in lastEye for any other thread.
     * Bug wave 324 (the user's item 2, 24.10.2026): freeYaw - Viewpoint's free look held, the yaw the body keeps
     * (FreeLook.anchor, Look.yaw at the press), NaN released or without the optional member.
     */
    public static final class Eye {
        public final long number;
        public final double x, y, z, aheadX, aheadY, aheadZ;
        public final float yaw, pitch, freeYaw;

        Eye(long number, double x, double y, double z, double aheadX, double aheadY, double aheadZ, float yaw, float pitch) {
            this(number, x, y, z, aheadX, aheadY, aheadZ, yaw, pitch, Float.NaN);
        }

        Eye(long number, double x, double y, double z, double aheadX, double aheadY, double aheadZ, float yaw, float pitch, float freeYaw) {
            this.number = number;
            this.x = x;
            this.y = y;
            this.z = z;
            this.aheadX = aheadX;
            this.aheadY = aheadY;
            this.aheadZ = aheadZ;
            this.yaw = yaw;
            this.pitch = pitch;
            this.freeYaw = freeYaw;
        }

        /**
         * Bug wave 324: the same eye looking along another yaw and pitch (the weapon's frame of a free look): Camera.eye
         * puts the camera on a level circle ahead of the frame's eye (0.12 + the lean, at the yaw), so its offset turns
         * about the vertical by the yaw's difference; the height stays. This eye itself when nothing differs.
         */
        public Eye at(float yaw, float pitch) {
            if (Float.floatToRawIntBits(yaw) == Float.floatToRawIntBits(this.yaw) && Float.floatToRawIntBits(pitch) == Float.floatToRawIntBits(this.pitch)) {
                return this;
            }
            double d = (double) yaw - this.yaw, cs = Math.cos(d), sn = Math.sin(d);
            return new Eye(number, x, y, z, aheadX * cs - aheadY * sn, aheadX * sn + aheadY * cs, aheadZ, yaw, pitch, freeYaw);
        }
    }

    public static volatile Eye lastEye;

    /** The eye of the newest frame (the largest Frame.number of FP.frames), or null: no bridge, not in Viewpoint, no Camera.eye. */
    static Eye eyeNow() {
        if (!ready && !detect() || cameraEye == null || !perspective()) {
            return null;
        }
        try {
            Object[] frames = (Object[]) field("viewpoint.FP.frames").get(null);
            Object newest = null;
            long best = 0;
            for (Object f : frames == null ? new Object[0] : frames) {
                long n = f == null ? 0 : frameNumber(f);
                if (n > best) {
                    best = n;
                    newest = f;
                }
            }
            if (newest == null) {
                return null;
            }
            float[] o = new float[6], cam = new float[3];
            Object relief = origin(newest, o);
            float yaw = field("viewpoint.input.Look.yaw").getFloat(null), pitch = field("viewpoint.input.Look.pitch").getFloat(null);
            cameraEye.invokeExact(newest, yaw, cam);
            double x = o[0] - o[3], y = o[1] - o[5];
            double z = o[2] + (o[4] - lift(relief, x, y)) / LEVEL;
            // scene -> PZ: x = camX - sx, y = camY - sz, z levels = sy / sqrt 6
            Eye e = new Eye(best, x, y, z, -(cam[0] - o[3]), -(cam[2] - o[5]), (cam[1] - o[4]) / LEVEL, yaw, pitch, freeLookAnchor());
            lastEye = e;
            eyes++;
            return e;
        } catch (Throwable t) {
            fail("eye", t);
            return null;
        }
    }

    /**
     * Our advice on AnimationPlayer.updateModelTransforms exits after Viewpoint's
     * only when we load after it - the muzzle flash path needs that (keepFlash,
     * VpLights.muzzle: who lit the shot's light). The pose layers do not: they run
     * on the UpdateSkinTransforms entry in either order (GripIK.BeforeSkin).
     */
    static boolean loadedAfter() {
        return flashOuter;
    }

    /**
     * One camera kick per shot whatever the mod order (W8, user item 10). As the outer advice on
     * EffectsManager.startMuzzleFlash (we load after Viewpoint) our skip also skips Viewpoint's hook, so
     * Impulses.shot never queues its kick for a shot we take; loaded before it, its hook has already queued one
     * (Impulses.ownKick, private static, added to the frame's additive later) and it is dropped here, so Tarkov's
     * camera is the only kick in either order. False when the kick cannot be made ours alone (a Viewpoint without
     * that optional member): the shot then keeps Viewpoint's own kick, as before this fix.
     */
    static boolean ownShotKick() {
        if (flashOuter) {
            return true;
        }
        Field f = (Field) members.get("viewpoint.camera.Impulses.ownKick");
        if (f == null) {
            return false;
        }
        try {
            f.set(null, null);
            kicksDropped++;
            return true;
        } catch (Throwable t) {
            fail("shot kick", t);
            return false;
        }
    }

    /**
     * W10 user item 2 (tprecoil): Viewpoint's arm kick of this animation update set to zero for the local player whose
     * third-person weapon carries Tarkov's recoil (FpRecoil.thirdPerson, called from GripIK.apply: the same update's
     * updateModelTransforms, where ArmsPitch applies its kick, comes after it; its GunKick sets the kick again every frame).
     * Viewpoint's camera kick of the shot stays. False without the optional member: its arm kick then stays as before.
     */
    static boolean dropArmKick() {
        if (armKick == null) {
            return false;
        }
        try {
            armKick.invokeExact(0f, 0f, 0f, 0f, 0f);
            armKicksDropped++;
            return true;
        } catch (Throwable t) {
            fail("arm kick", t);
            return false;
        }
    }

    /** A frame's camera additive is there (optional members): Tarkov's camera can turn Viewpoint's view. */
    static boolean cameraAdditive() {
        return ready && field("viewpoint.core.Frame.cameraShot") != null && field("viewpoint.core.CameraShot.addYaw") != null
                && field("viewpoint.core.CameraShot.addPitch") != null && field("viewpoint.core.CameraShot.addRoll") != null;
    }

    /**
     * Main thread, after Viewpoint's snapshot of the frame (its camera additive is set by then and
     * read by its drawing on the render thread): adds yaw (right), pitch (up) and roll (radians)
     * to the frame's camera additive, once per frame. Returns the frame's number, or -1 when that
     * frame was written already or the members are absent. An error turns the bridge off.
     */
    static long addCamera(Object frame, long written, double[] add) {
        if (!cameraAdditive() || frame == null) {
            return -1;
        }
        try {
            long n = frameNumber(frame);
            if (n == written) {
                return -1;
            }
            Object shot = field("viewpoint.core.Frame.cameraShot").get(frame);
            if (shot == null) {
                return -1;
            }
            Field yaw = field("viewpoint.core.CameraShot.addYaw"), pitch = field("viewpoint.core.CameraShot.addPitch"),
                    roll = field("viewpoint.core.CameraShot.addRoll");
            yaw.setFloat(shot, yaw.getFloat(shot) + (float) add[0]);
            pitch.setFloat(shot, pitch.getFloat(shot) + (float) add[1]);
            roll.setFloat(shot, roll.getFloat(shot) + (float) add[2]);
            return n;
        } catch (Throwable t) {
            fail("recoil camera", t);
            return -1;
        }
    }

    /** The scene data of a frame (Frame.scene): the key of its batch on the render thread. */
    static Object sceneOf(Object frame) throws IllegalAccessException {
        return field("viewpoint.core.Frame.scene").get(frame);
    }

    // ------------------------------------------------------------------ main thread: lights (VpLights)

    /** A scene's point-light records (SceneData.torches) and their count. */
    /** The frame's point-light records are here in one of the two shapes: VpLights may append ours. */
    static boolean torchesReady() {
        if (!ready) {
            return false;
        }
        Field t = field("viewpoint.render.SceneData.torches");
        if (t == null) {
            return false;
        }
        return t.getType() == float[].class
                ? field("viewpoint.render.SceneData.torchCount") != null && field("viewpoint.render.SceneData.TORCH_STRIDE") != null
                        && field("viewpoint.render.SceneData.MAX_TORCHES") != null
                : field("viewpoint.render.TorchDraws.records") != null && field("viewpoint.render.TorchDraws.rides") != null
                        && field("viewpoint.render.TorchDraws.count") != null && field("viewpoint.render.TorchDraws.STRIDE") != null
                        && field("viewpoint.render.TorchDraws.MAX") != null;
    }

    static float[] torches(Object scene) throws IllegalAccessException {
        Object t = field("viewpoint.render.SceneData.torches").get(scene);
        return t instanceof float[] records ? records : (float[]) field("viewpoint.render.TorchDraws.records").get(t);
    }

    static int torchCount(Object scene) throws IllegalAccessException {
        Object t = field("viewpoint.render.SceneData.torches").get(scene);
        return t instanceof float[] ? field("viewpoint.render.SceneData.torchCount").getInt(scene)
                : field("viewpoint.render.TorchDraws.count").getInt(t);
    }

    /** The records up to n are ours from the old count on: on 0.1.7 they ride on no model, or Viewpoint would carry them with one. */
    static void torchCount(Object scene, int n) throws IllegalAccessException {
        Object t = field("viewpoint.render.SceneData.torches").get(scene);
        if (t instanceof float[]) {
            field("viewpoint.render.SceneData.torchCount").setInt(scene, n);
            return;
        }
        Field count = field("viewpoint.render.TorchDraws.count");
        int[] rides = (int[]) field("viewpoint.render.TorchDraws.rides").get(t);
        for (int i = Math.max(0, count.getInt(t)); i < n && i * 2 + 1 < rides.length; i++) {
            rides[i * 2] = -1;
            rides[i * 2 + 1] = -1;
        }
        count.setInt(t, n);
    }

    static int torchStride() throws IllegalAccessException {
        Field f = field("viewpoint.render.SceneData.TORCH_STRIDE");
        return (f != null ? f : field("viewpoint.render.TorchDraws.STRIDE")).getInt(null);
    }

    static int maxTorches() throws IllegalAccessException {
        Field f = field("viewpoint.render.SceneData.MAX_TORCHES");
        return (f != null ? f : field("viewpoint.render.TorchDraws.MAX")).getInt(null);
    }

    static Object spots(Object scene) throws IllegalAccessException {
        return field("viewpoint.render.SceneData.spots").get(scene);
    }

    static boolean flashOn(Object spots) throws IllegalAccessException {
        return field("viewpoint.render.SpotDraws.flashOn").getBoolean(spots);
    }

    /** The flashlight shines from the bone of the hand holding its item (not the chest). */
    static boolean flashAtItem(Object spots) throws IllegalAccessException {
        return field("viewpoint.render.SpotDraws.flashAtItem").getBoolean(spots);
    }

    /** The origin of Viewpoint's flashlight in scene space. */
    static void flashItem(Object spots, float[] s) throws IllegalAccessException {
        field("viewpoint.render.SpotDraws.flashItemX").setFloat(spots, s[0]);
        field("viewpoint.render.SpotDraws.flashItemY").setFloat(spots, s[1]);
        field("viewpoint.render.SpotDraws.flashItemZ").setFloat(spots, s[2]);
    }

    /**
     * The light Viewpoint gives a model in a square (OwnedLight.modelLightAt,
     * public): red, green, blue in bytes 0, 1, 2 (/ 255); negative when it has
     * none there (it then lights a model with PZ's ambient).
     */
    static int cellLight(int x, int y, int z) {
        try {
            return (int) cellLight.invokeExact(x, y, z);
        } catch (Throwable t) {
            fail("cell light", t);
            return -1;
        }
    }

    // ------------------------------------------------------------------ render thread

    /** The frame's view-projection matrix (SceneDrawer, no TAA jitter) and view matrix. */
    static org.joml.Matrix4f viewProjection() {
        return (org.joml.Matrix4f) viewProjection;
    }

    /** The view-projection Viewpoint draws its scene with now, TAA jitter included (FrameContext). */
    static org.joml.Matrix4f jitteredViewProjection() {
        return (org.joml.Matrix4f) jitteredViewProjection;
    }

    /** The scene Viewpoint draws now (FrameContext.scene, set by WorldRenderer.begin). */
    static Object currentScene() throws IllegalAccessException {
        return field("viewpoint.render.FrameContext.scene").get(frameContext);
    }

    /** WorldRenderer.frame: Viewpoint's one FrameContext of the main view. */
    static Object frameContext() {
        return frameContext;
    }

    /** WorldRenderer.restoreOutput: the frame's output target and viewport, no vertex array or sampler (public). */
    static void restoreOutput() throws Throwable {
        restoreOutput.invoke();
    }

    /** WorldRenderer.bindTarget: Viewpoint's HDR target with the render viewport (public). */
    static void bindTarget() throws Throwable {
        bindTarget.invoke();
    }

    static org.joml.Matrix4f view() {
        return (org.joml.Matrix4f) view;
    }

    /** The projection of a frame (SceneData.projection). */
    static org.joml.Matrix4f projection(Object frame) throws IllegalAccessException {
        Object scene = field("viewpoint.core.Frame.scene").get(frame);
        return scene == null ? null : (org.joml.Matrix4f) field("viewpoint.render.SceneData.projection").get(scene);
    }

    /** depth texture, render width/height, depth texture width/height (0: unknown), output width/height, Panini on (1). */
    static void targets(int[] out) throws IllegalAccessException {
        Object targets = field("viewpoint.render.WorldRenderer.targets").get(null);
        out[0] = targets == null ? 0 : field("viewpoint.render.Targets.depth").getInt(targets);
        out[1] = field("viewpoint.render.FrameContext.width").getInt(frameContext);
        out[2] = field("viewpoint.render.FrameContext.height").getInt(frameContext);
        Field tw = field("viewpoint.render.Targets.width"), th = field("viewpoint.render.Targets.height");
        out[3] = targets == null || tw == null ? 0 : tw.getInt(targets);
        out[4] = targets == null || th == null ? 0 : th.getInt(targets);
        out[5] = field("viewpoint.render.FrameContext.outputWidth").getInt(frameContext);
        out[6] = field("viewpoint.render.FrameContext.outputHeight").getInt(frameContext);
        out[7] = 0;
        Field panini = field("viewpoint.render.FrameContext.panini");
        if (panini != null && paniniOn != null) {
            Object p = panini.get(frameContext);
            try {
                out[7] = p != null && (boolean) paniniOn.invokeExact(p) ? 1 : 0;
            } catch (Throwable t) {
                out[7] = 0;
            }
        }
        VpShaderPackCompat.overrideTargets(out);
    }

    // ------------------------------------------------------------------ QA

    /** "bridge= version= sha256= order= state= fov= ring= flash= errors= missing=" (TWZB.vpInfo). */
    public static String info() {
        return "bridge=" + status + " version=" + version + " sha256=" + sha256 + " order=" + order + " state=" + state()
                + String.format(java.util.Locale.ROOT, " fov=%.2f ring=%.3f,%.3f sway=%.3f,%.3f",
                        floatOf("viewpoint.core.View.frameFovY", 0f), floatOf("viewpoint.core.View.ringX", 0f),
                        floatOf("viewpoint.core.View.ringY", 0f), floatOf("viewpoint.core.View.swayRight", 0f),
                        floatOf("viewpoint.core.View.swayUp", 0f))
                + " flashOuter=" + flashOuter + " flashCalls=" + flashCalls + " kicks=" + kicks + " kicksReplaced=" + kicksReplaced
                + " kicksDropped=" + kicksDropped
                + " eyes=" + eyes + " errors=" + errors + " dataMisses=" + dataMisses + " misses=" + missInfo()
                + String.format(java.util.Locale.ROOT, " freeLook=%.2f", Math.toDegrees(freeLookAnchor()))
                + " adsThird=" + (adsThird ? 1 : 0) + "/" + adsThirdEnters + "/" + adsThirdLeaves + "/" + adsThirdRefused
                + (missingOptional.isEmpty() ? "" : " missing=" + missingOptional)
                + (lastError.isEmpty() ? "" : " lastError=" + lastError);
    }

    public static String status() {
        return status;
    }
}
