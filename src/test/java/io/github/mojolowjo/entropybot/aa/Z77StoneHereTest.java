package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.LootBook;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 stone (run CF 16:55:08-16:55:38, owner's seed: under ground at 11 16 -19, the one stone spot the live lookup
 * named (6 16 -21) came back UNREACHABLE, the order held it, and the re-plan said "at band: iron_ingot ends in an explore
 * (no known source for #stone_tool_materials)" - the order stopped with stone all round the bot). The live lookup names the
 * nearest counted block only; the hold filtered that one out after, so the need had no source left. Now the lookup passes
 * over the held spots ({@link NeedChain.Lookup}, {@link Nearby#source(Nearby.Scan, int[], Set, java.util.function.Supplier,
 * java.util.function.Predicate)}) and the next nearest stone - the tunnel's own wall - is the source.
 * Hand numbers: the fake walk 1 m x 1.3 / 4.317 = 0.30 s; a block with the stone pickaxe, priced at the item's own block's
 * hardness as the chain does (cobblestone 2, NeedChain.breakS): 4 / 2 / 30 a tick -> 15 ticks = 0.75 s, x 3 = 2.25 s;
 * together 2.55 s ("about 2 s").
 */
class Z77StoneHereTest {
    static final int[] FEET = {11, 16, -19};
    static final String STONE = "minecraft:stone", COBBLE = "minecraft:cobblestone", TAG = "#minecraft:stone_tool_materials";
    static final double TOL = 0.1;

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    /** A 1x2 tunnel along x at y 16-17, z -19, from x 0 to the bot at x 11: stone floor, ceiling, both walls and the end. */
    static NearbyExposureTest.World tunnel() {
        NearbyExposureTest.World t = new NearbyExposureTest.World();
        for (int x = 0; x <= 12; x++) {
            t.put(x, 15, -19, STONE).put(x, 18, -19, STONE);
            for (int y = 16; y <= 17; y++) {
                t.put(x, y, -20, STONE).put(x, y, -18, STONE);
                if (x <= 11) t.air(x, y, -19); else t.put(x, y, -19, STONE);
            }
        }
        return t;
    }

    static LootBook book() {
        return new LootBook(List.of(new LootBook.Source(LootBook.Kind.BLOCK, STONE, COBBLE, 1, 1, false, null)), "test");
    }

    /** The live lookup's shape (AaRuntime.nearestSource): the nearest counted stone of the scan, the order's held spots passed over. */
    static NeedChain.Lookup lookup(Nearby.Scan scan) {
        return (word, skip) -> {
            boolean tag = word.contains("stone_tool_materials");
            if (!tag && !word.equals(COBBLE)) return List.of();
            Function<Spot, Spot> giving = sp -> tag ? sp.giving(COBBLE) : sp;
            Nearby.Source s = Nearby.source(scan, FEET, Set.of(STONE), () -> null, skip == null ? null : sp -> skip.test(word, giving.apply(sp)));
            return s.spot() == null ? List.of() : List.of(giving.apply(s.spot()));
        };
    }

    static World world(Function<String, List<Spot>> seen) {
        ScenariosTest.world("wooden", "stone");
        return FakePaths.paths(new World(Map.of("minecraft:stone_pickaxe", 1), false, false, seen, null, null, null, 0)).withMobs(x -> List.of(), book());
    }

    static World held(World w, String word, Spot sp) {
        return w.withoutSources(Map.of(NeedChain.spotKey(word, sp), "unreachable (Baritone: no path: Baritone's search made no progress (no progress))"));
    }

    static void digsHere(Chain c, Spot notAt) {
        assertTrue(c.ok() && !c.explore(), c.why());
        assertFalse(c.why().contains("no known source"), c.why());
        assertEquals(1, c.steps().size(), c.steps().toString());
        Step.Collect k = assertInstanceOf(Step.Collect.class, c.steps().get(0), c.steps().toString());
        assertEquals("cobblestone", k.item());
        assertEquals(3, k.n());
        int[] at = k.at();
        assertNotNull(at, "the priced spot is named");
        assertEquals(1.0, Math.sqrt(Math.pow(at[0] - FEET[0], 2) + Math.pow(at[1] - FEET[1], 2) + Math.pow(at[2] - FEET[2], 2)), 1e-9, "a wall of the tunnel, 1 m: " + k.spot());
        if (notAt != null) assertNotEquals(notAt.x() + " " + notAt.y() + " " + notAt.z(), k.spot(), "never the held spot");
        assertEquals(2.55, c.seconds(), TOL, c.why());
        assertTrue(c.why().contains("dig cobblestone seen 1 m"), c.why());
    }

    /** Nearby: the held nearest stone is passed over and said; the next nearest wall counts. */
    @Test
    void theLookupPassesOverAHeldSpotToTheNextWall() {
        ScenariosTest.world("wooden", "stone");
        NearbyExposureTest.World t = tunnel();
        Spot first = Nearby.source(t, FEET, Set.of(STONE), () -> null).spot();
        assertNotNull(first);
        assertEquals(1.0, first.dist(), 1e-9);
        Nearby.Source s = Nearby.source(t, FEET, Set.of(STONE), () -> null, sp -> sp.x() == first.x() && sp.y() == first.y() && sp.z() == first.z());
        assertNotNull(s.spot(), s.line());
        assertNotEquals(List.of(first.x(), first.y(), first.z()), List.of(s.spot().x(), s.spot().y(), s.spot().z()));
        assertEquals(1.0, s.spot().dist(), 1e-9);
        assertTrue(s.line().contains("1 stone held by the order (the next one counts), not counted"), s.line());
    }

    /**
     * Run CF: the tag need #stone_tool_materials 3 in the tunnel, the lookup's nearest wall held UNREACHABLE for the order: dig
     * the next wall here, 2.55 s, no explore (was "no known source for #stone_tool_materials").
     */
    @Test
    void cf_tagNeedInATunnelWithTheNearestWallHeldDigsTheNextWall() {
        NearbyExposureTest.World t = tunnel();
        World w = world(lookup(t));
        Spot first = w.seenAll().apply(TAG).get(0);
        digsHere(NeedChain.resolve(TAG, 3, held(w, TAG, first)), first);
    }

    /** The plain need, cobblestone 3, the same tunnel and hold: the same dig here. */
    @Test
    void cf_cobblestoneInATunnelWithTheNearestWallHeldDigsTheNextWall() {
        NearbyExposureTest.World t = tunnel();
        World w = world(lookup(t));
        Spot first = w.seenAll().apply(COBBLE).get(0);
        digsHere(NeedChain.resolve("cobblestone", 3, held(w, "cobblestone", first)), first);
    }

    /** No hold: the nearest wall, the same 2.55 s (as before). */
    @Test
    void control_noHoldDigsTheNearestWall() {
        World w = world(lookup(tunnel()));
        digsHere(NeedChain.resolve(TAG, 3, w), null);
    }

    /**
     * The old shape (a lookup that names the nearest only and cannot pass over a hold, filtered after): the held nearest
     * leaves no source - run CF's "no known source". A plain function keeps that filter-after (the tests' lists of every
     * known spot), so this is what the live lookup did before.
     */
    @Test
    void oldShape_aNearestOnlyLookupFilteredAfterHasNoSourceLeft() {
        NeedChain.Lookup live = lookup(tunnel());
        Function<String, List<Spot>> nearestOnly = word -> live.apply(word, null);
        World w = world(nearestOnly);
        Spot first = w.seenAll().apply(TAG).get(0);
        Chain c = NeedChain.resolve(TAG, 3, held(w, TAG, first));
        assertTrue(c.explore(), c.why());
        assertTrue(c.why().contains("stone_tool_materials"), c.why());
    }

    /** Control: in open air on a plain, no stone seen: as now - the explore for the tag (no source named). */
    @Test
    void control_openAirNoStoneSeenExploresAsNow() {
        World w = world(lookup(new NearbyExposureTest.World()));
        Chain c = NeedChain.resolve(TAG, 3, w);
        assertTrue(c.ok() && c.explore(), c.why());
        assertTrue(c.why().contains("stone_tool_materials"), c.why());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Collect), c.steps().toString());
    }
}
