package pz.escapefromzomboidfirearms.zb;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** Bounded render-thread scratch, never visibility or GPU data from an older frame. */
final class ScopePipCpu {
    private static final int MAX_LENGTH = 1 << 20;
    static boolean enabled = !Boolean.getBoolean("tw.pip.cpuBaseline");
    private static volatile String status = enabled ? "on" : "baseline";
    private static final Geometry geometry = new Geometry();
    private static int[] flags;
    private static boolean flagsBusy;
    private static Context context;

    private ScopePipCpu() { }

    static void fail(Throwable failure) {
        enabled = false;
        if (!status.startsWith("off:")) {
            status = "off:" + failure.getClass().getSimpleName();
            System.out.println("[TWZB] scope CPU cache " + status);
        }
    }

    static String info() { return "cpuCache=" + status; }

    static Geometry geometry(float[] fade, int[] plan, int count) {
        if (!enabled || geometry.busy || fade.length > MAX_LENGTH || plan.length > MAX_LENGTH) return null;
        try {
            if (geometry.fade == null || geometry.fade.length != fade.length || geometry.plan.length != plan.length) {
                geometry.fade = new float[fade.length];
                geometry.plan = new int[plan.length];
                geometry.count = -1;
            }
            if (geometry.count != count) {
                for (int i = 0; i < count; i++) {
                    geometry.fade[i * 2] = 0;
                    geometry.fade[i * 2 + 1] = 1;
                }
                Arrays.fill(geometry.plan, 0, count, -1);
                geometry.count = count;
            }
            // Keep unused capacity byte-for-byte equivalent to the old clones,
            // including when counts shrink or another snapshot replaces them.
            System.arraycopy(fade, count * 2, geometry.fade, count * 2, fade.length - count * 2);
            System.arraycopy(plan, count, geometry.plan, count, plan.length - count);
            geometry.busy = true;
            return geometry;
        } catch (Throwable t) { fail(t); return null; }
    }

    static int[] flags(int[] original) {
        if (!enabled || flagsBusy || original.length > MAX_LENGTH) return null;
        try {
            if (flags == null || flags.length != original.length) flags = new int[original.length];
            System.arraycopy(original, 0, flags, 0, original.length);
            flagsBusy = true;
            return flags;
        } catch (Throwable t) { fail(t); return null; }
    }

    static void release(int[] borrowed) { if (borrowed == flags) flagsBusy = false; }

    static Context context(Object owner) {
        if (!enabled || context != null && context.busy) return null;
        try {
            if (context == null) context = new Context();
            context.capture(owner);
            return context;
        } catch (Throwable t) {
            if (context != null) context.release();
            fail(t);
            return null;
        }
    }

    static final class Geometry {
        float[] fade;
        int[] plan;
        int count = -1;
        boolean busy;

        void release() { busy = false; }
    }

    /** Schema uses only members already admitted by ViewpointPipBridge.detect. */
    static final class Context {
        final Field[] fields;
        final Object[] values;
        final boolean[] inPlace;
        boolean busy;

        Context() {
            ArrayList<Field> schema = new ArrayList<>();
            for (String[] row : ViewpointPipBridge.rows()) {
                if (row[0].equals("viewpoint.render.FrameContext") && !row[1].equals("<init>")) {
                    Field f = ViewpointPipBridge.field(row[0] + "." + row[1]);
                    if (f == null) throw new IllegalStateException("unadmitted context member");
                    schema.add(f);
                }
            }
            fields = schema.toArray(Field[]::new);
            values = new Object[fields.length];
            inPlace = new boolean[fields.length];
            for (int i = 0; i < fields.length; i++) inPlace[i] = Modifier.isFinal(fields[i].getModifiers());
        }

        void capture(Object owner) throws IllegalAccessException {
            for (int i = 0; i < fields.length; i++) {
                Object value = fields[i].get(owner);
                if (!inPlace[i]) values[i] = value;
                else if (value instanceof Matrix4f m) {
                    if (!(values[i] instanceof Matrix4f)) values[i] = new Matrix4f();
                    ((Matrix4f) values[i]).set(m);
                } else if (value instanceof Matrix3f m) {
                    if (!(values[i] instanceof Matrix3f)) values[i] = new Matrix3f();
                    ((Matrix3f) values[i]).set(m);
                } else if (value instanceof int[] a) {
                    if (!(values[i] instanceof int[] b) || b.length != a.length) values[i] = new int[a.length];
                    System.arraycopy(a, 0, values[i], 0, a.length);
                } else if (value instanceof float[] a) {
                    if (!(values[i] instanceof float[] b) || b.length != a.length) values[i] = new float[a.length];
                    System.arraycopy(a, 0, values[i], 0, a.length);
                } else if (fields[i].getType().getName().equals("viewpoint.render.CarriedEffects")) {
                    // Keep each FrameContext's own effects object and its own mutable buffers.
                    values[i] = null;
                } else throw new IllegalStateException("unreviewed final context value " + fields[i]);
            }
            busy = true;
        }

        void copyTo(Object destination) throws Throwable {
            Throwable failure = null;
            for (int i = 0; i < fields.length; i++) {
                try {
                    Object value = values[i];
                    if (!inPlace[i]) fields[i].set(destination, value);
                    else if (value instanceof Matrix4f m) ((Matrix4f) fields[i].get(destination)).set(m);
                    else if (value instanceof Matrix3f m) ((Matrix3f) fields[i].get(destination)).set(m);
                    else if (value instanceof int[] a) System.arraycopy(a, 0, fields[i].get(destination), 0, a.length);
                    else if (value instanceof float[] a) System.arraycopy(a, 0, fields[i].get(destination), 0, a.length);
                    // null is the deliberate no-copy marker for CarriedEffects.
                } catch (Throwable t) { failure = t; }
            }
            if (failure != null) throw failure;
        }

        void release() {
            for (int i = 0; i < fields.length; i++) if (!inPlace[i]) values[i] = null;
            busy = false;
        }
    }
}
