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
        return of(what, got, want, end, null, null);
    }

    /**
     * 0.39.z36 (S42): with the step's source ({@code source}: its block or kind, {@code at}: where): an end with none of it
     * got is the source's changed fact (unreachable, in the process's own words), as the mine end says it since z33; the
     * order re-plans without that source.
     */
    static Car.Status of(String what, int got, int want, String end, String source, String at) {
        if (got >= want) return Car.Status.done("ok: " + what + ": got " + got + " (" + end + ")");
        zeroGot(got, source, at, end);
        return Car.Status.failed(what + ": " + end + " (got " + got + " of " + want + ")");
    }

    /** 0.39.z36: any collect end with 0 got, whatever the process: the source fact (null source: not a block source, nothing said). */
    static void zeroGot(int got, String source, String at, String end) {
        if (got <= 0 && source != null) io.github.mojolowjo.entropybot.aa.moves.FactChange.unreachable(source, at, end);
    }
}
