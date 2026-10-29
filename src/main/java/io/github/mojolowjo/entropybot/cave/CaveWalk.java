package io.github.mojolowjo.entropybot.cave;

import io.github.mojolowjo.entropybot.sense.CaveRecord;
import io.github.mojolowjo.entropybot.sense.Sightings;

import java.util.List;
import java.util.Locale;

/**
 * 0.39.z78 J6b (D12): the caving step's decisions inside a KNOWN cave, on its record (the one store). In order:
 * <ol>
 * <li>the need met (the ore blocks mined) - ends, NEED_MET;</li>
 * <li>the step's minutes ({@code cave.exploreMinutes} at most, the cave way's cap) - ends, MINUTES;</li>
 * <li>a seen ore of the family left in reach of the eyes - mined by the vein (seen ore only, rule 17), its goto dropped
 * or not (J6d, run EV);</li>
 * <li>a seen ore of the family left farther in - walked to;</li>
 * <li>the nearest branch (a frontier group with more than BRANCH_MIN unseen cells behind it), ranked by straight distance
 * (rule 18 as amended: ranked rough, Baritone's walk exact) - its first cell walked to;</li>
 * <li>none: the record's own done (THE done reader, rule 23 (2); run EK 02:24:18: an empty frontier because nothing was
 * seen yet is not done) - DONE; the cells being read from the index - Wait; not loaded - NOT_LOADED; else the unseen cell
 * nearest the cave's entry ({@code firstUnseen}, the step's first target) - walked to; none - NO_PROGRESS, said.</li>
 * </ol>
 * Baritone's no progress on a walk ends the step too (NO_PROGRESS; the adapter's hold, ExploreRules.TargetHold). Torches
 * are not in this slice (D12 5). Pure (JUnit: J6bCaveStepTest). Loader notes: none (StepHands is the adapter).
 */
public final class CaveWalk {
    private CaveWalk() {}

    public enum End { NEED_MET, MINUTES, DONE, NOT_LOADED, NO_PROGRESS, NO_BRANCH, STOPPED }

    public sealed interface Next permits Mine, Goto, Stop, Wait {}

    /** Run EK: the record's cells are being read from the index (its first pass): the step waits, Baritone idle. */
    public record Wait(String why) implements Next {}

    /** Mine the seen ore cell (its vein). */
    public record Mine(int[] cell) implements Next {}

    /**
     * Walk to the cell ({@code why}: "the seen iron at ...", "the branch at ... (more than 8 cells behind)"). Run EN: the
     * step's goal is a reach goal over {@code cells} (the ore, the unseen cell, or the branch's frontier cells) - a stand
     * Baritone picks from which one of them is in reach and in sight, never the cell itself (rule 2); {@code key} the
     * drop key (the cell, or the branch's first cell). J6d (run EV): {@code kind} ORE - the walk is to a seen ore (its
     * arrival mines it, never drops it "arrived, nothing new"); BRANCH - fc (run FC): its cells are the unseen index cells
     * behind the branch's frontier (CaveRecord.Group#unseen); CELL - an unseen cell (the first target by the entry).
     */
    public record Goto(int[] cell, String why, List<int[]> cells, long key, Kind kind) implements Next {
        public enum Kind { ORE, BRANCH, CELL }

        public Goto {
            cells = cells == null || cells.isEmpty() ? List.of(cell) : List.copyOf(cells);
        }

        public Goto(int[] cell, String why, List<int[]> cells, long key) { this(cell, why, cells, key, Kind.CELL); }

        public Goto(int[] cell, String why) { this(cell, why, List.of(cell), Sightings.cell(cell[0], cell[1], cell[2]), Kind.CELL); }

        /** J6d: the walk to a seen ore (one cell, its own key). */
        public static Goto toOre(int[] cell, String why) { return new Goto(cell, why, List.of(cell), Sightings.cell(cell[0], cell[1], cell[2]), Kind.ORE); }

        /** J6d: the walk is to a seen ore. */
        public boolean ore() { return kind == Kind.ORE; }

        /** fc: the walk is to see behind a branch's frontier. */
        public boolean branch() { return kind == Kind.BRANCH; }
    }

    /**
     * fc (run FC, cause 3): the cells a branch's reach goal spans - the unseen index cells behind its frontier (nearest the
     * feet first, {@link #REACH_CELLS} of them); a group that names none (a record of before fc): its frontier cells as
     * before. Pure.
     */
    public static List<int[]> branchCells(CaveRecord.Group g, int[] feet) {
        List<int[]> cells = new java.util.ArrayList<>();
        for (long k : g.unseen().isEmpty() ? g.cells() : g.unseen()) cells.add(Sightings.cellOf(k));
        return nearest(cells, feet, REACH_CELLS);
    }

    /** fc: the drop of a branch whose reach goal over the unseen cells behind it is empty (said once, the key dropped). */
    public static String noStandSees(int cells) { return "no stand sees behind it (no standable cell within reach and sight of its " + cells + " unseen cell(s))"; }

