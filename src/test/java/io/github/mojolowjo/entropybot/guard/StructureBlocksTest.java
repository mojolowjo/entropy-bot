package io.github.mojolowjo.entropybot.guard;

import io.github.mojolowjo.entropybot.aa.Nearby;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Action;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Ask;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.BlockFacts;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Snapshot;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Structure;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Verdict;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Where;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** STRUCTURE_BLOCKS.md (owner 2026-10-23, RULES 16): scenarios S27-S30 and the structure fact's three states. */
class StructureBlocksTest {
    static final String OW = "minecraft:overworld";
    /** camp (work, tagged break-structures) holds house (base, untagged); yard (work, use-stations) far off; shrine no-break. */
    static final String POLICY = "{\"areas\":["
            + "{\"name\":\"camp\",\"x1\":0,\"z1\":0,\"x2\":40,\"z2\":40,\"tags\":[\"break-structures\"]},"
            + "{\"name\":\"house\",\"type\":\"base\",\"x1\":10,\"z1\":10,\"x2\":15,\"z2\":15},"
            + "{\"name\":\"yard\",\"x1\":100,\"z1\":0,\"x2\":120,\"z2\":20,\"tags\":[\"use-stations\"]}],"
            + "\"protect\":[{\"name\":\"shrine\",\"x1\":300,\"z1\":300,\"x2\":310,\"z2\":310,\"tags\":[\"break-structures\"]}]}";
    static final Snapshot SP = Snapshot.of(Policy.parse(POLICY), false);
    /** A plank wall block: a structure block (crafted, no world-gen placement). */
    static final BlockFacts WALL = BlockFacts.STONE.withStructure(Structure.YES);

    static Verdict brk(int x, int y, int z, BlockFacts f) {
        return BreakPolicy.judge(SP, new Ask(Action.BREAK, Intent.TRAVEL, OW, x, y, z, f, Where.DRY));
    }

    @Test
    void s27aPlankWallOutsideEveryAreaIsRefusedSoThePathGoesAround() {
        Verdict v = brk(500, 64, 500, WALL);
        assertFalse(v.allowed());
        assertEquals("structure block outside my areas", v.reason());
        assertTrue(Double.isInfinite(v.multiplier()), "the path cost: never through, the walk around is priced longer");
        assertTrue(brk(500, 64, 500, BlockFacts.STONE).allowed(), "plain stone in the same spot is open");
    }

    @Test
    void s28theSameWallPlacedByMeBreaksAtItsPrice() {
        Verdict v = brk(500, 64, 500, WALL.withPlacedByMe(true));
        assertTrue(v.allowed(), v.text());
        assertEquals(brk(500, 64, 500, BlockFacts.STONE).multiplier(), v.multiplier(), 1e-9, "priced like any block");
    }

    @Test
    void s29theWallInsideAnAreaTaggedBreakStructuresBreaks() {
        assertTrue(brk(5, 64, 5, WALL).allowed(), "camp is tagged break-structures");
        Verdict house = brk(12, 64, 12, WALL);
        assertFalse(house.allowed(), "the innermost area (house, untagged) wins over camp's tag");
        assertEquals("structure block in house (no break-structures tag)", house.reason());
        Verdict shrine = brk(305, 64, 305, WALL);
        assertFalse(shrine.allowed(), "no-break stays absolute, whatever its tags");
        assertTrue(shrine.reason().contains("no-break"), shrine.reason());
    }

