package io.github.mojolowjo.entropybot.aa.moves;

import io.github.mojolowjo.entropybot.aa.NeedChain;
import io.github.mojolowjo.entropybot.aa.brain.LootBook;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * The block kinds a cut may break for the word it is for (Step.ChopTree#word): the blocks that give the word by the
 * game's loot data (the collect's own source set, handed in: Nearby.sourceBlocks for an item, the lookup's sourceBlocksOf
 * for a tag word), kept only where the game tags the block as a log (so the hand stays the chop) and the owner's logs
 * kind holds it ("kinds logs exclude"). One fact for the price and the hand (rule 23): the planner's tree lookup
 * (AaRuntime.nearestSource) reads the same source set, passes over a log the kind leaves out by the same test
 * ({@link #leftOut}, said in the lookup's source line), sizes a tree by the connected cells of that set only
 * (Nearby.treeCut) and refuses a word no kept log gives before any walk or explore ({@link #noSource}).
 * <p>Cloud tester 2, probe round 3, w058-2 (2026-11-23): "aa want acacia_log 16" ran "cut 1 logs - Baritone's chop
 * process on 20 block kind(s)", every break line named acacia_wood (bark blocks, their drop is acacia_wood), the bag
 * ended with acacia_wood 47 and acacia_log 1 and the next cut read "until 67 in the bag" (51 + 16): the filter was the
 * whole logs tag and the count every item of it.
 * <p>Rule 15: a loot book that is not loaded is "not known" (the step refuses with the reason), never the whole tag.
 * Pure (JUnit: ChopSetTest). Loader notes: none (the game's reads are handed in).
 */
public final class ChopSet {
    private ChopSet() {}

    /** The start of the refusal while the loot data is not loaded (rule 15: not known, never "none" and never every log). */
    public static final String NOT_KNOWN = "not known which log blocks drop ";

    /**
     * One cut's block set. {@code kept}: the block ids to cut, in the source set's order; {@code notLogs}: sources the game
     * does not tag as logs (another hand's blocks: leaves, a chest); {@code excluded}: log sources the owner's logs kind
     * leaves out; {@code refusal}: null when there is a set to cut, else why not (the step's error words).
     */
    public record Pick(List<String> kept, List<String> notLogs, List<String> excluded, String refusal) {
        public Pick {
            kept = List.copyOf(kept);
            notLogs = List.copyOf(notLogs);
            excluded = List.copyOf(excluded);
        }

        public boolean ok() { return refusal == null; }

        /** The step line's words: "kept acacia_log; left out: none". */
        public String line() {
            return "kept " + (kept.isEmpty() ? "none" : shorts(kept)) + "; left out: " + leftOut();
        }

        String leftOut() {
            List<String> parts = new ArrayList<>();
            if (!excluded.isEmpty()) parts.add(shorts(excluded) + " (" + EXCLUDED + ")");
            if (!notLogs.isEmpty()) parts.add(shorts(notLogs) + " (not logs by the game's tag)");
            return parts.isEmpty() ? "none" : String.join(", ", parts);
        }
    }

    /**
     * The cut's blocks for {@code word} (an item id, a tag "#ns:path", several joined by "|"). {@code book}: the world's loot
     * book (null or still read: the not-known refusal); {@code sources}: the blocks that give the word (the collect's source
     * set, read from that book); {@code isLog}: the game tags the block as a log; {@code inLogsKind}: the owner's logs kind
     * holds the block's item.
     */
    public static Pick of(String word, LootBook book, Collection<String> sources, Predicate<String> isLog, Predicate<String> inLogsKind) {
        String w = NeedChain.shw(NeedChain.word(word));
        if (book == null || !book.ready()) {
            String why = book == null ? "the loot book is not read yet" : book.waiting();
            return new Pick(List.of(), List.of(), List.of(), NOT_KNOWN + w + " (" + why + "; rule 15: not known, the whole logs tag is not cut instead)");
        }
        List<String> kept = new ArrayList<>(), notLogs = new ArrayList<>(), excluded = new ArrayList<>();
        if (sources != null) for (String b : sources) {
            if (b == null || kept.contains(b) || notLogs.contains(b) || excluded.contains(b)) continue;
            if (!isLog.test(b)) notLogs.add(b);
            else if (leftOut(b, isLog, inLogsKind) != null) excluded.add(b);
            else kept.add(b);
        }
        Pick p = new Pick(kept, notLogs, excluded, null);
        if (!kept.isEmpty()) return p;
        return new Pick(kept, notLogs, excluded, "no log block drops " + w + " (the game's loot data" + (book.isEmpty() ? ", which has no entry in this world" : "")
                + "; left out: " + p.leftOut() + ")");
    }

    /** The words of a log block the owner's logs kind leaves out (the step line, the lookup's source line, the refusals). */
    public static final String EXCLUDED = "kinds logs exclude";

    /**
     * Review of w058-2 (rule 23 (2): the hand left the owner's excluded kinds out, the planner's lookup did not, so an
     * excluded tree was planned and priced and every cut of it refused): the one kind test of a cut, read by the hand
     * ({@link #of}) and by the planner's tree lookup (Nearby.Scan#cutLeftOut). Why a log block is no block of a cut:
     * {@link #EXCLUDED} when the game tags it as a log and the owner's logs kind does not hold it; null: it may be cut, or
     * it is no log (another hand's block, not this rule's).
     */
    public static String leftOut(String block, Predicate<String> isLog, Predicate<String> inLogsKind) {
        return block != null && isLog.test(block) && !inLogsKind.test(block) ? EXCLUDED : null;
    }

    /**
     * The planner's refusal up front (the same fact as the hand's "no log block drops ..."): every block that gives
     * {@code word} is a log the owner's logs kind leaves out, so no tree seen or found by an explore could ever be cut for
     * it. Null: something may be cut or collected for it, or the loot data is not loaded (rule 15: not known is never a no).
     */
    public static String noSource(String word, LootBook book, Collection<String> sources, Predicate<String> isLog, Predicate<String> inLogsKind) {
        if (book == null || !book.ready()) return null;
        Pick p = of(word, book, sources, isLog, inLogsKind);
        if (p.ok() || p.excluded().isEmpty() || !p.notLogs().isEmpty()) return null;
        return "no " + NeedChain.shw(NeedChain.word(word)) + " source: every log block that drops it is left out of the owner's logs kind ("
                + shorts(p.excluded()) + ": " + EXCLUDED + "; \"kinds logs include <id>\" lets it be cut)";
    }

    private static String shorts(List<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) out.add(NeedChain.shw(id));
        return String.join(", ", out);
    }
}
