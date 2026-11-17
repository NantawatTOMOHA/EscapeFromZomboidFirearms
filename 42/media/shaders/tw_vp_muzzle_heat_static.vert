#version 330 compatibility
// The visible heat of a hot weapon part on its own surface in Viewpoint's frame (MuzzleHeatModel): the
// frame's captured record of the part, its matrix and terrain lift as tw_vp_heat_model_static.vert;
// the position in the part's exported mesh space for its HeatBounds.
layout(location=0) in vec4 vertex;
layout(location=2) in vec2 uv;
uniform mat4 Model;
uniform mat4 MeshFrame;
uniform mat4 ViewProjection;
uniform vec4 Lift;
uniform vec2 LiftAt;
out vec3 meshPos;
out vec2 atlasUV;
void main() {
    vec4 world=Model*vec4(vertex.xyz,1.0);
    world.y+=Lift.x+(1.0-Lift.w)*dot(Lift.yz,world.xz-LiftAt);
    gl_Position=ViewProjection*world;
    meshPos=(MeshFrame*vec4(vertex.xyz,1.0)).xyz;
    atlasUV=uv;
}
