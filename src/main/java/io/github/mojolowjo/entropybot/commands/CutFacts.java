package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.NeedChain;
import io.github.mojolowjo.entropybot.aa.moves.ChopSet;
import io.github.mojolowjo.entropybot.vocab.Kinds;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The game's side of a cut's block set (aa/moves/ChopSet), in one place for its three readers (rule 23 (2); the review of
 * w058-2: the hand left the owner's excluded log kinds out, the planner's lookup did not): the hand (StepHands.chop), the
 * planner's tree lookup (AaRuntime.nearestSource, the live scan's Nearby.Scan#cutLeftOut) and the planner's refusal up
 * front (AaRuntime's Lookup#refused). The reads: the game's logs tag (BlockTags.LOGS) and the owner's logs kind
 * (VocabCommands.expand("logs"): the tag's items less "kinds logs exclude"), by a block's own item.
 * Loader notes: vanilla registries and tags only.
 */
final class CutFacts {
    private static final Logger LOG = LoggerFactory.getLogger("entropybot");

    private final Commands c;
    /** The owner's logs kind as last expanded, and the rule it was expanded for (a changed rule expands again). */
    private Kinds.Rule kindRule;
    private Set<String> kind = Set.of();
    /** The block ids already said as left out of the tree lookups (once per kind while it stays left out; rule 7). */
    private final Set<String> said = new HashSet<>();

    CutFacts(Commands c) { this.c = c; }

    /** The game tags the block as a log. */
    static boolean isLog(String block) {
        return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.tryParse(block)).map(b -> b.defaultBlockState().is(BlockTags.LOGS)).orElse(false);
    }

    /** The owner's logs kind holds the block's own item. */
    boolean inLogsKind(String block) {
        Kinds.Rule r = c.vocab.rule("logs");
        if (!r.equals(kindRule)) {
            kind = new HashSet<>(c.vocab.expand("logs"));
            kindRule = r;
            said.clear();
        }
        return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.tryParse(block)).map(b -> kind.contains(BuiltInRegistries.ITEM.getKey(b.asItem()).toString())).orElse(false);
    }

    /** The hand's block set of a cut for {@code word} (a full need word, Step.ChopTree#word). */
    ChopSet.Pick pick(String word) {
        var book = c.aaRuntime == null ? null : c.aaRuntime.currentLootBook();
        return ChopSet.of(word, book, sources(word, book), CutFacts::isLog, (Predicate<String>) this::inLogsKind);
    }

    /** The planner's refusal up front (ChopSet.noSource); null: none, or the loot data is not loaded. */
    String noSource(String word) {
        var book = c.aaRuntime == null ? null : c.aaRuntime.currentLootBook();
        return ChopSet.noSource(word, book, sources(word, book), CutFacts::isLog, (Predicate<String>) this::inLogsKind);
    }

    /**
     * The tree lookups' answer for one log block (Nearby.Scan#cutLeftOut): why the cut leaves it out, null: it may be cut.
     * Said in the log once per block kind while it stays left out (the lookups run every few ticks).
     */
    String leftOut(String block) {
        String why = ChopSet.leftOut(block, CutFacts::isLog, this::inLogsKind);
        if (why != null && said.add(block))
            LOG.info("[entropybot] nearby: {} is left out of the tree lookups ({}): no tree of it is planned or cut", NeedChain.shw(block), why);
        return why;
    }

    /** The blocks that give the word: the collect's own source set (StepHands.plainSources for an item, the lookup's for a tag word). */
    private Set<String> sources(String word, io.github.mojolowjo.entropybot.aa.brain.LootBook book) {
        if (book == null || !book.ready() || c.aaRuntime == null) return Set.of();
        return NeedChain.plain(word) ? StepHands.plainSources(word, book) : c.aaRuntime.sourceBlocksOf(word);
    }
}
