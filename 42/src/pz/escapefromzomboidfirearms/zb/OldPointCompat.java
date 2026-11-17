package pz.escapefromzomboidfirearms.zb;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

/**
 * OldPoint (Workshop 3815999052, mod id OldPoint, version 0.6.151, requires Viewpoint) draws a rotating overhead camera
 * for Viewpoint: it keeps Viewpoint's own view flags and overrides the camera (viewpoint.camera.CameraView.place,
 * viewpoint.input.Camera.eye/direction), so Viewpoint can report first person while the world is drawn from above.
 * W11 oldpoint (the user, 29.10.2026: "with OldPoint the aim marker does not appear"): every first-person-only rule of
 * the mod (the eye pose, the ADS camera narrowing, the hidden crosshair dot and aim ring, the sight pictures, the
 * bore-aligned shot) must stand down there exactly as in Viewpoint's third person. OldPoint publishes the state
 * itself - public static oldpoint.viewpoint.Bridge.topDownActive(), the same call its own Lua uses through the
 * OldPointBridge handle - so the camera is read from the mod that owns it, never guessed from a threshold.
 * Inert without OldPoint: the class is looked up once, a missing or changed member leaves the mod exactly as it was
 * (one console line), and the read costs one static field test of OldPoint per call.
 */
public final class OldPointCompat {
    static final String OWNER = "oldpoint.viewpoint.Bridge";
    static final String OVERHEAD = "topDownActive";
    static final String KEEP_AIM = "keepAimCursor";

    public static volatile long reads, overheadReads, errors;
    public static volatile String status = "unchecked";

    /** QA (W11 oldpoint, A/B in one run): 0 follows OldPoint, 1 reports its overhead, -1 ignores it (the code before W11). */
    static volatile int qaForce;

    private static volatile boolean detected;
    private static volatile MethodHandle overhead;
    private static volatile MethodHandle keepAim;
    private static volatile MethodHandle aimRay;
    private static volatile String aimStatus = "aim point unchecked";

    private OldPointCompat() { }

    static synchronized boolean detect() {
        if (detected) {
            return overhead != null;
        }
        detected = true;
        try {
            Class<?> bridge = null;
            for (ClassLoader loader : new ClassLoader[] { OldPointCompat.class.getClassLoader(), ClassLoader.getSystemClassLoader(),
                    Thread.currentThread().getContextClassLoader() }) {
                if (loader == null) {
                    continue;
                }
                try {
                    bridge = Class.forName(OWNER, false, loader);
                    break;
                } catch (ClassNotFoundException ignored) {
                    // OldPoint is not installed for this loader: try the next one.
                }
            }
            if (bridge == null) {
                status = "absent";
                System.out.println("[TWZB] OldPoint camera: not installed, nothing changes");
                return false;
            }
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Method read = bridge.getDeclaredMethod(OVERHEAD);
            if (!read.getReturnType().equals(boolean.class)) {
                throw new NoSuchMethodException(OVERHEAD + " does not return boolean");
            }
            overhead = lookup.unreflect(read);
            try {
                keepAim = lookup.unreflect(bridge.getDeclaredMethod(KEEP_AIM));
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                keepAim = null; // A diagnostic only: the entry path reads it.
            }
            status = "on";
            System.out.println("[TWZB] OldPoint camera: " + OWNER + "." + OVERHEAD + " found; while its overhead camera is on the"
                    + " mod's first-person rules stand down (pose, ADS camera, crosshair dot and aim ring, sight pictures) and the"
                    + " shot is the game's own, as with a vanilla firearm");
            return true;
        } catch (Throwable t) {
            status = "lookup failed: " + t;
            overhead = null;
            System.out.println("[TWZB] OldPoint camera: " + status + ", nothing changes");
            return false;
        }
    }

    /**
     * OldPoint's rotating overhead camera draws the world now (any thread; static state of OldPoint only). False without
     * OldPoint, on any failure and while OldPoint is not in its overhead mode.
     */
    public static boolean overhead() {
        int force = qaForce;
        if (force != 0) {
            reads++;
            if (force > 0) {
                overheadReads++;
            }
            return force > 0;
        }
        MethodHandle read = overhead;
        if (read == null) {
            if (!detected && !detect() || (read = overhead) == null) {
                return false;
            }
        }
        try {
            boolean on = (boolean) read.invokeExact();
            reads++;
            if (on) {
                overheadReads++;
            }
            return on;
        } catch (Throwable t) {
            errors++;
            String message = "read failed: " + t;
            if (!message.equals(status)) {
                status = message;
                System.out.println("[TWZB] OldPoint camera " + message + ", the mod behaves as without it");
            }
            return false;
        }
    }

    /** OldPoint keeps the native aim cursor in its overhead view (its own keepAimCursor): logged only. */
    static boolean keepAimCursor() {
        MethodHandle read = keepAim;
        if (read == null) {
            return false;
        }
        try {
            return (boolean) read.invokeExact();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * QA: the world point under the cursor at a level height, mapped by OldPoint itself
     * (oldpoint.viewpoint.Pointer.ballisticsRay, the call its own aiming uses): "x,y,z,dx,dy,dz" in grid coordinates
     * (x and z are cells, y is the height in level units), or null when OldPoint is absent or has no projection.
     */
    public static String aimPoint(double height) {
        MethodHandle ray = aimRay;
        if (ray == null && !detectAim()) {
            return null;
        }
        ray = aimRay;
        if (ray == null) {
            return null;
        }
        try {
            org.joml.Vector3f from = new org.joml.Vector3f();
            org.joml.Vector3f to = new org.joml.Vector3f();
            boolean ok = (boolean) ray.invokeExact((float) height, from, to);
            if (!ok) {
                return null;
            }
            return String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f,%.4f,%.4f,%.4f", from.x, from.y, from.z, to.x, to.y, to.z);
        } catch (Throwable t) {
            errors++;
            return null;
        }
    }

    private static synchronized boolean detectAim() {
        if (aimRay != null) {
            return true;
        }
        try {
            Class<?> pointer = Class.forName("oldpoint.viewpoint.Pointer", false, OldPointCompat.class.getClassLoader());
            Method method = pointer.getDeclaredMethod("ballisticsRay", float.class, org.joml.Vector3f.class, org.joml.Vector3f.class);
            aimRay = MethodHandles.publicLookup().unreflect(method);
            return true;
        } catch (Throwable t) {
            aimStatus = "aim point unavailable: " + t;
            return false;
        }
    }

    public static String aimStatus() {
        return aimStatus;
    }

    /** QA: 0 follows OldPoint, 1 reports its overhead, -1 ignores it (the behaviour before W11 oldpoint). Returns the mode. */
    public static int qaForce(int mode) {
        qaForce = mode == 1 ? 1 : mode < 0 ? -1 : 0;
        return qaForce;
    }

    /** QA (02.11.2026): OldPoint's own "cursor over the UI" flag (Pointer.overUI), which hides its overhead aim. */
    public static String qaOverUI() {
        try {
            Class<?> pointer = Class.forName("oldpoint.viewpoint.Pointer", false, OldPointCompat.class.getClassLoader());
            return String.valueOf(pointer.getDeclaredMethod("overUI").invoke(null));
        } catch (Throwable t) {
            return "unavailable:" + t.getClass().getSimpleName();
        }
    }

    public static String stats() {
        return "status=" + status + " detected=" + detected + " force=" + qaForce + " reads=" + reads + " overhead="
                + overheadReads + " errors=" + errors + " keepAimCursor=" + keepAimCursor() + " " + aimStatus;
    }

    public static void reset() {
        reads = overheadReads = errors = 0;
    }
}
