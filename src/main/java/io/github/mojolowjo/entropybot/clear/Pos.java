package io.github.mojolowjo.entropybot.clear;

/** A block position. {@link #key()} is the bridge's "x y z" text (map keys and messages use it). */
public record Pos(int x, int y, int z) {
    public String key() {
        return x + " " + y + " " + z;
    }

    public static String key(int x, int y, int z) {
        return x + " " + y + " " + z;
    }

    /** "x y z" back to a position. */
    public static Pos parse(String key) {
        String[] p = key.trim().split("\\s+");
        return new Pos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
    }

    @Override
    public String toString() {
        return key();
    }
}
