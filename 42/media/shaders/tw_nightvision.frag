#version 330

// Night vision scope image of Tarkov (Hidden/NightVision, read from its
// bytecode): sat((r+g+b) * Color * Intensity * 0.45); here of the raw scene
// amplified by the gain of the device (NightVision.w). Then the scope camera's
// chromatic aberration (Tarkov's Hidden/ChromaticAberration, non-simple pass,
// read from its bytecode): the channels scaled about the lens centre by
// 1 -+ S and 1 -+ S/2, mixed in towards the edge with the weight
// (1 - 4 r^2)^2, r = distance from the centre over the lens diameter: a clean
// centre, fringes only towards the edge.

uniform sampler2D WorldTex;
uniform sampler2D NoiseTex;
uniform vec2 WorldSize;
uniform vec4 NightVision;
uniform float NvNoise;
uniform vec4 Viewport;
uniform vec4 Input;
// Lens: centre (texels, the viewer), diameter (texels), aberration shift S.
uniform vec4 Lens;

in vec4 vColor;
in vec2 vUV1;

float hash(vec2 p)
{
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

vec3 image(vec2 texel)
{
    vec3 c = texture2D(WorldTex, texel / WorldSize).rgb;
    float sum = (c.r + c.g + c.b) * NightVision.w;
    if (NvNoise > 0.0)
    {
        vec2 offset = vec2(hash(vec2(Input.w, 1.0)), hash(vec2(Input.w, 2.0))) * 512.0;
        float n = texture2D(NoiseTex, (texel - Viewport.xy + offset) / 512.0).a;
        sum = sqrt(sum * sum + n * NvNoise);
    }
    return clamp(sum * NightVision.rgb, 0.0, 1.0);
}

vec3 aberrated(vec2 texel)
{
    vec3 c = image(texel);
    // Under half a texel at the lens edge: nothing to see.
    if (Lens.w * Lens.z * 0.5 < 0.5)
    {
        return c;
    }
    vec2 d = texel - Lens.xy;
    float r = length(d) / Lens.z;
    // Past the lens edge (the view is wider than the lens): the edge shift.
    vec2 k = (r > 0.5 ? d * (0.5 / r) : d) * Lens.w;
    vec3 a = image(texel - k), a2 = image(texel - 0.5 * k);
    vec3 b = image(texel + k), b2 = image(texel + 0.5 * k);
    vec3 fringe = 0.666 * vec3(a.r + 0.5 * a2.r, 0.5 * (c.g + a2.g + b2.g), b.b + 0.5 * b2.b);
    float w = max(0.0, 1.0 - 4.0 * r * r);
    return mix(fringe, c, w * w);
}

void main()
{
    gl_FragColor = vec4(aberrated(gl_FragCoord.xy), 1.0);
}
