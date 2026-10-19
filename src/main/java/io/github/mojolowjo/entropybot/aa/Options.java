package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Inputs;
import io.github.mojolowjo.entropybot.brain.FailMemory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MINING_PLAN 10 (S0, "Scoring every rung"): the option scorer every rung uses, extracted from the IRON rung's
 * (0.36.a8 IronOptions). A rung's {@link OptionSet} turns its facts (a {@code Supplier} on the blackboard, read once per
 * pick) and the planner's chain into ranked {@link Option}s with a reason each; {@link Picker} is the rung's
 * {@link Rung.Picker}: it runs the best, remembers failures per option ({@link OptionMemory}) and switches to the next
 * best on a failure the set calls switchable (at most {@link #MAX_SWITCHES} per task).
 *
 * <p>Cost unit: walk-blocks; 1 minute of work = {@link #MINUTE} blocks, a planner step-cost unit = {@link #PLAN_UNIT}
 * blocks (owner's defaults, 2026-10-18; tuned from the decision log). Score = cost x (1 + fails of that option); a
 * floor deny or a live memory block drops the row with its reason; a tie goes to the earlier option. Pure (JUnit:
 * OptionsTest, IronOptionsTest). Loader notes: none.
 */
public final class Options {
    private Options() {}

    public static final double MINUTE = 60, PLAN_UNIT = 5;
    public static final int MAX_SWITCHES = 4;
    /** A failed option is skipped this long per failure (at most an hour). */
    public static final long RETRY_MS = 10 * 60_000, RETRY_MAX_MS = 60 * 60_000;
    /** The command of an option that does nothing: the rung passes and a later rung's own walk does the job. */
    public static final String NOTHING = "(nothing)";

    /**
     * @param id           what {@code aa why}, {@code aa use} and the decision log call it ("pillar", "walk_known 1 2 3")
     * @param way          the kind of way ("pillar", "planner"...)
     * @param command      the chain to run
     * @param cost         walk-blocks, already weighted by the failures
     * @param memoryKey    what a failure of it is remembered under
     * @param needsPlanner it is the planner's chain (or a rewrite of it)
     */
    public record Option(String id, String way, String command, double cost, String why, String memoryKey, boolean needsPlanner) {
        public String text() { return id + " " + Math.round(cost) + " (" + why + ")"; }
    }

    /** The best option (null: none can run), the ranking, the dropped rows with their reasons, the {@code aa why} line. */
    public record Choice(Option best, List<Option> ranked, List<String> dropped, String line) {
        public String why() { return line; }

        /** Sorts by cost (stable: a tie keeps the earlier option) and writes the standard line. */
        public static Choice of(List<Option> rows, List<String> dropped, String none) {
            List<Option> ranked = new ArrayList<>(rows);
            ranked.sort(Comparator.comparingDouble(Option::cost));
            return new Choice(ranked.isEmpty() ? null : ranked.get(0), ranked, dropped, line(ranked, dropped, none));
        }

        public static String line(List<Option> ranked, List<String> dropped, String none) {
            StringBuilder b = new StringBuilder(ranked.isEmpty() ? none : ranked.get(0).text());
            if (ranked.size() > 1) {
                b.append("; then ");
                for (int i = 1; i < ranked.size() && i < 4; i++) b.append(i > 1 ? ", " : "").append(ranked.get(i).text());
            }
            if (!dropped.isEmpty()) b.append("; dropped: ").append(String.join(", ", dropped));
            return b.toString();
        }
    }

    /** A scored row: cost x (1 + failures), the failures in the reason. */
    public static Option scored(String id, String way, String command, double cost, String why, String key, boolean planner, OptionMemory m) {
        int fails = m.fails(key);
        return new Option(id, way, command, cost * (1 + fails), fails > 0 ? why + ", failed " + fails + "x" : why, key, planner);
    }

    // ---- the memory of what failed here ----

    /** memoryKey -> failures, skipped until, the last reason, and what would clear it. In memory only. */
    public static final class OptionMemory {
        record Rec(int fails, long until, String reason, FailMemory.Reason cause, int items, int[] pos) {}

        private final Map<String, Rec> map = new LinkedHashMap<>();

        public void failed(String key, String reason, long now) { failed(key, reason, now, null); }

        /** @param in the inputs when it failed (for {@link #sweep}); null: only the time clears it */
        public void failed(String key, String reason, long now, Inputs in) {
            Rec r = map.get(key);
            int n = r == null ? 1 : r.fails + 1;
            map.put(key, new Rec(n, now + Math.min(RETRY_MAX_MS, RETRY_MS * n), reason, FailMemory.classify(reason),
                    in == null ? -1 : items(in), in == null || in.pos == null ? null : in.pos.clone()));
        }

        public int fails(String key) {
            Rec r = map.get(key);
            return r == null ? 0 : r.fails;
        }

        public boolean blocked(String key, long now) {
            Rec r = map.get(key);
            return r != null && r.until > now;
        }

        public String reason(String key) {
            Rec r = map.get(key);
            return r == null ? null : r.reason;
        }

        public int size() { return map.size(); }

        /**
         * Drops a record whose cause is gone (FailMemory.causeGone's two rules on the inputs): NO_ITEM when the bag's
         * counted items rose, NO_PATH when the bot moved 16+ blocks. The dropped keys.
         */
        public List<String> sweep(Inputs in) {
            List<String> out = new ArrayList<>();
            for (var it = map.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                Rec r = e.getValue();
                boolean gone = r.cause == FailMemory.Reason.NO_ITEM && r.items >= 0 && items(in) > r.items
                        || r.cause == FailMemory.Reason.NO_PATH && r.pos != null && in.pos != null && moved(r.pos, in.pos) >= 16;
                if (gone && r.until > 0) {
                    // review: the block lifts, the failure count stays (it keeps weighing, as 10.1 says)
                    out.add(e.getKey() + " " + r.cause);
                    e.setValue(new Rec(r.fails, 0, r.reason, null, r.items, r.pos));
                }
            }
            return out;
        }

        static int items(Inputs in) { return in.wood + in.cobble + in.coal + in.ironIngots + in.torches + in.planks + in.foodItems + in.wool + in.pickaxes; }

        static double moved(int[] a, int[] b) {
            double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        public String describe(long now) {
            List<String> w = new ArrayList<>();
            for (Map.Entry<String, Rec> e : map.entrySet())
                w.add(e.getKey() + " x" + e.getValue().fails + (e.getValue().until > now ? " (" + Math.max(1, (e.getValue().until - now + 59_999) / 60_000) + " min left)" : ""));
            return String.join(", ", w);
        }
    }

    // ---- a rung's options ----

    /** One rung's options: facts and the planner's chain in, a ranked choice out. */
    public interface OptionSet<F> {
        /**
         * @param planned     the planner's chain as plain steps (null: the planner has none, {@code plannerWhy} says why)
         * @param plannedCost the planner's own cost (its step-cost sum)
         * @param afterFail   the failed end that made the rung pick again (a switch), null on a fresh pick
         */
        Choice choose(F facts, OptionMemory m, long now, String planned, double plannedCost, String plannerWhy, String afterFail);

        /** May the rung answer this failed end with another option (never the owner's stop, a fight, a death). */
        boolean switchable(String end);

        /** The memory key the failure is charged to (null: no switch). Default: the option that ran. */
        default String blame(Choice last, Option ran, String end) { return ran == null ? null : ran.memoryKey(); }
    }

    /** The ends that never switch: the owner's stop, a preempt, danger, a death (IronOptions.switchable's list). */
    public static boolean ownerOrDanger(String end) {
        String t = end.toLowerCase(Locale.ROOT);
        return t.contains("stopped: your") || t.contains("overridden") || t.contains("interrupted") || t.contains("attacked") || t.contains("low health") || t.contains("died");
    }

    // ---- chain text helpers every rung uses ----

    public static final class Chains {
        private Chains() {}

        private static final Pattern STEP = Pattern.compile("^stopped at step \\d+ \\(([^)]*)\\)");

        public static List<String> split(String chain) {
            List<String> out = new ArrayList<>();
            for (String s : (chain == null ? "" : chain).split("(?i)\\s+then\\s+")) if (!s.isBlank()) out.add(s.trim());
            return out;
        }

        /** The step the chain stopped at ("stopped at step 2 (mine iron 3 dig): ..."), or null. */
        public static String failedStep(String end) {
            if (end == null) return null;
            Matcher m = STEP.matcher(end);
            return m.find() ? m.group(1).trim() : null;
        }

        /** The steps with every step matching {@code step} replaced by {@code with} (null: left out); unchanged otherwise. */
        public static String rewrite(List<String> steps, Pattern step, java.util.function.Function<String, String> with) {
            List<String> out = new ArrayList<>();
            for (String s : steps) {
                String t = s.trim();
                if (step.matcher(t.toLowerCase(Locale.ROOT)).matches()) {
                    String r = with.apply(t);
                    if (r != null) out.add(r);
                } else out.add(t);
            }
            return String.join(" then ", out);
        }
    }

    // ---- the rung's picker ----

    /** Per rung counters for aa status. */
    public static final class Counters {
        public int picks, switches, factErrors, none;
        public long lastFactErrorAt = -1, lastPickUs;
        public final Map<String, Integer> drops = new LinkedHashMap<>();

        public String text() {
            List<Map.Entry<String, Integer>> d = new ArrayList<>(drops.entrySet());
            d.sort((a, b) -> b.getValue() - a.getValue());
            StringBuilder b = new StringBuilder("picks ").append(picks).append(", switches ").append(switches).append(", none ").append(none);
            if (factErrors > 0) b.append(", facts errors ").append(factErrors);
            b.append(", last pick ").append(lastPickUs).append(" us");
            if (!d.isEmpty()) {
                b.append(", drops: ");
                for (int i = 0; i < d.size() && i < 5; i++) b.append(i > 0 ? ", " : "").append(d.get(i).getKey()).append(" x").append(d.get(i).getValue());
            }
            return b.toString();
        }
    }

    /**
     * A rung's {@link Rung.Picker} over an {@link OptionSet}: facts from the blackboard supplier under {@code factsKey}
     * (none or unreadable: the planner's chain as it is), the best option out, a switchable failure charged to the
     * option that ran and the next best at once. {@code aa use} (AaManual) forces a ranked option by its id.
     */
    public static class Picker<F> implements Rung.Picker {
        public final String rung, factsKey;
        private final Class<F> type;
        private final OptionSet<F> set;
        public final OptionMemory memory = new OptionMemory();
        public final Counters counters = new Counters();
        private Choice last;
        private Option ran;
        private String why = "no choice yet", lastEnd;
        private int switches;
        private boolean switching;
        private final java.util.function.Consumer<String> errors;

        /**
         * @param errors gets "facts: ..." when the supplier threw (the adapter logs it rate-limited); may be null
         */
        public Picker(String rung, String factsKey, Class<F> type, OptionSet<F> set, java.util.function.Consumer<String> errors) {
            this.rung = rung;
            this.factsKey = factsKey;
            this.type = type;
            this.set = set;
            this.errors = errors;
        }

        public Picker(String rung, String factsKey, Class<F> type, OptionSet<F> set) { this(rung, factsKey, type, set, null); }

        private java.util.function.BiFunction<Blackboard, String, String> noFacts = (b, planned) -> planned;

        /** What runs without facts (default: the planner's chain as it is); the TOOLS rung keeps its old bootstrap-at-tier-0. */
        public Picker<F> withoutFacts(java.util.function.BiFunction<Blackboard, String, String> f) {
            noFacts = f;
            return this;
        }

        @Override public String pick(Blackboard bb, String planned) { return pick(bb, planned, 0, null); }

        @Override public String pick(Blackboard bb, String planned, double plannedCost, String plannerWhy) {
            long t0 = System.nanoTime();
            try {
                return pick0(bb, planned, plannedCost, plannerWhy);
            } finally {
                counters.lastPickUs = (System.nanoTime() - t0) / 1000;
            }
        }

        private String pick0(Blackboard bb, String planned, double plannedCost, String plannerWhy) {
            Object sup = bb.fact(factsKey);
            if (!switching) switches = 0;
            String after = switching ? lastEnd : null;
            switching = false;
            counters.picks++;
            long now = bb.clock.now();
            ran = null;
            if (!(sup instanceof Supplier<?> s)) {
                why = "no " + rung.toLowerCase(Locale.ROOT) + " facts from the adapter: the planner's chain as it is";
                last = null;
                return noFacts.apply(bb, planned);
            }
            Object fo;
            try {
                fo = s.get();
            } catch (RuntimeException e) {
                // PLANNING 3: logged once a minute (the first in full), counted for aa status (red for a minute)
                if (counters.lastFactErrorAt < 0 || now - counters.lastFactErrorAt >= 60_000) bb.port.log(rung + " facts: " + e);
                counters.factErrors++;
                counters.lastFactErrorAt = now;
                if (errors != null) errors.accept(rung + " facts: " + e);
                fo = null;
            }
            if (!type.isInstance(fo)) {
                why = rung.toLowerCase(Locale.ROOT) + " facts not readable now: the planner's chain as it is";
                last = null;
                return noFacts.apply(bb, planned);
            }
            memory.sweep(bb.in);
            Choice c = set.choose(type.cast(fo), memory, now, planned, plannedCost, plannerWhy, after);
            last = c;
            why = c.why();
            for (String d : c.dropped()) {
                int k = d.indexOf(':');
                counters.drops.merge(k > 0 ? d.substring(k + 1).trim() : d, 1, Integer::sum);
            }
            ran = c.best();
            AaManual man = AaManual.of(bb);
            String use = man.useFor(bb.fact(Rung.CURRENT_RUNG) instanceof String r ? r : null);
            if (use != null) {
                Option forced = null;
                String u = use.toLowerCase(Locale.ROOT);
                for (Option o : c.ranked()) if (o.id().toLowerCase(Locale.ROOT).contains(u) || o.way().toLowerCase(Locale.ROOT).contains(u)) { forced = o; break; }
                man.used(rung, forced == null ? null : forced.id(), ran == null ? "none" : ran.id(), bb.port::log);
                if (forced != null) ran = forced;
            }
            if (ran == null) {
                counters.none++;
                return null;
            }
            // "nothing" (the rung passes) is tried once: if the rung comes back within the retry time, a real way runs
            if (NOTHING.equals(ran.command())) memory.failed(ran.memoryKey(), "passed once already", now);
            return ran.command();
        }

        @Override public boolean failed(Blackboard bb, String end) {
            if (last == null || ran == null || end == null || !set.switchable(end) || switches >= MAX_SWITCHES) return false;
            String key = set.blame(last, ran, end);
            if (key == null) return false;
            memory.failed(key, end, bb.clock.now(), bb.in);
            switches++;
            counters.switches++;
            switching = true;
            lastEnd = end;
            return true;
        }

        @Override public void chose(String command) {
            if (last == null || command == null) return;
            for (Option o : last.ranked()) if (command.equals(o.command())) { ran = o; return; }
            ran = null;         // review: not in the newest ranking: a failure is charged to nothing rather than the wrong option
        }

        @Override public List<AaManual.Row> rows() {
            List<AaManual.Row> out = new ArrayList<>();
            if (last == null) return out;
            if (ran != null) out.add(new AaManual.Row(ran.text(), ran.command()));
            for (Option o : last.ranked()) if (o != ran) out.add(new AaManual.Row(o.text(), o.command()));
            return out;
        }

        @Override public String chosenId() { return ran == null ? null : ran.id(); }

        @Override public String why() { return why; }

        @Override public String counters(long now) { return counters.text() + (memory.size() > 0 ? "; memory: " + memory.describe(now) : ""); }

        @Override public boolean factsFailedSince(long since) { return counters.lastFactErrorAt >= since; }

        public Choice last() { return last; }

        public Option ran() { return ran; }

        public int switches() { return switches; }

        public OptionSet<F> set() { return set; }
    }
}
