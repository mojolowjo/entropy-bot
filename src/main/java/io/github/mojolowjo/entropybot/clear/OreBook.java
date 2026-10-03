package io.github.mojolowjo.entropybot.clear;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The ores left in place for players (the bridge's memory.ores, PM "ores"). The clear notes the ores it leaves and
 * forgets the ones it mines; the wiring backs this with the bot's knowledge files.
 */
public interface OreBook {
    /** Lists the ore at x y z (block name, e.g. "iron_ore"); true when it wasn't listed yet. */
    boolean note(int x, int y, int z, String name);

    /** An ore was mined: drop it from the list; true when it was listed. */
    boolean forget(String key);

    /** A plain in-memory book ("x y z" -> block name, in the order noted). */
    final class Simple implements OreBook {
        public final Map<String, String> ores = new LinkedHashMap<>();

        @Override
        public boolean note(int x, int y, int z, String name) {
            return ores.putIfAbsent(Pos.key(x, y, z), name) == null;
        }

        @Override
        public boolean forget(String key) {
            return ores.remove(key) != null;
        }
    }
}
