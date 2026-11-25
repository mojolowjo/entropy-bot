package io.github.mojolowjo.entropybot.baritone;

import io.github.mojolowjo.entropybot.guard.PolicyStats;

/**
 * Review sent back (5): where {@link CostAdvisorHook#breakMultiplier} was asked from, as data, so the counts and the probe
 * verb can be tested without the game. The break site (Baritone's break cost) asks and counts its own denies through the
 * judge; the place site ({@link CostAdvisorHook#placeNeedsRefusedBreak}) was asked once by placeCost already, so every deny
 * is counted once here at the place site, the judge is not asked again, and the probe line says "place". Pure.
 */
record BreakSite(String site) {
    /** The strip-probe action of a place refused because the block in its cell may not be broken. */
    static final String PLACE_NEEDS_BREAK = "place (the cell's block may not be broken)";

    static final BreakSite COST = new BreakSite(PolicyStats.SITE_ASTAR_COST), PLACE = new BreakSite(PolicyStats.SITE_ASTAR_PLACE);

    boolean forPlace() { return PolicyStats.SITE_ASTAR_PLACE.equals(site); }

    /** The probe line's verb. */
    String verb() { return forPlace() ? PLACE_NEEDS_BREAK : "break"; }

    /** A refusal before the judge (walking only, a no-break run, no dimension) is counted here only for a place. */
    void earlyDeny(PolicyStats stats, String reason) { if (forPlace()) stats.deny(site, reason); }

    /** The judge counts an ask of its own only for the break site (placeCost counted the place's). */
    boolean judgeAsks() { return !forPlace(); }

    /** MINE's own ask is counted only at the break site. */
    void mineAsk(PolicyStats stats) { if (!forPlace()) stats.ask(site); }
}
