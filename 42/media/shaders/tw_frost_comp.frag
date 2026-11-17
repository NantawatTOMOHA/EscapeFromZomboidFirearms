#version 330
// GLSL 1.20 dialect of PZ's own shaders (gl_FragColor, texture2D): compiles as written and after PZ's GL 2.1
// rewrite of every shader on macOS (ShaderUnit.processShaderSyntax; tools/check_shader_gl21.py).

// The frosted game frame under the Tarkov screens (FrostedBackdrop.Draw): the blurred quarter-size
// frame stretched over the screen, partly desaturated, darkened and tinted towards Tarkov's cold
// blue, with a soft vignette and a dither against banding. Opaque: the UI buffer is composited
// with premultiplied alpha, so alpha 1 shows the frost instead of the live world.

uniform sampler2D Input;
uniform vec2 Screen;
uniform vec4 Tint;
uniform vec4 Look;

void main()
{
    vec2 uv = gl_FragCoord.xy / Screen;
    vec3 c = texture2D(Input, uv).rgb;
    float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(c, vec3(luma), Look.y);
    c *= 1.0 - Look.x;
    c = mix(c, Tint.rgb, Tint.a);
    vec2 d = (uv - 0.5) * vec2(Screen.x / Screen.y, 1.0);
    c *= clamp(1.0 - Look.z * dot(d, d) * 0.9, 0.0, 1.0);
    float n = fract(sin(dot(gl_FragCoord.xy + Look.w, vec2(12.9898, 78.233))) * 43758.5453) - 0.5;
    c += n / 255.0;
    gl_FragColor = vec4(max(c, vec3(0.0)), 1.0);
}
