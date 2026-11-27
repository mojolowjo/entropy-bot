package io.github.mojolowjo.entropybot.nav.drive;

import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;

import java.util.HashSet;
import java.util.Set;

/** A world of solid cells on a floor at y 63 (feet at 64), plus lava and water cells, for the drivers' tests. */
final class FakeMoveWorld implements MoveDriver.MoveWorld {
    final Set<BetterBlockPos> solid = new HashSet<>(), lava = new HashSet<>(), water = new HashSet<>();
    final Set<BetterBlockPos> holes = new HashSet<>();

    FakeMoveWorld solid(int x, int y, int z) { solid.add(new BetterBlockPos(x, y, z)); return this; }
    FakeMoveWorld hole(int x, int z) { holes.add(new BetterBlockPos(x, 63, z)); return this; }
    FakeMoveWorld lava(int x, int y, int z) { lava.add(new BetterBlockPos(x, y, z)); return this; }

    private boolean isSolid(int x, int y, int z) {
        BetterBlockPos p = new BetterBlockPos(x, y, z);
        return solid.contains(p) || (y <= 63 && y >= 60 && !holes.contains(new BetterBlockPos(x, 63, z))) || y < 60;
    }

    @Override public boolean canWalkThrough(int x, int y, int z) { return !isSolid(x, y, z) && !lava.contains(new BetterBlockPos(x, y, z)); }
    @Override public boolean canWalkOn(int x, int y, int z) { return isSolid(x, y, z); }
    @Override public boolean avoidWalkingInto(int x, int y, int z) { return lava.contains(new BetterBlockPos(x, y, z)); }
    @Override public boolean isWater(int x, int y, int z) { return water.contains(new BetterBlockPos(x, y, z)); }
    @Override public boolean isLiquid(int x, int y, int z) { BetterBlockPos p = new BetterBlockPos(x, y, z); return lava.contains(p) || water.contains(p); }
}
