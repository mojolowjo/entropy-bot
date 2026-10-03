package io.github.mojolowjo.entropybot.farm;

import java.util.List;
import java.util.Map;

/**
 * What the farm round needs to see of the game, without Minecraft types (the bridge's Client.level, player and
 * inventory reads in cropAt, farmDrops, standableCell, harmlessHand...). {@link McFarmWorld} is the game's; the tests
 * use a small map of positions to blocks.
 */
public interface FarmWorld {

    /** A block's "age" property: its value and the highest it gets (the property's possible values - 1). */
    record Age(int age, int max) {}

    /**
     * An item entity lying around: {@code key} identifies it from tick to tick (the entity id), {@code item} is
     * the stack's item id, x y z its position.
     */
    record Drop(String key, String item, double x, double y, double z) {}

    /** True for air at x y z. */
    boolean isAir(int x, int y, int z);

    /** The block's registry id ("minecraft:farmland", "mysticalagriculture:inferium_crop"). */
    String blockId(int x, int y, int z);

    /** The block's "age" property, or null when it has none. */
    Age age(int x, int y, int z);

    /** Can the bot stand with its feet in x y z? Room for feet and head, no liquid, something solid underneath. */
    boolean standable(int x, int y, int z);

    /** The bot's exact position (its feet). */
    double[] pos();

    /** The bot's eye position. */
    double[] eye();

    /** The bot's dimension ("minecraft:overworld"). */
    String dim();

    /** The live item entities the client sees. */
    List<Drop> drops();

    /** Is that item entity still there (alive and not removed)? */
    boolean dropAlive(String key);

    /** Can the bag take that drop (a free slot, or a part stack of the same item with room)? */
    boolean roomFor(Drop d);

    /** The 36 bag slots (hotbar 0-8 first), "" for an empty slot. */
    List<String> slots();

    /** The selected hotbar slot. */
    int selected();

    /** What the bot carries: item id -> count (the 36 bag slots). */
    Map<String, Integer> inventory();

    /** Is Baritone still pathing (not idle)? */
    boolean pathing();

    /** The block the bot's feet are in (the bridge's here()). */
    default int[] here() {
        double[] p = pos();
        return new int[]{(int) Math.floor(p[0]), (int) Math.floor(p[1]), (int) Math.floor(p[2])};
    }
}
