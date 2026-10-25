# Entropy Bot (the mod)

A client-side NeoForge mod for Minecraft 1.21.1 that drives one Minecraft account as a helper bot. It is an
improved Baritone: our own Baritone fork (`libs/*-entropy.jar`, source in `..\baritone-src`) does every move, dig
and place; this mod decides what to do by pricing options from the game's own data (recipes, tags, loot tables,
world-gen, burn times), never from name lists. The owner drives it with `aa want <item>` orders; a scored brain
(AA, "Advanced Algorithm") plans each order as a chain of jobs, runs them as typed steps, and re-plans on facts.

Sessions are run from the private operator repo `..\minecraft-bot`: read its `CLAUDE.md` (session rules), the
HANDOVER at the top of its `DEVNOTES.md` (current state) and `docs/PLAN.md` (live plan) before touching code here.
Design docs live there under `docs/`; this file links them by relative path. This repo is PUBLIC.

## Build and run
- JDK 21: `$env:JAVA_HOME="C:\Program Files\Java\jdk-21"`, then `.\gradlew.bat test build --offline` (about 2 min,
  2153 JUnit tests, jar in `build/libs/entropybot-<mod_version>.jar`). `mod_version` is in `gradle.properties`.
- Gradle fetches the stock Baritone jar into `libs/` and checks its sha1; when the fork jar
  `baritone-unoptimized-neoforge-1.11.3-entropy.jar` is beside it, the mod compiles against the fork.
- `.\gradlew.bat runClient` starts a dev client with Baritone. Test games are launched from the operator repo:
  `..\minecraft-bot\tools\test_world.ps1 -Fresh -World <w> -Template peaceful -Port 8768 -Jar <jar>`, driven by
  `..\minecraft-bot\bridge.ps1`, log at `<instance>\minecraft\logs\latest.log`.
- Install: the mod jar plus ONE Baritone jar (the fork) in the client's `mods`. Client only; the server never sees it.
- CI (`.github/workflows/build.yml`) runs `gradlew build` on push. Release steps: `..\minecraft-bot\docs\RELEASING.md`.

