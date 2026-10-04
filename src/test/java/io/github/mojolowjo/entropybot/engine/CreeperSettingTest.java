package io.github.mojolowjo.entropybot.engine;

import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.engine.CreeperRules.Mode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CreeperSettingTest {
    @Test
    void setsSavesAndApplies() {
        JsonObject brain = new JsonObject();
        AtomicInteger saves = new AtomicInteger();
        AtomicReference<Mode> live = new AtomicReference<>(Mode.MELEE);
        assertTrue(CreeperSetting.command("", brain, saves::incrementAndGet, live::get, live::set).startsWith("creepers: melee"));
        assertEquals(0, saves.get());
        String r = CreeperSetting.command(" Bow ", brain, saves::incrementAndGet, live::get, live::set);
        assertTrue(r.startsWith("ok: creepers: bow"), r);
        assertEquals(Mode.BOW, live.get());
        assertEquals("bow", brain.get("creepers").getAsString());
        assertEquals(1, saves.get());
        assertEquals(Mode.BOW, CreeperSetting.load(brain));
        CreeperSetting.command("flee", brain, saves::incrementAndGet, live::get, live::set);
        assertEquals(Mode.FLEE, CreeperSetting.load(brain));
    }

    @Test
    void refusesOtherWordsAndDefaultsToMelee() {
        JsonObject brain = new JsonObject();
        AtomicReference<Mode> live = new AtomicReference<>(Mode.MELEE);
        assertTrue(CreeperSetting.command("sword", brain, () -> fail("saved"), live::get, live::set).startsWith("error:"));
        assertFalse(brain.has("creepers"));
        assertEquals(Mode.MELEE, CreeperSetting.load(brain));
        assertEquals(Mode.MELEE, CreeperSetting.load(null));
        brain.add("creepers", new JsonObject());
        assertEquals(Mode.MELEE, CreeperSetting.load(brain));
    }
}
