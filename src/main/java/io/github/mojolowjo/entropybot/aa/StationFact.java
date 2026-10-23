package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;

import java.util.List;

/**
 * station-race (0.39.z17), rule 15 (owner 2026-10-22: a fact is loaded, empty, or NOT LOADED; "not loaded" is never cached
 * and never treated as "none"): the answer of one station lookup of a recipe kind. Sealed, so no caller can read a
 * {@link NotLoaded} as an empty list: every caller switches on the three states. Pure.
 */
public sealed interface StationFact {
    /** The item data that names the station is not in yet (a server join reads it after the first ticks): wait, say so. */
    record NotLoaded(String why) implements StationFact {}

    /** Loaded, and no station of the kind is known anywhere (my placements, Baritone's scan); {@code why} says which part was empty. */
    record None(String why) implements StationFact {}

    /** The known stations, nearest first; never empty. */
    record Known(List<Spot> spots) implements StationFact {
        public Known {
            if (spots == null || spots.isEmpty()) throw new IllegalArgumentException("a Known station fact holds at least one station");
            spots = List.copyOf(spots);
        }

        public Spot nearest() { return spots.get(0); }
    }

    /** The line text of a not-loaded answer (stationsLine, the waiting step's status). */
    String NOT_LOADED_LINE = "stations: item data not loaded yet";
}
