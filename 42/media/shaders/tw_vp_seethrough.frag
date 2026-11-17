#version 330
// GLSL 1.20 dialect of PZ's own shaders (gl_FragColor, texture2D): compiles as written and after PZ's GL 2.1
// rewrite of every shader on macOS (ShaderUnit.processShaderSyntax; tools/check_shader_gl21.py).

// See-through weapon (pz.escapefromzomboidfirearms.zb.SeeThrough, FB3 item 4): the defocus of the own weapon
// and hands in Viewpoint's HDR target, before its fog, bloom, tone mapping and anti-aliasing.
// The weapon and the hands are the pixels nearer than the weapon's far end (Viewpoint's depth):
// their faded records leave the scene behind in the other pixels, which stay sharp. A near pixel
// takes the mean of the near pixels on a disc around it; a scene pixel by them is drawn toward that
// mean by Blur.z times the share of near pixels found (the soft outline of a defocused object).
// Inside the Sharp circle (the front sight's post while aiming through irons) nothing changes.
//   Copy        our copy of the HDR target (its viewport size; the box is current)
//   SceneDepth  Viewpoint's depth, render resolution (DepthScale: render size / texture size)
//   DepthLinear view distance = x / (ndc z + y) of the frame projection
//   Blur        x: near limit (scene distance), y: radius (px), z: soft outline, w: frame seed
//   Sharp       x, y: window pixels, z: radius (0: none)

uniform sampler2D Copy;
uniform sampler2D SceneDepth;
uniform vec4 Viewport;
uniform vec2 DepthScale;
uniform vec2 DepthLinear;
uniform vec4 Blur;
uniform vec3 Sharp;

const int TAPS = 24;
const float GOLDEN = 2.39996323;

float viewDistance(vec2 p)
{
    vec2 st = (p - Viewport.xy) / Viewport.zw * DepthScale;
    float ndc = texture2D(SceneDepth, st).r * 2.0 - 1.0;
    return DepthLinear.x / (ndc + DepthLinear.y);
}

vec3 colour(vec2 p)
{
    return texture2D(Copy, (p - Viewport.xy) / Viewport.zw).rgb;
}

// A per-pixel, per-frame turn of the sample disc (the anti-aliasing averages the pattern away).
float turn(vec2 p)
{
    return 6.2831853 * fract(sin(dot(p + Blur.w, vec2(12.9898, 78.233))) * 43758.5453);
}

void main()
{
    vec2 p = gl_FragCoord.xy;
    if (Sharp.z > 0.0 && distance(p, Sharp.xy) < Sharp.z) {
        discard;
    }
    bool near = viewDistance(p) < Blur.x;
    vec3 sum = near ? colour(p) : vec3(0.0);
    float found = near ? 1.0 : 0.0;
    float a = turn(p);
    for (int i = 0; i < TAPS; i++) {
        float r = sqrt((float(i) + 0.5) / float(TAPS)) * Blur.y;
        float t = float(i) * GOLDEN + a;
        vec2 q = p + r * vec2(cos(t), sin(t));
        if (viewDistance(q) < Blur.x) {
            sum += colour(q);
            found += 1.0;
        }
    }
    if (found < 0.5) {
        discard;
    }
    vec3 mean = sum / found;
    if (near) {
        gl_FragColor = vec4(mean, 1.0);
        return;
    }
    float alpha = Blur.z * found / float(TAPS);
    if (alpha <= 0.002) {
        discard;
    }
    gl_FragColor = vec4(mean, alpha);
}
