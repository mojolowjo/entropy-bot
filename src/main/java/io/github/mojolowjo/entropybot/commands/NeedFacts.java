package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.BagOptions;
import io.github.mojolowjo.entropybot.aa.BaseOptions;
import io.github.mojolowjo.entropybot.aa.BedOptions;
import io.github.mojolowjo.entropybot.aa.Options;
import io.github.mojolowjo.entropybot.aa.StationOptions;
import io.github.mojolowjo.entropybot.aa.WoodOptions;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Inputs;
import io.github.mojolowjo.entropybot.camp.JunkRules;

import io.github.mojolowjo.entropybot.aa.AaDesires;
import io.github.mojolowjo.entropybot.aa.brain.Brain;
import io.github.mojolowjo.entropybot.aa.brain.Costs;
import io.github.mojolowjo.entropybot.aa.brain.Failures;
import io.github.mojolowjo.entropybot.threat.ThreatFacts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * AA_ROUND4A O: the facts suppliers of the station, bed, base and bag option sets, registered by one call
 * {@link #install} (piece N wires it next to the other {@code bb.fact(...)} lines). They read only existing senses: the
 * filled {@link Inputs} ({@code bb.in}), the bag ({@code Inputs.bag}), the wood and bed facts AaRuntime already
 * builds; no new scans. The builders ({@link #station}, {@link #base}, {@link #bag}, {@link #bed}) are pure (JUnit:
 * NeedFactsTest); a supplier that throws is caught by the Picker (counted and logged, "facts not readable").
 * Loader notes: none of its own (the wood and bed facts are AaRuntime's client reads); the builders are loader-neutral.
 */
public final class NeedFacts {
    private NeedFacts() {}

    /**
     * Registers every supplier on the blackboard (station x 3, bed, base, bag). One wood memory for the station's cut
     * rows, bound to the brain's failure record ("station.wood") when one is on the board, so a failed cut stays held.
     */
    public static void install(Blackboard bb, AaRuntime rt) {
        Options.OptionMemory woodMem = new Options.OptionMemory();
        // review 4A-N (4): every supplier is read once per game tick (FactsMemo), as SURFACE_FACTS; the wood choice too
        Supplier<Options.Choice> wood = memo(() -> wood(bb, rt, woodMem));
        for (StationOptions.Kind k : StationOptions.Kind.values())
            bb.fact(k.factsKey(), memo(() -> station(k, bb.in, bb.in.bag, wood.get())));
        // round 4A N: the one station need reads the station it lacks now (table, then furnace, then the camp chest)
        bb.fact(AaDesires.STATION_FACTS, memo(() -> station(AaDesires.stationKind(bb.in), bb.in, bb.in.bag, wood.get())));
        bb.fact(BedOptions.FACTS, memo(() -> bed(rt.bedFacts(), bb.in)));
        bb.fact(BaseOptions.FACTS, memo(() -> base(bb.in, bb.in.bag, rt.areaCell(bb.in.basePlace), burrowCell(bb))));
        bb.fact(BagOptions.FACTS, memo(() -> bag(bb.in, bb.in.bag, bb.in.bag == null ? java.util.List.<String>of() : bb.in.bag.keySet().stream().filter(io.github.mojolowjo.entropybot.aa.brain.Junk::is).toList(), AaDesires.bagLeave())));      // the need's own band: slots it wants free
    }

    /** One read per game tick. */
    static <T> Supplier<T> memo(Supplier<T> s) { return AaRuntime.perTick(new io.github.mojolowjo.entropybot.aa.FactsMemo<>(0), s); }

    /** The wood set's best row now through the one memory (null: no wood facts; the station set drops its cut row with the reason). */
    private static Options.Choice wood(Blackboard bb, AaRuntime rt, Options.OptionMemory mem) {
        if (bb.fact(Brain.FAILURES_FACT) instanceof Failures f) mem.bind(f, AaDesires.STATION_ID);       // review 4A: one wood memory, the station need's (Rung's woodMem is gone)
        mem.sweep(bb.in);
        WoodOptions.WoodFacts f = rt.woodFacts();
        return f == null ? null : WoodOptions.choose(f, mem, bb.clock.now());
    }

    /** The danger facts' burrow check (ThreatFacts.Ways.burrow: a diggable floor that seals), false when not readable. */
    static boolean burrowCell(Blackboard bb) {
        if (!(bb.fact(AaDesires.THREAT_FACT) instanceof Supplier<?> s)) return false;
        return s.get() instanceof ThreatFacts tf && tf.ways() != null && tf.ways().burrow();
    }

    /** The bag by item path (Inputs.bag: "chest" -> 1); a namespaced id is looked up by its path. */
    static int count(Map<String, Integer> bag, String id) {
        if (bag == null) return 0;
        int c = id.indexOf(':');
        return bag.getOrDefault(c >= 0 ? id.substring(c + 1) : id, 0);
    }

    /**
     * A known table or furnace is only sensed as "near" (no cell), the base chests' cells are not in the senses either:
     * knownAt stays null and the station set drops its "use known" row with that reason (never a walk to nowhere).
     */
    public static StationOptions.StationFacts station(StationOptions.Kind k, Inputs in, Map<String, Integer> bag, Options.Choice wood) {
        boolean inBag = switch (k) {
            case TABLE -> in.tableInBag;
            case FURNACE -> in.furnaceInBag;
            case CHEST -> count(bag, "minecraft:chest") > 0;
        };
        boolean known = switch (k) {
            case TABLE -> in.tableKnown;
            case FURNACE -> in.furnaceKnown;
            case CHEST -> in.baseChests;
        };
        return new StationOptions.StationFacts(k, inBag, known, null, -1, in.planks, in.wood, in.cobble, in.pickTier, in.chestCell,
                wood == null ? null : wood.best(), wood == null ? null : wood.why(), in.underGround, in.underDepth);
    }

    /** AaRuntime's bed facts plus the bed, night and depth; with none readable, the bag and Inputs alone (no herd). */
    public static BedOptions.BedFacts bed(BedOptions.BedFacts old, Inputs in) {
        if (old == null) return new BedOptions.BedFacts(in.wool, 0, null, in.pos, in.bedKnown, in.night, in.underGround, in.underDepth);
        return new BedOptions.BedFacts(old.wool(), old.sheepInView(), old.herd(), old.me(), in.bedKnown, in.night, in.underGround, in.underDepth);
    }

    /** @param areaCell the marked area's centre (AaRuntime.areaCell), null none */
    public static BaseOptions.BaseFacts base(Inputs in, Map<String, Integer> bag, int[] areaCell, boolean burrowCell) {
        int blocks = in.cobble + in.planks + count(bag, "minecraft:dirt") + count(bag, "minecraft:cobbled_deepslate");
        double danger = in.night ? Costs.nightDanger(in.difficulty, in.armorPoints, in.health, in.maxHealth, in.torchesNear, in.hostilesNear) : 0;
        return new BaseOptions.BaseFacts(in.basePlace, areaCell, blocks, in.night, danger, in.underGround, in.underDepth, burrowCell);
    }

    /** @param want slots the need wants free */
    public static BagOptions.BagFacts bag(Inputs in, Map<String, Integer> bag, Collection<String> junk, int want) {
        int junkSlots = 0;
        List<io.github.mojolowjo.entropybot.aa.moves.Step.DropJunk> drops = new ArrayList<>();
        for (String id : junk) {
            int n = count(bag, id);
            if (n <= 0) continue;
            junkSlots += (n + 63) / 64;
            drops.add(new io.github.mojolowjo.entropybot.aa.moves.Step.DropJunk(id.indexOf(':') >= 0 ? id.substring(id.indexOf(':') + 1) : id, n));
        }
        return new BagOptions.BagFacts(in.freeSlots, want, in.baseChests, in.baseDist, in.basePos, count(bag, "minecraft:chest") > 0,
                in.planks, in.wood, in.chestCell, in.pos, junkSlots, drops);
    }
}
