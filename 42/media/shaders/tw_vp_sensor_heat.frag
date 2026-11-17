#version 330
// GLSL 1.20 dialect of PZ's own shaders (gl_FragColor, texture2D): compiles as written and after PZ's GL 2.1
// rewrite of every shader on macOS (ShaderUnit.processShaderSyntax; tools/check_shader_gl21.py).

// Heat buffer of a perspective camera in Viewpoint's view (VpHeat): red =
// thermal input / 4, combined with GL_MAX, as the isometric buffer (tw_heat).
// Kinds (vKind.z):
//   0 heat volume  the visible surface under the pixel, rebuilt from the
//                  camera's depth, takes the heat when it lies within the
//                  capsule (vA.xyz - vB.xyz, radius vA.w) and between the floor
//                  (vB.w) and ceiling (vC.x) heights: the model's own
//                  silhouette, hidden by what is in front of it;
//   1 glow         a camera-facing quad of the halo (hot gas, sparks), faded
//                  over vC.y scene units in front of the scene surface;
//   2 fire         the procedural flame, gas and floor parts of tw_heat_fire
//                  (vA.x seed, vA.y part), with the same soft depth test;
//   3 vehicle      a heat volume of the vehicle's box with VehicleHeat's field
//                  (tw_heat_vehicle) on the visible surface inside it;
//   4 veil         a glow's out-of-focus share at a thermal scope's objective
//                  (FB5): its halo into green only, added up (VpHeat draws the
//                  run with GL_FUNC_ADD and a green colour mask).
// Scene space: x, z horizontal, y up, units of metres (Viewpoint's scene).

uniform sampler2D SceneDepth;
uniform sampler2D Halo;
uniform mat4 InvViewProjection;
uniform vec4 Target;        // width, height, depth texture scale x, y
uniform vec2 DepthLinear;   // view distance = x / (ndc z + y)
uniform float Time;
uniform float DepthOn;
uniform float HaloOn;
uniform vec4 Engine;        // centre, radius
uniform vec4 Exhaust;       // centre, radius
uniform vec4 Wheels[4];     // centre, radius
uniform vec4 Axis;          // forward (x, z), length
uniform vec4 Level;         // warmth of engine, exhaust, tyres; wheel count
uniform vec4 Peaks;         // input / 4 at full warmth: engine, exhaust, tyres, body
uniform vec4 BoxCentre;     // centre, half height
uniform vec4 BoxForward;    // unit axis, half length
uniform vec4 BoxRight;      // unit axis, half width
uniform float BoxGround;

// vKind.zw, vA, vB and vC are the same at every vertex of a primitive (VpHeat writes them per quad or volume):
// flat in GLSL 1.30+, as before; interpolated in GLSL 1.20 (PZ's GL 2.1 rewrite on macOS), which has no flat.
in vec2 vUV;
#if __VERSION__ >= 130
flat in vec4 vKind;
flat in vec4 vA;
flat in vec4 vB;
flat in vec4 vC;
#else
varying vec4 vKind;
varying vec4 vA;
varying vec4 vB;
varying vec4 vC;
#endif

float depthAt(out vec2 uv)
{
    uv = gl_FragCoord.xy / Target.xy;
    return texture2D(SceneDepth, uv * Target.zw).r;
}

// The scene point seen at this pixel; false for the sky (nothing drawn there).
bool surface(out vec3 p)
{
    vec2 uv;
    float d = depthAt(uv);
    vec4 w = InvViewProjection * vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    p = w.xyz / w.w;
    return d < 0.999999;
}

// Fades a glow out as it goes behind the scene surface.
float soft(float width)
{
    if (DepthOn < 0.5)
    {
        return 1.0;
    }
    vec2 uv;
    float scene = DepthLinear.x / (depthAt(uv) * 2.0 - 1.0 + DepthLinear.y);
    return clamp((scene - 1.0 / gl_FragCoord.w) / max(width, 1e-3), 0.0, 1.0);
}

