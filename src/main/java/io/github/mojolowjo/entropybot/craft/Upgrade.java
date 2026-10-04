package io.github.mojolowjo.entropybot.craft;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntUnaryOperator;

import static io.github.mojolowjo.entropybot.craft.CraftPlanner.shortId;

/**
 * Package E (TO-LOOK-AT-LATER 15): the "upgrade &lt;essence&gt; [n]" verb's game-free part. Mystical Agriculture's
 * essence tiers go inferium -&gt; prudentium -&gt; tertium -&gt; imperium -&gt; supremium, each a shaped plus of 4 of the tier
 * below around an infusion crystal (kept: it only loses durability). The planner (package D) already plans the climb
 * bottom-up with the crystal kept; {@link GridLoop} crafts 64 a cell with the crystal in its cell (one shift-click a
 * batch). What this adds: the names, the refusals, and rounds sized to the bag (1 supremium = 256 inferium = 4 stacks,
 * so 4 supremium from essence don't fit next to everything else; the owner's hand run had to go in chunks).
 */
public final class Upgrade {
    private Upgrade() {}

    /** The tiers, lowest first ("insanium" only exists with Mystical Agradditions). */
    public static final List<String> TIERS = List.of("inferium", "prudentium", "tertium", "imperium", "supremium", "insanium");
    /** At most this many in one "upgrade" (the planner caps counts anyway; this keeps a typo from planning for minutes). */
    public static final int MAX_N = 4096;
    /** A stack of essence, and of essence blocks. */
    public static final int STACK = 64;
    /** Bag slots kept free in a round besides what the round needs (a pickup, a stray drop). */
    public static final int MARGIN = 1;

    public static final String USAGE = "usage: upgrade <essence> [n] - e.g. upgrade imperium 4 (makes 4 imperium_essence from the tiers below "
            + "in my bag, chests and RS network; the infusion crystal is kept). Tiers: prudentium, tertium, imperium, supremium";

    /** "upgrade imperium 4" -> the essence's id, its tier (index into {@link #TIERS}) and n; or the error to answer. */
    public record Parsed(String id, int tier, int n, String error) {
        static Parsed fail(String why) { return new Parsed(null, -1, 0, why); }

        public boolean ok() { return error == null; }
    }

    /** The tier of an essence id ("mysticalagriculture:tertium_essence" -> 2), else -1. */
    public static int tierOf(String id) {
        if (id == null) return -1;
        String path = id.substring(id.indexOf(':') + 1);
        if (!path.endsWith("_essence")) return -1;
        return TIERS.indexOf(path.substring(0, path.length() - "_essence".length()));
    }

