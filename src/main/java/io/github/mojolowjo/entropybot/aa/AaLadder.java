package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.core.Band;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.MemoryFallback;
import io.github.mojolowjo.entropybot.aa.core.Node;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;

import java.util.List;

/**
 * AA v0 (AA_PLAN 9, checked against the 0.35.4 source, build notes): the basic-needs ladder for Peaceful. Rungs top to
 * bottom, the first unmet one that can start runs. Verbs are the existing ones; a "goal ..." text goes to the planner
 * through {@code JobPort.resolve}.
 *
 * <ol>
 * <li value="0">AREA (live check 2026-10-18: a fresh world has no area and every gathering verb and the planner work
 *     inside areas only): no area -> {@value #AREA_COMMAND} (instant). A radius-48 area is too large for {@code house}
 *     (4096 blocks at most), so the base rung builds a shelter unless the owner made a smaller area.</li>
 * <li>TOOLS: no pickaxe at all -> {@code bootstrap} (the from-nothing job); a wooden one, or the best under 15 % with
 *     no spare -> {@code goal stone tools}.</li>
 * <li>FOOD: on a hungry difficulty only (Peaceful: the bar never drains): food under 10 until 18, or the bag under its stock target (aa-food,
 *     FoodModel) -> the best of FoodOptions' ways (eat, cook, hunt, forage, apples, a wheat field, {@code goal food 8}).</li>
 * <li>TABLE_FURNACE: no table known -> {@code goal table}; no furnace -> {@code goal furnace}.</li>
 * <li>TORCHES: under 8 until 16 -> {@code goal torch 16} (planner: coal or charcoal).</li>
 * <li>BASE_BED: no base/shelter -> {@code house <area>} when a buildable area exists, else {@code goal shelter} (once:
 *     the shelter fact is AA's own, the world has none); then the bed only with 3 wool or a bed in the bag:
 *     {@code goal bed}. Without wool the bed is skipped with the reason (no sheep on the template, {@code hunt} needs
 *     areas; assumption 9.4 was false).</li>
 * <li>BAG: free slots under 3 until 10 -> {@code deposit}, only with base chests scanned (assumption 9.5: no chest on a
 *     fresh world, so the rung says why it is skipped; the idle junk tick keeps dropping junk).</li>
 * <li>IRON: no iron pickaxe -> {@code goal iron tools}.</li>
 * </ol>
 */
public final class AaLadder {
    private AaLadder() {}

    public static final String SHELTER_FACT = "shelterBuilt";

    /** The work area AA makes itself on a fresh world (the mod's own check hint; every gathering verb works inside areas). */
    public static final String AREA_COMMAND = "area here 48 work neutral";
    /**
     * The from-nothing job (live check 2026-10-18: the planner's "trees" fact scans 16 blocks around the bot, so from a
     * spawn with the nearest oak 52 blocks away {@code goal stone tools} has no plan; {@code bootstrap} fells trees
     * anywhere in the areas and makes the table, wooden then stone tools, furnace, chest, charcoal, torches, base, camp).
     */
    public static final String BOOTSTRAP = "bootstrap";

    public static final String CLIMB = "climb", TREE_AREA_FACT = "treeArea";
    /** Under this much ground the table/furnace/house/iron rungs climb out first (a tree's leaves never count). */
    public static final int SURFACE_DEPTH = 3;

    /** A later rung that places a table or furnace, builds the base or plans iron (it places a table and furnace). */
    /**
     * aa-costs: the gate rung still open (needs.area, needs.woodArea, needs.surface), else null. An upkeep that may now
     * score urgent (tools) waits for it: nothing it runs can work before the gate is met.
     */
    public static String openGate(Blackboard bb) { return openGate(bb, io.github.mojolowjo.entropybot.aa.brain.Desire.Exposure.SURFACE); }

