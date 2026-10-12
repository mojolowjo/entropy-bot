package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 0.28.0 live (LAN flat world): ore at the bot's foot level on flat ground, two blocks away - a stand cell exists. */
class FootLevelOreTest {
    @Test
    void oreAtFootLevelHasASpot() {
        FakeWorld w = new FakeWorld();             // floor: stone up to y 52; the bot's feet at 53
        w.set(49, 53, 23, "iron_ore");
        w.set(49, 54, 23, "iron_ore");
        Bot bot = Bot.at(51.5, 53, 26.5);
        ClearJob j = ClearJob.start(new ClearJob.Options().only(List.of(new Pos(49, 53, 23), new Pos(49, 54, 23))).collect(true), null, new OreBook.Simple());
        ClearGrid.Plan p = ClearGrid.planWalk(w, bot, j);
        assertNotNull(p, "no plan; nowhere to stand: " + j.noSpot);
    }

    @Test
    void oreAtFootLevelInABoxHasASpot() {
        FakeWorld w = new FakeWorld();
        w.set(49, 53, 23, "iron_ore");
        w.set(49, 54, 23, "iron_ore");
        Bot bot = Bot.at(51.5, 53, 26.5);
        ClearJob j = ClearJob.start(new ClearJob.Options().box(ClearBox.of(49, 53, 23, 49, 54, 23)).collect(true), null, new OreBook.Simple());
        ClearGrid.Plan p = ClearGrid.planWalk(w, bot, j);
        assertNotNull(p, "no plan; nowhere to stand: " + j.noSpot);
    }
}
