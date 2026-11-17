package pz.escapefromzomboidfirearms.zb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

/** Scene-relative relief interaction and opt-in, read-only world diagnostics. */
final class ScopePipSceneState implements AutoCloseable {
    private final Object drawing = ViewpointPipBridge.get("FarRelief.drawing", null);
    private final Object onUse = ViewpointPipBridge.get("viewpoint.platform.GlProgram.onUse", null);
    private boolean applied;

    ScopePipSceneState() throws Throwable {
    }

    void apply(Object scene) throws Throwable {
        applied = true; // Also restore when the invocation itself fails.
        ViewpointPipBridge.call("FarRelief.frame", null, scene);
    }

    @Override
    public void close() throws Exception {
        if (!applied) { return; }
        Throwable failure = null;
        try { ViewpointPipBridge.put("FarRelief.drawing", null, drawing); } catch (Throwable t) { failure = t; }
        try { ViewpointPipBridge.put("viewpoint.platform.GlProgram.onUse", null, onUse); } catch (Throwable t) { failure = t; }
        try { ViewpointPipBridge.put("FarRelief.bound", null, -1); } catch (Throwable t) { failure = t; }
        if (failure != null) { throw new Exception("PIP relief restore failed", failure); }
    }

    static String matrix(Matrix4f value) {
        return Arrays.toString(value.get(new float[16])).replace(" ", "");
    }

    static float difference(Matrix4f a, Matrix4f b) {
        float[] x = a.get(new float[16]), y = b.get(new float[16]);
        float maximum = 0;
        for (int i = 0; i < 16; i++) { maximum = Math.max(maximum, Math.abs(x[i] - y[i])); }
        return maximum;
    }

    /** No GL calls: usable on generated contexts with nonzero scene origins. */
    static String fields(Object context) throws Throwable {
        Object scene = ViewpointPipBridge.get("FrameContext.scene", context);
        StringBuilder out = new StringBuilder("origin=");
        for (String key : new String[] {"originX", "originY", "originZ"}) {
            out.append(ViewpointPipBridge.get("SceneData." + key, scene)).append(',');
        }
        out.append(" reliefDrawingMatchesScene=").append(ViewpointPipBridge.get("FarRelief.drawing", null) == scene)
                .append(" reliefBound=").append(ViewpointPipBridge.get("FarRelief.bound", null));
        Object lift = ViewpointPipBridge.get("SceneData.relief", scene);
        out.append(" relief=").append(lift == null ? "none" : "present");
        if (lift != null) {
            for (String key : new String[] {"levels", "ground", "camX", "camY"}) {
                out.append(' ').append(key).append('=').append(ViewpointPipBridge.call("FarRelief$Lift." + key, lift));
            }
        }
        for (String key : new String[] {"eyeX", "eyeY", "eyeZ", "farNear", "farFar", "jitterX", "jitterY"}) {
            out.append(' ').append(key).append('=').append(ViewpointPipBridge.get("FrameContext." + key, context));
        }
        for (String key : new String[] {"view", "jitteredProjection", "viewProjection", "plainViewProjection", "farInvPlainViewProjection"}) {
            out.append(' ').append(key).append("ColumnMajor=")
                    .append(matrix((Matrix4f) ViewpointPipBridge.get("FrameContext." + key, context)));
        }
        return out.toString();
    }

    /** One-shot only. Read actual program uniforms without use(), setters or shader sources. */
    static String capture(Object context) throws Throwable {
        List<Object> programs = new ArrayList<>();
        ViewpointPipBridge.call("viewpoint.platform.GlProgram.forEach", null, (Consumer<Object>) programs::add);
        StringBuilder out = new StringBuilder(fields(context)).append(" programs=[");
        for (Object program : programs) {
            int id = (int) ViewpointPipBridge.call("viewpoint.platform.GlProgram.id", program);
            if (id == 0 || GL20.glGetUniformLocation(id, "uReliefGrid") < 0) { continue; }
            out.append('{').append(ViewpointPipBridge.get("viewpoint.platform.GlProgram.name", program)).append('#').append(id);
            out.append(uniform(id, "uReliefGrid", 4)).append(uniform(id, "uReliefCamera", 2))
                    .append(uniform(id, "uSceneOrigin", 3)).append(uniform(id, "uEye", 3))
                    .append(uniform(id, "uViewProjection", 16));
            for (String key : new String[] {"uRelief", "uReliefWater", "uReliefWalls", "uReliefFlow"}) {
                out.append(sampler(id, key));
            }
            out.append('}');
        }
        return out.append(']').toString();
    }

    static String uniform(int program, String name, int components) {
        int location = GL20.glGetUniformLocation(program, name);
        if (location < 0) { return " " + name + "=absent"; }
        // A shader pack can change a uniform's type. Reserve the largest GLSL
        // matrix instead of letting a vector/matrix overrun a smaller buffer.
        float[] values = new float[16];
        GL20.glGetUniformfv(program, location, values);
        return " " + name + '=' + Arrays.toString(Arrays.copyOf(values, components)).replace(" ", "");
    }

    private static String sampler(int program, String name) {
        int location = GL20.glGetUniformLocation(program, name);
        if (location < 0) { return " " + name + "=absent"; }
        int[] values = new int[16];
        GL20.glGetUniformiv(program, location, values);
        int unit = values[0];
        if (unit < 0 || unit >= GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS)) {
            return " " + name + "=invalid-unit:" + unit;
        }
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            int sampler = GLCompat.sampler(unit);
            return " " + name + '=' + unit + "@texture:" + GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
                    + "@sampler:" + (sampler >= 0 ? String.valueOf(sampler) : "unreadable");
        } finally { GL13.glActiveTexture(active); }
    }
}
