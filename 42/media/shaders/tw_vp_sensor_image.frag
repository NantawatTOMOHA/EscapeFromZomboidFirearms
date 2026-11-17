#version 330
// GLSL 1.20 dialect of PZ's own shaders (gl_FragColor, texture2D): compiles as written and after PZ's GL 2.1
// rewrite of every shader on macOS (ShaderUnit.processShaderSyntax; tools/check_shader_gl21.py).

// A night vision or thermal device's image in Viewpoint's view (VpSensorGL),
// over a rectangle Rect (pixels of the target) of an input picture (InUV of
// InputTex): the scope picture of a night vision / thermal scope, the frame
// inside a 1x sight's eyepiece, or the whole view through a head device.
//   Kind 1 thermal   tw_thermal: Tarkov's Hidden/ThermalVision u = sat(sat(L *
//                    MainTexColorCoef) * fade + Minimum / 2 - Shift / (1 -
//                    Shift)), ramp(u); L = max(the scene brightness (Tarkov
//                    weights) * ENV * gain, softly limited to EnvCap, the heat
//                    buffer * 4); fade = min(1, exp(-distance * DepthFade))^(1/4)
//                    with the distance from the camera's depth (the sky: no
//                    scene term, nothing in the distance); the veil (the heat
//                    buffer's green: the own shot's gases out of the scope's
//                    focus, VpHeat) moves u towards the hot end, by at most
//                    VEIL, so the scene stays seen through them (FB5); bloom,
//                    pixelation and noise of the mode, its chromatic aberration;
//   Kind 2 night vision scope  tw_nightvision: sat((r + g + b) * gain * Color *
//                    Intensity * 0.45) with the scope's noise and aberration;
//   Kind 3 head night vision   Tarkov's Hidden/NightVision of a head device:
//                    s = sqrt(((r + g + b) * gain)^2 + grain.r * NoiseIntensity),
//                    sat(s * Color * Intensity * 0.45), the grain moving every
//                    frame; then its eyepiece mask (Hidden/MaskTexture: the
//                    mask fitted to the view's height, black outside).
// gain: the device's automatic brightness, GainTarget / the mean brightness
// of the input (MeanTex), between 1 and the isometric image's gain.
// LensOn: only inside the ellipse of a 1x sight's eyepiece, hidden where the
// scene is in front of it.

uniform sampler2D InputTex;
uniform sampler2D HeatTex;
uniform sampler2D DepthTex;
uniform sampler2D RampTex;
uniform sampler2D NoiseTex;
uniform sampler2D MeanTex;
uniform sampler2D MaskTex;
uniform float Kind;
uniform vec4 Rect;          // x, y, width, height of the target's pixels drawn
uniform vec4 InUV;          // input uv of the rectangle: origin, size
uniform vec4 Depth;         // depth texture scale x, y; view distance = z / (ndc + w)
uniform float DepthOn;
uniform vec4 Thermal;       // MainTexColorCoef, MinimumTemperatureValue, RampShift, DepthFade
uniform vec4 Input;         // ENV, gain limit (thermal), noise * 0.785, seed
uniform float EnvCap;
uniform vec4 Pixel;         // blocks over the height, alpha
uniform vec4 Bloom;         // intensity, threshold, radius (pixels)
uniform vec4 Lens;          // aberration centre (pixels), lens diameter, shift
uniform vec4 NightVision;   // Color * Intensity * 0.45, gain limit
uniform float NvNoise;
uniform vec4 NoiseUV;       // head grain: uv scale, offset
uniform vec4 Mask;          // on, 1 / MaskSize, mask height / width, view aspect
uniform vec4 LensOn;        // on, the eyepiece's depth (0..1)
uniform vec4 LensAxes;      // the eyepiece's two radius vectors (pixels)
uniform vec3 LensDepth;     // centre window depth, its change along each projected radius
uniform vec2 LensCentre;
uniform float GainTarget;

float gain;

// FB5 item 5: the furthest the veil of the own shot's gases moves the palette towards its hot end.
const float VEIL = 0.5;

float sat(float x)
{
    return clamp(x, 0.0, 1.0);
}

