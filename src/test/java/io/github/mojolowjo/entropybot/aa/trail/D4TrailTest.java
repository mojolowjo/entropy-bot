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
