package io.github.mojolowjo.entropybot.sim;

import io.github.mojolowjo.entropybot.aa.brain.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * sim step 2, slice 1 (to-do/simulator-plan.md): an order line played on a hand-built flat map with no game - the real
 * brain plans, each step is applied to the blocks and the bag, a timeline comes out with its end.
 */
class SimGameTest {
    @AfterEach
    void clear() {
        // the game's statics SimGame sets (was left on: the tests after it read "orders only")
        io.github.mojolowjo.entropybot.aa.brain.Only.set("off");
        io.github.mojolowjo.entropybot.aa.brain.BatchWay.clear();
        io.github.mojolowjo.entropybot.aa.moves.FactChange.clear();
        TestData.clear();
        io.github.mojolowjo.entropybot.aa.brain.Tuning.reset();
    }

    /** Grass at y 63; the bot at 0 64 0; a spruce tree (a trunk of {@code logs}) at 10 64 0. */
    static SimGame treeMap(int logs) {
        SimWorld w = SimWorld.flat(63, "minecraft:grass_block", 40);
        w.column(10, 64, 0, logs, "minecraft:spruce_log");
        return new SimGame(w, new int[]{0, 64, 0});
    }

    @Test
    void logsAndATable_runToDone_onAFlatMap() {
        SimGame g = treeMap(6);
        g.order("spruce_log", 4, "b1", false);
        g.order("crafting_table", 1, "b1", false);
        String end = g.play(3600);
        System.out.println(g.timelineText());
        assertEquals("done", end, g.timelineText());
        // "cut 4" takes the whole tree of 6 (the fork's chop, as the game); the table's planks take 1
        assertEquals(5, g.bag.getOrDefault("minecraft:spruce_log", 0), "the whole tree less the table's log: " + g.bag);
        assertTrue(g.timelineText().contains("cut 4 spruce_log at 10 64 0 > craft spruce_planks 4 > craft crafting_table 1"), "the trip's plan: " + g.timelineText());
        assertEquals(1, g.bag.get("minecraft:crafting_table"), g.bag.toString());
        int left = 0;
        for (int y = 64; y < 70; y++) if ("minecraft:spruce_log".equals(g.world.at(10, y, 0))) left++;
        assertEquals(0, left, "the whole tree was cut: " + g.bag);
        assertTrue(g.seconds() > 5 && g.seconds() < 120, "a walk of 10 m and five or six logs by hand: " + g.seconds());
    }

    /** A tree too small for the line: the game ends at its reason, never "done". */
    @Test
    void aTreeTooSmall_endsWithItsReason() {
        SimGame g = treeMap(2);
        g.order("spruce_log", 4, null, false);
        String end = g.play(300);
        System.out.println(g.timelineText());
        assertEquals("not simulated: explore 5 until spruce_log", end, "the plan's next step after the 2 logs is an explore - no actor yet (slice 4): " + g.timelineText());
        assertEquals(2, g.bag.get("minecraft:spruce_log"), g.bag.toString());
    }

