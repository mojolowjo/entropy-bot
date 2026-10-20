package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;

import java.util.HashMap;
import java.util.Map;

/**
 * Director P1 "kit and stash" (aa-kit). Pure but for the blackboard fact; JUnit KitTest. Loader notes: none (the adapter
 * calls {@link #died} on the death tick, while {@code bb.in} still holds the last live position and bag).
 *
 * <ul>
 * <li>Worth of a bag: tools by tier (pickaxe 8/15/30/45/55, sword x0.6, axe x0.4, shovel x0.3), armour per piece, food
 *     at 0.25 per energy point (FoodModel), loot (ores, ingots, gems) at hand values, a block 0.02.</li>
 * <li>recover.kit (PROGRESS): the dropped items still missing x P(still there) where P = 1 - (time since death + the
 *     walk) / 5 min (the despawn clock; it pauses in an unloaded chunk, so this is the pessimistic side); the brain
 *     divides by the cost: the walk + {@link #DIED_S} x (1 + the night's danger) for the spot that killed me. The
 *     record is dropped after the clock.</li>
 * <li>stash (UPKEEP, under the urgent line): the loot the {@code deposit} verb moves (not tools, armour, food, torches)
 *     x the night's danger x how soon: rising over the last {@link #STASH_LEAD} ticks before dusk, 1 before a far trip.</li>
 * </ul>
 */
public final class Kit {
    private Kit() {}

    /** Blackboard fact: the last {@link Death} (null none). */
    public static final String FACT = "aa.kitDeath";
    public static final long DESPAWN_MS = 300_000;
    /** Seconds of extra cost per unit of danger at the spot that killed me. */
    public static final double DIED_S = 60;
    /** Under this missing worth there is nothing to go back for. */
    public static final double MIN_RECOVER = 2;
    /** Under this loot worth there is nothing to stash. */
    public static final double MIN_STASH = 3;
    /** The stash rises over this many ticks before dusk (75 s). */
    public static final long STASH_LEAD = 1_500;
    /** A trip this far from the base is a far trip (blocks). */
    public static final double FAR_TRIP = 64;
    /** The stash stays under the urgent line. */
    public static final double STASH_MAX = 74;

    public record Death(String dim, int x, int y, int z, Map<String, Integer> bag, long at) {
        public Site site() { return new Site(dim, x, y, z, 0); }
    }

    // ---- worth ----

    static final Map<String, Double> TIER = Map.of("wooden", 8.0, "golden", 5.0, "stone", 15.0, "iron", 30.0, "diamond", 45.0, "netherite", 55.0);
    static final Map<String, Double> ARMOUR = Map.of("leather", 1.0, "golden", 2.0, "chainmail", 4.0, "iron", 5.0, "diamond", 8.0, "netherite", 10.0);
    static final Map<String, Double> LOOT = Map.ofEntries(
            Map.entry("raw_iron", 1.5), Map.entry("iron_ingot", 2.0), Map.entry("iron_ore", 1.5), Map.entry("deepslate_iron_ore", 1.5),
            Map.entry("raw_gold", 1.5), Map.entry("gold_ingot", 2.0), Map.entry("raw_copper", 0.3), Map.entry("copper_ingot", 0.4),
            Map.entry("coal", 0.3), Map.entry("charcoal", 0.3), Map.entry("diamond", 8.0), Map.entry("emerald", 3.0),
            Map.entry("redstone", 0.2), Map.entry("lapis_lazuli", 0.3), Map.entry("string", 0.2), Map.entry("white_wool", 0.5),
            Map.entry("leather", 0.5), Map.entry("bucket", 3.0), Map.entry("water_bucket", 3.0), Map.entry("shield", 4.0), Map.entry("bow", 4.0),
            Map.entry("arrow", 0.1), Map.entry("bed", 4.0), Map.entry("white_bed", 4.0));
    static final double BLOCK = 0.02;

    static String path(String id) { int i = id.indexOf(':'); return i < 0 ? id : id.substring(i + 1); }

    /** A tool's or armour piece's worth (0: not one). */
    public static double toolValue(String id) {
        String p = path(id);
        int u = p.indexOf('_');
        if (u < 0) return 0;
        String mat = p.substring(0, u), kind = p.substring(u + 1);
        Double t = TIER.get(mat);
        if (t != null) {
            switch (kind) {
                case "pickaxe": return t;
                case "sword": return 0.6 * t;
                case "axe": return 0.4 * t;
                case "shovel": return 0.3 * t;
                default: break;
            }
        }
        Double a = ARMOUR.get(mat);
        if (a != null && (kind.equals("helmet") || kind.equals("chestplate") || kind.equals("leggings") || kind.equals("boots"))) return a;
        return 0;
    }

    /** One food item: 0.25 per energy point. */
    public static double foodValue(String id) { return 0.25 * FoodModel.energyOf(path(id)); }

    /** What the deposit verb keeps: tools, armour, food, torches. */
    public static boolean kept(String id) {
        String p = path(id);
        return toolValue(p) > 0 || foodValue(p) > 0 || p.equals("torch") || p.equals("shield") || p.equals("crafting_table");
    }

