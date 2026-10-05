#version 150

// Entropy Bot watch tunnel faces (0.17.1), the flat-colour ones (no sprite found). Same cutaway as watch_cut_tex.fsh.
uniform vec4 ColorModulator;
uniform vec3 CutCam;
uniform vec3 CutMin;
uniform vec3 CutMax;
uniform vec2 CutMargin;

in vec3 meshPos;

// 0.17.1: the same rule as io.github.mojolowjo.entropybot.watchview.Cutaway.cuts. A fragment is cut when the ray from
// the camera (CutCam) through it goes on to hit the bot's box (CutMin..CutMax; grown by the margin at the sides and top)
// and the fragment lies before the box: it covers the bot (or the margin round it) and is nearer. The margin is picked
// per pixel between CutMargin.x and CutMargin.y by a 4x4 ordered dither (a stippled rim). CutMargin.y < 0: no cut.
// All positions are in this mesh's space (the Java side moves them per draw).
const float BAYER[16] = float[16](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0);

float cutEnter(vec3 o, vec3 d, vec3 lo, vec3 hi) {
    vec3 dd = vec3(abs(d.x) < 1e-9 ? (d.x < 0.0 ? -1e-9 : 1e-9) : d.x,
                   abs(d.y) < 1e-9 ? (d.y < 0.0 ? -1e-9 : 1e-9) : d.y,
                   abs(d.z) < 1e-9 ? (d.z < 0.0 ? -1e-9 : 1e-9) : d.z);
    vec3 t0 = (lo - o) / dd;
    vec3 t1 = (hi - o) / dd;
    vec3 tmin = min(t0, t1);
    vec3 tmax = max(t0, t1);
    float tn = max(max(tmin.x, tmin.y), tmin.z);
    float tf = min(min(tmax.x, tmax.y), tmax.z);
    if (tf < max(tn, 0.0) || tn < 0.0) return -1.0;
    return tn;
}

bool cutHere() {
    if (CutMargin.y < 0.0) return false;
    vec3 v = meshPos - CutCam;
    float dist = length(v);
    if (dist < 1e-4) return false;
    vec3 dir = v / dist;
    ivec2 p = ivec2(mod(gl_FragCoord.xy, 4.0));
    float dither = (BAYER[p.x + 4 * p.y] + 0.5) / 16.0;
    float m = mix(CutMargin.x, CutMargin.y, dither);
    vec3 lo = CutMin;
    lo.y += 0.1;
    float grown = cutEnter(CutCam, dir, vec3(lo.x - m, lo.y, lo.z - m), CutMax + vec3(m));
    if (grown < 0.0) return false;
    float real = cutEnter(CutCam, dir, lo, CutMax);
    float limit = real >= 0.0 ? real : grown;
    return dist < limit - 0.02;
}
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    if (cutHere()) {
        discard;
    }
    vec4 color = vertexColor;
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}