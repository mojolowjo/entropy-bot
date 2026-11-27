/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Entropy Bot 2026-11-27: the fork's RouteStore (fork-only class, from the fork's source at dd8235b7)
 */

package io.github.mojolowjo.entropybot.nav.price;

import io.github.mojolowjo.entropybot.nav.price.PathMemory.Cell;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Entropy D11 build 2 (route memory; owner 2026-10-27: "save some of this information in a file, so we don't recalculate
 * movement paths every time"): the routes the bot WALKED to their goal, per world and dimension (one store per
 * {@code WorldData.directory}, the file {@link #FILE} beside the waypoints). A route is a kept path as the memory held it -
 * its cells, the search's cost to each ({@code cum}), each move's valid positions, the watched cells with the classification
 * bits and block id read when saved - plus the tick saved, the wall-clock time saved and the walks it served. The newest
 * {@code max} routes are kept, at most {@code perGoalCell} per goal cell (the newest replaces). The one store for routes
 * (rule 23): the mod keeps no copy. Pure but for Gson and the file (JUnit: RouteStoreTest). Not synchronized: game thread.
 */
public final class RouteStore {

    public static final String FILE = "entropy-routes.json";
    static final int VERSION = 1; // structural: the file format

    /** One watched cell as stored: its last move, the classification bits (-1 = not loaded) and block id when saved. */
    public record WatchCell(Cell cell, int lastMove, int bits, String block, boolean breaks, boolean places) {}

    /** One stored route. {@code goal}: the goal's text (its match key); {@code goalCell}: the goal's cell (its replace key). */
    public record Route(String goal, Cell goalCell, List<Cell> cells, double[] cum, List<Set<Cell>> valid, List<WatchCell> watch,
                        long tick, long savedAt, int walks) {

        /** The route as the memory's kept path, each watched cell's saved read made an S by {@code seen(bits, block)}. */
        public <S> PathMemory.Path<S> path(BiFunction<Integer, String, S> seen) {
            Map<Cell, PathMemory.Watch<S>> w = new LinkedHashMap<>();
            for (WatchCell c : watch) {
                w.put(c.cell(), new PathMemory.Watch<>(c.lastMove(), seen.apply(c.bits(), c.block()), c.breaks(), c.places()));
            }
            return new PathMemory.Path<>(cells, cum, valid, w);
        }

        public double total() {
            return cum[cum.length - 1];
        }

        public String line() {
            Cell s = cells.get(0);
            return "to " + goal + " from " + s.text() + ", " + cells.size() + " cells, " + Math.round(total()) + " ticks, walks " + walks
                    + ", saved at tick " + tick;
        }
    }

    /**
     * A route from a kept path: each watched cell's read is {@code now} (re-read at save) where loaded, else the path's
     * own read; {@code bits} / {@code block} turn a read into what is stored.
     */
    public static <S> Route of(String goal, Cell goalCell, PathMemory.Path<S> p, Function<Cell, S> now, Function<S, Integer> bits,
                               Function<S, String> block, long tick, long savedAt) {
        List<WatchCell> w = new ArrayList<>();
        for (Map.Entry<Cell, PathMemory.Watch<S>> e : p.watched().entrySet()) {
            S read = now == null ? null : now.apply(e.getKey());
            Integer b = read == null ? null : bits.apply(read);
            if (b == null || b < 0) {
                read = e.getValue().was();
                b = read == null ? -1 : bits.apply(read);
            }
            PathMemory.Watch<S> x = e.getValue();
            w.add(new WatchCell(e.getKey(), x.lastMove(), b == null ? -1 : b, read == null ? null : block.apply(read), x.breaks(), x.places()));
        }
        double[] cum = new double[p.size()];
        for (int i = 0; i < cum.length; i++) {
            cum[i] = p.cum(i);
        }
        return new Route(goal, goalCell, p.cells(), cum, p.valid, w, tick, savedAt, 1);
    }

    /** Whether a stored route may answer now: the check of {@link #check}. */
    public enum Use { HOLDS, CHANGED, NOT_LOADED, FAR }

    /** {@code index}: the feet's cell index on the route, or its nearest cell when off it (within reach). */
    public record Check(Use use, int index, String why) {}

    /**
     * Pure: may the route {@code p} answer for feet {@code feet}? The feet on a cell (or a move's valid position) of it, or
     * within {@code reach} blocks of one of its cells (a re-join then); every watched cell of the moves from there re-read:
     * one not loaded = NOT_LOADED (rule 15: not known), one changed = CHANGED (the re-join repair), all hold = HOLDS.
     */
    public static <S> Check check(PathMemory.Path<S> p, Cell feet, PathMemory.Blocks<S> b, double reach) {
        int i = p.indexOf(feet, 0);
        if (i < 0) {
            double best = Double.POSITIVE_INFINITY;
            for (int k = 0; k < p.size() - 1; k++) {
                double d = p.cells.get(k).dist(feet);
                if (d < best) {
                    best = d;
                    i = k;
                }
            }
            if (i < 0 || best > reach) {
                return new Check(Use.FAR, -1, "the feet " + feet.text() + " are " + (Double.isInfinite(best) ? "not near" : Math.round(best) + " blocks from")
                        + " its nearest cell (reach " + reach + ")");
            }
        }
        String why = p.changed(i, b);
        if (why == null) {
            return new Check(Use.HOLDS, i, null);
        }
        if (why.startsWith(PathMemory.NOT_LOADED_MARK)) {
            return new Check(Use.NOT_LOADED, i, "a watched cell is not loaded: " + why.substring(PathMemory.NOT_LOADED_MARK.length()));
        }
        return new Check(Use.CHANGED, i, "a watched cell changed: " + why);
    }

    /** Oldest first. */
    private final List<Route> routes = new ArrayList<>();

    /**
     * Keeps {@code r} as the newest route: a route with the same goal text is replaced (its walks carried + 1), routes of the
     * same goal cell past {@code perGoalCell} go (the oldest first), and the oldest routes past {@code max}.
     */
    public void put(Route r, int max, int perGoalCell) {
        int walks = r.walks();
        for (int i = routes.size() - 1; i >= 0; i--) {
            if (routes.get(i).goal().equals(r.goal())) {
                walks = Math.max(walks, routes.get(i).walks() + 1);
                routes.remove(i);
            }
        }
        routes.add(new Route(r.goal(), r.goalCell(), r.cells(), r.cum(), r.valid(), r.watch(), r.tick(), r.savedAt(), walks));
        int same = 0;
        for (int i = routes.size() - 1; i >= 0; i--) {
            if (routes.get(i).goalCell().equals(r.goalCell()) && ++same > Math.max(1, perGoalCell)) {
                routes.remove(i);
            }
        }
        while (routes.size() > Math.max(1, max)) {
            routes.remove(0);
        }
    }

    /** The newest route for the goal text, or null. */
    public Route find(String goal) {
        for (int i = routes.size() - 1; i >= 0; i--) {
            if (routes.get(i).goal().equals(goal)) {
                return routes.get(i);
            }
        }
        return null;
    }

    /** The routes, oldest first (read-only). */
    public List<Route> list() {
        return Collections.unmodifiableList(routes);
    }

    /** Removes route {@code i} of {@link #list}; false when there is none. */
    public boolean forget(int i) {
        if (i < 0 || i >= routes.size()) {
            return false;
        }
        routes.remove(i);
        return true;
    }

    public int size() {
        return routes.size();
    }

    // ---- JSON: compact flat arrays, one block-id palette per route ----

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonArray rs = new JsonArray();
        for (Route r : routes) {
            JsonObject o = new JsonObject();
            o.addProperty("goal", r.goal());
            o.add("goalCell", cells(List.of(r.goalCell())));
            o.add("cells", cells(r.cells()));
            JsonArray cum = new JsonArray();
            for (double d : r.cum()) {
                cum.add(d);
            }
            o.add("cum", cum);
            JsonArray valid = new JsonArray();
            for (Set<Cell> s : r.valid()) {
                valid.add(cells(new ArrayList<>(s)));
            }
            o.add("valid", valid);
            List<String> palette = new ArrayList<>();
            Map<String, Integer> idx = new HashMap<>();
            JsonArray w = new JsonArray();
            for (WatchCell c : r.watch()) {
                int bi = -1;
                if (c.block() != null) {
                    bi = idx.computeIfAbsent(c.block(), k -> {
                        palette.add(k);
                        return palette.size() - 1;
                    });
                }
                w.add(c.cell().x());
                w.add(c.cell().y());
                w.add(c.cell().z());
                w.add(c.lastMove());
                w.add(c.bits());
                w.add((c.breaks() ? 1 : 0) | (c.places() ? 2 : 0));
                w.add(bi);
            }
            JsonArray pal = new JsonArray();
            palette.forEach(pal::add);
            o.add("blocks", pal);
            o.add("watch", w);
            o.addProperty("tick", r.tick());
            o.addProperty("savedAt", r.savedAt());
            o.addProperty("walks", r.walks());
            rs.add(o);
        }
        root.add("routes", rs);
        return root.toString();
    }

    /** Parses {@link #toJson}'s text; a malformed file throws (the caller says so, rule 7). */
    public static RouteStore fromJson(String text) {
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        int v = root.get("version").getAsInt();
        if (v != VERSION) {
            throw new IllegalArgumentException("route file version " + v + ", this fork reads " + VERSION);
        }
        RouteStore s = new RouteStore();
        for (JsonElement e : root.getAsJsonArray("routes")) {
            JsonObject o = e.getAsJsonObject();
            List<Cell> cells = cells(o.getAsJsonArray("cells"));
            JsonArray ca = o.getAsJsonArray("cum");
            double[] cum = new double[ca.size()];
            for (int i = 0; i < cum.length; i++) {
                cum[i] = ca.get(i).getAsDouble();
            }
            List<Set<Cell>> valid = new ArrayList<>();
            for (JsonElement ve : o.getAsJsonArray("valid")) {
                valid.add(new LinkedHashSet<>(cells(ve.getAsJsonArray())));
            }
            List<String> palette = new ArrayList<>();
            for (JsonElement pe : o.getAsJsonArray("blocks")) {
                palette.add(pe.getAsString());
            }
            JsonArray wa = o.getAsJsonArray("watch");
            List<WatchCell> watch = new ArrayList<>();
            for (int i = 0; i + 6 < wa.size(); i += 7) {
                int flags = wa.get(i + 5).getAsInt(), bi = wa.get(i + 6).getAsInt();
                watch.add(new WatchCell(new Cell(wa.get(i).getAsInt(), wa.get(i + 1).getAsInt(), wa.get(i + 2).getAsInt()), wa.get(i + 3).getAsInt(),
                        wa.get(i + 4).getAsInt(), bi < 0 ? null : palette.get(bi), (flags & 1) != 0, (flags & 2) != 0));
            }
            Route r = new Route(o.get("goal").getAsString(), cells(o.getAsJsonArray("goalCell")).get(0), List.copyOf(cells), cum,
                    List.copyOf(valid), List.copyOf(watch), o.get("tick").getAsLong(), o.get("savedAt").getAsLong(), o.get("walks").getAsInt());
            r.path((b, id) -> b);   // checks the shapes (one cost per cell, one move per step): a bad record throws here
            s.routes.add(r);
        }
        return s;
    }

    /** Reads the file; a missing file is an empty store. A malformed one throws (the caller says so). */
    public static RouteStore read(java.nio.file.Path file) throws IOException {
        if (!Files.exists(file)) {
            return new RouteStore();
        }
        return fromJson(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Writes the file (a temp file moved over it, so a crash leaves the old one whole). */
    public void write(java.nio.file.Path file) throws IOException {
        Files.createDirectories(file.getParent());
        java.nio.file.Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, toJson(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static JsonArray cells(List<Cell> cs) {
        JsonArray a = new JsonArray();
        for (Cell c : cs) {
            a.add(c.x());
            a.add(c.y());
            a.add(c.z());
        }
        return a;
    }

    private static List<Cell> cells(JsonArray a) {
        List<Cell> out = new ArrayList<>();
        for (int i = 0; i + 2 < a.size(); i += 3) {
            out.add(new Cell(a.get(i).getAsInt(), a.get(i + 1).getAsInt(), a.get(i + 2).getAsInt()));
        }
        return out;
    }
}
