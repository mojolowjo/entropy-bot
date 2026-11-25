package io.github.mojolowjo.entropybot.sim;

import io.github.mojolowjo.entropybot.aa.brain.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test tooling (docs repo to-do/test-tooling-plan.md; the owner 2026-11-21: "about 100 worlds"): the owner's line played
 * by the simulator at the spawn of 100 real worlds - cubes of 65 blocks dumped by a cloud tester with {@code aa scenario}
 * (seeds w001..w100, src/test/resources/scenarios/worlds, its index.md beside them). The real brain, planner, step runner
 * and Baritone's search; the fake hands. A cube has an edge: a plan that leaves it ends "not simulated", which is said,
 * not a failure of the bot. The table is the output (what the bot decides at 100 different spawns, in seconds), read for
 * decisions that look wrong. Merge review 2026-11-23: one outcome is held when the games are played - the count of games
 * that end with the whole line in the chest may not fall under {@link #WHOLE_FLOOR}. The games stay out of the plain
 * build (six minutes; SIM_WORLDS=1 plays them): a build that did not play them proves nothing about them.
 */
class SimWorldsTest {
    static final int LIMIT_S = 1500;       // sim: a game's seconds allowed (the whole lines end under 450 s)
    /**
     * The fewest of the 100 games that must end with the whole line in the chest: the last three runs had 67 (docs repo
     * to-do/test-tooling-plan.md, 2026-11-22 and 23; the others leave the cube or name a wood the test data lacks), less
     * a margin of 5 for a game that tips over the cube's edge. Raised by hand when the count rises.
     */
    static final int WHOLE_FLOOR = 62;

    @AfterEach
    void clear() {
        TestData.clear();
        io.github.mojolowjo.entropybot.aa.brain.Tuning.reset();
    }

    /** The log the cube holds most of (its full id), null: none in sight. */
    static String wood(SimGame g) {
        Map<String, Integer> n = new TreeMap<>();
        for (SimWorld.Group gr : g.world.groups(g.feet, 40, id -> id.endsWith("_log")))
            for (int[] c : gr.cells()) n.merge(g.world.at(c[0], c[1], c[2]).replaceAll("\\[.*", ""), 1, Integer::sum);
        String best = null;
        for (var e : n.entrySet()) if (e.getKey().endsWith("_log") && !e.getKey().contains("stripped") && (best == null || e.getValue() > n.get(best))) best = e.getKey();
        return best;
    }

    @Test
    void theOwnersLine_atTheSpawnOf100Worlds() throws Exception {
        // six minutes of games: run on purpose (SIM_WORLDS=1 in the environment), not in every build
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("SIM_WORLDS") != null, "set SIM_WORLDS=1 to play the 100 worlds");
        var dirUrl = SimWorldsTest.class.getResource("/scenarios/worlds");
        assertNotNull(dirUrl, "the world files are in src/test/resources/scenarios/worlds");
        List<Path> files;
        try (var st = Files.list(Path.of(dirUrl.toURI()))) {
            files = st.filter(p -> p.getFileName().toString().matches("w\\d{3}\\.json\\.gz")).sorted().toList();
        }
        assertTrue(files.size() >= 100, "100 world files: " + files.size());
        Map<String, Integer> ends = new TreeMap<>();
        StringBuilder table = new StringBuilder();
        int whole = 0, threw = 0, drops = 0, tooLarge = 0, mostWays = 0;
        String mostAt = "-";
        StringBuilder dropLines = new StringBuilder();
        long t0 = System.currentTimeMillis();
        for (Path f : files) {
            clear();
            String name = f.getFileName().toString().substring(0, 4);
            String end, wood = null, dep = "-";
            double secs = 0;
            Map<String, Integer> chest = Map.of();
            List<String> planned = List.of();
            io.github.mojolowjo.entropybot.aa.NeedChain.mostWays(true);      // job-plan-budget: this game's largest resolve
            try {
                SimGame g = SimGame.of(Scenario.read(f));
                planned = g.planner.drops;
                wood = wood(g);
                // the main chest two blocks from the feet, on the feet's level (the testers' staging, shortened)
                int[] c = {g.feet[0] + 2, g.feet[1], g.feet[2]};
                g.world.set(c[0], c[1], c[2], "minecraft:chest");
                g.mainAt = c[0] + " " + c[1] + " " + c[2];
                g.sight = 32;
                String log = wood == null ? "oak_log" : wood.replace("minecraft:", "");
                g.order("cobblestone", 32, "b1", true);
                g.order(log, 16, "b1", true);
                g.order("torch", 4, "b1", true);
                end = g.play(LIMIT_S);
                secs = g.seconds();
                chest = g.chest;
                dep = g.depositColumn();            // deposit-goal stage 3: the way the deposit took, its runs (re-plans)
                if (g.timelineText().contains(io.github.mojolowjo.entropybot.aa.NeedChain.TOO_LARGE)) tooLarge++;   // job-plan-budget: the bound never met here
                if (chest.getOrDefault("minecraft:cobblestone", 0) == 32 && chest.getOrDefault("minecraft:torch", 0) == 4 && chest.getOrDefault("minecraft:" + log, 0) == 16) whole++;
            } catch (IllegalArgumentException e) {
                // the tests' item data does not know this wood (mangrove): the simulator's gap, said, not a fault
                end = "not simulated: " + e.getMessage();
            } catch (RuntimeException e) {
                threw++;
                end = "THREW " + e + " at " + io.github.mojolowjo.entropybot.Quiet.where(e);
            }
            int mw = io.github.mojolowjo.entropybot.aa.NeedChain.mostWays(false);
            if (mw > mostWays) { mostWays = mw; mostAt = name; }
            String kind = end.replaceAll("[-\\d]+", "N");
            if (kind.length() > 90) kind = kind.substring(0, 90);
            ends.merge(kind, 1, Integer::sum);
            // job-own-aside-worker review: the planner's drops (SyncPlanner takes an answer on its first drop, where the game
            // waits planner.dropsBeforeTake worker rounds) are a planner fault in a world that stands still while it thinks -
            // a column on the game's line (only when there are any, so the earlier tables compare) and a count held below
            if (!planned.isEmpty()) {
                drops += planned.size();
                dropLines.append(name).append(": ").append(planned.get(0)).append('\n');
            }
            table.append(String.format(java.util.Locale.ROOT, "%s wood %-12s %6.0f s  %s | chest %s | deposit %s%s%n", name, wood == null ? "none seen" : wood.replace("minecraft:", ""), secs,
                    end.length() > 130 ? end.substring(0, 130) : end, chest, dep, planned.isEmpty() ? "" : " | planner drops " + planned.size()));
        }
        StringBuilder sum = new StringBuilder();
        ends.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).forEach(e -> sum.append(String.format("%3d  %s%n", e.getValue(), e.getKey())));
        System.out.println("WORLDS\n" + table + "ENDS\n" + sum + "whole line in the chest: " + whole + " of " + files.size() + "; threw " + threw
                + "; planner drops " + drops + "; computed in " + (System.currentTimeMillis() - t0) / 1000 + " s" + (drops == 0 ? "" : "\nDROPS\n" + dropLines)
                + "\nPLANNER most ways a resolve " + mostWays + " (" + mostAt + "), planner.maxWays " + io.github.mojolowjo.entropybot.aa.NeedChain.maxWays() + "; plans too large " + tooLarge);
        assertEquals(0, threw, "a game threw (a fault in the simulator or the mod, never an end):\n" + table);
        assertEquals(0, tooLarge, "job-plan-budget: a resolve met planner.maxWays in the 100 worlds (the bound is the last line of defence, never a plan's end here)");
        assertEquals(0, drops, "the planner dropped answers in worlds that stood still while it thought (the game waits planner.dropsBeforeTake worker rounds for each):\n" + dropLines);
        assertTrue(whole >= WHOLE_FLOOR, "the whole line reached the chest in " + whole + " of " + files.size() + " games, under the floor of " + WHOLE_FLOOR
                + " (the last runs: 67):\n" + table);
    }

    /** One world's game with its timeline printed (a short deposit looked at): the names come from -Dsim.world or the three first seen short. */
    @Test
    void oneWorldsTimeline() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("SIM_WORLDS") != null, "set SIM_WORLDS=1 (and -Dsim.world=w014,...) to print a world's timeline");
        String names = System.getProperty("sim.world", "w014,w059,w042");
        for (String name : names.split(",")) {
            clear();
            SimGame g = SimGame.of(Scenario.read(Path.of(SimWorldsTest.class.getResource("/scenarios/worlds/" + name.trim() + ".json.gz").toURI())));
            String wood = wood(g);
            int[] c = {g.feet[0] + 2, g.feet[1], g.feet[2]};
            g.world.set(c[0], c[1], c[2], "minecraft:chest");
            g.mainAt = c[0] + " " + c[1] + " " + c[2];
            g.sight = 32;
            String log = wood == null ? "oak_log" : wood.replace("minecraft:", "");
            g.order("cobblestone", 32, "b1", true);
            g.order(log, 16, "b1", true);
            g.order("torch", 4, "b1", true);
            String end = g.play(LIMIT_S);
            System.out.println("TIMELINE " + name + " (" + end + ") chest " + g.chest + " bag " + g.bag + "\n" + g.timelineText());
        }
    }
}