#version 330 compatibility
// GLSL 1.20 dialect of PZ's own shaders (gl_FragData, texture2D, float maths): compiles as written and after PZ's
// GL 2.1 rewrite of every shader on macOS (ShaderUnit.processShaderSyntax; tools/check_shader_gl21.py).
// Outputs: gl_FragData[0] the picture (draw buffer 0, RGBA16F), gl_FragData[1] its depth (draw buffer 1, R32F).
uniform sampler2D CurrentImage;
uniform sampler2D CurrentDepth;
uniform sampler2D HistoryImage;
uniform sampler2D HistoryDepth;
uniform int HistoryOn;
uniform mat4 Reproject;
uniform mat4 ReprojectSky;
uniform vec2 PreviousDepth;
uniform vec2 ImageSize; // CurrentImage's (= CurrentDepth's) level 0 in texels, from ScopePipHistory

float distanceAt(float depth) {
    return abs(PreviousDepth.y / (depth * 2.0 - 1.0 + PreviousDepth.x));
}

// One texel of a current image (texelFetch is GLSL 1.30+): its centre, through the NEAREST sampler ScopePipHistory
// binds to both current images.
vec4 texel(sampler2D image, vec2 at) {
    return texture2D(image, (at + 0.5) / ImageSize);
}

void main() {
    vec2 size = ImageSize;
    vec2 pixel = floor(gl_FragCoord.xy);
    vec2 uv = gl_FragCoord.xy / size;
    float depth = texel(CurrentDepth, pixel).r;
    vec3 lo = vec3(1e20), hi = vec3(-1e20), current = vec3(0.0);
    // A small tent reconstructs the undersampled scene, including the first
    // frame and newly revealed surfaces. Reticles/sensor noise come later.
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 o = vec2(float(x), float(y));
            vec3 c = texel(CurrentImage, clamp(pixel + o, vec2(0.0), size - 1.0)).rgb;
            current += c * ((2.0 - abs(o.x)) * (2.0 - abs(o.y))) / 16.0;
            lo = min(lo, c);
            hi = max(hi, c);
        }
    }
    gl_FragData[0] = vec4(current, 1.0);
    gl_FragData[1] = vec4(depth, 0.0, 0.0, 1.0);
    if (HistoryOn == 0) return;
    bool sky = depth >= 1.0;
    vec4 last = (sky ? ReprojectSky : Reproject) * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    if (last.w <= 0.0) return;
    vec3 ndc = last.xyz / last.w;
    vec2 previousUV = ndc.xy * 0.5 + 0.5;
    vec2 margin = 0.5 / size;
    if (any(lessThan(previousUV, margin)) || any(greaterThan(previousUV, 1.0 - margin)) || abs(ndc.z) > 1.0) return;
    float oldDepth = texture2D(HistoryDepth, previousUV).r;
    if (sky != (oldDepth >= 1.0)) return;
    if (!sky) {
        float expected = distanceAt(ndc.z * 0.5 + 0.5);
        if (abs(distanceAt(oldDepth) - expected) > max(0.02, expected * 0.002)) return;
    }
    vec4 old = texture2D(HistoryImage, previousUV);
    float motion = length((previousUV - uv) * size);
    // A stationary stochastic cluster can be entirely dark or bright in the
    // current neighbourhood. Clamping it unconditionally would preserve the
    // noise. Depth rejects new surfaces; motion introduces the colour clamp.
    vec3 carried = mix(old.rgb, clamp(old.rgb, lo, hi), clamp(motion, 0.0, 1.0));
    float count = clamp(old.a, 1.0, 7.0);
    float weight = count / (count + 1.0) / (1.0 + motion * 0.25);
    gl_FragData[0] = vec4(mix(current, carried, weight), min(count + 1.0, 8.0));
}
