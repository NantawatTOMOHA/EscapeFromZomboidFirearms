package pz.escapefromzomboidfirearms.zb;

import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL20;

import zombie.ZomboidFileSystem;
import zombie.core.opengl.ShaderProgram;

/**
 * A GL program of the passes that draw inside Viewpoint's frame. Where the game's own OpenGL draws Viewpoint it is PZ's
 * ShaderProgram, call for call. Under the Viewpoint Metal shim (VPMetal) it is a program of the shim's.
 *
 * Player log 04.11.2026 (1.1.4, macOS, every scope picture missing): the shim rewrites the GL calls of our classes, not of
 * PZ's (vpshim.Rewriter: "zombie/" is no add-on). A ShaderProgram compiles and starts on the real GL context, so the
 * shim's current program stayed Viewpoint's or none, and its draw call (GlDraw.draw) dropped our draws without a word:
 * "if (linked == null || uniforms == null) return". Shaders "compiled" (failed=0 programs=30), and nothing was drawn.
 * Here the same files compile through the rewritten GL20 (TwShaderText: the shim's dialect), as Viewpoint's and
 * ProjectViewpointADS's programs do.
 *
 * The setters keep ShaderProgram's rule: a uniform that is missing or of another declared type is left alone.
 */
final class TwProgram {
    private static final Pattern UNIFORM = Pattern.compile("(?m)^\\s*uniform\\s+(?:(?:lowp|mediump|highp)\\s+)?(\\w+)\\s+([^;]+);");

    private record Slot(String type, int location) {
    }

    private final String name;
    /** PZ's program, or null under the shim. */
    private final ShaderProgram real;
    private int id;
    private boolean compiled;
    private final Map<String, Slot> slots = new HashMap<>();
    private final FloatBuffer matrix = BufferUtils.createFloatBuffer(16);

    private TwProgram(String name, ShaderProgram real) {
        this.name = name;
        this.real = real;
    }

    /** Render thread: the program of media/shaders/name.vert and name.frag. */
    static TwProgram create(String name) {
        if (!GLCompat.metalPath()) {
            return new TwProgram(name, ShaderProgram.createShaderProgram(name, false, false, true));
        }
        TwProgram program = new TwProgram(name, null);
        program.link();
        return program;
    }

