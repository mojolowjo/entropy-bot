package io.github.mojolowjo.entropybot.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 0.39.z76 J3-mod item 2 (rule 18 amended: rank rough, walk exact; the fork's J3 {@code IPricedPath.cancel}): which of the
 * mod's pending priced-path asks to withdraw at one sweep (LivePathPrice, once per AA tick). An ask is withdrawn when nobody
 * asked for its goal since the last sweep AND either the chain ranked it out of the ways it prices (a way dropped) or the
 * bot stands more than {@code moved} blocks from the cell it was asked from (failures.moved: the planner's own "moved");
 * never the running step's walk. An ask asked again stays (z57 review 2: its answer lands from the feet). Pure (JUnit:
 * Z76RankRoughTest). Loader notes: none.
 */
public final class AskSweep {
    private AskSweep() {}

    /** One pending ask: its goal key ("x,y,z") and the feet cell it was asked from (null: not known). */
    public record Ask(String key, int[] from) {}

    /** The goal keys to withdraw now. {@code feet}: the feet now (null: not known - no move rule, rule 15). */
    public static List<String> due(List<Ask> pending, Set<String> asked, Set<String> rankedOut, int[] feet, double moved, String running) {
        List<String> out = new ArrayList<>();
        for (Ask a : pending) {
            if (a.key() == null || asked.contains(a.key()) || a.key().equals(running)) continue;
            boolean dropped = rankedOut.contains(a.key());
            boolean left = feet != null && a.from() != null && dist(feet, a.from()) > moved;
            if (dropped || left) out.add(a.key());
        }
        return out;
    }

    static double dist(int[] a, int[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
