package io.github.mojolowjo.entropybot.gather;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P2: the gather planner. Each call decides the ONE next step toward "n of item in the bag", from what the bag and
 * storage hold now (the driver re-plans after every step, so partial progress counts): enough -> done; some in
 * storage -> {@code get}; the real craft planner can make it now -> {@code craft} (it fetches, crafts and smelts);
 * else down the recipes (furnace recipes first, the plan's rule 5 before 6) to the first missing raw item, whose
 * source (GatherSources) is the step. Pure Java: JUnit drives it with a fake {@link World}.
 */
public final class GatherPlan {
    private GatherPlan() {}

    /** How deep the recipe walk goes (the plan's depth 6). */
    public static final int MAX_DEPTH = 6;
    /** Alternatives of a tag tried per ingredient. */
    static final int ALTS = 4;

    public record Need(List<String> alts, int amount) {}

    /** A recipe: its used-up ingredients, how many it makes, and whether it is a furnace recipe (one input, one item). */
    public record Recipe(List<Need> needs, int out, boolean smelt) {}

    /** What the planner sees. */
    public interface World {
        /** In the bag. */
        int bag(String id);

        /** In the chests and the RS network (the notes). */
        int stored(String id);

        /** The craft planner can make n of it now from the bag and storage (with a table and furnace as needed). */
        boolean canMake(String id, int n);

        /** Its crafting and furnace recipes, breakdowns (a block back into ingots) left out. */
        List<Recipe> recipes(String id);

        /** A "mine" place with a direction is marked. */
        boolean mineMarked();

        /** The owner's sources (commands.json "gatherSources"): item id -> command. */
        Map<String, String> overrides();
    }

    public enum Kind { DONE, GET, CRAFT, SOURCE, NO_WAY }

    /**
     * The next step. leaf: the item that step is for (GET/CRAFT: that item; SOURCE: the raw item); n: how many;
     * commands: SOURCE's commands in the order to try; why: NO_WAY's text.
     */
    public record Step(Kind kind, String leaf, int n, List<String> commands, String why) {
        public String command(int attempt) {
            return switch (kind) {
                case GET -> "get " + leaf + " " + n;
                case CRAFT -> "craft " + leaf + " " + n;
                case SOURCE -> commands.get(Math.floorMod(attempt, commands.size()));
                default -> null;
            };
        }
    }

    /** The next step toward {@code want} of {@code target} in the bag. */
    public static Step next(String target, int want, World w) {
        String id = GatherSources.full(target);
        int have = w.bag(id);
        if (have >= want) return new Step(Kind.DONE, id, want, List.of(), null);
        int need = want - have;
        int stored = w.stored(id);
        if (stored > 0) return new Step(Kind.GET, id, Math.min(need, stored), List.of(), null);
        GatherSources.Source src = GatherSources.resolve(id, w.overrides());
        if (src != null && src.kind() == GatherSources.Kind.OVERRIDE) return source(src, need, false, w);
        if (w.canMake(id, need)) return new Step(Kind.CRAFT, id, need, List.of(), null);
        Step s = leaf(id, need, w, new HashSet<>(), 0, false);
        return s != null ? s : noWay(id, "no recipe makes it and I know no source for it");
    }

    private static Step noWay(String id, String why) {
        return new Step(Kind.NO_WAY, id, 0, List.of(), why);
    }

    private static Step source(GatherSources.Source src, int n, boolean anyWood, World w) {
        if (src.kind() == GatherSources.Kind.NONE) return noWay(src.item(), src.hint());
        return new Step(Kind.SOURCE, src.item(), n, GatherSources.commands(src, n, w.mineMarked(), anyWood), null);
    }

    static int stock(World w, String id) {
        return w.bag(id) + w.stored(id);
    }

    private static Step leaf(String id, int m, World w, Set<String> path, int depth, boolean anyWood) {
        GatherSources.Source src = GatherSources.resolve(id, w.overrides());
        if (src != null) return source(src, m, anyWood, w);
        if (depth >= MAX_DEPTH) return noWay(id, "its recipe chain is deeper than " + MAX_DEPTH + " steps");
        List<Recipe> rs = new ArrayList<>(w.recipes(id));
        if (rs.isEmpty()) return null;
        // the furnace first (rule 5: an ingot from raw ore, not from nuggets), then crafting
        // and an ore block as the input last: mining drops raw_iron, never iron_ore (the leaf is what gets counted)
        rs.sort(java.util.Comparator.comparingInt((Recipe r) -> r.smelt() ? 0 : 1).thenComparingInt(r -> oreBlockInput(r) ? 1 : 0));
        Step firstNoWay = null;
        path.add(id);
        try {
            for (Recipe r : rs) {
                int crafts = (int) Math.ceil(m / (double) Math.max(1, r.out()));
                boolean ok = true;
                for (Need n : r.needs()) {
                    List<String> alts = new ArrayList<>();
                    for (String a : n.alts()) if (!path.contains(a)) alts.add(a);
                    if (alts.isEmpty()) {
                        ok = false;
                        break;
                    }
                    int amount = n.amount() * crafts, avail = 0;
                    for (String a : alts) avail += stock(w, a);
                    int missing = amount - avail;
                    if (missing <= 0) continue;
                    boolean any = anyWood || woodTypes(alts) > 1;
                    for (int j = 0; j < alts.size() && j < ALTS; j++) {
                        Step s = leaf(alts.get(j), missing, w, path, depth + 1, any);
                        if (s == null) continue;
                        if (s.kind() != Kind.NO_WAY) return s;
                        if (firstNoWay == null) firstNoWay = s;
                    }
                    ok = false;
                    break;
                }
                if (!ok) continue;
                // everything is there but the planner can't make it now: a furnace without fuel, or a table/furnace
                // missing (the craft verb says which)
                if (r.smelt() && !hasFuel(w, crafts)) {
                    GatherSources.Source coal = GatherSources.resolve("minecraft:coal", w.overrides());
                    return source(coal, (int) Math.ceil(crafts / 8.0), false, w);
                }
                return new Step(Kind.CRAFT, id, m, List.of(), null);
            }
        } finally {
            path.remove(id);
        }
        return firstNoWay;
    }

    static boolean oreBlockInput(Recipe r) {
        for (Need n : r.needs()) for (String a : n.alts()) if (a.endsWith("_ore")) return true;
        return false;
    }

    /** Coal or charcoal for n items (the planner also burns planks: those count, 1.5 items each). */
    static boolean hasFuel(World w, int n) {
        int coal = stock(w, "minecraft:coal") + stock(w, "minecraft:charcoal");
        if (coal * 8 >= n) return true;
        int planks = 0;
        for (String t : WOODS) planks += stock(w, "minecraft:" + t + "_planks");
        return coal * 8 + planks * 3 / 2 >= n;
    }

    static final List<String> WOODS = List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "bamboo");
    private static final Pattern WOOD = Pattern.compile("^minecraft:(?:stripped_)?([a-z_]+?)_(log|wood|planks)$");

    /** How many wood kinds a tag's alternatives span (#planks: many; #oak_logs: 1). */
    static int woodTypes(List<String> alts) {
        Set<String> kinds = new HashSet<>();
        for (String a : alts) {
            Matcher m = WOOD.matcher(a);
            if (m.find()) kinds.add(m.group(1));
        }
        return kinds.size();
    }
}
