package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.moves.Car;

/**
 * The cut hand's end (owner 2026-10-22, "the tree chop job is not done until it picks up the drops"): the bag's gain
 * decides. Short of the want, the step fails with the chop's own end text ("tree 3 logs: picked up 2 of 3 ...",
 * "... drops left, unreachable"), never done; a bag that gained the want is done whatever the chop says. Pure (no
 * Baritone), so it is unit-tested.
 */
final class ChopEnd {
    private ChopEnd() {}

    static Car.Status of(String what, int got, int want, String end) {
        if (got >= want) return Car.Status.done("ok: " + what + ": got " + got + " (" + end + ")");
        return Car.Status.failed(what + ": " + end + " (got " + got + " of " + want + ")");
    }
}
