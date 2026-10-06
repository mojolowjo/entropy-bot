package io.github.mojolowjo.entropybot.gather;

import io.github.mojolowjo.entropybot.chop.ChopRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** P2: the source table, the plan on fake stock and recipes, the stop rules, the grammar, the texts; P2's chop fallback helper. */
class GatherTest {
    static final List<String> PLANKS = List.of("minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks");
    static final List<String> OAK_LOGS = List.of("minecraft:oak_log", "minecraft:oak_wood", "minecraft:stripped_oak_log");

    /** Fake stock and recipes; canMake = every need of one recipe is in stock (one level, like a planner that only crafts). */
    static final class Fake implements GatherPlan.World {
        final Map<String, Integer> bag = new HashMap<>(), stored = new HashMap<>();
        final Map<String, List<GatherPlan.Recipe>> recipes = new HashMap<>();
        final Map<String, String> overrides = new LinkedHashMap<>();
        boolean mine;

        Fake() {
            craft("minecraft:oak_planks", 4, need(OAK_LOGS, 1));
            craft("minecraft:spruce_planks", 4, need(List.of("minecraft:spruce_log"), 1));
            craft("minecraft:birch_planks", 4, need(List.of("minecraft:birch_log"), 1));
            craft("minecraft:stick", 4, need(PLANKS, 2));
            craft("minecraft:torch", 4, need(List.of("minecraft:coal", "minecraft:charcoal"), 1), need(List.of("minecraft:stick"), 1));
            smelt("minecraft:iron_ingot", "minecraft:iron_ore");          // listed first: the plan still picks raw_iron
            smelt("minecraft:iron_ingot", "minecraft:raw_iron");
            craft("minecraft:iron_ingot", 1, need(List.of("minecraft:iron_nugget"), 9));
            smelt("minecraft:glass", "minecraft:sand");
            smelt("minecraft:charcoal", "minecraft:oak_log");
            craft("minecraft:bread", 1, need(List.of("minecraft:wheat"), 3));
            craft("minecraft:furnace", 1, need(List.of("minecraft:cobblestone"), 8));
            craft("minecraft:diamond_pickaxe", 1, need(List.of("minecraft:diamond"), 3), need(List.of("minecraft:stick"), 2));
            craft("minecraft:bucket", 1, need(List.of("minecraft:iron_ingot"), 3));
        }

        static GatherPlan.Need need(List<String> alts, int n) { return new GatherPlan.Need(alts, n); }

        void craft(String id, int out, GatherPlan.Need... needs) {
            recipes.computeIfAbsent(id, k -> new ArrayList<>()).add(new GatherPlan.Recipe(List.of(needs), out, false));
        }

        void smelt(String id, String input) {
            recipes.computeIfAbsent(id, k -> new ArrayList<>()).add(new GatherPlan.Recipe(List.of(need(List.of(input), 1)), 1, true));
        }

        @Override public int bag(String id) { return bag.getOrDefault(id, 0); }

        @Override public int stored(String id) { return stored.getOrDefault(id, 0); }

        @Override public boolean canMake(String id, int n) {
            for (GatherPlan.Recipe r : recipes.getOrDefault(id, List.of())) {
                int crafts = (int) Math.ceil(n / (double) r.out());
                boolean ok = true;
                for (GatherPlan.Need nd : r.needs()) {
                    int have = 0;
                    for (String a : nd.alts()) have += bag(a) + stored(a);
                    if (have < nd.amount() * crafts) ok = false;
                }
                if (ok && r.smelt() && !GatherPlan.hasFuel(this, crafts)) ok = false;
                if (ok) return true;
            }
            return false;
        }

        @Override public List<GatherPlan.Recipe> recipes(String id) { return recipes.getOrDefault(id, List.of()); }

        @Override public boolean mineMarked() { return mine; }

        @Override public Map<String, String> overrides() { return overrides; }
    }

    // ---- the source table ----

