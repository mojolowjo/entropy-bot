# Architecture: the tick, the facts, the files

The detail behind `CLAUDE.md`. Written 2026-10-24 for mod 0.39.z53; check the class javadocs when they disagree.

## The client tick (`Core.onClientTick`, NeoForge `ClientTickEvent.Post`)
In order, every tick unless noted:
1. `events.setTick`, `guard.core.tick` (leases expire), `reconnect.tick` (also outside a world).
2. `commands.tick`: PMs and `cmd.json`/fast-channel commands, the job slot (`Jobs`), `Seq` jobs (every 2 ticks),
   chains and rules, `state.json`; **`AaRuntime.tick` every 5 ticks** (the brain; senses every 10 ticks).
3. `reflexes.tick` unless the owner drives (`Door` scope REFLEX): air, lava, fire, creeper, hit back, eat.
4. `ChunkPass.INSTANCE.tick`: due chunk reads, patches, the once-a-minute self-audit, file writes (background thread).
5. `recorder.tick`, `terrain.tick`, `SafetyNet.tick`, `PolicyRuntime.tick` (the rule snapshot the pathfinder reads).
6. Owner tools: `WindowCare`, `WatchCamera`, `TunnelView`, `SeenSampler`, `SkyScanner`, `SurfaceExport`, `PaperPlan`.
Everything that can throw is wrapped: `Quiet.warn(site, t)` logs the first hit and every 100th.

## Who may start a job (rule 6, the door)
Only AA, the owner's own command or a true reflex. `aa/door/Door` scopes the driver; rules and routines are refused
while AA is on. AA's own chain is named in `AaRuntime.CHAIN`; only AA starts and cancels it.

## The AA tick (`AaRuntime.tick`)
1. `sensed = SenseRuntime.sense()` every 10 ticks -> `Blackboard.in` (`Inputs`): health, food, saturation, bag, light,
   time of day, weather, under a roof, gear tiers.
2. Lazy facts on the blackboard (read once per pick, cached briefly): `NeedChain.WORLD_FACT` (`needWorld()`),
   `FoodOptions.FACTS` (`foodFacts()`), `Goals.NOT_READY_FACT` (why the senses are not ready yet).
3. `Brain.tick`: score desires, run or continue the best, the stuck watchdog, `Failures`.
4. A running task is its typed `Steps` run (`stepRun`), followed every tick; its end (DONE / FAILED with stage and
   reason) is a `FactChange` the brain re-plans on.
5. Files under `<game dir>/entropybot/aa/`: the snapshot and decision logs; `aa-debug.log` for `aa debug want`.

### Path memory (D11)
The fork keeps priced paths (`PathMemory`) and re-joins a kept path the feet left by one short search (build 1); the mod
keeps no copy (rule 23). Waiting trips are priced at the running step's END cell (the anchor rule, `BatchWay`). The load
line (`LoadProbe` -> `ProbeLines.LoadFacts`) says re-joins (`IPricedPath.joinCounts`) and route memory
(`routeCounts`, read by name until the fork has build 2: "routes not readable in this fork jar" when absent).

