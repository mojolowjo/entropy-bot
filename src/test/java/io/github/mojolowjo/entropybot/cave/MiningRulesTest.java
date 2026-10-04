package io.github.mojolowjo.entropybot.cave;

import io.github.mojolowjo.entropybot.guard.Box;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** B7d D3: the ore lists, the "mine" grammar, the cave decisions and the Baritone mine's rules (sim "mining" checks, ported). */
class MiningRulesTest {
    static final List<String> ORES = OreSpec.oreIds(List.of("minecraft:stone", "minecraft:iron_ore", "minecraft:deepslate_iron_ore", "minecraft:coal_ore",
            "minecraft:deepslate_coal_ore", "oritech:nickel_ore", "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore", "minecraft:redstone_ore",
            "minecraft:lapis_ore", "minecraft:ancient_debris", "minecraft:iron_block"));

    static String ids(String t) {
        OreSpec.Parsed p = OreSpec.parse(t, ORES);
        if (p.err() != null) return "ERR " + p.err();
        List<String> out = new ArrayList<>();
        for (OreSpec.Entry e : p.spec().list) out.add(e.ids() == null ? "*" : String.join("+", e.ids()));
        return String.join(" | ", out);
    }

    // ---- ore lists ----

    @Test
    void oreLists() {
        assertFalse(ORES.contains("minecraft:stone"));
        assertTrue(ORES.contains("minecraft:ancient_debris"));
        assertEquals("minecraft:iron_ore+minecraft:deepslate_iron_ore", ids("iron"), "a family is every ore of it");
        assertEquals("minecraft:iron_ore", ids("iron_ore"), "an id is just that block");
        assertEquals("oritech:nickel_ore | minecraft:coal_ore+minecraft:deepslate_coal_ore", ids("nickel, coal"), "a modded family and a list in order");
        assertEquals("*", ids("any"));
        assertEquals("minecraft:diamond_ore+minecraft:deepslate_diamond_ore", ids("diamonds"), "a plural");
        assertTrue(ids("unobtainium").startsWith("ERR no ore is called unobtainium (try iron"), ids("unobtainium"));
        assertEquals("ERR I don't know an ore called gold_ore", ids("gold_ore"));
        assertEquals("ERR which ores? e.g. iron,diamond (or \"any\")", ids(" "));
        OreSpec s = OreSpec.parse("iron,coal", ORES).spec();
        assertEquals("iron,coal", s.label);
        assertEquals(0, s.match("minecraft:deepslate_iron_ore"));
        assertEquals(1, s.match("minecraft:coal_ore"));
        assertEquals(-1, s.match("minecraft:diamond_ore"));
        assertEquals(List.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore", "minecraft:coal_ore", "minecraft:deepslate_coal_ore"), new ArrayList<>(s.ids()));
        assertEquals("3 iron,coal ores", s.word(3));
        assertEquals("1 any".replace("any", "ore"), OreSpec.parse("any", ORES).spec().word(1));
        assertEquals("5 ores", OreSpec.parse("any", ORES).spec().word(5));
        Map<String, Integer> tally = Map.of("minecraft:iron_ore", 2, "minecraft:diamond_ore", 4, "minecraft:coal_ore", 1);
        assertEquals(3, s.count(tally));
        assertEquals(7, OreSpec.parse("any", ORES).spec().count(tally));
        assertEquals(10, OreSpec.parse("all", ORES).spec().ids().size());
    }

    // ---- the grammar ----

    @Test
    void grammar() {
        assertEquals(MineGrammar.Kind.GRAMMAR, MineGrammar.parse("", null, ORES).kind());
        assertTrue(MineGrammar.parse(" ", null, ORES).text().startsWith("say \"mine strip <ores>"));
        MineGrammar.Parsed o = MineGrammar.parse("iron_ore 4 dig", null, ORES);
        assertEquals(MineGrammar.Kind.ORE, o.kind());
        assertEquals("iron_ore 4 dig", o.text());
        MineGrammar.Parsed c = MineGrammar.parse("cave iron 5", null, ORES);
        assertEquals(MineGrammar.Kind.CAVE, c.kind());
        assertEquals(5, c.n());
        assertEquals(0, c.minutes());
        assertNull(c.at());
        c = MineGrammar.parse("cave any 10m 30 at cave_2", null, ORES);
        assertEquals(30, c.n());
        assertEquals(10, c.minutes());
        assertEquals("cave_2", c.at());
        assertEquals("any", c.spec().label);
        c = MineGrammar.parse("CAVE 3 5m", "iron,coal", ORES);
        assertEquals("iron,coal", c.spec().label, "no ores named: the preferred ones");
        assertEquals(3, c.n());
        assertEquals(5, c.minutes());
        assertEquals("error: which ores? e.g. iron,diamond (or \"any\") - or set a default with \"ores prefer iron,diamond\"", MineGrammar.parse("cave 5", null, ORES).text());
        assertEquals("error: no ore is called gold (try iron, coal, copper, gold, diamond, emerald, redstone, lapis, quartz, or an id)", MineGrammar.parse("cave gold", null, ORES).text());
        MineGrammar.Parsed st = MineGrammar.parse("strip iron 2", null, ORES);
        assertEquals(MineGrammar.Kind.STRIP, st.kind());
        assertEquals("mine", st.at());
        assertEquals(2, st.n());
        assertEquals("deepmine", MineGrammar.parse("strip iron at deepmine", null, ORES).at());
        assertEquals("error: a strip mine counts ores, not minutes: mine strip iron 16", MineGrammar.parse("strip iron 5m", null, ORES).text());
    }

    // ---- caving ----

    static CaveSearch.Ore ore(int x, int y, int z, String id, int dist) { return new CaveSearch.Ore(x, y, z, id, x, y - 1, z, dist); }

    @Test
    void stopReasons() {
        OreSpec iron = OreSpec.parse("iron", ORES).spec();
        Map<String, Integer> bread = Map.of("minecraft:bread", 3), none = Map.of("minecraft:cobblestone", 64);
        assertEquals("that makes 5 iron ores", CaveRules.stopReason(iron, 5, 5, 0, 100, 20, 20, 20, bread));
        assertNull(CaveRules.stopReason(iron, 5, 0, 0, 100, 20, 20, 20, bread), "no target: the count never ends it");
        assertEquals("the time is up", CaveRules.stopReason(iron, 0, 5, 101, 100, 20, 20, 20, bread));
        assertEquals("my bag is nearly full", CaveRules.stopReason(iron, 0, 5, 0, 100, 4, 20, 20, bread));
        assertEquals("my health is low (9)", CaveRules.stopReason(iron, 0, 5, 0, 100, 20, 9.4f, 20, bread));
        assertEquals("I am hungry and carry no food", CaveRules.stopReason(iron, 0, 5, 0, 100, 20, 20, 7, none));
        assertNull(CaveRules.stopReason(iron, 0, 5, 0, 100, 20, 20, 7, Map.of("minecraft:cooked_beef", 1)));
        assertTrue(CaveRules.needsTorch(1, 0));
        assertFalse(CaveRules.needsTorch(2, 0));
        assertFalse(CaveRules.needsTorch(0, 3));
    }

    @Test
    void oneVeinAtATime() {
        Map<String, Integer> tried = new HashMap<>();
        Set<String> tooHard = new LinkedHashSet<>();
        List<CaveSearch.Ore> ores = List.of(ore(5, 40, 0, "minecraft:iron_ore", 3), ore(6, 40, 0, "minecraft:iron_ore", 4),
                ore(30, 40, 0, "minecraft:iron_ore", 9), ore(7, 41, 1, "minecraft:deepslate_diamond_ore", 5), ore(8, 40, 0, "minecraft:iron_ore", 6));
        List<CaveSearch.Ore> v = CaveRules.pickVein(ores, tried, o -> false, o -> !o.id().contains("diamond"), tooHard);
        assertEquals(3, v.size(), "the nearest and the ones within 4 of it, not the one 25 away");
        assertEquals(5, v.get(0).x());
        assertEquals(Set.of("deepslate_diamond_ore"), tooHard, "one it can't mine is noted, not chased");
        assertEquals(2, tried.get("7 41 1"));
        assertEquals(1, tried.get("5 40 0"));
        // tried twice: passed over, so the next vein is the far one
        CaveRules.pickVein(ores, tried, o -> false, o -> true, tooHard);
        v = CaveRules.pickVein(ores, tried, o -> false, o -> true, tooHard);
        assertEquals(1, v.size());
        assertEquals(30, v.get(0).x());
        // the nearest ore more than 16 steps away: none
        assertTrue(CaveRules.pickVein(List.of(ore(1, 1, 1, "minecraft:iron_ore", 17)), new HashMap<>(), o -> false, o -> true, tooHard).isEmpty());
        // one the guard refuses is passed over
        v = CaveRules.pickVein(List.of(ore(1, 1, 1, "minecraft:iron_ore", 2), ore(2, 1, 1, "minecraft:iron_ore", 3)), new HashMap<>(), o -> o.x() == 1, o -> true, tooHard);
        assertEquals(1, v.size());
        assertEquals(2, v.get(0).x());
        // at most 8
        List<CaveSearch.Ore> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) many.add(ore(i % 4, 40 + i / 4, 0, "minecraft:iron_ore", 2 + i));
        assertEquals(8, CaveRules.pickVein(many, new HashMap<>(), o -> false, o -> true, tooHard).size());
    }

