package io.github.mojolowjo.entropybot.route;

/** A block position in world coordinates; as a walking cell it is a "feet position" (Baritone's node). */
public record Cell(int x, int y, int z) {
    /** Straight-line distance in blocks. */
    public double dist(Cell o) {
        double dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public String toString() {
        return x + " " + y + " " + z;
    }
}