    private void link() {
        try {
            String vertex = read(".vert"), fragment = read(".frag");
            String vertexText = TwShaderText.forShim(vertex, false), fragmentText = TwShaderText.forShim(fragment, true);
            int program = GL20.glCreateProgram();
            int vs = shader(GL20.GL_VERTEX_SHADER, vertexText);
            int fs = shader(GL20.GL_FRAGMENT_SHADER, fragmentText);
            GL20.glAttachShader(program, vs);
            GL20.glAttachShader(program, fs);
            GL20.glLinkProgram(program);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
                System.out.println("[TWZB] shim program " + name + " did not link: " + GL20.glGetProgramInfoLog(program));
                return;
            }
            if (!linkedByShim(program, vertexText, fragmentText)) {
                return;
            }
            id = program;
            compiled = true;
            uniforms(vertex);
            uniforms(fragment);
        } catch (Throwable t) {
            System.out.println("[TWZB] shim program " + name + " not made: " + t);
        }
    }

    /**
     * The shim answers GL_LINK_STATUS 1 for any program and keeps its error to its own trace (vpshim.gl.Gl.glGetProgrami,
     * glLinkProgram): its own table says whether the program is on Metal, and the error goes to the log.
     */
    private boolean linkedByShim(int program, String vertexText, String fragmentText) {
        if (!GLCompat.metalShim()) {
            return true;
        }
        try {
            Class<?> programs = Class.forName("vpshim.metal.Programs", true, GLCompat.class.getClassLoader());
            if (programs.getMethod("get", int.class).invoke(null, program) != null) {
                return true;
            }
            Object error = programs.getMethod("link", int.class, String.class, String.class).invoke(null, program, vertexText, fragmentText);
            System.out.println("[TWZB] shim program " + name + " did not link: " + error);
            return false;
        } catch (Throwable t) {
            // A shim of another version: its link status stands.
            System.out.println("[TWZB] shim program " + name + ": the shim's table cannot be read: " + t);
            return true;
        }
    }

    private String read(String extension) throws java.io.IOException {
        String file = ZomboidFileSystem.instance.getString("media/shaders/" + name + extension);
        return Files.readString(Path.of(file), StandardCharsets.UTF_8);
    }

    private static int shader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        return shader;
    }

    private void uniforms(String source) {
        Matcher m = UNIFORM.matcher(source);
        while (m.find()) {
            String type = m.group(1);
            for (String declared : m.group(2).split(",")) {
                String uniform = declared.split("=")[0].trim();
                int bracket = uniform.indexOf('[');
                if (bracket >= 0) {
                    continue; // arrays go by element (GLCompat.elements, ScopePipGL.Uniforms)
                }
                slots.putIfAbsent(uniform, new Slot(type, GL20.glGetUniformLocation(id, uniform)));
            }
        }
    }

    private int location(String uniform, String type) {
        Slot slot = slots.get(uniform);
        return slot == null || !type.equals(slot.type) ? -1 : slot.location;
    }

    /** A program of PZ's as it is (the standalone GL fixtures of the gates). */
    static TwProgram wrap(ShaderProgram real) {
        return new TwProgram(real.getName(), real);
    }

    /** The locations of the names of u for this program's current link. */
    int[] locations(ScopePipGL.Uniforms u) {
        return real != null ? u.of(real) : u.of(id);
    }

    /** PZ's program, for a compile listener; null under the shim, where a program never relinks. */
    ShaderProgram real() {
        return real;
    }

    String getName() {
        return name;
    }

    boolean isCompiled() {
        return real != null ? real.isCompiled() : compiled;
    }

    int getShaderID() {
        return real != null ? real.getShaderID() : id;
    }

    void Start() {
        if (real != null) {
            real.Start();
        } else {
            GLCompat.useProgram(id);
        }
    }

    void End() {
        if (real != null) {
            real.End();
        } else {
            GLCompat.useProgram(0);
        }
    }

    void setSamplerUnit(String uniform, int unit) {
        if (real != null) {
            real.setSamplerUnit(uniform, unit);
            return;
        }
        int at = location(uniform, "sampler2D");
        if (at >= 0) {
            GL20.glUniform1i(at, unit);
        }
    }

    void setValue(String uniform, float value) {
        if (real != null) {
            real.setValue(uniform, value);
            return;
        }
        int at = location(uniform, "float");
        if (at >= 0) {
            GL20.glUniform1f(at, value);
        }
    }

    void setValue(String uniform, int value) {
        if (real != null) {
            real.setValue(uniform, value);
            return;
        }
        int at = location(uniform, "int");
        if (at >= 0) {
            GL20.glUniform1i(at, value);
        }
    }

    void setValue(String uniform, Matrix4f value) {
        if (real != null) {
            real.setValue(uniform, value);
            return;
        }
        int at = location(uniform, "mat4");
        if (at >= 0) {
            matrix.clear();
            value.get(matrix);
            matrix.limit(16);
            GL20.glUniformMatrix4fv(at, false, matrix);
        }
    }

    void setVector2(String uniform, float x, float y) {
        if (real != null) {
            real.setVector2(uniform, x, y);
            return;
        }
        int at = location(uniform, "vec2");
        if (at >= 0) {
            GL20.glUniform2f(at, x, y);
        }
    }

    void setVector3(String uniform, float x, float y, float z) {
        if (real != null) {
            real.setVector3(uniform, x, y, z);
            return;
        }
        int at = location(uniform, "vec3");
        if (at >= 0) {
            GL20.glUniform3f(at, x, y, z);
        }
    }

    void setVector4(String uniform, float x, float y, float z, float w) {
        if (real != null) {
            real.setVector4(uniform, x, y, z, w);
            return;
        }
        int at = location(uniform, "vec4");
        if (at >= 0) {
            GL20.glUniform4f(at, x, y, z, w);
        }
    }
}
