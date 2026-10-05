package io.github.mojolowjo.entropybot.routewalk;

import io.github.mojolowjo.entropybot.route.Cell;
import io.github.mojolowjo.entropybot.route.RoutePlan;
import io.github.mojolowjo.entropybot.route.RoutePlanner;
import io.github.mojolowjo.entropybot.route.RoutePlannerHolder;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutePlannerHolderTest {
    @Test
    void defaultsDoNothing() throws Exception {
        RoutePlanner p = RoutePlannerHolder.get();
        assertSame(RoutePlannerHolder.NONE, p);
        assertFalse(p.available());
        RoutePlan plan = p.plan(0, new Cell(0, 64, 0), new Cell(100, 64, 0), 0).get();
        assertEquals(RoutePlan.Status.BAD_REQUEST, plan.status());
        assertEquals(0, plan.table().size());
        p.requestBuildAlong(0, new Cell(0, 0, 0), new Cell(1, 1, 1));
        assertEquals("", p.stats().lastError());
        assertFalse(RoutePlannerHolder.dumper().dump(0, 1, 2, 3, Path.of(".")));
        // a walk with the no-op planner: plain at once
        RouteWalk w = new RouteWalk(RouteWalk.Mode.GOAL, 0, new Cell(0, 64, 0), new Cell(100, 64, 0),
                p.plan(0, new Cell(0, 64, 0), new Cell(100, 64, 0), 0), 0);
        assertEquals(RouteWalk.Phase.PLAIN, w.poll(1, null, null));
    }

    @Test
    void setNullPutsTheNoOpBack() {
        RoutePlannerHolder.set(null);
        assertSame(RoutePlannerHolder.NONE, RoutePlannerHolder.get());
        RoutePlannerHolder.setDumper(null);
        assertSame(RoutePlannerHolder.NO_DUMPER, RoutePlannerHolder.dumper());
    }

    @Test
    void dimIds() {
        assertEquals(0, RoutePlannerHolder.dimId("minecraft:overworld"));
        assertEquals(-1, RoutePlannerHolder.dimId("minecraft:the_nether"));
        assertEquals(-1, RoutePlannerHolder.dimId(null));
    }

    /** The walk logic stays loader-neutral: no Minecraft, NeoForge, Fabric or Baritone imports in routewalk. */
    @Test
    void routewalkIsLoaderNeutral() throws Exception {
        Path dir = Path.of("src/main/java/io/github/mojolowjo/entropybot/routewalk");
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                for (String line : Files.readAllLines(f)) {
                    String t = line.trim();
                    if (!t.startsWith("import ")) continue;
                    for (String bad : List.of("net.minecraft", "net.neoforged", "net.fabricmc", "baritone", "com.mojang")) {
                        assertFalse(t.contains(bad), f.getFileName() + ": " + t);
                    }
                }
            }
        }
        assertTrue(true);
    }
}
