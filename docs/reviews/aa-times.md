# Review: `aa-times` against `aa-line`

Branch head `43be83f` (0.39.f1), three commits (`307360c` hand constants into Tuning, `c01c6fb` break times and walk
speed from the game, a merge of `aa-line`), `git diff aa-line...aa-times`: 20 files, +639/-79.
Scope: `aa/brain/GameTimes` (pure formulas), `commands/GameTimesLive` (vanilla reads), `Unit` live walk speed,
`ToolWear.Kit` game seconds, `IronOptions`/`CoalOptions` dig term, 24 constants moved into `Tuning`.

## Build and tests (JDK 21, `./gradlew test`)

- Compiles against the stock Baritone jar. 2512 tests, 1 failed, 1 skipped. The failure is
  `BlindModeTest.dashboardProject` (Windows `C:\` paths on Linux); it fails identically on `aa-line`
  (2495 tests, same single failure), so it is not this branch's.
- `GameTimesTest` (14) and `ConstantsTest` (3) pass. The break-time formula was checked against
  `Player.getDigSpeed` / `BlockBehaviour.getDestroyProgress` / `MultiPlayerGameMode` (1.21.1): the terms, their order,
  the 30/100 divisor and `ceil(1/progress)` ticks match. The 5-tick `destroyDelay` after a break is noted and not added.
- Static-init check: `Tuning`'s static block no longer references `Costs`/`Curves`/`Brain`, so `Brain.URGENT =
  Tuning.defOf(...)` and friends cannot hit a half-initialised `Tuning`. The legacy `public static final` names are
  only referenced from javadoc now (grep), so a tuned value is applied everywhere (no half-applied tuning).

## Findings, by impact

### 1. High: the dig term is sampled with the bot's state at sense time, so one tick in the air prices every dig 5x (`commands/GameTimesLive.java:99-106`, `commands/SenseRuntime.java:100-101`, `commands/AaRuntime.java:461,526,552`)
`bestPickaxeStoneSeconds` passes `p.onGround()` and `p.isEyeInFluid(WATER)` into `Dig`. A planning price is for
digging *later, standing*; the sense runs every tick while the bot walks and jumps (Baritone jumps constantly), so
the iron and coal `digBlock` term and `ToolWear.Kit.toolS`/`handS` flap between 0.6 s and 3.0 s per block from one
reprice to the next. Effects: the DIG_KNOWN rows jump by hundreds of walk-blocks (`digCost(18)` goes 46 → 230),
`aa why` shows a different dig number every second, `Replan`/commitment see phantom cost changes, and the wear
"worth" (`ToolWear.java:102`, `left x (hand - tool)`) is scaled by the same 5x. Fix: price the plan with
`onGround = true` (and `inWater` from the *target's* cell, which the iron facts know via `Ore.drown()`/`inWater`),
and keep the live state only for the one block being broken now (the engine's own timing), not for planning.

### 2. Medium: `Unit`'s live walk speed is a process-wide static set from one code path and never reset (`aa/brain/Unit.java:14-17`, `commands/AaRuntime.java:552`)
`setLiveBps` is called only inside the Discovery facts builder, so the live speed is whatever the player had the
last time *that* path ran (minutes ago, or never: then the constant). It survives leaving the world, a death, the
end of a Speed/Slowness effect, and it changes **every** seconds↔blocks conversion in the brain (option costs,
measured-run stats, `Site.travelS`, the `digBlock` term itself) at a different moment than the facts around them.
Mixed-epoch units: `IronOptions.Facts.digBlock = Unit.blocks(seconds)` at facts time, then
`Curves.progress(... Unit.seconds(cost))` with a later speed. Fix: set it once per brain tick at the top of
`AaRuntime.tick` (and clear it on world leave), or better, carry `walkBps` in `Inputs` and pass it where needed so a
reprice is internally consistent and `aa why` can print "walk 4.3 (game)".

### 3. Medium: Soul Speed, Speed and Slowness are read from the attribute while standing on the wrong block (`commands/GameTimesLive.java:120-129`)
`MOVEMENT_SPEED` carries the Soul Speed modifier only while on soul sand/soil, and the sprinting modifier only
while sprinting (handled). A sample taken on soul sand prices every walk in the next reprice at 1.3-1.5x speed on
stone. Same shape as finding 1: a planning number taken from a transient state. Fix: strip the Soul Speed
modifier (the data-driven enchantment's attribute modifier id; check `EnchantmentHelper` for the exact key) the way
sprinting is stripped, or take the attribute's base + effect modifiers only.

### 4. Medium: the deep-dig extra keeps the old constant's seconds while the base term goes live (`aa/IronOptions.java:200-203`)
`deep = digBlock + (DIG_DEEP_BLOCK - DIG_BLOCK)` adds a flat 3 walk-blocks for climb/fall/water risk. That is fine,
but `DIG_DEEP_BLOCK` and `DIG_BLOCK` are walk-block hand numbers outside `Tuning` (rule 2), and the "past 16" extra
is now the only part of the dig that is not from the game or measured (rule 3). Fix: `Tuning.def("dig.deepExtraS",
...)` in seconds, converted with `Unit.blocks` at use.

### 5. Medium: the NeoForge `BreakSpeed` event is skipped, so any other mod's speed change is unpriced (`commands/GameTimesLive.java:35`)
Documented, but rule 4 ("any mod") wants it priced. The event takes the player and a state/pos, not a candidate
stack; it can be fired with the current held item and the ratio applied to the candidate's speed. Fix: compute
`EventHooks.getBreakSpeed(p, state, vanillaSpeedHeld, pos) / vanillaSpeedHeld` once and multiply the candidate's speed
by it; mark it as an approximation.

### 6. Low: `breakSeconds` uses the hardness at the player's own position (`commands/GameTimesLive.java:76`)
`block.getDestroySpeed(level, p.blockPosition())` is correct for `Blocks.STONE.defaultBlockState()` (position-
independent), but the public method takes any `BlockState`, and a position-dependent state (modded) would be priced
at the wrong cell. Fix: take a `BlockPos` parameter or document stone-only.

### 7. Low: `warned` is set once per JVM, so after the first failure every later read is silent (`commands/GameTimesLive.java:43`; rule 7)
A read that throws falls back to the constant and logs once; the second failure (and every one after, maybe a
different cause) is silent. Fix: log once per `what` key, or once a minute.

### 8. Low: `GameTimes.BASE_SWIM_BPS = 2.2` and the Depth Strider interpolation are wiki/hand numbers (`aa/brain/GameTimes.java:36,122`)
Marked as assumptions (rule 8 kept), but `swimBps` is dead code: nothing calls `GameTimesLive.swimBps`. Either wire it
into the water facts (`DangerOptions.SWIM_BPS` is still a hand number there) or drop it until measured.

### 9. Low: `Tuning` moved constants keep the old provenance tags but lose the one place they were defined (`aa/brain/Tuning.java:27-47`)
`def("urgent", 75, "aa-brain 1", ...)` etc. Good. But `Brain.MIN_RUN_MS`, `STUCK_MS`, `CD_BASE_MS`,
`Curves.COST_C`, `Kit.FAR_TRIP`, `Kit.MIN_RECOVER`, `IronOptions.CLEAR/DIG_BLOCK/PLACE_BLOCK/...`,
`CoalOptions.MINE_ORE/SMELT_ITEM` are hand numbers still outside `Tuning` (rule 2). Not a regression; listing it so
the round-T sweep is known to be partial.

## Rule check

- Rule 1: kept; the dig term enters the existing option prices, nothing is special-cased.
- Rule 2 (Baritone is the car): not touched.
- Rule 3 (seconds from the game): the formula is right; findings 1-3 are about *which* state is sampled.
- Rule 4 (any mod): `MINING_EFFICIENCY` via `EnchantmentHelper.forEachModifier` picks up modded enchantments
  (good); finding 5 for other mods' break-speed hooks.
- Rule 7: finding 7.
- Rule 8 (vanilla API, assumptions marked): all reads are vanilla calls that exist on Fabric; `GameTimesLive`
  lists them. `swimBps` assumption marked.

## Not covered by tests that the diff changes

- `GameTimesLive` entirely (the attribute/modifier reading, `withModifiers` operation order, the sprint strip).
- The unit round-trip `Unit.blocks(bestPickaxeStoneSeconds)` → `Facts.digBlock` → `digCost` under a *changed* live
  speed between the two calls (finding 2).
- `ToolWear.price` with a kit whose `toolS > handS` (reads taken under water with a wrong-tier pickaxe): `worth`
  clamps to 0 so USE is priced only by `replace`; no test pins the ordering then.
- `CoalOptions.choose` with a live `digBlock` (only the record plumbing is tested).
- `Brain.bonus` is refreshed from `Personality.of(bb).get("margin")` each tick (`Brain.java:320`), so the Tuning
  entry "margin" only seeds the default; a test that `aa tune margin` changes the challenger threshold is missing.
  `ConstantsTest.tuningReachesTheCode` covers `Worth` and `Costs` only.
