package io.github.mojolowjo.entropybot.farm;

import io.github.mojolowjo.entropybot.craft.Crafter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** The bridge's sim 'compact' scenario (test/sim.js) and compactPair over the Crafter's recipes. */
class CompactTest {
    static final String ESS = "mysticalagriculture:inferium_essence", BLK = "mysticalagriculture:inferium_block";

    // ---- compactPair ----

    @Test
    void pairs() {
        StubCrafter c = StubCrafter.standard();
        assertEquals(new Compact.Pair(ESS, BLK, 9), Compact.pair(c, ESS));
        assertEquals(new Compact.Pair(ESS, BLK, 9), Compact.pair(c, BLK), "named the block: what is it made of?");
        assertEquals(new Compact.Pair("minecraft:iron_ingot", "minecraft:iron_block", 9), Compact.pair(c, "minecraft:iron_ingot"), "9-to-1 beats 4-to-1 (the trapdoor)");
        assertEquals(new Compact.Pair("minecraft:iron_ingot", "minecraft:iron_block", 9), Compact.pair(c, "minecraft:iron_block"));
        assertEquals(new Compact.Pair("minecraft:iron_nugget", "minecraft:iron_ingot", 9), Compact.pair(c, "minecraft:iron_nugget"));
        assertEquals(new Compact.Pair("minecraft:quartz", "minecraft:quartz_block", 4), Compact.pair(c, "minecraft:quartz"));
        assertEquals(new Compact.Pair("minecraft:quartz", "minecraft:quartz_block", 4), Compact.pair(c, "minecraft:quartz_block"));
        assertNull(Compact.pair(c, "minecraft:dirt"));
        assertNull(Compact.pair(c, "minecraft:oak_log"), "1 log -> 4 planks is no compacting");
        assertNull(Compact.pair(c, "mysticalagriculture:infusion_crystal"), "two ingredients");
        assertNull(Compact.pair(c, "minecraft:oak_planks"), "sticks: 2 -> 4");
    }

    @Test
    void commandTexts() {
        assertEquals("error: usage compact <item> [here | <place> | x y z] (e.g. compact inferium_essence)", Compact.USAGE);
        assertEquals("error: dirt doesn't craft 9-to-1 (or 4-to-1) into a block", Compact.noPair("minecraft:dirt"));
        assertEquals("error: mysticalagriculture:inferium_seeds doesn't craft 9-to-1 (or 4-to-1) into a block", Compact.noPair("mysticalagriculture:inferium_seeds"));
        assertEquals("error: I don't know an item called blorb", Compact.unknownItem("blorb"));
        assertEquals("error: I know no place called x - PM compact <item> x y z", Compact.placeError("error: I know no place called x - PM open x y z"));
        assertArrayEquals(new String[]{"inferium_essence", "base"}, Compact.words("  Inferium_Essence   base "));
        assertArrayEquals(new String[]{"inferium_essence", ""}, Compact.words("inferium_essence"));
        assertEquals(0, Compact.words("  ").length);

        Compact.Pair p = new Compact.Pair(ESS, BLK, 9);
        Compact.Start s = Compact.start(p, new int[]{2, 53, 2}, "2 53 2", new int[]{2, 53, 2});
        assertEquals("compacting inferium_essence into inferium_block around 2 53 2", s.label());
        assertEquals(List.of("compacthere", "compactdone"), s.steps().stream().map(PlanStep::type).toList());
        s = Compact.start(p, new int[]{2, 53, 2}, "farm", new int[]{2, 53, 15});
        assertEquals("compacting inferium_essence into inferium_block around farm", s.label());
        assertEquals(List.of("walk", "compacthere", "compactdone"), s.steps().stream().map(PlanStep::type).toList(), "13 away: walk first");
        assertTrue(s.steps().get(0).near());
    }

    @Test
    void bagSpace() {
        List<Compact.Slot> bag = new ArrayList<>();
        for (int i = 0; i < 36; i++) bag.add(new Compact.Slot(i < 30 ? "minecraft:dirt" : "", i < 30 ? 64 : 0, 64));
        bag.set(0, new Compact.Slot(ESS, 15, 64));
        bag.set(1, new Compact.Slot("minecraft:ender_pearl", 10, 16));
        assertEquals(new Compact.BagSpace(49, 6, 64), Compact.bagSpace(bag, ESS));
        assertEquals(new Compact.BagSpace(6, 6, 16), Compact.bagSpace(bag, "minecraft:ender_pearl"));
        assertEquals(new Compact.BagSpace(0, 6, 64), Compact.bagSpace(bag, BLK));
    }

    // ---- the sim scenario ----

    /** Containers: position -> contents; a found list of those within COMPACT_R of a center; a Seq-like driver. */
    final Map<String, Map<String, Integer>> chests = new LinkedHashMap<>();
    FakeWorld w;
    StubCrafter crafter;
    int crafts;
    String openAt;

    static String k(int[] p) { return p[0] + " " + p[1] + " " + p[2]; }

