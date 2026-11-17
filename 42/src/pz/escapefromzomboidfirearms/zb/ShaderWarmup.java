package pz.escapefromzomboidfirearms.zb;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import zombie.core.ShaderHelper;
import zombie.core.opengl.RenderThread;
import zombie.core.opengl.ShaderProgram;
import zombie.core.skinnedmodel.shader.Shader;

/**
 * The user's choice (23.10.2026, "do the shader warm-up"): the mod's GL programs are compiled once when the world
 * starts, on the render thread, not at their first use (the first shot, ADS, laser or light beam, night vision or
 * thermal; 10-100 ms each on a fresh driver cache). Each class's own lazy creation runs (its shaders()), so the first
 * use finds the program made exactly as it would have made it; the current program is restored after (a model Shader
 * binds its program to read its uniforms). Viewpoint's programs only while Viewpoint is there. QA: disabled - set
 * before the world starts - keeps the first use as before.
 *
 * macOS (02.11.2026): PZ's createShaderProgram never throws - a program that does not compile is only "not compiled" -
 * so the warm-up reported failed=0 while 15 programs had not compiled on the player's GL 2.1 context. Every program
 * our classes create is registered here (made(); tools/check_shader_gl21.py checks that each creation is) and the
 * warm-up counts the ones that did not compile, by name, with one line of the GL environment at its start and the
 * context's details at the first failure.
 */
public final class ShaderWarmup {
    public static volatile boolean disabled;
    private static volatile boolean queued;
    private static volatile String report = "idle";
    /** Every program our classes made: {label, whether it compiled} (render thread; read by the warm-up). */
    private static final List<Object[]> programs = new ArrayList<>();

    private ShaderWarmup() {
    }

    /** Render thread: a program one of our classes made, registered for the warm-up's check; returns it. */
    static ShaderProgram made(ShaderProgram program) {
        if (program != null) {
            synchronized (programs) {
                programs.add(new Object[] {program.getName(), (BooleanSupplier) program::isCompiled});
            }
        }
        return program;
    }

    /** Render thread: a program of Viewpoint's passes one of our classes made, registered as made(ShaderProgram); returns it. */
    static TwProgram made(TwProgram program) {
        if (program != null) {
            synchronized (programs) {
                programs.add(new Object[] {program.getName(), (BooleanSupplier) program::isCompiled});
            }
        }
        return program;
    }

    /** Render thread: a model shader one of our classes made (its program registered as made(ShaderProgram)); returns it. */
    static Shader made(Shader shader) {
        if (shader != null) {
            synchronized (programs) {
                ShaderProgram program = shader.getShaderProgram();
                programs.add(new Object[] {shader.getName() + (shader.isStatic() ? "_static" : ""),
                        (BooleanSupplier) () -> program != null && program.isCompiled()});
            }
        }
        return shader;
    }

    /** The registered programs that did not compile, by label (render thread). */
    static List<String> notCompiled() {
        List<String> failed = new ArrayList<>();
        synchronized (programs) {
            for (Object[] p : programs) {
                if (!((BooleanSupplier) p[1]).getAsBoolean()) {
                    failed.add((String) p[0]);
                }
            }
        }
        return failed;
    }

    /** Main thread (OnGameStart): once per game process; the render thread runs it before its next frame. */
    public static void queue() {
        if (disabled || queued) {
            report = disabled ? "disabled" : report;
            return;
        }
        queued = true;
        report = "queued";
        RenderThread.queueInvokeOnRenderContext(ShaderWarmup::run);
    }

    private interface Step {
        void run() throws Throwable;
    }

    private static final class Log {
        final StringBuilder out = new StringBuilder();
        int failed;
        double total;

        void step(String name, Step step) {
            long start = System.nanoTime();
            try {
                step.run();
            } catch (Throwable t) {
                // The first use meets the same failure and reports it as before.
                failed++;
                out.append(' ').append(name).append("=failed(").append(t.getClass().getSimpleName()).append(')');
                return;
            }
            double ms = (System.nanoTime() - start) / 1e6;
            total += ms;
            out.append(' ').append(name).append('=').append(String.format(Locale.ROOT, "%.1f", ms));
        }
    }

    private static void run() {
        int previous = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        Log log = new Log();
        String environment = GLCompat.environment();
        System.out.println("[TWZB] GL environment: " + environment);
        try {
            log.step("muzzle", MuzzleFx::shaders);
            log.step("barrelGlow", MuzzleHeatModel::shaderIso);
            log.step("heatModel", ModelHeat::shaders);
            log.step("heat", HeatRenderer::shaders);
            log.step("vehicleHeat", VehicleHeat::shaders);
            log.step("optic", OpticRenderer::shaders);
            log.step("reticle", ReticleRenderer::shaders);
            log.step("frost", FrostedBackdrop::shaders);
            boolean viewpoint = ViewpointBridge.detect();
            if (viewpoint) {
                log.step("vpPass", ViewpointPass::shaders);
                log.step("vpMuzzle", ViewpointPass::muzzleShaders);
                log.step("vpBarrelGlow", MuzzleHeatModel::shaderVp);
                log.step("vpHeatModel", ModelHeatViewpoint::shaders);
                log.step("vpSensor", VpSensorGL::shaders);
                log.step("vpSensorHeat", VpHeat::shaders);
                log.step("pip", ScopePip::warm);
                log.step("rangefinder", ReticleRendererRangefinder::shaders);
                log.step("seeThrough", SeeThrough::shaders);
            }
            List<String> failed = notCompiled();
            if (!failed.isEmpty()) {
                System.out.println("[TWZB] shader warm-up: " + failed.get(0) + " did not compile (" + failed.size()
                        + " programs in all); " + environment);
            }
            int made;
            synchronized (programs) {
                made = programs.size();
            }
            report = String.format(Locale.ROOT, "done viewpoint=%b totalMs=%.1f failed=%d programs=%d", viewpoint, log.total,
                    log.failed + failed.size(), made) + log.out + (failed.isEmpty() ? "" : " notCompiled=" + String.join(",", failed));
        } catch (Throwable t) {
            report = "error " + t;
        } finally {
            ShaderHelper.forgetCurrentlyBound();
            GLCompat.useProgram(previous);
        }
        System.out.println("[TWZB] shader warm-up " + report);
        if (GLCompat.metalShim()) {
            // The shim's own count: programs of ours that its link refused name themselves in TwProgram.
            try {
                System.out.println("[TWZB] shim programs: " + Class.forName("vpshim.metal.Programs", true, GLCompat.class.getClassLoader())
                        .getMethod("stats").invoke(null));
            } catch (Throwable t) {
                System.out.println("[TWZB] shim programs: unreadable: " + t);
            }
        }
    }

    public static String info() {
        return report;
    }
}