    /**
     * Parses "&lt;essence&gt; [n]": a tier name ("imperium"), "imperium_essence", or a full id; n defaults to 1 and is
     * capped at {@link #MAX_N}. {@code resolve} turns a name into an id the game knows (null = unknown).
     */
    public static Parsed parse(String text, java.util.function.Function<String, String> resolve) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return Parsed.fail(USAGE);
        String[] w = t.split("\\s+");
        int n = 1;
        String name = w[0];
        if (w.length >= 2) {
            if (!w[w.length - 1].matches("\\d{1,9}")) return Parsed.fail(USAGE);
            n = Integer.parseInt(w[w.length - 1]);
            name = String.join("_", java.util.Arrays.copyOf(w, w.length - 1));
        }
        if (n < 1) return Parsed.fail("error: upgrade needs a count of 1 or more");
        n = Math.min(n, MAX_N);
        String path = name.substring(name.indexOf(':') + 1);
        String bare = path.endsWith("_essence") ? path.substring(0, path.length() - "_essence".length()) : path;
        int tier = TIERS.indexOf(bare);
        if (tier < 0) return Parsed.fail("error: " + name + " is not an essence tier I know - " + USAGE.replaceFirst("^usage: ", "use: "));
        if (tier == 0) return Parsed.fail("error: inferium is the lowest tier - name the tier to make, e.g. upgrade prudentium 64 (4 inferium each)");
        String id = name.indexOf(':') >= 0 ? name : resolve.apply(bare + "_essence");
        if (id == null) return Parsed.fail("error: I don't know " + bare + "_essence here (is it in this pack?)");
        return new Parsed(id, tier, n, null);
    }

    /** How many of the tier {@code from} one of the tier {@code to} takes: 4 a step. */
    public static long perUnit(int to, int from) {
        long p = 1;
        for (int i = from; i < to; i++) p *= 4;
        return p;
    }

    /** Grid crafts for {@code k} of tier {@code to} from tier {@code from}: k * (4^gap - 1) / 3 (1 supremium from inferium: 85). */
    public static long crafts(long k, int to, int from) {
        long c = 0, p = 1;
        for (int i = to; i > from; i--) {
            c += k * p;
            p *= 4;
        }
        return c;
    }

    /**
     * The bag slots one round takes at most: what it fetches (a stack each 64), every craft step's output (the
     * intermediates and the essence unpacked from blocks; an over-count, as each one is used up while the next is
     * made), and {@link #MARGIN}. {@code fetched} = what the round takes from the chests and the RS network.
     */
    public static int roundSlots(Map<String, Integer> fetched, List<Integer> craftOutputs) {
        long s = MARGIN;
        for (int v : fetched.values()) if (v > 0) s += (v + STACK - 1) / STACK;
        for (int v : craftOutputs) if (v > 0) s += (v + STACK - 1) / STACK;
        return (int) Math.min(Integer.MAX_VALUE, s);
    }

    /**
     * The most of the target one round makes: the largest k (1..{@code want}) whose {@code slotsFor(k)} fits
     * {@code free} bag slots ({@code slotsFor} returns {@link Integer#MAX_VALUE} when k can't be planned); 0 when not
     * even one fits. Assumes more takes no fewer slots (binary search).
     */
    public static int roundSize(int want, int free, IntUnaryOperator slotsFor) {
        if (want < 1) return 0;
        if (slotsFor.applyAsInt(1) > free) return 0;
        int lo = 1, hi = want;
        while (lo < hi) {
            int mid = lo + (hi - lo + 1) / 2;
            if (slotsFor.applyAsInt(mid) <= free) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    // ---- over the planner ----

    /** The crafting recipe that makes {@code id} from the tier below with a catalyst (the infusion crystal) in it, or null. */
    public static RecipeData tierUp(CraftPlanner planner, String id) {
        for (RecipeData r : planner.craftingRecipes(id)) {
            if (planner.isBreakdown(r)) continue;
            if (!crystals(planner, r).isEmpty()) return r;
        }
        return null;
    }

    /** The recipe's catalyst cell's alternatives (infusion_crystal, master_infusion_crystal...), or none. */
    public static List<String> crystals(CraftPlanner planner, RecipeData r) {
        for (Crafter.Need n : r.needs()) if (planner.catalyst(n.alts())) return n.alts();
        return List.of();
    }

    /** The grid crafts a plan does with a crystal in the grid (each costs a plain crystal one use). */
    public static long crystalCrafts(CraftPlanner planner, List<Crafter.Step> steps) {
        long n = 0;
        for (Crafter.Step s : steps) {
            if (!(s instanceof Crafter.Craft c)) continue;
            RecipeData r = planner.recipe(c.recipeId());
            if (r != null && !crystals(planner, r).isEmpty()) n += c.times();
        }
        return n;
    }

    /**
     * The first step that takes a higher tier apart (an uncraft: supremium -&gt; 4 imperium), or null. "upgrade" only
     * builds up; the planner allows a breakdown when none of the lower tiers is anywhere, which is "craft"'s business.
     */
    public static Crafter.Craft breakdownIn(CraftPlanner planner, List<Crafter.Step> steps) {
        for (Crafter.Step s : steps) {
            if (!(s instanceof Crafter.Craft c)) continue;
            RecipeData r = planner.recipe(c.recipeId());
            if (r != null && planner.isTierBreakdown(r)) return c;
        }
        return null;
    }

    /** The tier of an essence or an essence block ("prudentium_block" -> 1), else -1. */
    public static int tierOfAny(String id) {
        if (id == null) return -1;
        String path = id.substring(id.indexOf(':') + 1);
        for (String suf : new String[]{"_essence", "_block"}) {
            if (path.endsWith(suf)) return TIERS.indexOf(path.substring(0, path.length() - suf.length()));
        }
        return -1;
    }

    /**
     * Why the plan is not a climb up to {@code target}, or null: a step that takes a higher tier apart (the planner
     * allows that when no lower tier is anywhere, which is "craft"'s business), or one that uses up the target's tier or
     * higher - e.g. unpacking the target's own block, which would count as "made".
     */
    public static String notUp(CraftPlanner planner, List<Crafter.Step> steps, String target) {
        int tt = tierOf(target);
        Crafter.Craft down = breakdownIn(planner, steps);
        if (down != null) return "it would take a higher tier apart for " + down.want() + " " + shortId(down.item());
        for (Crafter.Step s : steps) {
            if (!(s instanceof Crafter.Craft c)) continue;
            RecipeData r = planner.recipe(c.recipeId());
            if (r == null) continue;
            for (Crafter.Need n : planner.usedNeeds(r)) {
                for (String a : n.alts()) {
                    int t = tierOfAny(a);
                    if (tt >= 0 && t >= tt) return "making " + shortId(c.item()) + " would use up " + shortId(a) + " (the same tier or higher)";
                }
            }
        }
        return null;
    }

    /** A round's plan: from the bag first, else from the bag and storage with what it fetches. */
    public record RoundPlan(CraftPlanner.AllPlan plan, Map<String, Integer> fetched) {}

    /** The plan for a round of {@code k}, or null when it can't be planned. */
    public static RoundPlan roundPlan(CraftPlanner planner, String id, int k, Map<String, Integer> inv, Map<String, Integer> combined) {
        List<CraftPlanner.Target> t = List.of(new CraftPlanner.Target(id, k));
        CraftPlanner.AllPlan r = planner.planAll(t, new java.util.LinkedHashMap<>(inv), combined);
        if (r.ok()) return new RoundPlan(r, Map.of());
        Map<String, Integer> counts = new java.util.LinkedHashMap<>(combined);
        r = planner.planAll(t, counts, combined);
        return r.ok() ? new RoundPlan(r, CraftTexts.fromStorage(combined, counts, inv, r.catalysts())) : null;
    }

    /**
     * {@link #roundSlots} for a round of {@code k}; {@link Integer#MAX_VALUE} when k can't be planned or the plan is not
     * a climb up ({@link #notUp}), so the rounds stay at what can be built up.
     */
    public static int slotsFor(CraftPlanner planner, String id, int k, Map<String, Integer> inv, Map<String, Integer> combined) {
        RoundPlan rp = roundPlan(planner, id, k, inv, combined);
        if (rp == null || notUp(planner, rp.plan().steps(), id) != null) return Integer.MAX_VALUE;
        List<Integer> outs = new java.util.ArrayList<>();
        for (Crafter.Step s : rp.plan().steps()) outs.add(s.want());
        return roundSlots(rp.fetched(), outs);
    }

    /**
     * The bag slot (in menu order: inventory rows, then the hotbar) whose crystal the grid will use: {@link GridLayout}
     * picks the alternative the bag has most of (the first on a tie), {@link GridLoop} the first stack of it. -1: none.
     */
    public static int crystalSlot(List<String> menuOrder, List<String> alts) {
        String pick = null;
        int best = 0;
        for (String a : alts) {
            int have = 0;
            for (String id : menuOrder) if (a.equals(id)) have++;
            if (have > best) {
                best = have;
                pick = a;
            }
        }
        return pick == null ? -1 : menuOrder.indexOf(pick);
    }

    // ---- texts ----

    public static String notUpRefusal(int n, String id, String why) {
        return "error: I can't upgrade to " + n + " " + shortId(id) + " by building up: " + why
                + " - upgrade only climbs from the lower tiers (none of them is in my bag, chests or the RS network?)";
    }

    public static String noRecipe(String id) {
        return "error: I know no recipe that makes " + shortId(id) + " from the tier below with an infusion crystal";
    }

    /** No crystal (any of the recipe's catalyst alternatives) in the bag, the chests or the RS network. */
    public static String noCrystal(List<String> alts, boolean rsKnown) {
        String names = alts.isEmpty() ? "an infusion crystal" : String.join(" or ", alts.stream().map(CraftPlanner::shortId).toList());
        return "error: I need " + names + " to upgrade essence and there is none in my bag, my chests" + (rsKnown ? " or the RS network" : " (PM rs once so I know the RS network)")
                + " - the crystal is kept, not used up";
    }

    /** The crystal in the bag can't do the crafts: it would break halfway. */
    public static String crystalWorn(String crystal, int usesLeft, long crafts) {
        return "error: my " + shortId(crystal) + " has " + usesLeft + " uses left and this takes about " + crafts
                + " crafts - put it away and fetch a fresh one (or the master infusion crystal, which doesn't wear)";
    }

    public static String cantPlan(int n, String id, String why) {
        return "error: I can't upgrade to " + n + " " + shortId(id) + " - " + why;
    }

    /** The job's label while a round runs. */
    public static String label(int n, String id, int round, int made) {
        return "upgrading to " + n + " " + shortId(id) + (round > 0 ? " (round " + round + ", " + made + " made)" : "");
    }

    public static String bagFull(int made, int n, String id) {
        return made > 0
                ? "my inventory is too full for another round after making " + made + " of " + n + " " + shortId(id) + " - put things away (deposit) and run upgrade again"
                : "my inventory is too full to upgrade " + shortId(id) + " - put things away first (deposit)";
    }

    public static String noProgress(int made, int n, String id) {
        return "the last round made no " + shortId(id) + " (" + made + " of " + n + " so far)";
    }

    /** The end note: "made 4 imperium_essence in 2 rounds; the infusion_crystal is in my bag (812 uses left)". */
    public static String done(int made, String id, int rounds, String crystal, int usesLeft) {
        String c = crystal == null ? "" : "; the " + shortId(crystal) + " is in my bag" + (usesLeft >= 0 ? " (" + usesLeft + " uses left)" : "");
        return "made " + made + " " + shortId(id) + " in " + rounds + " round" + (rounds == 1 ? "" : "s") + c;
    }
}
