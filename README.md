# Entropy Bot

A client-side NeoForge mod (Minecraft 1.21.1) that turns a Minecraft account into a helper bot, with
[Baritone](https://github.com/cabaletta/baritone) as the legs. Baritone only walks; everything the
bot breaks or places goes through the mod's own engine, inside areas the owner defines in game, behind
a guard that checks every block click and refuses when unsure.

This is release **0.1** (milestone B0 of the plan): the guard, the areas policy, leases, Baritone path
and log events, and an atomic JSON file helper, all behind one static class scripts can call. The jobs
themselves (mining, farming, chests, crafting) still live in a KubeJS script that calls this mod and
move into it release by release.

## What it does today

- **Guard, three hooks.** A Mixin into `MultiPlayerGameMode` catches every block break and every
  liquid, fire, spawn egg or entity put down; a Mixin into `BlockItem.place` catches every block
  placement (after doors, buttons and menus had their turn); a Mixin into Baritone's
  `CalculationContext.isPossiblyProtected` keeps its route planner from planning breaks the guard
  would refuse.
- **The floor** can't be switched off: blocks with a block entity (chests, machines, beds...), building
  blocks that don't occur in the wild (planks, glass, doors, torches, rails, crops...), protect boxes,
  the Nether and the End.
- **Areas and leases** on top: a break or placement needs a lease (a job's short-lived permission for
  one small box) inside an area; with no areas or no lease nothing breaks. `log` mode records what
  those rules would have refused instead of refusing; the floor is enforced either way.
- **Events.** Baritone's path events (`CALC_FAILED`, `AT_GOAL`, `CANCELED`...) and chat lines, and the
  guard's decisions, in a ring with sequence numbers.
- **Files.** Atomic JSON writes under `<game dir>/entropybot/`.

## For scripts

```
io.github.mojolowjo.entropybot.api.BotAPI
  String version();  String features();  String token()
  String events(long afterSeq, int max);  long lastSeq()
  String guard();  String check(String dim, int x, int y, int z, String action)   // break | place | go
  String policy();  String setPolicy(String json, String token);  String mode(String mode, String token)
  String lease(String token, String task, String boxJson, boolean place);  String forceLease(String token, String task, String boxJson)
  void release(String id);  void releaseAll(String token);  int heartbeat(String token)
  String vetoes(int max);  String writeJson(String name, String json);  String readJson(String name)
  boolean hold()
```

Policy JSON: `{"areas":[{"name":"home","x1":-120,"z1":100,"x2":60,"z2":260}],"protect":[{"name":"base","x1":-40,"y1":45,"z1":170,"x2":-10,"y2":80,"z2":200}]}`
(a box without `y1`/`y2` covers every height; `dim` defaults to the overworld). A lease box needs
`y1` and `y2` and may cover at most 4,096 blocks (64 for a force lease).

From KubeJS: `var BotAPI = Java.tryLoadClass('io.github.mojolowjo.entropybot.api.BotAPI')` (null when the
mod is absent).

## Building

Needs JDK 21. Gradle downloads the official `baritone-unoptimized-neoforge-1.11.3.jar` into `libs/`
and checks its sha1 against the release's `checksums.txt`.

```
./gradlew build        # JUnit tests and build/libs/entropybot-<version>.jar
./gradlew runClient    # a dev client with Baritone loaded next to the mod
```

## Installing

Put the mod jar and `baritone-unoptimized-neoforge-1.11.3.jar` into the client's `mods` folder. The
shipped `baritone-api-neoforge-1.11.3.jar` must be disabled first: both carry the mod id `baritoe`, and
the mod's Mixin into Baritone needs the readable class names of the unoptimized build (the plugin
skips that one Mixin when it finds the ProGuard build instead, and `features()` then lacks
`guard:astar`). Client only; the server never sees the mod.

## License

LGPL-3.0-or-later (see `LICENSE` and `LICENSE.GPL`). Baritone is LGPL-3.0 and the mod patches into it.
