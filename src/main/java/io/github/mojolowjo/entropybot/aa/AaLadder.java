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
 * <li>FOOD: on a hungry difficulty only (Peaceful: the bar never drains): food under 10 until 18 -> {@code goal food 8}.</li>
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

    public static Node build() {
        Band torches = new Band(8, 16), bag = new Band(3, 10), food = new Band(10, 18);
        Rung area = new Rung("needs.area", TaskKind.TOOLS,
                bb -> !bb.in.areaKnown,
                bb -> null, bb -> AREA_COMMAND);
        Rung tools = new Rung("needs.tools", TaskKind.TOOLS,
                bb -> bb.in.pickTier < 2 || (bb.in.pickPct < 15 && bb.in.pickaxes < 2),
                bb -> null, bb -> bb.in.pickTier == 0 ? BOOTSTRAP : "goal stone tools");
        Rung hunger = new Rung("needs.food", TaskKind.FOOD,
                bb -> bb.in.hungerDrains && food.update(bb.in.food),
                bb -> null, bb -> "goal food 8");
        // a table or furnace in the bag counts (live 2026-10-18: "goal table" with one in the bag is "nothing to do",
        // which the planner hook reports as no plan; the near-block fact is false as soon as the bot walks away)
        Rung tableFurnace = new Rung("needs.tableFurnace", TaskKind.TABLE_FURNACE,
                bb -> !(bb.in.tableKnown || bb.in.tableInBag) || !(bb.in.furnaceKnown || bb.in.furnaceInBag),
                bb -> null, bb -> !(bb.in.tableKnown || bb.in.tableInBag) ? "goal table" : "goal furnace");
        Rung torch = new Rung("needs.torches", TaskKind.TORCHES,
                bb -> torches.update(bb.in.torches),
                bb -> null, bb -> "goal torch 16");
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
        Rung iron = new Rung("needs.iron", TaskKind.IRON,
                bb -> bb.in.pickTier < 3,
                bb -> bb.in.pickTier >= 2 ? null : "no stone pickaxe yet",
                bb -> "goal iron tools");
        // a memory node (live check 2026-10-18: as a reactive Fallback, an earlier rung that started *planning* while a
        // lower rung's job ran outranked it and cancelled the job: a pointless switch)
        return new MemoryFallback("needs", "basic needs", area, tools, hunger, tableFurnace, torch, base, bed, deposit, iron);
    }

    /** The rungs under a built ladder, in order (for tests and the status line). */
    public static List<Rung> rungs(Node ladder) {
        List<Rung> out = new java.util.ArrayList<>();
        for (Node n : ladder.children()) if (n instanceof Rung r) out.add(r);
        return out;
    }

    /** The shelter rung's SUCCESS is remembered on the blackboard (the adapter persists it). */
    public static void noteShelter(Blackboard bb) { bb.fact(SHELTER_FACT, Boolean.TRUE); }
}