    @Test
    void sourceTable() {
        Map<String, String> none = Map.of();
        assertEquals(GatherSources.Kind.ORE, GatherSources.resolve("raw_iron", none).kind());
        assertEquals("iron", GatherSources.resolve("minecraft:raw_iron", none).family());
        assertEquals("lapis", GatherSources.resolve("lapis_lazuli", none).family());
        assertEquals("minecraft:diamond_ore", GatherSources.resolve("diamond", none).block());
        GatherSources.Source nickel = GatherSources.resolve("oritech:raw_nickel", none);
        assertEquals(GatherSources.Kind.ORE, nickel.kind());
        assertEquals("oritech:nickel_ore", nickel.block());
        assertEquals("oak", GatherSources.resolve("oak_log", none).wood());
        assertEquals("dark_oak", GatherSources.resolve("minecraft:dark_oak_log", none).wood());
        assertEquals(GatherSources.Kind.NONE, GatherSources.resolve("crimson_stem", none).kind());
        assertEquals(GatherSources.Kind.CROP, GatherSources.resolve("wheat", none).kind());
        assertEquals(GatherSources.Kind.CROP, GatherSources.resolve("mysticalagriculture:inferium_essence", none).kind());
        assertNull(GatherSources.resolve("mysticalagriculture:prudentium_essence", none));
        assertEquals(GatherSources.Kind.BLOCK, GatherSources.resolve("sand", none).kind());
        assertEquals(4, GatherSources.resolve("clay_ball", none).perBlock());
        assertEquals(GatherSources.Kind.NONE, GatherSources.resolve("cobblestone", none).kind());
        assertEquals(GatherSources.Kind.NONE, GatherSources.resolve("quartz", none).kind());
        assertNull(GatherSources.resolve("stick", none));
        assertNull(GatherSources.resolve("iron_ingot", none));
        // an owner's source wins
        GatherSources.Source o = GatherSources.resolve("cobblestone", Map.of("minecraft:cobblestone", "dig 0 60 0 4 62 4"));
        assertEquals(GatherSources.Kind.OVERRIDE, o.kind());
    }

    @Test
    void sourceCommands() {
        Map<String, String> none = Map.of();
        GatherSources.Source iron = GatherSources.resolve("raw_iron", none);
        assertEquals(List.of("mine strip iron 5", "mine iron_ore 5", "mine cave iron 5 10m"), GatherSources.commands(iron, 5, true, false));
        assertEquals(List.of("mine iron_ore 5", "mine cave iron 5 10m"), GatherSources.commands(iron, 5, false, false));
        assertEquals(List.of("chop 4 oak"), GatherSources.commands(GatherSources.resolve("oak_log", none), 4, false, false));
        assertEquals(List.of("chop 4"), GatherSources.commands(GatherSources.resolve("oak_log", none), 4, false, true));
        assertEquals(List.of("mine clay 2"), GatherSources.commands(GatherSources.resolve("clay_ball", none), 8, false, false));
        assertEquals(List.of("farm"), GatherSources.commands(GatherSources.resolve("wheat", none), 8, false, false));
        GatherSources.Source o = GatherSources.resolve("x:thing", Map.of("x:thing", "mine strip nickel {n}"));
        assertEquals(List.of("mine strip nickel 7"), GatherSources.commands(o, 7, false, false));
        assertEquals("mined", GatherSources.doneWord("mine strip iron 4"));
        assertEquals("chopped", GatherSources.doneWord("chop 4"));
    }

    // ---- the plan ----

    @Test
    void ingotFromRawFromOre() {
        Fake w = new Fake();
        GatherPlan.Step s = GatherPlan.next("iron_ingot", 4, w);
        assertEquals(GatherPlan.Kind.SOURCE, s.kind());
        assertEquals("minecraft:raw_iron", s.leaf());
        assertEquals(4, s.n());
        assertEquals("mine iron_ore 4", s.command(0));
        assertEquals("mine cave iron 4 10m", s.command(1));
        w.mine = true;
        assertEquals("mine strip iron 4", GatherPlan.next("iron_ingot", 4, w).command(0));
        // raw iron there, no fuel: coal is the leaf
        w.bag.put("minecraft:raw_iron", 4);
        GatherPlan.Step c = GatherPlan.next("iron_ingot", 4, w);
        assertEquals("minecraft:coal", c.leaf());
        assertEquals(1, c.n());
        // with coal it is a craft (the craft verb smelts)
        w.stored.put("minecraft:coal", 2);
        assertEquals("craft minecraft:iron_ingot 4", GatherPlan.next("iron_ingot", 4, w).command(0));
        // some in storage: get first
        w.stored.put("minecraft:iron_ingot", 3);
        assertEquals("get minecraft:iron_ingot 3", GatherPlan.next("iron_ingot", 4, w).command(0));
        // enough in the bag: done
        w.bag.put("minecraft:iron_ingot", 4);
        assertEquals(GatherPlan.Kind.DONE, GatherPlan.next("iron_ingot", 4, w).kind());
    }

