package io.github.mojolowjo.entropybot.watchview;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the tunnel view draws since 0.16.0: the dug-tunnel shell ({@link Shell}, always kept) together with the faces
 * the bot's view has seen ({@link SeenFaces}), nearest first, under one face cap. A seen face that is also a shell face
 * is drawn once, as a shell face. A seen face is drawn only while it is still a real exposed face: its block is a full
 * solid block now and the cell on its seen side is open and loaded (the same test the shell makes). Pure (JUnit).
 */
public final class SeenMesh {
    /** A face to draw. seen: it came from the seen store (not the shell); light: the brightest it was seen at (shell: 15). */
    public record MeshFace(int x, int y, int z, int side, boolean seen, int light) {}

    public record Result(List<MeshFace> faces, boolean capped, int shellFaces, int seenFaces) {}

    private SeenMesh() {}

    static long faceKey(int x, int y, int z, int side) {
        return CellKey.of(x, y, z) * 8 + side;
    }

    public static Result union(List<Shell.Face> shell, boolean shellCapped, List<SeenFaces.Entry> seen, Shell.World w, int cx, int cy, int cz, int maxFaces) {
        List<MeshFace> all = new ArrayList<>(shell.size() + seen.size() * 2);
        Set<Long> shellKeys = new HashSet<>();
        for (Shell.Face f : shell) {
            shellKeys.add(faceKey(f.x(), f.y(), f.z(), f.side()));
            all.add(new MeshFace(f.x(), f.y(), f.z(), f.side(), false, 15));
        }
        for (SeenFaces.Entry e : seen) {
            int x = CellKey.x(e.key()), y = CellKey.y(e.key()), z = CellKey.z(e.key());
            boolean solidChecked = false;
            for (int s = 0; s < 6; s++) {
                if ((e.mask() & (1 << s)) == 0) continue;
                if (shellKeys.contains(faceKey(x, y, z, s))) continue;
                if (!solidChecked) {
                    if (w.kind(x, y, z) != Shell.SOLID) break;          // mined or changed since: nothing of it is drawn
                    solidChecked = true;
                }
                int[] o = Shell.OFF[s];
                if (w.kind(x + o[0], y + o[1], z + o[2]) != Shell.OPEN) continue;   // covered since, or not loaded
                all.add(new MeshFace(x, y, z, s, true, e.light()));
            }
        }
        boolean capped = shellCapped;
        all.sort(Comparator.comparingLong(f -> dist2(f, cx, cy, cz)));
        if (all.size() > maxFaces) {
            all = new ArrayList<>(all.subList(0, maxFaces));
            capped = true;
        }
        int seenN = 0;
        for (MeshFace f : all) if (f.seen()) seenN++;
        return new Result(all, capped, all.size() - seenN, seenN);
    }

    private static long dist2(MeshFace f, int cx, int cy, int cz) {
        long dx = f.x() - cx, dy = f.y() - cy, dz = f.z() - cz;
        return dx * dx + dy * dy + dz * dz;
    }
}
