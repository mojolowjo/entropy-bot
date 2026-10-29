package io.github.mojolowjo.entropybot.aa.core;

/** AA v0 (AA_PLAN 7): the fixed outputs of the decision step. A trained scorer later answers one score per kind. */
public enum TaskKind {
    NONE, OWNER, /** No need uses it since 0.39.y1 (no tools need); kept so the older ordinals stay. */ TOOLS, TABLE_FURNACE, TORCHES, BASE_BED, BAG, IRON, FOOD, STOCK_WOOD, STOCK_STONE, EXPLORE, WAIT,
    /** S1 (MINING_PLAN 12): the idle stock-ups and chores; appended so the older ordinals stay. */
    STOCK_COAL, STOCK_IRON, STOCK_TORCHES, DEPOSIT, LIGHT,
    /** Owner 2026-10-29: the idle report-back option - the walk back to the base place when nothing else runs; appended so the older ordinals stay. */
    REPORT_BACK
}
