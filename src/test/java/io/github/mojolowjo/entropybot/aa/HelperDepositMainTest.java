package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.guard.BreakPolicy;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Verdict;
import io.github.mojolowjo.entropybot.guard.Policy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Run HQ (2026-11-02, the helper test: "aa want raw_iron 64", then "aa want deposit" into the chest the owner set with
 * "aa deposit 6 68 10" on the surface): the bot held 65 raw iron 190 blocks under ground and the deposit failed "no chest
 * known, and no chest or 8 planks in the bag" - the lookup refused the owner's main as "wild station (not placed by me,
 * outside my areas)" and the deposit dropped that reason. The owner's mark is his own say on the block.
 */
class HelperDepositMainTest {
    static final String OW = "minecraft:overworld";
    static final Set<String> CHEST = Set.of("minecraft:chest");

    /** (1) The station rule: an owner-marked main outside every area is usable; keep-out still refuses it. */
    @Test
    void theOwnersMainIsUsableOutsideHisAreas_keepOutStillRefuses() {
        Policy p = Policy.parse("{\"areas\":[{\"name\":\"shrine\",\"type\":\"keep-out\",\"x1\":300,\"z1\":300,\"x2\":310,\"z2\":310}]}");
        Verdict wild = BreakPolicy.stationUse(p, OW, 6, 68, 10, false);
        assertFalse(wild.allowed(), "as before: not mine, outside my areas");
        Verdict main = BreakPolicy.stationUse(p, OW, 6, 68, 10, false, true);
        assertTrue(main.allowed(), main.text());
        assertTrue(main.text().contains("the owner's main station (aa deposit x y z / aa station main)"), main.text());
        assertFalse(BreakPolicy.stationUse(p, OW, 305, 64, 305, false, true).allowed(), "a main inside a keep-out box is still refused");
        assertEquals(BreakPolicy.stationUse(p, OW, 6, 68, 10, false).reason(), BreakPolicy.stationUse(p, OW, 6, 68, 10, false, false).reason(), "the 6-arg form is the old rule");
    }

    /** (1) The lookup: the main chest 190 blocks away, no area of the owner's, nothing placed by me - listed, nothing skipped. */
    @Test
    void theLookupListsTheOwnersMainChestFarAway() {
        Policy p = Policy.parse("{\"areas\":[]}");
        MainStations ms = new MainStations();
        ms.mark(new MainStations.Main(MainStations.CONTAINER, 6, 68, 10, "minecraft:chest"));
        Map<String, String> world = Map.of("6 68 10", "minecraft:chest");
        var s = new Nearby.Scan() {
            @Override public List<int[]> logs(int radius) { return List.of(); }
            @Override public List<int[]> find(Set<String> blocks, int radius) { return List.of(); }   // Baritone's scan: nothing in reach under ground
            @Override public boolean isLog(String block) { return false; }
            @Override public boolean fairOk(int x, int y, int z) { return true; }
            @Override public boolean leavesNear(int x, int y, int z, int reach) { return false; }
            @Override public boolean allowed(int x, int y, int z) { return true; }
            @Override public String blockAt(int x, int y, int z) { return world.get(x + " " + y + " " + z); }   // null: the chunk is not loaded
            @Override public Boolean open(int x, int y, int z) { return true; }
            @Override public List<Nearby.Mine> own() { return List.of(); }
            @Override public Policy stationPolicy() { return p; }
            @Override public String stationDim() { return OW; }
            @Override public MainStations mains() { return ms; }
        };
        Nearby.Lookup l = Nearby.lookup(s, new int[]{-182, -20, 37}, CHEST);
        assertEquals(1, l.spots().size(), "the main is known wherever it is: " + l.skipped());
        assertEquals(6, l.spots().get(0).x());
        assertTrue(l.skipped().isEmpty(), l.skipped().toString());
        assertEquals(MainStations.bonusS(), l.spots().get(0).bonusS(), 1e-9, "its main bonus");
        // the fact the deposit reads
        var f = Nearby.stationFact(s, new int[]{-182, -20, 37}, new io.github.mojolowjo.entropybot.aa.StationBlocks.Answer.Of(CHEST));
        assertInstanceOf(io.github.mojolowjo.entropybot.aa.StationFact.Known.class, f);
    }

    /** (2) Rule 7: the deposit says the lookup's refusal with its failure; the mark says when the lookup does not list the main. */
    @Test
    void theDepositAndTheMarkSayTheRefusal() throws IOException {
        String leg = Files.readString(Path.of("src/main/java/io/github/mojolowjo/entropybot/commands/DepositLeg.java"));
        assertTrue(leg.contains("case FAIL -> Car.Status.failed(\"deposit: \" + ch.why() + refused);"), "the refusal rides with the failure");
        assertTrue(leg.contains("!io.github.mojolowjo.entropybot.aa.Nearby.NONE_KNOWN.equals(n.why())"), "only a reason beyond 'none known'");
        String live = Files.readString(Path.of("src/main/java/io/github/mojolowjo/entropybot/commands/MainStationsLive.java"));
        assertTrue(live.contains("\" - BUT the deposit's lookup does not list it: \""), "said at the mark");
        String nearby = Files.readString(Path.of("src/main/java/io/github/mojolowjo/entropybot/aa/Nearby.java"));
        assertTrue(nearby.contains("if (!usable(s, p, false, true, first, skipped)) continue;"), "the mains loop passes ownerMain");
        assertEquals(1, count(nearby, "false, true, first, skipped)"), "only the mains loop does");
    }

    private static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + 1)) n++;
        return n;
    }
}