    /**
     * small-1: the surface gate (climb out first) binds only a desire whose exposure is SURFACE; UNDER and ANY desires
     * never wait for it (the dawn climb is the surface rung's own score). The area gates bind everything.
     */
    public static String openGate(Blackboard bb, io.github.mojolowjo.entropybot.aa.brain.Desire.Exposure e) {
        if (!bb.in.areaKnown) return "needs.area";
        if (bb.fact(TREE_AREA_FACT) instanceof String) return "needs.woodArea";
        if (e != io.github.mojolowjo.entropybot.aa.brain.Desire.Exposure.SURFACE) return null;
        if (io.github.mojolowjo.entropybot.aa.brain.NightWork.climbAtDawn(bb, SURFACE_DEPTH) | (bb.in.underDepth >= SURFACE_DEPTH && needsOpenGround(bb))) return "needs.surface";
        return null;
    }

    static boolean needsOpenGround(Blackboard bb) {
        boolean table = bb.in.tableKnown || bb.in.tableInBag, furnace = bb.in.furnaceKnown || bb.in.furnaceInBag;
        return !table || !furnace || (!bb.in.baseKnown && !bb.flag(SHELTER_FACT)) || (bb.in.pickTier >= 2 && bb.in.pickTier < 3);
    }

    /** No table in the bag and planks for one (4): make the spare. */
    public static boolean spareTableWanted(boolean tableInBag, int planks) {
        return !tableInBag && planks >= 4;
    }

    /** Seen within this many blocks of the camp or base mark, a table/furnace counts as the camp's (latched). */
    public static final int CAMP_R = 24;

    /**
     * 0.36.a6 (aa371: under ground, away from the camp, the table/furnace rung planned a new furnace: "no a free spot
     * next to me for the furnace"): the near-block sense is true only near the block, so a table/furnace seen within
     * {@link #CAMP_R} of the camp or base mark is latched and counts wherever the bot is. Cleared only when neither a
     * camp nor a base is known any more.
     *
     * @param distToCamp blocks to the nearer of the camp and base marks, -1 when neither is known
     */
    public static boolean campLatch(boolean latched, boolean seenNear, double distToCamp) {
        if (distToCamp < 0) return false;
        return latched || (seenNear && distToCamp <= CAMP_R);
    }

