package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Thing;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.y1: there is no tools need. A pickaxe is made only where a chain digs a block that asks for one (the lowest tier that does the
 * job), by the torches' chain row, the owner's ore goal or the owner's group goal ("stone tools"). Pure; no game.
 */
class ToolsChainsTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());

    @AfterEach
    void clear() { TestData.clear(); Tuning.reset(); }

    static final Thing.Catalog CATALOG = new Thing.Catalog(List.of(), List.of("minecraft:iron_ore"), Map.of(), Map.of("minecraft:iron_ore", 2), null);

    void world(World w) { bb.fact(NeedChain.WORLD_FACT, (Supplier<World>) () -> w); }

    @Test
    void noToolsNeedNoToolsNumbersNoOnlyTools() {
        assertTrue(AaDesires.needs().stream().noneMatch(n -> n.id.equals("needs.tools")), "no tools need");
        assertTrue(AaDesires.needs().stream().noneMatch(n -> n.id.contains("tools")));
        assertNull(AaManual.rungId("tools"));
        assertNull(AaManual.rungId("needs.tools"));
        assertNull(AaManual.rungId("pickaxe"));
        for (String gone : List.of("tools.base", "tools.blockedW0", "tools.blockedW1", "tools.blockedOf", "tools.keepBase", "tools.wearSpan",
                "tools.stoneTier", "tools.wearPct", "tools.spares", "swordValue", "blockedTop"))
            assertFalse(Tuning.names().contains(gone), gone);
    }

    @Test
    void aGroupWantIsOneJobTheChainOfAllItsMembers() {
        ScenariosTest.toolWorld();
        var r = Thing.resolve("stone tools", CATALOG);
        assertNull(r.err(), r.err());
        assertTrue(r.group() && !r.ore(), r.text());
        assertEquals(5, r.ids().size(), r.text());
        world(ScenariosTest.w(Map.of("minecraft:cobblestone", 12, "minecraft:stick", 8), true, false, Map.of()));
        Goals.Goal g = Goals.add(bb, r, 0, 0, clock.now());
        bb.in.bag = Map.of("minecraft:cobblestone", 12, "minecraft:stick", 8);
        List<Step> steps = Goals.steps(bb, g);
        assertEquals(List.of(new Step.Craft("stone_pickaxe", 1), new Step.Craft("stone_axe", 1), new Step.Craft("stone_hoe", 1), new Step.Craft("stone_sword", 1)), steps);
        // done when every member is had: the fewest of them
        bb.in.bag = Map.of("stone_pickaxe", 1, "stone_axe", 1, "stone_hoe", 1, "stone_sword", 1);
        assertEquals(0, Goals.have(bb.in.bag, g));
        bb.in.bag = Map.of("stone_pickaxe", 1, "stone_axe", 1, "stone_hoe", 1, "stone_sword", 1, "stone_shovel", 1);
        assertEquals(1, Goals.have(bb.in.bag, g));
        assertNull(Goals.steps(bb, g), "all had: nothing left");
        assertFalse(Thing.resolve("stone_pickaxe", new Thing.Catalog(List.of("minecraft:stone_pickaxe"), List.of(), Map.of(), Map.of(), null)).group());
    }

    @Test
    void anOreGoalThatNeedsAPickaxeMakesTheLowestOneItsBlockAsksFor() {
        ScenariosTest.toolWorld();
        var r = Thing.resolve("iron_ore", CATALOG);
        assertEquals(2, r.tier(), r.text());
        world(ScenariosTest.w(Map.of(), false, false, ScenariosTest.s4Seen()));
        Goals.Goal g = Goals.add(bb, r, 0, 0, clock.now());
        bb.in.pickTier = 0;
        List<Step> steps = Goals.steps(bb, g);
        assertNotNull(steps, "the dig makes its pickaxe");
        assertTrue(steps.contains(new Step.Craft("wooden_pickaxe", 1)), steps.toString());
        assertEquals(new Step.Craft("stone_pickaxe", 1), steps.get(steps.size() - 1), "iron ore asks for stone: " + steps);
        assertTrue(steps.stream().noneMatch(s -> s instanceof Step.Craft k && (k.item().startsWith("iron") || k.item().startsWith("golden"))), steps.toString());
        assertNull(Goals.prereq(bb, g), "no tools need to wait for");
        bb.in.pickTier = 2;
        assertTrue(Goals.steps(bb, g).get(0) instanceof Step.Mine || Goals.steps(bb, g).get(0) instanceof Step.Explore || Goals.steps(bb, g).get(0) instanceof Step.MarkMine, "with the pickaxe: the mine steps");
    }

    @Test
    void theTorchesGetAChainRowThatPullsTheWoodenPickaxeIn() {
        ScenariosTest.toolWorld();
        Spot coal = new Spot(0, 40, 20, 20, "minecraft:coal_ore", 4);
        world(ScenariosTest.w(Map.of(), false, false, Map.of("log", List.of(ScenariosTest.tree(10, 6)), "fuel", List.of(coal))));
        bb.in.coal = 0;
        Options.Choice c = new CoalOptions.Torches().choose(bb, null, 0, null);
        Options.Option row = c.ranked().stream().filter(o -> o.id().equals("chain torch")).findFirst().orElseThrow(() -> new AssertionError(c.why()));
        assertTrue(row.why().contains("dig fuel seen 20 m with wooden_pickaxe"), row.why());
        assertFalse(row.why().contains("stone_pickaxe") && !row.why().contains("dropped"), row.why());
        assertTrue(row.steps().contains(new Step.Craft("wooden_pickaxe", 1)), row.steps().toString());
    }

    @Test
    void aChainThatWouldExploreIsLeftToThePlannerRowAndSaidInTheDroppedList() {
        ScenariosTest.toolWorld();
        world(ScenariosTest.w(Map.of(), false, false, Map.of()));
        bb.in.coal = 0;
        Options.Choice c = new CoalOptions.Torches().choose(bb, null, 0, null);
        assertTrue(c.ranked().stream().noneMatch(o -> o.id().equals("chain torch")), c.why());
        assertTrue(c.dropped().stream().anyMatch(d -> d.startsWith("chain torch: something it needs is not seen yet")), c.dropped().toString());
    }
}
