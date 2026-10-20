package io.github.mojolowjo.entropybot.vocab;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 0.24.3 (the owner: "hunting is fine for right now"): {@code hunting on|off} (default off) and {@code hunt <n> [animal]}.
 * The target filter (only the allowed farm animals; never tamed, named, owned, villagers or players), the stop rule
 * (n raw meat in the bag) and the raw meat each animal gives. Pure; loader notes: none.
 */
public final class HuntRules {
    private HuntRules() {}

    /** The animals hunt may take (the "animals" kind before exclusions). */
    public static final List<String> ANIMALS = List.of("minecraft:cow", "minecraft:pig", "minecraft:chicken", "minecraft:sheep");
    /** Animal -> its raw meat. */
    public static final Map<String, String> MEAT = Map.of("minecraft:cow", "minecraft:beef", "minecraft:pig", "minecraft:porkchop",
            "minecraft:chicken", "minecraft:chicken", "minecraft:sheep", "minecraft:mutton");
    public static final Set<String> RAW = Set.of("minecraft:beef", "minecraft:porkchop", "minecraft:chicken", "minecraft:mutton");
    public static final int RANGE = 32;

    /** One seen entity. */
    public record Target(String type, boolean tamed, boolean named, boolean owned, boolean player, boolean villager, boolean baby, boolean inAreas) {}

    /** May hunt take it? allowed: the animals kind (ids) after the owner's exclusions; want: one animal word or null. */
    public static boolean mayTake(Target t, List<String> allowed, String want) {
        if (t == null || t.player() || t.villager() || t.tamed() || t.named() || t.owned() || t.baby() || !t.inAreas()) return false;
        if (allowed == null || !allowed.contains(t.type())) return false;
        return want == null || t.type().equals(want);
    }

    /** Raw meat in the bag (all kinds, or the wanted animal's). */
    public static int meat(Map<String, Integer> bag, String want) {
        int n = 0;
        for (String r : RAW) if (want == null || r.equals(MEAT.get(want))) n += bag.getOrDefault(r, 0);
        return n;
    }

    public static boolean done(Map<String, Integer> bag, int n, String want) {
        return meat(bag, want) >= n;
    }

    /** The director (2026-10-19): an urgent hunt ("hunt n [animal] out") may take animals this far from where it started, outside my areas. */
    public static final int OUT_R = 48;

    /** Food urgent enough to leave the areas for a hunt: hunger 6 or less, or health under 10. */
    /** aa395: "I see no cow" this many looks in a row (nothing of the kind in view, none outside, none merely not takable) ends the hunt step. */
    public static final int NONE_LOOKS = 3;

    public static boolean noneEnds(int looksInARow) { return looksInARow >= NONE_LOOKS; }

    public static boolean urgentOut(int food, float health) { return food <= 6 || health < 10; }

    /** An animal outside my areas, {@code fromStart} blocks from the hunt's start: may an "out" hunt take it? */
    public static boolean outOk(boolean out, double fromStart) { return out && fromStart <= OUT_R; }

    /** "hunt n [animal] out": the trailing "out" word, else false. */
    public static boolean outWord(String rest) {
        return rest != null && rest.trim().toLowerCase(Locale.ROOT).matches(".*\\s+out$");
    }

    /**
     * AA_ROUND3 5: "hunt n [animal] at x z" (and "hunt n mob for item [count] at x z"): the hunt starts at a place where
     * the animals were seen; it walks there and takes only animals within {@link #AT_R} of it (outside my areas too, as
     * an out hunt). Arriving to none there ends it ({@link #AT_NONE}).
     */
    public static final int AT_R = 16, AT_ARRIVED = 8;
    private static final java.util.regex.Pattern AT = java.util.regex.Pattern.compile("(?i)\\s+at\\s+(-?\\d{1,8})\\s+(-?\\d{1,8})(?=\\s|$)");

    /** The "at x z" part ({x, z}) or null. */
    public static int[] at(String rest) {
        if (rest == null) return null;
        var m = AT.matcher(rest);
        return m.find() ? new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))} : null;
    }

    /** The words without the "at x z" part. */
    public static String withoutAt(String rest) { return rest == null ? null : AT.matcher(rest).replaceFirst(""); }

    /** May an "at" hunt take an animal {@code dx dz} from its place? */
    public static boolean atOk(int[] at, int x, int z) {
        if (at == null) return false;
        long dx = x - at[0], dz = z - at[1];
        return dx * dx + dz * dz <= (long) AT_R * AT_R;
    }

    /** The end text when an "at" hunt arrives to none (FoodOptions' switch words: "none"). */
    public static final String AT_NONE = "none at the place I saw them";

    /** "hunt <n> [animal] [out]": {n, animal id or null}; null = bad. The "out" word: {@link #outWord}. */
    public static Object[] parse(String rest) {
        rest = withoutAt(rest);
        if (outWord(rest)) rest = rest.trim().replaceFirst("(?i)\\s+out$", "");
        String[] w = (rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT)).split("\\s+");
        if (w.length == 0 || w[0].isEmpty()) return new Object[]{4, null};
        if (!w[0].matches("^\\d{1,3}$")) return null;
        int n = Integer.parseInt(w[0]);
        if (n < 1) return null;
        String a = null;
        if (w.length > 1) {
            a = w[1].indexOf(':') >= 0 ? w[1] : "minecraft:" + w[1];
            if (a.equals("minecraft:cows")) a = "minecraft:cow";
            if (a.equals("minecraft:pigs")) a = "minecraft:pig";
            if (a.equals("minecraft:chickens")) a = "minecraft:chicken";
            if (!MEAT.containsKey(a)) return null;
        }
        if (w.length > 2) return null;
        return new Object[]{n, a};
    }

    /**
     * Loot data: "hunt &lt;n&gt; &lt;mob&gt; for &lt;item&gt; [want]": {kills n, mob id, item id, item count to stop at}; null = bad.
     * The stop count defaults to n.
     */
    public static Object[] parseFor(String rest) {
        rest = withoutAt(rest);
        String[] w = (rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT)).split("\\s+");
        if (w.length < 4 || w.length > 5 || !w[2].equals("for") || !w[0].matches("^\\d{1,3}$")) return null;
        int n = Integer.parseInt(w[0]), want = n;
        if (w.length == 5) {
            if (!w[4].matches("^\\d{1,4}$")) return null;
            want = Integer.parseInt(w[4]);
        }
        if (n < 1 || want < 1) return null;
        String mob = w[1].indexOf(':') >= 0 ? w[1] : "minecraft:" + w[1];
        String item = w[3].indexOf(':') >= 0 ? w[3] : "minecraft:" + w[3];
        return new Object[]{n, mob, item, want};
    }

    /**
     * Loot data: may a "hunt ... for" take it? The same protections as {@link #mayTake}; the mob must be the wanted one
     * and either friendly (not a monster) or in the owner's animals kind (owner-allowed).
     */
    public static boolean mayTakeFor(Target t, boolean friendly, List<String> allowed, String want) {
        if (t == null || t.player() || t.villager() || t.tamed() || t.named() || t.owned() || t.baby() || !t.inAreas()) return false;
        if (!t.type().equals(want)) return false;
        return friendly || (allowed != null && allowed.contains(t.type()));
    }

    /** gather's source for a raw meat while hunting is on: "hunt {n} <animal>"; null for anything else. */
    public static String source(String rawId) {
        for (Map.Entry<String, String> e : MEAT.entrySet())
            if (e.getValue().equals(rawId)) return "hunt {n} " + e.getKey().substring(10);
        return null;
    }
}