    /** BRAIN_PLAN B1: the rungs in ladder order; the brain (AaDesires) scores them instead of trying them in order. */
    public static List<Rung> build() {
        Band torches = new Band(8, 16), bag = new Band(3, 10), food = new Band(10, 18);
        Rung area = new Rung("needs.area", TaskKind.TOOLS,
                bb -> !bb.in.areaKnown,
                bb -> null, bb -> AREA_COMMAND);
        Rung tools = new Rung("needs.tools", TaskKind.TOOLS,
                bb -> bb.in.pickTier < 2 || (bb.in.pickPct < 15 && bb.in.pickaxes < 2),
                // S2 (MINING_PLAN 11): bootstrap tools / bootstrap / the planner, scored (RungOptions.Tools)
                bb -> null, bb -> "goal stone tools", new Options.Picker<>("TOOLS", RungOptions.TOOLS_FACTS, RungOptions.ToolsFacts.class, new RungOptions.Tools())
                        .withoutFacts((b, planned) -> b.in.pickTier == 0 ? BOOTSTRAP : planned));
        // 0.36.3 (aa40b: bootstrap left the bot in its quarry hole, 9 under the surface, and "goal table" / "goal iron
        // tools" had no free spot for the table for 30 minutes): a rung that needs open ground waits for a climb first
        Rung surface = new Rung("needs.surface", TaskKind.TABLE_FURNACE,
                // overnight 2026-10-19: also at dawn after the night work dug down (it clears its note near the surface)
                bb -> io.github.mojolowjo.entropybot.aa.brain.NightWork.climbAtDawn(bb, SURFACE_DEPTH) | (bb.in.underDepth >= SURFACE_DEPTH && needsOpenGround(bb)),
                // S2: climb / pillar / trail / goto open sky / nothing, scored (RungOptions.Surface)
                bb -> null, bb -> CLIMB, new Options.Picker<>("SURFACE", RungOptions.SURFACE_FACTS, RungOptions.SurfaceFacts.class, new RungOptions.Surface()));
        // 0.36.3: no tree the chopper can get at in my areas: a second work area around the nearest reachable tree
        // (AaRuntime puts the area command in the fact; it is cleared after the area is made)
        Rung woodArea = new Rung("needs.woodArea", TaskKind.STOCK_WOOD,
                bb -> bb.fact(TREE_AREA_FACT) instanceof String,
                bb -> null, bb -> (String) bb.fact(TREE_AREA_FACT));
        // aa-food: the bar's band, or the bag under the stock target (FoodModel); the ways are scored (FoodOptions)
        Rung hunger = new Rung("needs.food", TaskKind.FOOD,
                bb -> bb.in.hungerDrains && (food.update(bb.in.food) | AaDesires.foodStockLow(bb)),
                bb -> null, bb -> "goal food 8",
                new Options.Picker<>("FOOD", FoodOptions.FACTS, FoodOptions.FoodFacts.class, new FoodOptions.Food()));
        // a table or furnace in the bag counts (live 2026-10-18: "goal table" with one in the bag is "nothing to do",
        // which the planner hook reports as no plan; the near-block fact is false as soon as the bot walks away)
        Rung tableFurnace = new Rung("needs.tableFurnace", TaskKind.TABLE_FURNACE,
                bb -> !(bb.in.tableKnown || bb.in.tableInBag) || !(bb.in.furnaceKnown || bb.in.furnaceInBag),
                bb -> null, bb -> !(bb.in.tableKnown || bb.in.tableInBag) ? "goal table" : "goal furnace");
        // a spare table in the bag at all times (owner 2026-10-18): crafts away from the camp never walk back to it
        Rung spare = new Rung("needs.spareTable", TaskKind.TABLE_FURNACE,
                bb -> spareTableWanted(bb.in.tableInBag, bb.in.planks),
                bb -> null, bb -> "craft crafting_table 1");
        Rung torch = new Rung("needs.torches", TaskKind.TORCHES,
                bb -> torches.update(bb.in.torches),
                bb -> bb.in.torchCoal() ? null : "coal kept for the coal goal (" + bb.in.torchCoalTerms() + ")", bb -> "goal torch 16");
        Rung base = new Rung("needs.base", TaskKind.BASE_BED,
                bb -> !bb.in.baseKnown && !bb.flag(SHELTER_FACT),
                bb -> null,
                bb -> bb.in.areaName != null ? "house " + bb.in.areaName : "goal shelter");
        Rung bed = new Rung("needs.bed", TaskKind.BASE_BED,
                bb -> !bb.in.bedKnown && !bb.in.bedInBag,
                bb -> bb.in.wool >= 3 ? null : "no wool for a bed (" + bb.in.wool + "/3; no sheep on this template)",
                bb -> "goal bed");
        Rung deposit = new Rung("needs.bag", TaskKind.BAG,
                bb -> bag.update(bb.in.freeSlots),
                bb -> bb.in.baseChests ? null : "no base chests scanned (scan base)",
                bb -> "deposit");
        // 0.36.a8 (MINING_PLAN 3.6): the option scorer chooses how the planner's ore and smelt steps run (IronOptions)
        Rung iron = new Rung("needs.iron", TaskKind.IRON,
                bb -> bb.in.pickTier < 3,
                bb -> bb.in.pickTier >= 2 ? null : "no stone pickaxe yet",
                bb -> "goal iron tools", new IronOptions.Picker());
        // the brain runs a started desire to its end (live check 2026-10-18: a reactive Fallback let an earlier rung that
        // started *planning* cancel a lower rung's job: a pointless switch)
        return List.of(area, woodArea, tools, surface, hunger, tableFurnace, spare, torch, base, bed, deposit, iron);
    }

    /** The rungs under the brain (or any node), in order (for tests and the status line). */
    public static List<Rung> rungs(Node ladder) {
        List<Rung> out = new java.util.ArrayList<>();
        for (Node n : ladder.children()) if (n instanceof Rung r) out.add(r);
        return out;
    }

    /** The shelter rung's SUCCESS is remembered on the blackboard (the adapter persists it). */
    public static void noteShelter(Blackboard bb) { bb.fact(SHELTER_FACT, Boolean.TRUE); }
}
