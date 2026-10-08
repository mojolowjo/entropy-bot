package io.github.mojolowjo.entropybot.vocab;

import io.github.mojolowjo.entropybot.clear.DigArgs;
import io.github.mojolowjo.entropybot.clear.Tools;
import io.github.mojolowjo.entropybot.cave.MineRules;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** V1b-1 (0.22.2): kinds, tools mode, dig +N/-N, places and markers, the queue, the game stage. */
class V1bOneTest {
    static final List<String> IDS = List.of("minecraft:oak_log", "minecraft:cherry_log", "minecraft:stripped_oak_log", "minecraft:crimson_stem",
            "minecraft:oak_planks", "minecraft:iron_ore", "minecraft:deepslate_iron_ore", "minecraft:ancient_debris", "minecraft:stone",
            "minecraft:cobblestone", "minecraft:bread", "minecraft:wheat_seeds", "mysticalagriculture:inferium_seeds", "minecraft:dirt");
    static final java.util.function.Predicate<String> FOOD = id -> id.equals("minecraft:bread");

    @Test
    void kindsExpandAndExclude() {
        assertEquals(List.of("minecraft:cherry_log", "minecraft:crimson_stem", "minecraft:oak_log"), Kinds.expand("logs", IDS, FOOD, null), "stripped logs are left out");
        assertEquals(List.of("minecraft:oak_planks"), Kinds.expand("wood", IDS, FOOD, null));
        assertEquals(3, Kinds.expand("ores", IDS, FOOD, null).size());
        assertEquals(List.of("minecraft:cobblestone", "minecraft:stone"), Kinds.expand("stone", IDS, FOOD, null));
        assertEquals(List.of("minecraft:bread"), Kinds.expand("food", IDS, FOOD, null));
        assertEquals(2, Kinds.expand("seeds", IDS, FOOD, null).size());
        String[] err = new String[1];
        Kinds.Rule r = Kinds.change("logs", "exclude", "cherry_log", Kinds.Rule.none(), IDS, FOOD, err);
        assertNull(err[0]);
        assertEquals(List.of("minecraft:crimson_stem", "minecraft:oak_log"), Kinds.expand("logs", IDS, FOOD, r));
        Kinds.Rule back = Kinds.change("logs", "include", "minecraft:cherry_log", r, IDS, FOOD, err);
        assertEquals(3, Kinds.expand("logs", IDS, FOOD, back).size(), "include undoes the exclusion");
        // an exclusion that empties the kind is refused with the list
        assertNull(Kinds.change("wood", "exclude", "oak_planks", Kinds.Rule.none(), IDS, FOOD, err));
        assertTrue(err[0].contains("leaves wood empty") && err[0].contains("oak_planks"), err[0]);
        assertNull(Kinds.change("leaves", "exclude", "x", Kinds.Rule.none(), IDS, FOOD, err));
        assertTrue(err[0].startsWith("error: no kind leaves"));
        // include of something not of the kind by name
        Kinds.Rule dirt = Kinds.change("stone", "include", "dirt", Kinds.Rule.none(), IDS, FOOD, err);
        assertTrue(Kinds.expand("stone", IDS, FOOD, dirt).contains("minecraft:dirt"));
        assertTrue(Kinds.list(Map.of("logs", r), IDS, FOOD).contains("logs: 2 ids (not cherry_log)"));
        assertEquals("minecraft:oak_log", Kinds.pick(List.of("minecraft:cherry_log", "minecraft:oak_log"), Map.of("minecraft:oak_log", 5)));
        assertEquals("minecraft:cherry_log", Kinds.pick(List.of("minecraft:cherry_log", "minecraft:oak_log"), Map.of()));
        assertNull(Kinds.pick(List.of(), Map.of()));
        // cut ... logs with cherry excluded: the chop filter
        assertFalse(io.github.mojolowjo.entropybot.chop.ChopRules.matches("except:cherry_log.pale_oak_log", "minecraft:cherry_log"));
        assertTrue(io.github.mojolowjo.entropybot.chop.ChopRules.matches("except:cherry_log", "minecraft:birch_log"));
        assertEquals("except:cherry_log", io.github.mojolowjo.entropybot.chop.ChopRules.parse("16 except:cherry_log").type());
    }