float hash(vec2 p)
{
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float noise(vec2 p)
{
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

float fbm(vec2 p)
{
    float s = 0.0, a = 0.5;
    for (int i = 0; i < 4; i++)
    {
        s += a * noise(p);
        p = p * 2.03 + vec2(1.7, 9.2);
        a *= 0.5;
    }
    return s / 0.9375;
}

// tw_heat_fire: u across, v from the top (0) to the base (1); parts 0 flame,
// 0.25 gas, 0.5 gas under a ceiling, 0.75 burning patch, 1 warm ground.
float fire(vec2 uv, float seed, float part)
{
    float x = uv.x * 2.0 - 1.0;
    float h = 1.0 - uv.y;
    float t;
    if (part < 0.125)
    {
        float n = fbm(vec2(x * 2.2 + seed, h * 3.0 - Time * 2.4));
        float n2 = fbm(vec2(x * 4.5 - seed, h * 6.0 - Time * 4.0));
        float sway = (noise(vec2(h * 2.0 - Time * 1.3, seed)) - 0.5) * 0.5 * h;
        float width = mix(0.95, 0.12, pow(h, 0.75)) * (0.7 + 0.6 * n);
        float d = abs(x - sway) / max(width, 0.02);
        t = smoothstep(1.0, 0.45, d) * smoothstep(0.95, 0.55, h + 0.45 * (n2 - 0.5));
        t *= 0.75 + 0.55 * (1.0 - h) * (1.0 - 0.5 * min(d, 1.0));
    }
    else if (part < 0.625)
    {
        float n = fbm(vec2(x * 1.6 + seed, h * 2.2 - Time * 0.9));
        float width = mix(0.28, 0.78, sqrt(h));
        float fall = pow(1.0 - h, 0.8) * smoothstep(1.0, 0.7, h);
        if (part > 0.375)
        {
            float layer = smoothstep(0.62, 0.86, h) * smoothstep(1.0, 0.93, h);
            width = mix(width, 0.86, layer);
            fall = max(fall, 0.6 * layer);
        }
        float d = abs(x) / width + 0.3 * (n - 0.5);
        t = smoothstep(1.0, 0.3, d) * fall * (0.35 + 0.9 * n) * smoothstep(0.0, 0.12, h);
    }
    else
    {
        vec2 c = vec2(x, uv.y * 2.0 - 1.0);
        float r = length(c);
        if (part < 0.875)
        {
            float n = fbm(c * 2.5 + seed + vec2(0.0, Time * 0.6));
            t = smoothstep(1.0, 0.35, r + 0.35 * (n - 0.5)) * (0.65 + 0.45 * n);
        }
        else
        {
            t = smoothstep(1.0, 0.0, r);
            t *= t;
        }
    }
    return clamp(t, 0.0, 1.15);
}

float spot(vec3 d, float r)
{
    return exp(-dot(d, d) / (r * r));
}

float vehicle(vec3 p)
{
    vec3 d = p - BoxCentre.xyz;
    if (abs(dot(d, BoxForward.xyz)) > BoxForward.w || abs(dot(d, BoxRight.xyz)) > BoxRight.w || abs(d.y) > BoxCentre.w || p.y < BoxGround)
    {
        return 0.0;
    }
    vec3 e = p - Engine.xyz;
    float hot = Peaks.x * Level.x * spot(e, Engine.w);
    float along = abs(dot(e.xz, Axis.xy));
    hot = max(hot, Peaks.w * Level.x * exp(-max(0.0, along - Engine.w) / (0.35 * Axis.z)));
    hot = max(hot, Peaks.y * Level.y * spot(p - Exhaust.xyz, Exhaust.w));
    for (int i = 0; i < 4; i++)
    {
        if (float(i) < Level.w)
        {
            hot = max(hot, Peaks.z * Level.z * spot(p - Wheels[i].xyz, Wheels[i].w));
        }
    }
    return clamp(hot, 0.0, 1.0);
}

float halo()
{
    return HaloOn > 0.5 ? texture2D(Halo, vUV).a : clamp(1.0 - length(vUV * 2.0 - 1.0), 0.0, 1.0);
}

void main()
{
    float kind = vKind.z;
    float value;
    if (kind > 3.5)
    {
        float veil = vKind.w * halo() * soft(vC.y);
        if (veil <= 0.002)
        {
            discard;
        }
        gl_FragColor = vec4(0.0, veil, 0.0, 0.0);
        return;
    }
    if (kind < 0.5 || kind > 2.5)
    {
        vec3 p;
        if (DepthOn < 0.5 || !surface(p))
        {
            discard;
        }
        if (kind > 2.5)
        {
            value = vehicle(p);
        }
        else
        {
            if (p.y < vB.w || p.y > vC.x)
            {
                discard;
            }
            vec3 ab = vB.xyz - vA.xyz;
            float t = clamp(dot(p - vA.xyz, ab) / max(dot(ab, ab), 1e-8), 0.0, 1.0);
            float d = length(p - (vA.xyz + ab * t));
            value = vKind.w * (1.0 - smoothstep(vA.w * (1.0 - vC.y), vA.w, d));
        }
    }
    else if (kind < 1.5)
    {
        value = vKind.w * halo() * soft(vC.y);
    }
    else
    {
        value = min(1.0, vKind.w * fire(vUV, vA.x * 97.0, vA.y)) * soft(vC.y);
    }
    if (value <= 0.004)
    {
        discard;
    }
    gl_FragColor = vec4(value, 0.0, 0.0, 1.0);
}
