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
 * Entropy Bot 2026-11-27: the fork's PricedPath route memory (D11 build 2: routeStore, fromRoute, savedWalk, routeList,
 * forget(index), the route lines), moved out of PricedPath; a stored route is rebuilt with our Path.fromCells.
 */

package io.github.mojolowjo.entropybot.nav.price;

import io.github.mojolowjo.entropybot.nav.price.PathBuild.Seen;
import io.github.mojolowjo.entropybot.nav.price.PathBuild.SegInfo;
import io.github.mojolowjo.entropybot.nav.search.Path;
import io.github.mojolowjo.entropybot.nav.search.goals.Goal;
import io.github.mojolowjo.entropybot.nav.search.goals.HasGoalPos;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The route memory: routes that ended in their goal, stored per world and dimension ({@link RouteStore}, entropy-routes.json
 * in {@link PriceWorld#routesDir}). An ask with no kept answer and no search running looks for a stored route to its goal;
 * a usable one becomes the goal's kept path. Every refusal is said once per route and reason (rule 7). Game thread. Pure
 * but for the block registry (JUnit: SavedRoutesTest). Loader notes: vanilla BuiltInRegistries.BLOCK for block ids.
 */
final class SavedRoutes {
    private final PriceWorld world;
    private final KeptAnswers kept;
    private final PriceConfig config;
    private RouteStore routes;
    private java.nio.file.Path routesDir;
    long asked, reused, changed, notLoaded, saved;
    /** Rule 7: the route lines for the mod's "probe reuse:" read (drainDrops), bounded like the memory's drop lines. */
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    /** Rule 7: one line per route and reason (route goal + saved tick + kind), not one per ask. */
    private final Set<String> said = new HashSet<>();

    SavedRoutes(PriceWorld world, KeptAnswers kept, PriceConfig config) {
        this.world = world;
        this.kept = kept;
        this.config = config;
    }

    private void line(String line) {
        world.debug("Entropy priced path: " + line);
        if (lines.size() >= 64) {                                              // structural, as the memory's drop lines
            lines.pollFirst();
        }
        lines.addLast(line);
    }

    /** The route lines since the last call (the fork's drainDrops adds them after the memory's). */
    List<String> drainLines() {
        List<String> out = new ArrayList<>(lines);
        lines.clear();
        return out;
    }

    /** Says a refusal once per route and reason. */
    private void refuse(RouteStore.Route r, String kind, String why) {
        if (said.size() > 4096) {
            said.clear();                                                       // structural bound; a reason may be said again after
        }
        if (said.add(r.goal() + "@" + r.tick() + "/" + kind)) {
            line("route not used " + r.line() + ": " + why);
        }
    }

    /** This world and dimension's store (read from its file when the folder changed); null: no folder. */
    RouteStore store() {
        java.nio.file.Path dir = world.routesDir();
        if (dir == null) {
            return null;
        }
        if (routes == null || !dir.equals(routesDir)) {
            routesDir = dir;
            java.nio.file.Path file = routesDir.resolve(RouteStore.FILE);
            try {
                routes = RouteStore.read(file);
                line("routes loaded: " + routes.size() + " from " + file);
            } catch (Exception e) {
                routes = new RouteStore();
                line("routes file " + file + " could not be read (" + e + "): starting empty, it is replaced at the next save");
            }
        }
        return routes;
    }

    static int bitsOf(Seen s) {
        return s == null ? -1 : s.bits();
    }

    static String blockOf(Seen s) {
        return s == null || s.block() == null ? null : String.valueOf(BuiltInRegistries.BLOCK.getKey(s.block()));
    }

    /** A stored read made a Seen again; an unknown block id is a null block (any compare with a block then differs). */
    static Seen seenOf(Integer bits, String id) {
        if (bits == null || bits < 0) {
            return Seen.NOT_LOADED;
        }
        ResourceLocation rl = id == null ? null : ResourceLocation.tryParse(id);
        Block b = rl == null ? null : BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
        return new Seen(bits, b);
    }

    /**
     * D11 build 2: an ask with no kept answer and no search running looks for a stored route to the goal. Its end must be in
     * the goal; the feet on it or within routeStoreReach of a cell; its watched cells re-read now: all hold = loaded as the
     * kept path (the memory answers EXACT / SUFFIX, or a re-join when the feet are off it); a changed one = loaded too (the
     * memory's re-join repair); one not loaded = not used (rule 15: not known; a normal search). The route's cells are
     * rebuilt as our planner's path; a move no longer possible = not used (a whole search). The price is the route's own cum
     * (rule 18).
     */
    void fromRoute(Goal goal, BetterBlockPos start, long tick) {
        RouteStore st = store();
        if (st == null || start == null) {
            return;
        }
        RouteStore.Route r = st.find(goal.toString());
        if (r == null) {
            return;
        }
        asked++;
        PathMemory.Cell end = r.cells().get(r.cells().size() - 1);
        if (!goal.isInGoal(new BlockPos(end.x(), end.y(), end.z()))) {
            refuse(r, "end", "its end " + end.text() + " is not in the goal");
            return;
        }
        PathMemory.Blocks<Seen> blocks = kept.blocksNow();
        PathMemory.Path<Seen> p = r.path(SavedRoutes::seenOf);
        RouteStore.Check c = RouteStore.check(p, PathBuild.cell(start), blocks, config.routeStoreReach());
        switch (c.use()) {
            case FAR:
                refuse(r, "far", c.why());
                return;
            case NOT_LOADED:
                notLoaded++;
                refuse(r, "unloaded", c.why() + " - not known, a normal search");
                return;
            default:
                break;
        }
        List<BetterBlockPos> cells = new ArrayList<>();
        for (PathMemory.Cell x : r.cells()) {
            cells.add(new BetterBlockPos(x.x(), x.y(), x.z()));
        }
        PriceWorld.Priced w = world.context();
        Path seg;
        try {
            seg = Path.fromCells(cells, r.cum(), goal, w.context());
        } catch (RuntimeException e) {
            changed++;
            refuse(r, "rebuild", "its moves could not be rebuilt (" + e + ") - a whole search");
            return;
        }
        if (seg.positions().size() != cells.size()) {
            changed++;
            refuse(r, "move", "a move from " + seg.getDest() + " is no longer possible here - a whole search");
            return;
        }
        if (c.use() == RouteStore.Use.CHANGED) {
            changed++;
            refuse(r, "changed", c.why() + " - loaded for the re-join repair");
        } else {
            reused++;
        }
        int n = cells.size();
        int[] segOf = new int[n], posOf = new int[n];
        for (int i = 0; i < n; i++) {
            posOf[i] = i;
        }
        SegInfo info = new SegInfo(List.of(seg), segOf, posOf, null, false);
        kept.stampBuilds++;
        kept.memory.keep(goal, IPathCost.Answer.complete(p.total()).withPath(PriceQueue.positions(p.cells())), p, r.cells().get(0),
                world.world(), world.stampNow(), tick, info);
        world.debug("Entropy priced path: stored route " + r.line() + " loaded as the kept path (" + c.use() + ", feet at its cell " + c.index() + ")");
    }

    /**
     * D11 build 2: a priced walk to {@code g} ended (the walk driver says so; the fork watched CustomGoalProcess): when the
     * feet stand in the goal and the goal has a kept path, it is saved as a route. Returns true when saved.
     */
    boolean walkEnded(Goal g, long tick) {
        if (g == null) {
            return false;                                                       // review of 2026-11-27: no goal, nothing walked to
        }
        BetterBlockPos feet = world.start();
        PathMemory.Path<Seen> p = kept.memory.path(g);
        if (feet == null || world.world() == null || !g.isInGoal(feet) || p == null) {
            return false;                                                       // not in the goal, or no kept path walked: nothing to save
        }
        if (!(g instanceof HasGoalPos rp)) {
            line("route not saved to " + g + ": the goal has no cell");
            return false;
        }
        RouteStore st = store();
        if (st == null) {
            return false;
        }
        RouteStore.Route r = RouteStore.of(g.toString(), PathBuild.cell(rp.getGoalPos()), p, kept::read,
                SavedRoutes::bitsOf, SavedRoutes::blockOf, tick, System.currentTimeMillis());
        st.put(r, config.routeStoreMax(), config.routeStorePerGoalCell());
        saved++;
        write(st, "saved " + r.line());
        return true;
    }

    private void write(RouteStore st, String what) {
        java.nio.file.Path file = routesDir.resolve(RouteStore.FILE);
        try {
            st.write(file);
            world.debug("Entropy priced path: route " + what + " (" + st.size() + " in " + file + ")");
        } catch (Exception e) {
            line("routes file " + file + " could not be written (" + e + "): kept in memory only");
        }
    }

    List<String> list() {
        RouteStore st = store();
        if (st == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (RouteStore.Route r : st.list()) {
            out.add(r.line());
        }
        return out;
    }

    boolean forget(int index) {
        RouteStore st = store();
        if (st == null || !st.forget(index)) {
            return false;
        }
        write(st, "forgotten (index " + index + ")");
        return true;
    }

    long[] counts() {
        return new long[]{asked, reused, changed, notLoaded, saved};
    }
}
