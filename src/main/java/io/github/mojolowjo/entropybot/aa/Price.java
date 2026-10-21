package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.Unit;

/**
 * AA_ROUND4A O: the shared price terms of the station, bed, base and bag option sets. Each number is read by name through
 * {@link Tuning} (piece T adds the entries); until it has one, the named default here is used (TODO T: move each default
 * into Tuning: station.craftS 0.5, station.placeS 0.25, station.near 48, station.nearGuess 16, climbPerBlockS 1.5,
 * base.houseS 300, base.nightW 1, bag.depositS 5, bag.dropS 2). Seconds per TIME_MODEL, priced in walk-blocks (the
 * Options unit). Pure.
 */
final class Price {
    private Price() {}

    /** The tuned value of {@code name}, or {@code def} while Tuning has no such entry. */
    static double tune(String name, double def) {
        double v = Tuning.get(name);
        return Double.isNaN(v) ? def : v;
    }

    /** Seconds as walk-blocks. */
    static double s(double seconds) { return Unit.blocks(seconds); }

    static double craftS() { return tune("station.craftS", 0.5); }

    static double placeS() { return tune("station.placeS", 0.25); }

    /** Under ground: the climb out before a surface way, {@code depth} blocks up (0 on the surface). */
    static double climb(boolean under, int depth) {
        return under ? s(Math.max(1, depth) * tune("climbPerBlockS", 1.5)) : 0;
    }

    static String r(double v) { return String.valueOf(Math.round(v)); }
}
