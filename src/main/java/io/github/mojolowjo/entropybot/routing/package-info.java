/**
 * Routing stage 1, package R2: the Minecraft and Baritone adapter under the route core ({@code route}).
 *
 * <h2>What is here</h2>
 * <ul>
 *   <li>Plain Java (JUnit-tested, no game types): {@link io.github.mojolowjo.entropybot.routing.RouteRules} (walkability
 *       filter, standable rule, quality), {@link io.github.mojolowjo.entropybot.routing.AreaBoxes} (which boxes exist),
 *       {@link io.github.mojolowjo.entropybot.routing.RoutePool} (3 MIN_PRIORITY daemon threads),
 *       {@link io.github.mojolowjo.entropybot.routing.RouteScheduler} (per-tick budget, now before idle, pauses),
 *       {@link io.github.mojolowjo.entropybot.routing.RouteEngine} (wiring, planning thread, staleness),
 *       {@link io.github.mojolowjo.entropybot.routing.RouteTiles} (tile files), {@link io.github.mojolowjo.entropybot.routing.DumpFixture}
 *       ({@code route dump} files and their read-back as a {@code CellMoves}).</li>
 *   <li>Game side: {@link io.github.mojolowjo.entropybot.routing.BaritoneCellMoves} (Baritone's moves) and
 *       {@link io.github.mojolowjo.entropybot.routing.RouteRuntime} (the {@code RoutePlanner}, lifecycle, hooks).</li>
 * </ul>
 *
 * <h2>Loader API used (docs/PLANNING.md section 2; review R8)</h2>
 * <table>
 *   <tr><th>Part</th><th>NeoForge 21.1 (now)</th><th>Fabric / Forge</th></tr>
 *   <tr><td>Block changes</td><td>No mixin of its own: a listener on the existing {@code RecorderMixinClientLevel}
 *       ({@code ClientLevel.setBlock(BlockPos, BlockState, int, int)} and {@code setServerVerifiedBlockState}, HEAD/RETURN,
 *       {@code require = 0}; applied flag {@code MixinFlags.levelHookApplied}) through
 *       {@code FlightRecorder.routeListener}</td><td>The same mixin works unchanged on both (vanilla class, mojmap names;
 *       Fabric needs intermediary remapping as usual)</td></tr>
 *   <tr><td>Chunk loads</td><td>Event {@code net.neoforged.neoforge.event.level.ChunkEvent.Load} on
 *       {@code NeoForge.EVENT_BUS}, client side only ({@code level.isClientSide()}; posted at the end of
 *       {@code ClientChunkCache.replaceWithPacketData}, verified in the 21.1.249 sources)</td><td>Fabric:
 *       {@code ClientChunkEvents.CHUNK_LOAD}; Forge: {@code ChunkEvent.Load} (same shape)</td></tr>
 *   <tr><td>Second change source</td><td>Baritone's {@code IGameEventListener.onBlockChange} (Baritone API, loader-neutral)</td>
 *       <td>Same (Baritone ships Fabric and Forge jars of 1.11.3)</td></tr>
 *   <tr><td>Move costs</td><td>Baritone internals of the unoptimized jar: {@code CalculationContext(IBaritone, true)},
 *       {@code Moves.apply}, {@code MovementHelper}, {@code MutableMoveResult}; thread-safe chunk copy inside
 *       Baritone (review R4: no {@code get0} mixin in stage 1)</td><td>Same classes in Baritone's Fabric/Forge unoptimized
 *       jars</td></tr>
 *   <tr><td>Versions</td><td>{@code ModList.get().getModContainerById("baritone")}</td><td>Fabric:
 *       {@code FabricLoader.getModContainer}; Forge: {@code ModList} (same)</td></tr>
 *   <tr><td>Threads, files, scheduling</td><td>Plain Java</td><td>Unchanged</td></tr>
 * </table>
 *
 * <h2>Error catching</h2>
 * {@code RouteRuntime.statusLine()} (for {@code route status} and {@code check}): availability with the reason, the
 * core counters ({@code RouteStats}), the builder's counters (paused and why, contexts, skips, idle breaks, requeues,
 * rehashes), tile saves and failures, and the hooks (block hook in or not, calls seen, chunk loads, Baritone events).
 * Worker, planner, save and load exceptions are counted and logged through {@code RouteLog}: the first 5 in full, then
 * one line a minute. Nothing here adds a mixin.
 */
package io.github.mojolowjo.entropybot.routing;
