package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.cave.OreSpec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The strip order (owner 2026-10-22): the three ore forms, the bare Y and heading, "any" by tier, the fork's end texts. */
class StripOrderTest {

    @Test
    void parsesTheForms() {
        StripOrder.Args one = StripOrder.parse("strip coal_ore 16 east");
        assertEquals(List.of("coal_ore"), one.ores());
        assertEquals(Map.of("coal_ore", 16), one.amounts(), "a number right after an ore is its amount");
        assertNull(one.y());
        assertEquals("east", one.heading());

        StripOrder.Args amtY = StripOrder.parse("strip coal_ore 32 16");
        assertEquals(Map.of("coal_ore", 32), amtY.amounts());
        assertEquals(16, amtY.y(), "the first number after the list is the Y");

        StripOrder.Args two = StripOrder.parse("strip iron_ore 64,coal_ore 16 east");
        assertEquals(List.of("iron_ore", "coal_ore"), two.ores());
        assertEquals(Map.of("iron_ore", 64, "coal_ore", 16), two.amounts());
        assertNull(two.y());
        assertEquals("east", two.heading());

        StripOrder.Args mixed = StripOrder.parse("strip iron_ore,gold_ore 5 -20 north");
        assertEquals(Map.of("gold_ore", 5), mixed.amounts());
        assertEquals(-20, mixed.y());

        StripOrder.Args plain = StripOrder.parse("strip iron_ore,gold_ore");
        assertTrue(plain.amounts().isEmpty());
        assertNull(plain.y());
        assertNull(plain.heading());

        StripOrder.Args any = StripOrder.parse("strip any 40");
        assertTrue(any.any());
        assertEquals(40, any.y(), "any takes no amount: the number is the Y");
        assertTrue(any.amounts().isEmpty());

        StripOrder.Args swapped = StripOrder.parse("strip any west -12");
        assertEquals("west", swapped.heading());
        assertEquals(-12, swapped.y());

        assertNotNull(StripOrder.parse("strip").err());
        assertNotNull(StripOrder.parse("strip 16").err());
        assertNotNull(StripOrder.parse("strip coal_ore y 16").err(), "no y keyword");
        assertNotNull(StripOrder.parse("strip coal_ore 1 2 3").err());
        assertNotNull(StripOrder.parse("strip iron_ore 5 east,coal_ore").err(), "heading inside the list");
        assertNotNull(StripOrder.parse("strip any,coal_ore").err());
        assertTrue(StripOrder.is("strip any"));
        assertFalse(StripOrder.is("stripes"));
    }

    @Test
    void endRules() {
        Map<String, Integer> amounts = Map.of("coal", 32);
        assertNull(StripOrder.endRule(amounts, Map.of("coal", 10), false));
        assertEquals(Car.State.DONE, StripOrder.endRule(amounts, Map.of("coal", 32), false).state());
        // the deposit leg: a full bag short of an amount ends the strip JOB done; the order is not met (deposit, back, resume)
        Car.Status short1 = StripOrder.endRule(amounts, Map.of("coal", 10), true);
        assertEquals(Car.State.DONE, short1.state(), "bag full short of an amount");
        assertTrue(short1.why().contains("bag full"));
        assertFalse(StripOrder.orderMet(short1.why()), "the order goes on");
        assertTrue(StripOrder.orderMet(StripOrder.endRule(amounts, Map.of("coal", 32), false).why()));
        assertEquals(Car.State.DONE, StripOrder.endOf("bag full", "strip east feet y -40: 3 blocks").state());
        assertFalse(StripOrder.orderMet(StripOrder.endOf("bag full", "s").why()));
        assertNull(StripOrder.endRule(Map.of(), Map.of("coal", 99), false), "no amount: until the bag is full");
        Car.Status full = StripOrder.endRule(Map.of(), Map.of(), true);
        assertEquals(Car.State.DONE, full.state());
        assertTrue(full.why().contains("bag full"));
        assertFalse(StripOrder.orderMet(full.why()), "no amounts: until the owner stops");
        // mixed: only the ores with an amount decide; the others are picked up as they come
        assertEquals(Car.State.DONE, StripOrder.endRule(Map.of("iron", 4), Map.of("iron", 4, "coal", 0), false).state());
        assertNull(StripOrder.endRule(Map.of("iron", 4, "gold", 2), Map.of("iron", 9), false));
        assertEquals("coal", StripOrder.countKey("deepslate_coal_ore", OreSpec::family));
        assertEquals("nickel", StripOrder.countKey("oritech:nickel_ore", OreSpec::family));
    }

