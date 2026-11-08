package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.moves.WalkEndText;
import io.github.mojolowjo.entropybot.aa.trail.Trail;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** job-climb: a trail leg that ends short says Baritone's own end and how the leg ran. */
class TrailClimbTest {
    @Test void aShortPillarSaysBaritonesEnd() {
        Trail.Leg pillar = Trail.leg(new Trail.Goal(new Trail.Cell(0, 24, 0), new Trail.Cell(0, 24, 0), new Trail.Cell(0, 6, 0), true), Trail.ClimbHow.PILLAR);
        String why = WalkEndText.reason(new WalkEndText.Calc(true, true, false, "EXHAUSTED"), 3, 3, null);
        String line = Trail.breakText("0 24 0", pillar, "0 6 0", why);
        assertTrue(line.startsWith("Baritone's pillar (placing on, no breaks) to 0 24 0 ended at 0 6 0 short of it: "), line);
        assertTrue(line.contains("no path (the graph exhausted)"), line);
    }

    @Test void aShortWalkKeepsTheWalkWords() {
        String line = Trail.breakText("1 2 3", Trail.Leg.WALK, "1 2 4", WalkEndText.reason(WalkEndText.Calc.NONE, 0, 1, "no path"));
        assertTrue(line.startsWith("Baritone's walk to 1 2 3 ended at 1 2 4 short of it: "), line);
        assertTrue(line.contains("no path"), line);
    }
}
