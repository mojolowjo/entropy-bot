package io.github.mojolowjo.entropybot.aa;

import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.aa.brain.Brain;
import io.github.mojolowjo.entropybot.aa.brain.NightWork;
import io.github.mojolowjo.entropybot.aa.brain.ToolWear;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Inputs;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Director 2026-10-19: idle as one desire (its options inside), tool wear as costs (ToolWear). */
class IdleToolTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());

    private void allMet() {
        bb.in.pickTier = 3;
        bb.in.pickaxes = 1;
        bb.in.pickPct = 100;
        bb.in.tableKnown = bb.in.furnaceKnown = bb.in.baseKnown = bb.in.tableInBag = true;
        bb.in.torches = 64;
        bb.in.coal = 32;
        bb.in.ironIngots = 40;
        bb.in.wood = 10;              // wood stock-up wanted
    }

    // ---- 1: idle as one desire ----

    @Test
    void idleIsOneDesireWithItsOptionsInside() {
        AaTree tree = AaTree.build(new AaScorer());
        long idle = tree.brain.desires().stream().filter(d -> d.id.startsWith("idle")).count();
        assertEquals(1, idle, "one idle desire in the main list");
        var d = tree.brain.desires().stream().filter(x -> x.id.equals(AaDesires.IDLE_ID)).findFirst().orElseThrow();
        assertTrue(d.node instanceof Brain b && b.desires().size() == 8, "the eight idle options under it");
        assertEquals(io.github.mojolowjo.entropybot.aa.brain.Desire.Exposure.ANY, d.exposure(), "no night exposure on the parent");
        assertTrue(((Brain) d.node).desires().stream().anyMatch(o -> o.exposure() != io.github.mojolowjo.entropybot.aa.brain.Desire.Exposure.ANY),
                "the options keep their own exposure");
    }

    @Test
    void whyShowsTheBestOptionAndTheNext() {
        allMet();
        AaTree tree = AaTree.build(new AaScorer());
        port.resolveTo = "goal wood 64";
        tree.tick(bb);
        String line = tree.brain.whyLines(30).stream().filter(l -> l.startsWith("idle ")).findFirst().orElseThrow();
        assertTrue(line.contains("[idle: stock stone 25 (next stock wood 21, wait 3)]"), line);
        assertTrue(line.contains("(next ") && line.contains("wait 3"), line);
        assertTrue(Math.round(new AaScorer().score(bb.in)[io.github.mojolowjo.entropybot.aa.core.TaskKind.STOCK_WOOD.ordinal()] * AaDesires.IDLE_SCALE) > 0);
    }

    @Test
    void theLiveTreeShowsIdleAsOneNodeWithItsOptions() {
        allMet();
        AaTree tree = AaTree.build(new AaScorer());
        port.resolveTo = "goal wood 64";
        tree.tick(bb);
        JsonObject o = AaLive.json(tree, bb, true, 5);
        JsonObject idle = null;
        for (var e : o.getAsJsonArray("desires")) if (e.getAsJsonObject().get("id").getAsString().equals("idle")) idle = e.getAsJsonObject();
        assertNotNull(idle);
        assertEquals(8, idle.getAsJsonObject("options").getAsJsonArray("rows").size());
        assertTrue(o.toString().contains("\"id\":\"idle.options\""), "the option set is one node in the tree");
    }

    @Test
    void aFailedIdleOptionGivesTheNextItsTurnInsideTheDesire() {
        allMet();
        AaTree tree = AaTree.build(new AaScorer());
        port.resolveTo = null;         // no planner chain: the stock-ups fail before a task
        for (int i = 0; i < 12; i++) tree.tick(bb);
        assertTrue(port.started.stream().anyMatch(t -> t.chain().startsWith("wait ")), port.log.toString());
    }

    // ---- 2: tool wear as costs ----

    @Test
    void digTimesFollowTheGamesBreakRule() {
        assertEquals(7.5, ToolWear.hand(), 1e-9, "stone by hand: 150 ticks");
        assertEquals(0.6, ToolWear.withTool(4), 1e-9, "stone pickaxe: 12 ticks");
        assertEquals(0.4, ToolWear.withTool(6), 1e-9, "iron pickaxe: 8 ticks");
    }

    @Test
    void aWornPickaxeIsUsedWhenNothingCanReplaceIt() {
        // aa388: stone pickaxe at 29/131, 114 blocks, no materials: never by hand
        var k = new ToolWear.Kit(4, 29, ToolWear.INF);
        List<ToolWear.Priced> p = ToolWear.price(114, k);
        assertEquals(ToolWear.Way.USE, p.get(0).way(), ToolWear.text(114, k));
        assertTrue(p.get(0).seconds() <= 114 * ToolWear.hand() + 1e-9);
        assertTrue(p.get(0).terms().contains("wear"), p.get(0).terms());
    }

    @Test
    void aFreshPickaxeIsWorthItsDigTimeWithNothingToCraft() {
        var k = new ToolWear.Kit(4, 131, ToolWear.INF);
        assertTrue(ToolWear.best(60, k).seconds() < 60 * ToolWear.hand() / 3, ToolWear.text(60, k));
    }

    @Test
    void aNewPickaxeFirstWinsWhenTheOldOneWouldBreak() {
        var k = new ToolWear.Kit(4, 29, ToolWear.CRAFT_S);
        assertEquals(ToolWear.Way.CRAFT, ToolWear.best(114, k).way(), ToolWear.text(114, k));
        assertEquals(ToolWear.Way.USE, ToolWear.best(10, k).way(), "a short dig: use it");
    }

    @Test
    void handOnlyWinsWithNoPickaxeAndNothingToMakeOne() {
        assertEquals(ToolWear.Way.HAND, ToolWear.best(60, new ToolWear.Kit(0, 0, ToolWear.INF)).way());
        assertEquals(ToolWear.Way.CRAFT, ToolWear.best(60, new ToolWear.Kit(0, 0, ToolWear.CRAFT_S)).way());
    }

    @Test
    void theKitComesFromTheInputs() {
        Inputs in = new Inputs();
        in.pickaxes = 1;
        in.pickSpeed = 4;
        in.pickLeft = 29;
        in.cobble = 3;
        in.bag = Map.of("stick", 2);
        in.tableInBag = true;
        assertEquals(ToolWear.CRAFT_S, ToolWear.kit(in).craftS());
        in.cobble = 2;
        assertEquals(ToolWear.INF, ToolWear.kit(in).craftS(), "3 cobblestone needed");
    }

    @Test
    void theClimbPricesItsWays() {
        var f = new RungOptions.SurfaceFacts(23, new int[]{0, 40, 0}, false, 0, false, true, null, 0, null, -1, false,
                new ToolWear.Kit(4, 29, ToolWear.CRAFT_S));
        var c = new RungOptions.Surface().choose(f, new Options.OptionMemory(), 0, null, 0, null, null);
        assertTrue(c.best().command().startsWith("craft stone_pickaxe 1 then climb"), c.why());
        assertTrue(c.why().contains("climb by hand"), "hand is shown with its price");
        var none = new RungOptions.SurfaceFacts(23, new int[]{0, 40, 0}, false, 0, false, false, null, 0, null, -1, false,
                new ToolWear.Kit(0, 0, ToolWear.INF));
        assertNull(new RungOptions.Surface().choose(none, new Options.OptionMemory(), 0, null, 0, null, null).best());
    }

    @Test
    void theDigToOreCarriesTheWearTerm() {
        var ore = IronOptionsTest.buried(1, 50, 1, 5, 10, false);
        var f = new IronOptions.Facts(List.of(ore), 60, false, 0, true, -1, false, null, -1, false, false, 0, new ToolWear.Kit(4, 2, ToolWear.INF));
        var c = IronOptions.choose(f, new IronOptions.Memory(), 0);
        assertTrue(c.why().contains("tools 10 blocks: use"), c.why());
    }

    @Test
    void aWornPickaxeStillDigsTheNightStairs() {
        Inputs in = new Inputs();
        in.pickTier = 2;
        in.pickPct = 5;
        assertEquals(NightWork.Work.STAIRS, NightWork.pick(in), "no save-a-worn-tool rule");
    }
}