    /**
     * Slice 2: the owner's test line (cobblestone 32, spruce_log 16, torch 4 deposit) from an empty bag: four spruce trees,
     * a stone outcrop, the main chest. The plan the brain starts with is the plan run JBS-T1 started with (01:56:26: "cut 1,
     * craft spruce_planks 4, craft spruce_planks 4, craft stick 4, craft spruce_planks 4, craft crafting_table 1, place
     * crafting_table near me, craft wooden_pickaxe 1, take my crafting_table here, ..."), and every step has an actor.
     */
    @Test
    void theOwnersLine_runsToDone_andDeposits() {
        SimWorld w = SimWorld.flat(63, "minecraft:grass_block", 40);
        for (int i = 0; i < 4; i++) w.column(10 + 2 * i, 64, 3, 6, "minecraft:spruce_log");
        for (int x = -12; x <= -6; x++) for (int z = -3; z <= 3; z++) for (int y = 64; y <= 66; y++) w.set(x, y, z, "minecraft:stone");
        w.set(3, 64, -5, "minecraft:chest");
        SimGame g = new SimGame(w, new int[]{0, 64, 0});
        g.mainAt = "3 64 -5";
        g.order("cobblestone", 32, "b1", true);
        g.order("spruce_log", 16, "b1", true);
        g.order("torch", 4, "b1", true);
        String end = g.play(900);
        System.out.println(g.timelineText());
        assertEquals("done", end, g.timelineText());
        String t = g.timelineText();
        assertTrue(t.contains("\"cut 1 #logs_that_burn at 10 64 3 > craft spruce_planks 4 > craft spruce_planks 4 > craft stick 4 > craft spruce_planks 4 > craft crafting_table 1 > "
                + "place crafting_table near me > craft wooden_pickaxe 1 > take my crafting_table here > collect cobblestone 40"), "the plan's head, as run JBS-T1's but for the furnace's own 8 cobblestone (deposit-kept): " + t);
        assertTrue(t.contains("smelted 1 charcoal from {minecraft:spruce_log=1} on {minecraft:spruce_planks=1}"), "the game's fuel rule picks a plank (run JBS: 'spruce_planks 1 for smelt charcoal 1 here'): " + t);
        assertEquals(4, g.chest.get("minecraft:torch"), g.chest.toString());
        assertEquals(16, g.chest.get("minecraft:spruce_log"), "the trees gave 24 logs, so the torch's log and the planks' logs came from the spare: " + g.chest);
        // deposit-kept (owner 2026-11-13: a -d amount goes to the chest whole; was 24 of 32 here, the torch order's furnace
        // took 8 of the finished order's): the trip collects 40, and the finished 32 are kept from the later plans
        assertEquals(32, g.chest.get("minecraft:cobblestone"), "the cobblestone deposited of the 32 ordered: " + g.chest);
        assertTrue(t.contains("kept for a deposit: cobblestone"), "said (rule 7): " + t);
        assertTrue(g.seconds() > 60 && g.seconds() < 400, "seconds: " + g.seconds());
    }

    /**
     * deposit-kept, the owner's other half ("anything not being deposited can be used"): the same line with no deposit word
     * on the cobblestone - the furnace takes 8 of the finished order's 32, nothing more is collected for it.
     */
    @Test
    void anOrderWithNoDepositWord_isIngredientsForTheNext() {
        SimWorld w = SimWorld.flat(63, "minecraft:grass_block", 40);
        for (int i = 0; i < 4; i++) w.column(10 + 2 * i, 64, 3, 6, "minecraft:spruce_log");
        for (int x = -12; x <= -6; x++) for (int z = -3; z <= 3; z++) for (int y = 64; y <= 66; y++) w.set(x, y, z, "minecraft:stone");
        w.set(3, 64, -5, "minecraft:chest");
        SimGame g = new SimGame(w, new int[]{0, 64, 0});
        g.mainAt = "3 64 -5";
        g.order("cobblestone", 32, "b1", false);
        g.order("torch", 4, "b1", true);
        String end = g.play(900);
        String t = g.timelineText();
        assertEquals("done", end, t);
        assertTrue(t.contains("collect cobblestone 32"), "no more than the order: " + t);
        assertEquals(24, g.bag.get("minecraft:cobblestone"), "the furnace took 8 of the 32: " + g.bag);
        assertEquals(4, g.chest.get("minecraft:torch"), g.chest.toString());
        assertNull(g.chest.get("minecraft:cobblestone"), "no deposit word, nothing deposited: " + g.chest);
    }

    /** The stone outcrop, the trees and the chest of the owner's line, with 32 cobblestone in the bag already. */
    private static SimGame lineMapWith32() {
        SimWorld w = SimWorld.flat(63, "minecraft:grass_block", 40);
        for (int i = 0; i < 4; i++) w.column(10 + 2 * i, 64, 3, 6, "minecraft:spruce_log");
        for (int x = -12; x <= -6; x++) for (int z = -3; z <= 3; z++) for (int y = 64; y <= 66; y++) w.set(x, y, z, "minecraft:stone");
        w.set(3, 64, -5, "minecraft:chest");
        SimGame g = new SimGame(w, new int[]{0, 64, 0});
        g.mainAt = "3 64 -5";
        g.give("minecraft:cobblestone", 32);
        return g;
    }

