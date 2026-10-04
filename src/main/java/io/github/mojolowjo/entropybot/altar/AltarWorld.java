package io.github.mojolowjo.entropybot.altar;

import java.util.Map;

/**
 * Package E: what {@link AltarRun} needs of the game (the client), so JUnit can drive it with a fake. In game it is
 * {@link McAltarWorld}.
 */
public interface AltarWorld {
    /** One stack in a block entity's inventory. */
    record Stack(String id, int count) {}

    /** The block's registry id at pos ("mysticalagriculture:infusion_pedestal"), or null when its chunk isn't loaded. */
    String block(int[] pos);

    /**
     * The block entity's items by slot (the altar: 0 = input, 1 = output; a pedestal: 0), as the server last synced
     * them to the client; null when there is no block entity or it can't be read.
     */
    Map<Integer, Stack> items(int[] pos);

    /** The altar's "Active" flag (crafting), or null when it can't be read. */
    Boolean active(int[] altar);

    /** How many of the item the bag holds. */
    int bag(String id);

    /** Empty slots in the bag (36). */
    int freeSlots();

    /** No menu is open (a click on a block would go to it); false = it closed one and the caller waits a tick. */
    boolean ready();

    /** Holds {@code item} (null = an empty hand), right-clicks the block: null when the click was sent, else why not. */
    String use(int[] pos, String item);
}
