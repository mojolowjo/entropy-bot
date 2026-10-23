package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * station-race (0.39.z17, owner-watched 2026-10-22 on a LAN world): the block ids that are a station of a recipe kind
 * (ItemWorth.CRAFT / SMELT), from the item data's station item ({@link ItemWorth#stationOf}) and {@code blocksOfItem} (the
 * item's own block plus the workstation tags it is in; empty: the item has no block).
 *
 * <p>Rule 15: the item data is read on a tick after the join, so the first lookups can come before it. Such an answer is
 * {@link Answer.NotLoaded} and is NEVER cached (the old cache kept the empty set forever and every craft said "none known").
 * Only a loaded, non-empty set is cached, and the cache goes when the item data is replaced ({@link ItemWorth#version}: a new
 * world, a re-read). Loaded means: the data is in and it names a station for the kind. Pure (JUnit: StationRaceTest).
 */
public final class StationBlocks {
    /** The three states of the block set. */
    public sealed interface Answer {
        record NotLoaded(String why) implements Answer {}
        record None(String why) implements Answer {}
        record Of(Set<String> ids) implements Answer {
            public Of { ids = Set.copyOf(ids); }
        }
    }

    private final Function<String, Set<String>> blocksOfItem;
    private final Map<String, Set<String>> cache = new HashMap<>();
    private long version = -1;

    public StationBlocks(Function<String, Set<String>> blocksOfItem) {
        this.blocksOfItem = blocksOfItem;
    }

    /** The station blocks of the kind; recomputed on every call until loaded, then cached until the item data changes. */
    public synchronized Answer of(String kind) {
        if (!ItemWorth.hasData()) return new Answer.NotLoaded("item data not loaded yet");
        long v = ItemWorth.version();
        if (v != version) {
            cache.clear();
            version = v;
        }
        Set<String> hit = cache.get(kind);
        if (hit != null) return new Answer.Of(hit);
        String item = ItemWorth.stationOf(kind);
        if (item == null) return new Answer.NotLoaded("item data loaded, but it names no station for " + kind + " recipes yet");
        Set<String> ids = blocksOfItem.apply(item);
        if (ids == null || ids.isEmpty()) return new Answer.None("the station item " + item + " has no block");
        cache.put(kind, Set.copyOf(ids));
        return new Answer.Of(ids);
    }

    /** Whether a lookup now would be NotLoaded (for the waiting step). */
    public boolean notLoaded(String kind) {
        return of(kind) instanceof Answer.NotLoaded;
    }
}
