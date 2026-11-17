#version 330 compatibility
layout(location=0) in vec4 vertex;
layout(location=1) in vec4 normal;
layout(location=2) in vec4 boneWeights;
layout(location=3) in vec4 boneIndices;
layout(location=4) in vec2 uv;
uniform mat4 MatrixPalette[128];
uniform mat4 Model;
uniform mat4 ViewProjection;
// Viewpoint's terrain lift of this record (ModelHeatViewpoint.lift): lift at
// LiftAt, its slope along scene x and z, w 1 for one rigid lift.
uniform vec4 Lift;
uniform vec2 LiftAt;
out vec2 texCoords;
void main() {
    vec4 p=vec4(vertex.xyz,1.0);
    mat4 skin=mat4(0.0);
    for(int i=0;i<4;i++) if(boneWeights[i]>0.0) skin+=MatrixPalette[int(boneIndices[i])]*boneWeights[i];
    if(dot(boneWeights,vec4(1.0))<=0.0) skin=mat4(1.0);
    vec4 world=Model*skin*p;
    world.y+=Lift.x+(1.0-Lift.w)*dot(Lift.yz,world.xz-LiftAt);
    gl_Position=ViewProjection*world;
    texCoords=uv;
}
