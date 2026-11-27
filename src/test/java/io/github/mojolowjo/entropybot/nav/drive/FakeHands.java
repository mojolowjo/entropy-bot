package io.github.mojolowjo.entropybot.nav.drive;

import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import io.github.mojolowjo.entropybot.nav.util.Rotation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Hands set by hand for the drivers' tests: what the crosshair is on, what is in reach, the bag, crouching. */
final class FakeHands implements MoveDriver.Hands {
    final Set<BetterBlockPos> lookingAt = new HashSet<>();
    final Map<BetterBlockPos, Rotation> reach = new HashMap<>();
    final Map<BetterBlockPos, MoveDriver.PlaceSense> place = new HashMap<>();
    boolean crouching, block = true;
    Rotation toward = new Rotation(0, 45);

    @Override public boolean lookingAt(BetterBlockPos c) { return lookingAt.contains(c); }
    @Override public Rotation reach(BetterBlockPos c) { return reach.get(c); }
    @Override public Rotation toward(double x, double y, double z, boolean sneak) { return toward; }
    @Override public MoveDriver.PlaceSense place(BetterBlockPos cell, boolean preferDown, boolean sneak) { return place.get(cell); }
    @Override public boolean crouching() { return crouching; }
    @Override public boolean hasBlock() { return block; }
}
