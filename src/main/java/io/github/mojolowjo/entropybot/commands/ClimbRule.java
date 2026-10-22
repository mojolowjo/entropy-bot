package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.clear.ClearBox;

/** Pure part of {@link ClimbGoal} (JUnit): feet at level y or higher inside the footprint, where the fence allows. */
final class ClimbRule {
    interface Allowed { boolean ok(int x, int y, int z); }

    final int y;
    final ClearBox box;
    private final Allowed allowed;

    ClimbRule(int y, ClearBox box, Allowed allowed) {
        this.y = y;
        this.box = box;
        this.allowed = allowed;
    }

    boolean in(int x, int fy, int z) {
        return fy >= y && x >= box.x1() && x <= box.x2() && z >= box.z1() && z <= box.z2() && allowed.ok(x, fy, z);
    }

    /** Blocks sideways from x z into the footprint (0 inside). */
    int dx(int x) { return x < box.x1() ? box.x1() - x : x > box.x2() ? x - box.x2() : 0; }

    int dz(int z) { return z < box.z1() ? box.z1() - z : z > box.z2() ? z - box.z2() : 0; }
}