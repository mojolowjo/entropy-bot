package io.github.mojolowjo.entropybot.route;

/**
 * {@code route dump x y z}: writes the box holding (x, y, z) (its cells and Baritone moves) as a JUnit fixture into
 * outDir. R2 implements it and installs itself with {@link RoutePlannerHolder#setDumper}. Called on the game thread;
 * true when the dump was written (or started), false when it can't be done.
 */
public interface RouteDumper {
    boolean dump(int dim, int x, int y, int z, java.nio.file.Path outDir);
}