    @Test
    void planksFromLogsAndSticksFromAnyWood() {
        Fake w = new Fake();
        GatherPlan.Step s = GatherPlan.next("oak_planks", 16, w);
        assertEquals("minecraft:oak_log", s.leaf());
        assertEquals("chop 4 oak", s.command(0));
        // sticks take any planks: any log will do
        GatherPlan.Step st = GatherPlan.next("stick", 8, w);
        assertEquals("chop 1", st.command(0));
        // torches: sticks from wood, coal first (the first missing ingredient)
        GatherPlan.Step t = GatherPlan.next("torch", 32, w);
        assertEquals("minecraft:coal", t.leaf());
        assertEquals(8, t.n());
        w.bag.put("minecraft:coal", 8);
        GatherPlan.Step t2 = GatherPlan.next("torch", 32, w);
        assertEquals("minecraft:oak_log", t2.leaf());
        assertEquals("chop 1", t2.command(0));             // 8 sticks <- 4 planks <- 1 log
        w.bag.put("minecraft:oak_log", 4);
        // logs in the bag: oak planks can be made now
        assertEquals("craft minecraft:oak_planks 16", GatherPlan.next("oak_planks", 16, w).command(0));
    }

    @Test
    void glassFromSandAndBreadFromTheFarm() {
        Fake w = new Fake();
        GatherPlan.Step g = GatherPlan.next("glass", 10, w);
        assertEquals("mine sand 10", g.command(0));
        GatherPlan.Step b = GatherPlan.next("bread", 2, w);
        assertEquals("farm", b.command(0));
        assertEquals("minecraft:wheat", b.leaf());
        assertEquals(6, b.n());
    }

    @Test
    void noWayAndOverrides() {
        Fake w = new Fake();
        GatherPlan.Step f = GatherPlan.next("furnace", 1, w);
        assertEquals(GatherPlan.Kind.NO_WAY, f.kind());
        assertEquals("minecraft:cobblestone", f.leaf());
        assertTrue(f.why().contains("dig x1 y1 z1 x2 y2 z2"), f.why());
        GatherPlan.Step u = GatherPlan.next("minecraft:bedrock", 1, w);
        assertEquals(GatherPlan.Kind.NO_WAY, u.kind());
        w.overrides.put("minecraft:cobblestone", "dig 0 60 0 3 61 3");
        assertEquals("dig 0 60 0 3 61 3", GatherPlan.next("furnace", 1, w).command(0));
        // a modded ingot through its raw ore
        w.smelt("oritech:nickel_ingot", "oritech:raw_nickel");
        assertEquals("mine nickel_ore 2".replace("nickel_ore", "oritech:nickel_ore"), GatherPlan.next("oritech:nickel_ingot", 2, w).command(0));
        w.overrides.put("oritech:raw_nickel", "mine strip nickel {n}");
        assertEquals("mine strip nickel 2", GatherPlan.next("oritech:nickel_ingot", 2, w).command(0));
    }

    @Test
    void diamondPickaxeNeedsDiamondsFirst() {
        Fake w = new Fake();
        GatherPlan.Step s = GatherPlan.next("diamond_pickaxe", 1, w);
        assertEquals("minecraft:diamond", s.leaf());
        assertEquals("mine diamond_ore 3", s.command(0));
        // the bucket: 3 ingots -> 3 raw iron
        assertEquals("mine iron_ore 3", GatherPlan.next("bucket", 1, w).command(0));
    }

    @Test
    void woodKinds() {
        assertEquals(3, GatherPlan.woodTypes(PLANKS));
        assertEquals(1, GatherPlan.woodTypes(OAK_LOGS));
    }

    // ---- grammar, stop rules, texts ----

    @Test
    void parsing() {
        GatherRules.Args a = GatherRules.parse("iron_ingot 64 30m");
        assertEquals(GatherRules.Mode.RUN, a.mode());
        assertEquals("minecraft:iron_ingot", a.item());
        assertEquals(64, a.n());
        assertEquals(30, a.minutes());
        GatherRules.Args b = GatherRules.parse("oritech:nickel_ingot");
        assertEquals(1, b.n());
        assertEquals(60, b.minutes());
        assertEquals(GatherRules.Mode.STATUS, GatherRules.parse("status").mode());
        assertEquals(GatherRules.Mode.SOURCES, GatherRules.parse("sources").mode());
        assertEquals("minecraft:torch", GatherRules.parse("sources torch").item());
        GatherRules.Args s = GatherRules.parse("source iron_ore \"mine strip iron {n}\"");
        assertEquals(GatherRules.Mode.SOURCE_SET, s.mode());
        assertEquals("mine strip iron {n}", s.command());
        assertEquals(GatherRules.Mode.SOURCE_CLEAR, GatherRules.parse("source iron_ore clear").mode());
        assertEquals(GatherRules.Mode.ERROR, GatherRules.parse("source iron_ore mine a then deposit").mode());
        assertEquals(GatherRules.Mode.ERROR, GatherRules.parse("source x gather y 2").mode());
        assertEquals(GatherRules.Mode.ERROR, GatherRules.parse("").mode());
        assertEquals(GatherRules.Mode.ERROR, GatherRules.parse("iron_ingot 0").mode());
        assertEquals(GatherRules.Mode.ERROR, GatherRules.parse("iron_ingot 4 999m").mode());
        assertEquals(GatherRules.Mode.ERROR, GatherRules.parse("two words 4").mode());
    }

