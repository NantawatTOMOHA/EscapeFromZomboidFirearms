#version 330 compatibility
// GLSL 1.20 dialect of PZ's own shaders (gl_FragColor, texture2D): compiles as written and after PZ's GL 2.1
// rewrite of every shader on macOS (ShaderUnit.processShaderSyntax; tools/check_shader_gl21.py).

uniform sampler2D Digits;
uniform sampler2D SceneDepth;
uniform vec3 Color;
uniform vec4 Viewport;
uniform float DepthOn;
uniform float Plain; // 1: the opaque display panel texture, 0: SDF glyphs in Color
uniform vec2 DepthLinear; // view distance = x / (ndc z + y)
in vec2 vUV;
in float vDepth;
float distanceAt(float depth) {
    return DepthLinear.x/(depth*2.0-1.0+DepthLinear.y);
}
void main() {
    vec2 uv=(gl_FragCoord.xy-Viewport.xy)/Viewport.zw;
    // Hidden only by something in front of the display face; the glyph plane
    // lying on the device's own surface (a few millimetres) still shows.
    if(DepthOn>0.5) {
        float own=distanceAt(vDepth);
        if(distanceAt(texture2D(SceneDepth,uv).r)<own-0.003-0.005*own) discard;
    }
    if(Plain>0.5) {
        gl_FragColor=vec4(texture2D(Digits,vUV).rgb,1.0);
        return;
    }
    float sdf=texture2D(Digits,vUV).a;
    float edge=max(fwidth(sdf),0.01);
    float alpha=smoothstep(0.5-edge,0.5+edge,sdf);
    if(alpha<0.01) discard;
    gl_FragColor=vec4(Color,alpha);
}