    /**
     * deposit-kept (owner 2026-11-13: "if I do drop the torch order, it should still deposit the cobblestone"): the line's
     * cobblestone ended, the torch order is dropped - the line ends there and the cobblestone goes to the chest.
     */
    @Test
    void aDroppedOrder_stillDepositsTheLinesFinishedOnes() {
        SimGame g = lineMapWith32();
        g.order("cobblestone", 32, "b1", true);
        var torch = g.order("torch", 4, "b1", true);
        g.tick();
        g.drop(torch);
        String end = g.play(300);
        String t = g.timelineText();
        assertEquals("done", end, t);
        assertTrue(t.contains("torch dropped (nothing of it is deposited): the line's deposit of cobblestone 32 to the main chest"), t);
        assertEquals(32, g.chest.get("minecraft:cobblestone"), g.chest.toString());
    }

    /**
     * hold-bound (owner 2026-11-24, plan step 2; worlds run 3: "deposit: held" to the end in 6 games): the line's other
     * order is stopped and nothing it waits on can change (every order stopped, nothing runs). Wanted: the line's deposit is
     * owed and goes, the cobblestone reaches the chest, and the end names the shortfall.
     */
    @Test
    void aStoppedOrderThatNothingCanWake_doesNotHoldTheLinesDeposit() {
        SimGame g = lineMapWith32();
        g.order("cobblestone", 32, "b1", true);
        var log = g.order("spruce_log", 16, "b1", true);
        // the sim has no explore, so the stop is set as the game's park leaves it: a PLACE park that keeps its facts, its
        // look-around leg and climb try taken (run 3 w006: looked, then nothing left)
        log.parked = "stopped at step 1 (cut 6 spruce_log at 10 64 3): no way from here";
        log.park = new io.github.mojolowjo.entropybot.aa.brain.Goals.Park(io.github.mojolowjo.entropybot.aa.brain.Goals.ParkKind.PLACE, new int[]{0, 64, 0}, 32, java.util.Set.of(), java.util.Set.of());
        log.looked = true;
        log.climbRetried = true;
        g.play(600);
        String t = g.timelineText();
        assertEquals(32, g.chest.get("minecraft:cobblestone"), "the cobblestone reached the chest, not held for the stopped log order: " + g.chest + "\n" + t);
        assertTrue(t.contains("short: spruce_log 0 of 16"), "the log order's shortfall is named (rule 7): " + t);
    }

    /**
     * deposit-kept (owner 2026-11-13: "the bot should gather 10 more if the 32 is going to be deposited ... the items that
     * are tagged with -d should be considered separate"): 32 wait for the line's deposit, a new order of 10 is not met by them.
     */
    @Test
    void aNewOrderOfAKeptItem_gathersItsOwn() {
        SimGame g = lineMapWith32();
        g.order("cobblestone", 32, "b1", true);
        g.order("torch", 4, "b1", true);
        g.tick();
        g.order("cobblestone", 10, null, false);
        String end = g.play(900);
        String t = g.timelineText();
        assertEquals("done", end, t);
        assertTrue(t.contains("cobblestone (goal 10 done)"), "the new order met by its own: " + t);
        assertEquals(32, g.chest.get("minecraft:cobblestone"), "the -d amount whole: " + g.chest);
        // 32 kept + 10 for the new order + 8 for the torch's furnace, or the furnace took 8 of the no-word 10 (allowed)
        int dug = 0;
        for (int x = -12; x <= -6; x++) for (int z = -3; z <= 3; z++) for (int y = 64; y <= 66; y++) if (!"minecraft:stone".equals(g.world.at(x, y, z))) dug++;
        assertTrue(dug >= 10, "at least the new order's 10 were dug: " + dug + "\n" + t);
    }

