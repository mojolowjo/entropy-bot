package io.github.mojolowjo.entropybot.nav.steer;

import java.util.List;

/**
 * Ghost steering S1: where the steerer heads. {@code cells} are the block cells that count as "there" (for a route, the
 * valid cells of the move it aims at; the feet's cell must be one of them), and the aim point is where the feet should
 * end, usually the centre of the move's last cell at its floor.
 *
 * <p>Pure (JUnit: SteererTest). Loader notes: none.
 */
public record Target(List<Cell> cells, double x, double y, double z) {
    /** One block cell. */
    public record Cell(int x, int y, int z) {}

    /** A target of one cell, aimed at its centre, feet on its floor. */
    public static Target cell(int x, int y, int z) {
        return new Target(List.of(new Cell(x, y, z)), x + 0.5, y, z + 0.5);
    }

    /** Is a body with its feet at (x, y, z) in one of the cells? */
    public boolean holds(double x, double y, double z) {
        // structural: the feet's cell, with a hair of slack so standing on a floor (y = n.0) counts as cell n
        int bx = (int) Math.floor(x), by = (int) Math.floor(y + 1e-6), bz = (int) Math.floor(z);
        for (Cell c : cells) if (c.x == bx && c.y == by && c.z == bz) return true;
        return false;
    }

    /** Straight distance from (x, y, z) to the aim point, the height weighted by {@code upWeight}. */
    public double distance(double x, double y, double z, double upWeight) {
        double dx = this.x - x, dy = (this.y - y) * upWeight, dz = this.z - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
