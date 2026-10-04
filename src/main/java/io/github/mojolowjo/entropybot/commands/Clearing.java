package io.github.mojolowjo.entropybot.commands;

import java.util.List;
import java.util.Map;

import io.github.mojolowjo.entropybot.clear.ClearJob;
import net.minecraft.client.player.LocalPlayer;

/**
 * B7d contract (2026-10-03). D1 (dig/build/place) implements this class; D2 (strip mine) and D3 (caves, explore,
 * mine) only build steps with the two factories and read {@link Outcome} from the step after it ran. The public
 * surface below (factory signatures, Step fields {@code clear}/{@code cleared}/{@code id}/{@code pos}, the Outcome
 * record) is fixed; D1 may add to it but not change it.
 *
 * <p>Step semantics: a "clear" step runs one careful clear of {@code opts.box} (or {@code opts.only}) the way
 * startClear did (leases, reach, sight, torches, drops, pickaxe restock by a spliced craft, fights and meals hold it),
 * stores the result in {@code step.cleared} and returns "next" when it ended normally (everything cleared, or what is
 * left was left for a reason the report names); it returns an error text (the Seq ends with it) only when it could not
 * run at all or {@code opts.mustFinish} was set and blocks are left. A "placeblock" step places one {@code step.id}
 * at {@code step.pos} (walking into reach first, taking a place lease, never breaking anything) and returns "next", or
 * an error text.
 */
public final class Clearing {
    private Clearing() {}

    /** What a clear step did; set on the step when it ends. */
    public record Outcome(boolean ok, int broken, int left, Map<String, Integer> mined, List<String> ores,
            String message) {}

    public static Seq.Step clearStep(ClearJob.Options opts) {
        Seq.Step s = new Seq.Step("clear");
        s.clear = opts;
        return s;
    }

    public static Seq.Step placeStep(int[] pos, String item) {
        Seq.Step s = new Seq.Step("placeblock");
        s.pos = pos;
        s.id = item;
        return s;
    }

    /** Seq hands "clear" and "placeblock" steps here. D1 implements. */
    static String step(Seq seq, Seq.Step st, LocalPlayer p, long elapsed) {
        return "not built yet: the " + st.type + " step (B7d D1)";
    }
}
