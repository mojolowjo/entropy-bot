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
 * Entropy Bot 2026-11-27: the fork's RouteStoreTest (fork-only class, from the fork's source at dd8235b7)
 */

package io.github.mojolowjo.entropybot.nav.price;

import io.github.mojolowjo.entropybot.nav.price.IPricedPath;
import io.github.mojolowjo.entropybot.nav.price.PathMemory;
import io.github.mojolowjo.entropybot.nav.price.PathMemory.Cell;
import io.github.mojolowjo.entropybot.nav.price.PathMemory.Decision;
import io.github.mojolowjo.entropybot.nav.price.PathMemory.Kind;
import io.github.mojolowjo.entropybot.nav.price.PathMemory.Stamp;
import io.github.mojolowjo.entropybot.nav.price.PathMemory.Watch;
import io.github.mojolowjo.entropybot.nav.price.RouteStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Entropy D11 build 2 (route memory): a route saved from PathMemoryTest's hand path (x 0..4 at y 64, costs 10, 20, 5, 15),
 * through the JSON file and back, checked against the world now and kept in a fresh memory (a restart): its cells hold =
 * EXACT / SUFFIX with no search; a changed watched cell = the re-join repair; an unloaded one = not known; the newest-N cap
 * and the per-goal-cell replace. Cells read as words ("unloaded" = not loaded), stored as bits 0 / -1 and the word as the
 * block id. No game.
 */
public class RouteStoreTest {

    private static final String GOAL = "G";
    private static final Object WORLD = new Object();
    private static final IPricedPath.Limits LIM = new IPricedPath.Limits(6000, 1200, 16, 4);
    private static final Stamp STAMP = PathMemoryTest.stamp(Map.of(), Map.of(), Map.of());

    private final Map<Cell, String> world = new HashMap<>();

    private static Cell c(int x) {
        return new Cell(x, 64, 0);
    }

    private String at(Cell c) {
        return world.getOrDefault(c, c.y() < 64 ? "stone" : "air");
    }

    private PathMemory.Blocks<String> blocks() {
        return new PathMemory.Blocks<>() {
            @Override public String read(Cell c) { return at(c); }
            @Override public boolean same(String was, String now, boolean breakPlanned) { return was.equals(now); }
            @Override public boolean notLoaded(String s) { return "unloaded".equals(s); }
            @Override public boolean broken(String s) { return "air".equals(s); }
            @Override public boolean placed(String s) { return !"air".equals(s) && !"unloaded".equals(s); }
        };
    }

    private PathMemory.Path<String> hand() {
        List<Cell> cells = new ArrayList<>();
        List<Set<Cell>> valid = new ArrayList<>();
        Map<Cell, Watch<String>> watch = new HashMap<>();
        for (int x = 0; x <= 4; x++) {
            cells.add(c(x));
        }
        for (int i = 0; i < 4; i++) {
            valid.add(Set.of(c(i), c(i + 1)));
            for (Cell base : List.of(c(i), c(i + 1))) {
                for (int dy = -1; dy <= 1; dy++) {
                    Cell w = base.up(dy);
                    Watch<String> old = watch.get(w);
                    watch.put(w, new Watch<>(i, old != null ? old.was() : at(w), false, false));
                }
            }
        }
        return new PathMemory.Path<>(cells, new double[]{0, 10, 30, 35, 50}, valid, watch);
    }

    private RouteStore.Route route(String goal, Cell goalCell, long tick) {
        return RouteStore.of(goal, goalCell, hand(), this::at, s -> "unloaded".equals(s) ? -1 : 0, s -> s, tick, 1000 + tick);
    }

    private static String seen(Integer bits, String id) {
        return bits < 0 ? "unloaded" : id;
    }

    /** The stored route through the file text, as a fresh game reads it. */
    private PathMemory.Path<String> reloaded() {
        RouteStore s = new RouteStore();
        s.put(route(GOAL, c(4), 7), 64, 1);
        RouteStore back = RouteStore.fromJson(s.toJson());
        return back.find(GOAL).path(RouteStoreTest::seen);
    }

    private Decision<Object> keepAndAsk(PathMemory.Path<String> p, Cell feet) {
        PathMemory<String, Object, String> m = new PathMemory<>();
        m.keep(GOAL, io.github.mojolowjo.entropybot.nav.price.IPathCost.Answer.complete(p.total()), p, c(0), WORLD, STAMP, 100, null);
        return m.ask(GOAL, new PathMemory.Now<>(feet, feet.equals(c(4)), WORLD, () -> STAMP, 101, this::blocks), LIM);
    }

