package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.FakePaths;
import io.github.mojolowjo.entropybot.aa.NeedChain;
import io.github.mojolowjo.entropybot.aa.PlanNodes;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The second review of w058-2 (rule 23 (2), rule 7): the planner's refusal up front ("source refused": every block that
 * gives the word is a log the owner's logs kind leaves out) was read through the live World's Lookup only. The planner
 * worker resolves on PlannerSnapshot's frozen World, whose lookup was a plain function: off the render thread the fact
 * always answered "none said", the worker planned an explore, the live check dropped it every round ("re-planned while
 * thinking: source refused ...") and after planner.dropsBeforeTake rounds the explore was taken. Here: the snapshot carries
 * the fact, the worker's result passes PlannerPort's check and is the refusal, with no drop and no take.
 */
class CutRefusedSnapshotTest {
    static final String OAK = "minecraft:oak_log";
    static final String REFUSAL = "no oak_log source: every log block that drops it is left out of the owner's logs kind (oak_log: kinds logs exclude; \"kinds logs include <id>\" lets it be cut)";
    static final PlanNodes.Fact FACT = new PlanNodes.Fact("source refused", OAK);

    @BeforeEach
    void on() {
        PlanNodes.forgetMine();
        PlanNodes.on(true);
        D6WorkerTest.data();
    }

    @AfterEach
    void off() {
        PlanNodes.defaultOn();
        PlanNodes.forgetMine();
        TestData.clear();
        Tuning.reset();
        BatchWay.clear();
    }

    /** The live World: no tree known; {@code refused}: the owner's logs kind leaves the oak log out. */
    static NeedChain.World live(boolean refused) {
        NeedChain.Lookup none = (word, skip) -> List.of();
        return FakePaths.paths(new NeedChain.World(Map.of(), false, false,
                refused ? none.refusing(word -> word.equals(OAK) ? REFUSAL : null) : none, null, null, null, 0));
    }

    static final BatchWay.OwnAsk ASK = new BatchWay.OwnAsk(OAK, List.of(OAK), 16, false);

    @Test
    void theSnapshotCarriesTheRefusal_notCopiedIsNoneSaid() {
        NeedChain.World w = live(true);
        // the first resolve's snapshot copied nothing: none said (not known, never a no: the read is a fetch)
        NeedChain.World first = PlannerSnapshot.take(w, List.of(), null, 1, 0).world();
        assertInstanceOf(NeedChain.Lookup.class, first.seenAll(), "the frozen lookup is a Lookup as the live one is");
        assertNull(((NeedChain.Lookup) first.seenAll()).refused(OAK));
        // the fact copied: the frozen World answers as the live one does, and so does the one ask both go through
        PlannerSnapshot snap = PlannerSnapshot.take(w, List.of(FACT), null, 2, 0);
        assertTrue(snap.copied(FACT));
        assertEquals(REFUSAL, ((NeedChain.Lookup) snap.world().seenAll()).refused(OAK));
        assertEquals(REFUSAL, PlannerPort.timedAsk(snap.world(), FACT));
        assertEquals(PlannerPort.timedAsk(w, FACT), PlannerPort.timedAsk(snap.world(), FACT), "two readers of one fact agree");
        assertNull(((NeedChain.Lookup) snap.world().seenAll()).refused("minecraft:acacia_log"), "another word: not copied, none said");
        // an order's held spots (World#without) keep it on the frozen World too
        assertEquals(REFUSAL, ((NeedChain.Lookup) snap.world().without((word, sp) -> false).seenAll()).refused(OAK));
        // a live World that says none: copied as none
        PlannerSnapshot open = PlannerSnapshot.take(live(false), List.of(FACT), null, 3, 0);
        assertTrue(open.copied(FACT));
        assertNull(((NeedChain.Lookup) open.world().seenAll()).refused(OAK));
    }

    @Test
    void theWorkersResultOnTheSnapshotPassesTheLiveCheck() {
        NeedChain.World w = live(true);
        // round 1: nothing copied - the worker plans the explore; the check finds the fact differs, as a fetch
        PlannerWorker.Done one = PlannerWorker.resolve(new PlannerWorker.Job("o1", 1, null, Set.of(), PlannerSnapshot.take(w, List.of(), null, 1, 0), ASK));
        assertNull(one.error(), String.valueOf(one.error()));
        assertTrue(one.read().facts().containsKey(FACT), "the resolve read the fact: " + one.read().facts().keySet());
        List<String> changed = PlannerPort.check(one, w, new java.util.HashMap<>());
        assertFalse(changed.isEmpty(), "the explore planned without the fact is never applied");
        assertTrue(changed.stream().allMatch(c -> c.startsWith(PlannerPort.MISS)), "a fetch, not a drop: " + changed);
        // round 2: the snapshot of the facts it read - the worker refuses as the live World does, and the check holds
        PlannerWorker.Done two = PlannerWorker.resolve(new PlannerWorker.Job("o1", 2, null, Set.of(), PlannerSnapshot.take(w, one.read().facts().keySet(), null, 2, 0), ASK));
        assertNull(two.error(), String.valueOf(two.error()));
        // (a fact first read in round 2 is one more fetch round: asked until the facts read are the facts copied)
        for (int round = 3; round < 3 + PlannerPort.ROUNDS && !PlannerPort.check(two, w, new java.util.HashMap<>()).isEmpty(); round++) {
            List<String> ch = PlannerPort.check(two, w, new java.util.HashMap<>());
            assertTrue(ch.stream().allMatch(c -> c.startsWith(PlannerPort.MISS)), "only fetches, never the refusal dropped: " + ch);
            two = PlannerWorker.resolve(new PlannerWorker.Job("o1", round, null, Set.of(), PlannerSnapshot.take(w, two.read().facts().keySet(), null, round, 0), ASK));
        }
        assertEquals(List.of(), PlannerPort.check(two, w, new java.util.HashMap<>()), "the worker's result holds on the live World");
        NeedChain.Chain c = two.legs().chain();
        assertFalse(c.explore(), c.why() + " " + c.steps());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Explore || s instanceof Step.ChopTree), c.steps().toString());
        assertTrue((c.why() + " " + c.dropped()).contains(REFUSAL), c.why() + " " + c.dropped());
    }

    @Test
    void throughThePort_theRefusalIsApplied_neverDroppedNeverTaken() throws InterruptedException {
        NeedChain.World w = live(true);
        List<String> log = new ArrayList<>();
        PlannerWorker worker = new PlannerWorker();
        PlannerPort port = new PlannerPort(worker, () -> 0, log::add);
        NeedChain.Chain c = port.own("o1", ASK, w);
        long until = System.nanoTime() + 30_000_000_000L;
        while (NeedChain.thinking(c)) {
            if (System.nanoTime() > until) fail("the planner did not answer within 30 s: " + c.why() + " " + log);
            long seen = worker.landed();
            while (worker.landed() == seen && worker.busy("o1") && System.nanoTime() < until) Thread.sleep(2);
            c = port.own("o1", ASK, w);
        }
        assertFalse(c.explore(), c.why() + " " + c.steps());
        assertTrue((c.why() + " " + c.dropped()).contains(REFUSAL), c.why() + " " + c.dropped());
        assertTrue(log.stream().noneMatch(l -> l.contains("re-planned while thinking")), "no drop on the refusal: " + log);
        assertTrue(log.stream().noneMatch(l -> l.contains("this answer is taken")), "no answer taken against the facts: " + log);
        // the same facts with no refusal said: the explore, as before
        NeedChain.Chain open = NeedChain.resolve(OAK, 16, live(false));
        assertTrue(open.explore(), open.why());
    }
}
