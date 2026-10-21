# Review: `aa-4a-o` against `aa-line`

Branch head `13db84a` (0.39.e1), one commit, `git diff aa-line...aa-4a-o`: 12 files, +626/-9.
Scope: new option sets `StationOptions`, `BaseOptions`, `BagOptions`, shared `Price`, `BedOptions` extended (known
bed, climb prerequisite), `commands/NeedFacts` (facts suppliers; not wired, "piece N"), four new tests.

## Build and tests (JDK 21, `./gradlew test`)

- Compiles against the stock Baritone jar. 2511 tests, 1 failed, 1 skipped. The failure is
  `BlindModeTest.dashboardProject` (Windows `C:\` paths on Linux); it fails identically on `aa-line`
  (2495 tests, same single failure), so it is not this branch's.
- `StationOptionsTest` (6), `BaseOptionsTest` (4), `BagOptionsTest` (4), `BedOptionsTest` (+1), `NeedFactsTest` (1) pass.
- Nothing in this branch runs yet: `NeedFacts.install` has no caller and no desire reads the four facts keys, so the
  findings below are about what will happen once piece N wires it.

## Findings, by impact

### 1. High: the "drop junk" row runs a chain command no verb understands (`aa/BagOptions.java:24,65-70`)
`DROP_JUNK = "drop junk"` goes to `Commands` `case "drop"` → `Storage.drop(player, "junk")` → `GuiCore.drop`, which
resolves `"junk"` as an item id against the bag and finds nothing. The row is the cheapest in the set whenever any
junk is in the bag (2 s vs. a deposit's 2 x base distance), so the bag need will pick it first every time, fail with
"no junk in the bag"-style text, be held by `OptionMemory`, then run the deposit, and repeat after the hold. The
comment admits "verb to confirm". Fix: either add a `drop junk` form to `Storage.drop` that walks `JunkRules.DEFAULT`
(the bag already knows `junkSlots`), or build the chain as `drop <id> all then drop <id> all ...` from the ids
`NeedFacts.bag` counted, so the command and the price describe the same action.

### 2. High: the base set prices the night with a hand danger of 1.0 / 1.5 instead of the brain's night danger (`commands/NeedFacts.java:74`, `aa/BaseOptions.java:34-36`; rules 1 and 2)
`danger = in.night ? (in.thunder ? 1.5 : 1.0) : 0` ignores difficulty, armour, health, torches and hostiles, all of
which `Costs.nightDanger(...)` already computes from `Inputs` and the danger/hide desires already use. On peaceful
the base set still adds a full "open night" surcharge; in full iron with torches around it over-prices the house by
the same amount as naked at 3 hp. The two numbers are also hand numbers outside `Tuning`. Fix:
`Costs.nightDanger(in.difficulty, in.armorPoints, in.health, in.maxHealth, in.torchesNear, in.hostilesNear)`, 0 by day.

### 3. Medium: `Price` carries nine named defaults outside `Tuning`, and `Tuning.get` returns NaN for them so `aa why` cannot show or change them (`aa/Price.java:17-33`; rule 2)
`station.craftS 0.5`, `station.placeS 0.25`, `station.near 48`, `station.nearGuess 16`, `climbPerBlockS 1.5`,
`base.houseS 300`, `base.nightW 1`, `bag.depositS 5`, `bag.dropS 2` are read with `tune(name, def)`; none is
registered, so `aa tune` neither lists nor sets them, and `aa why` prints a number whose origin is a Java literal.
The TODO says piece T adds them; `aa-times` (round T) does not, so the two branches merge into a state where the
TODO is already false. Fix: register them in `Tuning` on this branch (one `def` each, provenance "AA_ROUND4A O") and
make `Price.tune` a plain `Tuning.get`.

### 4. Medium: `base.houseS = 300` and `climbPerBlockS = 1.5` are step times from nowhere (`aa/BaseOptions.java:47`, `aa/Price.java:30-32`; rule 3)
The house's time is a flat 300 s whatever the plan (`HousePlan` knows its block count; `GameTimes` on `aa-times`
knows the place time), and the climb is 1.5 s per block whether it is a staircase Baritone walks (about 1 block/s
up a stair) or a pillar. Both numbers decide between "house" and "shelter here" and between "climb then hunt" and
"stay". Fix: `houseS = plan.blocks() x (placeS + walkPerBlock)` from `HousePlan`, and the climb from
`Unit.seconds(depth x stairLength)` or the measured `climb` chain time in `BrainStats`.

### 5. Medium: recipe amounts and material names are a vanilla list (`aa/StationOptions.java:28`, `commands/NeedFacts.java:62-66,75,81`; rule 4)
`TABLE("crafting_table","planks",4)`, `FURNACE("furnace","cobblestone",8)`, `CHEST("chest","planks",8)` hard-code
vanilla recipes; `NeedFacts` counts `minecraft:dirt`, `minecraft:cobbled_deepslate`, `minecraft:chest` by name and
assumes a stack of 64 for every junk id (`(count + 63) / 64`). The furnace accepts any `#stone_crafting_materials`
(blackstone, cobbled deepslate), modded tables/chests exist, and `TechTree` already holds the recipes. Fix: read the
amount and the ingredient tag from `TechTree`/the recipe manager, and the stack size from `Item.getMaxStackSize`
via `Inputs` (a `bag` of counts cannot know it; add `stackSize` to the sensed bag or count slots in the sense).

### 6. Medium: "use known" with no cell is a `(nothing)` row that wins at 16 walk-blocks (`aa/StationOptions.java:66-69`)
When only "near" is sensed (`knownAt == null`), the row's command is `Options.NOTHING` priced at `station.nearGuess`.
`Rung`/`Replan` treat `NOTHING` as "this rung is satisfied, go on"; for a station need that means the need is
declared met because a table is *somewhere* within 48 blocks, and the next step (a craft) runs where the bot stands,
without a table. Fix: without a cell, drop the row ("known near, no cell sensed") or price it as `goto` the sensed
`tableKnown` position (the camp latch in `AaLadder` has one).

### 7. Medium: the known-bed row is priced without the bed's distance and offered under ground (`aa/BedOptions.java:56-59`)
`Price.s(danger.bedS)` = 20 s flat ("no bed distance in the facts"), and the row skips `climbFirst(f)`: under ground
at night with a bed known on the surface, "sleep" wins at 20 s over a 60 s hunt+craft, `SleepJob.start` then fails
("no bed near") and is charged. `Inputs.bedKnown` is `BrainState.bedNear`, which is a radius flag, not a position.
Fix: carry the bed position in the facts (`Inputs` has `campPos`; the camp latch knows the bed), price
`2 x distance + lieDownS`, and prepend the climb when `under`.

### 8. Low: `BaseOptions` offers the burrow without a floor fact (`aa/BaseOptions.java:62-66`)
`DangerOptions` prices BURROW only with `Ways.burrow()` (a diggable floor, not wet); here the row is offered at
night unconditionally. Over water or on bedrock it fails at run time with a reason (rule 7 kept) and is charged, but
the price never said it could not work. Fix: pass `ThreatFacts.Ways.burrow()`/`waterNear` into `BaseFacts`.

### 9. Low: `BagOptions.deposit` uses `2 x baseDist` but the deposit chain walks there and the *next* desire walks back from the base (`aa/BagOptions.java:45-47`)
The "and back" half is charged to the bag need although the return leg belongs to whatever runs next (which prices
its own walk from the new position). Double counting against the "chest here" row, which is charged no return.
Fix: price one leg, or charge the return in both rows.

### 10. Low: `NeedFacts.wood` builds a fresh `OptionMemory` each call (`commands/NeedFacts.java:38-41`)
The wood row the station set prepends is chosen without the real wood memory, so a wood way that just failed is
still offered as the station's "cut + craft" prefix. Fix: pass the picker's shared memory in `install`.

## Rule check

- Rule 1 (options x costs x facts): the structure is right (rows, dropped reasons, held rows); findings 2, 6, 7
  are prices that do not come from facts.
- Rule 2 (hand numbers in Tuning): findings 2, 3.
- Rule 3 (step times from the game or measured): finding 4.
- Rule 4 (any mod, no vanilla lists): finding 5.
- Rule 6 (one driver): kept; everything goes through the Picker's chains.
- Rule 7 (no silent failure): kept in the sets (every dropped row has a reason); finding 1 is a way that can
  never succeed.
- Rule 8 (vanilla API, assumptions marked): `NeedFacts` has no game calls; the `Price` TODO marks its assumption.

## Not covered by tests that the diff changes

- `NeedFacts.install` (registration keys, the supplier casts) and `NeedFacts.wood`.
- `StationOptions`: the "place from the bag" row, "gather + craft furnace", the `NOTHING` row when `knownAt == null`,
  and a set where `inBag && known` both hold (ordering).
- `BedOptions`: a known bed at night with `wool >= WOOL` (sleep vs. craft bed ordering) and a known bed while
  `under` (finding 7).
- `BagOptions.switchable`, `BaseOptions.switchable`, `StationOptions.switchable` (all three are the same one-liner;
  one shared `Options.switchableUnlessOwnerOrDanger` would need one test).
- `Price.tune` when the name *is* registered (the default must lose to Tuning; today no entry exists so the branch
  cannot be tested).
- `BaseOptions.nightCost` for `danger > 1` and for `base.nightW != 1`.
