package io.github.mojolowjo.entropybot.gui;

/**
 * An open container menu as the GUI toolkit sees it (B7b part 2): slots by index, the cursor, and clicks with the
 * vanilla click types. {@code McMenu} wraps the game's menu; JUnit uses a fake with vanilla's click rules.
 */
public interface GuiMenu {
    int size();

    /** The item id in slot i ("minecraft:dirt"), or null when it is empty. */
    String id(int i);

    int count(int i);

    /** The max stack size of the item in slot i (0 when empty). */
    int stackMax(int i);

    /** The bot's inventory or hotbar (never armor). */
    boolean mine(int i);

    /** The bot's armor or offhand slots: never clicked. */
    boolean armor(int i);

    /** How the container's slot i looks to a probe: "other" (upgrade/filter/display), "plain" (takes dirt), "fuel" (takes coal, not dirt) or "output". */
    String probe(int i);

    /** Would slot `to` take the stack now in slot `from`? */
    boolean mayPlace(int to, int from);

    /** The most of the stack in slot `from` that slot `to` holds. */
    int slotLimit(int to, int from);

    /** Same item and same data (they would merge). */
    boolean same(int a, int b);

    /** null when the game won't say. */
    Boolean cursorHas();

    /** The cursor's stack would merge with slot i's. */
    boolean cursorSame(int i);

    /** type: PICKUP, QUICK_MOVE, THROW. */
    void click(int i, int button, String type);
}
