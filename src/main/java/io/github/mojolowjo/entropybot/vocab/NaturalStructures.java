package io.github.mojolowjo.entropybot.vocab;

import java.util.List;

/**
 * 0.33.0 (fresh-world runs: a village was "someone's base" for its loot chests, beds and hay bales; mineshaft planks and
 * fences "looked like a build"): the world's own structures, told apart from player builds. rm-final: the game says so
 * itself. The structure starts the world generator recorded (the integrated server's structure manager) name every
 * structure that covers a spot, vanilla or modded; no block-name palette is guessed. A client on a server has no
 * structure data: {@link #known()} is false and the callers say so instead of calling a build natural or not.
 * Plain Java; loader API: none (the adapter {@code commands.StructureSource} reads NeoForge's / vanilla's structure manager).
 */
public final class NaturalStructures {
    private NaturalStructures() {}

    /** The world's structure data around a spot. */
    public interface Source {
        /** The names ("village_plains", "mineshaft") of the structures whose bounding box meets the cube of radius r round x y z in dim; empty: none. */
        List<String> near(String dim, int x, int y, int z, int r);
    }

    private static volatile Source source;

    public static void use(Source s) { source = s; }

    /** True when structure data is loaded (singleplayer); false on a server, where no structure answer exists. */
    public static boolean known() { return source != null; }

    /**
     * The world's own structure(s) at the spot ("a village_plains and a mineshaft"), or null: none there, or no structure
     * data ({@link #known()} tells which). A failed read is thrown to the caller, which logs it.
     */
    public static String explains(String dim, int x, int y, int z, int r) {
        Source s = source;
        if (s == null) return null;
        List<String> names = s.near(dim, x, y, z, r);
        if (names == null || names.isEmpty()) return null;
        return String.join(" and ", names.stream().map(n -> "a " + n).toList());
    }
}