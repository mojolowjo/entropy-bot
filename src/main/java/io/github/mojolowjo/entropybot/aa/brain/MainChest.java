package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * deposit-goal plan, piece 3 (the reader part; plan step 1 answers, section 3: stationAt("container") is NOT the main
 * chest): the one reader of the main chest (rule 23). The runtime installs {@link #FACT} from MainStations' container
 * main, SimGame and OrderLineSim from their own main chest cell; every reader asks {@link #of}. Four answers, each with
 * its reason (rule 15: what is not known is said, never priced as zero). Review 4 (point 1): a cell whose block is loaded
 * and is no container, or a chest that refused the put (full, gone), answers {@link Held} with the reason, kept by the one
 * hold store ({@link MainChestHold}) while it is true. No Minecraft or Baritone types. Loader notes: none.
 */
public final class MainChest {

    private MainChest() {}

    /** The blackboard fact: a {@code Supplier<Answer>}. */
    public static final String FACT = "aa.mainChest";

    /** What the reader answers. */
    public sealed interface Answer permits None, NotLoaded, Known, Held {
        /** The cell as "x y z" (the deposit lines' text); null: none set. */
        String at();
    }

    /** No main chest: {@code why} says why (none set, no reader installed). */
    public record None(String why) implements Answer {
        public String at() { return null; }
    }

    /**
     * A main chest is set at this cell, and what it is cannot be read now ({@code why}): its chunk is not loaded, or (review
     * 7, rule 15) the item data that says whether its block is a container is not loaded yet.
     */
    public record NotLoaded(int x, int y, int z, String why) implements Answer {
        /** The chunk's words: the block cannot be read. */
        public static final String CHUNK = "its chunk is not loaded";

        public NotLoaded(int x, int y, int z) { this(x, y, z, CHUNK); }

        public String at() { return x + " " + y + " " + z; }
    }

    /** A main chest is set at this cell, its chunk is loaded and its block is a container. */
    public record Known(int x, int y, int z) implements Answer {
        public String at() { return x + " " + y + " " + z; }
    }

    /**
     * Review 4 (point 1): a main chest is set at this cell and it cannot take the put now: {@code why} (its block is no
     * container, or the put found it full or gone and nothing that could change that has happened since,
     * {@link MainChestHold}).
     */
    public record Held(int x, int y, int z, String why) implements Answer {
        public String at() { return x + " " + y + " " + z; }
    }

    /**
     * The answer on {@code bb}; no reader installed: None, said (rule 7). A hold store on the board ({@link MainChestHold#FACT})
     * is applied to the reader's answer, and what it lifts or records is said in the log and to the owner, once.
     */
    @SuppressWarnings("unchecked")
    public static Answer of(Blackboard bb) {
        Object f = bb == null ? null : bb.fact(FACT);
        if (!(f instanceof Supplier<?> s)) return new None("no main chest reader installed");
        Object a = ((Supplier<Object>) s).get();
        Answer ans = a instanceof Answer x ? x : new None("the main chest reader answered nothing");
        if (!(bb.fact(MainChestHold.FACT) instanceof MainChestHold h)) return ans;
        // review 7 (point 4): what is owed now (the one owed store), so a hold on refused kinds lifts when none is owed
        java.util.Set<String> owedIds = null;
        if (bb.fact(LineDeposits.FACT) instanceof LineDeposits ld) {
            owedIds = new java.util.HashSet<>();
            for (String k : ld.owed().keySet()) owedIds.add(io.github.mojolowjo.entropybot.aa.NeedChain.id(k));
        }
        MainChestHold.Applied out = h.apply(ans, owedIds);
        if (out.line() != null && bb.port != null) {
            bb.port.log(out.line());
            bb.port.tellOwner(out.line());
        }
        return out.answer();
    }

    /**
     * The answer for a cell given as "x y z" text (SimGame, OrderLineSim): null or unreadable: None. {@code loaded} null:
     * loaded. {@code container} null: the block is a container; false for the cell: {@link Held}, its block is no container.
     */
    public static Answer ofText(String at, Predicate<int[]> loaded, Predicate<int[]> container) {
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
        if (container != null && !container.test(c)) return gone(c[0], c[1], c[2], "its block is no container");
        return new Known(c[0], c[1], c[2]);
    }

    /**
     * Review 7 (point 1, rules 15 and 23): the answer for the main chest set at x y z whose block reads {@code blockId}
     * (null: its chunk is not loaded), by the one container reader ({@link ItemWorth#containerOf}): item data not loaded is
     * {@link NotLoaded} with that reason, never "gone". The runtime's reader (AaRuntime.mainChest) answers through here.
     */
    public static Answer ofBlock(int x, int y, int z, String blockId) {
        if (blockId == null) return new NotLoaded(x, y, z);
        return switch (ItemWorth.containerOf(blockId)) {
            case NOT_LOADED -> new NotLoaded(x, y, z, "the item data is not loaded yet, so whether its block " + blockId + " is a container is not known");
            case NO -> gone(x, y, z, "its block is " + blockId + ", no container");
            case YES -> new Known(x, y, z);
        };
    }

    /** {@link #ofText(String, Predicate, Predicate)} with the block a container. */
    public static Answer ofText(String at, Predicate<int[]> loaded) { return ofText(at, loaded, null); }

    /** The words of a main chest whose loaded block is no container ({@code what}: the block's words). */
    public static Held gone(int x, int y, int z, String what) {
        return new Held(x, y, z, MainChestHold.GONE_WORDS + x + " " + y + " " + z + " is gone (" + what + ")");
    }
}