### Caves (J6 / J6b, 0.39.z78)
Caves are known by rays like ore: `sense/CaveIndex` (the air volumes per world, in the one store), credits from the sight
rays, `aa cave list|yes|no|check`. A known cave is one way to an ore for the planner (`NeedChain.caveAlts`, the yield from
world-gen data). Its step is `Step.Cave`, run by `StepHands.caveStatus` / `caveMove`: `sense/CaveRecord` holds the cave's
cells, what is seen, its passages (D15 pass two: THE cave model; the frontier groups and the first unseen cell are deleted), the ore seen and mined;
`cave/CaveWalk.next` picks the move (mine a seen ore in reach, walk to a seen ore, walk the cheapest open passage, else the
record's end). D15 (2026-11-08): `cave/Passages.build` reads the record's cells as passages from the feet - a flood of
walkable cells (feet and head air over a floor; joins down / up `cave.passageDrop`) in breadth-first layers, a layer that
splits into arms of `cave.passageForkLayers` layers or more is a fork, shorter arms are folded bumps; each passage has its
far end, length, width, unseen cells and ore faces (`Sightings.cavePassages`). Its price is seconds to its far end per ore
expected (`CaveWalk.passagePrice`: `cave.passageSecPerCell`, `cave.orePerUnseenCell`); the walk is a `GoalNear`
(`cave.passageNear`) at the far end, arrival sees the passage's cells; a seen ore in reach on the walk pauses it and is mined.
Seen-ore gotos keep the reach goals below. Pass two: a layer piece wider than `cave.passageRoomWidth` is a ROOM node whose
exits are its arms; at the lip (no walkable cell under the feet) the flood starts at the nearest walkable cave cell within
`cave.passageLipNear` (run JAJ: a lip 4 blocks off), and with none the step walks to the cave's mouth (said). The record keeps the step's last passage
list (saved as the list; an old save's frontier groups are dropped, logged); its done = measured and none open; never
measured, it measures from the entry. The planner's walk inside is `CaveWalk.findSeconds` over that list (nearest open
passages first, seconds per expected ore). While a caving step runs the list is HELD (`Sightings.caveHold`): the planner
reads the step's first measure until the step ends, so the price does not move with each metre. A budget end (MINUTES) is the cave's source fact
(`FactChange.caveMinutesFact`: "cave at x y z: budget spent, N ore mined, M passages left"), so the order re-plans on the
sources left; while a caving step runs, a sighting re-prices the order only when closer than the step's target by
`cave.seenMarginM` (`SeenAsk.caveHold`, `StepHands.caveTargetDist`). Arrival = Baritone's `isInGoal(feet)`; an arrival that
saw nothing new drops the goto. `probe cave:` lines behind `debug.stepProbe` say each goal's parts and Baritone's state;
`probe cave site:` lines (`StepHands.caveSiteLines`, 5 s into a held goal, then every 10 s) say the blocks around the feet,
the drop at each side, Baritone's own break setting and tool strengths, and per goal part its stands and the blocks on the
eye's line to the cell. A walk's goal is built over stands the mod judges (`LipGoals.standable`: feet and head open, solid
under, no fluid) AND Baritone's own `MovementHelper.canWalkOn` / `canWalkThrough` (pointed dripstone is no floor to it). A
held walk (`ExploreRules.TargetHold`, the explore target, the lip and the caving walk alike) fails after `monitor.noPathFails`
failed searches that found no way (`StepSignalsLive.noWay`: the graph exhausted, a budget stop whose best node stayed at
the feet, or a budget stop that repeats the failed calc before it - the same start feet, goal and best node; the reader keeps
the last failed calc it read), else after `monitor.stallS` with no new best. The step monitor's progress signal for the
caving step is its record's cells seen (`StepHands.caveSeen` into `StepSignalsLive.read`, the explore's chunk slot): the owner's
rule (2026-11-02) is that the step clears the cave and notes every ore it sees, never leaving early on a poor count; the
10-minute cap (`Step.Cave.minutes`) is the safety net. A seen ore's vein mine (`VeinMine`) has its own no-progress clock
(`MineRules.VeinWatch`: the feet, the vein's gone count, a break stage; `monitor.stallS`), so an ore Baritone cannot reach is
left out of the step after 30 s. A place-near failure says its site (`StepHands.placeSiteProbe`, behind `debug.stepProbe`).
The step monitor's silent rule (`StepMonitor.silent`, job-mon, run JAI): a judged step with no move of the feet, no break
stage change, no bag gain, no placed block and no new Baritone chat line for `monitor.silentS` ends "no progress N s at
<step>: Baritone's <process> last said '<line>' M s ago", even while a search holds the stall clock. Baritone's lines come
from `Steps.Hands.said` (`StepHands.said`: the process in control, `BaritoneHook.lastLine` / `lines`); not known, the rule is
off and the outer watch stays the backstop. The honest park (`Steps.Attempt.sameAs`) also compares the cell the step ENDED at
and a silent end's last line (also in `Attempt.tryKey`); the fact key, the cave's hold key, is unchanged.

## Where the facts come from
| Fact | Class | Game API or file |
|---|---|---|
| health, food, saturation, bag, light, day time | `SenseRuntime` | `LocalPlayer` getters, `Level.getBrightness`, `Level.getDayTime` |
| recipes (vanilla, modded, KubeJS edits) | `craft/McRecipes` | the client's `RecipeManager` (synced by the server) |
| tags (`#planks`, `#logs`, `#coals`, `c:ores`) | `ItemWorth.isIn`, `ItemWorth.itemsIn` | the synced tag index |
| item worth, tool tiers, food values | `ItemWorth`, `MiningTiers` | 1.21.1 data components (TOOL, FOOD, MAX_DAMAGE...) |
| fuel burn ticks | `ItemWorth.fuels` | NeoForge `getBurnTime` table |
| loot: mob drops, block drops, chests | `LootBook`, `LootFiles`, `JarPacks` | loot-table JSON in the client's game and mod jars |
| ore bands, world-gen block ids | `OreBand`, `WorldGenFiles` | `placed_feature` / `configured_feature` JSON in the jars |
| structure blocks | `guard/StructureTable` | tags, `EntityBlock`, recipe stations, world-gen JSON |
| chunk contents, ore index, column tops | `sense/ChunkPass`, `McChunkReader`, `OreIndex` | `ChunkEvent.Load/Unload`, `RecorderMixinClientLevel` for block changes |
| what the bot has SEEN (ore, trees, herds, cave mouths) | `Seen`, `Sightings`, `SeenRuntime`, `SightingsRuntime` | `Level.clip` rays from the eyes, `getBrightness(SKY)`, heightmaps |
| nearest tree / block set | `aa/Nearby`, `commands/NearbyLive` | Baritone `IWorldScanner.scanChunkRadius`, then the sightings |
| a source's cover (job-sight, run JAH) | `aa/Nearby.withCover`, `Scan.seeThrough` / `Scan.coveredMark`, `Sightings.markCovered` (`covered` in `sightings.json`), `NeedChain.coverText` / `coverS` / `digTo`, `CollectHand.mineNoneKnown(.., Cover)`, `StepHands.digRefusedMark` | every non-log source spot carries `cover`: 0 exposed (a face of air, no fluid), N covered (N closed cells straight over it, `coverBlock` the first), -1 not read (priced as before, rule 15). Deeper than `sight.coverMax`: not counted, said. A covered spot is priced walk + N x the cover block's break and stepped `Step.Dig` (the cover column, Baritone's builder under its lease) before the `Collect`. Baritone's mine ending "none in sight" at a spot marks it covered once (a price fact `price covered x y z`); already marked: the near-collect fact. A refused cover dig marks "dig refused: ..." and the spot is no source |
| known stations and chests (the deposit, the chain) | `aa/Nearby.lookup`, `aa/MainStations`, `commands/MainStationsLive` | my own placements (the ledger, any distance), the owner's mains (`aa deposit x y z`, `aa station main`: his say, usable outside his areas, run HQ), Baritone's scan; `guard/BreakPolicy.stationUse` refuses keep-out and wild stations. The mains are the world's (`stations-main.json` in `worlds/<key>/`, `io/WorldState.FILES`, owner 2026-11-03); `aa deposit x y z` takes a container block entity before the item data is in (runs JS-JX) |
| station memory, walk-back vs make-new, where a new station goes (D14) | `aa/StationBook` (`stations-book.json`, loaded in `commands/MainStationsLive`), `aa/NeedChain.farStation`, `aa/moves/StationPlace`, `aa/moves/BaritoneHand.startPlaceNear`, `restore/RestorePlan.keptOpen` | D14 (2026-11-08): every station placed (`OwnStations.afterPlaceNear`) or seen (`Nearby.lookup`) is a record with its block, cell, mine, last seen and present; one found gone (`OwnStations.forget`) is kept GONE with its reason. Every known station's priced walk is weighed against making a new one (obtain + place + `station.materialS` unless the item is in the bag); the old `station.nearM` gate is gone. A station is placed by the fork's `IBuilderProcess.placeStation(StationCell)`: a cell with room near the base spot (within `station.baseReachM`) or the feet, on the leaf-free surface when the feet are on it (`StationPlace.onSurface`, MOTION_BLOCKING with leaves skipped). The put-back never fills a station of mine or the cell over it |
| a walk's price | `LivePathPrice`, fork `IPricedPath` | Baritone's A* on its executor, ticks -> seconds in `TickPrice` |
| may this cell be broken / placed, at what cost | `guard/BreakPolicy`, `PolicyRuntime` | `Level` on the client thread, `BlockStateInterface` on the pathfinder thread; surface (2026-11-02): the cell's depth below the cave index's ground (`CaveIndex.groundAt`, a ravine floor's ground is its rim: `CaveIndex.rimmed`) - the top `breakPolicy.surfaceDepth` blocks or a depth not known are the surface: x8 to break or place for a walk, gravel above refused, the per-job cap (`travelCap`, grown with the distance walked) counts surface breaks only; under ground gravel above costs x3, no cap |
| what a walk may place (throwaway) | `engine/RunThrowaway.holdBag` / `bagBlocks`, the fork's `IRunThrowaway` lease | run HV (2026-11-02): the bag's placeable junk blocks (`Junk.placeable`, the item data: cobbled deepslate, granite...) are held as throwaway for every walk through one standing lease, refreshed on a change; Baritone's own list alone (dirt, cobblestone, netherrack, stone) left a bot under ground with 90 cobbled deepslate unable to pillar |
| the deposit's way to a known chest | `commands/DepositLeg.walk` (phase WALK), `commands/LivePathPrice.walk` | run HU (2026-11-02): the price's kept path is walked first (the same goal, one search, Baritone walks it), the use's own short walk to a stand follows from beside the chest; a walk that ends short hands over to the use where it stopped. Run JAC (2026-11-03, rule 15): a known chest whose path is not known because its chunks are not loaded (ESTIMATED / CHUNKS) is walked toward on Baritone's estimate (`DepositRule.Choice.estimated`), segment by segment while each ends nearer; a new chest is made only when no known chest has an estimate either |
| the trail home (D4) | `aa/trail/Trail` (segments, the return price, `pick`, the walk-back goals), `aa/trail/Trails` (one per dimension on the board, `trail.json` per world), `commands/TrailRuntime` (recorder from the feet in `Core`, the climb level price, the file), `commands/DepositLeg.byTrail` / `trailWalk` (phase TRAIL) | the way down kept as segments between the surface cell, each one-way top and foot (a drop of 2+, a dug-down step; their cells kept, no fork route exists for them); return price = walked seconds + one-way levels x the cheaper of a pillar level (JUMP_ONE_BLOCK_COST + walk.placePenalty) and a dug stair level (`ReflexGoals.digLevelTicks`); beats an ESTIMATE or no path, loses to a cheaper PRICED path, waits for a computing one; a walk back that ends short breaks the trail with its reason; cleared once out; the fork's saved routes (IPricedPath.routeCounts, read by name) mark a walk segment's fork route; tests D4TrailTest |
| the walk up's end words, the surface probe | `aa/moves/WalkEndText` (pure), `StepHands.toSurfaceStatus`; `commands/Jobs.rockOverHead` (debug.surfaceProbe) | run JAL (2026-11-08): a walk up that ends off its cell says "ended short at x y z: " the fork's path end (exhausted, budget, a named stop) and/or Baritone's last line since the start; never "stop unknown". The under-ground reading stays `world/SurfaceHeight.noLeaves` (MOTION_BLOCKING walked down past leaves and non-blocking cells, logs solid, unloaded = NOT_KNOWN); debug.surfaceProbe 1 logs the block the top stopped on; tests SurfaceJobTest |
| the standing step's end | `aa/moves/Steps.reprice` (the stand's landed answer), `aa/moves/StandWait.stillComputing` | runs JU, JV, JW, JAB (2026-11-03): a re-plan answer with no steps whose reason is a walk "not known yet (computing)" keeps the stand standing; only a final answer (a price, no way, met) or the stand's own wait ends it (rule 15) |
| the walk before a collect spot, and its failure | `aa/moves/Steps.walkLeads`, `NeedChain.spotKey(Step.Collect)`, `commands/Commands.walkNearTo`, `StepHands.walk` | run JAF (2026-11-04): a collect spot beyond the mine's sight gets a "near" walk first (`Step.Walk.near` = `Step.Collect.MINE_SIGHT`); it travels to the cell itself on Baritone's goal (GoalNear far, the priced GoalGetToBlock otherwise) - never `walkTo`'s free-spot test for a cell inside a block ("5 64 -5 is inside dirt, no free spot within 3" ended it at its start). Its failure is a fact of that spot only ("collect dirt at 5 64 -5", the form of `spotKey`); the item key ("collect dirt") held every dirt spot for the order and three explores for dirt ran 15 min over dirt |
| the bag goes to the main chest when an order ends | `commands/AaRuntime.goalsTick` (`depositDue`), `aa/brain/Goals.Goal.depositWeighed` | helper (owner 2026-11-02): an item order done or parked with some of it in the bag and a main chest set (`aa deposit x y z`) starts `aa want deposit` once the job slot is free; no main chest: nothing (said in the log) |
| hostiles and their reach | `threat/ThreatRuntime`, `ReachCache` | entity list, collision shapes, line of sight |
| the owner's position and actions | `OwnerFix` (via `entropybot/owner.json`) | written by the dashboard from the companion mod |

## Baritone: the fork's surface the mod uses
`IChopProcess` (a whole tree), `IInteractProcess` (use a block: stand, face, click), `IBuilderProcess.placeNear` / `placeStation` (D14) /
`buildLeg` (one-block or few-block schematics), `IBurrowProcess` (dig down, dig in, `startJob` for a job's hands),
`IPricedPath` / `IPathCost` (A* cost without moving), `CostAdvisor` (the policy's prices inside A*), `RunLease` /
`IRunThrowaway` (per-run break/place/throwaway settings; the mod never sets `allowBreak`/`allowPlace` itself),
`DigDownGoal`, `GoalReachBlock`. Full table: `..\minecraft-bot\docs\FORK_API.md`. Stock Baritone used as is:
`MineProcess`, `ExploreProcess`, `CustomGoalProcess`, `LookBehavior`, the cached world.

The mod's one Baritone process is `engine/EngineProcess` (priority -0.5): a reflex goal saves and restores the job's
goal. Three mixins touch Baritone: `GuardMixinCalculationContext` (break/place cost fallback), `FarmlandMixinPrecomputedData`.

## Files under `<game dir>/entropybot/`
`state.json`, `cmd.json` (the bridge; the fast channel replaces polling), `fast.json` (path of the key file, never the
key), `aa/` (snapshots, decisions), `aa-debug.log`, `aa-tuning.json` and `aa-stats.json` (per world), `sightings.json`,
`caves.json`, `cave-patterns.json` (world + global, seeded from `src/main/resources/entropybot/cave-patterns-seed.json`),
`sense/<dim>/<cx>.<cz>.json` (chunk summaries), `places.json`, `chests.json`, `pois.json`, `restore.json`, `stock.json`,
`owner.json` (from the dashboard), `screen.json`, the recorder's store and the terrain map PNGs. Writes are atomic
(`BotFiles`); a file that will not parse is set aside, never overwritten.

## Reading a run
`latest.log` lines with `[entropybot]`: `aa:` (the brain), `explore:`, `cave:`, `sense: cave candidate ...`,
`chain` lines with prices and the dropped list. In game: `aa status`, `aa why`, `aa debug want <item>`, `aa cave list`,
`debug ores known`, `fence why x y z`. Only what the game logged counts as a fact about a run.
