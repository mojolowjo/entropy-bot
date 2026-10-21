package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 0.31.0 (trial 11, item 3): the roof of a house dug into the ground ("nowhere to stand" at -69 61 -11). */
class Fix031ClearTest {
    /** Trial 11's house_main: box x -70..-64, y 57..61, z -16..-10 dug into ground that reaches y 63 all around. */
    static FakeWorld sunkenHouse() {
        FakeWorld w = new FakeWorld();
        w.fill(-80, -54, 50, 63, -26, 0, "stone");
        w.fill(-70, -64, 57, 57, -16, -10, "cobblestone");           // the floor
        w.fill(-69, -65, 58, 61, -15, -11, "air");                    // inside, and the roof layer's inner cells
        for (int x = -70; x <= -64; x++) for (int z = -16; z <= -10; z++) {
            if (x == -70 || x == -64 || z == -16 || z == -10) w.fill(x, x, 58, 61, z, z, "cobblestone");   // walls + roof rim
        }
        return w;
    }

}
