package pz.escapefromzomboidfirearms.zb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * The text of one of our shaders in the dialect the Viewpoint Metal shim compiles (VPMetal 3823866661: glslang to SPIR-V
 * to Metal, as Viewpoint's own "#version 330" shaders with in/out and texture()). Our files are written in the GLSL 1.20
 * dialect of PZ's shaders (gl_FragColor, gl_FragData, texture2D: tools/check_shader_gl21.py), which a SPIR-V target does
 * not have. Nothing but these names changes, so a shader computes the same picture on either path.
 *
 * The shim gives varyings their locations with a regular expression over whole lines, "in|out type name;" and nothing
 * after the semicolon (libvpmetal.dylib): a comment or a carriage return at the end of the line leaves the varying without
 * a location, and the program does not link. So the text goes without comments, in LF lines, and with the branch of a
 * "#if __VERSION__" that #version 330 compiles.
 *
 * Pure text, no GL and no game classes: tools/check_vpmetal_programs.py runs main over every shader of the mod and
 * compiles the result with the same glslang.
 */
final class TwShaderText {
    private static final Pattern VERSION = Pattern.compile("#version[^\\r\\n]*");
    private static final Pattern FRAG_DATA = Pattern.compile("\\bgl_FragData\\s*\\[\\s*(\\d+)\\s*\\]");
    private static final Pattern FRAG_COLOR = Pattern.compile("\\bgl_FragColor\\b");
    private static final Pattern TEXTURE_LOD = Pattern.compile("\\btexture(?:2D|Cube)Lod\\s*\\(");
    private static final Pattern TEXTURE = Pattern.compile("\\btexture(?:2D|Cube)\\s*\\(");
    private static final Pattern ATTRIBUTE = Pattern.compile("(?m)^(\\s*)attribute\\b");
    private static final Pattern VARYING = Pattern.compile("(?m)^(\\s*)varying\\b");
    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);
    private static final Pattern VERSION_BRANCH = Pattern.compile(
            "#if\\s+__VERSION__\\s*>=\\s*(\\d+)[ \\t]*\\n(.*?)(?:#else[ \\t]*\\n(.*?))?#endif", Pattern.DOTALL);

    private TwShaderText() {
    }

    /** The shader as the shim takes it; it opens with #version, as the shim requires ("shader does not open with #version"). */
    static String forShim(String source, boolean fragment) {
        java.util.regex.Matcher version = VERSION.matcher(source);
        if (!version.find()) {
            throw new IllegalArgumentException("shader without #version");
        }
        String body = COMMENT.matcher(source.substring(version.end()).replace("\r\n", "\n")).replaceAll("");
        body = VERSION_BRANCH.matcher(body).replaceAll(m -> {
            String kept = Integer.parseInt(m.group(1)) <= 330 ? m.group(2) : m.group(3);
            return java.util.regex.Matcher.quoteReplacement(kept == null ? "" : kept);
        });
        StringBuilder outputs = new StringBuilder();
        if (fragment) {
            java.util.regex.Matcher data = FRAG_DATA.matcher(body);
            java.util.TreeSet<Integer> slots = new java.util.TreeSet<>();
            while (data.find()) {
                slots.add(Integer.valueOf(data.group(1)));
            }
            body = FRAG_DATA.matcher(body).replaceAll("twFragData$1");
            for (int slot : slots) {
                outputs.append("layout(location = ").append(slot).append(") out vec4 twFragData").append(slot).append(";\n");
            }
            if (FRAG_COLOR.matcher(body).find()) {
                if (!slots.isEmpty()) {
                    throw new IllegalArgumentException("shader writes gl_FragColor and gl_FragData");
                }
                body = FRAG_COLOR.matcher(body).replaceAll("twFragColor");
                outputs.append("layout(location = 0) out vec4 twFragColor;\n");
            }
        } else {
            body = ATTRIBUTE.matcher(body).replaceAll("$1in");
        }
        body = VARYING.matcher(body).replaceAll(fragment ? "$1in" : "$1out");
        body = TEXTURE_LOD.matcher(body).replaceAll("textureLod(");
        body = TEXTURE.matcher(body).replaceAll("texture(");
        return "#version 330\n" + outputs + body;
    }

    /** tools/check_vpmetal_programs.py: each input shader (.vert or .frag) written to the output folder as the shim takes it. */
    public static void main(String[] args) throws IOException {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        for (int i = 1; i < args.length; i++) {
            Path in = Path.of(args[i]);
            String name = in.getFileName().toString();
            String text = forShim(Files.readString(in, StandardCharsets.UTF_8), name.endsWith(".frag"));
            Files.writeString(out.resolve(name), text, StandardCharsets.UTF_8);
        }
    }
}
