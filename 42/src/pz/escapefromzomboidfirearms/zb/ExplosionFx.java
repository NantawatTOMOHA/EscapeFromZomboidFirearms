package pz.escapefromzomboidfirearms.zb;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadataNode;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

import me.zed_0xff.zombie_buddy.Exposer;
import se.krka.kahlua.vm.KahluaTable;
import zombie.ZomboidFileSystem;
import zombie.core.SpriteRenderer;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCell;
import zombie.iso.IsoLightSource;
import zombie.iso.IsoWorld;

/** Optional perspective fire/flash and ambient-lit smoke of smallgrenade_expl.
 * Public runtime API: TWExplosionFx.explode/smokeStart/smokeStop, via TW/ExplosionFx.
 * VpLights and MuzzleFx carry its source lights and one thermal event; Gunworks keeps the
 * isometric frames, damage, sound, projectile and network delivery.
 */
@Exposer.LuaClass
public final class ExplosionFx {
    static final int MAX_LIVE = 32;
    static final int[] OFFSETS = {0, 50, 150, 400, 1000};
    static final ArrayList<Event> live = new ArrayList<>();
    static final ArrayList<Smoke> smoke = new ArrayList<>();
    static long nextId;
    static Event last;
    static volatile CaptureRun capture;
    public static volatile long emitted, quads, expired, capped;
    public static volatile String rejected = "";

    static final class Event {
        final long id, nanos;
        final float x, y, h;
        final double born;
        final ExplosionFxData.Profile profile;
        final long seed;
        final ArrayList<VpLights.Light> lamps = new ArrayList<>();
        int frame = -1, built;
        boolean texturesReady;
        final float[] scene = new float[3];
        float side;
        /** Main thread (build): its squared scene distance this frame, the sort key. */
        double vpDistance;
        Event(float x, float y, float z, ExplosionFxData.Profile profile, long seed) {
            id = ++nextId; nanos = System.nanoTime(); born = MuzzleFx.time;
            this.x = x; this.y = y; h = (float) (z * ViewpointBridge.LEVEL);
            this.profile = profile; this.seed = seed;
        }
    }

    private ExplosionFx() { }

    public static boolean available() { return true; } // Native isometric rendering has no VP dependency.
    /** Client world boundary: no persistent cloud or owned thermal record leaks into the next world. */
    public static void reset() {
        for (Event e : live) { VpLights.lights.removeAll(e.lamps); }
        live.clear(); smoke.clear(); last = null; cancelCapture();
        MuzzleFx.blasts.removeIf(b -> b.explosionFx);
    }
    public static boolean hdrOn() { return ViewpointPass.hdrEnabled; }
    public static double hdrGain() { return ViewpointPass.hdrGain; }
    /** Warm the original resources once at client startup, before a cold first flash. */
    public static boolean readyImages() {
        boolean ready = true;
        for (var p : ExplosionFxData.PROFILES) {
            for (int i = 0; i < ExplosionFxData.TICKS.length; i++) {
                for (String layer : new String[] {"ambient", "fire"}) {
                    Texture t = MuzzleFx.texture(String.format(Locale.ROOT,"%s_%s_%02d",p.stem(),layer,i));
                    ready &= t != null && t.isReady();
                }
            }
        }
        for (String name : new String[] {"explosion_smoke", "explosion_smoke_noise"}) {
            Texture t = MuzzleFx.texture(name); ready &= t != null && t.isReady();
        }
        return ready;
    }

