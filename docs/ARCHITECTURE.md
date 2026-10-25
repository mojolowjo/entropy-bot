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
| a walk's price | `LivePathPrice`, fork `IPricedPath` | Baritone's A* on its executor, ticks -> seconds in `TickPrice` |
| may this cell be broken / placed, at what cost | `guard/BreakPolicy`, `PolicyRuntime` | `Level` on the client thread, `BlockStateInterface` on the pathfinder thread |
| hostiles and their reach | `threat/ThreatRuntime`, `ReachCache` | entity list, collision shapes, line of sight |
| the owner's position and actions | `OwnerFix` (via `entropybot/owner.json`) | written by the dashboard from the companion mod |

## Baritone: the fork's surface the mod uses
`IChopProcess` (a whole tree), `IInteractProcess` (use a block: stand, face, click), `IBuilderProcess.placeNear` /
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