## Project map (`src/main/java/io/github/mojolowjo/entropybot/`)
| Package | Responsibility | Key classes |
|---|---|---|
| root | mod entry and wiring | `EntropyBot` (NeoForge events), `Core` (everything, ticked once per client tick), `JarPacks` (the client's jars as data packs), `Quiet` (no silent catch) |
| `aa/` | the brain's needs and ways | `NeedChain` (THE planner: "I need N of X"), `Need`, `Ways`, `Options` (ranking), `Nearby` (what is near), `FoodOptions`, `IronOptions`, `DangerOptions`, `Replan`, `AaDesires` |
| `aa/brain/` | scoring, values, game knowledge | `Brain` (scored desires), `Desire`, `Goals` (`aa want`), `Failures`, `ItemWorth`, `TechTree`, `LootBook`, `OreBand`, `FoodModel`, `Costs`, `Tuning` + `TuningDefs` (every hand number) |
| `aa/moves/` | typed steps and the hands | `Step` (sealed), `Steps` (the step machine), `StepNeeds`, `BaritoneCar` (goals to Baritone), `JobHands` (fork BurrowProcess jobs) |
| `aa/core/` | blackboard plumbing | `Blackboard`, `Inputs`, `JobPort`, `Node`, `Cooldown` |
| `craft/` | crafting and smelting | `CraftPlanner`, `CraftJob` (grid clicks), `FurnaceJobs`, `McRecipes` (client recipe manager), `FuelWood`, `Upgrade` |
| `sense/` | what the bot has seen | `ChunkPass` (one read per chunk), `OreIndex`, `Seen` + `Sightings` (fair-play rays, cave mouths, trees, herds), `CaveMouths`, `CavePatterns`, `Feasibility` |
| `cave/` | caving, exploring, mining rules | `Caves`, `CaveRules`, `ExploreRules`, `MineRules`, `OreSpec` |
| `guard/` | may this block go, at what cost | `BreakPolicy` (the one authority), `GuardCore`, `Guard` (mixin entry points), `PolicyRuntime`, `StructureTable`, `WorldGenFiles`, `Lease` |
| `baritone/` | hooks into Baritone | `CostAdvisorHook` (policy prices into A*), `SafetyNet`, `BaritoneHook` |
| `engine/` | reflexes and the Baritone process | `Reflexes` (air, lava, fire, creeper, hit back, eat), `EngineProcess`, `DangerMoves`, `FireReflex`, `WatchCamera` |
| `threat/` | hostile reach and decisions | `ThreatRuntime`, `ThreatFacts`, `ThreatRules`, `ReachGrid` |
| `commands/` | the game-side adapters and verbs | `Commands` (dispatcher, PMs, cmd.json), `AaRuntime` (AA's adapter, 3.4k lines), `SenseRuntime`, `NearbyLive`, `StepHands`, `Jobs`, `Seq`, `Mining`, `Crafting`, `Chains`, `VerbTable` |
| `fast/` | the local HTTP channel for bridge.ps1 | `FastServer` (127.0.0.1, key header) |
| `memory/`, `storage/`, `io/` | files under `<game dir>/entropybot/` | `Knowledge` (places, chests), `Stock`, `BotFiles`, `WorldState` |
| `clear/`, `camp/`, `farm/`, `restore/` | older job engines, still owner verbs | `ClearEngine`, `HousePlan`, `FarmRound`, `Ledger` |
| `recorder/`, `watchview/`, `surface/`, `map/`, `view/`, `screen/`, `blind/`, `gui/` | owner tools: flight recorder, watch camera, dashboard exports, config screen | `FlightRecorder`, `TunnelView`, `SurfaceExport`, `TerrainMap`, `ReplyShaper`, `DashboardProcess` |
| `mixin/` | 9 mixins, `require = 1` | `GuardMixinGameMode`, `GuardMixinBlockItem`, `GuardMixinCalculationContext`, `RecorderMixinClientLevel` |
| `api/` | `BotAPI`: the static face for scripts | |
Tests: `src/test/java` mirrors the packages (354 files). Pure classes are tested with fakes (`TestData`, fake hands,
fake worlds); scenario tests `Z<nn>...Test` mirror `..\minecraft-bot\docs\SCENARIOS.md`.

## How the decisions work (game state -> decision -> action)
The tick (`Core.onClientTick`): guard, `Commands.tick` (ticks `AaRuntime` every 5 client ticks), reflexes, the chunk
pass, recorder, threat search, policy snapshot. Full order and the fact sources: `docs/ARCHITECTURE.md`.

1. **Facts.** `AaRuntime` fills the `Blackboard`: `SenseRuntime.sense()` every 10 ticks (health, food, bag, light,
   time), plus lazy suppliers for the planner's world (`needWorld`: bag, known sources from `Nearby`/`Sightings`,
   stations, paths), food facts and goals. Game data comes from the client's jars and the server sync (`JarPacks`,
   `McRecipes`, `LootBook`, `WorldGenFiles`, `ItemWorth.fuels`). A fact is loaded, empty or NOT LOADED (rule 15):
   never treat "not loaded" as "none".
2. **Desire.** `Brain` scores every `Desire` 0..100 (owner goals from `aa want`, upkeep, progress) and runs the best;
   with `aa needs off` (the default) only orders and reflexes act.
3. **Plan.** The desire's `Need` asks `NeedChain.resolve(item, n, world)`: have it; craft it (recipes, tags kept as
   tags until a leaf); smelt it (furnace + the fuel table, fuel priced by wood consumed); dig it (loot data + the
   mining-tier data pulls in the pickaxe); hunt it; else explore until a source is known. Each way is priced in rough
   seconds (`TIME_MODEL`): walk = Baritone's complete path (`LivePathPrice`, rule 18), break = the game's formula,
   craft 0.3 s, place 0.15 s, smelt 10 s. Losers go to a dropped list with their reason (`aa why`, `aa debug want`).
