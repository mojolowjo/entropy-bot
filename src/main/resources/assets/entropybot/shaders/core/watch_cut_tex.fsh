#version 150

// Entropy Bot watch tunnel cutaway (0.16.1). Same rule as io.github.mojolowjo.entropybot.watchview.Cutaway.cuts:
// a fragment whose projection on the camera (CutA) -> bot (CutB) segment falls within it and lies closer than
// CutRadius to that line is not drawn. CutRadius 0 = no cut. Otherwise vanilla position_tex_color.
uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform vec3 CutA;
uniform vec3 CutB;
uniform float CutRadius;

in vec2 texCoord0;
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
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}