    int[] stock(int x, int y, int z, Object... items) {
        Map<String, Integer> c = new TreeMap<>();
        for (int i = 0; i < items.length; i += 2) c.merge((String) items[i], (Integer) items[i + 1], Integer::sum);
        chests.put(x + " " + y + " " + z, c);
        return new int[]{x, y, z};
    }

    int in(int[] chest, String id) { return chests.get(k(chest)).getOrDefault(id, 0); }

    List<int[]> found(int[] center) {
        List<int[]> out = new ArrayList<>();
        for (String key : chests.keySet()) {
            String[] s = key.split(" ");
            int[] p = {Integer.parseInt(s[0]), Integer.parseInt(s[1]), Integer.parseInt(s[2])};
            if (Math.abs(p[0] - center[0]) <= Compact.COMPACT_R && Math.abs(p[1] - center[1]) <= Compact.COMPACT_R && Math.abs(p[2] - center[2]) <= Compact.COMPACT_R) out.add(p);
        }
        return out;
    }

    /** Runs "compact <item>" around the bot to its end: the job's final status line. */
    String compact(String item) {
        Compact.Pair pair = Compact.pair(crafter, item);
        if (pair == null) return Compact.noPair(item);
        int[] me = w.here();
        Compact.Start start = Compact.start(pair, me, k(me), me);
        Compact.Run run = start.run();
        List<PlanStep> steps = new ArrayList<>(start.steps());
        String label = start.label(), note = null;
        int putLeft = 0;
        for (int idx = 0; idx < steps.size(); idx++) {
            PlanStep st = steps.get(idx);
            String r = "next";
            List<PlanStep> splice = List.of();
            switch (st.type()) {
                case "walk" -> { }
                case "open" -> openAt = k(st.pos());
                case "close" -> openAt = null;
                case "compacthere" -> {
                    Compact.Result res = run.here(found(st.pos()), me, st.label());
                    r = res.result();
                    splice = res.splice();
                }
                case "compactchest" -> {
                    int have = openAt == null ? 0 : chests.get(openAt).getOrDefault(pair.item(), 0);
                    Compact.Result res = run.chest(st.pos(), st.again(), openAt != null, have, w.bag());
                    r = res.result();
                    splice = res.splice();
                    if (res.label() != null) label = res.label();
                }
                case "take" -> {
                    Map<String, Integer> c = chests.get(openAt);
                    assertTrue(c.getOrDefault(st.item(), 0) >= st.n());
                    int left = w.add(st.item(), st.n());
                    assertEquals(0, left, "the batch fits the bag");
                    c.merge(st.item(), -st.n(), Integer::sum);
                }
                case "craftitem" -> {
                    Crafter.Plan plan = crafter.plan(st.item(), st.n(), w.inventory());
                    if (!plan.ok()) r = plan.error();
                    else {
                        for (Crafter.Step s : plan.steps()) {
                            Crafter.Craft cr = (Crafter.Craft) s;
                            Crafter.Recipe rec = crafter.craftingRecipesFor(cr.item()).get(0);
                            for (Crafter.Need need : rec.needs()) w.remove(need.alts().get(0), need.amount() * cr.times());
                            assertEquals(0, w.add(cr.item(), cr.times() * rec.outCount()));
                        }
                        crafts++;
                    }
                }
                case "put" -> {
                    for (Map.Entry<String, Integer> e : st.keep().entrySet()) {
                        int n = w.count(e.getKey()) - e.getValue();
                        if (n <= 0) continue;
                        w.remove(e.getKey(), n);
                        chests.get(openAt).merge(e.getKey(), n, Integer::sum);
                    }
                }
                case "compactdone" -> {
                    Compact.Result res = run.done(putLeft);
                    note = res.note();
                    label = res.label();
                }
                default -> fail("unknown step " + st.type());
            }
            if (!r.equals("next")) {
                openAt = null;                                       // the job closes the menu however it ends
                return "error: " + r + " (while " + label + ")";
            }
            steps.addAll(idx + 1, splice);
        }
        openAt = null;
        return "ok: done " + label + (note != null ? "; " + note : "");
    }

    @BeforeEach
    void setUp() {
        crafter = StubCrafter.standard();
        w = new FakeWorld();
        w.at(2.5, 53, 2.5);
    }

