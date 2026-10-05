/**
 * Routing stage 1: the route core (package R1 of {@code docs/ROUTING_PLAN.md}).
 *
 * <p>The travel map of the owner's areas, cut into chunk sections (16x16x16 boxes). Each box stores its
 * {@link io.github.mojolowjo.entropybot.route.Door doors} and the ticks to cross from door to door
 * ({@link io.github.mojolowjo.entropybot.route.SectionRecord}). The {@link io.github.mojolowjo.entropybot.route.Router}
 * runs one backward Dijkstra over the door graph and hands back a {@link io.github.mojolowjo.entropybot.route.CostToGo}
 * table that {@link io.github.mojolowjo.entropybot.route.RouteGoalMath#heuristic} reads on Baritone's path thread.
 *
 * <h2>Loader API used</h2>
 * None. This package is plain Java 21: no {@code net.minecraft}, {@code net.neoforged}, {@code baritone} or Fabric
 * imports (a JUnit source scan, {@code RouteLoaderNeutralTest}, fails the build if one appears). Everything that touches
 * the game sits behind {@link io.github.mojolowjo.entropybot.route.CellMoves} (package R2: Baritone's
 * {@code Moves.apply} with a per-worker {@code CalculationContext}) and the callers in R3 ({@code RouteGoal implements
 * baritone.api.pathing.goals.Goal}). The core moves to Fabric or Forge unchanged. No mixins.
 *
 * <h2>Error catching</h2>
 * Nothing here fails silently: a bad file is ignored and counted ({@link io.github.mojolowjo.entropybot.route.RouteStats}),
 * worker exceptions are counted and logged through {@link io.github.mojolowjo.entropybot.route.RouteLog} (first few in
 * full, then rate-limited, {@code [entropybot]} prefix added by the sink the adapter installs).
 */
package io.github.mojolowjo.entropybot.route;
