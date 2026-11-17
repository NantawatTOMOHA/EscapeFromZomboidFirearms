#version 330
// GLSL 1.20 dialect of PZ's own shaders (gl_FragColor, texture2D): compiles as written and after PZ's GL 2.1
// rewrite of every shader on macOS (ShaderUnit.processShaderSyntax; tools/check_shader_gl21.py).

// Our mark pass, with the recovered vanilla sampling and per-vertex colours.
uniform sampler2D MarkTex;
uniform vec4 MarkColor;
uniform float MarkKind; // 0 mesh, 1 alpha-only image, 2 RGB + alpha image
uniform float MarkLightness;
uniform float RoundLens;
uniform vec4 UVTransform;
uniform vec2 WrapMode; // Unity: 0 Repeat, 1 Clamp, 2 Mirror, 3 MirrorOnce
uniform vec4 TextureRect;
uniform int GlassCount;
uniform vec2 GlassBoundary[64];
uniform sampler2D SceneDepth;
uniform float DepthOn;
uniform float LensDepth;
uniform vec2 LensDepthSlope;
uniform vec4 Viewport;
in vec2 vUVw;
in float vInvW;
in vec2 vLens;
in vec4 vColorW;

float address(float x, float mode)
{
    if (mode < 0.5) return fract(x);
    if (mode < 1.5) return clamp(x, 0.0, 1.0);
    if (mode < 2.5) return 1.0 - abs(mod(x, 2.0) - 1.0);
    return clamp(abs(x), 0.0, 1.0);
}

void main()
{
    float planeDepth = LensDepth + dot(LensDepthSlope, vLens);
    if (DepthOn > 0.5 && texture2D(SceneDepth, (gl_FragCoord.xy - Viewport.xy) / Viewport.zw).r + 0.00002 < planeDepth) discard;
    if (GlassCount > 0) {
        bool inside = false;
        vec2 a = GlassBoundary[GlassCount - 1];
        for (int i = 0; i < GlassCount; ++i) {
            vec2 b = GlassBoundary[i];
            if ((a.y > vLens.y) != (b.y > vLens.y)
                && vLens.x < (b.x - a.x) * (vLens.y - a.y) / (b.y - a.y) + a.x) inside = !inside;
            a = b;
        }
        if (!inside) discard;
    }
    float edge = RoundLens > 0.5 ? length(vLens) : max(abs(vLens.x), abs(vLens.y));
    float coverage = GlassCount > 0 ? 1.0 : clamp((1.0 - edge) / max(fwidth(edge), 0.000001), 0.0, 1.0);
    if (coverage <= 0.0 || vInvW <= 0.0) discard;
    vec4 mark = vec4(1.0);
    if (MarkKind > 0.5) {
        vec2 uv = vUVw / vInvW * UVTransform.xy + UVTransform.zw;
        uv = vec2(address(uv.x, WrapMode.x), address(uv.y, WrapMode.y));
        uv.y = 1.0 - uv.y; // Unity source UV -> unchanged PNG rows / PZ texture UV
        mark = texture2D(MarkTex, mix(TextureRect.xy, TextureRect.zw, uv));
        if (MarkKind < 1.5) mark.rgb = vec3(1.0);
    }
    vec4 vertexColor = vColorW / vInvW;
    gl_FragColor = vec4((mark.rgb * MarkColor.rgb + vec3(MarkLightness)) * vertexColor.rgb,
                     mark.a * MarkColor.a * vertexColor.a * coverage);
}
