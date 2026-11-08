package io.github.mojolowjo.entropybot.aa.trail;

import io.github.mojolowjo.entropybot.aa.PathPrice;
import io.github.mojolowjo.entropybot.aa.trail.Trail.Cell;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** D4 the trail home: recording, the return price (walked seconds + climb work), the pick, a break, the file round trip. */
class D4TrailTest {
    static final int JUMP = 8;
    static final Trail.Climb CLIMB = new Trail.Climb(3, 5);     // a pillar level 3 s, a dug stair level 5 s: 3 s a level

    /** Out at 0 64 0 (tick 0), walk to 5 64 0 (tick 100), dig down two (ticks 120, 140), walk to 9 62 0 (tick 220). */
    static Trail down() {
        Trail t = new Trail();
        t.loadedEmpty();
        t.offer(new Cell(0, 64, 0), 0, true, JUMP);
        t.offer(new Cell(1, 64, 0), 20, false, JUMP);
        t.offer(new Cell(5, 64, 0), 100, false, JUMP);
        t.offer(new Cell(5, 63, 0), 120, false, JUMP);
        t.offer(new Cell(5, 62, 0), 140, false, JUMP);
        t.offer(new Cell(9, 62, 0), 220, false, JUMP);
        return t;
    }

    @Test void threeSegmentsPriceAsTheSum() {
        Trail t = down();
        assertEquals(Trail.State.OPEN, t.state());
        assertEquals(2, t.segs().size(), "a walk and a one-way, the tail is the third");
        Trail.Seg w = t.segs().get(0), d = t.segs().get(1);
        assertFalse(w.oneWay());
        assertEquals(5.0, w.walkedS(), 1e-9);
        assertEquals(new Cell(5, 64, 0), d.from());
        assertEquals(new Cell(5, 62, 0), d.to());
        assertEquals(2, d.drop());
        assertEquals(3, d.cells().size(), "a one-way segment keeps its own cells");
        Trail.Price p = t.price(CLIMB, 220);
        assertTrue(p.whole());
        // walked 5 + 2 + the tail 4 (ticks 140 -> 220), climb 2 levels x 3 s
        assertEquals(11.0, p.walkedS(), 1e-9);
        assertEquals(6.0, p.climbS(), 1e-9);
        assertEquals(17.0, p.seconds(), 1e-9);
    }

    @Test void aDropAddsClimbWork() {
        Trail flat = new Trail();
        flat.loadedEmpty();
        flat.offer(new Cell(0, 64, 0), 0, true, JUMP);
        flat.offer(new Cell(1, 64, 0), 20, false, JUMP);
        flat.offer(new Cell(5, 64, 0), 100, false, JUMP);
        Trail.Price a = flat.price(CLIMB, 100);
        assertEquals(0.0, a.climbS(), 1e-9);
        flat.offer(new Cell(5, 60, 0), 110, false, JUMP);       // a fall of 4
        Trail.Price b = flat.price(CLIMB, 110);
        assertEquals(4, b.levels());
        assertEquals(12.0, b.climbS(), 1e-9);
        assertEquals(a.seconds() + 0.5 + 12.0, b.seconds(), 1e-9);
        assertEquals(1.0, new Trail.Climb(Double.NaN, 1).levelS(), 1e-9, "the stair price alone when no pillar price");
        assertFalse(flat.price(new Trail.Climb(Double.NaN, Double.NaN), 110).whole(), "climb work not known: no price (rule 18)");
    }

    @Test void theTrailBeatsAnEstimateAndLosesToACheaperPricedPath() {
        Trail.Price p = down().price(CLIMB, 220);
        assertEquals(Trail.Pick.TRAIL, Trail.pick(PathPrice.Kind.ESTIMATED, 9, p).pick(), "an estimate is not a price");
        assertEquals(Trail.Pick.TRAIL, Trail.pick(PathPrice.Kind.UNREACHABLE, Double.NaN, p).pick());
        assertEquals(Trail.Pick.DIRECT, Trail.pick(PathPrice.Kind.PRICED, 10, p).pick());
        assertEquals(Trail.Pick.TRAIL, Trail.pick(PathPrice.Kind.PRICED, 100, p).pick());
        assertEquals(Trail.Pick.DIRECT, Trail.pick(PathPrice.Kind.COMPUTING, Double.NaN, p).pick(), "rule 15: waited for");
        assertEquals(Trail.Pick.DIRECT, Trail.pick(PathPrice.Kind.UNREACHABLE, Double.NaN, null).pick());
    }