    @Test
    void s30aWildFurnaceIsNoOptionMyFurnaceInAUseStationsAreaRanksFirst() {
        Policy p = Policy.parse(POLICY);
        Verdict wild = BreakPolicy.stationUse(p, OW, 3, 64, -50, false);
        assertFalse(wild.allowed());
        assertEquals("wild station (not placed by me, outside my areas)", wild.reason());
        assertTrue(BreakPolicy.stationPreferred(BreakPolicy.stationUse(p, OW, 110, 64, 10, true)));
        Set<String> furnace = Set.of("minecraft:furnace");
        Map<String, String> world = Map.of("3 64 -50", "minecraft:furnace", "110 64 10", "minecraft:furnace", "20 64 20", "minecraft:furnace");
        var s = new Nearby.Scan() {
            @Override public List<int[]> logs(int radius) { return List.of(); }
            @Override public List<int[]> find(Set<String> blocks, int radius) { return List.of(new int[]{3, 64, -50}, new int[]{20, 64, 20}); }
            @Override public boolean isLog(String block) { return false; }
            @Override public boolean fairOk(int x, int y, int z) { return true; }
            @Override public boolean leavesNear(int x, int y, int z, int reach) { return false; }
            @Override public boolean allowed(int x, int y, int z) { return true; }
            @Override public String blockAt(int x, int y, int z) { return world.get(x + " " + y + " " + z); }
            @Override public List<Nearby.Mine> own() { return List.of(new Nearby.Mine(110, 64, 10, "minecraft:furnace")); }
            @Override public Verdict stationUse(int x, int y, int z, boolean mine) { return BreakPolicy.stationUse(p, OW, x, y, z, mine); }
        };
        List<String> skipped = new java.util.ArrayList<>();
        List<Spot> l = Nearby.stations(s, new int[]{0, 64, 0}, furnace, skipped);
        assertEquals(List.of(110, 20), l.stream().map(Spot::x).toList(), "mine in yard first, then the one in camp; the wild one is gone");
        assertEquals(1, skipped.size());
        assertTrue(skipped.get(0).contains("3 64 -50") && skipped.get(0).contains("wild station"), skipped.get(0));
    }

    @Test
    void theFactHasThreeStatesAndOresAndLogsAreNeverStructures() {
        assertEquals(Structure.NOT_LOADED, BreakPolicy.structureOf(false, true, false, true, true, true, Boolean.FALSE));
        assertEquals(Structure.NO, BreakPolicy.structureOf(true, false, true, true, true, true, Boolean.FALSE), "an ore or a log is never a structure");
        assertEquals(Structure.YES, BreakPolicy.structureOf(true, true, false, false, false, false, Boolean.TRUE), "a tagged block");
        assertEquals(Structure.YES, BreakPolicy.structureOf(true, false, false, true, false, false, Boolean.TRUE), "a block entity");
        assertEquals(Structure.YES, BreakPolicy.structureOf(true, false, false, false, true, false, Boolean.TRUE), "a station");
        assertEquals(Structure.YES, BreakPolicy.structureOf(true, false, false, false, false, true, Boolean.FALSE), "crafted, never placed by world gen");
        assertEquals(Structure.NO, BreakPolicy.structureOf(true, false, false, false, false, true, Boolean.TRUE), "crafted but world gen places it (diorite)");
        assertEquals(Structure.YES, BreakPolicy.structureOf(true, false, false, false, false, true, null), "world gen unknown: fail closed");
        assertEquals(Structure.NO, BreakPolicy.structureOf(true, false, false, false, false, false, null), "not crafted: plain terrain");
        Verdict v = brk(500, 64, 500, BlockFacts.STONE.withStructure(Structure.NOT_LOADED));
        assertFalse(v.allowed());
        assertEquals("not loaded yet", v.reason());
    }

    @Test
    void areaTagsParseSaveAndRefuseUnknownWords() {
        Policy p = Policy.parse(POLICY);
        assertEquals(List.of("break-structures"), p.innermostAreaAt(OW, 5, 64, 5).tags);
        assertEquals(List.of(), p.innermostAreaAt(OW, 12, 64, 12).tags, "no tags in the file: an empty list");
        assertTrue(p.areas.get(0).toJson().toString().contains("\"tags\":[\"break-structures\"]"));
        assertNull(AreaTags.unknown(List.of("use-stations", "BREAK-STRUCTURES")));
        String bad = AreaTags.unknown(List.of("burn"));
        assertTrue(bad.contains("burn") && bad.contains("break-structures") && bad.contains("use-stations"), bad);
    }
}