    @Test
    void simCompactScenario() {
        int[] a = stock(5, 53, 2, ESS, 64 * 4 + 44, "minecraft:cobblestone", 30);
        int[] b = stock(5, 53, 4, ESS, 20, BLK, 5);
        int[] c = stock(-1, 53, 4, ESS, 5);
        int[] d = stock(12, 53, 2, ESS, 100);                             // 10 blocks away: not "here"
        // the bot's own: 15 essence, 2 blocks, and a bag with 4 free slots (room for 177 essence, 2 slots kept for blocks)
        w.give(0, ESS, 15);
        w.give(1, BLK, 2);
        for (int i = 2; i < 32; i++) w.give(i, "minecraft:dirt", 64);

        assertEquals("error: dirt doesn't craft 9-to-1 (or 4-to-1) into a block", compact("minecraft:dirt").replace("minecraft:", ""));
        String st = compact(ESS);
        assertEquals("ok: done compacting inferium_essence into inferium_block around 2 53 2; turned 315 inferium_essence into 35 inferium_block in 2 chests, 10 left over", st);
        assertEquals(3, crafts, "the big chest took two batches (bag-sized)");
        assertEquals(List.of(33, 3, 7, 2), List.of(in(a, BLK), in(a, ESS), in(b, BLK), in(b, ESS)), "blocks went back into the chest they came from");
        assertEquals(5, in(c, ESS), "a chest with fewer than 9 is left alone");
        assertEquals(100, in(d, ESS), "a chest out of range is not touched");
        assertEquals(15, w.count(ESS), "the bot kept its own essence");
        assertEquals(2, w.count(BLK), "the bot kept its own blocks");
        assertEquals(30, in(a, "minecraft:cobblestone"), "the junk in the chest stayed");
        assertNull(openAt, "no menu left open");

        // nothing left to do
        st = compact(ESS);
        assertEquals("ok: done compacting inferium_essence into inferium_block around 2 53 2; nothing to compact: no chest here holds 9 or more inferium_essence (10 in all)", st);

        // room for essence in its part stack, but none for the blocks: only as many as the blocks' room allows
        chests.get(k(c)).put(ESS, 50);
        for (int i = 0; i < 36; i++) if (w.ids[i].isEmpty()) w.give(i, "minecraft:dirt", 64);
        w.give(1, BLK, 61);                                              // room for 3 blocks
        crafts = 0;
        st = compact(ESS);
        assertTrue(st.endsWith("; turned 45 inferium_essence into 5 inferium_block in 1 chest, 10 left over"), st);
        assertEquals(5, in(c, ESS));
        assertEquals(2, crafts);
        assertEquals(61, w.count(BLK));

        // a full bag: a clear reason, nothing taken
        chests.get(k(c)).put(ESS, 50);
        w.give(0, ESS, 64);
        w.give(1, BLK, 64);
        st = compact(ESS);
        assertEquals("error: my inventory is too full to carry 9 inferium_essence (I keep 2 slots free for the blocks) (while compacting inferium_essence into inferium_block around 2 53 2)", st);
        assertEquals(50, in(c, ESS));
        assertNull(openAt, "...and the menu was closed");
    }

    @Test
    void runPieces() {
        Compact.Run run = new Compact.Run(new Compact.Pair(ESS, BLK, 9), "compacting inferium_essence into inferium_block around base");
        assertEquals("no chests or barrels within 6 blocks of base", run.here(List.of(), new int[]{0, 0, 0}, "base").result());
        // nearest first, at most 30
        List<int[]> many = new ArrayList<>();
        for (int i = 40; i > 0; i--) many.add(new int[]{i, 0, 0});
        Compact.Result r = run.here(many, new int[]{0, 0, 0}, "base");
        assertEquals(90, r.splice().size());
        assertEquals("walk 1 0 0", r.splice().get(0).toString());
        assertEquals("compactchest 30 0 0", r.splice().get(89).toString());
        // a closed menu
        assertEquals("no container open", run.chest(new int[]{1, 0, 0}, false, false, 50, List.of()).result());
        // a batch: take, close, craft, walk back, open, put the blocks back (keeping the bot's own), again
        List<Compact.Slot> bag = new ArrayList<>();
        for (int i = 0; i < 36; i++) bag.add(new Compact.Slot("", 0, 64));
        bag.set(3, new Compact.Slot(BLK, 7, 64));
        r = run.chest(new int[]{1, 0, 0}, false, true, 50, bag);
        assertEquals("next", r.result());
        assertEquals("compacting inferium_essence into inferium_block around base (5 inferium_block so far)", r.label());
        assertEquals(List.of("take 1 0 0 mysticalagriculture:inferium_essence 45", "close", "craftitem mysticalagriculture:inferium_block 5",
                "walk 1 0 0", "open 1 0 0", "put keep {mysticalagriculture:inferium_block=7}", "compactchest 1 0 0 again"),
                r.splice().stream().map(PlanStep::toString).toList());
        // back for more: 5 left, not counted as another chest
        r = run.chest(new int[]{1, 0, 0}, true, true, 5, bag);
        assertEquals(List.of("close"), r.splice().stream().map(PlanStep::toString).toList());
        assertEquals(1, run.chests());
        assertEquals("turned 45 inferium_essence into 5 inferium_block in 1 chest, 5 left over (3 blocks didn't fit back, I carry them)", run.done(3).note());
        assertEquals("compacting inferium_essence into inferium_block around base", run.done(0).label());
        // 4-to-1
        Compact.Run q = new Compact.Run(new Compact.Pair("minecraft:quartz", "minecraft:quartz_block", 4), "q");
        r = q.chest(new int[]{1, 0, 0}, false, true, 10, bag);
        assertEquals("take 1 0 0 minecraft:quartz 8", r.splice().get(0).toString());
        assertEquals("craftitem minecraft:quartz_block 2", r.splice().get(2).toString());
        assertEquals("nothing to compact: no chest here holds 4 or more quartz", new Compact.Run(new Compact.Pair("minecraft:quartz", "minecraft:quartz_block", 4), "q").done(0).note());
    }
}