    /**
     * reserves-once (b), run 3 w064: the running order's OWN -d amount (cobblestone 32 -d, 20 in the bag, not met) and a
     * torch -d in its trip. The runner's craft and smelt (SimSteps over the free bag, as Crafting.planBag over
     * AaRuntime.keptFromUse) never use a kept amount, and the planner prices the furnace and the fuel the same way (the
     * trip sets the bag's kept amounts aside from its start): the line ends done with the 32 whole, no re-plan loop.
     */
    @Test
    void theRunningOrdersOwnKeptAmount_isNotTheFurnaces_w064() {
        SimGame g = lineMapWith32();
        g.take("minecraft:cobblestone", 12);                       // 20 of the 32 in the bag: the order is not met
        g.order("cobblestone", 32, "b1", true);
        g.order("torch", 4, "b1", true);
        String end = g.play(900);
        String t = g.timelineText();
        assertEquals("done", end, t);
        assertFalse(t.contains("FROM A KEPT"), "no smelt paid from a kept amount: " + t);
        assertFalse(t.contains("the kept -d amounts left out"), "no craft refused for a kept amount (the planner priced the same bag): " + t);
        assertEquals(32, g.chest.get("minecraft:cobblestone"), "the -d amount whole: " + g.chest);
        assertEquals(4, g.chest.get("minecraft:torch"), g.chest.toString());
    }

    /** Slice 3: the orders of a scenario's words - open ones with their deposit word, the finished ones first. */
    @Test
    void aScenariosWords_giveItsOrders() {
        var a = SimGame.asked("owner goals (3):\nspruce_log 11/16 (deposit): score 70 running - chain: trip\ntorch 0/4 (deposit): score 70\n"
                + "finished, kept from pillaring (aa drop <thing> lets them go): cobblestone 32\nideas (1250 recipes): x 1.0");
        assertEquals(java.util.List.of(new SimGame.Asked("cobblestone", 32, true), new SimGame.Asked("spruce_log", 16, true), new SimGame.Asked("torch", 4, true)), a);
        assertEquals(java.util.List.of(new SimGame.Asked("dirt", 5, false)), SimGame.asked("owner goals (1):\ndirt 0/5: score 70"));
        assertTrue(SimGame.asked("no orders").isEmpty());
    }

    /**
     * Slice 3: the file run JBS-T1 wrote at 02:00:34 (boxed in under ground while exploring for spruce logs) played from
     * that moment: its cube, feet, bag (53 cobblestone, 11 logs, 2 sticks), main chest and three orders.
     */
    @Test
    void theRunJbsFile_playsFromItsMoment() throws Exception {
        Scenario s = Scenario.read(java.nio.file.Path.of(SimGameTest.class.getResource("/scenarios/runJBS-T1-boxed-in.json.gz").toURI()));
        long t = System.currentTimeMillis();
        SimGame g = SimGame.of(s);
        int open = g.world.reach(g.feet).size();
        String end = g.play(900);
        String tl = g.timelineText();
        System.out.println(tl);
        System.out.println("computed in " + (System.currentTimeMillis() - t) + " ms; cells open from the feet " + open + "; bag " + g.bag + "; chest " + g.chest);
        // the game said "boxed in at -61 114 -7: no way out without breaking or placing": the sim's open-way check agrees
        // (a second source), and says so on the one move that leaves the box
        assertTrue(open < 40, "boxed in: few cells open from the feet: " + open);
        assertTrue(tl.contains("dug a way"), "slice 4b: the way out of the box is a dug way, said: " + tl);
        assertEquals("done", end, tl);
        assertEquals(java.util.Map.of("minecraft:cobblestone", 32, "minecraft:spruce_log", 16, "minecraft:torch", 4), g.chest);
        assertTrue(tl.contains("collect coal 13"), "coal in sight: the torch's fuel is coal ore here, not charcoal: " + tl);
    }

