#version 330 compatibility
layout(location=0) in vec4 vertex;
layout(location=4) in vec2 uv;
uniform mat4 Model;
uniform mat4 ViewProjection;
// Viewpoint's terrain lift of this record, as tw_vp_heat_model.vert.
uniform vec4 Lift;
uniform vec2 LiftAt;
out vec2 texCoords;
void main() {
    vec4 world=Model*vec4(vertex.xyz,1.0);
    world.y+=Lift.x+(1.0-Lift.w)*dot(Lift.yz,world.xz-LiftAt);
    gl_Position=ViewProjection*world;
    texCoords=uv;
}