    @Test
    void toolModes() {
        assertEquals("best", ToolMode.of(null, null));
        assertEquals("cheapest", ToolMode.of(null, "cheapest"), "the old toolOres setting carries over");
        assertEquals("stone", ToolMode.of("stone", "cheapest"));
        assertEquals("stone", ToolMode.parse("mode STONE"));
        assertNull(ToolMode.parse("ores iron"));
        List<Tools.Slot> inv = List.of(new Tools.Slot(0, "minecraft:wooden_pickaxe", true, 2), new Tools.Slot(1, "minecraft:stone_pickaxe", true, 4),
                new Tools.Slot(2, "minecraft:iron_pickaxe", true, 6), new Tools.Slot(3, "minecraft:diamond_pickaxe", true, 8));
        assertEquals(3, Tools.choose(inv, true, false, "best"), "best: the diamond one");
        assertEquals(0, Tools.choose(inv, true, false, "cheapest"), "cheapest: the wooden one");
        assertEquals(1, Tools.choose(inv, true, false, "stone"), "stone: the stone one");
        // an ore that needs iron: only iron and diamond can (correctForDrops), stone mode takes the tier it needs
        List<Tools.Slot> iron = List.of(new Tools.Slot(1, "minecraft:stone_pickaxe", false, 4), new Tools.Slot(2, "minecraft:iron_pickaxe", true, 6),
                new Tools.Slot(3, "minecraft:diamond_pickaxe", true, 8));
        assertEquals(2, Tools.choose(iron, true, true, "stone"));
        assertEquals(3, Tools.choose(iron, true, true, "best"));
        assertFalse(Tools.stonePicksFirst("minecraft:diamond_pickaxe", false, "best", true, false), "best mode keeps using the best");
        assertTrue(Tools.stonePicksFirst("minecraft:iron_pickaxe", false, "stone", true, false));
        List<MineRules.Slot> picks = List.of(new MineRules.Slot(0, "minecraft:stone_pickaxe", true), new MineRules.Slot(1, "minecraft:iron_pickaxe", true));
        assertEquals(1, MineRules.pickSlot(picks, true, "stone", "best"));
        assertEquals(0, MineRules.pickSlot(picks, true, "stone", "stone"));
        assertEquals(0, MineRules.pickSlot(picks, true, "stone", "cheapest"));
        assertEquals(1, ToolMode.pick(new int[]{1, 2, 4}, "stone"), "wooden goes last in stone mode");
        assertEquals(0, ToolMode.pick(new int[]{1}, "stone"), "only wooden: wooden");
    }

    @Test
    void digSignedForms() {
        DigArgs a = DigArgs.parse("0 0 10 10 -3", null);
        assertTrue(a.surfaceForm());
        assertEquals("down", a.surface());
        assertEquals(3, a.depth());
        DigArgs up = DigArgs.parse("0 0 10 10 +5 ores", null);
        assertEquals("up", up.surface());
        assertTrue(up.ores());
        DigArgs box = DigArgs.parse("0 60 0 10 -3 10", null);
        assertFalse(box.surfaceForm(), "-3 followed by a coordinate is a box's y2");
        assertNull(DigArgs.parse("0 0 10 10 down 3", null), "the old words are gone");
        assertEquals("dig 0 0 10 10 -3 ores", DigArgs.oldSurfaceHint("0 0 10 10 down 3 ores"));
        assertEquals("dig ~-8 ~-8 ~8 ~8 +10", DigArgs.oldSurfaceHint("~-8 ~-8 ~8 ~8 up 10"));
        assertNull(DigArgs.oldSurfaceHint("0 60 0 10 64 10"));
        assertEquals("that is now dig 0 0 10 10 -3", io.github.mojolowjo.entropybot.commands.OldWords.removedAnswer("dig", "0 0 10 10 down 3", "dig 0 0 10 10 down 3"));
        // dig <area>: heights before the question
        List<String> w = List.of("pit");
        assertTrue(DigForms.areaProblem("pit", true, false, true, false, w).contains("covers all heights: add -N"));
        assertNull(DigForms.areaProblem("pit", true, false, true, false, List.of("pit", "-3")));
        assertNull(DigForms.areaProblem("pit", true, false, false, false, List.of("pit", "ores")));
        assertTrue(DigForms.areaProblem("pit", false, false, false, false, w).startsWith("error: I have no area called pit"));
        assertTrue(DigForms.areaProblem("house", true, false, false, true, List.of("house")).contains("safe area"));
        assertTrue(DigForms.areaProblem("pit", true, false, false, false, List.of("pit", "deep")).startsWith("usage"));
        assertEquals("-3", DigForms.signed(List.of("pit", "-3", "ores")));
    }

