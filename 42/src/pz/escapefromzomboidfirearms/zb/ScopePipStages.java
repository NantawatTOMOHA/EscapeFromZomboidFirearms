package pz.escapefromzomboidfirearms.zb;

import org.joml.Matrix4f;

/**
 * Extension points of the scope picture, so later stages stay in their own
 * classes: a scene stage adds our own world content (lasers, light beams,
 * muzzle effects) into a freshly rendered scope image, a sensor (night vision,
 * thermal) rewrites the image before the lens composite, an overlay (reticle,
 * lens effects) draws over the composited lens disk. All run on the render thread inside
 * ScopePip's saved GL state; a stage that throws is switched off alone (one
 * log line), the plain scope picture keeps working.
 */
public final class ScopePipStages {
    private ScopePipStages() {
    }

    /** One drawn scope frame; immutable copies, safe to keep for the frame. */
    public static final class Frame {
        /** ScopePip request key: item id + ":" + path:tpl:channel:mode + ":" + fov. */
        public final String key;
        /** TWSight view kind: optic, thermal or nv. */
        public final String kind;
        public final Object weapon;
        /** Vertical field of view of the scope camera, degrees (Tarkov units). */
        public final float fov;
        /** Scope colour image (RGBA8, size x size) and the secondary depth Viewpoint drew it with. */
        public final int texture, depth, size;
        /** True when the scope camera re-rendered the world this frame (false: the last image is reused). */
        public final boolean fresh;
        public final Matrix4f view, projection;
        /** ScopePip.lens() layout: GL pixels (bottom-left) cx,cy, radius-axisX.xy, radius-axisY.xy, center depth. */
        public final float[] lens;
        /** Optical ray origin in GL pixels and in the physical ellipse's UV basis. */
        public final float[] opticalOrigin, imageOrigin;
        /** Optional exact source polygon in aperture coordinates (GL y up), and window-depth slopes. */
        public final float[] boundary, depthSlope;
        /** Main viewport x,y,w,h. */
        public final int[] viewport;
        public final float near, far;

        Frame(String key, String kind, Object weapon, float fov, int texture, int depth, int size, boolean fresh,
                Matrix4f view, Matrix4f projection, float[] lens, int[] viewport, float near, float far) {
            this(key, kind, weapon, fov, texture, depth, size, fresh, view, projection, lens, viewport, near, far, null);
        }

        Frame(String key, String kind, Object weapon, float fov, int texture, int depth, int size, boolean fresh,
                Matrix4f view, Matrix4f projection, float[] lens, int[] viewport, float near, float far, float[] opticalOrigin) {
            this(key, kind, weapon, fov, texture, depth, size, fresh, view, projection, lens, viewport, near, far,
                    opticalOrigin, null, null);
        }

        Frame(String key, String kind, Object weapon, float fov, int texture, int depth, int size, boolean fresh,
                Matrix4f view, Matrix4f projection, float[] lens, int[] viewport, float near, float far, float[] opticalOrigin,
                float[] boundary, float[] depthSlope) {
            this.key = key; this.kind = kind; this.weapon = weapon; this.fov = fov;
            this.texture = texture; this.depth = depth; this.size = size; this.fresh = fresh;
            this.view = new Matrix4f(view); this.projection = new Matrix4f(projection);
            this.lens = lens.clone(); this.viewport = viewport.clone(); this.near = near; this.far = far;
            this.opticalOrigin = opticalOrigin == null ? new float[] {lens[0], lens[1]} : opticalOrigin.clone();
            imageOrigin = opticalOrigin == null ? new float[2] : ScopePipComposite.offset(this.lens, this.opticalOrigin);
            this.boundary = boundary == null ? null : boundary.clone();
            this.depthSlope = depthSlope == null ? new float[2] : depthSlope.clone();
        }
    }

    /** Our world content drawn into the scope image; called only when it was re-rendered (Frame.fresh). */
    public interface Scene {
        void draw(Frame frame) throws Throwable;
    }

    /** Night vision / thermal over the scope image. Returns the texture to composite (0: the scope image unchanged). */
    public interface Sensor {
        int process(Frame frame) throws Throwable;
    }

    /** Drawn after the composite with the main draw framebuffer and viewport bound. */
    public interface Overlay {
        void draw(Frame frame) throws Throwable;
    }

    public static volatile Scene scene;
    public static volatile Sensor sensor;
    public static volatile Overlay overlay;
    public static volatile long sceneErrors, sensorErrors, overlayErrors;

    static void scene(Frame frame) {
        Scene s = scene;
        if (s == null || !frame.fresh) {
            return;
        }
        try (PerfProbe.Scope perf = PerfProbe.render(PerfProbe.PIP_EFFECTS)) {
            s.draw(frame);
        } catch (Throwable t) {
            scene = null;
            sceneErrors++;
            System.out.println("[TW] scope scene stage off: " + t);
        }
    }

    static int sensor(Frame frame) {
        Sensor s = sensor;
        if (s == null) {
            return 0;
        }
        try (PerfProbe.Scope perf = PerfProbe.render(PerfProbe.SENSOR_SCOPE)) {
            return s.process(frame);
        } catch (Throwable t) {
            sensor = null;
            sensorErrors++;
            System.out.println("[TW] scope sensor off: " + t);
            return 0;
        }
    }

    static void overlay(Frame frame) {
        Overlay o = overlay;
        if (o == null) {
            return;
        }
        try (PerfProbe.Scope perf = PerfProbe.render(PerfProbe.RETICLE)) {
            o.draw(frame);
        } catch (Throwable t) {
            overlay = null;
            overlayErrors++;
            System.out.println("[TW] scope overlay off: " + t);
        }
    }
}
