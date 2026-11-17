#version 330 compatibility
// GLSL 1.20 dialect of PZ's own shaders (gl_FragColor, texture2D): compiles as written and after PZ's GL 2.1
// rewrite of every shader on macOS (ShaderUnit.processShaderSyntax; tools/check_shader_gl21.py).

uniform sampler2D SceneDepth;
uniform vec4 Target;      // heat width, height; depth texture scale x, y
uniform vec2 DepthLinear; // view distance = x / (ndc z + y)
uniform vec2 Tolerance;   // metres: fixed, per metre of distance
uniform float Input;
float distanceAt(float depth) {
    return DepthLinear.x/(depth*2.0-1.0+DepthLinear.y);
}
void main() {
    vec2 uv=gl_FragCoord.xy/Target.xy;
    // The same surface, captured pose and terrain lift as the camera depth,
    // independent of diffuse RGB, normals, illumination, hair colour or blood.
    float scene=distanceAt(texture2D(SceneDepth,uv*Target.zw).r);
    float own=distanceAt(gl_FragCoord.z);
    // Only where this surface is the one the camera saw, in metres of view
    // distance: not behind another surface, and not cut away by the native
    // pass (alpha, fade, camera clear) with only the background behind it.
    if(abs(own-scene)>Tolerance.x+Tolerance.y*own+2.0*fwidth(own)) discard;
    gl_FragColor=vec4(Input,0.0,0.0,1.0);
}
