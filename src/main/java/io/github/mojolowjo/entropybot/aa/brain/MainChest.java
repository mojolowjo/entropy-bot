package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;

import java.util.function.Supplier;

/**
 * deposit-goal plan, piece 3 (the reader part; plan step 1 answers, section 3: stationAt("container") is NOT the main
 * chest): the one reader of the main chest (rule 23). The runtime installs {@link #FACT} from MainStations' container
 * main, SimGame and OrderLineSim from their own main chest cell; every reader asks {@link #of}. Three answers, each with
 * its reason (rule 15: what is not known is said, never priced as zero). No Minecraft or Baritone types. Loader notes: none.
 */
public final class MainChest {

    private MainChest() {}

    /** The blackboard fact: a {@code Supplier<Answer>}. */
    public static final String FACT = "aa.mainChest";

    /** What the reader answers. */
    public sealed interface Answer permits None, NotLoaded, Known {
        /** The cell as "x y z" (the deposit lines' text); null: none set. */
        String at();
    }

    /** No main chest: {@code why} says why (none set, no reader installed). */
    public record None(String why) implements Answer {
        public String at() { return null; }
    }

    /** A main chest is set at this cell, and its chunk is not loaded now: its block cannot be read. */
    public record NotLoaded(int x, int y, int z) implements Answer {
        public String at() { return x + " " + y + " " + z; }
    }

    /**
     * A main chest is set at this cell and its chunk is loaded. {@code bonusS}: seconds the chest adds to a deposit's price
     * beyond its walk; 0 until a stage prices it (the deposit goal's later pieces).
     */
    public record Known(int x, int y, int z, double bonusS) implements Answer {
        public String at() { return x + " " + y + " " + z; }
    }

    /** The answer on {@code bb}; no reader installed: None, said (rule 7). */
    @SuppressWarnings("unchecked")
    public static Answer of(Blackboard bb) {
        Object f = bb == null ? null : bb.fact(FACT);
        if (!(f instanceof Supplier<?> s)) return new None("no main chest reader installed");
        Object a = ((Supplier<Object>) s).get();
        return a instanceof Answer ans ? ans : new None("the main chest reader answered nothing");
    }

    /**
     * The answer for a cell given as "x y z" text (SimGame, OrderLineSim): null or unreadable: None. {@code loaded} null:
     * loaded.
     */
    public static Answer ofText(String at, java.util.function.Predicate<int[]> loaded) {
        if (at == null) return new None("no main chest set (aa deposit x y z)");
        String[] p = at.trim().split(" ");
        if (p.length != 3) return       // structural: x y z
 new None("the main chest cell \"" + at + "\" is not x y z");
        int[] c = new int[3];                       // structural: x y z
        try {
            for (int i = 0; i < 3; i++) c[i] = Integer.parseInt(p[i]);      // structural: x y z
        } catch (NumberFormatException e) {
            return new None("the main chest cell \"" + at + "\" is not x y z");
        }
        if (loaded != null && !loaded.test(c)) return new NotLoaded(c[0], c[1], c[2]);
        return new Known(c[0], c[1], c[2], 0);      // structural: no bonus priced yet (see Known)
    }
}