    /**
     * The owner's line on 40 slightly different maps (owner 2026-11-13: "run the same tests a few times but in a slightly
     * different environment"): the trees' count, heights and places, the stone outcrop's place and size, the chest's and
     * the bot's spots all vary by the seed. Each end is printed; a play that ends "done" must have deposited the line whole.
     */
    @Test
    void theOwnersLine_onVariedMaps() {
        java.util.Map<String, Integer> ends = new java.util.TreeMap<>();
        StringBuilder table = new StringBuilder();
        for (int seed = 1; seed <= 40; seed++) {
            clear();
            java.util.Random r = new java.util.Random(seed);
            SimWorld w = SimWorld.flat(63, "minecraft:grass_block", 40);
            int trees = 2 + r.nextInt(6), logs = 0;
            for (int i = 0; i < trees; i++) {
                int h = 4 + r.nextInt(4);
                w.column(r.nextInt(61) - 30, 64, r.nextInt(61) - 30, h, "minecraft:spruce_log");
                logs += h;
            }
            int sx = r.nextInt(51) - 25, sz = r.nextInt(51) - 25, sw = 2 + r.nextInt(5), sh = 1 + r.nextInt(3);
            for (int x = sx; x < sx + sw; x++) for (int z = sz; z < sz + sw; z++) for (int y = 64; y < 64 + sh; y++) w.set(x, y, z, "minecraft:stone");
            int cx = r.nextInt(31) - 15, cz = r.nextInt(31) - 15;
            w.set(cx, 64, cz, "minecraft:chest");
            int[] feet = {r.nextInt(11) - 5, 64, r.nextInt(11) - 5};
            if (!w.standable(feet[0], feet[1], feet[2])) feet = new int[]{feet[0], w.standY(feet[0], 64, feet[2]), feet[2]};
            SimGame g = new SimGame(w, feet);
            g.mainAt = cx + " 64 " + cz;
            g.order("cobblestone", 32, "b1", true);
            g.order("spruce_log", 16, "b1", true);
            g.order("torch", 4, "b1", true);
            String end = g.play(1800);
            String tl = g.timelineText();
            String kind = end.startsWith("not simulated: ") ? "not simulated: " + end.substring(15).replaceAll("[-\\d]+", "N") : end.replaceAll("[-\\d]+", "N");
            ends.merge(kind.length() > 90 ? kind.substring(0, 90) : kind, 1, Integer::sum);
            table.append(String.format(java.util.Locale.ROOT, "seed %2d: %d trees %2d logs, stone %2d  -> %4.0f s  %s | chest %s%n", seed, trees, logs, sw * sw * sh, g.seconds(),
                    end.length() > 110 ? end.substring(0, 110) : end, g.chest));
            if (end.equals("done")) {
                assertEquals(java.util.Map.of("minecraft:cobblestone", 32, "minecraft:spruce_log", 16, "minecraft:torch", 4), g.chest, "seed " + seed + ": done means the line deposited whole\n" + tl);
                assertFalse(tl.contains("SHORT"), "seed " + seed + "\n" + tl);
            }
        }
        System.out.println("VARIED MAPS\n" + table + "ENDS " + ends);
        assertEquals(8, ends.get("done"), "the maps with 40 stone and about 20 logs in them (seen 2026-11-13): " + ends);
        assertEquals(40, ends.get("done") + ends.get("not simulated: explore N until cobblestone") + ends.get("not simulated: explore N until spruce_log"), "every other map ends at an explore for what it lacks: " + ends);
    }

    /**
     * Slice 4: the owner's line on 40 lands with no edge (trees and stone outcrops scattered by the seed, thick or thin):
     * what the start does not show, an explore finds. Each end is printed; "done" must have deposited the line whole.
     */
    static final int LANDS = 12;        // 40 were played 2026-11-13 (all done, 82 s to compute); 12 keep the suite quick

