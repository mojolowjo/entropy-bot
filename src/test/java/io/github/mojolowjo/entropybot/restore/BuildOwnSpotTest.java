package io.github.mojolowjo.entropybot.restore;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 0.24.3: no build hint for the bot's own spot. */
class BuildOwnSpotTest {
    static BuildSpotter.Hint hint() {
        List<int[]> b = new ArrayList<>();
        for (int i = 0; i < 8; i++) b.add(new int[]{48 + i % 3, 85, 35 + i / 3});
        return BuildSpotter.spot(b);
    }

    @Test void strangerBuildStillHints() {
        assertFalse(BuildSpotter.ownSpot(hint(), List.of(new int[]{200, 70, 200}), List.of(), List.of(), List.of(new int[]{100, 70, 100})));
    }

    @Test void placeChestOrOwnBlockInsideIsOwn() {
        BuildSpotter.Hint h = hint();
        assertTrue(BuildSpotter.ownSpot(h, List.of(new int[]{49, 85, 36}), null, null, null));
        assertTrue(BuildSpotter.ownSpot(h, null, List.of(new int[]{50, 86, 37}), null, null));
        assertTrue(BuildSpotter.ownSpot(h, null, null, List.of(new int[]{48, 84, 35}), null));
    }

    @Test void nearSpawnIsOwn() {
        BuildSpotter.Hint h = hint();
        assertTrue(BuildSpotter.ownSpot(h, null, null, null, List.of(new int[]{63, 77, 20})));
        assertFalse(BuildSpotter.ownSpot(h, null, null, null, List.of(new int[]{90, 77, 16})));
    }
}
