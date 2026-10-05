#version 150

// Entropy Bot watch tunnel cutaway (0.16.1), the flat-colour faces. Same rule as watch_cut_tex.fsh.
uniform vec4 ColorModulator;
uniform vec3 CutA;
uniform vec3 CutB;
uniform float CutRadius;

in vec4 vertexColor;
in vec3 meshPos;

out vec4 fragColor;

void main() {
    if (CutRadius > 0.0) {
        vec3 ab = CutB - CutA;
        float len2 = dot(ab, ab);
        float t = len2 > 1e-12 ? dot(meshPos - CutA, ab) / len2 : 0.0;
        if (t >= 0.0 && t <= 1.0 && distance(meshPos, CutA + ab * t) < CutRadius) {
            discard;
        }
    }
    vec4 color = vertexColor;
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}
