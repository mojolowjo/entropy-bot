package io.github.mojolowjo.entropybot.altar;

import io.github.mojolowjo.entropybot.craft.RecipeData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import static io.github.mojolowjo.entropybot.craft.CraftPlanner.shortId;

/**
 * Package E (TO-LOOK-AT-LATER 14): the infusion altar's plan, game-free. The ground truth is the hand run of
 * 2026-10-03 (altar -23 53 156, its 8 pedestals at the fixed offsets {@link #PEDESTALS}, the oak button at -23 53 157,
 * standing at -22 53 157): the seed base goes on the altar (slot 0 = input, slot 1 = output), 4 + 4 ingredients on the
 * pedestals (one each), the button starts it (it never starts by itself), and a right-click on the altar with an empty
 * hand takes the output - or the INPUT when nothing is made yet.
 */
public final class AltarPlan {
    private AltarPlan() {}

    /** The pedestals around an altar (dx, dz), in the order the hand run filled them (x, then z). */
    public static final int[][] PEDESTALS = {{-3, 0}, {-2, -2}, {-2, 2}, {0, -3}, {0, 3}, {2, -2}, {2, 2}, {3, 0}};
    /**
     * The survival block interaction range, measured as the server's canInteractWithBlock does: eye to the block's box
     * (the server even allows 1 more). In game the player's own {@code blockInteractionRange()} is used for a click.
     */
    public static final double REACH = 4.5;
    /** A stand spot must reach every block with this much to spare (the bot may stand that far off the block's centre). */
    public static final double SLACK = 0.5;
    public static final double EYE = 1.62;
    /** Seeds in one "infuse". */
    public static final int MAX_N = 64;

    public static final String ALTAR = "infusion_altar", PEDESTAL = "infusion_pedestal";
    /** Used when the recipe hides its center (item 14 saw "recipe" leave it out): MA's seeds sit on a seed base. */
    public static final String PROSPERITY_BASE = "mysticalagriculture:prosperity_seed_base", SOULIUM_BASE = "mysticalagriculture:soulium_seed_base";

    public static final String USAGE = "usage: infuse <seed> [n] - e.g. infuse silicon 2 (on the infusion altar near me or the base: I fetch the ingredients "
            + "from my chests and the RS network, put them on the altar and pedestals, press the button and take the seed)";

    /** An infusion recipe of Mystical Agriculture's altar, by its type id ("mysticalagriculture:infusion"). */
    public static boolean infusion(RecipeData r) {
        if (r == null) return false;
        String t = r.type().toLowerCase(Locale.ROOT);
        return t.startsWith("mysticalagriculture:") && t.contains("infusion");
    }

    // ---- the recipe: what goes on the altar, what on the pedestals ----

    /** The altar's input and the pedestals' ingredients; {@code guessed}: the recipe didn't show its center. */
    public record Split(List<String> center, List<List<String>> pedestals, boolean guessed, String error) {
        public boolean ok() { return error == null; }
    }

    private static boolean seedBase(List<String> alts) {
        for (String a : alts) if (a.endsWith("seed_base")) return true;
        return false;
    }

