package io.github.mojolowjo.entropybot.mule;

import io.github.mojolowjo.entropybot.gui.GuiCore;
import io.github.mojolowjo.entropybot.storage.StorageRules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * C6 (mule and fetch): the rules of {@code hold this}, {@code give}, {@code carry}, {@code unload} and {@code fetch}
 * that need no game: parsing, what the bot may give away (the deposit keep rules plus tools, armor, 8 food, 16
 * torches), the throw plan, which item entities "hold this" and "carry" pick up, the fetch plan and the reports.
 * Plain Java: JUnit tests it.
 * <p>
 * Loader notes: none here. The game side ({@code commands.Mule}) uses vanilla client classes only (ItemEntity,
 * LocalPlayer.lookAt, the inventory menu's THROW click through GuiCore.drop) and Baritone's GoalBlock/GoalNear.
 */
public final class MuleRules {
    private MuleRules() {}

    public static final int HOLD_TICKS = 300, HOLD_RADIUS = 4, CARRY_RADIUS = 6, GIVE_REACH = 3;
    public static final int KEEP_FOOD = 8, KEEP_TORCH = 16, MAX_GIVE = 36 * 64;

    public enum Kind { HOLD, GIVE, CARRY, CARRY_OFF, CARRY_LIST, UNLOAD, FETCH, ERROR }

    /** One parsed command. player: who gets the items (give). items: carry's list. n = 0: "all I can give". */
    public record Args(Kind kind, String item, int n, String player, List<String> items, String error) {
        static Args err(String e) { return new Args(Kind.ERROR, null, 0, null, List.of(), e); }
    }

    public static final String HOLD_USAGE = "usage: hold this (then throw me the items within 15 s)";
    public static final String GIVE_USAGE = "usage: give me <item> [n] | give <player> <item> [n]";
    public static final String CARRY_USAGE = "usage: carry <item> [item ...] | carry off | carry list";
    public static final String FETCH_USAGE = "usage: fetch <item> [n]";

    public static Args parse(String verb, String rest, String from) {
        String r = rest == null ? "" : rest.trim();
        String[] w = r.isEmpty() ? new String[0] : r.split("\\s+");
        switch (verb == null ? "" : verb.toLowerCase()) {
            case "hold":
                if (w.length == 1 && w[0].equalsIgnoreCase("this")) return new Args(Kind.HOLD, null, 0, from, List.of(), null);
                return Args.err(HOLD_USAGE);
            case "give": {
                if (w.length < 2 || w.length > 3) return Args.err(GIVE_USAGE);
                String who = w[0].equalsIgnoreCase("me") ? from : w[0];
                if (!who.matches("^[A-Za-z0-9_]{1,16}$")) return Args.err(GIVE_USAGE);
                int n = 0;
                if (w.length == 3) {
                    n = count(w[2]);
                    if (n <= 0) return Args.err("error: give how many? " + w[2] + " is not a count (1-" + MAX_GIVE + ")");
                }
                return new Args(Kind.GIVE, w[1].toLowerCase(), n, who, List.of(), null);
            }
            case "carry": {
                if (w.length == 0 || (w.length == 1 && w[0].equalsIgnoreCase("list"))) return new Args(Kind.CARRY_LIST, null, 0, from, List.of(), null);
                if (w.length == 1 && w[0].equalsIgnoreCase("off")) return new Args(Kind.CARRY_OFF, null, 0, from, List.of(), null);
                List<String> items = new ArrayList<>();
                for (String s : r.toLowerCase().split("[\\s,]+")) if (!s.isEmpty() && !items.contains(s)) items.add(s);
                if (items.isEmpty() || items.size() > 16) return Args.err(CARRY_USAGE + " (16 items at most)");
                return new Args(Kind.CARRY, null, 0, from, items, null);
            }
            case "unload":
                if (w.length != 0) return Args.err("usage: unload (I go home, put my loot away and come back to you)");
                return new Args(Kind.UNLOAD, null, 0, from, List.of(), null);
            case "fetch": {
                if (w.length < 1 || w.length > 2) return Args.err(FETCH_USAGE);
                int n = 1;
                if (w.length == 2) {
                    n = count(w[1]);
                    if (n <= 0) return Args.err("error: fetch how many? " + w[1] + " is not a count (1-" + MAX_GIVE + ")");
                }
                return new Args(Kind.FETCH, w[0].toLowerCase(), n, from, List.of(), null);
            }
            default:
                return Args.err("error: unknown mule verb " + verb);
        }
    }

    static int count(String s) {
        try {
            int n = Integer.parseInt(s);
            return n > MAX_GIVE ? -1 : n;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ==== give: what may go ====

    static final Pattern NEVER = Pattern.compile("_(pickaxe|axe|shovel|hoe|sword|helmet|chestplate|leggings|boots)$|(^|:)(bow|crossbow|shield|trident|elytra|fishing_rod|flint_and_steel|shears)$");
    static final Pattern TORCH = Pattern.compile("(^|:)torch$");

    /** How many of id the bot may give away (0: none) and why it holds some back (null: it holds nothing back). */
    public record Giveable(int n, int have, String why) {}

    /**
     * The give keep rules: never tools, weapons or armor; food only above 8 in all; torches above 16; and what deposit
     * keeps (the supplies up to their count, the best-tier pickaxes). The hotbar layout is not a keep here (its "food"
     * slot would hold back all food). keeps may be null.
     */
    public static Giveable giveable(List<StorageRules.Held> inv, String id, StorageRules.Keeps keeps) {
        int have = 0, food = 0;
        boolean isFood = false;
        for (StorageRules.Held h : inv) {
            if (h.id() == null) continue;
            if (h.food()) food += h.n();
            if (h.id().equals(id)) {
                have += h.n();
                isFood |= h.food();
            }
        }
        if (have == 0) return new Giveable(0, 0, "I have no " + GuiCore.shortId(id));
        if (NEVER.matcher(id).find()) return new Giveable(0, have, "my tools, weapons and armor stay with me");
        int keep = 0;
        String why = null;
        if (isFood) {
            int k = Math.max(0, KEEP_FOOD - (food - have));          // the other food counts towards the 8 I keep
            if (k > keep) { keep = k; why = "I keep " + KEEP_FOOD + " food"; }
        }
        if (TORCH.matcher(id).find() && KEEP_TORCH > keep) { keep = KEEP_TORCH; why = "I keep " + KEEP_TORCH + " torches"; }
        StorageRules.Keeps k = keeps == null ? null : new StorageRules.Keeps(keeps.supplies(), Map.of());
        int kept = StorageRules.kept(inv, k).getOrDefault(id, 0);
        if (kept > keep) {
            keep = kept;
            why = k != null && k.supplies() != null && k.supplies().containsKey(id) ? "I keep " + kept + " (my supplies)" : "it is my best pickaxe";
        }
        int n = Math.max(0, have - keep);
        return new Giveable(n, have, keep > 0 ? why : null);
    }

    /** The throws for n items of a kind whose stack holds maxStack (64 at most per throw). */
    public static List<Integer> throwPlan(int n, int maxStack) {
        int per = Math.max(1, Math.min(64, maxStack));
        List<Integer> out = new ArrayList<>();
        for (int left = n; left > 0; left -= per) out.add(Math.min(per, left));
        return out;
    }

    /** How many to give: the ask (0 = all it may), cut to what it may; and the line when it is cut or nothing. */
    public record GiveDecision(int n, String note) {}

    public static GiveDecision decide(int asked, Giveable g, String id) {
        String x = GuiCore.shortId(id);
        if (g.n() <= 0) return new GiveDecision(0, "error: I can't give " + x + " - " + g.why());
        if (asked <= 0 || asked >= g.n()) {
            if (asked > g.n()) return new GiveDecision(g.n(), "only " + g.n() + " of " + asked + " " + x + (g.why() == null ? " - I have no more" : " - " + g.why()));
            return new GiveDecision(g.n(), null);
        }
        return new GiveDecision(asked, null);
    }

    public static String gaveText(String to, String from, int n, String id, String note) {
        String who = to.equalsIgnoreCase(from) ? "you" : to;
        return "ok: gave " + who + " " + n + " " + GuiCore.shortId(id) + (note == null ? "" : " (" + note + ")");
    }

    // ==== hold this / carry: which drops ====

    /** An item entity the bot sees: its entity id, item, thrower's name (null: unknown on the client), distance. */
    public record Seen(int entity, String item, String thrower, double dist) {}

    /**
     * "hold this": an item within 4 blocks that the sender threw (when the thrower is known), else one that wasn't
     * there when the command came.
     */
    public static boolean holdTakes(Seen s, String sender, Set<Integer> before) {
        if (s.dist() > HOLD_RADIUS) return false;
        if (s.thrower() != null) return s.thrower().equalsIgnoreCase(sender);
        return !before.contains(s.entity());
    }

    /** "carry": an item of the list within 6 blocks of the owner (dist = to the owner). Names match as in deposit. */
    public static boolean carryTakes(Seen s, List<String> wanted) {
        if (s.dist() > CARRY_RADIUS || s.item() == null) return false;
        for (String w : wanted) {
            if (w.equals("all")) return true;                // 0.24.2: carry all (assist's idle mode)
            Predicate<String> m = GuiCore.nameMatcher(w);
            if (m.test(s.item())) return true;
        }
        return false;
    }

    /** "holding 3 items: 32 cobblestone, 5 dirt, 1 bread" (the gain since the command). */
    public static String holdText(Map<String, Integer> before, Map<String, Integer> after) {
        Map<String, Integer> got = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : after.entrySet()) {
            int d = e.getValue() - before.getOrDefault(e.getKey(), 0);
            if (d > 0) got.put(e.getKey(), d);
        }
        if (got.isEmpty()) return "ok: nothing came my way in 15 s - throw the items within 4 blocks of me after \"hold this\"";
        int total = 0;
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : got.entrySet()) {
            total += e.getValue();
            parts.add(e.getValue() + " " + GuiCore.shortId(e.getKey()));
        }
        return "ok: holding " + total + " item" + (total == 1 ? "" : "s") + ": " + String.join(", ", parts);
    }

    // ==== fetch ====

    /** The steps before the give: "get <item> k" from storage, then "gather <item> n" for the rest. */
    public static List<String> fetchPlan(String item, int want, int bag, int stored) {
        List<String> out = new ArrayList<>();
        int missing = want - bag;
        if (missing <= 0) return out;
        int take = Math.min(Math.max(0, stored), missing);
        if (take > 0) out.add("get " + item + " " + take);
        if (take < missing) out.add("gather " + item + " " + want);      // gather counts the bag towards n
        return out;
    }

    /** "fetched 32 torch: took 16 from storage, gathered 16". */
    public static String fetchedText(int n, String item, int took, int made, int had) {
        List<String> parts = new ArrayList<>();
        if (had > 0) parts.add(had + " I had");
        if (took > 0) parts.add("took " + took + " from storage");
        if (made > 0) parts.add("gathered " + made);
        return "fetched " + n + " " + GuiCore.shortId(item) + (parts.isEmpty() ? "" : ": " + String.join(", ", parts));
    }
}
