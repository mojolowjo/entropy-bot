package io.github.mojolowjo.entropybot.strip;

import io.github.mojolowjo.entropybot.clear.ClearWorld;

/** B7d D2: what the strip mine's rules need beyond the clear engine's view of the world. */
public interface StripWorld extends ClearWorld {
    /** The pickaxe a block needs: "stone", "iron" or "diamond" (the needs_*_tool tags; "stone" when in none). */
    String toolNeed(int x, int y, int z);

    /** It only drops with the right tool (stone, ores...). */
    boolean needsCorrectTool(int x, int y, int z);

    /** A placement may replace it (air, grass, a fluid...). */
    boolean replaceable(int x, int y, int z);

    /** Its chunk is loaded on the client. */
    boolean loaded(int x, int y, int z);

    /** The owner said "untrust x y z" for this block: the mine never adopts it as its chest or table. */
    default boolean untrusted(int x, int y, int z) { return false; }
}
