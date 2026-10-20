package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Discovery;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * three-tier round 1 (live, fair play: 7x "failed STOCK_COAL: nothing gained - mining coal: got 0 of N - no coal ore known
 * yet", 7x "failed STOCK_TORCHES: can't craft N torch - torch: missing ..."): coal is a priced option set, not one verb.
 * The ways, in the scorer's unit (walk-blocks, 1 s = 1 block, Options.MINUTE = 60):
 * <ul>
 * <li><b>mine seen coal</b>: the nearest coal I have seen that may be taken (open face: walk; else dig down to it): its
 *     distance + the dig + {@value #MINE_ORE} per ore. Needs a pickaxe.</li>
 * <li><b>discovery</b>: the ways to find coal when none is seen (explore a cave, strip at coal's learned Y), priced by
 *     {@link Discovery} (the iron rung's rows; the first ore found, then {@value #MINE_ORE} per further one).</li>
 * <li><b>charcoal</b>: logs smelted in a furnace ({@code gather charcoal N}); the missing logs are cut
 *     ({@link WoodOptions#CUT_LOG} each, {@link WoodOptions#SEARCH} to find trees when none is known), the smelt costs
 *     {@value #SMELT_ITEM} per item. Needs a furnace (in the bag or known): without one the row is dropped with the
 *     reason and the table/furnace rung makes it first (the station prerequisite).</li>
 * </ul>
 * Charcoal counts as coal everywhere the bag does (BrainRuntime: coal + charcoal), so the stock goal and the torches take
 * it. Torches whose coal is missing run the best row first ({@link #torchChain}), never a bare "can't craft". A failed
 * row is held per option ({@link Options#RETRY_MS}) in {@link Memory}; zero gained stays a failure (AaRuntime.poll).
 * Pure (JUnit: CoalOptionsTest). Loader notes: none (the facts come from {@code AaRuntime.coalFacts}).
 */
public final class CoalOptions {
    private CoalOptions() {}

    /** Blackboard fact: an {@code IntFunction<CoalFacts>} (the need in, the facts out; null result: not readable). */
    public static final String FACTS = "coal.facts", MEM_FACT = "coal.mem";
    public static final double MINE_ORE = 3, SMELT_ITEM = 10, FURNACE_TRIP = 2;
    public static final int DIG_MAX = 16;
    public static final String FAMILY = "coal";

    /**
     * @param need       coal (or charcoal) wanted
     * @param pickTier   my best pickaxe (0 none)
     * @param ores       the coal ores seen (or known, fair off), nearest first
     * @param discovery  the ways to find coal (Discovery.rank for "coal", cheapest first), their drops
     * @param logs       logs in the bag; {@code planks} planks (4 to a log)
     * @param furnaceInBag a furnace item in the bag; {@code furnaceDist} blocks to a known furnace, -1 none
     * @param treesKnown a tree has been seen (cutting the missing logs needs no search)
     */
    public record CoalFacts(int need, int pickTier, List<IronOptions.Ore> ores, List<Discovery.Opt> discovery, List<String> discoveryDropped,
                            int logs, int planks, boolean furnaceInBag, int furnaceDist, boolean treesKnown) {
        public CoalFacts withNeed(int n) { return new CoalFacts(n, pickTier, ores, discovery, discoveryDropped, logs, planks, furnaceInBag, furnaceDist, treesKnown); }
    }

    /** Logs a charcoal batch burns: one per item and a log of planks-fuel (6 items) per 6 smelted. */
    public static int logsFor(int need) { return need + (need + 5) / 6; }

    public static Options.Choice choose(CoalFacts f, Options.OptionMemory m, long now) {
        List<Options.Option> rows = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        int need = Math.max(1, f.need());
        // 1. mine coal I have seen
        if (f.pickTier() < 1) dropped.add("mine coal: no pickaxe yet");
        else {
            IronOptions.Ore pick = null;
            for (IronOptions.Ore o : f.ores()) {
                if (o.deny() != null) dropped.add("coal " + o.x() + " " + o.y() + " " + o.z() + ": " + o.deny());
                else if (o.floor() || o.wetDigDown()) dropped.add("coal " + o.x() + " " + o.y() + " " + o.z() + ": a floor or a wet dig down");
                else if (pick == null && (o.openFace() || (o.loaded() && o.depth() <= DIG_MAX))) pick = o;
            }
            if (pick == null) dropped.add("mine seen coal: " + (f.ores().isEmpty() ? "no coal ore known yet" : "none of the " + f.ores().size() + " seen can be taken"));
            else {
                String key = "mine " + pick.x() + " " + pick.y() + " " + pick.z();
                if (m.blocked(key, now)) dropped.add(key + ": " + m.reason(key));
                else {
                    double dig = pick.openFace() ? 0 : Math.max(1, pick.depth()) * IronOptions.DIG_BLOCK;
                    rows.add(Options.scored("mine seen coal", "coal", "mine coal " + need + (pick.openFace() ? "" : " dig"),
                            pick.dist() + dig + MINE_ORE * need + IronOptions.CLEAR,
                            "coal seen " + Math.round(pick.dist()) + " away" + (pick.openFace() ? ", an open face" : ", dig " + pick.depth() + " deep"), key, false, m)
                            .withSite(io.github.mojolowjo.entropybot.aa.brain.Site.at(pick.x(), pick.y(), pick.z())));
                }
            }
        }
        // 2. find coal (the iron rung's discovery rows for the coal family)
        if (f.pickTier() >= 1) {
            dropped.addAll(f.discoveryDropped());
            for (Discovery.Opt o : f.discovery()) {
                String key = "discover " + o.kind().name().toLowerCase(Locale.ROOT);
                if (m.blocked(key, now)) { dropped.add(o.label() + ": " + m.reason(key)); continue; }
                Options.Option r = Options.scored(o.label() + " for coal", "coal", o.command(), o.cost() + MINE_ORE * (need - 1), o.why(), key, false, m);
                rows.add(o.site() == null ? r : r.withSite(io.github.mojolowjo.entropybot.aa.brain.Site.of(o.site())));
            }
        }
        // 3. charcoal: logs smelted
        int want = logsFor(need), have = f.logs() + f.planks() / 4, missing = Math.max(0, want - have);
        if (!f.furnaceInBag() && f.furnaceDist() < 0) dropped.add("charcoal: no furnace yet (the table/furnace rung makes one first)");
        else if (m.blocked("charcoal", now)) dropped.add("charcoal: " + m.reason("charcoal"));
        else {
            double trip = f.furnaceInBag() || f.furnaceDist() < 0 ? 0 : FURNACE_TRIP * f.furnaceDist();
            double cut = missing == 0 ? 0 : WoodOptions.CUT_LOG * missing + (f.treesKnown() ? 0 : WoodOptions.SEARCH);
            rows.add(Options.scored("charcoal", "coal", "gather charcoal " + need, trip + cut + SMELT_ITEM * need + 10,
                    want + " logs (" + have + " in the bag" + (missing > 0 ? ", cut " + missing + (f.treesKnown() ? "" : ", no tree seen yet") : "") + "), smelt " + need
                            + (f.furnaceInBag() ? " at a placed furnace" : ", a furnace " + f.furnaceDist() + " away"), "charcoal", false, m));
        }
        return Options.Choice.of(rows, dropped, "no way to coal");
    }

    // ---- what ran, and what a failure is charged to ----

    /** The failures of the coal ways (held 10 min each) and the command each pick ran under (a failed end finds its option by it). */
    public static final class Memory {
        public final Options.OptionMemory held = new Options.OptionMemory();
        final Map<String, String> ran = new HashMap<>();
    }

    public static Memory memory(Blackboard bb) {
        if (bb.fact(MEM_FACT) instanceof Memory m) return m;
        Memory m = new Memory();
        bb.fact(MEM_FACT, m);
        return m;
    }

    /** Best way to {@code need} coal now (the choice logged by the caller), or null: no facts readable (the old verb runs). */
    public static Options.Choice plan(Blackboard bb, int need) {
        if (!(bb.fact(FACTS) instanceof IntFunction<?> sup)) return null;
        CoalFacts f;
        try {
            f = sup.apply(Math.max(1, need)) instanceof CoalFacts x ? x : null;
        } catch (RuntimeException e) {
            bb.port.log("coal facts: " + e);
            return null;
        }
        if (f == null) return null;
        Memory mem = memory(bb);
        mem.held.sweep(bb.in);
        Options.Choice c = choose(f.withNeed(Math.max(1, need)), mem.held, bb.clock.now());
        if (c.best() != null) mem.ran.put(c.best().command(), c.best().memoryKey());
        return c;
    }

    /** A task ended: a failure of a coal way is held under its option; a success clears nothing (a held row lapses by itself). */
    public static void noteEnd(Blackboard bb, String chain, String end, boolean failed) {
        if (!failed || chain == null || !(bb.fact(MEM_FACT) instanceof Memory mem)) return;
        for (Map.Entry<String, String> e : mem.ran.entrySet())
            if (chain.contains(e.getKey())) {
                mem.held.failed(e.getValue(), end == null ? "failed" : end, bb.clock.now(), bb.in);
                bb.port.log("coal way " + e.getValue() + " failed (\"" + end + "\"), held " + Options.RETRY_MS / 60000 + " min; the next way runs");
                return;
            }
    }

    /** Coal for the torches: one coal makes 4. */
    public static int coalForTorches(int have, int target) { return Math.max(1, (Math.max(0, target - have) + 3) / 4); }

    /** The coal way first, then the torches: "gather charcoal 2 then craft torch 16" (never a bare craft without its input). */
    public static String torchChain(String coalCommand, int target) { return coalCommand + " then craft torch " + target; }

    /** Why the torches cannot start (no coal in the bag and no way to get any now, every reason), else null. */
    public static String torchBlock(Blackboard bb, int target) {
        if (bb.in.coal >= 1) return null;
        Options.Choice c = plan(bb, coalForTorches(bb.in.torches, target));
        return c == null || c.best() != null ? null : "no way to coal for the torches (" + c.why() + ")";
    }

    /**
     * Torches short of coal: the chain that prices their missing input through the coal set ({@code planned} the planner's
     * chain, null none), or {@code planned} unchanged when coal is in the bag, the chain already fetches coal itself, or no
     * facts are readable. No runnable way: null (the caller logs the choice's reasons; the cooldown holds the retry).
     */
    public static String torchesFirst(Blackboard bb, String planned, int target, String[] whyOut) {
        if (bb.in.coal >= 1) return planned;
        if (planned != null && !planned.startsWith("craft torch") && !planned.startsWith("goal ")) return planned;
        Options.Choice c = plan(bb, coalForTorches(bb.in.torches, target));
        if (c == null) return planned;
        if (whyOut != null) whyOut[0] = c.why();
        if (c.best() == null) return null;
        return torchChain(c.best().command(), target);
    }
}