    @Test
    public void storedRouteHoldsExactWithNoSearch() {
        PathMemory.Path<String> p = reloaded();
        RouteStore.Check ch = RouteStore.check(p, c(0), blocks(), 8);
        assertEquals(RouteStore.Use.HOLDS, ch.use());
        Decision<Object> d = keepAndAsk(p, c(0));
        assertEquals(Kind.EXACT, d.kind());
        assertEquals(50, d.answer().ticks, 1e-9);   // the route's own cum, never a rank
    }

    @Test
    public void storedRouteHoldsSuffixFromAMiddleCell() {
        PathMemory.Path<String> p = reloaded();
        RouteStore.Check ch = RouteStore.check(p, c(2), blocks(), 8);
        assertEquals(RouteStore.Use.HOLDS, ch.use());
        assertEquals(2, ch.index());
        Decision<Object> d = keepAndAsk(p, c(2));
        assertEquals(Kind.SUFFIX, d.kind());
        assertEquals(20, d.answer().ticks, 1e-9);
    }

    @Test
    public void changedWatchedCellGoesToTheRejoin() {
        PathMemory.Path<String> p = reloaded();
        world.put(c(2).up(-1), "glass");           // the floor under cell 2 changed since the save
        RouteStore.Check ch = RouteStore.check(p, c(0), blocks(), 8);
        assertEquals(RouteStore.Use.CHANGED, ch.use());
        assertTrue(ch.why().contains("stone -> glass"), ch.why());
        Decision<Object> d = keepAndAsk(p, c(0));
        assertEquals(Kind.REJOIN, d.kind());
        assertEquals(3, d.index());                 // re-joined after the last move that watches it
    }

    @Test
    public void unloadedCellIsNotKnown() {
        PathMemory.Path<String> p = reloaded();
        world.put(c(3), "unloaded");
        RouteStore.Check ch = RouteStore.check(p, c(0), blocks(), 8);
        assertEquals(RouteStore.Use.NOT_LOADED, ch.use());
        assertTrue(ch.why().contains("not loaded"), ch.why());
    }

    @Test
    public void feetFarAwayAreNotOffered() {
        PathMemory.Path<String> p = reloaded();
        assertEquals(RouteStore.Use.FAR, RouteStore.check(p, new Cell(40, 64, 0), blocks(), 8).use());
        RouteStore.Check near = RouteStore.check(p, new Cell(1, 64, 2), blocks(), 8);
        assertEquals(RouteStore.Use.HOLDS, near.use());     // within reach: loaded, the memory re-joins
        assertEquals(Kind.REJOIN, keepAndAsk(p, new Cell(1, 64, 2)).kind());
    }

    @Test
    public void newestCapAndOnePerGoalCell() {
        RouteStore s = new RouteStore();
        for (int i = 0; i < 70; i++) {
            s.put(route("goal " + i, new Cell(i, 64, 9), i), 64, 1);
        }
        assertEquals(64, s.size());
        assertNull(s.find("goal 5"));
        assertNotNull(s.find("goal 6"));
        s.put(route("other goal at 69", new Cell(69, 64, 9), 80), 64, 1);   // same goal cell: replaces
        assertNull(s.find("goal 69"));
        assertEquals(64, s.size());
        s.put(route("goal 68", new Cell(68, 64, 9), 90), 64, 1);            // same goal again: walks carried
        assertEquals(2, s.find("goal 68").walks());
        assertTrue(s.forget(0));
        assertEquals(63, s.size());
    }

    @Test
    public void jsonRoundTrip() throws Exception {
        world.put(c(1).up(-1), "unloaded");
        RouteStore s = new RouteStore();
        s.put(route(GOAL, c(4), 7), 64, 1);
        java.nio.file.Path dir = Files.createTempDirectory("routes");
        java.nio.file.Path file = dir.resolve(RouteStore.FILE);
        s.write(file);
        RouteStore back = RouteStore.read(file);
        RouteStore.Route a = s.find(GOAL), b = back.find(GOAL);
        assertEquals(a.goal(), b.goal());
        assertEquals(a.goalCell(), b.goalCell());
        assertEquals(a.cells(), b.cells());
        assertArrayEquals(a.cum(), b.cum(), 0);
        assertEquals(a.valid(), b.valid());
        assertEquals(a.watch(), b.watch());
        assertEquals(a.tick(), b.tick());
        assertEquals(a.savedAt(), b.savedAt());
        assertEquals(a.walks(), b.walks());
        assertEquals(0, RouteStore.read(dir.resolve("missing.json")).size());
        try {
            RouteStore.fromJson("{\"version\":99,\"routes\":[]}");
            fail("another version must be said, not read");
        } catch (IllegalArgumentException expected) {
        }
    }
}