    @Test
    void theOwnersLine_onEndlessLands_explores() {
        java.util.Map<String, Integer> ends = new java.util.TreeMap<>();
        StringBuilder table = new StringBuilder();
        for (int seed = 1; seed <= LANDS; seed++) {
            clear();
            java.util.Random r = new java.util.Random(seed * 0x9E3779B97F4A7C15L);       // structural: spreads the small seeds
            double treeP = 0.02 + r.nextDouble() * 0.28, stoneP = 0.01 + r.nextDouble() * 0.14;
            SimWorld w = SimWorld.endless(seed, 63, treeP, stoneP);
            w.set(2, 64, 2, "minecraft:chest");
            int[] feet = {0, w.standY(0, 64, 0), 0};
            SimGame g = new SimGame(w, feet);
            g.sight = 40;
            g.mainAt = "2 64 2";
            g.order("cobblestone", 32, "b1", true);
            g.order("spruce_log", 16, "b1", true);
            g.order("torch", 4, "b1", true);
            String end = g.play(3600);
            String tl = g.timelineText();
            int explores = tl.split("-> explore", -1).length - 1;
            String kind = end.replaceAll("[-\\d]+", "N");
            ends.merge(kind.length() > 100 ? kind.substring(0, 100) : kind, 1, Integer::sum);
            table.append(String.format(java.util.Locale.ROOT, "land %2d: trees %.2f stone %.2f -> %5.0f s, %d explores, %s | chest %s%n", seed, treeP, stoneP, g.seconds(), explores,
                    end.length() > 130 ? end.substring(0, 130) : end, g.chest));
            if (!end.equals("done") && ends.get(kind.length() > 100 ? kind.substring(0, 100) : kind) == 1) System.out.println("TIMELINE land " + seed + "\n" + tl);
            if (end.equals("done")) {
                assertEquals(java.util.Map.of("minecraft:cobblestone", 32, "minecraft:spruce_log", 16, "minecraft:torch", 4), g.chest, "land " + seed + ": done means the line deposited whole\n" + tl);
                assertFalse(tl.contains("SHORT"), "land " + seed + "\n" + tl);
            }
        }
        System.out.println("ENDLESS LANDS\n" + table + "ENDS " + ends);
        assertEquals(java.util.Map.of("done", LANDS), ends, "every land ends done (seen 2026-11-13):\n" + table);
        assertTrue(table.toString().contains("3 explores"), "thin lands need explores:\n" + table);
    }

    /**
     * Slice 4b: the owner's line on lands where stone and coal lie only under the ground (dirt 3 deep over them): the bot
     * digs its way down, and comes back up to the trees and the chest. Each end is printed; "done" must have deposited whole.
     */
    static final int DEEP_LANDS = 6;

    @Test
    void theOwnersLine_onDeepLands_digsDown() {
        java.util.Map<String, Integer> ends = new java.util.TreeMap<>();
        StringBuilder table = new StringBuilder();
        for (int seed = 1; seed <= DEEP_LANDS; seed++) {
            clear();
            java.util.Random r = new java.util.Random(seed * 0x9E3779B97F4A7C15L);       // structural: spreads the small seeds
            double treeP = 0.05 + r.nextDouble() * 0.25, coalP = 0.005 + r.nextDouble() * 0.03;
            SimWorld w = SimWorld.deep(seed, 63, treeP, coalP, 12);
            w.set(2, 64, 2, "minecraft:chest");
            int[] feet = {0, w.standY(0, 64, 0), 0};
            SimGame g = new SimGame(w, feet);
            g.sight = 24;
            g.mainAt = "2 64 2";
            g.order("cobblestone", 32, "b1", true);
            g.order("spruce_log", 16, "b1", true);
            g.order("torch", 4, "b1", true);
            long t = System.currentTimeMillis();
            String end = g.play(3600);
            String tl = g.timelineText();
            String kind = end.replaceAll("[-\\d]+", "N");
            ends.merge(kind.length() > 100 ? kind.substring(0, 100) : kind, 1, Integer::sum);
            table.append(String.format(java.util.Locale.ROOT, "deep %2d: trees %.2f coal %.3f -> %5.0f s, %d dug ways, %d climbs, %s | chest %s | %d ms%n", seed, treeP, coalP, g.seconds(),
                    tl.split("dug a way", -1).length - 1, tl.split("climb", -1).length - 1, end.length() > 130 ? end.substring(0, 130) : end, g.chest, System.currentTimeMillis() - t));
            if (seed == 1 || !end.equals("done") && ends.get(kind.length() > 100 ? kind.substring(0, 100) : kind) == 1) System.out.println("TIMELINE deep " + seed + "\n" + tl);
            if (end.equals("done")) {
                assertEquals(java.util.Map.of("minecraft:cobblestone", 32, "minecraft:spruce_log", 16, "minecraft:torch", 4), g.chest, "deep " + seed + ": done means the line deposited whole\n" + tl);
                assertFalse(tl.contains("SHORT"), "deep " + seed + "\n" + tl);
            }
        }
        System.out.println("DEEP LANDS\n" + table + "ENDS " + ends);
        assertEquals(java.util.Map.of("done", DEEP_LANDS), ends, "every deep land ends done (seen 2026-11-14):\n" + table);
        assertFalse(table.toString().contains(" 0 dug ways"), "stone is under the ground: every land needs a dug way:\n" + table);
    }

