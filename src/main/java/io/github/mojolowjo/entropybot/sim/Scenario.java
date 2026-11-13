package io.github.mojolowjo.entropybot.sim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * sim step 1 (owner 2026-11-13: "give in information, it does calculations, spits out an answer. then we know and see
 * where it failed"; to-do/simulator-plan.md): a SCENARIO - what the simulator is given. One moment of a game as plain
 * data: the blocks in a box round the bot (each cell a block state's text as the game writes it,
 * "minecraft:oak_stairs[facing=north,...]"; {@link #NOT_LOADED} where the client had no chunk), its feet, health, bag,
 * the orders and status as the mod said them, and the last decision lines. Written by the game (commands.ScenarioDump:
 * on a command, or at a failed end when debug.scenarioOnFail is on), read by tests and the simulator.
 *
 * <p>The blocks: a palette of state texts and run-length pairs (count, palette index) over the box in the order y, z, x
 * (x fastest). A file is gzip JSON, {@link #VERSION} in it; a reader refuses another version, said (rule 7).
 * Pure: no Minecraft types (JUnit: ScenarioTest). Loader notes: none.
 */
public final class Scenario {
    /** structural: the file format's version; a change of the fields below raises it */
    public static final int VERSION = 1;
    /** The palette text of a cell the client had no chunk for (rule 15: not known, never air). */
    public static final String NOT_LOADED = "?";

    /** One bag slot: the inventory slot index, the item id, its count and damage. */
    public record Slot(int slot, String item, int n, int damage) {}

    public int v = VERSION;
    public String when, why, dim;
    public int[] feet = new int[3];
    public double hp, food;
    /** The box: its low corner and its sizes. */
    public int x0, y0, z0, sx, sy, sz;
    public List<String> palette = new ArrayList<>();
    /** Run-length pairs: count, palette index, count, palette index, ... */
    public int[] runs = new int[0];
    public List<Slot> bag = new ArrayList<>();
    /** The mod's own words at that moment: aa goals, aa status, the main stations, the last decisions. */
    public String orders, status, stations;
    public List<String> lines = new ArrayList<>();

    private transient int[] cells;

    /** The run-length pairs of {@code cells} (palette indices in box order). Pure. */
    public static int[] rle(int[] cells) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < cells.length; ) {
            int j = i;
            while (j < cells.length && cells[j] == cells[i]) j++;
            out.add(j - i);
            out.add(cells[i]);
            i = j;
        }
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /** The cells of run-length pairs; {@code size} the cells expected - another count is an error, said. Pure. */
    public static int[] unrle(int[] runs, int size) {
        if (runs.length % 2 != 0) throw new IllegalArgumentException("scenario: the block runs are not pairs (" + runs.length + " numbers)");
        int[] cells = new int[size];
        int at = 0;
        for (int i = 0; i < runs.length; i += 2) {
            int n = runs[i];
            if (n <= 0 || at + n > size) throw new IllegalArgumentException("scenario: the block runs hold more than the box's " + size + " cells (run " + i / 2 + ": " + n + " at " + at + ")");
            java.util.Arrays.fill(cells, at, at + n, runs[i + 1]);
            at += n;
        }
        if (at != size) throw new IllegalArgumentException("scenario: the block runs hold " + at + " cells, the box " + size);
        return cells;
    }

    /** The box's cells: sx * sy * sz. */
    public int size() { return sx * sy * sz; }

    /** The cell's index in box order (y, z, x), or -1 outside the box. */
    public int index(int x, int y, int z) {
        int dx = x - x0, dy = y - y0, dz = z - z0;
        if (dx < 0 || dy < 0 || dz < 0 || dx >= sx || dy >= sy || dz >= sz) return -1;
        return (dy * sz + dz) * sx + dx;
    }

    /** Set the blocks from cells in box order (the writer's side). */
    public void blocks(int[] cellsInBoxOrder) {
        if (cellsInBoxOrder.length != size()) throw new IllegalArgumentException("scenario: " + cellsInBoxOrder.length + " cells given, the box holds " + size());
        runs = rle(cellsInBoxOrder);
        cells = cellsInBoxOrder.clone();
    }

    /** The block state's text at x y z; {@link #NOT_LOADED} where the client had no chunk; null outside the box. */
    public String blockAt(int x, int y, int z) {
        int i = index(x, y, z);
        if (i < 0) return null;
        if (cells == null) cells = unrle(runs, size());
        int p = cells[i];
        if (p < 0 || p >= palette.size()) throw new IllegalStateException("scenario: palette index " + p + " at " + x + " " + y + " " + z + ", the palette holds " + palette.size());
        return palette.get(p);
    }

    /** The block's id at x y z without its state ("minecraft:oak_stairs"); as {@link #blockAt} otherwise. */
    public String blockIdAt(int x, int y, int z) {
        String s = blockAt(x, y, z);
        int b = s == null ? -1 : s.indexOf('[');
        return b < 0 ? s : s.substring(0, b);
    }

    /** How many cells hold each palette entry, most first (the summary line and the tests). */
    public Map<String, Integer> counts() {
        if (cells == null) cells = unrle(runs, size());
        int[] n = new int[palette.size()];
        for (int c : cells) n[c]++;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n.length; i++) order.add(i);
        order.sort((a, b) -> n[b] - n[a]);
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i : order) if (n[i] > 0) out.put(palette.get(i), n[i]);
        return out;
    }

    /** The bag by item id (slots summed). */
    public Map<String, Integer> bagCounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Slot s : bag) out.merge(s.item(), s.n(), Integer::sum);
        return out;
    }

    /** One line for the log and the command's answer. */
    public String summary() {
        int unknown = counts().getOrDefault(NOT_LOADED, 0);
        return String.format(java.util.Locale.ROOT, "feet %d %d %d in %s, a box of %d x %d x %d from %d %d %d (%d cells, %d block states, %d not loaded), %d bag slots, %d lines",
                feet[0], feet[1], feet[2], dim, sx, sy, sz, x0, y0, z0, size(), palette.size(), unknown, bag.size(), lines.size());
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public String toJson() { return GSON.toJson(this); }

    /** A scenario from its JSON; another version, or blocks that do not fill the box, is an error with its reason. */
    public static Scenario fromJson(String json) {
        Scenario s = GSON.fromJson(json, Scenario.class);
        if (s == null) throw new IllegalArgumentException("scenario: the file is empty");
        if (s.v != VERSION) throw new IllegalArgumentException("scenario: version " + s.v + ", this reader knows " + VERSION);
        if (s.palette == null || s.runs == null || s.feet == null || s.feet.length != 3) throw new IllegalArgumentException("scenario: no blocks or no feet in the file");
        if (s.bag == null) s.bag = new ArrayList<>();
        if (s.lines == null) s.lines = new ArrayList<>();
        s.cells = unrle(s.runs, s.size());
        for (int c : s.cells) if (c < 0 || c >= s.palette.size()) throw new IllegalArgumentException("scenario: a palette index " + c + " past the palette's " + s.palette.size());
        return s;
    }

    /** Written as gzip JSON; the folders are made. */
    public void write(Path file) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        try (var out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(toJson().getBytes(StandardCharsets.UTF_8));
        }
    }

    public static Scenario read(Path file) throws IOException {
        try (var in = new GZIPInputStream(Files.newInputStream(file))) {
            return fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
