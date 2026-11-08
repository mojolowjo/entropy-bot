package io.github.mojolowjo.entropybot.cave;

import io.github.mojolowjo.entropybot.sense.Sightings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * D15 (the owner's grid, 2026-10-29: "solid vs air in 1 and 0 ... clear that there are 2 branches"): a cave read as
 * PASSAGES, not cell groups. Pure, on an air answer (the cave record's own cells, rule 23) and a seen answer.
 * <ol>
 * <li>A walkable cell: feet and head in air over a floor (the cell below not air).</li>
 * <li>Its neighbours: the four sides on the same level, or down / up a column of air of at most {@code drop} blocks
 * (the same edge both ways: Baritone falls down it or pillars up it).</li>
 * <li>A flood from the bot's cell in breadth-first layers (the walking distance); each layer split into its connected
 * pieces (side or corner neighbours of the same layer).</li>
 * <li>A piece with two or more next pieces that each go on for {@code forkLayers} layers or more is a FORK: the passage
 * ends there and each arm starts its own. A shorter piece is a bump in the wall: folded into its passage (the owner's eye
 * does the same). No next piece: a DEAD_END. The flood's cell cap reached: CAPPED (the far end not known).</li>
 * </ol>
 * Each passage: its far end (the cell of its last piece nearest that piece's middle, a walkable cell from the flood), its
 * length in layers, its widest piece, its cells (feet and head), the unseen ones among them, the ore faces along its walls
 * (the given ore cells beside its cells), and how far its first layer is from the bot (layers). Sizes come from the
 * caller (Tuning: cave.passageDrop, cave.passageForkLayers, cave.passageCells). JUnit: D15PassagesTest. Loader notes: none.
 */
public final class Passages {
    private Passages() {}

    /** The air answer: true when the cell is air of the cave. */
    public interface Air { boolean air(int x, int y, int z); }

    /** The seen answer, by cell key (Sightings.cell). */
    public interface Seen { boolean seen(long cell); }

    /** ROOM (pass two): a room node, a layer piece wider than cave.passageRoomWidth; its exits are its arms. */
    public enum EndKind { FORK, DEAD_END, CAPPED, ROOM }

    /**
     * One passage. {@code parent}: the passage it forks from (-1: the first, from the bot's cell); {@code start}/{@code end}:
     * walkable cells (x y z); {@code fromFeet}: layers from the bot's cell to its first layer.
     */
    public record Passage(int id, int parent, int[] start, int[] end, int length, int width, int fromFeet, List<Long> cells,
                          int unseen, int oreFaces, EndKind endKind) {
        /** Its far end is not explored: cells of it not seen yet, or the flood stopped before its end. */
        public boolean open() { return unseen > 0 || endKind == EndKind.CAPPED; }

        public long key() { return Sightings.cell(end[0], end[1], end[2]); }

        /** Pass two: the same passage with its unseen count measured again (the record's seen cells moved on). */
        public Passage withUnseen(int n) { return new Passage(id, parent, start, end, length, width, fromFeet, cells, n, oreFaces, endKind); }
    }

    /**
     * The result: the passages (empty: no walkable cell at the feet, nor cave air within {@code near}), and why when empty
     * (rule 7). Pass two: {@code start} the walkable cell the flood began at; {@code lip} true when it is not under the feet
     * (the bot stands at the lip, outside the cave's air: the flood began at the nearest walkable cave cell).
     */
    public record Found(List<Passage> passages, String why, boolean capped, int[] start, boolean lip) {
        public Found(List<Passage> passages, String why, boolean capped) { this(passages, why, capped, null, false); }
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** A walkable cell: feet and head in air, the floor not air. */
    public static boolean walkable(Air a, int x, int y, int z) { return a.air(x, y, z) && a.air(x, y + 1, z) && !a.air(x, y - 1, z); }

    /** The walkable neighbours of a walkable cell (the same edges both ways). */
    static List<long[]> neighbours(Air a, int x, int y, int z, int drop) {
        List<long[]> out = new ArrayList<>();
        for (int[] s : SIDES) {
            int nx = x + s[0], nz = z + s[1];
            if (walkable(a, nx, y, nz)) { out.add(new long[]{nx, y, nz}); continue; }
            // down: a column of air beside, the first floor within drop
            if (a.air(nx, y, nz) && a.air(nx, y + 1, nz)) {
                for (int k = 1; k <= drop; k++) {
                    if (walkable(a, nx, y - k, nz)) { out.add(new long[]{nx, y - k, nz}); break; }
                    if (!a.air(nx, y - k, nz)) break;
                }
            }
            // up: the reverse of a down edge - a column of air over these feet, a floor beside within drop
            for (int k = 1; k <= drop; k++) {
                if (!a.air(x, y + k + 1, z)) break;
                if (walkable(a, nx, y + k, nz)) { out.add(new long[]{nx, y + k, nz}); break; }
            }
        }
        return out;
    }

    /** The walkable cell the flood starts from: the feet, else the first floor below within drop; null: none. */
    static int[] startCell(Air a, int[] feet, int drop) {
        for (int k = 0; k <= drop; k++) if (walkable(a, feet[0], feet[1] - k, feet[2])) return new int[]{feet[0], feet[1] - k, feet[2]};
        return null;
    }

    /**
     * The passages from {@code feet}. {@code ore}: ore cells seen and not mined (their faces counted along the walls);
     * {@code drop}, {@code forkLayers}, {@code cap}: cave.passageDrop, cave.passageForkLayers, cave.passageCells.
     */
    public static Found build(Air a, Seen seen, int[] feet, Set<Long> ore, int drop, int forkLayers, int cap) {
        return build(a, seen, feet, ore, drop, forkLayers, cap, Integer.MAX_VALUE, 0);
    }

    /**
     * Pass two, the lip: the walkable cave cell nearest the feet within {@code near} blocks (sides and up; down by
     * {@code near} + drop, the floor under a lip); null: none.
     */
    static int[] nearCell(Air a, int[] feet, int drop, int near) {
        int[] best = null;
        long bd = Long.MAX_VALUE;
        for (int dy = -near - drop; dy <= near; dy++)
            for (int dx = -near; dx <= near; dx++)
                for (int dz = -near; dz <= near; dz++) {
                    if (!walkable(a, feet[0] + dx, feet[1] + dy, feet[2] + dz)) continue;
                    long d = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                    if (d < bd) { bd = d; best = new int[]{feet[0] + dx, feet[1] + dy, feet[2] + dz}; }
                }
        return best;
    }

    /**
     * Pass two: as above; {@code roomWidth} (cave.passageRoomWidth): a layer piece wider than it is a ROOM node - the
     * passage ends there with the room's cells, and every exit of the room that goes on {@code forkLayers} layers is an
     * arm; {@code near} (cave.passageLipNear): no walkable cell at the feet - the flood starts at the nearest walkable cave
     * cell within it (the lip).
     */
    public static Found build(Air a, Seen seen, int[] feet, Set<Long> ore, int drop, int forkLayers, int cap, int roomWidth, int near) {
        if (feet == null || feet.length < 3) return new Found(List.of(), "the feet are not known", false);   // structural: x y z
        int[] s0 = startCell(a, feet, drop);
        boolean lip = false;
        if (s0 == null && near > 0) { s0 = nearCell(a, feet, drop, near); lip = s0 != null; }
        if (s0 == null) return new Found(List.of(), "no walkable cave cell at the feet " + feet[0] + " " + feet[1] + " " + feet[2] + " (nor a floor within cave.passageDrop " + drop + " below"
                + (near > 0 ? ", nor a walkable cave cell within cave.passageLipNear " + near : "") + ")", false);
        // 1. the flood in layers
        Map<Long, Integer> dist = new HashMap<>();
        List<List<Long>> layers = new ArrayList<>();
        long k0 = Sightings.cell(s0[0], s0[1], s0[2]);
        dist.put(k0, 0);
        layers.add(new ArrayList<>(List.of(k0)));
        Map<Long, List<Long>> adj = new HashMap<>();
        boolean capped = false;
        ArrayDeque<Long> q = new ArrayDeque<>(List.of(k0));
        while (!q.isEmpty()) {
            long c = q.poll();
            int[] p = Sightings.cellOf(c);
            List<Long> ns = new ArrayList<>();
            for (long[] n : neighbours(a, p[0], p[1], p[2], drop)) ns.add(Sightings.cell((int) n[0], (int) n[1], (int) n[2]));
            adj.put(c, ns);
            int d = dist.get(c);
            for (long n : ns) {
                if (dist.containsKey(n)) continue;
                if (dist.size() >= cap) { capped = true; continue; }
                dist.put(n, d + 1);
                if (layers.size() <= d + 1) layers.add(new ArrayList<>());
                layers.get(d + 1).add(n);
                q.add(n);
            }
        }
        // 2. each layer's pieces (union of same-layer cells that touch, sides or corners)
        Map<Long, Integer> pieceOf = new HashMap<>();
        List<List<Long>> pieces = new ArrayList<>();
        List<Integer> pieceLayer = new ArrayList<>();
        for (int d = 0; d < layers.size(); d++) {
            Set<Long> layer = new HashSet<>(layers.get(d));
            for (long c : layers.get(d)) {
                if (pieceOf.containsKey(c)) continue;
                int id = pieces.size();
                List<Long> piece = new ArrayList<>();
                ArrayDeque<Long> f = new ArrayDeque<>(List.of(c));
                pieceOf.put(c, id);
                while (!f.isEmpty()) {
                    long u = f.poll();
                    piece.add(u);
                    int[] p = Sightings.cellOf(u);
                    for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                        long n = Sightings.cell(p[0] + dx, p[1] + dy, p[2] + dz);
                        if (layer.contains(n) && !pieceOf.containsKey(n)) { pieceOf.put(n, id); f.add(n); }
                    }
                    for (long n : adj.getOrDefault(u, List.of())) if (layer.contains(n) && !pieceOf.containsKey(n)) { pieceOf.put(n, id); f.add(n); }
                }
                pieces.add(piece);
                pieceLayer.add(d);
            }
        }
        // 3. the tree: a piece's parent is the lowest piece of the layer before that holds a neighbour of it (a loop's
        // second parent is a merge: the piece stays with its first)
        int np = pieces.size();
        int[] parent = new int[np];
        List<List<Integer>> kids = new ArrayList<>();
        for (int i = 0; i < np; i++) kids.add(new ArrayList<>());
        for (int i = 0; i < np; i++) {
            parent[i] = -1;
            int d = pieceLayer.get(i);
            if (d == 0) continue;
            for (long c : pieces.get(i)) for (long n : adj.getOrDefault(c, List.of())) {
                Integer dn = dist.get(n);
                if (dn != null && dn == d - 1) {
                    int pp = pieceOf.get(n);
                    if (parent[i] < 0 || pp < parent[i]) parent[i] = pp;
                }
            }
            if (parent[i] >= 0) kids.get(parent[i]).add(i);
        }
        // the layers each piece goes on for (itself and its deepest line below); pieces are in layer order, so backwards
        int[] depth = new int[np];
        for (int i = np - 1; i >= 0; i--) {
            int best = 0;
            for (int k : kids.get(i)) best = Math.max(best, depth[k]);
            depth[i] = 1 + best;
        }
        Set<Long> lastLayer = capped ? new HashSet<>(layers.get(layers.size() - 1)) : Set.of();
        // 4. the passages
        List<Passage> out = new ArrayList<>();
        ArrayDeque<int[]> todo = new ArrayDeque<>();           // {first piece, parent passage}
        todo.add(new int[]{0, -1});
        while (!todo.isEmpty()) {
            int[] t = todo.poll();
            int id = out.size();
            out.add(null);                                     // its place, filled below
            int cur = t[0];
            List<Long> cells = new ArrayList<>();
            int width = 0, length = 0;
            EndKind end;
            List<Long> roomCells = null;
            while (true) {
                if (pieces.get(cur).size() > roomWidth) {
                    // pass two: a ROOM node - this piece and every wide piece below it; the narrow pieces leaving it are
                    // its exits: an arm each when it goes on forkLayers layers, else folded (a nook of the room)
                    roomCells = new ArrayList<>();
                    List<Integer> exits = new ArrayList<>();
                    ArrayDeque<Integer> rf = new ArrayDeque<>(List.of(cur));
                    int last = pieceLayer.get(cur);
                    while (!rf.isEmpty()) {
                        int i = rf.poll();
                        roomCells.addAll(pieces.get(i));
                        width = Math.max(width, pieces.get(i).size());
                        last = Math.max(last, pieceLayer.get(i));
                        for (int k : kids.get(i)) if (pieces.get(k).size() > roomWidth) rf.add(k); else exits.add(k);
                    }
                    cells.addAll(roomCells);
                    length += last - pieceLayer.get(cur) + 1;
                    for (int k : exits) {
                        if (depth[k] >= forkLayers) todo.add(new int[]{k, id});
                        else fold(k, kids, pieces, cells);
                    }
                    end = EndKind.ROOM;
                    break;
                }
                cells.addAll(pieces.get(cur));
                width = Math.max(width, pieces.get(cur).size());
                length++;
                List<Integer> ks = kids.get(cur);
                List<Integer> arms = new ArrayList<>();
                for (int k : ks) if (depth[k] >= forkLayers) arms.add(k);
                if (arms.size() >= 2) {
                    for (int k : ks) if (!arms.contains(k)) fold(k, kids, pieces, cells);
                    for (int k : arms) todo.add(new int[]{k, id});
                    end = EndKind.FORK;
                    break;
                }
                if (ks.isEmpty()) {
                    boolean atCap = false;
                    for (long c : pieces.get(cur)) if (lastLayer.contains(c)) { atCap = true; break; }
                    end = atCap ? EndKind.CAPPED : EndKind.DEAD_END;
                    break;
                }
                int next = arms.size() == 1 ? arms.get(0) : deepest(ks, depth);
                for (int k : ks) if (k != next) fold(k, kids, pieces, cells);
                cur = next;
            }
            int[] start = middle(pieces.get(t[0]));
            int[] endCell = middle(roomCells != null ? roomCells : pieces.get(cur));   // a room: its middle
            Set<Long> all = new HashSet<>();
            for (long c : cells) {
                all.add(c);
                int[] p = Sightings.cellOf(c);
                all.add(Sightings.cell(p[0], p[1] + 1, p[2]));  // the head cell
            }
            int unseen = 0;
            for (long c : all) if (!seen.seen(c)) unseen++;
            Set<Long> faces = new HashSet<>();
            if (ore != null && !ore.isEmpty()) for (long c : all) {
                int[] p = Sightings.cellOf(c);
                for (int[] o : SIX) {
                    long n = Sightings.cell(p[0] + o[0], p[1] + o[1], p[2] + o[2]);
                    if (ore.contains(n)) faces.add(n);
                }
            }
            out.set(id, new Passage(id, t[1], start, endCell, length, width, pieceLayer.get(t[0]), List.copyOf(all), unseen, faces.size(), end));
        }
        return new Found(List.copyOf(out), null, capped, s0, lip);
    }

    private static final int[][] SIX = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private static int deepest(List<Integer> ks, int[] depth) {
        int best = ks.get(0);
        for (int k : ks) if (depth[k] > depth[best]) best = k;
        return best;
    }

    /** A bump's cells (the piece and all below it) into the passage. */
    private static void fold(int k, List<List<Integer>> kids, List<List<Long>> pieces, List<Long> cells) {
        ArrayDeque<Integer> f = new ArrayDeque<>(List.of(k));
        while (!f.isEmpty()) {
            int i = f.poll();
            cells.addAll(pieces.get(i));
            f.addAll(kids.get(i));
        }
    }

    /** The piece's cell nearest its middle (a cell of the piece: walkable). */
    private static int[] middle(List<Long> piece) {
        double mx = 0, my = 0, mz = 0;
        for (long c : piece) { int[] p = Sightings.cellOf(c); mx += p[0]; my += p[1]; mz += p[2]; }
        mx /= piece.size(); my /= piece.size(); mz /= piece.size();
        int[] best = null;
        double bd = Double.MAX_VALUE;
        for (long c : piece) {
            int[] p = Sightings.cellOf(c);
            double d = (p[0] - mx) * (p[0] - mx) + (p[1] - my) * (p[1] - my) + (p[2] - mz) * (p[2] - mz);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }
}