    /**
     * Slice 4b: the games' failing case - the stone far down (dirt 10 thick over it) and the main chest 60 blocks off: the
     * bot ends its dig deep under ground with trees and a deposit still to do above. Each end is printed.
     */
    @Test
    void theOwnersLine_onDeepStoneAndAFarChest() {
        java.util.Map<String, Integer> ends = new java.util.TreeMap<>();
        StringBuilder table = new StringBuilder();
        for (int seed = 1; seed <= DEEP_LANDS; seed++) {
            clear();
            java.util.Random r = new java.util.Random(seed * 0x9E3779B97F4A7C15L);       // structural: spreads the small seeds
            double treeP = 0.05 + r.nextDouble() * 0.25, coalP = 0.005 + r.nextDouble() * 0.03;
            SimWorld w = SimWorld.deep(seed, 63, treeP, coalP, 24, 10);
            w.set(40, 64, 45, "minecraft:chest");
            int[] feet = {0, w.standY(0, 64, 0), 0};
            SimGame g = new SimGame(w, feet);
            g.sight = 24;
            g.mainAt = "40 64 45";
            g.order("cobblestone", 32, "b1", true);
            g.order("spruce_log", 16, "b1", true);
            g.order("torch", 4, "b1", true);
            long t = System.currentTimeMillis();
            String end = g.play(3600);
            String tl = g.timelineText();
            String kind = end.replaceAll("[-\\d]+", "N");
            ends.merge(kind.length() > 100 ? kind.substring(0, 100) : kind, 1, Integer::sum);
            table.append(String.format(java.util.Locale.ROOT, "far %2d: trees %.2f coal %.3f -> %5.0f s, %d dug ways, %d climbs, %d up to the surface, %s | chest %s | %d ms%n", seed, treeP, coalP, g.seconds(),
                    tl.split("dug a way", -1).length - 1, tl.split("  climb", -1).length - 1, tl.split("  up to the surface", -1).length - 1, end.length() > 130 ? end.substring(0, 130) : end, g.chest, System.currentTimeMillis() - t));
            if (seed == 1 || !end.equals("done") && ends.get(kind.length() > 100 ? kind.substring(0, 100) : kind) == 1) System.out.println("TIMELINE far " + seed + "\n" + tl);
            if (end.equals("done")) {
                assertEquals(java.util.Map.of("minecraft:cobblestone", 32, "minecraft:spruce_log", 16, "minecraft:torch", 4), g.chest, "far " + seed + ": done means the line deposited whole\n" + tl);
                assertFalse(tl.contains("SHORT"), "far " + seed + "\n" + tl);
            }
        }
        System.out.println("FAR CHEST\n" + table + "ENDS " + ends);
        assertEquals(java.util.Map.of("done", DEEP_LANDS), ends, "every land ends done (seen 2026-11-14):\n" + table);
        assertTrue(table.toString().contains("1 up to the surface"), "the planner sends the bot up to the surface from its dig:\n" + table);
    }

    static final int TROUBLE_LIMIT_S = 1500;     // sim: a troubled game's seconds allowed (the whole ones end under 450 s)
    static final int TROUBLE_GAMES = 30;         // sim: the games of the troubles test (6 lands, 5 dice each)