    @Test
    void stopRules() {
        assertNull(GatherRules.stopReason(100, 200, 60, 0, "minecraft:raw_iron", null, 0, 1));
        assertTrue(GatherRules.stopReason(200, 200, 60, 0, "x", null, 0, 1).contains("60 minutes are up"));
        String f = GatherRules.stopReason(100, 200, 60, 3, "minecraft:raw_iron", "mine iron_ore 4: error: ran out of ore in view", 0, 5);
        assertTrue(f.startsWith("3 tries at raw_iron failed (last: mine iron_ore 4"), f);
        assertTrue(GatherRules.stopReason(100, 200, 60, 1, "x", null, GatherRules.NO_PROGRESS, 5).contains("brought nothing"));
        assertNotNull(GatherRules.stopReason(100, 200, 60, 0, "x", null, 0, GatherRules.MAX_STEPS));
        assertEquals(72000 + 5, GatherRules.deadline(5, 60));
    }

    @Test
    void texts() {
        GatherRules.Tally t = new GatherRules.Tally();
        t.add("mined", "minecraft:raw_iron", 40);
        t.add("smelted", "minecraft:iron_ingot", 40);
        t.add("took", "minecraft:iron_ingot", 24);
        t.add("mined", "minecraft:raw_iron", 0);
        assertEquals("ok: gathered 64 iron_ingot (mined 40 raw_iron, smelted 40 iron_ingot, took 24 iron_ingot from storage)",
                GatherRules.endText("minecraft:iron_ingot", 64, 64, t, null, null));
        assertEquals("ok: I already have 5 stick", GatherRules.endText("minecraft:stick", 4, 5, new GatherRules.Tally(), null, null));
        String stop = GatherRules.endText("minecraft:iron_ingot", 64, 22, t, "3 tries at raw_iron failed", "put some raw_iron in the base chests");
        assertTrue(stop.startsWith("stopped: gather iron_ingot: 22/64 (mined 40"), stop);
        assertTrue(stop.endsWith("- next: put some raw_iron in the base chests"), stop);
        String nw = GatherRules.noWayText("minecraft:diamond", "minecraft:diamond", 1, 0, new GatherRules.Tally(), "no recipe makes it and I know no source for it");
        assertTrue(nw.startsWith("error: gather diamond: 0/1, no way to get diamond"), nw);
        assertTrue(nw.contains("gather source diamond"), nw);
        assertTrue(GatherRules.nextHint(GatherSources.resolve("raw_iron", Map.of()), "minecraft:raw_iron").startsWith("mark a mine"));
        assertTrue(GatherRules.nextHint(GatherSources.resolve("oak_log", Map.of()), "minecraft:oak_log").contains("where trees grow"));
        assertTrue(GatherRules.nextHint(null, "minecraft:x").contains("gather source x"));
        assertEquals("gather: 3/4 iron_ingot, step 2: mine iron_ore 1", GatherRules.status("minecraft:iron_ingot", 4, 3, 2, "mine iron_ore 1"));
    }

    // ---- P2's chop fix: a failed axe craft goes on by hand ----

    @Test
    void chopAxeFallback() {
        List<String> types = List.of("chopaxe", "walk", "craft", "chopaxecheck", "chopstep");
        assertEquals(3, ChopRules.axeCheckAfter(types, 2));
        assertEquals(3, ChopRules.axeCheckAfter(types, 1));
        assertEquals(-1, ChopRules.axeCheckAfter(types, 4));
        assertEquals(-1, ChopRules.axeCheckAfter(types, 3));
        assertEquals(-1, ChopRules.axeCheckAfter(List.of("chopstep", "walk", "clear"), 2));
        assertEquals("no axe (making one failed: the crafting table did not open) - chopped by hand",
                ChopRules.axeFailedNote("made", "error: the crafting table did not open"));
    }
}
