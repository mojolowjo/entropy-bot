package io.github.mojolowjo.entropybot.nav.search;

import io.github.mojolowjo.entropybot.nav.moves.MoveCatalog;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;

/**
 * nav step 6: one step of a found path, what Baritone keeps as a {@code Movement} object minus the driving: from, to,
 * which catalog move it is, and its price in ticks (Baritone's: the smaller of the move's own price and what the
 * search paid for it, so a favoured step is not charged its favouring). The driver (step 8) turns a Step into keys.
 *
 * <p>Pure (JUnit: SearchMatchForkTest). Loader notes: none.
 */
public record Step(BetterBlockPos src, BetterBlockPos dest, MoveCatalog move, double cost) {}