    @Test void aBrokenSegmentIsAFactAndTheTrailIsNoLongerPicked() {
        Trail t = down();
        List<Trail.Goal> way = t.way(1);
        assertEquals(new Cell(5, 62, 0), way.get(0).at(), "the tail first");
        assertEquals(new Cell(5, 63, 0), way.get(1).at(), "a shaft is climbed level by level");
        assertEquals(new Cell(5, 64, 0), way.get(2).at());
        assertEquals(new Cell(0, 64, 0), way.get(3).at());
        String line = t.breakAt(way.get(1), "no path");
        assertTrue(line.contains("5 62 0 -> 5 64 0") && line.contains("no path"), line);
        Trail.Price p = t.price(CLIMB, 220);
        assertFalse(p.whole());
        assertTrue(p.line().contains("no path"));
        assertEquals(Trail.Pick.DIRECT, Trail.pick(PathPrice.Kind.UNREACHABLE, Double.NaN, p).pick());
        assertEquals(1, t.breaks);
    }

    @Test void aOneWaySegmentIsWalkedAsPriced() {
        Trail t = down();
        List<Trail.Goal> way = t.way(1);
        assertFalse(way.get(0).up(), "the tail is a plain walk");
        assertTrue(way.get(1).up() && way.get(2).up(), "the shaft's levels and its top are the climb");
        assertFalse(way.get(3).up(), "the walked segment's start is a plain walk");
        assertEquals(2, t.levels());

        // CLIMB: a pillar 3 s beats a stair 5 s
        assertEquals(Trail.ClimbHow.PILLAR, CLIMB.how(t.levels()));
        Trail.Leg pillar = Trail.leg(way.get(2), CLIMB.how(2));
        assertEquals(Boolean.TRUE, pillar.place(), "a pillar places");
        assertEquals(Boolean.FALSE, pillar.breaks(), "a pillar breaks nothing");
        assertTrue(pillar.exact(), "GoalBlock on the top");

        Trail.Climb stairs = new Trail.Climb(3, 5, 1);                 // one block for two levels: the stairs
        assertEquals(Trail.ClimbHow.STAIRS, stairs.how(2));
        Trail.Leg dug = Trail.leg(way.get(2), stairs.how(2));
        assertEquals(Boolean.TRUE, dug.breaks(), "stairs break");
        assertNull(dug.place(), "stairs leave placing as the walk leaves it");
        assertTrue(dug.exact());

        assertSame(Trail.Leg.WALK, Trail.leg(way.get(0), Trail.ClimbHow.PILLAR), "a plain segment stays a plain walk");
        assertSame(Trail.Leg.WALK, Trail.leg(way.get(3), Trail.ClimbHow.STAIRS));
        assertSame(Trail.Leg.WALK, Trail.leg(way.get(1), null), "no climb price: a plain walk");
        assertNull(new Trail.Climb(Double.NaN, Double.NaN).how(2));
    }

    @Test void outClearsAndALoopCuts() {
        Trail t = down();
        t.offer(new Cell(9, 62, 1), 230, false, JUMP);
        t.offer(new Cell(5, 62, 1), 300, false, JUMP);          // beside the one-way's foot, not a segment start: kept
        assertEquals(2, t.segs().size());
        t.offer(new Cell(5, 64, 1), 400, false, JUMP);          // beside the shaft's top (segment 1's start): cut back to it
        assertEquals(1, t.segs().size());
        assertNotNull(t.offer(new Cell(5, 70, 1), 401, true, JUMP));
        assertEquals(Trail.State.CLOSED, t.state());
        assertTrue(t.segs().isEmpty());
        assertNull(t.price(CLIMB, 402));
    }

    // ---- pass two ---------------------------------------------------------------------------------------------------------

    @Test void pillarLevelsNeedThrowawayBlocks() {
        Trail t = down();                                        // 2 one-way levels
        assertEquals(6.0, t.price(new Trail.Climb(3, 5, 2), 220).climbS(), 1e-9, "2 blocks for 2 levels: the pillar");
        assertEquals(10.0, t.price(new Trail.Climb(3, 5, 1), 220).climbS(), 1e-9, "1 block for 2 levels: the stair price");
        Trail.Price none = t.price(new Trail.Climb(3, Double.NaN, 0), 220);
        assertFalse(none.whole());
        assertTrue(none.broken().contains("no way up by the trail: no blocks to pillar"), none.broken());
    }

    @Test void breakingTimeIsKeptApartFromTheWalk() {
        Trail t = new Trail();
        t.loadedEmpty();
        t.offer(new Cell(0, 64, 0), 0, true, JUMP);
        t.offer(new Cell(1, 64, 0), 20, false, JUMP);
        for (int i = 0; i < 40; i++) t.breakTick();             // 2 s of strip-digging on the walk
        t.offer(new Cell(5, 64, 0), 100, false, JUMP);
        t.offer(new Cell(5, 60, 0), 110, false, JUMP);           // a fall: the walk segment ends
        Trail.Seg w = t.segs().get(0);
        assertEquals(3.0, w.walkedS(), 1e-9, "5 s on the segment less 2 s of breaking");
        assertEquals(2.0, w.breakS(), 1e-9);
        for (int i = 0; i < 20; i++) t.breakTick();             // 1 s breaking on the tail
        Trail.Price p = t.price(new Trail.Climb(1, 1), 150);
        assertEquals(3.0 + 0.5 + 1.0, p.walkedS(), 1e-9, "the tail's 2 s less 1 s of breaking");
        assertEquals(3.0, p.breakS(), 1e-9);
        assertTrue(p.line().contains("3 s of breaking"), p.line());
        Trails ts = new Trails();
        ts.load("w", null);
        Trail board = ts.of("o");
        board.load(t.toJson());
        assertEquals(t.segs(), board.segs(), "breakS round-trips through the file");
    }

