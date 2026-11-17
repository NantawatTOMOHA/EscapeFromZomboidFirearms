package pz.escapefromzomboidfirearms.zb;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * The corners of our full-screen passes as vertex data (macOS, 02.11.2026): the triangle of the scope picture, its
 * history and the camera zoom's eyepiece (tw_pip_composite, tw_pip_history, tw_zoom_mask) and the strips of the device
 * images and the frosted backdrop (tw_vp_sensor_image, tw_vp_sensor_mean, tw_frost_*) built their corners from
 * gl_VertexID's bits, which PZ's GL 2.1 context (GLSL 1.20) does not have. Vertex i here holds the same bits,
 * (i & 1, i >> 1 & 1), as attribute 0 (aCorner): the shaders make exactly the corners they made before.
 *
 * Player report 03.11.2026 (every thermal and night vision picture black since 02.11): an array of ours stays bound
 * between a pass's setup and its draw, and what is drawn in between writes the attributes of whatever array is bound -
 * the game's model draws of the thermal heat mask (ModelHeatViewpoint, VertexBufferObject.Draw: glVertexAttribPointer,
 * then FinishDraw's glDisableVertexAttribArray). Attribute 0 of ours then read (0, 0) at every vertex, the strips had no
 * area, and every device image stayed empty until the game restarted. Our passes bind their arrays only through bind(),
 * which writes attribute 0 again each time (tools/check_screen_corners.py).
 *
 * Player log 03.11.2026 (macOS, VPMetal): the corners were uploaded with glBufferData(int, float[], int), an overload
 * the Metal shim does not have ("not implemented, ignored"); they go up as a FloatBuffer, Viewpoint's own overload.
 */
final class ScreenCorners {
    /** Vertices 0..3: (0, 0), (1, 0), (0, 1), (1, 1). */
    private static final float[] CORNERS = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};
    /** One buffer for every vertex array (render thread, one context). */
    private static int buffer;

    private ScreenCorners() {
    }

    /**
     * Render thread: attribute 0 of our own vertex array reads the corners (the array keeps it for every later draw).
     * The bound vertex array and ARRAY_BUFFER are as before after it.
     */
    static void attach(int vertexArray) {
        int previousArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        try {
            if (buffer == 0) {
                buffer = GL15.glGenBuffers();
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, BufferUtils.createFloatBuffer(CORNERS.length).put(CORNERS).flip(),
                        GL15.GL_STATIC_DRAW);
            }
            GL30.glBindVertexArray(vertexArray);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 0, 0L);
            GL20.glEnableVertexAttribArray(0);
        } finally {
            GL30.glBindVertexArray(previousArray);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer);
        }
    }

    /**
     * Render thread, before a draw of ours: binds the vertex array (left bound, as glBindVertexArray did) with attribute 0
     * reading the corners again, whatever was drawn while it was bound. ARRAY_BUFFER is as before after it.
     */
    static void bind(int vertexArray) {
        GL30.glBindVertexArray(vertexArray);
        int previousBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 0, 0L);
        GL20.glEnableVertexAttribArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer);
    }

    /** A new vertex array with the corners attached. */
    static int vertexArray() {
        int vertexArray = GL30.glGenVertexArrays();
        attach(vertexArray);
        return vertexArray;
    }
}
