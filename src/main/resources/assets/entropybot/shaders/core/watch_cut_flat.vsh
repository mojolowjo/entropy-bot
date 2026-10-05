#version 150

// Entropy Bot watch tunnel cutaway (0.16.1): vanilla position_color plus the position in mesh space for the cut.
in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vertexColor;
out vec3 meshPos;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    meshPos = Position;
}