    @Test void thePriceGoesOnToTheTargetOnce() {
        List<String> routes = List.of(
                "to GoalGetToBlock{x=30,y=64,z=7} from 0 64 1, 40 cells, 300 ticks, walks 1, saved at tick 5",
                "to GoalGetToBlock{x=30,y=64,z=7} from 0 64 0, 38 cells, 200 ticks, walks 2, saved at tick 9",
                "to GoalGetToBlock{x=31,y=64,z=7} from 0 64 0, 38 cells, 100 ticks, walks 2, saved at tick 9",
                "to GoalGetToBlock{x=30,y=64,z=7} from 9 64 0, 38 cells, 50 ticks, walks 2, saved at tick 9");
        assertEquals(200.0, Trail.routeTicks(routes, new Cell(0, 64, 0), new int[]{30, 64, 7}, 1), 1e-9, "the cheapest route from beside the start to the target");
        assertTrue(Double.isNaN(Trail.routeTicks(routes, new Cell(0, 64, 0), new int[]{2, 2, 2}, 1)));
        assertTrue(Double.isNaN(Trail.routeTicks(List.of("garbage"), new Cell(0, 64, 0), new int[]{30, 64, 7}, 1)));
        Trail.Price p = down().price(CLIMB, 220);
        Trail.Price on = p.on(10, "the fork's stored route");
        assertEquals(p.seconds() + 10, on.seconds(), 1e-9);
        assertEquals(on, on.on(10, "again"), "added once");
        assertTrue(on.line().contains("on from its start 10 s (the fork's stored route)"), on.line());
        Trail.Price unknown = p.on(Double.NaN, "no estimate");
        assertFalse(unknown.whole(), "the way on not known: no whole price (rule 18)");
        assertEquals(Trail.Pick.DIRECT, Trail.pick(PathPrice.Kind.UNREACHABLE, Double.NaN, unknown).pick());
    }

    @Test void theSurfaceWalkWeighsTheTrailOnToTheCell() {
        // the walk up to the surface cell 2 64 0 (beside the trail's start 0 64 0): 17 s home + 1 s on
        Trail.Price p = down().price(CLIMB, 220).on(1, "Baritone's estimate, no stored route");
        assertEquals(18.0, p.seconds(), 1e-9);
        assertEquals(Trail.Pick.TRAIL, Trail.pick(PathPrice.Kind.UNREACHABLE, Double.NaN, p).pick(), "the direct walk ended short: up by the trail");
        assertEquals(Trail.Pick.DIRECT, Trail.pick(PathPrice.Kind.PRICED, 18, p).pick(), "as cheap a priced path wins");
        assertEquals(Trail.Pick.TRAIL, Trail.pick(PathPrice.Kind.PRICED, 19, p).pick());
    }

    @Test void saveAndLoadRoundTrip() {
        Trails ts = new Trails();
        assertFalse(ts.loaded());
        assertEquals(Trail.State.NOT_LOADED, ts.of("minecraft:overworld").state(), "rule 15: not loaded until the file is read");
        ts.load("w", null);
        Trail t = ts.of("minecraft:overworld");
        t.loadedEmpty();
        Trail d = down();
        // the same walk into the board's trail
        t.offer(new Cell(0, 64, 0), 0, true, JUMP);
        t.offer(new Cell(1, 64, 0), 20, false, JUMP);
        t.offer(new Cell(5, 64, 0), 100, false, JUMP);
        t.offer(new Cell(5, 63, 0), 120, false, JUMP);
        t.offer(new Cell(5, 62, 0), 140, false, JUMP);
        assertTrue(ts.dirty());
        String text = ts.save();
        ts.written();
        assertFalse(ts.dirty());
        Trails back = new Trails();
        back.load("w", text);
        Trail r = back.of("minecraft:overworld");
        assertEquals(Trail.State.OPEN, r.state());
        assertEquals(t.segs(), r.segs());
        assertEquals(d.segs(), r.segs());
        assertEquals(new Cell(0, 64, 0), r.start());
        assertEquals(Trail.State.CLOSED, back.of("minecraft:the_nether").state(), "another dimension: its own, none yet");
        assertTrue(back.load("w", "{not json").startsWith("trail.json: unreadable"));
    }
}
