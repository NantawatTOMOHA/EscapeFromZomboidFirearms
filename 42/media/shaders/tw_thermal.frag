#version 330

// Thermal scope image of Tarkov (Hidden/ThermalVision of the SPT bundles, read
// from its bytecode): u = sat(sat(L*MainTexColorCoef)*fade + Minimum/2 -
// RampShift/(1-RampShift)), colour = ramp(u); noise sqrt(c^2 + n*k). L is the
// heat buffer (warm bodies and sources) or, for the cold world, the scene
// brightness (Tarkov weights) times ENV and the automatic gain. The scope
// camera's chromatic aberration as in tw_nightvision.frag (Tarkov's
// Hidden/ChromaticAberration: radial, clean at the lens centre).

uniform sampler2D WorldTex;
uniform sampler2D HeatTex;
uniform sampler2D RampTex;
uniform sampler2D NoiseTex;
uniform vec2 WorldSize;
uniform vec3 MapX;
uniform vec3 MapY;
uniform vec4 Cone;
uniform vec4 Thermal;
uniform vec4 Input;
// Light is no heat: the scene term saturates softly at EnvCap, below the coldest body.
uniform float EnvCap;
uniform vec4 Pixel;
uniform vec4 Viewport;
uniform vec4 Bloom;
// Lens: centre (texels, the viewer), diameter (texels), aberration shift S.
uniform vec4 Lens;

in vec4 vColor;
in vec2 vUV1;

float sat(float x)
{
    return clamp(x, 0.0, 1.0);
}

float hash(vec2 p)
{
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

float inputAt(vec2 texel)
{
    vec2 uv = texel / WorldSize;
    vec3 c = texture2D(WorldTex, uv).rgb;
    float env = dot(c, vec3(0.22, 0.707, 0.071)) * Input.x * Input.y;
    if (EnvCap > 0.0)
    {
        env = EnvCap * (1.0 - exp(-env / EnvCap));
    }
    float body = texture2D(HeatTex, uv).r * 4.0;
    return max(env, body);
}

vec3 thermal(vec2 texel)
{
    float v = inputAt(texel);
    vec2 w = vec2(dot(MapX, vec3(texel, 1.0)), dot(MapY, vec3(texel, 1.0)));
    float fade = sqrt(sqrt(min(1.0, exp(-length(w - Cone.xy) * Thermal.w))));
    float s = Thermal.z;
    float u = sat(sat(v * Thermal.x) * fade + Thermal.y * 0.5 - s / (1.0 - s));
    return texture2D(RampTex, vec2(u, 0.5)).rgb;
}

vec3 image(vec2 texel)
{
    vec3 c = thermal(texel);
    if (Lens.w * Lens.z * 0.5 < 0.5)
    {
        return c;
    }
    vec2 d = texel - Lens.xy;
    float r = length(d) / Lens.z;
    vec2 k = (r > 0.5 ? d * (0.5 / r) : d) * Lens.w;
    vec3 a = thermal(texel - k), a2 = thermal(texel - 0.5 * k);
    vec3 b = thermal(texel + k), b2 = thermal(texel + 0.5 * k);
    vec3 fringe = 0.666 * vec3(a.r + 0.5 * a2.r, 0.5 * (c.g + a2.g + b2.g), b.b + 0.5 * b2.b);
    float w = max(0.0, 1.0 - 4.0 * r * r);
    return mix(fringe, c, w * w);
}

void main()
{
    // No scan line glitch (GlitchUtilities) or motion blur: left out at the
    // user's request (2026-10-15).
    vec2 texel = gl_FragCoord.xy;
    float seed = Input.w;
    vec3 col = image(texel);
    // ScopeEffectsData.BloomOptimized: the bright part (over Threshold) blurred
    // over BlurSize and added with Intensity.
    if (Bloom.x > 0.0 && Bloom.z > 0.5)
    {
        vec3 glow = vec3(0.0);
        for (int i = 0; i < 8; i++)
        {
            float a = float(i) * 0.785398 + 0.3927;
            vec2 d = vec2(cos(a), sin(a)) * Bloom.z;
            glow += max(thermal(texel + d * 0.5) - Bloom.y, 0.0);
            glow += max(thermal(texel + d) - Bloom.y, 0.0);
        }
        col += glow / 16.0 * Bloom.x;
    }
    // PixelationUtilities: BlockCount blocks over the view height, mixed by Alpha.
    if (Pixel.x > 0.0 && Pixel.y > 0.0)
    {
        float block = Viewport.w / Pixel.x;
        vec2 snapped = (floor((texel - Viewport.xy) / block) + 0.5) * block + Viewport.xy;
        col = mix(col, image(snapped), sat(Pixel.y));
    }
    // Noise pass: sqrt(c^2 + n * NoiseIntensity * 0.785), a new offset every scope frame.
    if (Input.z > 0.0)
    {
        vec2 offset = vec2(hash(vec2(seed, 1.0)), hash(vec2(seed, 2.0))) * 512.0;
        float n = texture2D(NoiseTex, (gl_FragCoord.xy - Viewport.xy + offset) / 512.0).a;
        col = sqrt(col * col + n * Input.z);
    }
    gl_FragColor = vec4(col, 1.0);
}
