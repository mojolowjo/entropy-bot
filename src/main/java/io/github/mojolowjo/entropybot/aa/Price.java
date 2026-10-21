package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.Unit;
import io.github.mojolowjo.entropybot.commands.VerbTable;

import java.util.List;

/**
 * AA_ROUND4A O: the shared price terms of the station, bed, base and bag option sets, each read by name through
 * {@link Tuning} (station.*, base.*, bag.*, climbPerBlockS, danger.*). Seconds per TIME_MODEL, priced in walk-blocks
 * (the Options unit). {@link #add} also drops a row whose chain has a step with no verb in {@link VerbTable} (with the
 * reason), so no row can run a chain the parser refuses. Pure.
 */
final class Price {
    private Price() {}

    /** The tuned value of {@code name}; an unknown name is a bug and fails loudly. */
    static double tune(String name) {
        double v = Tuning.get(name);
        if (Double.isNaN(v)) throw new IllegalStateException("no tuning \"" + name + "\"");
        return v;
    }

    /** Seconds as walk-blocks. */
    static double s(double seconds) { return Unit.blocks(seconds); }

    static double craftS() { return tune("station.craftS"); }

    static double placeS() { return tune("station.placeS"); }

    /** Under ground: the climb out before a surface way, {@code depth} blocks up (0 on the surface). */
    static double climb(boolean under, int depth) {
        return under ? s(Math.max(1, depth) * tune("climbPerBlockS")) : 0;
    }

    static String r(double v) { return String.valueOf(Math.round(v)); }

    /** The first step of {@code chain} whose verb the parser does not know, or null (all known, or the "nothing" row). */
    static String unknownVerb(String chain) {
        if (chain == null || Options.NOTHING.equals(chain)) return null;
        for (String step : Options.Chains.split(chain)) {
            String w = step.split("\\s+")[0];
            if (VerbTable.of(w) == null) return step;
        }
        return null;
    }

    /** A row in, unless held (its reason) or its chain has an unknown verb (dropped: "no verb for ..."). */
    static void add(List<Options.Option> rows, List<String> dropped, Options.OptionMemory m, long now, Options.Option o) {
        String bad = o.typed() ? null : unknownVerb(o.command());      // rm-execution: typed steps have no verb to look up
        if (bad != null) dropped.add(o.id() + ": no verb for \"" + bad + "\"");
        else RungOptions.add(rows, dropped, m, now, o);
    }
}
