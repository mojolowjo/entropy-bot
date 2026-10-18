package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 0.36.a3: a waterOk clear (the known-ore vein clear) breaks ore touching water, never ore touching lava. */
class ClearWaterOkTest {
    @Test
    void liquidForbids() {
        FakeWorld w = new FakeWorld();
        w.set(0, 52, 0, "iron_ore");
        w.set(1, 52, 0, "water");
        ClearJob plain = ClearJob.start(new ClearJob.Options().only(List.of(new Pos(0, 52, 0))), null, null);
        ClearJob wet = ClearJob.start(new ClearJob.Options().only(List.of(new Pos(0, 52, 0))).waterOk(true), null, null);
        assertTrue(ClearEngine.liquidForbids(w, plain, 0, 52, 0));
        assertFalse(ClearEngine.liquidForbids(w, wet, 0, 52, 0));
        assertTrue(ClearEngine.liquidForbids(w, null, 0, 52, 0));
        w.set(0, 53, 0, "lava");
        assertTrue(ClearEngine.liquidForbids(w, wet, 0, 52, 0));   // lava never
    }
}
