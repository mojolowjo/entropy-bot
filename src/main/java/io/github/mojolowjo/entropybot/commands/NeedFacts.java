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

import java.util.Collection;
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

    /** Registers every supplier on the blackboard (station x 3, bed, base, bag). */
    public static void install(Blackboard bb, AaRuntime rt) {
        for (StationOptions.Kind k : StationOptions.Kind.values())
            bb.fact(k.factsKey(), (Supplier<StationOptions.StationFacts>) () -> station(k, bb.in, bb.in.bag, wood(rt, bb.clock.now())));
        bb.fact(BedOptions.FACTS, (Supplier<BedOptions.BedFacts>) () -> bed(rt.bedFacts(), bb.in));
        bb.fact(BaseOptions.FACTS, (Supplier<BaseOptions.BaseFacts>) () -> base(bb.in, bb.in.bag));
        bb.fact(BagOptions.FACTS, (Supplier<BagOptions.BagFacts>) () -> bag(bb.in, bb.in.bag, JunkRules.DEFAULT, 1));
    }

    /** The wood set's best row now (null: none or no facts; the station set drops its cut row with the reason). */
    private static Options.Choice wood(AaRuntime rt, long now) {
        WoodOptions.WoodFacts f = rt.woodFacts();
        return f == null ? null : WoodOptions.choose(f, new Options.OptionMemory(), now);
    }

    /** The bag by item path (Inputs.bag: "chest" -> 1); a namespaced id is looked up by its path. */
    static int count(Map<String, Integer> bag, String id) {
        if (bag == null) return 0;
        int c = id.indexOf(':');
        return bag.getOrDefault(c >= 0 ? id.substring(c + 1) : id, 0);
    }

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
        double dist = k == StationOptions.Kind.CHEST && in.baseChests ? in.baseDist : -1;
        int[] at = k == StationOptions.Kind.CHEST && in.baseChests ? in.basePos : null;
        return new StationOptions.StationFacts(k, inBag, known, at, dist, in.planks, in.wood, in.cobble, in.pickTier, in.chestCell,
                wood == null ? null : wood.best(), wood == null ? null : wood.why(), in.underGround, in.underDepth);
    }

    public static BedOptions.BedFacts bed(BedOptions.BedFacts old, Inputs in) {
        if (old == null) return null;
        return new BedOptions.BedFacts(old.wool(), old.sheepInView(), old.herd(), old.me(), in.bedKnown, in.night, in.underGround, in.underDepth);
    }

    public static BaseOptions.BaseFacts base(Inputs in, Map<String, Integer> bag) {
        int blocks = in.cobble + in.planks + count(bag, "minecraft:dirt") + count(bag, "minecraft:cobbled_deepslate");
        double danger = in.night ? (in.thunder ? 1.5 : 1.0) : 0;
        return new BaseOptions.BaseFacts(in.areaName, in.basePos, blocks, in.night, danger, in.underGround, in.underDepth);
    }

    /** @param want slots the need wants free */
    public static BagOptions.BagFacts bag(Inputs in, Map<String, Integer> bag, Collection<String> junk, int want) {
        int junkSlots = 0;
        for (String id : junk) junkSlots += (count(bag, id) + 63) / 64;
        return new BagOptions.BagFacts(in.freeSlots, want, in.baseChests, in.baseDist, in.basePos, count(bag, "minecraft:chest") > 0,
                in.planks, in.wood, in.chestCell, in.pos, junkSlots);
    }
}
