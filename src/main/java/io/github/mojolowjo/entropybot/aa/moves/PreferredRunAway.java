package io.github.mojolowjo.entropybot.aa.moves;

import baritone.api.pathing.goals.GoalRunAway;
import net.minecraft.core.BlockPos;

/**
 * Backbone step 4: Baritone's {@link GoalRunAway} with the FleeHeading lean ({@link RunPrefer#bias}). Loader notes:
 * Baritone API only (GoalRunAway is public and not final; its no-arg heuristic() calls the 3-arg one, so the lean is
 * seen there too). equals/hashCode are identity: GoalRunAway's hashCode unboxes a null maintainY.
 */
final class PreferredRunAway extends GoalRunAway {
    private final double hx, hz;
    private final int ox, oz;

    PreferredRunAway(double distance, double[] heading, int ox, int oz, BlockPos... from) {
        super(distance, from);
        double[] u = RunPrefer.unit(heading);
        this.hx = u == null ? 0 : u[0];
        this.hz = u == null ? 0 : u[1];
        this.ox = ox;
        this.oz = oz;
    }

    @Override public double heuristic(int x, int y, int z) {
        return super.heuristic(x, y, z) + RunPrefer.bias(x, z, ox, oz, hx, hz);
    }

    @Override public boolean equals(Object o) { return this == o; }

    @Override public int hashCode() { return System.identityHashCode(this); }

    @Override public String toString() {
        return "PreferredRunAway heading " + Math.round(hx * 100) / 100.0 + " " + Math.round(hz * 100) / 100.0 + " " + super.toString();
    }
}
