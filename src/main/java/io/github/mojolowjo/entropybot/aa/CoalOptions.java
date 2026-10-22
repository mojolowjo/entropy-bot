package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Discovery;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.moves.Step;

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
 * <li><b>charcoal</b>: logs smelted in a furnace (the missing logs cut first, then the smelt); the missing logs are cut
 *     ({@link WoodOptions#CUT_LOG} each, {@link WoodOptions#SEARCH} to find trees when none is known), the smelt costs
 *     {@value #SMELT_ITEM} per item. No furnace known: a furnace craft (8 cobblestone, collected first when short) is prepended and priced, so charcoal can win early.</li>
 * </ul>
 * Charcoal counts as coal everywhere the bag does (SenseRuntime: coal + charcoal), so the stock goal and the torches take
 * it. Torches whose coal is missing run the best row first ({@link #torchSteps}), never a bare "can't craft". A failed
 * row is held per option ({@link Options#RETRY_MS}) in {@link Memory}; zero gained stays a failure (AaRuntime.poll).
 * Pure (JUnit: CoalOptionsTest). Loader notes: none (the facts come from {@code AaRuntime.coalFacts}).
 */
public final class CoalOptions {
    private CoalOptions() {}

    /** Blackboard fact: an {@code IntFunction<CoalFacts>} (the need in, the facts out; null result: not readable). */
    public static final String FACTS = "coal.facts", MEM_FACT = "coal.mem";
    public static double mineOre() { return Tuning.get("coalOptions.mineOre"); }
    public static double smeltItem() { return Tuning.get("coalOptions.smeltItem"); }
    public static double furnaceTrip() { return Tuning.get("coalOptions.furnaceTrip"); }
    public static double furnaceMake() { return Tuning.get("coalOptions.furnaceMake"); }
    public static int digMax() { return Tuning.i("coalOptions.digMax"); }
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
                            int logs, int planks, boolean furnaceInBag, int furnaceDist, boolean treesKnown, int cobble, double digBlock) {
        /** aa-times: {@code digBlock} = walk-blocks per stone block dug (the game's break time through Unit); this one keeps {@link IronOptions#DIG_BLOCK}. */
        public CoalFacts(int need, int pickTier, List<IronOptions.Ore> ores, List<Discovery.Opt> discovery, List<String> discoveryDropped,
                         int logs, int planks, boolean furnaceInBag, int furnaceDist, boolean treesKnown, int cobble) {
            this(need, pickTier, ores, discovery, discoveryDropped, logs, planks, furnaceInBag, furnaceDist, treesKnown, cobble, IronOptions.digBlockV());
        }

        public CoalFacts withNeed(int n) { return new CoalFacts(n, pickTier, ores, discovery, discoveryDropped, logs, planks, furnaceInBag, furnaceDist, treesKnown, cobble, digBlock); }

        public double digPerBlock() { return digBlock > 0 && Double.isFinite(digBlock) ? digBlock : IronOptions.digBlockV(); }
    }

    /** Logs a charcoal batch burns: one per item and a log of planks-fuel (6 items) per 6 smelted. */
    public static int logsFor(int need) { return need + (need + 5) / 6; }  // structural: recipe arithmetic: one fuel log per 6 items smelted, rounded up

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
                else if (pick == null && (o.openFace() || (o.loaded() && o.depth() <= digMax()))) pick = o;
            }
            if (pick == null) dropped.add("seen coal: " + (f.ores().isEmpty() ? "no coal ore known yet" : "none of the " + f.ores().size() + " seen can be taken"));
            else {
                String key = "seen coal " + pick.x() + " " + pick.y() + " " + pick.z();
                if (m.blocked(key, now)) dropped.add(key + ": " + m.reason(key));
                else {
                    double dig = pick.openFace() ? 0 : Math.max(1, pick.depth()) * f.digPerBlock();      // aa-times: the game's dig term
                    // rm-execution2: the priced ore is the step's target (that cell and its vein; the need picks again for more)
                    rows.add(Options.typed("seen coal", "coal", List.of(new Step.MineAt(FAMILY, pick.x(), pick.y(), pick.z(), !pick.openFace())),
                            pick.dist() + dig + mineOre() * need + IronOptions.clear(),
                            "coal seen " + Math.round(pick.dist()) + " away" + (pick.openFace() ? ", an open face" : ", dig " + pick.depth() + " deep"), key, m)
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
                Options.Option r = Options.typed(o.label() + " for coal", "coal", o.steps(), o.cost() + mineOre() * (need - 1), o.why(), key, m);
                rows.add(o.site() == null ? r : r.withSite(io.github.mojolowjo.entropybot.aa.brain.Site.of(o.site())));
            }
        }
        // 3. charcoal: logs smelted
        int want = logsFor(need), have = f.logs() + f.planks() / 4, missing = Math.max(0, want - have);  // game fact: a log makes 4 planks
        boolean noFurnace = !f.furnaceInBag() && f.furnaceDist() < 0;
        int cobbleLack = Math.max(0, IronOptions.furnaceCobble() - f.cobble());
        if (noFurnace && cobbleLack > 0 && f.pickTier() < 1) dropped.add("charcoal: no furnace and no pickaxe for the " + IronOptions.furnaceCobble() + " cobblestone to make one");
        else if (missing > 0 && !f.treesKnown()) dropped.add("charcoal: " + want + " logs wanted, " + have + " in the bag and no tree seen to cut the other " + missing + "; the wood option set finds trees first");
        else if (m.blocked("charcoal", now)) dropped.add("charcoal: " + m.reason("charcoal"));
        else {
            double trip = f.furnaceInBag() || f.furnaceDist() < 0 ? 0 : furnaceTrip() * f.furnaceDist();
            // the station prerequisite, prepended: no furnace known -> craft one (8 cobblestone, gathered first when short); the smelt places it from the bag
            double station = noFurnace ? furnaceMake() + cobbleLack * mineOre() : 0;
            List<Step> st = new ArrayList<>();
            if (noFurnace && cobbleLack > 0) st.add(new Step.Collect("cobblestone", IronOptions.furnaceCobble()));
            if (noFurnace) st.add(new Step.Craft("furnace", 1));
            if (missing > 0) st.add(new Step.ChopTree(null, missing, null));
            st.add(new Step.Smelt("charcoal", need, false));
            double cut = missing == 0 ? 0 : WoodOptions.cutLog() * missing + (f.treesKnown() ? 0 : WoodOptions.search());
            rows.add(Options.typed("charcoal", "coal", st, trip + station + cut + smeltItem() * need + Tuning.i("coalOptions.charcoalExtraS"),
                    want + " logs (" + have + " in the bag" + (missing > 0 ? ", cut " + missing + (f.treesKnown() ? "" : ", no tree seen yet") : "") + "), smelt " + need
                            + (noFurnace ? ", first a furnace (" + (cobbleLack > 0 ? cobbleLack + " cobblestone short, " : "") + "make it)" : f.furnaceInBag() ? " at a placed furnace" : ", a furnace " + f.furnaceDist() + " away"), "charcoal", m));
        }        return Options.Choice.of(rows, dropped, "no way to coal");
    }

    // ---- what ran, and what a failure is charged to ----

    /** The failures of the coal ways (held 10 min each) and the command each pick ran under (a failed end finds its option by it). */
    public static final class Memory {
        public final Options.OptionMemory held = new Options.OptionMemory();
        final Map<String, String> ran = new HashMap<>();
    }

    /** The failure record's desire id the coal ways are held under (aa why: "held: coal/charcoal ..."). */
    public static final String MEM_DESIRE = "coal";

    public static Memory memory(Blackboard bb) {
        Memory m = bb.fact(MEM_FACT) instanceof Memory x ? x : null;
        if (m == null) {
            m = new Memory();
            bb.fact(MEM_FACT, m);
        }
        // review 2 (4): a view over the brain's one failure record (no record of its own once a brain runs)
        if (bb.fact(io.github.mojolowjo.entropybot.aa.brain.Brain.FAILURES_FACT) instanceof io.github.mojolowjo.entropybot.aa.brain.Failures f) m.held.bind(f, MEM_DESIRE);
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
                mem.held.failed(e.getValue(), end == null ? "ended failed without an end text while running " + chain : end, bb.clock.now(), bb.in);
                bb.port.log("coal way " + e.getValue() + " failed (\"" + end + "\"), held " + Options.retryMs() / 60000 + " min; the next way runs");  // structural: milliseconds per minute
                return;
            }
    }

    /** Coal for the torches: one coal makes 4. */
    public static int coalForTorches(int have, int target) { return Math.max(1, (Math.max(0, target - have) + 3) / 4); }  // game fact: a coal makes 4 torches (rounded up)

    /** The coal way's steps first, then the torches' craft (never a bare craft without its input). */
    public static List<Step> torchSteps(List<Step> coal, int target) { return Options.seq(coal, new Step.Craft("torch", target)); }

    /** Why the torches cannot start (no coal in the bag and no way to get any now, every reason), else null. */
    public static String torchBlock(Blackboard bb, int target) {
        if (bb.in.coal >= 1) return null;
        Options.Choice c = plan(bb, coalForTorches(bb.in.torches, target));
        return c == null || c.best() != null ? null : "no way to coal for the torches (" + c.why() + ")";
    }

    /**
     * Torches short of coal: the steps that price their missing input through the coal set ({@code planned} the planner's
     * routine, null none), or {@code planned} unchanged when coal is in the bag, the routine does more than the torch
     * craft (it gets the coal itself), or no facts are readable. No runnable way: null (the caller logs the choice's
     * reasons; the cooldown holds the retry).
     */
    public static List<Step> torchesFirst(Blackboard bb, List<Step> planned, int target, String[] whyOut) {
        if (bb.in.coal >= 1) return planned;
        if (planned != null && !(planned.size() == 1 && planned.get(0) instanceof Step.Craft k && k.item().endsWith("torch"))) return planned;
        Options.Choice c = plan(bb, coalForTorches(bb.in.torches, target));
        if (c == null) return planned;
        if (whyOut != null) whyOut[0] = c.why();
        if (c.best() == null) return null;
        return torchSteps(c.best().steps(), target);
    }

    /**
     * Round 4A N: the torches need's rows. Coal in the bag: the craft (ToolWear.CRAFT_S); none: each coal way of
     * {@link #plan} then the craft (its price + the craft's); the planner's chain at its cost x planUnit. A failed coal
     * way is held by {@link #noteEnd} (the adapter calls it on every end); the craft and planner rows here.
     */
    public static final class Torches implements Need.Picker {
        public static int target() { return (int) Tuning.get("torch.target"); }
        public final Options.OptionMemory memory = new Options.OptionMemory();
        private Options.Choice last;
        private Options.Option ran;
        private String why = "no choice yet";
        private int switches;
        private boolean switching;

        public Options.Choice choose(Blackboard bb, List<Step> planned, double plannedCost, String plannerWhy) {
            long now = bb.clock.now();
            List<Options.Option> rows = new ArrayList<>();
            List<String> dropped = new ArrayList<>();
            double craft = io.github.mojolowjo.entropybot.aa.brain.Unit.blocks(io.github.mojolowjo.entropybot.aa.brain.ToolWear.craftSV());
            String craftWhy = "the craft " + Math.round(io.github.mojolowjo.entropybot.aa.brain.ToolWear.craftSV()) + " s";
            int want = target();
            if (bb.in.coal >= 1) add(rows, dropped, now, new Options.Option("craft", "craft", List.of(new Step.Craft("torch", want)), craft, "coal in the bag: " + craftWhy, "torch craft", false));
            else {
                Options.Choice c = plan(bb, coalForTorches(bb.in.torches, target()));
                if (c == null) dropped.add("coal ways: no coal facts readable");
                else {
                    for (Options.Option r : c.ranked())
                        rows.add(new Options.Option("coal " + r.id() + " + craft", "coal", torchSteps(r.steps(), target()), r.total() + craft,
                                r.why() + " " + Math.round(r.total()) + " + " + craftWhy, "coal " + r.memoryKey(), false, null, r.site()));
                    for (String d : c.dropped()) dropped.add("coal " + d);
                }
                // ruling (a): no tools need; the dig of the coal ore pulls in the lowest pickaxe its block asks for (the recipe data, NeedChain)
                var world = io.github.mojolowjo.entropybot.aa.brain.Goals.world(bb);
                if (world == null) dropped.add("chain torch: no world facts (the senses are not ready)");
                else {
                    NeedChain.Chain ch = NeedChain.resolve("torch", want, world);
                    if (!ch.ok()) dropped.add("chain torch: " + ch.missing());
                    else if (ch.explore()) dropped.add("chain torch: something it needs is not seen yet (the planner row looks for it)");
                    else if (!ch.steps().isEmpty()) add(rows, dropped, now, Options.typed("chain torch", "chain", ch.steps(), ch.cost(), ch.why(), "chain torch", memory));
                }
            }
            if (planned != null) add(rows, dropped, now, new Options.Option("planner", "planner", planned, plannedCost * Options.planUnit(),
                    "the planner's torch " + target() + ": plan cost " + Math.round(plannedCost) + " x planUnit " + Options.planUnit(), "planner", true));
            else if (plannerWhy != null) dropped.add("planner: " + plannerWhy);
            return Options.Choice.of(rows, dropped, "no way to torches");
        }

        private void add(List<Options.Option> rows, List<String> dropped, long now, Options.Option o) {
            if (memory.blocked(o.memoryKey(), now)) dropped.add(o.id() + ": " + memory.reason(o.memoryKey()));
            else rows.add(o);
        }

        @Override public String pick(Blackboard bb, List<Step> planned) { return pick(bb, planned, 0, null); }

        @Override public Options.Option ranOption() { return ran; }

        @Override public String pick(Blackboard bb, List<Step> planned, double plannedCost, String plannerWhy) {
            if (!switching) switches = 0;
            switching = false;
            memory.sweep(bb.in);
            last = choose(bb, planned, plannedCost, plannerWhy);
            why = last.why();
            ran = last.best();
            return ran == null ? null : ran.command();
        }

        @Override public boolean failed(Blackboard bb, String end) {
            if (ran == null || end == null || Options.ownerOrDanger(end) || switches >= Options.maxSwitches()) return false;
            if (!ran.way().equals("coal")) memory.failed(ran.memoryKey(), end, bb.clock.now(), bb.in);       // a coal way: held by noteEnd
            switches++;
            switching = true;
            return true;
        }

        @Override public void charge(Blackboard bb, String end) {
            if (ran != null && !ran.way().equals("coal")) memory.failed(ran.memoryKey(), Options.endWhy(ran, end), bb.clock.now(), bb.in);
        }

        @Override public void stalled(Blackboard bb, String w) { charge(bb, w); }

        @Override public void bind(io.github.mojolowjo.entropybot.aa.brain.Failures f, String need) { memory.bind(f, need); }

        @Override public String why() { return why; }

        @Override public void chose(String command) {
            if (last == null || command == null) return;
            ran = null;
            for (Options.Option o : last.ranked()) if (command.equals(o.command())) ran = o;
        }

        @Override public List<AaManual.Row> rows() {
            List<AaManual.Row> out = new ArrayList<>();
            if (last != null) for (Options.Option o : last.ranked()) out.add(new AaManual.Row(o.text(), o.command()));
            return out;
        }

        @Override public String chosenId() { return ran == null ? null : ran.id(); }

        @Override public double lastCostS() { return ran == null ? Double.NaN : ran.seconds(); }

        @Override public io.github.mojolowjo.entropybot.aa.brain.Site lastSite() { return ran == null ? null : ran.site(); }

        @Override public String counters(long now) { return memory.size() > 0 ? "memory: " + memory.describe(now) : null; }
    }
}