    static double number(KahluaTable t, String key, double fallback) {
        Object v = t == null ? null : t.rawget(key);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    static String string(KahluaTable t, String key) {
        Object v = t == null ? null : t.rawget(key);
        return v instanceof String s ? s : "";
    }

    static boolean input(KahluaTable args) {
        double x = number(args, "x", Double.NaN), y = number(args, "y", Double.NaN), z = number(args, "z", Double.NaN);
        double seed = number(args, "seed", Double.NaN);
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && Math.abs(x) <= 1e7 && Math.abs(y) <= 1e7
                && Math.abs(z) <= 10000 && Double.isFinite(seed) && seed == Math.rint(seed) && Math.abs(seed) <= 9007199254740991d
                && args.rawget("kind") instanceof String && args.rawget("effect") instanceof String && args.rawget("sourceTpl") instanceof String
                && (string(args, "sourceTpl").isEmpty() || string(args, "sourceTpl").matches("[0-9a-f]{24}"));
    }

    static Double reject(String reason) {
        if (!reason.equals(rejected)) { rejected = reason; System.out.println("[TW-EXPLOSION] rejected=" + reason); }
        return null;
    }

    static ExplosionFxData.Profile profile(String effect) {
        if (effect.equals("Grenade_new2")) { return ExplosionFxData.PROFILES[0]; }
        for (var p : ExplosionFxData.PROFILES) { if (p.effect().equals(effect)) { return p; } }
        return null;
    }

    /** Fixed client API. It never sends a packet or applies damage. Unknown effect/kind fails closed. */
    public static Double explode(KahluaTable args) {
        if (!input(args)) { return reject("invalid-arguments"); }
        String kind = string(args, "kind"), effect = string(args, "effect");
        if (kind.equals("smoke")) { return smokeStart(args); }
        var profile = profile(effect);
        if (profile == null || !(kind.equals("launcher") || kind.equals("frag") || kind.equals("flash"))
                || kind.equals("flash") != effect.equals("Flashbang")) { return reject("unsupported-effect-or-kind"); }
        if (MuzzleFx.data == null) { return null; }
        return emit(number(args, "x", 0), number(args, "y", 0), number(args, "z", 0), profile,
                (long) number(args, "seed", 0), kind, effect, string(args, "sourceTpl"));
    }

    static Double emit(double x, double y, double z, ExplosionFxData.Profile profile, long seed, String kind, String requested, String tpl) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 1e7 || Math.abs(y) > 1e7 || Math.abs(z) > 10000 || MuzzleFx.data == null) {
            return null;
        }
        MuzzleFx.advanceClock();
        Event e = new Event((float) x, (float) y, (float) z, profile, seed);
        // One shared thermal event and the source's own light set. No legacy
        // small-grenade light is also fired for the larger frag or flashbang.
        MuzzleFx.Blast heat = new MuzzleFx.Blast();
        heat.x = e.x; heat.y = e.y; heat.h = e.h; heat.born = e.born;
        heat.explosionFx = true;
        if (kind.equals("flash")) { heat.life = profile.lamps()[0].seconds(); }
        MuzzleFx.blasts.add(heat); MuzzleFx.explosions++;
        if (VpLights.takes()) {
            for (var lamp : profile.lamps()) {
                float height = e.h + (float) (lamp.heightMetres() / TarkovBallistics.METRES_PER_CELL);
                VpLights.blast(e.x,e.y,height,lamp.colour(),lamp.intensity(),lamp.reach(),lamp.seconds());
                e.lamps.add(VpLights.lights.get(VpLights.lights.size() - 1));
            }
        } else {
            isometricLights(e, profile, z);
        }
        last = e;
        emitted++;
        if (live.size() == MAX_LIVE) { live.remove(0); capped++; }
        live.add(e);
        CaptureRun r = capture;
        if (r != null && r.baselineRead && r.event == null && !r.cancelled) { r.event = e; }
        System.out.println("[TW-EXPLOSION] event=" + e.id + " kind=" + kind + " effect=" + requested + " resolvedEffect=" + profile.effect()
                + " sourceTpl=" + (tpl.isEmpty() ? "UNKNOWN" : tpl) + " seed=" + seed);
        return (double) e.id;
    }

    /** The isometric view lights with PZ's own lights: each source lamp is a temporary IsoLightSource for its time,
     * radius = its reach in tiles (as VpLights reads it), colour x intensity / 3 (as MuzzleFx.explosion lit the
     * launcher blast before these events drew in the isometric view too, FB1-C; the same light for smallgrenade_expl). */
    static void isometricLights(Event e, ExplosionFxData.Profile profile, double z) {
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        if (cell == null) { return; }
        for (var lamp : profile.lamps()) {
            float s = Math.min(1f, lamp.intensity() / 3f);
            float[] c = lamp.colour();
            cell.getLamppostPositions().add(new IsoLightSource((int) Math.floor(e.x), (int) Math.floor(e.y), (int) Math.floor(z),
                    c[0] * s, c[1] * s, c[2] * s, Math.max(1, Math.round(lamp.reach())), Math.round(lamp.seconds() * 60f)));
            MuzzleFx.lights++;
        }
    }

    /** Half-open source intervals: frame zero at the event, no frame after 3 s. */
    static int frameIndex(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0 || seconds >= ExplosionFxData.SECONDS) { return -1; }
        double ticks = seconds * 60;
        int edge = 0;
        for (int i = 0; i < ExplosionFxData.TICKS.length; i++) {
            edge += ExplosionFxData.TICKS[i];
            if (ticks < edge) { return i; }
        }
        return -1;
    }

    /**
     * Performance pass 23.10.2026: the image names of a profile's frames, formatted once each (the same format), not
     * up to four times per event per frame. Main thread.
     */
    static final java.util.IdentityHashMap<ExplosionFxData.Profile, String[]> NAMES = new java.util.IdentityHashMap<>();
    static final String[] LAYERS = {"ambient", "fire"};

    static String textureName(Event e, String layer) {
        int l = layer.equals("ambient") ? 0 : layer.equals("fire") ? 1 : -1, n = ExplosionFxData.TICKS.length;
        if (l < 0 || e.frame < 0 || e.frame >= n) {
            return String.format(Locale.ROOT, "%s_%s_%02d", e.profile.stem(), layer, e.frame);
        }
        String[] names = NAMES.get(e.profile);
        if (names == null) {
            NAMES.put(e.profile, names = new String[2 * n]);
        }
        String name = names[l * n + e.frame];
        if (name == null) {
            name = names[l * n + e.frame] = String.format(Locale.ROOT, "%s_%s_%02d", e.profile.stem(), layer, e.frame);
        }
        return name;
    }

    static boolean layer(Event e, String layer, int kind, float r, float g, float b) {
        float side = (float) (e.profile.metres() / TarkovBallistics.METRES_PER_CELL);
        float h = e.h + side * .5f, half = side * .5f;
        float[] uv = (e.seed & 1) == 0 ? MuzzleFx.VP_UV : new float[] {1,0, 0,0, 0,1, 1,1};
        return ViewpointPass.muzzle(new float[] {e.x,e.y,h, e.x,e.y,h, e.x,e.y,h, e.x,e.y,h},
                new float[] {-half,half, half,half, half,-half, -half,-half}, uv,
                MuzzleFx.vpColour(r, g, b, 1), null, false, textureName(e, layer), null, kind, 0, 0);
    }

    /** Main thread, after the VP snapshot/light records, before MuzzleFx's empty-weapon early return. */
    public static void afterWorld(int player) {
        if (live.isEmpty() && smoke.isEmpty()) { return; }
        for (int i = live.size() - 1; i >= 0; i--) {
            Event e = live.get(i);
            if (MuzzleFx.time >= e.born + ExplosionFxData.SECONDS) { live.remove(i); expired++; }
        }
        smoke.removeIf(s -> MuzzleFx.time >= s.born + s.duration);
        if (ViewpointBridge.perspective() && ViewpointPass.accepting()) {
            try {
                if (!live.isEmpty()) { build(player); }
                if (!smoke.isEmpty()) { buildSmoke(player); }
            } catch (Throwable t) {
                ViewpointPass.fail("explosion frame", t);
            }
        }
    }

    /**
     * Performance pass 23.10.2026: the far-to-near order by each one's distance of this frame, taken once and kept on
     * it (the comparator computed two per comparison); the same stable sort of the same copy, in a reused list.
     */
    static final ArrayList<Event> ORDERED = new ArrayList<>();
    static final ArrayList<Smoke> ORDERED_SMOKE = new ArrayList<>();

    static void build(int player) {
        ArrayList<Event> ordered = ORDERED;
        ordered.clear();
        ordered.addAll(live);
        for (Event e : ordered) {
            e.vpDistance = ViewpointPass.distance(e.x, e.y, e.h);
        }
        ordered.sort((a, b) -> Double.compare(b.vpDistance, a.vpDistance));
        try {
            buildOrdered(ordered, player);
        } finally {
            ordered.clear();
        }
    }

    static void buildOrdered(ArrayList<Event> ordered, int player) {
        for (Event e : ordered) {
            e.built = 0;
            e.frame = frameIndex(MuzzleFx.time - e.born);
            if (e.frame < 0) { continue; }
            e.side = (float) (e.profile.metres() / TarkovBallistics.METRES_PER_CELL);
            if (capture != null && capture.event == e) {
                ViewpointPass.open.scene(e.x,e.y,(e.h + e.side * .5f) / ViewpointBridge.LEVEL,e.scene,0);
            }
            MuzzleFx.vpLightAt(e.x, e.y, e.h, player, 0);
            if (layer(e, "ambient", MuzzleFx.KIND_SMOKE, MuzzleFx.light[0], MuzzleFx.light[1], MuzzleFx.light[2])) { e.built++; }
            if (layer(e, "fire", MuzzleFx.KIND_SPARK, 1, 1, 1)) { e.built++; }
            Texture a = MuzzleFx.texture(textureName(e, "ambient")), f = MuzzleFx.texture(textureName(e, "fire"));
            e.texturesReady = a != null && a.isReady() && f != null && f.isReady();
            quads += e.built;
        }
    }

    /** QA diagnostics. Prepared quads and ready textures alone do not establish visible pixels. */
    public static String info() {
        Event e = last;
        return "effect=" + ExplosionFxData.EFFECT + " emitted=" + emitted + " live=" + live.size() + " quads=" + quads
                + " expired=" + expired + " capped=" + capped + " event=" + (e == null ? 0 : e.id)
                + " frame=" + (e == null ? -1 : e.frame) + " built=" + (e == null ? 0 : e.built)
                + " ready=" + (e != null && e.texturesReady) + " smoke=" + smoke.size();
    }

    static final class Smoke {
        long id;
        double born;
        float x, y, h, duration, radius;
        float[] colour;
        // Preserve the twelve-particle budget; only their material density changes.
        float[][] offsets = new float[12][4];
        ExplosionFxData.Smoke profile;
        Event event;
        Texture[] isoTiles;
        /** Main thread (buildSmoke): its squared scene distance this frame, the sort key. */
        double vpDistance;
    }

    static ExplosionFxData.Smoke smokeProfile(KahluaTable args) {
        String effect = string(args, "effect"), tpl = string(args, "sourceTpl");
        for (var p : ExplosionFxData.SMOKE) {
            if (p.effect().equals(effect) && (!effect.isEmpty() || p.tpl().equals(tpl))) { return p; }
        }
        return null;
    }

    public static Double smokeStart(KahluaTable args) {
        if (!input(args) || !string(args, "kind").equals("smoke")) { return reject("invalid-smoke-arguments"); }
        var p = smokeProfile(args);
        if (p == null) { return reject("unsupported-smoke-effect"); }
        double duration = number(args, "durationSec", p.seconds()), radius = number(args, "radiusM", p.radius());
        double elapsed=number(args,"elapsedSec",0);
        if (args.rawget("durationSec") != null && !(args.rawget("durationSec") instanceof Number)
                || args.rawget("radiusM") != null && !(args.rawget("radiusM") instanceof Number)
                || args.rawget("elapsedSec") != null && !(args.rawget("elapsedSec") instanceof Number)) { return reject("invalid-smoke-duration-or-radius"); }
        if (!Double.isFinite(duration) || !Double.isFinite(radius) || !Double.isFinite(elapsed) || elapsed<0 || elapsed>=duration
                || duration <= 0 || duration > 3600 || radius <= 0 || radius > 100) {
            return reject("invalid-smoke-duration-or-radius");
        }
        float[] colour = p.colour().clone();
        Object col = args.rawget("color");
        if (col != null) {
            if (!(col instanceof KahluaTable t)) { return reject("invalid-smoke-colour"); }
            for (int i = 0; i < 3; i++) {
                Object v = t.rawget(i + 1);
                if (v == null) { v = t.rawget(String.valueOf("rgb".charAt(i))); }
                if (!(v instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0 || n.doubleValue() > 64) {
                    return reject("invalid-smoke-colour");
                }
                colour[i] = n.floatValue();
            }
        }
        if (MuzzleFx.data == null) { return null; }
        MuzzleFx.advanceClock();
        Smoke s = new Smoke(); s.born = MuzzleFx.time-elapsed; s.profile = p;
        s.x = (float) number(args, "x", 0); s.y = (float) number(args, "y", 0);
        s.h = (float) (number(args, "z", 0) * ViewpointBridge.LEVEL); s.colour = colour;
        s.duration = (float) duration; s.radius = (float) (radius / TarkovBallistics.METRES_PER_CELL);
        s.event = new Event(s.x, s.y, (float) number(args,"z",0), ExplosionFxData.PROFILES[0], (long) number(args,"seed",0));
        s.id = s.event.id; last = s.event;
        MuzzleFx.Blast ignition = new MuzzleFx.Blast();
        ignition.x = s.x; ignition.y = s.y; ignition.h = s.h; ignition.born = s.born;
        ignition.life = s.profile.ignitionLife();
        ignition.explosionFx = true;
        if (elapsed<ignition.life) MuzzleFx.blasts.add(ignition);
        CaptureRun captureRun = capture;
        if (captureRun != null && captureRun.baselineRead && captureRun.event == null && !captureRun.cancelled) { captureRun.event = s.event; }
        Random rng = new Random((long) number(args, "seed", 0));
        for (float[] o : s.offsets) {
            double angle = rng.nextDouble() * Math.PI * 2, r = Math.sqrt(rng.nextDouble()) * .5;
            o[0] = (float) (Math.cos(angle) * r); o[1] = (float) (Math.sin(angle) * r);
            o[2] = rng.nextFloat() * .6f; o[3] = rng.nextFloat();
        }
        if (smoke.size() == MAX_LIVE) { smoke.remove(0); capped++; }
        smoke.add(s);
        System.out.println("[TW-EXPLOSION] smoke=" + s.id + " effect=" + string(args, "effect") + " resolvedEffect=" + p.resolved()
                + " sourceTpl=" + p.tpl() + " seed=" + (long) number(args, "seed", 0));
        return (double) s.id;
    }

    public static void smokeStop(double id) { smoke.removeIf(s -> s.id == id); }

    static boolean insideSmoke(double x,double y,double z,double cx,double cy,double base,double radius) {
        if (radius<=0) return false;
        double dx=(x-cx)/radius,dy=(y-cy)/radius,dz=((z-base)*ViewpointBridge.LEVEL-radius*.5)/(radius*.5);
        return dx*dx+dy*dy+dz*dz<1;
    }
    static void insideVeil(Smoke s,float growth,float fade,int player) {
        double radius=s.radius*growth,base=s.h/ViewpointBridge.LEVEL;
        MuzzleFx.vpLightAt(s.x,s.y,s.h+(float)radius*.5f,player,s.profile.minAmbient());
        float r=s.colour[0]*MuzzleFx.light[0],g=s.colour[1]*MuzzleFx.light[1],b=s.colour[2]*MuzzleFx.light[2];
        // A surface alone cannot hide a target closer than that surface when
        // the camera is inside. Put one local veil just beyond the near plane.
        // The shared pass still excludes visible smoke in a thermal channel.
        var eye=ViewpointBridge.eyeNow();
        if (eye!=null && insideSmoke(eye.x,eye.y,eye.z,s.x,s.y,base,radius)) {
            double cp=Math.cos(eye.pitch),sp=Math.sin(eye.pitch),cy=Math.cos(eye.yaw),sy=Math.sin(eye.yaw);
            double d=ViewpointPass.NEAR*2;
            double[] centre={eye.x+cp*cy*d,eye.y+cp*sy*d,eye.z+sp*d/ViewpointBridge.LEVEL};
            double[] right={-sy,cy,0},up={-sp*cy,-sp*sy,cp/ViewpointBridge.LEVEL};
            double[] face=new double[12];
            for (int i=0;i<4;i++) {
                double u=(i==0||i==3?-1:1)*d*8,v=(i<2?1:-1)*d*8;
                for (int k=0;k<3;k++) face[i*3+k]=centre[k]+right[k]*u+up[k]*v;
            }
            float[] centres=new float[12];
            for (int i=0;i<4;i++) { centres[i*3]=(float)face[i*3];centres[i*3+1]=(float)face[i*3+1];centres[i*3+2]=(float)(face[i*3+2]*ViewpointBridge.LEVEL); }
            float age=(float)(MuzzleFx.time-s.born);
            if (ViewpointPass.muzzle(centres,null,new float[]{-6,0,-5,0,-5,1,-6,1},MuzzleFx.vpColour(r,g,b,fade),null,false,
                    "explosion_smoke","explosion_smoke_noise",MuzzleFx.KIND_SMOKE,s.profile.distortion(),age*s.profile.scroll())) { quads++;s.event.built++; }
        }
    }

    static float curve(float[][] keys, float x) {
        if (x <= keys[0][0]) { return keys[0][1]; }
        for (int i = 1; i < keys.length; i++) {
            float[] a = keys[i - 1], b = keys[i];
            if (x <= b[0]) {
                float d = b[0] - a[0], t = (x - a[0]) / d;
                if (!Float.isFinite(a[3]) || !Float.isFinite(b[2])) { return a[1]; }
                return (2*t*t*t-3*t*t+1)*a[1] + (t*t*t-2*t*t+t)*d*a[3]
                        + (-2*t*t*t+3*t*t)*b[1] + (t*t*t-t*t)*d*b[2];
            }
        }
        return keys[keys.length - 1][1];
    }

    static void buildSmoke(int player) {
        ArrayList<Smoke> ordered = ORDERED_SMOKE;
        ordered.clear();
        ordered.addAll(smoke);
        for (Smoke s : ordered) {
            s.vpDistance = ViewpointPass.distance(s.x, s.y, s.h);
        }
        ordered.sort((a,b) -> Double.compare(b.vpDistance, a.vpDistance));
        try {
            buildSmokeOrdered(ordered, player);
        } finally {
            ordered.clear();
        }
    }

    static void buildSmokeOrdered(ArrayList<Smoke> ordered, int player) {
        for (Smoke s : ordered) {
            s.event.built = 0;
            Texture diffuse = MuzzleFx.texture("explosion_smoke"), noise = MuzzleFx.texture("explosion_smoke_noise");
            s.event.texturesReady = diffuse != null && diffuse.isReady() && noise != null && noise.isReady();
            float age = (float) (MuzzleFx.time - s.born);
            float growth = Math.max(0, Math.min(1, curve(s.profile.growth(), age / s.duration)));
            float fade = Math.min(1, (s.duration - age) / Math.max(.1f, Math.min(2, s.duration * .1f)));
            s.event.side = s.radius * growth * 2.5f;
            if (capture != null && capture.event == s.event) {
                ViewpointPass.open.scene(s.x,s.y,(s.h+s.radius*growth*.5f)/ViewpointBridge.LEVEL,s.event.scene,0);
            }
            if (growth <= 0 || fade <= 0) { continue; }
            insideVeil(s,growth,fade,player);
            for (float[] o : s.offsets) {
                float x = s.x + o[0] * s.radius * growth, y = s.y + o[1] * s.radius * growth;
                float h = s.h + (.2f + o[2]) * s.radius * growth;
                float size = s.profile.size() / s.profile.radius() * s.radius * growth * .5f;
                float cs = (float) Math.cos(o[3] * 6.2831853f) * size * .5f, sn = (float) Math.sin(o[3] * 6.2831853f) * size * .5f;
                int tx = s.profile.tilesX(), ty = s.profile.tilesY();
                float n = (age / Math.max(.01f, s.profile.life()) + o[3]) % 1;
                int tile = Math.min(tx * ty - 1, (int) (n * tx * ty));
                float u = (tile % tx) / (float) tx, v = (tile / tx) / (float) ty;
                MuzzleFx.vpLightAt(x, y, h, player, s.profile.minAmbient());
                if (ViewpointPass.muzzle(new float[] {x,y,h,x,y,h,x,y,h,x,y,h},
                        new float[] {-cs+sn,sn+cs,cs+sn,-sn+cs,cs-sn,-sn-cs,-cs-sn,sn-cs},
                        new float[] {u-3,v,u+1f/tx-3,v,u+1f/tx-3,v+1f/ty,u-3,v+1f/ty},
                        MuzzleFx.vpColour(s.colour[0]*MuzzleFx.light[0],s.colour[1]*MuzzleFx.light[1],s.colour[2]*MuzzleFx.light[2],fade),
                        null,false,"explosion_smoke","explosion_smoke_noise",MuzzleFx.KIND_SMOKE,s.profile.distortion(),age*s.profile.scroll())) { quads++; s.event.built++; }
            }
        }
    }

    /** Same authored images and source clock in the native isometric world buffer.
     * Distortion/soft depth stay with the perspective shader; no third-party FX route.
     */
    public static void isometric() {
        if (ViewpointBridge.perspective()) return;
        MuzzleFx.advanceClock();
        int player=zombie.iso.IsoCamera.frameState.playerIndex;
        afterWorld(player);
        for (Event e:live) {
            e.frame=frameIndex(MuzzleFx.time-e.born);if (e.frame<0) continue;
            double z=e.h/ViewpointBridge.LEVEL;
            float pixels=Math.abs(ImpactFx.isoY(e.x,e.y,z)-ImpactFx.isoY(e.x,e.y,z+e.profile.metres()/TarkovBallistics.METRES_PER_LEVEL));
            var square=zombie.iso.IsoWorld.instance.getCell().getGridSquare((int)e.x,(int)e.y,(int)Math.floor(z));
            if (square==null || !square.isCanSee(player)) continue;
            // No light info yet for this player: wait for it (as ImpactFx), never a session-wide failure.
            var lit=square.getLightInfo(player);
            if (lit==null) continue;
            e.built=0;
            for (String layer:LAYERS) {
                Texture texture=MuzzleFx.texture(textureName(e,layer));
                if (texture==null || !texture.isReady()) continue;
                float cx=ImpactFx.isoX(e.x,e.y,z),cy=ImpactFx.isoY(e.x,e.y,z)-pixels*.5f,half=pixels*.5f;
                boolean fire=layer.equals("fire");
                SpriteRenderer.instance.renderPoly(texture,cx-half,cy-half,cx+half,cy-half,cx+half,cy+half,cx-half,cy+half,
                        fire?1:lit.r,fire?1:lit.g,fire?1:lit.b,1);e.built++;quads++;
            }
            e.texturesReady=e.built==2;
        }
        for (Smoke s:smoke) {
            float age=(float)(MuzzleFx.time-s.born);
            float growth=Math.max(0,Math.min(1,curve(s.profile.growth(),age/s.duration)));
            float fade=Math.min(1,(s.duration-age)/Math.max(.1f,Math.min(2,s.duration*.1f)));
            Texture atlas=MuzzleFx.texture("fb2c_smoke_atlas");
            if (atlas==null || !atlas.isReady() || growth<=0 || fade<=0) continue;
            int tx=s.profile.tilesX(),ty=s.profile.tilesY();
            if (s.isoTiles==null) {
                s.isoTiles=new Texture[tx*ty];
                int authoredWidth=atlas.getWidth();
                for (int i=0;i<tx*ty;i++) s.isoTiles[i]=atlas.split(i%tx*authoredWidth/tx,i/tx*atlas.getHeight()/ty,authoredWidth/tx,atlas.getHeight()/ty);
            }
            s.event.built=0;
            for (float[] o:s.offsets) {
                double x=s.x+o[0]*s.radius*growth,y=s.y+o[1]*s.radius*growth;
                double z=(s.h+(.2+o[2])*s.radius*growth)/ViewpointBridge.LEVEL;
                float metres=s.profile.size()/s.profile.radius()*s.radius*growth*.5f*(float)TarkovBallistics.METRES_PER_CELL;
                float pixels=Math.abs(ImpactFx.isoY(x,y,z)-ImpactFx.isoY(x,y,z+metres/TarkovBallistics.METRES_PER_LEVEL));
                int tile=Math.min(tx*ty-1,(int)(((age/Math.max(.01f,s.profile.life())+o[3])%1)*tx*ty));
                // Source smoke is lit as max(ambient, _Ambient) + lights: here the
                // PZ light of the floor square under the particle (own lamps
                // included), never self-lit at night.
                MuzzleFx.lightAt((float)x,(float)y,s.h,player);
                float floor=s.profile.minAmbient();
                ImpactFx.isoSprite(s.isoTiles[tile],x,y,z,pixels,s.colour[0]*Math.max(floor,MuzzleFx.light[0]),
                        s.colour[1]*Math.max(floor,MuzzleFx.light[1]),s.colour[2]*Math.max(floor,MuzzleFx.light[2]),fade);
                s.event.built++;quads++;
            }
            s.event.texturesReady=true;
        }
    }

    // QA only: read the existing world target after the perspective drawer.
    // PNG compression is off the render thread; no Core.TakeFullScreenshot stall
    // between deadlines. Every image embeds its actual event age and geometry.
    static final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), task -> { Thread t = new Thread(task, "tw-explosion-png"); t.setDaemon(true); return t; });

    static final class CaptureRun {
        final String name;
        final Path directory;
        final AtomicInteger saved = new AtomicInteger(), pending = new AtomicInteger();
        volatile boolean baselineRead, cancelled;
        volatile String error = "";
        Event event;
        boolean baselineQueued;
        int next;
        CaptureRun(String name, Path directory) { this.name = name; this.directory = directory; }
    }

    /** QA: baseline, then the next emit's 0/50/150/400/1000 ms frames in this cachedir's Screenshots. */
    public static boolean capture(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,100}") || writer.getQueue().size() > 0
                || capture != null && (capture.pending.get() > 0 || !capture.cancelled && capture.next < OFFSETS.length)) { return false; }
        try {
            capture = new CaptureRun(name, Path.of(ZomboidFileSystem.instance.getScreenshotDir()));
            return true;
        } catch (Throwable t) { return false; }
    }

    public static void cancelCapture() {
        CaptureRun r = capture;
        if (r != null) { r.cancelled = true; }
    }

    /** QA isometric control: observe the legacy Gunworks event without creating perspective effects. */
    public static void observeLegacy(double x, double y, double z) {
        CaptureRun r = capture;
        if (r == null || !r.baselineRead || r.event != null || ViewpointBridge.perspective()) { return; }
        MuzzleFx.advanceClock();
        r.event = new Event((float)x,(float)y,(float)z,ExplosionFxData.PROFILES[0],0);
    }

    public static String captureInfo() {
        CaptureRun r = capture;
        return r == null ? "capture=idle" : "capture=" + r.name + " baseline=" + r.baselineRead + " queued=" + r.next
                + " saved=" + r.saved.get() + " pending=" + r.pending.get() + " cancelled=" + r.cancelled + " error=" + r.error;
    }

    public static void captureFrame() {
        CaptureRun r = capture;
        if (r == null || r.cancelled || !r.error.isEmpty()) { return; }
        if (!r.baselineQueued) {
            r.baselineQueued = true;
            SpriteRenderer.instance.drawGeneric(new Capture(r, null, -1, 0));
        } else if (r.event != null && r.next < OFFSETS.length) {
            double age = (MuzzleFx.time - r.event.born) * 1000;
            if (age >= OFFSETS[r.next]) {
                SpriteRenderer.instance.drawGeneric(new Capture(r, r.event, OFFSETS[r.next++], age));
            }
        }
    }

    static final class Capture extends TextureDraw.GenericDrawer {
        final CaptureRun run;
        final String suffix;
        final long event, eventNanos, worldFrame;
        final int target, sourceFrame, built;
        final double age;
        final boolean ready;
        final int view;
        final String sensor;
        final float[] scene;
        final float side;
        final long lightWritten, lightFull, heatFrames;
        final String lightRecords;
        final String world;
        Capture(CaptureRun r, Event e, int target, double age) {
            run = r; this.target = target; this.age = age;
            suffix = target < 0 ? "baseline" : String.format(Locale.ROOT, "t%04d", target);
            event = e == null ? 0 : e.id; eventNanos = e == null ? 0 : e.nanos;
            sourceFrame = e == null ? -1 : e.frame; built = e == null ? 0 : e.built; ready = e != null && e.texturesReady;
            worldFrame = ViewpointPass.lastFrame;
            view = ViewpointBridge.state();
            sensor = VpSensor.head == null ? "plain" : VpSensor.head.kind == OpticState.THERMAL ? "thermal" : "nv";
            scene = e == null ? null : e.scene.clone(); side = e == null ? 0 : e.side;
            lightWritten = VpLights.written; lightFull = VpLights.full; lightRecords = VpLights.lastRecords;
            heatFrames = VpSensor.heatFrames;
            world = e == null ? "null" : String.format(Locale.ROOT,"[%.9f,%.9f,%.9f]",e.x,e.y,e.h/ViewpointBridge.LEVEL);
        }

        @Override
        public void render() {
            if (run.cancelled || !run.error.isEmpty()) { return; }
            try {
                int[] viewport = new int[4]; GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
                int w = viewport[2], h = viewport[3];
                if (w < 1 || h < 1 || (long) w * h > 33554432) { throw new IllegalStateException("capture viewport"); }
                ByteBuffer pixels = BufferUtils.createByteBuffer(w * h * 4);
                int pack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
                int readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
                int drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                int drawBuffer = GL11.glGetInteger(GL20.GL_DRAW_BUFFER0);
                int align = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT), row = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
                int skipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS), skipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, drawFbo);
                int targetReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
                try {
                    GL11.glReadBuffer(drawBuffer);
                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
                    GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1); GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
                    GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0); GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
                    GL11.glReadPixels(viewport[0], viewport[1], w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
                } finally {
                    GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, align); GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, row);
                    GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, skipRows); GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, skipPixels);
                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pack);
                    GL11.glReadBuffer(targetReadBuffer);
                    GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
                }
                double wallAge = eventNanos == 0 ? 0 : (System.nanoTime() - eventNanos) / 1e6;
                int[] roi = {0,0,w,h};
                if (view > 0 && scene != null && side > 0) {
                    var vp = ViewpointBridge.viewProjection(); var vm = ViewpointBridge.view();
                    if (vp == null || vm == null) { throw new IllegalStateException("capture camera"); }
                    var projection = new org.joml.Matrix4f(vp).mul(new org.joml.Matrix4f(vm).invert());
                    var p = new ViewpointPass.Proj(vp,vm,projection,new int[] {0,0,w,h});
                    float[] c = new float[4]; p.clip(scene[0],scene[1],scene[2],c,0);
                    if (c[3] <= ViewpointPass.NEAR) { throw new IllegalStateException("event behind camera"); }
                    float x = p.sx(c[0],c[3]), y = p.sy(c[1],c[3]), radius = side * .5f * p.focal / c[3];
                    roi = new int[] {Math.max(0,(int)Math.floor(x-radius)),Math.max(0,(int)Math.floor(y-radius)),
                                     Math.min(w,(int)Math.ceil(x+radius)),Math.min(h,(int)Math.ceil(y+radius))};
                    if (roi[2] <= roi[0] || roi[3] <= roi[1]) { throw new IllegalStateException("event outside viewport"); }
                }
                String meta = String.format(Locale.ROOT,
                        "{\"schema\":1,\"event\":%d,\"targetMs\":%d,\"ageMs\":%.6f,\"wallAgeMs\":%.6f,\"worldFrame\":%d,"
                        + "\"sourceFrame\":%d,\"builtQuads\":%d,\"texturesReady\":%s,\"muzzleRuns\":%d,\"width\":%d,\"height\":%d,\"view\":%d,\"sensor\":\"%s\",\"roi\":[%d,%d,%d,%d],"
                        + "\"lightWritten\":%d,\"lightFull\":%d,\"lightRecords\":\"%s\",\"heatFrames\":%d,\"hdrDrawn\":%d,\"postDrawn\":%d,\"drawFramebuffer\":%d,\"readBuffer\":%d,\"world\":%s}",
                        event, target, age, wallAge, worldFrame, sourceFrame, built, ready, ViewpointPass.muzzleRuns, w, h, view, sensor,
                        roi[0],roi[1],roi[2],roi[3],lightWritten,lightFull,lightRecords,heatFrames,ViewpointPass.hdrDrawn,ViewpointPass.postDrawn,drawFbo,drawBuffer,world);
                if (target < 0) { run.baselineRead = true; }
                run.pending.incrementAndGet();
                try { writer.execute(() -> write(run, suffix, pixels, w, h, meta)); }
                catch (Throwable t) { run.pending.decrementAndGet(); throw t; }
                System.out.println("[TW-EXPLOSION-QA] CAPTURE " + run.name + " " + suffix + " " + meta);
            } catch (Throwable t) { run.error = t.getClass().getSimpleName(); }
        }
    }

    static void write(CaptureRun run, String suffix, ByteBuffer pixels, int w, int h, String meta) {
        try {
            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            int[] rgb = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = ((h - 1 - y) * w + x) * 4;
                    rgb[y * w + x] = (pixels.get(i) & 255) << 16 | (pixels.get(i + 1) & 255) << 8 | (pixels.get(i + 2) & 255);
                }
            }
            Files.createDirectories(run.directory);
            Path file = run.directory.resolve("TW_VPQA_" + run.name + "_" + suffix + ".png");
            var encoder = ImageIO.getImageWritersByFormatName("png").next();
            try (var stream = ImageIO.createImageOutputStream(file.toFile())) {
                encoder.setOutput(stream);
                var metadata = encoder.getDefaultImageMetadata(javax.imageio.ImageTypeSpecifier.createFromRenderedImage(image), null);
                var root = new IIOMetadataNode("javax_imageio_png_1.0");
                var text = new IIOMetadataNode("tEXt"); var entry = new IIOMetadataNode("tEXtEntry");
                entry.setAttribute("keyword", "TWExplosion"); entry.setAttribute("value", meta); text.appendChild(entry); root.appendChild(text);
                metadata.mergeTree("javax_imageio_png_1.0", root);
                encoder.write(null, new IIOImage(image, null, metadata), null);
            } finally { encoder.dispose(); }
            run.saved.incrementAndGet();
        } catch (Throwable t) { run.error = t.getClass().getSimpleName(); }
        finally { run.pending.decrementAndGet(); }
    }
}
