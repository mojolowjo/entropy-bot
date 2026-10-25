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
        return of(what, got, want, end, source, at, -1);
    }

    /**
     * 0.39.z66 item 2 (run X 08:41:01: "collect 3 iron_ore: have 19 valid items (cluster 9 blocks: picked up 9) (got 0 of
     * 3)" read as "unreachable"): {@code drops}: what the bag gained of the blocks' drops as Baritone counts them (its
     * filter's items; -1: not known). The blocks were reached and dropped other items than the step counts - the source's
     * fact says so (the order re-plans without it, rule 7), never "unreachable".
     */
    static Car.Status of(String what, int got, int want, String end, String source, String at, int drops) {
        if (got >= want) return Car.Status.done("ok: " + what + ": got " + got + " (" + end + ")");
        if (drops > got) {
            String why = otherDrops(what, got, want, drops, end);
            if (source != null) io.github.mojolowjo.entropybot.aa.moves.FactChange.otherDrops(source, at, why);
            return Car.Status.failed(why);
        }
        zeroGot(got, source, at, end);
        return Car.Status.failed(what + ": " + end + " (got " + got + " of " + want + ")");
    }

    /** 0.39.z66 item 2: the words of a collect whose blocks dropped other items than it counts. */
    static String otherDrops(String what, int got, int want, int drops, String end) {
        return what + ": the blocks dropped other items (" + drops + " of their drops picked up, " + got + " of " + want
                + " of the item; " + end + ")";
    }

    /** 0.39.z36: any collect end with 0 got, whatever the process: the source fact (null source: not a block source, nothing said). */
    static void zeroGot(int got, String source, String at, String end) {
        if (got <= 0 && source != null) io.github.mojolowjo.entropybot.aa.moves.FactChange.unreachable(source, at, end);
    }
}