    @Test
    void movesAndTexts() {
        List<CaveRules.Known> caves = List.of(new CaveRules.Known("cave_1", "minecraft:overworld", 100, 40, 0, true),
                new CaveRules.Known("cave_2", "minecraft:overworld", 20, 40, 0, false),
                new CaveRules.Known("cave_3", "minecraft:the_nether", 5, 40, 0, true),
                new CaveRules.Known("cave_4", "minecraft:overworld", 50, 40, 0, true),
                new CaveRules.Known("cave_5", "minecraft:overworld", 10, 40, 0, true));
        List<String> noCaveAt = new ArrayList<>(List.of("cave_5 (10 40 0)"));
        Set<String> closed = CaveRules.closedNames(noCaveAt);
        assertEquals(Set.of("cave_5"), closed);
        assertEquals("cave_4", CaveRules.nearestKnown(caves, "minecraft:overworld", 0, 40, 0, closed, k -> false).name(),
                "the nearest with frontier left, in this dimension, not one this job closed");
        assertEquals("cave_1", CaveRules.nearestKnown(caves, "minecraft:overworld", 0, 40, 0, closed, k -> k.name().equals("cave_4")).name(), "the guard's no");
        assertNull(CaveRules.nearestKnown(caves, "minecraft:overworld", 0, 40, 0, Set.of("cave_1", "cave_4", "cave_5"), k -> false));
        assertEquals("there is no cave I can get into near here (cave_5 (10 40 0), cave_6 (0 60 0); last: no dark corner in reach)",
                CaveRules.noCaveText(List.of("cave_5 (10 40 0)", "cave_6 (0 60 0)"), "no dark corner in reach"));
        OreSpec iron = OreSpec.parse("iron", ORES).spec();
        assertEquals("mined 1 iron ore in cave_1 (no dark corner left in reach); cave_1 is finished",
                CaveRules.endNote(iron, 1, "cave_1", "no dark corner left in reach", true, false, 0, Set.of(), List.of()), "the sim's report, word for word");
        assertEquals("mined 0 iron ores - there is no cave I can get into near here (x)",
                CaveRules.endNote(iron, 0, "cave_7", "there is no cave I can get into near here (x)", false, true, 0, Set.of(), List.of("cave_7 (1 2 3)")));
        Set<String> hard = new LinkedHashSet<>(List.of("deepslate_diamond_ore", "obsidian"));
        assertEquals("mined 3 iron ores in cave_2 (the time is up), placed 5 torches; left deepslate_diamond_ore, obsidian (needs a better pickaxe); no cave at cave_9 (0 50 0), so I moved",
                CaveRules.endNote(iron, 3, "cave_2", "the time is up", false, false, 5, hard, List.of("cave_9 (0 50 0)")));
        assertEquals("caving in cave_1 for iron (5), 30 min", CaveRules.label("cave_1", iron, 5, 30));
        assertEquals("caving in cave_1 for any, 10 min", CaveRules.label("cave_1", OreSpec.parse("any", ORES).spec(), 0, 10));
        assertEquals("caving in cave_12 for iron (5), 30 min", CaveRules.relabel("caving in cave_1 for iron (5), 30 min", "cave_12"));
        assertEquals("cave_1 at 20 40 0 (finished)", CaveRules.listLine("cave_1", 20, 40, 0, false, 9));
        assertEquals("cave_1 at 20 40 0 (got 9 blocks in)", CaveRules.listLine("cave_1", 20, 40, 0, true, 9));
    }

