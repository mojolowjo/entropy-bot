package io.github.mojolowjo.entropybot.brain;

import java.util.List;
import java.util.Locale;

/**
 * 0.25.1: the brain's share of the owner's played tactics (the companion action log of 2026-10-08, replayed; DEVNOTES
 * "Replay done"). Each rule is conservative, has a setting ({@link BrainConfig}) and says why in the decision's reason:
 * <ul>
 * <li>bag full on a job ({@code brain.bagFullSlots}): {@code go base then deposit then smelt collect}, plus {@code sleep}
 * at night in the shelter band (or when others sleep and sleep auto is on);</li>
 * <li>eat before a cave trip ({@code cave.eatFirst}): food on me and the bar below 20;</li>
 * <li>food before wood near dusk ({@code brain.foodStock}, {@code brain.foodDusk}): a need-score adjustment.</li>
 * </ul>
 * Pure; loader notes: none.
 */
public final class PlayRules {
    private PlayRules() {}

    /** The bag counts as full for a running job. */
    public static boolean bagFull(BrainState s, BrainConfig c) {
        return s.freeSlots <= Math.max(c.i("fullInterrupt"), c.i("brain.bagFullSlots"));
    }

    /** At night, in the shelter band (or others sleep and sleep auto): the trip home ends in bed. */
    public static boolean sleepAfter(BrainState s, BrainConfig c) {
        if (!s.night) return false;
        return NightSafety.band(NightSafety.score(s), c) == NightSafety.Band.SHELTER || (s.sleepAuto && s.othersSleeping);
    }

    /** The full-bag trip: home, deposit, the furnaces, maybe bed. No base place known: deposit alone (it finds the chests). */
    public static String bagChain(BrainState s, BrainConfig c) {
        String chain = s.basePos == null ? "deposit" : "go base then deposit then smelt collect";
        return sleepAfter(s, c) ? chain + " then sleep" : chain;
    }

    /** The reason text for the full-bag trip. */
    public static String bagWhy(BrainState s, BrainConfig c) {
        return "my bag is full (" + s.freeSlots + " free, brain.bagFullSlots " + c.i("brain.bagFullSlots") + ")"
                + (s.basePos == null ? "; no base place, so deposit only" : "") + (sleepAfter(s, c) ? "; night: then sleep" : "");
    }

    /** A cave trip ("mine cave ..."; not its status/ores words). */
    public static boolean caveTrip(String chain) {
        if (chain == null) return false;
        String[] w = chain.trim().toLowerCase(Locale.ROOT).split("\\s+");
        return w.length >= 2 && w[0].equals("mine") && w[1].equals("cave") && !(w.length >= 3 && w[2].matches("status|reset|turn|ores"));
    }

    /** Eat before this chain: the reason ("eating first (food 15/20)"), or null. The chain's first step only counts. */
    public static String eatFirst(String chain, BrainState s, BrainConfig c) {
        if (!c.on("cave.eatFirst") || chain == null) return null;
        String first = chain.split("\\s+then\\s+")[0];
        if (!caveTrip(first) || s.food >= 20 || s.foodItems <= 0) return null;
        return "eating first (food " + s.food + "/20, " + s.foodItems + " food on me) before the cave trip (cave.eatFirst)";
    }

    /** Wood in the bag (logs and planks, as counted by the game side). */
    public static boolean woodEnough(BrainState s) {
        int want = 16;
        for (BrainState.NeedItem n : s.needs) if (wood(n.id())) want = Math.max(1, Math.min(want, n.want()));
        return s.woodItems >= want;
    }

    static boolean wood(String id) {
        String p = id == null ? "" : id.toLowerCase(Locale.ROOT);
        return p.endsWith("_log") || p.endsWith("_planks") || p.endsWith("_wood") || p.endsWith("_stem");
    }

    /** A wood job (cut, or gather logs/planks). */
    public static boolean woodJob(String chain) {
        if (chain == null) return false;
        String c = chain.trim().toLowerCase(Locale.ROOT);
        if (c.startsWith("cut") || c.startsWith("chop")) return true;
        String[] w = c.split("\\s+");
        return w.length >= 2 && w[0].equals("gather") && wood(w[1]);
    }

    /**
     * Near dusk ({@code night.duskHours}), wood enough (16, or a wood need's count) and food in stock (bag + storage)
     * below {@code brain.foodStock}: the food option scores {@code brain.foodDusk}, and always above the best wood job.
     * Returns the food option to use instead, or null when the rule doesn't apply. The chain is {@code get food n}
     * (that hunts with hunting on, else crops and storage).
     */
    public static Needs.Option duskFood(BrainState s, BrainConfig c, List<Needs.Option> options) {
        if (s.night || !NightSafety.nearDusk(s.dayTime, c.i("night.duskHours"))) return null;
        int stock = Math.max(s.foodStock, s.foodItems);
        if (stock >= c.i("brain.foodStock") || !woodEnough(s)) return null;
        int woodTop = 0, food = 0;
        for (Needs.Option o : options) {
            if (woodJob(o.chain())) woodTop = Math.max(woodTop, o.score());
            if (o.need().equals("food")) food = o.score();
        }
        int sc = Needs.clamp(Math.max(Math.max(food, c.i("brain.foodDusk")), woodTop + 1));
        int n = Math.max(1, c.i("brain.foodStock") - stock);
        return new Needs.Option("food", sc, "get food " + n, "dusk is near, wood enough (" + s.woodItems + "), food in stock " + stock
                + " under brain.foodStock " + c.i("brain.foodStock") + ": food before wood", null, 0);
    }
}