4. **Act.** The chain is a list of typed `Step`s; `Steps` runs them on `StepHands`/`BaritoneCar`: a walk is a Baritone
   goal, a tree the fork's ChopProcess, a block Baritone's MineProcess, a placement the fork's BuilderProcess leg, a
   station use the fork's InteractProcess. Mod code never picks a stand spot, a face or a click.
5. **Re-plan.** Checks run at job boundaries: a failed step fails the way (`Failures` holds it by key), the chain is
   re-priced, the next way runs. Stuck and no-progress are facts that re-plan, never parks.

**Eating.** Two layers. The reflex (`engine/Reflexes`) eats from the bag when the bar is low, always on. The food
need (`FoodModel`: energy = nutrition + saturation, a stock target; `FoodOptions`: eat, cook, hunt, farm, or a chain
through `NeedChain`) runs only with `aa needs on`.

**Crafting.** `NeedChain` plans; `craft/CraftPlanner` + `CraftJob` execute with exact grid clicks from the client's
synced recipes; the table or furnace is a known station by priced path or a new one placed by Baritone. Values and
tiers come from item data (`ItemWorth`, `MiningTiers`), the unlock graph from recipes (`TechTree`).

**Finding.** `ChunkPass` reads each chunk once into `OreIndex`; a block is a SOURCE only with an exposed face or when a
ray from the eyes saw it (`Seen`, `Sightings`, rule 17: no x-ray). `Nearby` asks Baritone's world scan first. Caves:
heightmap-shape candidates (`CaveMouths`), confirmed by a ray plus a dark pocket / darkest sky, learned from the
owner's labels (`CavePatterns`, `aa cave`). Strip mining is priced from the world-gen JSON (`OreBand`, `Discovery`).

## Conventions
- **Pure core, thin adapters.** Decision classes have no Minecraft types and a JUnit test; the game side is a
  `*Runtime` / `*Live` / `*Hands` class in `commands/`. Every class javadoc ends with "Pure (JUnit: X). Loader notes:
  ..." naming the vanilla or NeoForge API it touches (Fabric port in mind).
- **Comments cite the cause**: "0.39.z51 item 3 (run L 16:30: ...)". Versions are `0.39.z<NN>`; the next major is 0.40.
- **Every hand number is a `Tuning` entry** (`TuningDefs`: name, default, the run that set it, why); `aa tune` changes
  it per world; `src/test/resources/tuning-defaults.txt` fails the build when a default moves. `// structural:` marks
  the few constants that are geometry, not tuning.
- **No name lists** (`NoNameListsTest`): items, blocks, tools, food by tags, recipes, loot and components only.
- **No silent failure** (`NoSilentCatchTest`, `Quiet.warn`): every "no" has a readable reason; a step ends on an
  observed state with a timeout and a named failure, never on "I clicked".
- **Baritone is the car** (`MoversTest`): no key presses, camera setting or click choosers in mod code.
- **Logging**: `[entropybot]` prefix, rate-limited; `LOG.info` for decisions the owner reads in `latest.log`.
- **Scenario first**: a fix starts as a scenario test with hand-computed seconds, then code, then the owner's watch.
- Rules 1-19 with their checkers: `..\minecraft-bot\docs\RULES.md`. Owner wording: `..\minecraft-bot\docs\OWNER_SAYS.md`.

## Rules for working here
- **You cannot see the game world. Never guess what happens in-game.** If you are unsure, add a clear `[entropybot]`
  log line and ask the owner to run the game and paste the output. A test run's notes contain only what the game LOGGED.
- **Before changing a system, read the related code and explain how it currently works** (the class javadocs are
  the map; `aa why`, `aa debug want <item>` and `aa status` show the live decision).
- **Make small, focused changes rather than large rewrites.** One job, one version, one commit; a job branch
  `job-z<NN>` from `main`, merged after the gate and deleted.
- The owner does nothing by hand: do every edit, build and install yourself. Plans before builds; get the yes once per
  job, then run it to its end. Agents never launch a game.
- Never put the server address or the dashboard key in this repo, and never print the key.
- Keep `..\minecraft-bot\docs\COMMANDS.md` and `MENU.md` current for every verb (`VerbTableTest`).
