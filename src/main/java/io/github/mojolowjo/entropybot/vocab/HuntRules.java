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

    /** "hunt <n> [animal]": {n, animal id or null}; null = bad. */
    public static Object[] parse(String rest) {
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

    /** gather's source for a raw meat while hunting is on: "hunt {n} <animal>"; null for anything else. */
    public static String source(String rawId) {
        for (Map.Entry<String, String> e : MEAT.entrySet())
            if (e.getValue().equals(rawId)) return "hunt {n} " + e.getKey().substring(10);
        return null;
    }
}