    /** One item's worth (any kind). */
    public static double itemValue(String id) {
        double t = toolValue(id);
        if (t > 0) return t;
        double f = foodValue(id);
        if (f > 0) return f;
        String p = path(id);
        if (p.equals("torch")) return 0.1;
        Double l = LOOT.get(p);
        return l != null ? l : BLOCK;
    }

    public static double worth(Map<String, Integer> bag) {
        double w = 0;
        if (bag != null) for (var e : bag.entrySet()) w += itemValue(e.getKey()) * Math.max(0, e.getValue());
        return w;
    }

    /** The loot the deposit verb moves: the LOOT items (not {@link #kept}; plain blocks left out so the stash can end). */
    public static double lootWorth(Map<String, Integer> bag) {
        double w = 0;
        if (bag != null) for (var e : bag.entrySet()) if (!kept(e.getKey()) && LOOT.containsKey(path(e.getKey()))) w += itemValue(e.getKey()) * Math.max(0, e.getValue());
        return w;
    }

    /** The worth of what the dead bag held and the bag now lacks (keepInventory or picked up: 0). */
    public static double missing(Map<String, Integer> dead, Map<String, Integer> now) {
        double w = 0;
        if (dead == null) return 0;
        for (var e : dead.entrySet()) {
            // review: food eaten and blocks placed since are no drops to go back for
            if (foodValue(e.getKey()) > 0 || itemValue(e.getKey()) <= BLOCK) continue;
            int have = now == null ? 0 : now.getOrDefault(e.getKey(), 0);
            w += itemValue(e.getKey()) * Math.max(0, e.getValue() - have);
        }
        return w;
    }

    // ---- death and recovery ----

    /** P(the drops are still there on arrival): the 5 min clock less the time gone and the walk; 0..1. */
    public static double pThere(long sinceMs, double walkS) {
        return Math.max(0, Math.min(1, 1 - (sinceMs + walkS * 1000) / (double) DESPAWN_MS));
    }

    /** The death tick: the last live position and bag become the record (nothing to record: none). */
    public static void died(Blackboard bb, long now) {
        var in = bb.in;
        if (in.pos == null || in.bag == null || in.bag.isEmpty()) return;     // review: an earlier record stays
        Death d = new Death(in.dimension, in.pos[0], in.pos[1], in.pos[2], new HashMap<>(in.bag), now);
        bb.fact(FACT, d);
        bb.port.log("kit: died at " + d.x + " " + d.y + " " + d.z + " with worth " + Math.round(worth(d.bag)) + " - recover.kit for 5 min");
    }

    /** The record now (dropped after the clock). */
    public static Death of(Blackboard bb) {
        if (!(bb.fact(FACT) instanceof Death d)) return null;
        if (bb.clock.now() - d.at >= DESPAWN_MS) {
            bb.fact(FACT, null);
            bb.port.log("kit: the despawn clock ran out - recover.kit dropped");
            return null;
        }
        return d;
    }

    /** Walk seconds to the death spot (infinite in another dimension or unknown position). */
    public static double walkS(Blackboard bb, Death d) {
        int[] p = bb.in.pos;
        if (p == null) return Double.POSITIVE_INFINITY;
        return new Site(bb.in.dimension, p[0], p[1], p[2], 0).travelS(d.site());
    }

    /** The recover's cost in seconds: the walk + DIED_S x (1 + danger). */
    public static double recoverCost(double walkS, double danger) { return walkS + DIED_S * (1 + Math.max(0, danger)); }

    /** The recover's value before the cost (at most 99), -1 when nothing to go back for. */
    public static double recoverValue(double missing, double pThere) {
        if (missing < MIN_RECOVER || pThere <= 0) return -1;
        return Math.min(99, missing * pThere);
    }

    /** The chain: walk onto the spot, then a short wait for the drops to come to me. */
    public static String recoverChain(Death d) { return "goto " + d.x + " " + d.y + " " + d.z + " then wait 3"; }

    // ---- the stash ----

    /** How soon the stash is wanted: 1 before a far trip, else rising 0..1 over STASH_LEAD ticks before dusk; 0 at night. */
    public static double soon(long dayTime, boolean nightFlag, boolean farTrip) {
        if (farTrip) return 1;
        if (dayTime < 0) return 0;
        long t = dayTime % 24_000, from = Night.DUSK - STASH_LEAD;
        if (t >= from && t < Night.DUSK) return (t - from) / (double) STASH_LEAD;
        if (t >= Night.DUSK && t < Night.BED_FROM) return 1;     // dusk: still daylight enough to walk home
        return 0;
    }

    /** loot x danger x soon, under the urgent line; -1 when there is nothing (or no reason) to stash. */
    public static double stashValue(double loot, double danger, double soon) {
        if (loot < MIN_STASH || soon <= 0 || danger <= 0) return -1;
        return Math.min(STASH_MAX, loot * danger * soon);
    }

    public static String terms(double loot, double danger, double soon, double v) {
        return "loot " + r(loot) + " x danger " + r(danger) + " x soon " + r(soon) + " -> " + (v < 0 ? "-" : String.valueOf(Math.round(v)));
    }

    static double r(double v) { return Math.round(v * 100) / 100.0; }
}
