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
        return s.freeSlots <= c.i("brain.bagFullSlots");      // 0.26.1 (R7): fullInterrupt is migrated into it
    }

    // ---- 0.26.1 "steady brain" ----

    /** R3: a job that fills the bag: mine (strip, cave, an ore), cut/chop, gather, farm, dig. */
    public static boolean gatheringJob(String chain) {
        if (chain == null) return false;
        String[] w = chain.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (w.length == 0) return false;
        return switch (w[0]) {
            case "mine" -> !(w.length >= 2 && w[1].matches("status|ores|reset|turn")) && !(w.length >= 3 && w[1].matches("strip|cave") && w[2].matches("status|ores|reset|turn"));
            case "cut", "chop", "gather", "farm", "dig" -> true;
            default -> false;
        };
    }

    /**
     * R3 pre-deposit: the chain to run instead ({@code deposit then <job>}) when the brain picked a gathering job with
     * {@code brain.preDepositSlots} or fewer free slots and a base known; null when the rule does not apply.
     */
    public static String preDeposit(String chain, BrainState s, BrainConfig c) {
        int slots = c.i("brain.preDepositSlots");
        if (slots <= 0 || chain == null || s.basePos == null || s.freeSlots > slots) return null;
        if (bagTripOrRestock(chain) || !gatheringJob(chain)) return null;
        return "deposit then " + chain;
    }

    static boolean bagTripOrRestock(String chain) {
        String c = chain.trim().toLowerCase(Locale.ROOT);
        return c.startsWith("deposit") || c.startsWith("restock") || c.contains(" deposit");
    }

    /** R2: torches can be had for the night: in the bag, or coal/charcoal or logs to craft them. */
    public static boolean torchMaterials(BrainState s) { return s.coal > 0 || s.logs > 0; }

    /** R2: a bed for the night: one within 24 blocks, or one in the bag (sleep places it). */
    public static boolean bedFor(BrainState s) { return s.bedNear || s.bedInBag; }

    /**
     * R8: the pickaxe materials for one of this tier in the bag (bag counts by id without the namespace): 3 heads
     * (planks, cobblestone-like stone, iron_ingot, diamond) and 2 sticks or 1 plank/log to make them.
     */
    public static boolean pickMaterials(String tier, java.util.Map<String, Integer> bag) {
        if (tier == null || bag == null) return false;
        int planks = 0, logs = 0, stone = 0;
        for (var e : bag.entrySet()) {
            String id = e.getKey().substring(e.getKey().indexOf(':') + 1);
            int n = e.getValue() == null ? 0 : e.getValue();
            if (id.endsWith("_planks")) planks += n;
            if (id.endsWith("_log") || id.endsWith("_stem")) logs += n;
            if (id.equals("cobblestone") || id.equals("cobbled_deepslate") || id.equals("blackstone")) stone += n;
        }
        int sticks = bag.getOrDefault("stick", bag.getOrDefault("minecraft:stick", 0));
        boolean handle = sticks >= 2 || planks >= 2 || logs >= 1;
        return switch (tier) {
            case "wooden" -> planks + logs * 4 >= 5 || (planks >= 3 && sticks >= 2);
            case "stone" -> stone >= 3 && handle;
            case "iron" -> bag.getOrDefault("iron_ingot", bag.getOrDefault("minecraft:iron_ingot", 0)) >= 3 && handle;
            case "diamond" -> bag.getOrDefault("diamond", bag.getOrDefault("minecraft:diamond", 0)) >= 3 && handle;
            default -> false;
        };
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
