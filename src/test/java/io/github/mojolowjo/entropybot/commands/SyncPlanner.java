package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.NeedChain;
import io.github.mojolowjo.entropybot.aa.PlanNodes;
import io.github.mojolowjo.entropybot.aa.Playbook;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * job-own-aside-worker (worlds run 4: OwnAskAsideTest never ran through the worker and the simulator priced every chain on
 * the tick, so neither saw the held -d amount the worker counted twice): the planner worker's path, synchronously, for the
 * simulators (SimGame, OrderLineSim). Each ask is resolved as the game resolves it - a PlannerSnapshot of the live World,
 * PlannerWorker.resolve on it (offThread: the frozen facts, the start bag), PlannerPort.check against the live World - and
 * asked again with the facts it read until the check holds (the port's fetch rounds), at most PlannerPort.ROUNDS rounds.
 * A check that finds a fact or the bag changed although nothing moved in between (the sim's world stands still while it
 * thinks) is a planner fault: said through {@code said} and kept in {@link #drops}, and that answer is taken (the game
 * takes it after planner.dropsBeforeTake rounds). A resolve that throws is said and resolved on the tick (the port's rule 7).
 */
public final class SyncPlanner implements BatchWay.Planner {
    private final Consumer<String> said;
    private final Map<String, Set<PlanNodes.Fact>> keys = new HashMap<>();
    private long version;
    /** Every drop the live check found (a planner fault in a world that stands still). */
    public final List<String> drops = new ArrayList<>();

    public SyncPlanner(Consumer<String> said) { this.said = said == null ? s -> { } : said; }

    @Override
    public NeedChain.Legs legs(String key, Playbook pb, Set<String> keptFuel, NeedChain.World w) { return ask(key, pb, keptFuel, null, w); }

    @Override
    public boolean ready(String key) { return false; }                 // never a thinking stand: the answer is there at once

    @Override
    public NeedChain.Chain own(String key, BatchWay.OwnAsk ask, NeedChain.World w) { return ask(key, null, Set.of(), ask, w).chain(); }

    private NeedChain.Legs ask(String key, Playbook pb, Set<String> kept, BatchWay.OwnAsk own, NeedChain.World w) {
        Set<PlanNodes.Fact> k = keys.computeIfAbsent(key, x -> new HashSet<>());
        PlannerWorker.Done d = null;
        boolean held = false;
        for (int round = 0; round < PlannerPort.ROUNDS && !held; round++) {
            Map<PlanNodes.Fact, Object> live = new HashMap<>();
            d = PlannerWorker.resolve(new PlannerWorker.Job(key, ++version, pb, kept == null ? Set.of() : Set.copyOf(kept), PlannerSnapshot.take(w, k, live, version, 0), own));
            if (d.error() != null) {
                said.accept("planner (sync): " + key + " could not be resolved off the tick (" + d.error() + "): resolved on the tick");
                if (own != null) return new NeedChain.Legs(own.resolve(w), Map.of(), -1);
                return NeedChain.keepingFuel(kept, () -> NeedChain.resolvePlaybookLegs(pb, w));
            }
            List<String> changed = PlannerPort.check(d, w, live);
            k.addAll(d.read().facts().keySet());
            held = changed.isEmpty();
            if (held) break;
            List<String> real = new ArrayList<>();
            for (String c : changed) if (!c.startsWith(PlannerPort.MISS)) real.add(c);
            if (!real.isEmpty()) {
                String line = "planner (sync): " + key + ": dropped on facts that did not move: " + real.get(0) + (real.size() > 1 ? " (+" + (real.size() - 1) + " more)" : "") + "; this answer is taken";
                drops.add(line);
                said.accept(line);
                held = true;
            }
        }
        if (!held) {
            String line = "planner (sync): " + key + ": still fetching facts after " + PlannerPort.ROUNDS + " rounds; the last answer is taken";
            drops.add(line);
            said.accept(line);
        }
        d.read().runEffects(w.path());
        return d.legs();
    }
}
