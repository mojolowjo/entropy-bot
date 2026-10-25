# Entropy Bot

A client-side NeoForge mod (Minecraft 1.21.1) that turns one Minecraft account into a helper bot. It is an improved
[Baritone](https://github.com/cabaletta/baritone): our own Baritone fork does every move, dig and place, and the mod
decides what to do. The owner gives orders in game (`aa want torch 16, iron_pickaxe`); the bot plans each order from
the game's own data (recipes, tags, loot tables, world-gen, burn times), prices every way in seconds, runs the
cheapest and re-plans when a step fails. No AI model is needed to run it.

## What it does
- **Orders** (`aa want <item> [n]`, `aa want strip <ore> ...`, `aa want deposit`): craft, smelt, dig, hunt or explore
  for anything the recipes and loot tables know, vanilla or modded, with the stations and tools it needs made on
  the way. `aa why` and `aa debug want <item>` show every way considered, its price and why the losers lost.
- **Legit play.** It only uses ore it has seen with an exposed face (no x-ray), finds caves from the surface shape
  and confirms them by sight, strip-mines at the ore's real world-gen band.
- **Respect for builds.** A fail-closed guard checks every block click and Baritone's own path costs: structure
  blocks outside the owner's areas are never broken, keep-out areas never entered, what the bot placed it may take
  back. Areas are drawn in game (`area ...`).
- **Survival reflexes** (air, lava, fire, creepers, eating from the bag) are always on; with `aa needs on` the bot
  also looks after food, torches and stock by itself.
- **Owner tools**: a watch camera, a flight recorder, a local dashboard, a config screen and a PowerShell bridge.

## Building
Needs JDK 21. Gradle downloads the official `baritone-unoptimized-neoforge-1.11.3.jar` into `libs/` and checks its
sha1; put the fork jar (`...-1.11.3-entropy.jar`, built from the fork's source) beside it to compile against the fork.

```
./gradlew test build   # JUnit tests and build/libs/entropybot-<version>.jar
./gradlew runClient    # a dev client with Baritone loaded next to the mod
```

## Installing
Put the mod jar and ONE Baritone jar (the fork, or the stock unoptimized build) into the client's `mods` folder; the
shipped `baritone-api-neoforge-*.jar` must be disabled (same mod id). Client only; the server never sees the mod.

## For developers
`CLAUDE.md` is the map of the code (packages, the decision flow, conventions); `docs/ARCHITECTURE.md` has the tick
order, the fact sources and the files the mod writes. `companion/` is a small separate mod for the owner's own game.

## License
LGPL-3.0-or-later (see `LICENSE` and `LICENSE.GPL`). Baritone is LGPL-3.0 and the mod patches into it.