    // ---- the Baritone mine ----

    @Test
    void mineArgsCountAndGains() {
        MineRules.Args a = MineRules.args("Iron_Ore 4 dig");
        assertEquals("minecraft:iron_ore", a.id());
        assertEquals(4, a.count());
        assertTrue(a.dig());
        assertEquals(1, MineRules.args("oritech:nickel_ore").count());
        assertFalse(MineRules.BLOCK_ID.matcher("minecraft:iron ore").matches());
        assertTrue(MineRules.EXTRA.matcher("minecraft:gravel").matches());
        assertFalse(MineRules.EXTRA.matcher("minecraft:stone").matches());
        assertEquals("error: \"mine\" only takes ores (and sand, gravel, clay, grass): oak_planks could be part of a build. \"dig x1 y1 z1 x2 y2 z2\" clears a box",
                MineRules.notAnOre("minecraft:oak_planks"));
        assertEquals("error: I only \"mine\" inside my areas (map, home); elsewhere use stripmine", MineRules.outsideAreas(List.of("map", "home"), "hint"));
        assertEquals("error: I only \"mine\" inside my areas (none set - area add <name> here <r>); elsewhere use stripmine",
                MineRules.outsideAreas(List.of(), "area add <name> here <r>"));
        Map<String, Integer> g = new LinkedHashMap<>();
        g.put("minecraft:raw_iron", 3);
        g.put("minecraft:cobblestone", 9);
        g.put("minecraft:iron_ore", 1);
        assertEquals(4, MineRules.count("minecraft:iron_ore", g), "raw iron and the block itself");
        assertEquals(3, MineRules.count("minecraft:deepslate_iron_ore", g), "another block of the family is no block of it (as the bridge)");
        assertEquals(2, MineRules.count("minecraft:redstone_ore", Map.of("minecraft:redstone", 9)), "redstone drops about 4 a block");
        assertEquals(1, MineRules.count("minecraft:deepslate_lapis_ore", Map.of("minecraft:lapis_lazuli", 5)));
        assertEquals(2, MineRules.count("minecraft:sand", Map.of("minecraft:sand", 2)));
        assertEquals(" - got 9 cobblestone, 3 raw_iron, 1 iron_ore", MineRules.gains(g));
        assertEquals(" - got nothing", MineRules.gains(Map.of()));
        Map<String, Integer> five = new LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) five.put("minecraft:i" + i, i);
        assertEquals(" - got 5 i5, 4 i4, 3 i3, 2 i2, +1 more", MineRules.gains(five));
        assertEquals(Map.of("minecraft:raw_iron", 2), MineRules.gained(Map.of("minecraft:raw_iron", 1, "minecraft:dirt", 5), Map.of("minecraft:raw_iron", 3, "minecraft:dirt", 4)));
        assertEquals("mining 4 minecraft:iron_ore (exposed only)", MineRules.status(4, "minecraft:iron_ore", false, 0));
        assertEquals("mining 4 minecraft:iron_ore (digging allowed) - look 2", MineRules.status(4, "minecraft:iron_ore", true, 1));
        assertEquals("done: mining iron_ore: got 2 of 4 - ran out of ore in view, also in 3 more places I walked to, breaking turned back off - got 2 raw_iron; made a stone pickaxe for mining iron_ore",
                MineRules.shortText("minecraft:iron_ore", 2, 4, "ran out of ore in view, also in 3 more places I walked to", " - got 2 raw_iron", "made a stone pickaxe for mining iron_ore"));
        assertEquals("done: mining iron_ore stopped, breaking turned back off - got 4 raw_iron", MineRules.doneText("minecraft:iron_ore", " - got 4 raw_iron", null));
        assertEquals("time is up (20 min)", MineRules.timeUpText());
    }

    static Box box(String name, int x1, int z1, int x2, int z2) { return new Box(name, "minecraft:overworld", x1, Box.ALL_Y_MIN, z1, x2, Box.ALL_Y_MAX, z2); }

    @Test
    void protectBoxesAndLeases() {
        List<Box> prot = List.of(new Box("base", "minecraft:overworld", -40, -64, 170, -10, 120, 200));
        MineRules.Near n = MineRules.protectNear(prot, "minecraft:overworld", -27, 53, 187, 16);
        assertEquals(0, n.gap());
        assertEquals("I won't mine here: I am inside the protected base (Baritone's mine digs where it likes, so I keep 16 blocks away from protect boxes)", MineRules.protectNearText(n));
        n = MineRules.protectNear(prot, "minecraft:overworld", 0, 53, 187, 16);
        assertEquals(10, n.gap());
        assertTrue(MineRules.protectNearText(n).startsWith("I won't mine here: the protected base is 10 blocks away"));
        assertNull(MineRules.protectNear(prot, "minecraft:overworld", 7, 53, 187, 16), "17 away is fine");
        assertNull(MineRules.protectNear(prot, "minecraft:the_nether", -27, 53, 187, 16));
        assertEquals(20, MineRules.gap(prot.get(0), -27, 140, 187), "the box's y counts");

        // the 32 x 33 x 32 box, clipped to the area, minus the protect box, every slice within a lease's size
        List<Box> areas = List.of(box("map", -272, -64, 223, 431));
        int[] mb = MineRules.mineBox(-2, 50, 187);
        List<int[]> parts = MineRules.leaseBoxes(mb, areas, prot, "minecraft:overworld");
        assertNotNull(parts);
        long vol = 0;
        for (int[] b : parts) {
            long v = (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
            assertTrue(v <= MineRules.LEASE_MAX, "slice " + v);
            vol += v;
            for (int x = b[0]; x <= b[3]; x++) assertTrue(x > -10 || b[2] > 200 || b[5] < 170, "no slice inside the protect box");
        }
        assertEquals(32L * 33 * 32 - 9L * 33 * 30, vol, "x -18..-10, z 171..200 is the base's, the rest is leased");
        // clipped by the area's edge
        parts = MineRules.leaseBoxes(MineRules.mineBox(220, 50, 0), areas, List.of(), "minecraft:overworld");
        int maxX = Integer.MIN_VALUE;
        for (int[] b : parts) maxX = Math.max(maxX, b[3]);
        assertEquals(223, maxX);
        assertNull(MineRules.leaseBoxes(MineRules.mineBox(1000, 50, 0), areas, List.of(), "minecraft:overworld"), "no area there");
        assertNull(MineRules.leaseBoxes(mb, List.of(), List.of(), "minecraft:overworld"), "no areas at all");
        // an area with heights
        List<int[]> ylim = MineRules.leaseBoxes(mb, List.of(new Box("deep", "minecraft:overworld", -100, -64, 100, 100, 40, 300)), List.of(), "minecraft:overworld");
        for (int[] b : ylim) assertTrue(b[4] <= 40 && b[1] >= 34);
        // minus: a box with no y cuts every height
        List<int[]> m = MineRules.minus(new int[]{0, 0, 0, 9, 9, 9}, box("p", 3, 3, 5, 5));
        long left = 0;
        for (int[] b : m) left += (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
        assertEquals(1000 - 3 * 10 * 3, left);
        assertEquals(1, MineRules.minus(new int[]{0, 0, 0, 9, 9, 9}, box("p", 30, 30, 50, 50)).size());
    }

    @Test
    void pickaxes() {
        List<MineRules.Slot> slots = List.of(new MineRules.Slot(12, "minecraft:iron_pickaxe", true), new MineRules.Slot(3, "minecraft:stone_pickaxe", true),
                new MineRules.Slot(4, "minecraft:diamond_pickaxe", true), new MineRules.Slot(5, "minecraft:iron_sword", true));
        assertEquals(12, MineRules.pickSlot(slots, true, "stone", "iron"), "tools ores iron: the cheapest of iron or better");
        assertEquals(3, MineRules.pickSlot(slots, true, "stone", "cheapest"));
        List<MineRules.Slot> stoneOnly = List.of(new MineRules.Slot(3, "minecraft:stone_pickaxe", false));
        assertEquals(-1, MineRules.pickSlot(stoneOnly, true, "iron", "iron"), "a stone pickaxe can't get deepslate diamond");
        assertEquals(3, MineRules.pickSlot(List.of(new MineRules.Slot(3, "minecraft:stone_pickaxe", false)), false, "stone", "iron"), "dig: by tier");
        assertEquals(-1, MineRules.pickSlot(List.of(new MineRules.Slot(3, "minecraft:wooden_pickaxe", false)), false, "stone", "iron"));
        assertEquals("iron", MineRules.pickNeed(true, "iron", false));
        assertEquals("stone", MineRules.pickNeed(false, "diamond", true), "sand with dig: a stone pickaxe for the stone");
        assertNull(MineRules.pickNeed(false, "stone", false));
        assertEquals(List.of("minecraft:iron_pickaxe", "minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe"), MineRules.pickIds("iron"));
        assertEquals(List.of("minecraft:stone_pickaxe", "minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe"), MineRules.pickIds("stone"));
        assertEquals(4, MineRules.bestPickTier(List.of("minecraft:stone_pickaxe", "minecraft:iron_pickaxe", "minecraft:dirt")));
        assertEquals(0, MineRules.bestPickTier(List.of("minecraft:dirt")));
        assertEquals("an iron", MineRules.anA("iron"));
        assertEquals("a stone", MineRules.anA("stone"));
    }

    @Test
    void theWayBack() {
        List<int[]> trail = new ArrayList<>();
        trail.add(new int[]{0, 40, 0});
        MineRules.trail(trail, new int[]{5, 40, 0});
        assertEquals(1, trail.size(), "a point every 12 blocks");
        for (int x = 12; x <= 120; x += 12) MineRules.trail(trail, new int[]{x, 40, 0});
        assertEquals(11, trail.size());
        int[] anchor = {120, 40, 0};
        List<int[]> legs = MineRules.legs(trail, anchor, new int[]{110, 40, 5});
        assertEquals(1, legs.size(), "close: straight there");
        // from far away (after /home near the start): along the trail, legs at most ~30-40 apart, the anchor last
        legs = MineRules.legs(trail, anchor, new int[]{-2, 40, 3});
        assertArrayEquals(new int[]{0, 40, 0}, legs.get(0));
        assertArrayEquals(anchor, legs.get(legs.size() - 1));
        for (int i = 1; i < legs.size(); i++) {
            long d = Math.abs(legs.get(i)[0] - legs.get(i - 1)[0]);
            assertTrue(d <= 48, "leg " + d);
        }
        assertEquals(List.of(0, 36, 72, 120), legs.stream().map(l -> l[0]).toList());
        // the trail keeps its first point and at most 300
        List<int[]> t2 = new ArrayList<>();
        t2.add(new int[]{0, 0, 0});
        for (int i = 1; i <= 400; i++) MineRules.trail(t2, new int[]{i * 20, 0, 0});
        assertEquals(300, t2.size());
        assertArrayEquals(new int[]{0, 0, 0}, t2.get(0));
    }
}