float hash(vec2 p)
{
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

vec2 inUV(vec2 p)
{
    return InUV.xy + p / Rect.zw * InUV.zw;
}

vec2 viewUV(vec2 p)
{
    return p / Rect.zw;
}

// Distance from the camera (scene units = metres); -1 for the sky.
float distanceAt(vec2 p)
{
    if (DepthOn < 0.5)
    {
        return 0.0;
    }
    float d = texture2D(DepthTex, viewUV(p) * Depth.xy).r;
    if (d >= 0.999999)
    {
        return -1.0;
    }
    return Depth.z / (d * 2.0 - 1.0 + Depth.w);
}

vec3 thermal(vec2 p)
{
    float dist = distanceAt(p);
    vec3 c = texture2D(InputTex, inUV(p)).rgb;
    float env = dist < 0.0 ? 0.0 : dot(c, vec3(0.22, 0.707, 0.071)) * Input.x * gain;
    if (EnvCap > 0.0)
    {
        env = EnvCap * (1.0 - exp(-env / EnvCap));
    }
    vec2 heat = texture2D(HeatTex, viewUV(p)).rg;
    float v = max(env, heat.r * 4.0);
    float fade = sqrt(sqrt(min(1.0, exp(-max(dist, 0.0) * Thermal.w))));
    float s = Thermal.z;
    float u = sat(sat(v * Thermal.x) * fade + Thermal.y * 0.5 - s / (1.0 - s));
    u = mix(u, 1.0, sat(heat.g) * VEIL);
    return texture2D(RampTex, vec2(u, 0.5)).rgb;
}

vec3 nvScope(vec2 p)
{
    vec3 c = texture2D(InputTex, inUV(p)).rgb;
    float sum = (c.r + c.g + c.b) * gain;
    if (NvNoise > 0.0)
    {
        vec2 offset = vec2(hash(vec2(Input.w, 1.0)), hash(vec2(Input.w, 2.0))) * 512.0;
        float n = texture2D(NoiseTex, (p + offset) / 512.0).a;
        sum = sqrt(sum * sum + n * NvNoise);
    }
    return clamp(sum * NightVision.rgb, 0.0, 1.0);
}

vec3 nvHead(vec2 p)
{
    vec3 c = texture2D(InputTex, inUV(p)).rgb;
    float sum = (c.r + c.g + c.b) * gain;
    float n = texture2D(NoiseTex, viewUV(p) * NoiseUV.xy + NoiseUV.zw).r;
    return clamp(sqrt(sum * sum + n * NvNoise) * NightVision.rgb, 0.0, 1.0);
}

vec3 sensor(vec2 p)
{
    return Kind < 1.5 ? thermal(p) : Kind < 2.5 ? nvScope(p) : nvHead(p);
}

// The scope camera's chromatic aberration (tw_nightvision.frag): clean at the
// lens centre, fringes towards its edge.
vec3 image(vec2 p)
{
    vec3 c = sensor(p);
    if (Lens.w * Lens.z * 0.5 < 0.5)
    {
        return c;
    }
    vec2 d = p - Lens.xy;
    float r = length(d) / Lens.z;
    vec2 k = (r > 0.5 ? d * (0.5 / r) : d) * Lens.w;
    vec3 a = sensor(p - k), a2 = sensor(p - 0.5 * k);
    vec3 b = sensor(p + k), b2 = sensor(p + 0.5 * k);
    vec3 fringe = 0.666 * vec3(a.r + 0.5 * a2.r, 0.5 * (c.g + a2.g + b2.g), b.b + 0.5 * b2.b);
    float w = max(0.0, 1.0 - 4.0 * r * r);
    return mix(fringe, c, w * w);
}

void main()
{
    vec2 p = gl_FragCoord.xy - Rect.xy;
    float alpha = 1.0;
    if (LensOn.x > 0.5)
    {
        vec2 d = p - LensCentre;
        float det = LensAxes.x * LensAxes.w - LensAxes.y * LensAxes.z;
        vec2 q = vec2(LensAxes.w * d.x - LensAxes.z * d.y, -LensAxes.y * d.x + LensAxes.x * d.y) / det;
        float r = length(q);
        if (r >= 1.0)
        {
            discard;
        }
        float lensDepth = LensDepth.x + dot(q, LensDepth.yz);
        if (DepthOn > 0.5 && texture2D(DepthTex, viewUV(p) * Depth.xy).r + 0.00002 < lensDepth)
        {
            discard;
        }
        alpha = 1.0 - smoothstep(1.0 - max(fwidth(r), 1e-4), 1.0, r);
    }
    gain = clamp(GainTarget / max(texture2D(MeanTex, vec2(0.5)).r, 1e-3), 1.0, max(1.0, Kind < 1.5 ? Input.y : NightVision.w));
    vec3 col = image(p);
    if (Kind < 1.5)
    {
        // ScopeEffectsData.BloomOptimized: the part over Threshold, blurred over BlurSize, added with Intensity.
        if (Bloom.x > 0.0 && Bloom.z > 0.5)
        {
            vec3 glow = vec3(0.0);
            for (int i = 0; i < 8; i++)
            {
                float a = float(i) * 0.785398 + 0.3927;
                vec2 d = vec2(cos(a), sin(a)) * Bloom.z;
                glow += max(thermal(p + d * 0.5) - Bloom.y, 0.0);
                glow += max(thermal(p + d) - Bloom.y, 0.0);
            }
            col += glow / 16.0 * Bloom.x;
        }
        // PixelationUtilities: blocks over the lens height, mixed by alpha.
        if (Pixel.x > 0.0 && Pixel.y > 0.0)
        {
            float block = Rect.w / Pixel.x;
            vec2 snapped = (floor(p / block) + 0.5) * block;
            col = mix(col, image(snapped), sat(Pixel.y));
        }
        // Noise: sqrt(c^2 + n * NoiseIntensity * 0.785), a new offset every sensor frame.
        if (Input.z > 0.0)
        {
            vec2 offset = vec2(hash(vec2(Input.w, 1.0)), hash(vec2(Input.w, 2.0))) * 512.0;
            float n = texture2D(NoiseTex, (p + offset) / 512.0).a;
            col = sqrt(col * col + n * Input.z);
        }
    }
    if (Mask.x > 0.5)
    {
        vec2 uv = viewUV(p);
        vec2 m = vec2((uv.x - 0.5) * Mask.w * Mask.z, uv.y - 0.5) * Mask.y + 0.5;
        col = mix(col, vec3(0.0), texture2D(MaskTex, m).a);
    }
    gl_FragColor = vec4(col, alpha);
}
