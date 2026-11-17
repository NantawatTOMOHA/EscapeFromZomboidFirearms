#version 330
// GLSL 1.20 dialect of PZ's own shaders (gl_FragColor, texture2D): compiles as written and after PZ's GL 2.1
// rewrite of every shader on macOS (ShaderUnit.processShaderSyntax; tools/check_shader_gl21.py).

// Dual Kawase downsample (FrostedBackdrop): the centre four times and the four diagonal taps one
// and a half source texels out, each a bilinear average of four texels.

uniform sampler2D Input;
uniform vec2 Target;
uniform vec2 HalfPixel;

void main()
{
    vec2 uv = gl_FragCoord.xy / Target;
    vec2 o = HalfPixel * 3.0;
    vec3 s = texture2D(Input, uv).rgb * 4.0;
    s += texture2D(Input, uv - o).rgb;
    s += texture2D(Input, uv + o).rgb;
    s += texture2D(Input, uv + vec2(o.x, -o.y)).rgb;
    s += texture2D(Input, uv - vec2(o.x, -o.y)).rgb;
    gl_FragColor = vec4(s / 8.0, 1.0);
}
