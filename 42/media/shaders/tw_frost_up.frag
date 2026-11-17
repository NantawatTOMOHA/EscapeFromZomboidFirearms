#version 330
// GLSL 1.20 dialect of PZ's own shaders (gl_FragColor, texture2D): compiles as written and after PZ's GL 2.1
// rewrite of every shader on macOS (ShaderUnit.processShaderSyntax; tools/check_shader_gl21.py).

// Dual Kawase upsample (FrostedBackdrop): a ring of eight taps around the centre in the smaller
// source, the diagonal ones weighted twice.

uniform sampler2D Input;
uniform vec2 Target;
uniform vec2 HalfPixel;

void main()
{
    vec2 uv = gl_FragCoord.xy / Target;
    vec2 o = HalfPixel * 2.0;
    vec3 s = texture2D(Input, uv + vec2(-o.x * 2.0, 0.0)).rgb;
    s += texture2D(Input, uv + vec2(-o.x, o.y)).rgb * 2.0;
    s += texture2D(Input, uv + vec2(0.0, o.y * 2.0)).rgb;
    s += texture2D(Input, uv + vec2(o.x, o.y)).rgb * 2.0;
    s += texture2D(Input, uv + vec2(o.x * 2.0, 0.0)).rgb;
    s += texture2D(Input, uv + vec2(o.x, -o.y)).rgb * 2.0;
    s += texture2D(Input, uv + vec2(0.0, -o.y * 2.0)).rgb;
    s += texture2D(Input, uv + vec2(-o.x, -o.y)).rgb * 2.0;
    gl_FragColor = vec4(s / 12.0, 1.0);
}
