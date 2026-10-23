package io.github.mojolowjo.entropybot.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * B7e (E1): the bot's own "bag room" (the bridge's bagRoom, which "rule when full" used to read from the bridge's
 * report): slots that are free, or hold only junk the next deposit puts away (deposit's keep rules with the
 * valuables kept; the keep amount of a junk item, e.g. 64 cobblestone, is not room). Pure.
 */
public final class BagRoom {
    private BagRoom() {}

    /** slots36: the 36 inventory slots in order, null for an empty one; keeps: package B's keep rules (null = the old rules). */
    public static int count(List<StorageRules.Held> slots36, StorageRules.Keeps keeps) {
        List<StorageRules.Held> held = new ArrayList<>();
        for (StorageRules.Held h : slots36) if (h != null && h.id() != null) held.add(h);
        return bagRoom(slots36, StorageRules.depositables(held, "", true, null, keeps));
    }

    /** bagRoom: slots that are free or hold only junk the next dump empties (junk = depositables with the valuables kept, {id: keep}). */
    public static int bagRoom(List<StorageRules.Held> slots36, Map<String, Integer> junk) {
        Map<String, Integer> kept = new java.util.HashMap<>();
        int n = 0;
        for (StorageRules.Held h : slots36) {
            if (h == null || h.id() == null) {
                n++;
                continue;
            }
            Integer keep = junk.get(h.id());
            if (keep == null) continue;
            if (keep > 0 && kept.getOrDefault(h.id(), 0) < keep) {
                kept.merge(h.id(), h.n(), Integer::sum);
                continue;
            }
            n++;
        }
        return n;
    }
}
