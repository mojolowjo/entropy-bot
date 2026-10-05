package io.github.mojolowjo.entropybot.watchview;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What the tunnel view draws: the dug-tunnel shell ({@link Shell}, always kept) together with the faces the bot's view
 * has seen ({@link SeenFaces}: the saved store, and since 0.16.1 the in-memory surface store of faces under open sky),
 * nearest to the bot first, under one face cap. A face in more than one source is drawn once (shell first, then the saved
 * store). A seen face is drawn only while the game would still show it ({@link SeenRays#stillShows}: its block is still
 * something visible, the cell in front is loaded and not opaque). Pure (JUnit). No boxed sort or set since 0.16.1.
 */
public final class SeenMesh {
    /**
     * A face to draw. seen: it came from a seen store (not the shell); surface: from the surface store (seen is then true
     * too); light: the brightest it was seen at (shell: 15).
     */
    public record MeshFace(int x, int y, int z, int side, boolean seen, int light, boolean surface) {
        public MeshFace(int x, int y, int z, int side, boolean seen, int light) {
            this(x, y, z, side, seen, light, false);
        }
    }

    public record Result(List<MeshFace> faces, boolean capped, int shellFaces, int seenFaces, int surfaceFaces) {}

    private SeenMesh() {}

    static long faceKey(int x, int y, int z, int side) {
        return CellKey.of(x, y, z) * 8 + side;
    }

    /** The 0.16.0 form (no surface store), kept for its callers and tests; w is the old Shell world (OPEN/SOLID/UNKNOWN). */
    public static Result union(List<Shell.Face> shell, boolean shellCapped, List<SeenFaces.Entry> seen, Shell.World w, int cx, int cy, int cz, int maxFaces) {
        return union(shell, shellCapped, seen, List.of(), fromShellWorld(w), cx, cy, cz, maxFaces);
    }

    /** A Shell world read as ray cells: SOLID = HIT, OPEN = PASS, UNKNOWN = STOP. */
    public static SeenRays.Cells fromShellWorld(Shell.World w) {
        return (x, y, z) -> switch (w.kind(x, y, z)) {
            case Shell.SOLID -> SeenRays.HIT;
            case Shell.OPEN -> SeenRays.PASS;
            default -> SeenRays.STOP;
        };
    }

    public static Result union(List<Shell.Face> shell, boolean shellCapped, List<SeenFaces.Entry> seen, List<SeenFaces.Entry> surface,
                               SeenRays.Cells w, int cx, int cy, int cz, int maxFaces) {
        List<MeshFace> all = new ArrayList<>(shell.size() + seen.size() * 2 + surface.size() * 2);
        long[] shellKeys = new long[shell.size()];
        int i = 0;
        for (Shell.Face f : shell) {
            shellKeys[i++] = faceKey(f.x(), f.y(), f.z(), f.side());
            all.add(new MeshFace(f.x(), f.y(), f.z(), f.side(), false, 15, false));
        }
        Arrays.sort(shellKeys);
        int seenStart = all.size();
        add(all, seen, w, shellKeys, null, false);
        long[] seenKeys = new long[all.size() - seenStart];
        for (int j = seenStart; j < all.size(); j++) {
            MeshFace f = all.get(j);
            seenKeys[j - seenStart] = faceKey(f.x(), f.y(), f.z(), f.side());
        }
        Arrays.sort(seenKeys);
        add(all, surface, w, shellKeys, seenKeys, true);
        boolean capped = shellCapped;
        // nearest first without boxing: distance squared in the high bits, the index in the low 32
        long[] order = new long[all.size()];
        for (int j = 0; j < order.length; j++) order[j] = dist2(all.get(j), cx, cy, cz) << 32 | j;
        Arrays.sort(order);
        int keep = Math.min(order.length, Math.max(0, maxFaces));
        if (order.length > keep) capped = true;
        List<MeshFace> out = new ArrayList<>(keep);
        int seenN = 0, surfN = 0;
        for (int j = 0; j < keep; j++) {
            MeshFace f = all.get((int) (order[j] & 0xFFFFFFFFL));
            out.add(f);
            if (f.surface()) surfN++;
            else if (f.seen()) seenN++;
        }
        return new Result(out, capped, out.size() - seenN - surfN, seenN, surfN);
    }

    private static void add(List<MeshFace> all, List<SeenFaces.Entry> entries, SeenRays.Cells w, long[] skip1, long[] skip2, boolean surface) {
        for (SeenFaces.Entry e : entries) {
            int x = CellKey.x(e.key()), y = CellKey.y(e.key()), z = CellKey.z(e.key());
            int k = -1;
            for (int s = 0; s < 6; s++) {
                if ((e.mask() & (1 << s)) == 0) continue;
                long fk = faceKey(x, y, z, s);
                if (Arrays.binarySearch(skip1, fk) >= 0) continue;
                if (skip2 != null && Arrays.binarySearch(skip2, fk) >= 0) continue;
                if (k < 0) {
                    k = w.cell(x, y, z);
                    if (!SeenRays.drawable(k)) break;                 // mined or changed since: nothing of it is drawn
                }
                int[] o = Shell.OFF[s];
                if (!SeenRays.stillShows(k, w.cell(x + o[0], y + o[1], z + o[2]))) continue;   // covered since, or not loaded
                all.add(new MeshFace(x, y, z, s, true, e.light(), surface));
            }
        }
    }

    private static long dist2(MeshFace f, int cx, int cy, int cz) {
        long dx = f.x() - cx, dy = f.y() - cy, dz = f.z() - cz;
        return Math.min(Integer.MAX_VALUE, dx * dx + dy * dy + dz * dz);
    }
}
