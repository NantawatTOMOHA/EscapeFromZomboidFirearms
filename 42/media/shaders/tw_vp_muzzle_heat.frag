#version 330 compatibility
// GLSL 1.20 dialect of PZ's own shaders (gl_FragColor, texture2D): compiles as written and after PZ's GL 2.1
// rewrite of every shader on macOS (ShaderUnit.processShaderSyntax; tools/check_shader_gl21.py).

// Tarkov's USEHEAT on a hot weapon part in Viewpoint's frame (MuzzleHeatModel), additive: only where
// this surface is the one the frame's depth saw (not behind the hand, a wall or the gun's own front),
// per HotObject w = max(0, 1 - |Q x p|) x ((T - 30) / 10)^2, colour lerp(c1, c2, w) x w x specular term
// x VisibleHeatAlpha x HeatVisibleFactor on the HotObject's own sub-mesh (its atlas rectangle), in the
// pass's colour gain.
uniform sampler2D SceneDepth;
uniform vec4 Viewport;     // the bound viewport: x, y, width, height
uniform vec2 DepthScale;   // render size / depth texture size
uniform vec2 DepthLinear;  // view distance = x / (ndc z + y)
uniform vec4 Tolerance;    // metres: fixed, per metre of distance; z: depth on
uniform vec4 Boxes[48];    // per box: rows 0..2 of Q, c1 + specular term, c2 + visibility, atlas rectangle
uniform float Count;
uniform float Level;
uniform float Gain;
in vec3 meshPos;
in vec2 atlasUV;
float distanceAt(float depth) {
    return DepthLinear.x/(depth*2.0-1.0+DepthLinear.y);
}
void main() {
    if(Tolerance.z>0.5) {
        vec2 st=(gl_FragCoord.xy-Viewport.xy)/Viewport.zw*DepthScale;
        float scene=distanceAt(texture2D(SceneDepth,st).r);
        float own=distanceAt(gl_FragCoord.z);
        if(abs(own-scene)>Tolerance.x+Tolerance.y*own+2.0*fwidth(own)) discard;
    }
    vec4 p=vec4(meshPos,1.0);
    vec3 heat=vec3(0.0);
    for(int i=0;i<8;i++) {
        if(float(i)>=Count) break;
        vec4 rect=Boxes[i*6+5];
        if(atlasUV.x<rect.x||atlasUV.y<rect.y||atlasUV.x>rect.z||atlasUV.y>rect.w) continue;
        vec3 q=vec3(dot(Boxes[i*6],p),dot(Boxes[i*6+1],p),dot(Boxes[i*6+2],p));
        float w=max(0.0,1.0-length(q))*Level;
        heat+=mix(Boxes[i*6+3].rgb,Boxes[i*6+4].rgb,w)*w*Boxes[i*6+3].a*Boxes[i*6+4].a;
    }
    if(max(heat.r,max(heat.g,heat.b))<0.002) discard;
    gl_FragColor=vec4(heat*Gain,0.0);
}
