package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.brain.Thing;
import io.github.mojolowjo.entropybot.gui.GuiCore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * aa drop (owner 2026-11-13: "i want drop to be physically dropping items"; "no need for the refusal of a drop all"; "it
 * should not drop -d items for now"): what {@code aa drop <item|all> [n|all]} throws on the floor - the item named (all of
 * it with no number), or with {@code all} every item of the bag, each less what the bag holds for a -d order's deposit
 * (Goals.kept: the one store of those amounts). Pure (JUnit: DropPlanTest); the runtime throws each entry with
 * Storage.drop (the inventory menu's THROW click: inventory and hotbar, never armor). Loader notes: none.
 */
record DropPlan(Map<String, Integer> drops, Map<String, Integer> held, String err) {
    static final String USAGE = "usage: aa drop <item|all> [n|all] (throws it on the floor; -d items stay); aa cancel <item>|all cancels an order";

    /**
     * {@code carried}: the bag by full item id; {@code kept}: counted id (Thing.path) -> the amount held for a deposit.
     * {@code drops}: full id -> how many to throw; {@code held}: counted id -> how many were asked for and stay for a deposit.
     */
    static DropPlan of(String arg, Map<String, Integer> carried, Map<String, Integer> kept) {
        String[] w = (arg == null ? "" : arg.trim().toLowerCase(java.util.Locale.ROOT)).split("\\s+");
        if (w[0].isEmpty() || w.length > 2) return new DropPlan(Map.of(), Map.of(), USAGE);
        long want = Long.MAX_VALUE;
        if (w.length == 2 && !w[1].equals("all")) {
            try { want = Long.parseLong(w[1]); } catch (NumberFormatException e) { want = -1; }
            if (want <= 0) return new DropPlan(Map.of(), Map.of(), USAGE);
        }
        List<String> ids = new ArrayList<>();
        if (w[0].equals("all")) ids.addAll(carried.keySet());
        else ids.add(GuiCore.resolve(w[0], carried.keySet()));
        Map<String, Integer> drops = new TreeMap<>(), held = new TreeMap<>();
        for (String id : ids) {
            int have = carried.getOrDefault(id, 0);
            if (have <= 0) continue;
            int k = Math.min(have, kept == null ? 0 : kept.getOrDefault(Thing.path(id), 0));
            int n = (int) Math.min(want, have - k);
            if (n > 0) drops.put(id, n);
            int short_ = (int) Math.min(want, have) - n;          // asked for, and staying for the deposit
            if (short_ > 0) held.put(Thing.path(id), short_);
        }
        return new DropPlan(drops, held, null);
    }

    /** " (kept for a deposit, not dropped: cobblestone 32)", or "" when nothing asked for stays. */
    String heldText() {
        if (held.isEmpty()) return "";
        List<String> p = new ArrayList<>();
        for (var e : held.entrySet()) p.add(e.getKey() + " " + e.getValue());
        return " (kept for a deposit, not dropped: " + String.join(", ", p) + ")";
    }
}
