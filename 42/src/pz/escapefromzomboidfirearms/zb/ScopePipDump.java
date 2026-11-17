package pz.escapefromzomboidfirearms.zb;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Opt-in, one-shot raw PIP colour/depth capture. No waiting on an unfinished GPU read. */
final class ScopePipDump {
    private record Request(Path folder, String name) { }
    private static volatile Request requested;
    private static volatile boolean busy;
    private static volatile String status = "idle";
    private static volatile long fence;
    private static int pbo, readFbo, size;
    private static Request active;
    private static Matrix4f projection;
    private static String details;

    private ScopePipDump() { }

    static String request(String name) {
        return request(Path.of(zombie.ZomboidFileSystem.instance.getScreenshotDir()), name);
    }

    static synchronized String request(Path folder, String name) {
        if (busy) { return "busy " + status; }
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,64}")) { return "invalid name"; }
        requested = new Request(folder, name);
        busy = true;
        status = "requested:" + name;
        return status;
    }

    static boolean needsPoll() { return fence != 0; }
    static boolean wanted() { return requested != null; }
    static String info() { return " dump=" + status; }

    static synchronized void cancelPending() {
        if (requested != null) {
            status = "cancelled:" + requested.name;
            requested = null;
            busy = false;
        }
    }

    /** All pixels are read once into a PBO; mapping happens after a zero-timeout fence poll. */
    static void capture(int colourFbo, int depth, int side, Matrix4f matrix, String metadata) {
        Request r = requested;
        if (r == null || fence != 0) { return; }
        requested = null;
        int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int pack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int[] keys = {GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_PIXELS,
                GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SWAP_BYTES, GL11.GL_PACK_LSB_FIRST};
        int[] saved = new int[keys.length];
        for (int i = 0; i < keys.length; i++) { saved[i] = GL11.glGetInteger(keys[i]); }
        try {
            active = r;
            size = side;
            projection = new Matrix4f(matrix);
            details = metadata;
            if (pbo == 0) { pbo = GL15.glGenBuffers(); readFbo = GL30.glGenFramebuffers(); }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (long) side * side * 8, GL15.GL_STREAM_READ);
            for (int i = 0; i < keys.length; i++) { GL11.glPixelStorei(keys[i], i == 0 ? 1 : 0); }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, colourFbo);
            if (GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("colour target incomplete");
            }
            GL11.glReadPixels(0, 0, side, side, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, readFbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, depth, 0);
            GL11.glReadBuffer(GL11.GL_NONE);
            GL11.glDrawBuffer(GL11.GL_NONE);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("depth target incomplete");
            }
            GL11.glReadPixels(0, 0, side, side, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (long) side * side * 4);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, 0, 0);
            fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            if (fence == 0) { throw new IllegalStateException("fence unavailable"); }
            status = "gpu:" + r.name;
        } catch (Throwable t) {
            failed(t);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pack);
            for (int i = 0; i < keys.length; i++) { GL11.glPixelStorei(keys[i], saved[i]); }
        }
    }

    static void poll() {
        if (fence == 0) { return; }
        int state = GL32.glClientWaitSync(fence, 0, 0L);
        if (state == GL32.GL_TIMEOUT_EXPIRED) { return; }
        GL32.glDeleteSync(fence);
        fence = 0;
        if (state == GL32.GL_WAIT_FAILED) { failed(new IllegalStateException("fence failed")); return; }
        int pack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            ByteBuffer bytes = BufferUtils.createByteBuffer(size * size * 8);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
            GL15.glGetBufferSubData(GL21.GL_PIXEL_PACK_BUFFER, 0L, bytes);
            byte[] data = new byte[bytes.remaining()];
            bytes.get(data);
            Request r = active;
            int side = size;
            Matrix4f matrix = projection;
            String metadata = details;
            status = "writing:" + r.name;
            Thread writer = new Thread(() -> {
                try { write(r.folder, r.name, side, data, matrix, metadata); status = "saved:" + r.name; }
                catch (Throwable t) { failed(t); }
                finally { busy = false; }
            }, "TW PIP dump");
            writer.setDaemon(true);
            writer.start();
        } catch (Throwable t) {
            failed(t);
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pack);
        }
    }

    private static void failed(Throwable t) {
        status = "failed:" + t.getClass().getSimpleName() + ":" + t.getMessage();
        busy = false;
    }

    static double linearMetres(float depth, Matrix4f p) {
        return p.m32() / (depth * 2. - 1. + p.m22()) / ScopePipCamera.SCENE_UNITS_PER_METRE;
    }

    /** 16-bit linear axial depth: 1 count = 1 mm; 65535 = sky; 65534 = saturation. */
    static void write(Path folder, String name, int side, byte[] bytes, Matrix4f p, String metadata) throws Exception {
        BufferedImage colour = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        BufferedImage depth = new BufferedImage(side, side, BufferedImage.TYPE_USHORT_GRAY);
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        int pixels = side * side;
        double min = Double.POSITIVE_INFINITY, max = 0;
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                int i = y * side + x, at = i * 4;
                colour.setRGB(x, side - 1 - y, (bytes[at] & 255) << 16 | (bytes[at + 1] & 255) << 8 | bytes[at + 2] & 255);
                float z = data.getFloat(pixels * 4 + at);
                int value = 65535;
                if (Float.isFinite(z) && z < 1) {
                    double m = linearMetres(z, p);
                    if (Double.isFinite(m) && m >= 0) {
                        min = Math.min(min, m); max = Math.max(max, m);
                        value = (int) Math.min(65534, Math.round(m * 1000));
                    }
                }
                depth.getRaster().setSample(x, side - 1 - y, 0, value);
            }
        }
        Files.createDirectories(folder);
        String stem = "TW_PIP_" + name;
        ImageIO.write(colour, "png", folder.resolve(stem + "_colour.png").toFile());
        ImageIO.write(depth, "png", folder.resolve(stem + "_depth_mm.png").toFile());
        Files.writeString(folder.resolve(stem + ".txt"), "stage=raw-scene-post-before-history-sensors-reticle\n"
                + "size=" + side + "x" + side + "\ndepth=linear axial metres; uint16 millimetres; sky=65535; saturation=65534\n"
                + "minDepthM=" + min + " maxDepthM=" + max
                + "\nprojectionColumnMajor=" + java.util.Arrays.toString(p.get(new float[16])) + "\n" + metadata + "\n");
    }
}
