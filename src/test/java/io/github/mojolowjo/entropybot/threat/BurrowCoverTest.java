package io.github.mojolowjo.entropybot.threat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** aa-night3 (normal2: 7 deaths, "0 throwaway blocks; no wall", 4 skeleton arrows): the burrow escape and ranged cover. */
class BurrowCoverTest {
    @Test
    void noBlocksNoWallDigsDown() {
        var c = CornerEscape.choose(true, true, false, 3, 0, false, 3);
        assertEquals(CornerEscape.Kind.DIG_DOWN, c.kind());
        assertEquals(3, c.height());
        assertEquals(CornerEscape.Kind.NONE, CornerEscape.choose(true, true, false, 3, 0, false, 0).kind());
    }

    @Test
    void cheapestEscapeWins() {
        assertEquals(CornerEscape.Kind.PILLAR, CornerEscape.choose(true, true, false, 3, 10, true, 2).kind(), "pillar 3 < down 5 < wall 6");
        assertEquals(CornerEscape.Kind.DIG_DOWN, CornerEscape.choose(true, true, false, 0, 10, true, 2).kind(), "no headroom: down 5 < wall 6");
        assertEquals(CornerEscape.Kind.DIG_IN, CornerEscape.choose(true, true, false, 0, 10, true, 3).kind(), "wall 6 < down 7");
        assertEquals(CornerEscape.Kind.NONE, CornerEscape.choose(false, true, false, 3, 0, false, 3).kind(), "fighting is fine");
    }

    @Test
    void burrowDepthAndStages() {
        boolean[] dig = {true, true, true};
        assertEquals(2, CornerEscape.burrowDepth(dig, new boolean[]{true, true}, true));
        assertEquals(3, CornerEscape.burrowDepth(dig, new boolean[]{true, true}, false), "open ground at the feet: seal inside the ground");
        assertEquals(0, CornerEscape.burrowDepth(new boolean[]{true, true, false}, new boolean[]{true, true}, false));
        assertEquals(0, CornerEscape.burrowDepth(dig, new boolean[]{false, false}, true));
        assertEquals(0, CornerEscape.burrowStage(0, 3, false, false, false));
        assertEquals(0, CornerEscape.burrowStage(0, 3, true, false, false), "open but not dropped in yet");
        assertEquals(1, CornerEscape.burrowStage(0, 3, true, true, false));
        assertEquals(3, CornerEscape.burrowStage(2, 3, true, true, false));
        assertEquals(3, CornerEscape.burrowStage(3, 3, false, false, false));
        assertEquals(4, CornerEscape.burrowStage(3, 3, false, false, true));
    }

    @Test
    void skeletonsAtRangeMeanCover() {
        var fight = new FightOrFlee.Result(FightOrFlee.Verdict.FIGHT, 5, 2, "fight: fliers/ranged can't be outrun");
        var skel = List.of(new FightOrFlee.Foe("minecraft:skeleton", 9, false));
        var r = FightOrFlee.cover(fight, 6, skel);
        assertTrue(FightOrFlee.isCover(r), r.why());
        assertTrue(FightOrFlee.isCover(FightOrFlee.cover(fight, 1, List.of(new FightOrFlee.Foe("minecraft:skeleton", 2, false)))), "unarmed: always cover");
        assertEquals(FightOrFlee.Verdict.FIGHT, FightOrFlee.cover(fight, 6, List.of(new FightOrFlee.Foe("minecraft:skeleton", 2, false))).verdict(), "armed and in reach");
        assertEquals(FightOrFlee.Verdict.FIGHT, FightOrFlee.cover(fight, 1, List.of(new FightOrFlee.Foe("minecraft:zombie", 2, false))).verdict(), "no ranged: not this rule");
    }
}
