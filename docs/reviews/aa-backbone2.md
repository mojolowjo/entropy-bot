# Review: `aa-backbone2` against `aa-line`

Branch head `57cdf3a` (0.39.d1), one commit, `git diff aa-line...aa-backbone2`: 15 files, +834/-279.
Scope: `aa/moves/{Car,BaritoneCar,Moves}`, `engine/DangerMoves` as a thin caller, `RunJob.failNow`, the
`MOVE_FAILED_FACT` chain fail, water parked in `DangerOptions`/`ThreatFacts.Ways`, `MoversTest` build gate.

## Build and tests (JDK 21, `./gradlew test`)

- **As pushed the branch does not compile** against the stock Baritone jar the build downloads (see finding 1):
  `BaritoneCar.java:9: cannot find symbol baritone.api.process.IBurrowProcess`.
- With a local-only stub of `IBurrowProcess` and a reflective `getBurrowProcess()` call (not committed anywhere),
  the suite ran: 2506 tests, 1 failed, 1 skipped. The failure is `BlindModeTest.dashboardProject` (Windows `C:\`
  paths on Linux); it fails identically on `aa-line` (2495 tests, same single failure), so it is not this branch's.
- `MoversTest`, `MovesTest`, `RunJobTest.failNowCancelsTheChainAsAFailure` all pass under the stub.

## Findings, by impact

### 1. Critical: the mod no longer builds without the fork jar (`build.gradle:29`, `aa/moves/BaritoneCar.java:9`)
`build.gradle` falls back to the stock Baritone jar when `libs/*-entropy.jar` is absent, and the javadoc claims the
mod then "compiles and runs against it" with a runtime "no BurrowProcess" message. It does not: `IBurrowProcess`
is imported and used as a type, so compilation fails. Every fresh checkout (CI, the owner's other machine, this
review) is broken. Fix: either commit a tiny `baritone.api.process.IBurrowProcess` source stub under a
`src/fork-api/` source set that is excluded when the fork jar is present, or reach the process reflectively through a
small `BurrowBridge` whose only compile-time dependency is the stock API; keep the `LinkageError` path for the stock jar.

### 2. High: Moves' clock starts at 0 and is never advanced while idle, so the first leg of a way times out at once (`aa/moves/Moves.java:30,94,120`)
`legStart = now` in `startLeg`, but `Moves.now` is only set inside `tick()`. `DangerMoves.tick` returns early when
`move == NONE` (`engine/DangerMoves.java:324`), so Moves is never ticked between ways; `DangerMoves.time(tick)` does
not reach it. Scenario: game tick 48 000, AA starts a flee: `legStart = 0` (or the last tick of the previous way,
minutes ago); the first `tick(48000)` sees `now - legStart > WALK_TICKS` and fails "timed out after 30 s" before
Baritone has computed a path. Every danger way that starts more than 30 s (walk) / 15 s (pillar) / 40 s (dig) after
the previous one fails on its first tick, the chain is failed by `failNow`, and the way is charged in `Failures`.
`MovesTest.aLegOverItsTimeFailsAndStopsTheCar` masks it by calling `m.tick(100)` before `m.pillar(...)`. Fix: give
`Moves` a `time(long tick)` that `DangerMoves.time` forwards every tick (or pass `now` into each way's entry point),
and add a test that starts a way without a prior tick at a large tick value.

### 3. High: the burrow and dig-in have no fallback when the fork is missing at run time, and `status()` reports it as "the burrow never started" one tick late (`aa/moves/BaritoneCar.java:107-123`)
`engine.hold()` runs before the `getBurrowProcess()` call; on `LinkageError` `forced` is set but the hold stays
until `stop()`. Fine. But `DangerOptions` still prices BURROW/DIG_IN as possible on the stock jar, so AA will pick
them, fail, charge them, and pick them again after the hold expires, forever. Rule 7 is kept (there is a reason),
rule 1 is not: the fact "this Baritone has a burrow process" is missing from the facts, so the option is priced on
a capability that does not exist. Fix: expose `BaritoneCar.hasBurrow()` (one `try { b().getBurrowProcess() }` at
construction) into `ThreatFacts.Ways` and drop the rows with "no BurrowProcess in this Baritone".

### 4. Medium: the leg time limits are hand numbers that disagree with the priced seconds (`aa/moves/Moves.java:21`, `aa/moves/BaritoneCar.java:40`; rule 2, rule 3)
`WALK_TICKS = 600`, `PILLAR_TICKS = 300`, `DIG_TICKS = 800`, `CALC_FAILS = 20` live outside `aa/brain/Tuning`,
and the pillar/dig limits are unrelated to the seconds `DangerOptions` priced (`PILLAR_S = 1.5`, `DIG_S = 3`,
`danger.burrowS = 6`). A 15 s pillar limit for a 1.5 s priced pillar, and a 40 s burrow for a 6 s priced one, means
`aa why` shows one number and the run allows ten times that before it fails. Fix: pass the priced seconds into the
way (`Moves.pillar(feet, up, limitS)`), derive the tick limit as `k x priced` with `k` in Tuning
(`danger.legSlack`), and move `CALC_FAILS` to Tuning or derive it from Baritone's `failureTimeoutMS`.

### 5. Medium: a failed move is dropped when no AA danger job runs (`commands/AaRuntime.java:1154`; rule 7)
`takeFailure()` consumes the failure every tick, but the fact is only set `if (dangerJob)`. When the owner's
`danger flee` (or a reflex-started move) fails, the string is consumed and nothing but the event ring sees it; the
owner's command gets no "failed" reply and `Failures` is not charged. Fix: when `!dangerJob`, hand the string to the
reflex owner (`Reflexes` log/whisper) instead of discarding it.

### 6. Medium: a stale `MOVE_FAILED_FACT` fails the next danger job (`aa/AaDesires.java:747-749`)
The fact is set by AaRuntime on the tick the move fails and cleared only when the danger desire's node ticks. If
the danger job ended the same tick (`stop(...)` then `fail(...)` ordering, or the brain switched desire), the fact
survives until the next time a danger job runs, and `ran && mf instanceof String` then fails a fresh, healthy way
with last week's reason. Fix: clear the fact in `RunJob.end`/when the danger job starts, or stamp it with the job's
start tick and ignore older stamps.

### 7. Medium: `DangerMoves.tick` checks the 90 s give-up before ticking Moves, so a way that arrived on that tick is reported as failed (`engine/DangerMoves.java:328-329`)
Order: `MAX_TICKS` check → `moves.tick`. A pillar that completes exactly at tick 1800 is failed "gave up after
90 s" and charged. Low probability, but it is also the only path where `fail()` runs without `moves.tick` having
been consulted, so `moves.why()` in the message is one tick stale. Fix: tick Moves first, then test the limit.

### 8. Low: `hitInReach` now turns the head every tick a hostile is in reach, even when the hit is on cooldown (`engine/DangerMoves.java:483-485`)
Before, the look only happened when a hit was about to land. Now `moves.lookAt` runs first and the cooldown test
after, so while holding on a pillar the LookBehavior target fights Baritone's own look (Baritone's look is used
for placing during the pillar leg). Fix: keep the cooldown test first, as before.

### 9. Low: flee "from" points get the bot's own Y (`engine/DangerMoves.java:106`)
`GoalRunAway` is XZ-based so this is harmless today, but `FleeHeading.Mob` could carry y and the comment should say
the Y is a placeholder. Fix: use the mob's y or document the assumption (rule 8: assumptions marked).

### 10. Low: `MoversTest` misses `setSprinting(`, `setDeltaMovement(`, `jumpFromGround(`, `attack(` (`aa/moves/MoversTest.java:27`)
The gate's regex catches keys, camera, break and place. `Reflexes` (3) and `CreeperDuel` (3) still move the player
with `setDeltaMovement`/`setSprinting`, and `attack(` is unlisted. The gate is weaker than the rule it enforces.
Fix: add those to `CALL` and set today's counts as ceilings.

### 11. Low: `Ways` 14-arg constructor silently reports `waterNear = false` (`threat/ThreatFacts.java:47-51`)
Test-only callers are fine, but any production caller that forgets the new argument prices water ways as
possible. Fix: make the 14-arg constructor package-private or test-only.

## Rule check

- Rule 1 (options x costs x facts): water parked as impossible is the owner's call; finding 3 is the gap.
- Rule 2 (Baritone is the car): kept for walk, run, pillar, dig, seal, look. The strike (`gameMode.attack`) stays,
  by design. `MoversTest` enforces it at build time; see finding 10 for its blind spots.
- Rule 3 (seconds from the game): the leg limits are hand ticks; finding 4.
- Rule 6 (one driver): kept; `begin()` cancels the previous way through Moves.
- Rule 7 (no silent failure): findings 5 and 6.
- Rule 8 (vanilla API, assumptions marked): `BaritoneCar` is Baritone-API only plus the fork's interface;
  `LinkageError` path marked. The build claim in `build.gradle` is wrong (finding 1).

## Not covered by tests that the diff changes

- `BaritoneCar` entirely (settings save/restore, `calcFails` leg failure, `status()` re-override after a cancel).
- `DangerMoves.verdict()` (the guard verdict string), `waterNear()`, the `holding` anchor check.
- `AaDesires` line 749: the `MOVE_FAILED_FACT` to `failNow` hand-off (only `RunJob.failNow` itself is tested).
- `AaRuntime.takeFailure()` plumbing.
- A way started at a large tick without a prior `Moves.tick` (finding 2).
- `EngineProcess.calcFails` counting.
