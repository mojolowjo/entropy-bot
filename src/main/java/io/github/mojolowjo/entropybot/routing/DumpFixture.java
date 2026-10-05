package io.github.mojolowjo.entropybot.routing;

import io.github.mojolowjo.entropybot.route.CellMoves;
import io.github.mojolowjo.entropybot.route.MoveSink;
import io.github.mojolowjo.entropybot.route.SectionKey;
import io.github.mojolowjo.entropybot.route.SectionRecord;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code route dump <x y z>}: one box's standable cells and moves as a text file that JUnit reads back as a
 * {@link CellMoves} (R1's golden tests). Plain text, one line each:
 * <pre>
 * # entropybot route dump v1
 * box 0 -2 3 11          (dim sx sy sz)
 * quality LIVE
 * margin 2               (cells and moves cover the box plus this many blocks on every side)
 * settings 1a2b...       (RouteHashes.settings of the Baritone settings it was made with, hex)
 * note free text
 * c x y z                (a standable cell; its moves follow)
 * m x y z ticks          (a move from the last c to (x, y, z))
 * </pre>
 * Ticks are written with full double precision, so a read-back gives exactly Baritone's costs.
 */
public final class DumpFixture {
    public static final String MAGIC = "# entropybot route dump v1";
    public static final int MARGIN = 2;

    private DumpFixture() {
    }

    /** What a write found. */
    public record Counts(int cells, int moves) {
    }

    /** Writes the box (plus {@link #MARGIN}) from {@code moves}. Runs where {@code moves} may be used (one thread). */
    public static Counts write(Appendable out, SectionKey key, SectionRecord.Quality quality, long settingsHash,
                               String note, CellMoves moves) throws IOException {
        out.append(MAGIC).append('\n');
        out.append("box ").append(String.valueOf(key.dim())).append(' ').append(String.valueOf(key.sx())).append(' ')
                .append(String.valueOf(key.sy())).append(' ').append(String.valueOf(key.sz())).append('\n');
        out.append("quality ").append(quality == null ? "UNKNOWN" : quality.name()).append('\n');
        out.append("margin ").append(String.valueOf(MARGIN)).append('\n');
        out.append("settings ").append(Long.toHexString(settingsHash)).append('\n');
        if (note != null && !note.isBlank()) out.append("note ").append(note.replace('\n', ' ')).append('\n');
        int cells = 0;
        int[] nMoves = {0};
        StringBuilder line = new StringBuilder();
        for (int y = key.minY() - MARGIN; y <= key.minY() + 15 + MARGIN; y++)
            for (int z = key.minZ() - MARGIN; z <= key.minZ() + 15 + MARGIN; z++)
                for (int x = key.minX() - MARGIN; x <= key.minX() + 15 + MARGIN; x++) {
                    if (!moves.standable(x, y, z)) continue;
                    cells++;
                    out.append("c ").append(String.valueOf(x)).append(' ').append(String.valueOf(y)).append(' ')
                            .append(String.valueOf(z)).append('\n');
                    line.setLength(0);
                    moves.forEachMove(x, y, z, (tx, ty, tz, ticks) -> {
                        nMoves[0]++;
                        line.append("m ").append(tx).append(' ').append(ty).append(' ').append(tz).append(' ')
                                .append(Double.toString(ticks)).append('\n');
                    });
                    out.append(line);
                }
        return new Counts(cells, nMoves[0]);
    }

    /** A dump read back. */
    public record Fixture(SectionKey key, String quality, int margin, long settingsHash, String note, FixtureMoves moves) {
    }

    /** Reads a dump. Throws IOException with the line number on a bad line. */
    public static Fixture read(Reader in) throws IOException {
        BufferedReader r = new BufferedReader(in);
        String first = r.readLine();
        if (!MAGIC.equals(first)) throw new IOException("not a route dump (first line " + first + ")");
        SectionKey key = null;
        String quality = "UNKNOWN", note = "";
        int margin = MARGIN;
        long settings = 0;
        FixtureMoves fm = new FixtureMoves();
        long cur = Long.MIN_VALUE;
        String s;
        int no = 1;
        while ((s = r.readLine()) != null) {
            no++;
            if (s.isBlank() || s.startsWith("#")) continue;
            String[] p = s.trim().split("\\s+");
            try {
                switch (p[0]) {
                    case "box" -> key = new SectionKey(Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                            Integer.parseInt(p[3]), Integer.parseInt(p[4]));
                    case "quality" -> quality = p[1];
                    case "margin" -> margin = Integer.parseInt(p[1]);
                    case "settings" -> settings = Long.parseUnsignedLong(p[1], 16);
                    case "note" -> note = s.trim().substring(4).trim();
                    case "c" -> cur = fm.addCell(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                    case "m" -> {
                        if (cur == Long.MIN_VALUE) throw new IOException("line " + no + ": a move before any cell");
                        fm.addMove(cur, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                                Double.parseDouble(p[4]));
                    }
                    default -> throw new IOException("line " + no + ": unknown line '" + s + "'");
                }
            } catch (RuntimeException e) {
                throw new IOException("line " + no + ": " + e, e);
            }
        }
        if (key == null) throw new IOException("no box line");
        return new Fixture(key, quality, margin, settings, note, fm);
    }

    /** The moves of a dump, as a {@link CellMoves} for JUnit. Cells not in the dump are not standable. */
    public static final class FixtureMoves implements CellMoves {
        private final Set<Long> cells = new HashSet<>();
        private final Map<Long, List<double[]>> moves = new HashMap<>();

        static long pack(int x, int y, int z) {
            return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
        }

        long addCell(int x, int y, int z) {
            long k = pack(x, y, z);
            cells.add(k);
            return k;
        }

        void addMove(long from, int x, int y, int z, double ticks) {
            moves.computeIfAbsent(from, k -> new ArrayList<>()).add(new double[]{x, y, z, ticks});
        }

        public int cellCount() {
            return cells.size();
        }

        @Override
        public boolean standable(int x, int y, int z) {
            return cells.contains(pack(x, y, z));
        }

        @Override
        public void forEachMove(int x, int y, int z, MoveSink s) {
            List<double[]> l = moves.get(pack(x, y, z));
            if (l == null) return;
            for (double[] m : l) s.move((int) m[0], (int) m[1], (int) m[2], m[3]);
        }
    }

    /** The dump file name for a box: {@code box.<sx>.<sy>.<sz>.<stamp>.txt}. */
    public static String fileName(SectionKey k, String stamp) {
        return String.format(Locale.ROOT, "box.%d.%d.%d.%s.txt", k.sx(), k.sy(), k.sz(), stamp);
    }
}