    /**
     * Which ingredient is the altar's: a seed base where there is one, else the first of 9 (MA lists the altar's input
     * first), else (8 or fewer: the center is hidden) the soulium seed base for mob seeds (an ingredient with "chunk"
     * or "soul"), else the prosperity seed base. {@code exists} says whether an item id is registered.
     */
    public static Split split(RecipeData r, Predicate<String> exists) {
        List<List<String>> cells = new ArrayList<>();
        for (List<String> c : r.cells()) if (!c.isEmpty()) cells.add(c);
        if (cells.isEmpty()) return new Split(null, null, false, "the recipe for " + shortId(r.output()) + " has no ingredients I can see");
        int center = -1;
        for (int i = 0; i < cells.size() && center < 0; i++) if (seedBase(cells.get(i))) center = i;
        if (center < 0 && cells.size() == 9) center = 0;
        List<String> mid;
        boolean guessed = false;
        List<List<String>> peds = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) if (i != center) peds.add(cells.get(i));
        if (center >= 0) {
            mid = cells.get(center);
        } else {
            boolean mob = false;
            for (List<String> c : peds) for (String a : c) mob |= a.contains("chunk") || a.contains("soul");
            String base = mob ? SOULIUM_BASE : PROSPERITY_BASE;
            if (!exists.test(base)) return new Split(null, null, false, "I can't tell what goes on the altar for " + shortId(r.output()));
            mid = List.of(base);
            guessed = true;
        }
        if (peds.size() > PEDESTALS.length) return new Split(null, null, false, shortId(r.output()) + " needs " + peds.size() + " pedestal items, an altar has 8 pedestals");
        return new Split(mid, peds, guessed, null);
    }

    /** One item per slot for a round: the altar's and each pedestal's, and the round's totals. */
    public record Pick(String center, List<String> pedestals, Map<String, Integer> perRound) {
        public Map<String, Integer> times(int n) {
            Map<String, Integer> out = new LinkedHashMap<>();
            perRound.forEach((k, v) -> out.put(k, v * n));
            return out;
        }
    }

    private static String best(List<String> alts, Map<String, Integer> stock) {
        String pick = alts.get(0);
        int most = stock.getOrDefault(pick, 0);
        for (String a : alts) {
            int h = stock.getOrDefault(a, 0);
            if (h > most) {
                most = h;
                pick = a;
            }
        }
        return pick;
    }

    /**
     * Each ingredient's alternative the stock (bag + chests + RS) has most of (the first on a tie); the pedestals'
     * items grouped as the hand run had them (4 silicon, then 4 prudentium: the altar matches the pedestals in any order).
     */
    public static Pick pick(Split s, Map<String, Integer> stock) {
        String center = best(s.center(), stock);
        List<String> each = new ArrayList<>();
        for (List<String> c : s.pedestals()) each.add(best(c, stock));
        List<String> grouped = new ArrayList<>();
        for (String id : each) if (!grouped.contains(id)) for (String x : each) if (x.equals(id)) grouped.add(x);
        Map<String, Integer> per = new LinkedHashMap<>();
        per.merge(center, 1, Integer::sum);
        for (String id : grouped) per.merge(id, 1, Integer::sum);
        return new Pick(center, List.copyOf(grouped), per);
    }

    // ---- the place: the altar, its pedestals, the button, where to stand ----

    /** The altar's surroundings, or why it can't be used. */
    public record Layout(int[] altar, List<int[]> pedestals, int[] button, int[] stand, String error) {
        public boolean ok() { return error == null; }

        static Layout fail(int[] altar, String why) { return new Layout(altar, List.of(), null, null, why); }
    }

    public static int[] at(int[] p, int dx, int dy, int dz) { return new int[]{p[0] + dx, p[1] + dy, p[2] + dz}; }

    public static String fmt(int[] p) { return p[0] + " " + p[1] + " " + p[2]; }

    /** From a point (the eye) to the block's box (0 inside it), as the server's canInteractWithBlock measures. */
    public static double toBox(double ex, double ey, double ez, int[] b) {
        double dx = Math.max(0, Math.max(b[0] - ex, ex - (b[0] + 1))), dy = Math.max(0, Math.max(b[1] - ey, ey - (b[1] + 1))),
                dz = Math.max(0, Math.max(b[2] - ez, ez - (b[2] + 1)));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** The eye of a bot standing in the middle of block {@code stand}, to the target's box. */
    public static double fromStand(int[] stand, int[] target) {
        return toBox(stand[0] + 0.5, stand[1] + EYE, stand[2] + 0.5, target);
    }

    /** Standing anywhere on {@code stand} (up to {@link #SLACK} off its centre) the block is within {@link #REACH}. */
    public static boolean reaches(int[] stand, int[] target) {
        return fromStand(stand, target) <= REACH - SLACK;
    }

    /**
     * A spot next to the altar (the 4 diagonals, then the 4 sides, those by the button first) the bot can stand on and
     * reach every target from (the hand run stood at the altar + (1, 0, 1)), or null.
     */
    public static int[] stand(int[] altar, int[] button, List<int[]> targets, Predicate<int[]> standable) {
        int[][] cand = {{1, 1}, {-1, 1}, {1, -1}, {-1, -1}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        List<int[]> order = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            for (int[] c : cand) {
                int[] s = at(altar, c[0], 0, c[1]);
                boolean byButton = button != null && Math.abs(s[0] - button[0]) <= 1 && Math.abs(s[2] - button[2]) <= 1;
                if ((pass == 0) == byButton) order.add(s);
            }
        }
        for (int[] s : order) {
            if (button != null && s[0] == button[0] && s[1] == button[1] && s[2] == button[2]) continue;
            if (!standable.test(s)) continue;
            boolean all = true;
            for (int[] t : targets) all &= reaches(s, t);
            if (all) return s;
        }
        return null;
    }

    /** The pedestals around the altar (the fixed offsets, only where there is one), the button, where to stand. */
    public static Layout layout(int[] altar, AltarWorld w, Predicate<int[]> standable, int pedestalsNeeded) {
        String id = w.block(altar);
        if (id == null) return Layout.fail(altar, "the altar at " + fmt(altar) + " is too far away to see");
        if (!id.contains(ALTAR)) return Layout.fail(altar, "there is no infusion altar at " + fmt(altar) + " (it is " + shortId(id) + ")");
        List<int[]> peds = new ArrayList<>();
        for (int[] o : PEDESTALS) {
            int[] p = at(altar, o[0], 0, o[1]);
            String b = w.block(p);
            if (b != null && b.contains(PEDESTAL)) peds.add(p);
        }
        if (peds.size() < pedestalsNeeded) {
            return Layout.fail(altar, "the altar at " + fmt(altar) + " has " + peds.size() + " pedestals and this needs " + pedestalsNeeded);
        }
        int[] button = null;
        long bestD = Long.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int[] p = at(altar, dx, dy, dz);
                    String b = w.block(p);
                    long d = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                    if (b != null && b.contains("button") && d < bestD) {
                        bestD = d;
                        button = p;
                    }
                }
            }
        }
        if (button == null) {
            return Layout.fail(altar, "there is no button next to the altar at " + fmt(altar) + " - it starts on a redstone pulse; put a button on it (within 2 blocks)");
        }
        List<int[]> targets = new ArrayList<>(peds.subList(0, Math.max(0, Math.min(peds.size(), pedestalsNeeded))));
        targets.add(altar);
        targets.add(button);
        // every pedestal may hold something to take back, so reach all of them
        for (int[] p : peds) if (!targets.contains(p)) targets.add(p);
        int[] stand = stand(altar, button, targets, standable);
        if (stand == null) return Layout.fail(altar, "there is no spot next to the altar at " + fmt(altar) + " where I can stand and reach the pedestals and the button");
        return new Layout(altar, List.copyOf(peds), button, stand, null);
    }

    // ---- what is on it now ----

    /** One slot the run fills: the altar's input (slot 0) or a pedestal's. */
    public record Target(int[] pos, String item, boolean altar) {}

    /** The altar first, then the pedestals in order, each with its item. */
    public static List<Target> targets(Layout l, Pick p) {
        List<Target> out = new ArrayList<>();
        out.add(new Target(l.altar(), p.center(), true));
        for (int i = 0; i < p.pedestals().size(); i++) out.add(new Target(l.pedestals().get(i), p.pedestals().get(i), false));
        return out;
    }

    /**
     * The altar as it is: {@code foreign} = why the bot won't touch it (something that isn't the bot's own), else the
     * bot's own items to take back first ({@code retrieve}: noted as its own and not what this round wants there), the
     * targets already holding the right item of the bot's ({@code ready}), and whether the output is the bot's seed
     * from a run that was stopped ({@code ownOutput}).
     */
    public record Survey(String foreign, List<int[]> retrieve, List<int[]> ready, boolean ownOutput, String outputId) {}

    public static Survey survey(Layout l, List<Target> targets, AltarWorld w, AltarMemory mem) {
        List<int[]> retrieve = new ArrayList<>(), ready = new ArrayList<>();
        Map<Integer, AltarWorld.Stack> alt = w.items(l.altar());
        if (alt == null) return new Survey("I can't read what is on the altar at " + fmt(l.altar()), retrieve, ready, false, null);
        List<int[]> all = new ArrayList<>();
        all.add(l.altar());
        all.addAll(l.pedestals());
        List<Map<Integer, AltarWorld.Stack>> seen = new ArrayList<>();
        for (int[] pos : all) {
            Map<Integer, AltarWorld.Stack> items = pos == l.altar() ? alt : w.items(pos);
            if (items == null) return new Survey("I can't read what is on the pedestal at " + fmt(pos), retrieve, ready, false, null);
            seen.add(items);
            // review fix: a note never outlives one look - an empty slot is nobody's, whatever goes there later is not the bot's
            if (items.get(0) == null && mem.placed(pos) != null) mem.forget(pos);
        }
        AltarWorld.Stack out = alt.get(1), in = alt.get(0);
        // "pressed" only means something while the bot's input is still on the altar or its output is there
        if (out == null && mem.pressed() != null && (in == null || !in.id().equals(mem.placed(l.altar())))) mem.pressed(null);
        boolean ownOutput = false;
        if (out != null) {
            if (out.id().equals(mem.pressed())) ownOutput = true;
            else return new Survey(notMine("the infusion altar at " + fmt(l.altar()) + " has " + out.count() + " " + shortId(out.id()) + " in its output"), retrieve, ready, false, out.id());
        }
        for (int i = 0; i < all.size(); i++) {
            int[] pos = all.get(i);
            boolean isAltar = pos == l.altar();
            AltarWorld.Stack s = seen.get(i).get(0);
            if (s == null) continue;
            String mine = mem.placed(pos);
            String where = (isAltar ? "the infusion altar at " : "the pedestal at ") + fmt(pos);
            if (!s.id().equals(mine)) return new Survey(notMine(where + " holds " + s.count() + " " + shortId(s.id())), retrieve, ready, ownOutput, null);
            String want = null;
            for (Target t : targets) if (same(t.pos(), pos)) want = t.item();
            if (s.id().equals(want) && s.count() == 1) ready.add(pos);
            else retrieve.add(pos);
        }
        return new Survey(null, retrieve, ready, ownOutput, ownOutput ? out.id() : null);
    }

    public static boolean same(int[] a, int[] b) { return a[0] == b[0] && a[1] == b[1] && a[2] == b[2]; }

    // ---- texts ----

    public static String notMine(String what) {
        return what + " that isn't mine - I never take things off the altar or its pedestals; please empty it and run infuse again";
    }

    public static String noRecipe(String q) {
        return "error: I know no infusion recipe for " + q + " (seeds are made on the infusion altar; try the seed's name, e.g. infuse silicon_seeds)";
    }

    public static String noAltar(boolean baseKnown) {
        return "error: I can't find an infusion altar within 16 blocks of me" + (baseKnown ? " or 24 of the base" : " (and no base is marked)");
    }

    /** What the ingredients lack: "missing 3 silicon (I have 5 in my bag, chests and the RS network; 4 a seed)". */
    public static String missing(int n, String seed, String item, int have, int perRound, String why) {
        return "error: I can't infuse " + n + " " + shortId(seed) + " - missing " + (perRound * n - have) + " " + shortId(item) + " (I have " + have
                + " in my bag, chests and the RS network; " + perRound + " a seed)" + (why != null ? " and I can't craft it: " + why : "");
    }

    public static String label(int n, String seed) {
        return "infusing " + n + " " + shortId(seed);
    }

    public static String done(int made, String seed, int[] altar, int recovered, String lost) {
        return "made " + made + " " + shortId(seed) + " on the infusion altar at " + fmt(altar) + " (in my bag)"
                + (recovered > 0 ? "; also took " + recovered + " " + shortId(seed) + " a stopped infuse had left on the altar" : "")
                + (lost != null ? "; " + lost : "");
    }

    public static String guessedNote(String center) {
        return "the recipe doesn't show what goes on the altar, so I use " + shortId(center);
    }
}
