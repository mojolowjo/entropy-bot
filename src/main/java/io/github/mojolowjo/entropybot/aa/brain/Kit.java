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
    public static final long DESPAWN_MS = 300_000; // game fact: dropped items despawn after 5 minutes
    /** Seconds of extra cost per unit of danger at the spot that killed me. */
    public static double diedS() { return Tuning.get("diedS"); }
    /** Under this missing worth there is nothing to go back for. */
    public static double minRecover() { return Tuning.get("kit.minRecover"); }
    /** Under this loot worth there is nothing to stash. */
    public static double minStash() { return Tuning.get("kit.minStash"); }
    /** The stash rises over this many ticks before dusk (75 s). */
    public static long stashLead() { return Tuning.l("kit.stashLead"); }
    /** A trip this far from the base is a far trip (blocks). */
    public static double farTripV() { return Tuning.get("kit.farTrip"); }
    /** The stash stays under the urgent line. */
    public static double stashMax() { return Tuning.get("stashMax"); }

    public record Death(String dim, int x, int y, int z, Map<String, Integer> bag, long at) {
        public Site site() { return new Site(dim, x, y, z, 0); }
    }

    // ---- worth ----

    static double block() { return Tuning.get("worth.floor"); }  // = Tuning worth.floor: a plain block, the line under which an item is no loot

    static String path(String id) { int i = id.indexOf(':'); return i < 0 ? id : id.substring(i + 1); }

    /** A tool's or armour piece's worth from the game's item data (0: not one, or no data: said once by ItemWorth). */
    public static double toolValue(String id) { return ItemWorth.gear(id); }

    /** One food item: the data's food worth (0 none). */
    public static double foodValue(String id) { return ItemWorth.food(id); }

    /** What the deposit verb keeps, by role and never by name: damageable gear (tools, armour, shield, bow), food, torch-like light, workstations. */
    public static boolean kept(String id) {
        return toolValue(id) > 0 || foodValue(id) > 0 || ItemWorth.damageable(id) || ItemWorth.lightSource(id) || ItemWorth.station(id);
    }

    /** One item's worth (any kind): the data; an item the game gives no data for is {@code kit.unknown}, said once. */
    public static double itemValue(String id) {
        double t = toolValue(id);
        if (t > 0) return t;
        double f = foodValue(id);
        if (f > 0) return f;
        if (ItemWorth.lightSource(id)) return Tuning.get("worth.lightK") * 14;
        var w = ItemWorth.of(id);
        if (w.known() && w.value() > 0) return w.value();
        ItemWorth.warnOnce("kit:" + id, path(id) + " has no worth in the game's data (" + w.why() + "); using the prior " + Tuning.get("kit.unknown"));
        return Tuning.get("kit.unknown");
    }

    public static double worth(Map<String, Integer> bag) {
        double w = 0;
        if (bag != null) for (var e : bag.entrySet()) w += itemValue(e.getKey()) * Math.max(0, e.getValue());
        return w;
    }

    /** The loot the deposit verb moves: not {@link #kept}, not junk, worth over a plain block (plain blocks left out so the stash can end). */
    public static double lootWorth(Map<String, Integer> bag) {
        double w = 0;
        if (bag != null) for (var e : bag.entrySet()) {
            String id = e.getKey();
            if (kept(id) || Junk.is(id)) continue;
            double v = itemValue(id);
            if (v > block()) w += v * Math.max(0, e.getValue());
        }
        return w;
    }
    /** The worth of what the dead bag held and the bag now lacks (keepInventory or picked up: 0). */
    public static double missing(Map<String, Integer> dead, Map<String, Integer> now) {
        double w = 0;
        if (dead == null) return 0;
        for (var e : dead.entrySet()) {
            // review: food eaten and blocks placed since are no drops to go back for
            if (foodValue(e.getKey()) > 0 || itemValue(e.getKey()) <= block()) continue;
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
    public static double recoverCost(double walkS, double danger) { return walkS + Tuning.get("diedS") * (1 + Math.max(0, danger)); }

    /** The recover's value before the cost (at most 99), -1 when nothing to go back for. */
    public static double recoverValue(double missing, double pThere) {
        if (missing < minRecover() || pThere <= 0) return -1;
        return Math.min(99, missing * pThere);
    }

    /** The chain: walk onto the spot, then a short wait for the drops to come to me. */
    public static java.util.List<io.github.mojolowjo.entropybot.aa.moves.Step> recoverSteps(Death d) {
        return java.util.List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Walk(d.x, d.y, d.z, 0), new io.github.mojolowjo.entropybot.aa.moves.Step.Wait((int) Tuning.get("steps.recoverWaitS")));
    }

    // ---- the stash ----

    /** How soon the stash is wanted: 1 before a far trip, else rising 0..1 over STASH_LEAD ticks before dusk; 0 at night. */
    public static double soon(long dayTime, boolean nightFlag, boolean farTrip) {
        if (farTrip) return 1;
        if (dayTime < 0) return 0;
        long t = dayTime % 24_000, from = Night.DUSK - stashLead();
        if (t >= from && t < Night.DUSK) return (t - from) / (double) stashLead();
        if (t >= Night.DUSK && t < Night.BED_FROM) return 1;     // dusk: still daylight enough to walk home
        return 0;
    }

    /** loot x danger x soon, under the urgent line; -1 when there is nothing (or no reason) to stash. */
    public static double stashValue(double loot, double danger, double soon) {
        if (loot < minStash() || soon <= 0 || danger <= 0) return -1;
        return Math.min(Tuning.get("stashMax"), loot * danger * soon);
    }

    public static String terms(double loot, double danger, double soon, double v) {
        return "loot " + r(loot) + " x danger " + r(danger) + " x soon " + r(soon) + " -> " + (v < 0 ? "-" : String.valueOf(Math.round(v)));
    }

    static double r(double v) { return Math.round(v * 100) / 100.0; }
}