    /**
     * J6d (run EV: ore seen 36, mined 0 - the seen-ore gotos were dropped "arrived, nothing new", then skipped): the drop
     * of an arrival that saw nothing new is for the gotos that walk to SEE (branches, unseen cells), never a walk to a seen
     * ore - its arrival is in reach of the ore, and the next move mines it. Pure.
     */
    public static boolean dropsOnNothingNew(boolean oreGoto, int seenAtGoal, int seenNow) { return !oreGoto && nothingNew(seenAtGoal, seenNow); }

    /** The step ends. */
    public record Stop(End end, String why) implements Next {}

    /**
     * Run EN: the frontier cells the branch's reach goal is built over - the nearest to the feet of the group's kept
     * cells. 4: a branch's mouth is a few adjacent cells (a carver tunnel 2 x 2); each reach goal tests a cube of about
     * 13 x 13 x 15 cells with a sight ray each on the game thread, so 4 keep the build under ~10,000 tests once per branch.
     */
    public static final int REACH_CELLS = 4; // structural: a 2 x 2 tunnel's mouth, the build's cost on the game thread

    /**
     * d0682da0 review (rule 17: seen is what is seen): of a composite goal's parts ({@code parts}, each the reach goal of
     * {@code cells}' cell at the same index), the cells whose OWN reach goal holds the feet - in reach and in sight from
     * here; the others are not seen by this arrival. Pure.
     */
    public static List<int[]> seenFrom(List<ExploreRules.LipGoal> parts, List<int[]> cells, int[] feet) {
        List<int[]> out = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(parts.size(), cells.size()); i++)
            if (feet != null && feet.length >= 3 && parts.get(i).isInGoal(feet[0], feet[1], feet[2])) out.add(cells.get(i));     // structural: x y z
        return out;
    }

    /** Run EO: an arrival that added no seen cell to the record since the goal was set ("arrived, nothing new"). Pure. */
    public static boolean nothingNew(int seenAtGoal, int seenNow) { return seenNow <= seenAtGoal; }

    /** Run EN: the {@code n} cells of {@code cells} nearest {@code feet} (nearest first). Pure. */
    public static List<int[]> nearest(List<int[]> cells, int[] feet, int n) {
        List<int[]> out = new java.util.ArrayList<>(cells);
        out.sort((a, b) -> Long.compare(d2(a, feet), d2(b, feet)));
        return out.size() > n ? out.subList(0, n) : out;
    }

    /**
     * The next move. {@code mined}/{@code need}: ore blocks; {@code minutesIn}/{@code capMinutes}: the step's time inside;
     * {@code facts}: the record now; {@code frontier}: its branches; {@code left}: the family's seen ore not mined;
     * {@code feet}: x y z; {@code reach}: the eyes' reach for a vein.
     */
    public static Next next(int mined, int need, double minutesIn, double capMinutes, CaveRecord.Facts facts, List<CaveRecord.Group> frontier,
                            List<int[]> left, int[] feet, double reach) {
        return next(mined, need, minutesIn, capMinutes, facts, frontier, left, feet, reach, null);
    }

    /** As above; {@code firstUnseen}: the record's unseen cell nearest the entry (CaveRecord#firstUnseen; null: none). */
    public static Next next(int mined, int need, double minutesIn, double capMinutes, CaveRecord.Facts facts, List<CaveRecord.Group> frontier,
                            List<int[]> left, int[] feet, double reach, int[] firstUnseen) {
        return next(mined, need, minutesIn, capMinutes, facts, frontier, left, feet, reach, firstUnseen, java.util.Set.of());
    }

    /**
     * Run EN (rule 23 (1)): as above; {@code dropped} - the goto keys the step dropped (an empty reach goal, no path):
     * passed over, the next one taken; every one dropped and nothing else to do - NO_BRANCH ("no reachable branch").
     * J6d (run EV): a seen ore left IN REACH of the feet is mined (the vein, from where the bot stands; Baritone picks the
     * look and the click, rule 2) even when its goto was dropped - a drop is of a walk, and no walk is needed; the nearest
     * in reach first. The caller leaves out of {@code left} the ore whose vein failed (said, rule 7).
     */
    public static Next next(int mined, int need, double minutesIn, double capMinutes, CaveRecord.Facts facts, List<CaveRecord.Group> frontier,
                            List<int[]> left, int[] feet, double reach, int[] firstUnseen, java.util.Set<Long> dropped) {
        if (need > 0 && mined >= need) return new Stop(End.NEED_MET, mined + " of " + need + " ore mined");
        if (capMinutes > 0 && minutesIn >= capMinutes) return new Stop(End.MINUTES, String.format(Locale.ROOT, "%.1f min inside, cave.exploreMinutes %.0f", minutesIn, capMinutes));
        int skipped = 0;
        int[] inReach = null;
        long rd = Long.MAX_VALUE;
        for (int[] o : left) {
            if (!ExploreRules.withinReach(feet, o)) continue;
            long d = d2(o, feet);
            if (d < rd) { rd = d; inReach = o; }
        }
        if (inReach != null) return new Mine(inReach);
        int[] near = null;
        long nd = Long.MAX_VALUE;
        for (int[] o : left) {
            if (dropped.contains(Sightings.cell(o[0], o[1], o[2]))) { skipped++; continue; }
            long d = d2(o, feet);
            if (d < nd) { nd = d; near = o; }
        }
        if (near != null) return Goto.toOre(near, "the seen ore at " + near[0] + " " + near[1] + " " + near[2]);
        CaveRecord.Group best = null;
        long bd = Long.MAX_VALUE;
        for (CaveRecord.Group g : frontier) {
            if (g.behind() <= CaveRecord.BRANCH_MIN) continue;
            if (dropped.contains(g.first())) { skipped++; continue; }
            long d = d2(Sightings.cellOf(g.first()), feet);
            if (d < bd) { bd = d; best = g; }
        }
        if (best != null) {
            int[] c = Sightings.cellOf(best.first());
            // fc (run FC, cause 3): the goal spans the unseen cells behind the frontier - a stand that sees behind it
            return new Goto(c, "the branch at " + c[0] + " " + c[1] + " " + c[2] + " (more than " + CaveRecord.BRANCH_MIN + " cells behind)",
                    branchCells(best, feet), best.first(), Goto.Kind.BRANCH);
        }
        // 88b28290 review 3: while the cells are read, done is the saved measure (maybe a past session's): never trusted - wait
        if (facts != null && facts.filling()) return new Wait("reading the cave's cells from the index (its first pass, CaveRecord.BUDGET a tick)");
        if (facts != null && facts.done()) return new Stop(End.DONE, "the cave is explored to its end (the record: something seen, no branch of more than " + CaveRecord.BRANCH_MIN + " cells left)");
        if (facts != null && !facts.loaded()) return new Stop(End.NOT_LOADED, "the cave's cells are not loaded (its chunks: the record cannot say where to go)");
        if (firstUnseen != null && !dropped.contains(Sightings.cell(firstUnseen[0], firstUnseen[1], firstUnseen[2])))
            return new Goto(firstUnseen, "the unseen cell nearest the entry at " + firstUnseen[0] + " " + firstUnseen[1] + " " + firstUnseen[2]);
        if (firstUnseen != null) skipped++;
        if (skipped > 0) return new Stop(End.NO_BRANCH, "no reachable branch (" + skipped + " dropped)");
        return new Stop(End.NO_PROGRESS, "no branch and no unseen cell near the entry within CaveRecord.BUDGET cells, yet the record is not done");
    }

    /**
     * FK fix (run FK 19:20:08-19:22:49: the caving step stood at 53 30 0 with every branch walk failing, was ended "stuck 40 s"
     * twice and the same cave priced again from the same cell in between, then the order parked): what the ended walk says of
     * the cave for the record's stuck mark ({@link CaveRecord#stuck}) - {@code gainedNow}: a cell seen or an ore mined this
     * walk; null: judge nothing. A STOPPED end judges nothing when the stop came from outside the walk (an owner stop, aa off,
     * the run's timeout: 57db13b7 review (b)) - but a stop for a STALL ({@link #stallStop}: the brain's "stuck N s", the step
     * monitor's "no new best", the run's "no progress") is the walk's own verdict and judges it as any end. Pure.
     */
    public static Boolean judged(End end, String why, boolean gainedNow) {
        if (end != End.STOPPED) return gainedNow;
        return stallStop(why) ? (Boolean) gainedNow : null;
    }

    /** FK fix: the stop's reason is a stall verdict (Brain / StepMonitor / RunJob words), not an owner's or the run's end. */
    public static boolean stallStop(String why) {
        if (why == null) return false;
        String w = why.toLowerCase(Locale.ROOT);
        return w.contains("stuck ") || w.contains("no new best") || w.contains("no progress");
    }

    /**
     * D12's measure on the step's end line: "cave: the caving step at x y z ended - NEED_MET (3 of 3 ore mined): 2.4 min
     * inside, cells seen 40 -> 120 of 200 (explored 60%), ore seen 4 / mined 3, branches left 2".
     */
    public static String endLine(String where, End end, String why, double minutes, int seenBefore, CaveRecord.Facts after, int oreSeen, int mined) {
        int seenNow = after == null ? seenBefore : after.cellCount() - after.unseen();
        return String.format(Locale.ROOT, "cave: the caving step at %s ended - %s (%s): %.1f min inside, cells seen %d -> %d of %d (explored %d%%), ore seen %d / mined %d, branches left %s",
                where, end, why, minutes, seenBefore, seenNow, after == null ? 0 : after.cellCount(), after == null ? 0 : Math.round(after.explored() * 100), oreSeen, mined,   // structural: a percent
                after == null || after.groups() < 0 ? "not measured" : String.valueOf(after.groups()));
    }

    private static long d2(int[] a, int[] b) {
        long dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }
}