    @Test
    void placesAndMarkers() {
        Set<String> blocks = Set.of("cobblestone");
        assertEquals(PlaceWords.Kind.BLOCK, PlaceWords.parse("cobblestone 1 2 3", blocks::contains).kind());
        PlaceWords.Parsed p = PlaceWords.parse("farm 1 2 3", blocks::contains);
        assertEquals(PlaceWords.Kind.PLACE, p.kind());
        assertEquals("1 2 3", p.rest());
        assertEquals("north", PlaceWords.parse("mine north", blocks::contains).rest());
        assertEquals(PlaceWords.Kind.ERROR, PlaceWords.parse("", blocks::contains).kind());
        PlaceWords.Marker m = PlaceWords.parseMarker("furnace of base 1 2 3");
        assertEquals("base", m.place());
        assertEquals("1 2 3", m.coords());
        assertNull(PlaceWords.parseMarker("door").place());
        assertNotNull(PlaceWords.parseMarker("door of").error());
        PlaceWords.Place base = new PlaceWords.Place("base", "minecraft:overworld", 0, 64, 0, List.of(new PlaceWords.Spot("furnace", 2, 64, 1)));
        PlaceWords.Place farm = new PlaceWords.Place("farm", "minecraft:overworld", 100, 64, 0, List.of(new PlaceWords.Spot("gate", 101, 64, 0), new PlaceWords.Spot("furnace", 99, 64, 0)));
        List<PlaceWords.Place> all = List.of(base, farm);
        assertEquals("base", PlaceWords.nearest(all, "minecraft:overworld", 10, 64, 10, 32).name());
        assertNull(PlaceWords.nearest(all, "minecraft:overworld", 50, 64, 50, 32));
        String[] err = new String[1];
        assertEquals("farm gate", PlaceWords.resolveGo(all, "gate", null, err).name(), "a unique marker by itself");
        assertNull(PlaceWords.resolveGo(all, "furnace", null, err));
        assertTrue(err[0].contains("marker of base and farm"), err[0]);
        assertEquals(2, PlaceWords.resolveGo(all, "base", "furnace", err).x());
        assertEquals(0, PlaceWords.resolveGo(all, "base", null, err).x());
        assertTrue(PlaceWords.list(all).startsWith("base 0 64 0 [furnace 2 64 1] | farm"));
        List<PlaceWords.Spot> sixteen = new java.util.ArrayList<>();
        for (int i = 0; i < 16; i++) sixteen.add(new PlaceWords.Spot("m" + i, i, 0, 0));
        assertNull(PlaceWords.withMarker(sixteen, new PlaceWords.Spot("new", 0, 0, 0), err));
        assertEquals(16, PlaceWords.withMarker(sixteen, new PlaceWords.Spot("m3", 9, 9, 9), err).size(), "moving a marker is fine when full");
    }

    @Test
    void queueAcceptance() {
        assertNull(QueueRules.refusal("craft torch 16"));
        assertNull(QueueRules.refusal("mine strip iron 16 then deposit"));
        assertTrue(QueueRules.refusal("escort me").contains("never finishes"));
        assertTrue(QueueRules.refusal("follow").contains("never finishes"));
        assertTrue(QueueRules.refusal("defend").contains("never finishes"));
        assertTrue(QueueRules.refusal("guard base").contains("never finishes"));
        assertTrue(QueueRules.refusal("repeat forever farm").contains("never finishes"));
        assertTrue(QueueRules.refusal("deposit then follow").contains("follow"), "a chain with an endless step");
        assertTrue(QueueRules.refusal("status").contains("answers at once"));
        assertTrue(QueueRules.refusal("").startsWith("usage"));
        assertFalse(QueueRules.mayStart(false, true), "behind an escort it waits until dismiss");
        assertFalse(QueueRules.mayStart(true, false));
        assertTrue(QueueRules.mayStart(false, false));
        assertEquals("nothing running - queued: 1. craft torch 16, 2. deposit", QueueRules.show(null, List.of("craft torch 16", "deposit")));
    }

    @Test
    void stageAndStatus() {
        assertEquals("nothing", GameStage.of(Map.of("minecraft:dirt", 64)));
        assertEquals("wood", GameStage.of(Map.of("minecraft:wooden_pickaxe", 1)));
        assertEquals("stone", GameStage.of(Map.of("minecraft:stone_axe", 1, "minecraft:wooden_pickaxe", 1)));
        assertEquals("iron", GameStage.of(Map.of("minecraft:iron_sword", 1)));
        assertEquals("diamond", GameStage.of(Map.of("minecraft:netherite_pickaxe", 1)));
        assertEquals("nothing", GameStage.of(Map.of("minecraft:iron_pickaxe", 0)), "a count of 0 isn't had");
        assertEquals(" | deaths 2 in the last hour | stage stone", GameStage.statusPart(2, "stone"));
        assertEquals(" | deaths 0 in the last hour", GameStage.statusPart(0, null));
        assertEquals(new TreeSet<>(Kinds.KINDS), new TreeSet<>(List.of("logs", "wood", "ores", "stone", "food", "seeds", "animals")));
    }
}