    /**
     * Step 5: the deep-stone far-chest lands again, with steps failing at the rates the recorded runs showed. Nothing is
     * pinned about how they end yet: the ends are printed, with the first timeline of each kind, to see what the brain does
     * with a failed step.
     */
    @Test
    void theOwnersLine_withMeasuredTroubles() {
        java.util.Map<String, Integer> ends = new java.util.TreeMap<>();
        StringBuilder table = new StringBuilder();
        int whole = 0, hit = 0, hitWhole = 0;
        for (int i = 0; i < TROUBLE_GAMES; i++) {
            int seed = 1 + i % DEEP_LANDS;
            clear();
            java.util.Random r = new java.util.Random(seed * 0x9E3779B97F4A7C15L);       // structural: spreads the small seeds
            double treeP = 0.05 + r.nextDouble() * 0.25, coalP = 0.005 + r.nextDouble() * 0.03;
            SimWorld w = SimWorld.deep(seed, 63, treeP, coalP, 24, 10);
            w.set(40, 64, 45, "minecraft:chest");
            SimGame g = new SimGame(w, new int[]{0, w.standY(0, 64, 0), 0});
            g.sight = 24;
            g.mainAt = "40 64 45";
            g.troubleRates.putAll(SimGame.MEASURED);
            g.troubleDice = new java.util.Random(1000 + i);
            g.order("cobblestone", 32, "b1", true);
            g.order("spruce_log", 16, "b1", true);
            g.order("torch", 4, "b1", true);
            String end = g.play(TROUBLE_LIMIT_S);
            String tl = g.timelineText();
            boolean full = java.util.Map.of("minecraft:cobblestone", 32, "minecraft:spruce_log", 16, "minecraft:torch", 4).equals(g.chest);
            if (full) whole++;
            if (g.troubles > 0) { hit++; if (full) hitWhole++; }
            String kind = (full ? "whole line in the chest" : "NOT whole") + " | " + end.replaceAll("[-\\d]+", "N");
            if (kind.length() > 110) kind = kind.substring(0, 110);
            boolean first = ends.merge(kind, 1, Integer::sum) == 1;
            table.append(String.format(java.util.Locale.ROOT, "game %2d land %d: %d troubles, %d climbs that placed, %d that had nothing spare -> %5.0f s, %s | chest %s%n", i, seed, g.troubles, tl.split("\\(placed \\d", -1).length - 1, tl.split("placed nothing", -1).length - 1, g.seconds(), end.length() > 110 ? end.substring(0, 110) : end, g.chest));
            if (first) System.out.println("TIMELINE troubles game " + i + "\n" + tl);
        }
        System.out.println("TROUBLES\n" + table + "ENDS " + ends + "\nwhole " + whole + " of " + TROUBLE_GAMES + "; games with a trouble " + hit + ", of those whole " + hitWhole);
        assertTrue(hit > 0, "the dice gave no trouble at all:\n" + table);
    }

    /**
     * A climb's placing is counted by the mod's reserves over the fork's own math: with 8 of 10 cobblestone reserved for an
     * order, 2 are spare; dirt no reserve names is all spare; a torch is no filler.
     */
    @Test
    void aClimbPlacesOnlyWhatTheReservesSpare() {
        SimGame g = treeMap(6);
        g.give("minecraft:cobblestone", 10);
        g.give("minecraft:dirt", 3);
        g.give("minecraft:torch", 5);
        g.lease = java.util.List.of(io.github.mojolowjo.entropybot.aa.moves.Reservations.order("cobblestone", java.util.List.of("cobblestone"), 8));
        assertEquals(2, g.spare("minecraft:cobblestone"));
        assertEquals(3, g.spare("minecraft:dirt"));
        assertEquals(0, g.spare("minecraft:torch"));
    }
    /** A step kind with no actor ends the game saying so (rule 7), never skipped. */
    @Test
    void aStepWithNoActor_endsNotSimulated() {
        SimGame g = treeMap(6);
        var r = SimSteps.apply(g, new io.github.mojolowjo.entropybot.aa.moves.Step.Sleep());
        assertTrue(r.notSimulated());
    }
}
