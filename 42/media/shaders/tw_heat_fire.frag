#version 330

// A fire in the heat buffer of TW/OpticView (HeatRenderer), as a thermal
// camera sees it: flame tongues rising from the burning square (white-hot
// low in the core), the hot gas above them (a widening turbulent column that
// leans with the wind outdoors and spreads into a layer under a ceiling), and
// on the floor the burning patch and the warm ground around it. Procedural;
// red = thermal input / 4 (GL_MAX with the rest of the buffer).
// vColor.r = input / 4 of this part, vColor.g = seed of the fire, vColor.b =
// part: 0 flame, 0.25 hot gas, 0.5 hot gas under a ceiling, 0.75 burning
// patch, 1 warm ground. UV: u across, v from the top (0) to the base (1).

uniform float Time;

in vec4 vColor;
in vec2 vUV1;

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

void main()
{
    float part = vColor.b;
    float seed = vColor.g * 97.0;
    float x = vUV1.x * 2.0 - 1.0;
    float h = 1.0 - vUV1.y;
    float t;
    if (part < 0.125)
    {
        // Tongues: noise carried upwards, the body narrowing with height and swaying.
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
        // Hot gas: slower, larger eddies; fades with height and off to the
        // sides with a ragged edge, to nothing well inside the quad.
        float n = fbm(vec2(x * 1.6 + seed, h * 2.2 - Time * 0.9));
        float width = mix(0.28, 0.78, sqrt(h));
        float fall = pow(1.0 - h, 0.8) * smoothstep(1.0, 0.7, h);
        if (part > 0.375)
        {
            // Under a ceiling: a layer spreading along it, thinning to its rim.
            float layer = smoothstep(0.62, 0.86, h) * smoothstep(1.0, 0.93, h);
            width = mix(width, 0.86, layer);
            fall = max(fall, 0.6 * layer);
        }
        float d = abs(x) / width + 0.3 * (n - 0.5);
        t = smoothstep(1.0, 0.3, d) * fall * (0.35 + 0.9 * n) * smoothstep(0.0, 0.12, h);
    }
    else
    {
        vec2 c = vec2(x, vUV1.y * 2.0 - 1.0);
        float r = length(c);
        if (part < 0.875)
        {
            // Burning patch: ragged edge, flickering embers.
            float n = fbm(c * 2.5 + seed + vec2(0.0, Time * 0.6));
            t = smoothstep(1.0, 0.35, r + 0.35 * (n - 0.5)) * (0.65 + 0.45 * n);
        }
        else
        {
            // Ground warmed by the fire.
            t = smoothstep(1.0, 0.0, r);
            t *= t;
        }
    }
    t = clamp(t, 0.0, 1.15);
    if (t < 0.02)
    {
        discard;
    }
    gl_FragColor = vec4(min(1.0, vColor.r * t), 0.0, 0.0, 1.0);
}