    @Test
    void resumeFromSavedProgress() {
        var a = new io.github.mojolowjo.entropybot.aa.moves.Step.Strip(List.of("minecraft:coal_ore", "minecraft:deepslate_coal_ore"), Map.of("coal", 32), 16, "east", 1);
        var b = new io.github.mojolowjo.entropybot.aa.moves.Step.Strip(List.of("minecraft:deepslate_coal_ore", "minecraft:coal_ore"), Map.of(), 16, "east", 2);
        assertEquals(a.key(), b.key(), "the same order (ore set, Y, heading) finds the same saved progress");
        assertNotEquals(a.key(), new io.github.mojolowjo.entropybot.aa.moves.Step.Strip(a.ores(), Map.of(), 16, "west", 3).key());
        var saved = new StripOrder.Saved(true, 10, 16, -4, "east", 57, 12, 3, 40, 2, Map.of("coal", 18));
        var json = new com.google.gson.Gson().toJson(saved);
        assertEquals(saved, new com.google.gson.Gson().fromJson(json, StripOrder.Saved.class), "the saved record survives the file");
        assertEquals("strip east feet y 16: 40 blocks, coal 18/32", StripOrder.goalLine("east", 16, Map.of("coal", 32), saved));
        assertEquals("strip east feet y 16: not started, coal 0/32", StripOrder.goalLine("east", 16, Map.of("coal", 32), null));
    }

    /** The loop: strip -> deposit -> back, jobs of one order; resume after a deposit from the same saved progress. */
    @Test
    void loopOrderAndResumeAfterADeposit() {
        var s = new io.github.mojolowjo.entropybot.aa.moves.Step.Strip(List.of("minecraft:coal_ore"), Map.of("coal", 64), -40, "east", 1);
        var loop = Goals.stripLoop(s);
        assertEquals(3, loop.size());
        assertSame(s, loop.get(0));
        assertEquals(new io.github.mojolowjo.entropybot.aa.moves.Step.Deposit(s), loop.get(1));
        assertEquals(new io.github.mojolowjo.entropybot.aa.moves.Step.StripBack(s), loop.get(2));
        var d = DepositRule.Deposits.NONE.from(new int[]{130, -40, 5}).add(36, new int[]{10, -40, 5});
        var json = new com.google.gson.Gson().toJson(d);
        var back = new com.google.gson.Gson().fromJson(json, DepositRule.Deposits.class);
        assertArrayEquals(new int[]{130, -40, 5}, back.back(), "the walk back's target survives a relaunch");
        assertEquals("1 deposit (36 items at 10 -40 5)", back.line());
        var saved = new StripOrder.Saved(true, 10, -40, 5, "east", 140, 120, 6, 120, 9, Map.of("coal", 40));
        assertEquals("strip east feet y -40: 120 blocks, coal 40/64, 1 deposit (36 items at 10 -40 5)",
                StripOrder.goalLine("east", -40, Map.of("coal", 64), saved, back));
        // a relaunch: the same words make the same key, so the same progress and deposits are found
        var again = new io.github.mojolowjo.entropybot.aa.moves.Step.Strip(List.of("minecraft:coal_ore"), Map.of("coal", 64), -40, "east", 99);
        assertEquals(s.key(), again.key());
    }
    @Test
    void anyResolvesByTier() {
        Map<String, Integer> t = new LinkedHashMap<>();
        t.put("minecraft:coal_ore", 1);
        t.put("minecraft:iron_ore", 2);
        t.put("minecraft:diamond_ore", 3);
        assertEquals(List.of("minecraft:coal_ore", "minecraft:iron_ore"), StripOrder.anyOres(t, 2));
        assertEquals(List.of("minecraft:coal_ore"), StripOrder.anyOres(t, 1));
        assertEquals(3, StripOrder.anyOres(t, 4).size());
    }

    @Test
    void namedTakesTheFamily() {
        List<String> ores = List.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore", "minecraft:coal_ore");
        assertEquals(List.of("minecraft:deepslate_iron_ore", "minecraft:iron_ore"), StripOrder.namedOres(List.of("iron_ore"), ores, OreSpec::family));
        assertEquals(List.of("minecraft:deepslate_iron_ore", "minecraft:iron_ore"), StripOrder.namedOres(List.of("iron"), ores, OreSpec::family));
        assertTrue(StripOrder.namedOres(List.of("tin"), ores, OreSpec::family).get(0).startsWith("error:"));
        Map<String, Thing.Gen> gen = Map.of("minecraft:coal_ore", new Thing.Gen(java.util.Set.of("minecraft:overworld"), 0, 192));
        assertEquals(96, StripOrder.bandCentre(List.of("minecraft:coal_ore"), gen));
        assertNull(StripOrder.bandCentre(List.of("minecraft:iron_ore"), gen));
    }

    @Test
    void mapsTheForkEnds() {
        String st = "strip east at y 40: 23 blocks, 2 clusters mined (coal 9)";
        assertEquals(Car.State.DONE, StripOrder.endOf("done: " + st, st).state());
        for (String f : new String[]{"lava ahead at 1 2 3", "water ahead at 1 2 3", "no path", "no pickaxe left", "stopped"}) {
            Car.Status s = StripOrder.endOf(f, st);
            assertEquals(Car.State.FAILED, s.state(), f);
            assertTrue(s.why().contains(f), f);
        }
        assertTrue(StripOrder.endOf("lost control", st).lostControl());
        assertEquals(Car.State.FAILED, StripOrder.endOf("", st).state(), "no silent end");
    }
}
