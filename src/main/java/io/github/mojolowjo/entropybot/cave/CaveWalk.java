package io.github.mojolowjo.entropybot.cave;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.sense.CaveRecord;
import io.github.mojolowjo.entropybot.sense.Sightings;

import java.util.List;
import java.util.Locale;

/**
 * 0.39.z78 J6b (D12): the caving step's decisions inside a KNOWN cave, on its record (the one store). In order:
 * <ol>
 * <li>the need met (the ore blocks mined) - ends, NEED_MET;</li>
 * <li>the step's minutes ({@code cave.exploreMinutes} at most, the cave way's cap) - ends, MINUTES;</li>
 * <li>a seen ore of the family left in reach of the eyes, or (pass three) within the mine's sight - mined by the vein (seen ore only, rule 17), its goto dropped
 * or not (J6d, run EV);</li>
 * <li>a seen ore of the family left farther in - walked to;</li>
 * <li>D15: the open passage ({@link Passages}: not all seen, or its end past the flood's cap) of the lowest price -
 * seconds to walk to its far end per ore expected along it ({@link #passagePrice}; rule 18: a price, never a rank) -
 * walked to its far end (a GoalNear Baritone paths to, rule 2);</li>
 * <li>none: the record's own done (THE done reader, rule 23 (2); run EK 02:24:18: an empty frontier because nothing was
 * seen yet is not done) - DONE; the cells being read from the index - Wait; not loaded - NOT_LOADED; else (pass two: no
 * passage from the feet - the lip, no cave air within cave.passageLipNear) the cave's MOUTH ({@code mouth}, its entry cell) -
 * walked to under the ordinary hold; none - NO_PROGRESS, said.</li>
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
     * drop key (the cell, or the passage's far end). J6d (run EV): {@code kind} ORE - the walk is to a seen ore (its
     * arrival mines it, never drops it "arrived, nothing new"); PASSAGE - D15: the walk is to a passage's far end
     * ({@code passage}: its cells, seen on arrival - the bot walked it end to end); CELL - an unseen cell (the first
     * target by the entry).
     */
    public record Goto(int[] cell, String why, List<int[]> cells, long key, Kind kind, Passages.Passage passage) implements Next {
        /** DESCENT (D15 pass four): the record's unseen cell off the walkable flood, a GoalNear Baritone paths to (falls priced by it). */
        public enum Kind { ORE, PASSAGE, CELL, DESCENT }

        /** D15 pass four: the walk is Baritone's way down (or up) from a pocket to the cave's unseen cell. */
        public boolean isDescent() { return kind == Kind.DESCENT; }

        public Goto {
            cells = cells == null || cells.isEmpty() ? List.of(cell) : List.copyOf(cells);
        }

        public Goto(int[] cell, String why, List<int[]> cells, long key, Kind kind) { this(cell, why, cells, key, kind, null); }

        /** D15: the walk to a passage's far end. */
        public static Goto toPassage(Passages.Passage p, String why) { return new Goto(p.end(), why, List.of(p.end()), p.key(), Kind.PASSAGE, p); }

        public Goto(int[] cell, String why, List<int[]> cells, long key) { this(cell, why, cells, key, Kind.CELL); }

        public Goto(int[] cell, String why) { this(cell, why, List.of(cell), Sightings.cell(cell[0], cell[1], cell[2]), Kind.CELL); }

        /** J6d: the walk to a seen ore (one cell, its own key). */
        public static Goto toOre(int[] cell, String why) { return new Goto(cell, why, List.of(cell), Sightings.cell(cell[0], cell[1], cell[2]), Kind.ORE); }

        /** J6d: the walk is to a seen ore. */
        public boolean ore() { return kind == Kind.ORE; }

        /** D15: the walk is to a passage's far end. */
        public boolean isPassage() { return kind == Kind.PASSAGE; }
    }

    /**
     * D15: a passage's price - the seconds to walk to its far end ({@code fromFeet + length} layers, {@code secPerCell}
     * each: cave.passageSecPerCell) per ore expected along it (the ore faces seen on its walls, plus its unseen cells times
     * {@code orePerUnseen}; job-price: a {@link Density}, the order's ore by game data). Nothing expected: infinite (not offered). Pure.
     */
    public static double passagePrice(Passages.Passage p, double secPerCell, double orePerUnseen) { return passagePrice(p, secPerCell, orePerUnseen, 0); }

    /** D15 pass three: as above, a CAPPED passage also expecting ore in {@code pastCap} cells past the flood (its share). Pure. */
    public static double passagePrice(Passages.Passage p, double secPerCell, double orePerUnseen, double pastCap) {
        return passagePrice(p, secPerCell, Density.flat(orePerUnseen, "given"), pastCap);
    }

    /** Job-price: as above, the ore per unseen cell from {@code d} at the passage's mean Y (the order's ore, game data). Pure. */
    public static double passagePrice(Passages.Passage p, double secPerCell, Density d, double pastCap) {
        double expected = p.oreFaces() + (p.unseen() + Math.max(0, pastCap)) * d.at(meanY(p));
        if (!(expected > 0)) return Double.POSITIVE_INFINITY;
        return (p.fromFeet() + p.length()) * secPerCell / expected;
    }

    /** Job-price: a passage's mean Y over its cells (none: the middle of its start and end). Pure. */
    public static double meanY(Passages.Passage p) {
        if (p.cells() == null || p.cells().isEmpty()) return (p.start()[1] + p.end()[1]) / 2.0;
        double s = 0;
        for (long k : p.cells()) s += Sightings.cellOf(k)[1];
        return s / p.cells().size();
    }

    /**
     * Job-price (run JAO: a surface cave priced 3 ore in 63 s at a hand 0.01 ore a cell while its own world-gen fact said
     * 0.6 in the whole cave): the order's ore expected per unseen cave cell at a Y. Game data: the record's air-exposed
     * blocks per cell x the placements' share of a block at that Y (CaveYield's count, per cell); not known (rule 15): the
     * record's seen-ore rate, else the Tuning fallback cave.orePerCellNotKnown - {@code why} says which.
     */
    public record Density(int minY, double[] perY, String why) {
        /*
         * job-freeze (run JAP: 293 results in a row dropped, every CaveWay printing the same before and after): this was
         * Density(IntToDoubleFunction atY, ...) - a fresh lambda each re-price, whose equals is identity, so the planner's
         * fact check (Deep.equal -> lambda.equals) said every cave fact changed. Now a value: the curve sampled once per Y
         * over [MIN_Y, MAX_Y] (a flat density: one sample), each sample rounded to PRECISION; equals by those values.
         */

        /** structural: the lowest / highest Y sampled (vanilla's world is -64..319; a Y outside takes the nearest sample) */
        public static final int MIN_Y = -128, MAX_Y = 383;
        /** structural: a density sample is rounded to this many ore per cell (a fact's double compared at a stated precision) */
        public static final double PRECISION = 1e-9;

        public Density {
            perY = perY == null || perY.length == 0 ? new double[]{0} : perY.clone();
            for (int i = 0; i < perY.length; i++) perY[i] = round(perY[i]);
        }

        static double round(double v) { return Double.isFinite(v) ? Math.rint(v / PRECISION) * PRECISION + 0.0 : v; }

        public double at(double y) {
            int i = (int) Math.round(y) - minY;
            return Math.max(0, perY[Math.max(0, Math.min(perY.length - 1, i))]);
        }

        @Override public double[] perY() { return perY.clone(); }

        @Override public boolean equals(Object o) {
            return o instanceof Density d && minY == d.minY && java.util.Arrays.equals(perY, d.perY) && java.util.Objects.equals(why, d.why);
        }

        @Override public int hashCode() { return 31 * (31 * minY + java.util.Arrays.hashCode(perY)) + java.util.Objects.hashCode(why); }

        @Override public String toString() { return "Density[" + why + "]"; }

        public static Density flat(double perCell, String why) { return new Density(0, new double[]{perCell}, why); }

        /** The curve {@code atY} sampled over [MIN_Y, MAX_Y]. */
        public static Density sampled(java.util.function.IntToDoubleFunction atY, String why) {
            double[] v = new double[MAX_Y - MIN_Y + 1];
            for (int i = 0; i < v.length; i++) v[i] = atY.applyAsDouble(MIN_Y + i);
            return new Density(MIN_Y, v, why);
        }

        /**
         * The density for the order's ore: {@code perBlockAtY} the placements' ore per air-exposed block at a Y (GenWhere
         * curve / CaveYield.LAYER_BLOCKS; null: no placement fact), {@code blocks} / {@code cells} the record's air-exposed
         * blocks and cells (the count done); {@code seenOre} / {@code seenCells} the record's seen ore of the family and seen
         * cells. Pure.
         */
        public static Density of(java.util.function.IntToDoubleFunction perBlockAtY, long blocks, long cells, int seenOre, long seenCells) {
            if (perBlockAtY != null && blocks > 0 && cells > 0) {
                double perCell = blocks / (double) cells;
                return sampled(y -> perCell * perBlockAtY.applyAsDouble(y), String.format(Locale.ROOT,
                        "game data: the placements' share at each passage's mean Y x %d air-exposed blocks over %d cells", blocks, cells));
            }
            if (seenOre > 0 && seenCells > 0)
                return flat(seenOre / (double) seenCells, String.format(Locale.ROOT, "density not known by game data: the record's seen rate, %d ore in %d cells seen", seenOre, seenCells));
            return notKnown();
        }

        /** No placement fact and no ore seen (rule 15): the Tuning fallback, said. */
        public static Density notKnown() {
            double f = Tuning.get("cave.orePerCellNotKnown");
            return flat(f, String.format(Locale.ROOT, "density not known: no placement fact and no ore seen, the fallback cave.orePerCellNotKnown %s", f));
        }
    }

    /** Pass two: the planner's cave price - seconds to find {@code n} ore along the open passages; {@code expected} the ore they hold. */
    public record FindPrice(double seconds, double expected, int open, String line) {}

    /**
     * D15 pass two: the cave's price in the planner (rule 18: seconds, never a rank) - the open passages nearest first
     * (by {@code fromFeet}), each costing the walk to its far end ({@code fromFeet + length} layers x {@code secPerCell})
     * and holding {@code unseen x orePerUnseen} ore expected, taken until {@code n} ore are expected (the last one in
     * part); past them all, the rest at their average seconds per ore. The seen ore is priced apart (its walk is the
     * record's left ore), so faces are not counted here. No passage list, or nothing expected: NaN, said. Pure.
     */
    public static FindPrice findSeconds(List<Passages.Passage> ps, int n, double secPerCell, double orePerUnseen) {
        return findSeconds(ps, n, secPerCell, Density.flat(orePerUnseen, "given"));
    }

    /**
     * Job-price: as above, each passage's ore = its unseen cells x {@code d} at its mean Y (the order's ore by game data).
     * Under 1 ore expected along all of them: "under 1 ore expected: K s per ore" - the cave competes honestly with the
     * ways down. The density's words close the line (rule 7). Pure.
     */
    public static FindPrice findSeconds(List<Passages.Passage> ps, int n, double secPerCell, Density d) {
        FindPrice f = findCore(ps, n, secPerCell, d);
        return ps == null || n <= 0 ? f : new FindPrice(f.seconds(), f.expected(), f.open(), f.line() + " (" + d.why() + ")");
    }

    private static FindPrice findCore(List<Passages.Passage> ps, int n, double secPerCell, Density d) {
        if (ps == null) return new FindPrice(Double.NaN, 0, -1, "its passages not measured yet (the cave's cells not loaded)");
        List<Passages.Passage> open = new java.util.ArrayList<>();
        for (Passages.Passage p : ps) if (p.open()) open.add(p);
        open.sort(java.util.Comparator.comparingInt(Passages.Passage::fromFeet));
        if (n <= 0) return new FindPrice(0, 0, open.size(), "no ore to find");
        double secs = 0, exp = 0, used = 0;
        for (Passages.Passage p : open) {
            double e = p.unseen() * d.at(meanY(p)), w = (p.fromFeet() + p.length()) * secPerCell;
            if (!(e > 0)) continue;
            if (exp + e >= n) {
                secs += w * (n - exp) / e;
                return new FindPrice(secs, exp + e, open.size(), String.format(Locale.ROOT, "%d ore along the %d nearest of %d open passage(s): %.0f s", n, (int) used + 1, open.size(), secs));
            }
            secs += w;
            exp += e;
            used++;
        }
        if (!(exp > 0)) return new FindPrice(Double.NaN, 0, open.size(), open.size() + " open passage(s), no ore expected along them");
        double all = secs + (n - exp) * secs / exp;
        if (exp < 1)
            return new FindPrice(all, exp, open.size(), String.format(Locale.ROOT, "%d ore: under 1 ore expected (%.2f along all %d open passage(s), %.0f s): %.0f s per ore -> %.0f s",
                    n, exp, open.size(), secs, secs / exp, all));
        return new FindPrice(all, exp, open.size(), String.format(Locale.ROOT, "%d ore: %.1f expected along all %d open passage(s) (%.0f s), the rest at their %.1f s per ore: %.0f s",
                n, exp, open.size(), secs, secs / exp, all));
    }

    /** D15: the open passages (CaveWalk's "branches left"). */
    public static int openPassages(List<Passages.Passage> ps) {
        int n = 0;
        for (Passages.Passage p : ps) if (p.open()) n++;
        return n;
    }

    /**
     * D15 (run JAD: 66 re-plans in 4 minutes, each new sighting of the same ore re-priced the caving step): while a caving
     * step runs, a new sighting re-prices the order only when it is closer than the step's current target by
     * {@code margin} blocks (cave.seenMarginM); no target known (the step mining, or between picks): never - the step's
     * own record holds the ore and its next pick takes it. Pure.
     */
    public static boolean seenRepricesCave(double sightDist, double targetDist, double margin) {
        if (Double.isNaN(sightDist) || Double.isNaN(targetDist)) return false;
        return sightDist + margin < targetDist;
    }

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

    /** Churn fix: the reach goals built for one branch goal at most (far cells that no stand sees cost a build each). */
    public static final int REACH_TRIES = 8; // structural: twice REACH_CELLS - the far end tried, the near edge still reachable

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
     * {@code facts}: the record now; {@code passages}: D15, the cave's passages from the feet ({@link Passages#build});
     * {@code left}: the family's seen ore not mined; {@code feet}: x y z; {@code reach}: the eyes' reach for a vein.
     */
    public static Next next(int mined, int need, double minutesIn, double capMinutes, CaveRecord.Facts facts, List<Passages.Passage> passages,
                            List<int[]> left, int[] feet, double reach) {
        return next(mined, need, minutesIn, capMinutes, facts, passages, left, feet, reach, null);
    }

    /** As above; {@code mouth}: pass two, the cave's entry cell, walked to when no passage is found from the feet (null: none). */
    public static Next next(int mined, int need, double minutesIn, double capMinutes, CaveRecord.Facts facts, List<Passages.Passage> passages,
                            List<int[]> left, int[] feet, double reach, int[] mouth) {
        return next(mined, need, minutesIn, capMinutes, facts, passages, left, feet, reach, mouth, java.util.Set.of());
    }

    /**
     * Run EN (rule 23 (1)): as above; {@code dropped} - the goto keys the step dropped (an empty reach goal, no path):
     * passed over, the next one taken; every one dropped and nothing else to do - NO_BRANCH ("no reachable branch").
     * J6d (run EV): a seen ore left IN REACH of the feet is mined (the vein, from where the bot stands; Baritone picks the
     * look and the click, rule 2) even when its goto was dropped - a drop is of a walk, and no walk is needed; the nearest
     * in reach first. The caller leaves out of {@code left} the ore whose vein failed (said, rule 7).
     */
    public static Next next(int mined, int need, double minutesIn, double capMinutes, CaveRecord.Facts facts, List<Passages.Passage> passages,
                            List<int[]> left, int[] feet, double reach, int[] mouth, java.util.Set<Long> dropped) {
        return next(mined, need, minutesIn, capMinutes, facts, passages, left, feet, reach, mouth, dropped, false, java.util.Set.of());
    }

    /**
     * D15 pass three: the seen ore of the family to mine now - the nearest within the eyes' reach, or within {@code sight}
     * blocks of the feet (Step.Collect.MINE_SIGHT, the fork's mine reach: mineWith walks to it); null: none. The walk to a
     * passage pauses for it (run JAK: one ore seen, none mined). Pure.
     */
    public static int[] oreToMine(List<int[]> left, int[] feet, double sight) {
        int[] best = null;
        long bd = Long.MAX_VALUE;
        for (int[] o : left) {
            long d = d2(o, feet);
            if (!ExploreRules.withinReach(feet, o) && d > sight * sight) continue;
            if (d < bd) { bd = d; best = o; }
        }
        return best;
    }

    /**
     * D15 pass three (run JAK): the cave's unseen cells the flood from the feet did not hold (the record's unseen less the
     * passages' own); facts null: 0. Pure.
     */
    public static int beyondFlood(CaveRecord.Facts facts, List<Passages.Passage> passages) {
        if (facts == null) return 0;
        long in = 0;
        for (Passages.Passage p : passages) in += p.unseen();
        return (int) Math.max(0, facts.unseen() - in);
    }

    /**
     * D15 pass three: a CAPPED passage the step has flooded again from (its end within {@link CaveRecord#SEE_STEPS} of a
     * stand in {@code reflooded}) with no unseen cell of its own: spent, not offered again (else two capped ends ping-pong).
     */
    public static boolean spent(Passages.Passage p, java.util.Set<Long> reflooded) {
        if (p.endKind() != Passages.EndKind.CAPPED || p.unseen() > 0 || reflooded.isEmpty()) return false;
        long r2 = (long) CaveRecord.SEE_STEPS * CaveRecord.SEE_STEPS;
        for (long k : reflooded) if (d2(Sightings.cellOf(k), p.end()) <= r2) return true;
        return false;
    }

    /**
     * D15 pass three (run JAK: DONE at 12% explored, the flood's slice judged as the cave): as above; {@code capped} the
     * flood from the feet hit cave.passageCells; {@code reflooded} the capped ends the step stood at and flooded again
     * from. A CAPPED passage is priced with its share of the cells past the flood ({@link #beyondFlood}). With no passage
     * left: DONE when every cell is seen; the flood not capped - DONE, the unseen cells said unreachable by walking from
     * here (rule 7); capped, every capped end spent - DONE once explored reaches cave.doneExplored, else NO_BRANCH (not done).
     */
    public static Next next(int mined, int need, double minutesIn, double capMinutes, CaveRecord.Facts facts, List<Passages.Passage> passages,
                            List<int[]> left, int[] feet, double reach, int[] mouth, java.util.Set<Long> dropped, boolean capped, java.util.Set<Long> reflooded) {
        return next(mined, need, minutesIn, capMinutes, facts, passages, left, feet, reach, mouth, dropped, capped, reflooded, null);
    }

    /**
     * D15 pass four (run JAM 02:13:33: the lip's flood held 2 cells while the record promised 38 open passages over 21331
     * cells; NO_BRANCH twice in one second from the same feet): what the step knows past its own flood. {@code lip} the
     * flood's start; {@code floodCells} the cells its passages hold; {@code promised} the cells of the record's open
     * passages measured from its entries; {@code targets} the record's unseen passage ends (or unseen cells) off the flood,
     * nearest the feet first (cave.pocketTries at most); {@code moved} the step moved or flooded from a new cell.
     */
    public record Pocket(int[] lip, int floodCells, int promised, List<int[]> targets, boolean moved) {
        public Pocket { targets = targets == null ? List.of() : List.copyOf(targets); }
    }

    /** D15 pass four: the flood holds fewer than {@code frac} (cave.pocketFraction) of the promised cells. Pure. */
    public static boolean isPocket(int floodCells, int promised, double frac) { return promised > 0 && floodCells < frac * promised; }

    /** The pocket's next target not dropped (Baritone found no path to it, or it was reached); null: none left. */
    static int[] nextTarget(Pocket pk, java.util.Set<Long> dropped) {
        for (int[] t : pk.targets()) if (!dropped.contains(Sightings.cell(t[0], t[1], t[2]))) return t;
        return null;
    }

    /** The targets tried (dropped), "x y z; x y z" for the end's reason (rule 7). */
    static String tried(Pocket pk, java.util.Set<Long> dropped) {
        List<String> out = new java.util.ArrayList<>();
        for (int[] t : pk.targets()) if (dropped.contains(Sightings.cell(t[0], t[1], t[2]))) out.add(t[0] + " " + t[1] + " " + t[2]);
        return out.isEmpty() ? "none" : String.join("; ", out);
    }

    static Goto descent(Pocket pk, int[] t, String head) {
        int[] l = pk.lip() == null ? t : pk.lip();
        return new Goto(t, String.format(Locale.ROOT, "%s at %d %d %d is a pocket (%d cells of %d): down to the cave's nearest unseen cell at %d %d %d by Baritone",
                head, l[0], l[1], l[2], pk.floodCells(), pk.promised(), t[0], t[1], t[2]), List.of(t), Sightings.cell(t[0], t[1], t[2]), Goto.Kind.DESCENT);
    }

    /**
     * D15 pass four: as above with {@code pocket} (null: none known). A pocket (the flood under cave.pocketFraction of what
     * the record promises) is not walked as the cave: after the ore, the step asks Baritone to the record's nearest unseen
     * cell (a DESCENT goto; on arrival it floods again); every target dropped - NO_BRANCH, the cells tried said. And an end
     * NO_BRANCH with no move and no flood from a new cell ({@code pocket.moved} false) tries the next target first.
     */
    public static Next next(int mined, int need, double minutesIn, double capMinutes, CaveRecord.Facts facts, List<Passages.Passage> passages,
                            List<int[]> left, int[] feet, double reach, int[] mouth, java.util.Set<Long> dropped, boolean capped, java.util.Set<Long> reflooded,
                            Pocket pocket) {
        return next(mined, need, minutesIn, capMinutes, facts, passages, left, feet, reach, mouth, dropped, capped, reflooded, pocket, null);
    }

    /** Job-price: as above, {@code density} the ore per unseen cell the passages are priced by (null: {@link Density#notKnown}). */
    public static Next next(int mined, int need, double minutesIn, double capMinutes, CaveRecord.Facts facts, List<Passages.Passage> passages,
                            List<int[]> left, int[] feet, double reach, int[] mouth, java.util.Set<Long> dropped, boolean capped, java.util.Set<Long> reflooded,
                            Pocket pocket, Density density) {
        if (dropped == null) dropped = java.util.Set.of();
        Next n = nextCore(mined, need, minutesIn, capMinutes, facts, passages, left, feet, reach, mouth, dropped, capped, reflooded, pocket,
                density == null ? Density.notKnown() : density);
        if (pocket != null && !pocket.moved() && n instanceof Stop s && s.end() == End.NO_BRANCH) {
            int[] t = nextTarget(pocket, dropped);
            if (t != null) return descent(pocket, t, "no move and no new flood yet before an end: the lip");
            if (!pocket.targets().isEmpty()) return new Stop(End.NO_BRANCH, s.why() + "; Baritone found no path to the cave's unseen cells tried: " + tried(pocket, dropped));
        }
        return n;
    }

    private static Next nextCore(int mined, int need, double minutesIn, double capMinutes, CaveRecord.Facts facts, List<Passages.Passage> passages,
                                 List<int[]> left, int[] feet, double reach, int[] mouth, java.util.Set<Long> dropped, boolean capped, java.util.Set<Long> reflooded,
                                 Pocket pocket, Density density) {
        if (passages == null) passages = List.of();
        if (reflooded == null) reflooded = java.util.Set.of();
        if (need > 0 && mined >= need) return new Stop(End.NEED_MET, mined + " of " + need + " ore mined");
        if (capMinutes > 0 && minutesIn >= capMinutes)
            return new Stop(End.MINUTES, String.format(Locale.ROOT, "budget spent: %.1f min inside (cave.exploreMinutes %.0f), %d ore mined, %d passage(s) left",
                    minutesIn, capMinutes, mined, openPassages(passages)));
        int skipped = 0;
        int[] inReach = oreToMine(left, feet, reach);
        if (inReach != null) return new Mine(inReach);
        int[] near = null;
        long nd = Long.MAX_VALUE;
        for (int[] o : left) {
            if (dropped.contains(Sightings.cell(o[0], o[1], o[2]))) { skipped++; continue; }
            long d = d2(o, feet);
            if (d < nd) { nd = d; near = o; }
        }
        if (near != null) return Goto.toOre(near, "the seen ore at " + near[0] + " " + near[1] + " " + near[2]);
        // D15 pass four: a pocket lip - the record's cells are past a drop or climb the walkable flood stops at: Baritone's way
        if (pocket != null && isPocket(pocket.floodCells(), pocket.promised(), Tuning.get("cave.pocketFraction")) && !pocket.targets().isEmpty()) {
            int[] t = nextTarget(pocket, dropped);
            if (t != null) return descent(pocket, t, "the lip");
            int[] l = pocket.lip() == null ? feet : pocket.lip();
            return new Stop(End.NO_BRANCH, String.format(Locale.ROOT, "the lip at %d %d %d is a pocket (%d cells of %d) and Baritone found no path to any of the cave's unseen cells tried: %s",
                    l[0], l[1], l[2], pocket.floodCells(), pocket.promised(), tried(pocket, dropped)));
        }
        // D15: the passage of the lowest price (seconds per expected ore), its far end walked to
        double sec = Tuning.get("cave.passageSecPerCell");
        Passages.Passage best = null;
        double bp = Double.POSITIVE_INFINITY;
        int open = 0, spentN = 0, cappedOpen = 0;
        for (Passages.Passage p : passages) if (p.open() && p.endKind() == Passages.EndKind.CAPPED && !spent(p, reflooded)) cappedOpen++;
        int beyond = beyondFlood(facts, passages);
        double share = cappedOpen == 0 ? 0 : beyond / (double) cappedOpen;
        for (Passages.Passage p : passages) {
            if (!p.open()) continue;
            if (spent(p, reflooded)) { spentN++; continue; }
            open++;
            if (dropped.contains(p.key())) { skipped++; continue; }
            double pr = passagePrice(p, sec, density, p.endKind() == Passages.EndKind.CAPPED ? share : 0);
            if (pr < bp) { bp = pr; best = p; }
        }
        if (best != null) {
            int[] c = best.end();
            String capNote = best.endKind() == Passages.EndKind.CAPPED
                    ? String.format(Locale.ROOT, ", its end at the flood's cap: %.0f of the %d cells past it counted", share, beyond) : "";
            return Goto.toPassage(best, String.format(Locale.ROOT, "the passage to %d %d %d (%d layers, %d unseen cells, %d ore faces%s, %.1f s per ore expected)",
                    c[0], c[1], c[2], best.length(), best.unseen(), best.oreFaces(), capNote, bp));
        }
        // 88b28290 review 3: while the cells are read, done is the saved measure (maybe a past session's): never trusted - wait
        if (facts != null && facts.filling()) return new Wait("reading the cave's cells from the index (its first pass, CaveRecord.BUDGET a tick)");
        if (!passages.isEmpty() && skipped == 0) {
            int unseen = facts == null ? 0 : facts.unseen();
            double explored = facts == null ? 1 : facts.explored();
            if (unseen == 0)
                return new Stop(End.DONE, "every passage from here is walked (" + passages.size() + " passage(s)) and every cell of the cave is seen");
            if (!capped)
                return new Stop(End.DONE, String.format(Locale.ROOT, "every passage from here is walked (%d passage(s), the flood whole: under cave.passageCells); "
                        + "the %d unseen cell(s) of the cave (explored %d%%) are off the walkable flood - air over the floors, or past a drop or climb over cave.passageDrop %d: unreachable by walking from here",
                        passages.size(), unseen, Math.round(explored * 100), Tuning.i("cave.passageDrop")));   // structural: a percent
            double frac = Tuning.get("cave.doneExplored");
            String why = String.format(Locale.ROOT, "the flood hit cave.passageCells and every capped end (%d) was flooded again from; explored %d%% (%d cell(s) unseen)",
                    spentN, Math.round(explored * 100), unseen);                                   // structural: a percent
            if (explored >= frac) return new Stop(End.DONE, why + ", at least cave.doneExplored " + frac);
            return new Stop(End.NO_BRANCH, why + ", under cave.doneExplored " + frac + ": not done, no passage leads on from here");
        }
        if (facts != null && facts.done()) return new Stop(End.DONE, "the cave is explored to its end (the record: its passages measured, none open)");
        if (facts != null && !facts.loaded()) return new Stop(End.NOT_LOADED, "the cave's cells are not loaded (its chunks: the record cannot say where to go)");
        // pass two, the lip: no passage from the feet (no walkable cave cell within cave.passageLipNear) - the mouth, said
        if (passages.isEmpty() && mouth != null && !dropped.contains(Sightings.cell(mouth[0], mouth[1], mouth[2])))
            return new Goto(mouth, "the cave's mouth at " + mouth[0] + " " + mouth[1] + " " + mouth[2] + " (no passage from the feet: no walkable cave cell within cave.passageLipNear)");
        if (passages.isEmpty() && mouth != null) skipped++;
        if (skipped > 0) return new Stop(End.NO_BRANCH, "no reachable passage (" + skipped + " dropped)");
        return new Stop(End.NO_PROGRESS, "no open passage from here and no mouth to walk to, yet the record is not done");
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

    /**
     * FK fix: the stop's reason is a stall verdict (Brain / StepMonitor / RunJob), not an owner's or the run's end. Review
     * (rule 23): the words are the writers' own constants ({@link io.github.mojolowjo.entropybot.aa.moves.FactChange#isStall}).
     */
    public static boolean stallStop(String why) { return io.github.mojolowjo.entropybot.aa.moves.FactChange.isStall(why); }

    /**
     * D12's measure on the step's end line: "cave: the caving step at x y z ended - NEED_MET (3 of 3 ore mined): 2.4 min
     * inside, cells seen 40 -> 120 of 200 (explored 60%), ore seen 4 / mined 3, passages left 2". D15: {@code passagesLeft}
     * the open passages at the last pick (-1: not measured).
     */
    public static String endLine(String where, End end, String why, double minutes, int seenBefore, CaveRecord.Facts after, int oreSeen, int mined, int passagesLeft) {
        return endLine(where, end, why, minutes, seenBefore, after, oreSeen, mined, passagesLeft, -1, -1);
    }

    /**
     * D15 pass three: as above, with the passage cells looked at (in the eyes' cone on the walk) against those walked past
     * unseen at the far ends ({@code looked} -1: not counted).
     */
    public static String endLine(String where, End end, String why, double minutes, int seenBefore, CaveRecord.Facts after, int oreSeen, int mined, int passagesLeft,
                                 int looked, int walkedPast) {
        int seenNow = after == null ? seenBefore : after.cellCount() - after.unseen();
        return String.format(Locale.ROOT, "cave: the caving step at %s ended - %s (%s): %.1f min inside, cells seen %d -> %d of %d (explored %d%%), ore seen %d / mined %d, passages left %s",
                where, end, why, minutes, seenBefore, seenNow, after == null ? 0 : after.cellCount(), after == null ? 0 : Math.round(after.explored() * 100), oreSeen, mined,   // structural: a percent
                passagesLeft < 0 ? "not measured" : String.valueOf(passagesLeft))
                + (looked < 0 ? "" : ", passage cells looked at " + looked + " / walked past unseen " + walkedPast);
    }

    private static long d2(int[] a, int[] b) {
        long dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }
}
