package io.github.mojolowjo.entropybot.strip;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.clear.Pos;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B7d D2: the run's report (minedone) from the clears' reports (fake outcomes, the sim's wording), "mine strip"'s
 * end, the replies of status/reset/ores/turn, ore lists and the mines' progress notes (select, reset, prune).
 */
class StripReportTest {
    /** The sim's strip run 1, the clears' reports word for word (clear/StripTest's STRIP_RUN1). */
    static final List<String> STRIP_RUN1 = List.of(
            "ok: done digging room for the mine chests - broke 4 blocks",
            "ok: done digging room for a crafting table - broke 1 blocks",
            "ok: done digging the mine corridor (branch 1) - broke 6 blocks",
            "ok: done digging branch 1 left - broke 24 blocks; 1 ores mined",
            "ok: done digging branch 1 right - broke 24 blocks",
            "ok: done mining 1 ore blocks next to branch 1 - broke 1 blocks; 1 ores mined");

    @Test
    void stripRunOneReport() {
        RunNotes n = new RunNotes();
        for (String r : STRIP_RUN1) n.addClear(r);
        // what the collecting digs added to the bag: the iron in branch 1's path, the coal in the wall
        n.addGains(Map.of("minecraft:dirt", 64), Map.of("minecraft:dirt", 80, "minecraft:raw_iron", 1, "minecraft:cobblestone", 10));
        n.addGains(Map.of(), Map.of("minecraft:coal", 1));
        n.baseTrip = true;
        n.setupNote = null;
        String note = n.doneNote(1, 71);
        assertEquals("60 blocks dug, 2 ores mined (1 raw_iron, 1 coal), took 71 items to base - next run digs branch 2", note);
        // the sim's check of the whisper
        assertTrue(("round 1 done of 3 - done strip mining branch 1 (setting up chests first); " + note).matches("round 1 done of 3 - done strip mining branch 1 .*ores mined \\(.*raw_iron.*\\), took \\d+ items to base.*"));
    }

    @Test
    void theDocsExample() {
        RunNotes n = new RunNotes();
        n.addClear("ok: done digging the mine corridor (branch 56) - broke 3 blocks");
        n.addClear("ok: done digging branch 56 left - broke 24 blocks; 4 ores mined");
        n.addClear("ok: done digging branch 56 right - broke 24 blocks; 3 ores mined");
        Map<String, Integer> got = new LinkedHashMap<>();
        got.put("minecraft:coal", 2);
        got.put("minecraft:raw_iron", 9);
        n.addGains(Map.of(), got);
        n.baseTrip = true;
        assertEquals("51 blocks dug, 7 ores mined (9 raw_iron, 2 coal), took 71 items to base - next run digs branch 57", n.doneNote(56, 71));
    }

    @Test
    void notesSaidOnceAndTheRest() {
        RunNotes n = new RunNotes();
        n.addClear("ok: done digging branch 1 left - broke 23 blocks; 1 ores left in place for you (PM \"ores\")");
        n.addClear("ok: finished digging branch 2 right - broke 16 blocks; 8 left, e.g. 308 41 -6 next to water/lava, 309 41 -6 out of reach (nowhere to stand close enough)");
        n.setupNote = "used the chest at 101 40 0 already there";
        n.toolNote = "fetched an iron pickaxe for the deepslate_redstone_ore at 100 40 -5";
        n.addSkip("branch 2 right skipped (outside my areas)");
        n.addSkip("branch 3 left skipped (stuck - 8 tries)");
        n.baseNote = "no trip to the base (I know no base chests - PM \"scan base\" at the base first)";
        assertEquals("39 blocks dug, 1 ores left for you (PM \"ores\"), 1 tunnel(s) stopped short of water/lava, no trip to the base (I know no base chests - "
                        + "PM \"scan base\" at the base first), used the chest at 101 40 0 already there, fetched an iron pickaxe for the deepslate_redstone_ore at 100 40 -5, "
                        + "branch 2 right skipped (outside my areas), branch 3 left skipped (stuck - 8 tries) - next run digs branch 3",
                n.doneNote(2, 0));
        // the setup, tool and skip notes are said once; the totals and the base note run on, as in the bridge
        assertEquals("39 blocks dug, 1 ores left for you (PM \"ores\"), 1 tunnel(s) stopped short of water/lava, no trip to the base (I know no base chests - "
                + "PM \"scan base\" at the base first) - next run digs branch 4", n.doneNote(3, 0));
    }

    @Test
    void oresMinedWithNoDrops() {
        RunNotes n = new RunNotes();
        n.addClear("ok: done digging branch 1 left - broke 24 blocks; 1 ores mined");
        assertEquals("24 blocks dug, 1 ores mined (no drops picked up) - next run digs branch 2", n.doneNote(1, 0));
    }

    @Test
    void mineStripEnds() {
        OreSpec iron = OreSpec.parse("iron", OreSpecIds.IDS);
        assertNull(StripTexts.stripStop(2, 1, false, 1));
        assertEquals("that makes 2", StripTexts.stripStop(2, 2, false, 2));
        assertEquals("an hour is up", StripTexts.stripStop(0, 9, true, 4));
        assertEquals("30 runs", StripTexts.stripStop(0, 9, false, 30));
        assertEquals("mined 2 iron ores in 2 runs (that makes 2); last run: 60 blocks dug - next run digs branch 3",
                StripTexts.stripDone(iron, 2, 2, "that makes 2", "60 blocks dug - next run digs branch 3"));
        assertEquals("strip mining for iron (2) at mine", StripTexts.stripJobLabel(iron, 2, "mine"));
        assertEquals("strip mining for iron,diamond at deepmine", StripTexts.stripJobLabel(OreSpec.parse("iron, diamond", OreSpecIds.IDS), 0, "deepmine"));
        Map<String, Integer> tally = Map.of("minecraft:iron_ore", 1, "minecraft:deepslate_iron_ore", 1, "minecraft:coal_ore", 3);
        assertEquals(2, iron.count(tally));
        assertEquals(5, OreSpec.parse("any", OreSpecIds.IDS).count(tally));
    }

    @Test
    void replies() {
        MineGeom g = new MineGeom(-119, -54, 194, "east");
        JsonObject n = MineBook.fresh(g.key());
        assertEquals("mine at -119 -54 194 going east: 0 branch pairs dug, not set up yet; ores: collecting inside the mapped area", StripTexts.status(g, n, true));
        MineBook.setSetup(n, List.of(new Pos(-120, -54, 194), new Pos(-120, -54, 195)), new Pos(-119, -54, 193));
        MineBook.setK(n, 44);
        assertEquals("mine at -119 -54 194 going east: 43 branch pairs dug, chests at -120 -54 194 and -120 -54 195; ores: listing only", StripTexts.status(g, n, false));
        assertEquals("ok: the mine starts over from branch 1 (the chests stay where they are)", StripTexts.resetReply());
        assertEquals("ok: I'll mine the ores I pass inside my areas (map, home) and take them to the base; elsewhere they stay listed - from the next run on",
                StripTexts.oresSet(true, StripTexts.areaText(List.of("map", "home"))));
        assertEquals("ok: I'll leave ores in place and list them (PM \"ores\") - from the next run on", StripTexts.oresSet(false, "x"));
        assertTrue(StripTexts.oresMode(true).startsWith("ores: mined inside the mapped area"));
        assertTrue(StripTexts.oresMode(false).startsWith("ores: left in place"));
        assertEquals("my areas (none set - area add <name> here <r>)", StripTexts.areaText(List.of()));
        assertEquals(StripTexts.NO_MINE, StripTexts.noMine("mine"));
        assertTrue(StripTexts.noMine("deepmine").contains("deepmine is no mine"));
        assertEquals("error: the mine mine (300 40 0) is outside my areas (map), where I mine no ores - area add <name> here <r>",
                StripTexts.outsideAreas("mine", new Pos(300, 40, 0), "my areas (map)"));
        assertEquals("ok: when a \"mine\" names no ores I go for iron,coal, in that order", StripTexts.preferSet("iron,coal"));
        assertEquals("I go for iron,coal when a \"mine\" names no ores", StripTexts.preferShow("iron,coal"));
        assertEquals("no preferred ores yet - \"ores prefer diamond,iron,copper\"", StripTexts.preferShow(null));
        assertTrue(StripTexts.MINE_GRAMMAR.startsWith("say \"mine strip <ores>"));
        assertEquals("an iron", StripTexts.anA("iron"));
        assertEquals("a stone", StripTexts.anA("stone"));
    }

    @Test
    void oreLists() {
        OreSpec iron = OreSpec.parse("iron", OreSpecIds.IDS);
        assertEquals("iron", iron.label);
        assertEquals(List.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore"), iron.list.get(0).ids());
        assertEquals(List.of("minecraft:iron_ore"), OreSpec.parse("iron_ore", OreSpecIds.IDS).list.get(0).ids());
        OreSpec two = OreSpec.parse("nickel, coal", OreSpecIds.IDS);
        assertEquals("nickel,coal", two.label);
        assertEquals(List.of("oritech:nickel_ore"), two.list.get(0).ids());
        assertEquals(1, two.match("minecraft:coal_ore"));
        assertEquals(-1, two.match("minecraft:iron_ore"));
        assertNull(OreSpec.parse("any", OreSpecIds.IDS).list.get(0).ids());
        assertEquals(0, OreSpec.parse("any", OreSpecIds.IDS).match("minecraft:ancient_debris"));
        assertEquals("diamond", OreSpec.parse("diamonds", OreSpecIds.IDS).label.replace("s", ""));
        assertEquals(List.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore"), OreSpec.parse("diamonds", OreSpecIds.IDS).list.get(0).ids());
        assertTrue(OreSpec.parse("unobtainium", OreSpecIds.IDS).err.startsWith("no ore is called unobtainium"));
        assertEquals("I don't know an ore called foo_ore", OreSpec.parse("foo_ore", OreSpecIds.IDS).err);
        assertEquals("which ores? e.g. iron,diamond (or \"any\")", OreSpec.parse("  ", OreSpecIds.IDS).err);
        assertEquals("3 iron ores", iron.oresWord(3));
        assertEquals("1 iron ore", iron.oresWord(1));
        assertEquals("5 ores", OreSpec.parse("any", OreSpecIds.IDS).oresWord(5));
    }

    @Test
    void mineNotes() {
        JsonObject root = new JsonObject();
        MineBook b = new MineBook(root);
        JsonObject m = b.select("0 40 0 north");
        MineBook.setK(m, 4);
        // another marked mine keeps its own progress (the sim's "switching mines keeps progress")
        b.select("0 40 50 north");
        b.select("0 40 0 north");
        assertEquals(4, MineBook.k(b.current()));
        assertEquals(1, MineBook.k(b.old("0 40 50 north")));
        b.reset("0 40 0 north");
        assertEquals(1, MineBook.k(b.current()));
        assertFalse(MineBook.setup(b.current()));
        MineBook.setSetup(b.current(), List.of(new Pos(-1, 40, 0)), null);
        assertTrue(MineBook.setup(b.current()));
        assertNull(MineBook.table(b.current()));
        assertTrue(MineBook.collect(b.current()));
        b.current().addProperty("collectOres", false);
        assertFalse(MineBook.collect(b.current()));
        MineBook.addBad(b.current(), "3 left");
        assertEquals(List.of("3 left"), MineBook.bad(b.current()));
        // the bridge's memory.json shape reads as is
        JsonObject mem = JsonParser.parseString("{\"mine\":{\"at\":\"-119 -54 194 east\",\"k\":44,\"setup\":true,\"chests\":[{\"x\":-120,\"y\":-54,\"z\":194}],"
                + "\"table\":{\"x\":-119,\"y\":-54,\"z\":193}}}").getAsJsonObject();
        MineBook fromBridge = new MineBook(mem);
        assertSame(fromBridge.current(), fromBridge.select("-119 -54 194 east"));
        assertEquals(new Pos(-119, -54, 193), MineBook.table(fromBridge.current()));
        // old notes past the cap go, oldest first, never one a marked place points at
        for (int i = 0; i < 25; i++) b.select(i + " 40 0 north");
        // (25 old notes: 0 40 50, 0 40 0 and 1..23; the current one is 24)
        int dropped = b.prune(Set.of("0 40 0 north"));
        assertEquals(5, dropped);
        assertNotNull(b.old("0 40 0 north"));
        assertNull(b.old("0 40 50 north"));
        assertNotNull(b.old("23 40 0 north"));
    }
}
