package io.github.mojolowjo.entropybot.brain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 0.25.1: the brain's share of the owner's played tactics (bag trip, eat before a cave, food before wood at dusk). */
class PlayRulesTest {
    static BrainState state() {
        BrainState s = new BrainState();
        s.basePos = new int[]{0, 64, 0};
        s.armourTier = 3;
        s.weaponTier = 3;
        return s;
    }

    @Test
    void settingsTakeOnOffAndRanges() {
        BrainConfig c = BrainConfig.defaults();
        assertEquals(8, c.i("threat.coverRange"));
        assertEquals(4, c.i("threat.coverCloseAt"));
        assertEquals(15, c.i("threat.coverWait"));
        assertEquals(4, c.i("brain.bagFullSlots"));
        assertEquals(8, c.i("path.oreDetour"));
        assertEquals(8, c.i("brain.foodStock"));
        assertTrue(c.on("threat.highGround") && c.on("cave.eatFirst") && c.on("gear.ironFirst"));
        assertNull(c.set("threat.highGround", "off"));
        assertFalse(c.on("threat.highGround"));
        assertNull(c.set("cave.eatFirst", "ON"));
        assertTrue(c.on("cave.eatFirst"));
        assertNotNull(c.set("threat.highGround", "2"), "0 or 1 only");
        assertNotNull(c.set("path.oreDetour", "40"));
    }

    @Test
    void bagFullIsTheTripHomeAndBedAtNightInTheShelterBand() {
        BrainConfig c = BrainConfig.defaults();
        BrainState s = state();
        s.freeSlots = 5;
        assertFalse(PlayRules.bagFull(s, c));
        s.freeSlots = 4;
        assertTrue(PlayRules.bagFull(s, c));
        assertEquals("go base then deposit then smelt collect", PlayRules.bagChain(s, c));
        s.night = true;
        s.othersSleeping = false;
        assertEquals("go base then deposit then smelt collect", PlayRules.bagChain(s, c), "night, normal band, nobody sleeping: no bed");
        s.armourTier = 0;
        s.weaponTier = 0;            // the shelter band
        assertEquals("go base then deposit then smelt collect then sleep", PlayRules.bagChain(s, c));
        s.basePos = null;
        assertEquals("deposit then sleep", PlayRules.bagChain(s, c), "no base place: deposit finds the chests");
        assertTrue(PlayRules.bagWhy(s, c).startsWith("my bag is full (4 free, brain.bagFullSlots 4)"));
    }

    @Test
    void aFullBagInterruptsTheJobOnceNotEveryLoop() {
        BrainConfig c = BrainConfig.defaults();
        BrainState s = state();
        s.freeSlots = 3;
        String[] m = Interrupts.midJob(s, c);
        assertNotNull(m);
        assertEquals("FULL", m[0]);
        assertEquals("go base then deposit then smelt collect", m[1]);
        s.brainJob = "go base then deposit then smelt collect then mine strip";
        assertNull(Interrupts.midJob(s, c), "the trip runs: no second interrupt");
        BrainTree.Ctx ctx = new BrainTree.Ctx(state(), c, Needs.score(state(), c, List.of(), Map.of()), new BrainTree.Running("upkeep", "mine strip", 15), false);
        ctx.s().freeSlots = 2;
        BrainTree.Decision d = BrainTree.interrupt(ctx);
        assertEquals("go base then deposit then smelt collect then mine strip", d.chain());
    }

    @Test
    void theBagNeedUsesTheTrip() {
        BrainConfig c = BrainConfig.defaults();
        BrainState s = state();
        s.freeSlots = 2;
        Needs.Scored sc = Needs.score(s, c, List.of(), Map.of());
        Needs.Option bag = sc.options().stream().filter(o -> o.need().equals("bag")).findFirst().orElseThrow();
        assertEquals("go base then deposit then smelt collect", bag.chain());
        s.freeSlots = 7;
        bag = Needs.score(s, c, List.of(), Map.of()).options().stream().filter(o -> o.need().equals("bag")).findFirst().orElseThrow();
        assertEquals("deposit", bag.chain(), "some room left: a plain deposit as before");
    }

    @Test
    void eatsBeforeACaveTripWithFoodOnItAndTheBarNotFull() {
        BrainConfig c = BrainConfig.defaults();
        BrainState s = state();
        s.food = 16;
        s.foodItems = 5;
        assertTrue(PlayRules.eatFirst("mine cave any 20 10m", s, c).startsWith("eating first (food 16/20"));
        assertNull(PlayRules.eatFirst("mine strip", s, c));
        assertNull(PlayRules.eatFirst("mine cave status", s, c));
        assertNull(PlayRules.eatFirst("deposit then mine cave", s, c), "only a chain that starts with the trip");
        s.food = 20;
        assertNull(PlayRules.eatFirst("mine cave", s, c), "full bar");
        s.food = 12;
        s.foodItems = 0;
        assertNull(PlayRules.eatFirst("mine cave", s, c), "nothing to eat (the supply check gets food)");
        s.foodItems = 3;
        c.set("cave.eatFirst", 0);
        assertNull(PlayRules.eatFirst("mine cave", s, c), "cave.eatFirst off");
    }

    @Test
    void nearDuskWithWoodEnoughFoodBeatsWood() {
        BrainConfig c = BrainConfig.defaults();
        BrainState s = state();
        s.dayTime = 11500;              // dusk at 13000: 1.5 h
        s.woodItems = 20;
        s.foodItems = 6;
        s.foodStock = 6;
        s.needs.add(new BrainState.NeedItem("minecraft:oak_log", 64, 20, 0));
        Needs.Scored sc = Needs.score(s, c, List.of(), Map.of());
        Needs.Option best = sc.best(c.i("floor"));
        assertEquals("food", best.need(), sc.line());
        assertTrue(best.score() > sc.scoreOf("need:oak_log"), sc.line());
        assertEquals("get food 2", best.chain());
        assertTrue(best.reason().contains("food before wood"), best.reason());
        // midday: the wood need wins as before
        s.dayTime = 3000;
        assertEquals("need:oak_log", Needs.score(s, c, List.of(), Map.of()).best(c.i("floor")).need());
        // dusk but food in stock
        s.dayTime = 11500;
        s.foodStock = 30;
        assertNull(PlayRules.duskFood(s, c, Needs.score(s, c, List.of(), Map.of()).options()));
        // dusk, food low, but too little wood
        s.foodStock = 2;
        s.woodItems = 5;
        assertNull(PlayRules.duskFood(s, c, List.of()));
    }
}
