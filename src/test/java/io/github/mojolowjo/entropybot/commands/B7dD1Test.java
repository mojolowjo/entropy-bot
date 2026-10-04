package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.commands.BridgeLink.Request;
import io.github.mojolowjo.entropybot.memory.Knowledge;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B7d D1: the honest end of a bridge job that hands over to a trip of its own (TO-LOOK-AT-LATER 18b), and the ores
 * listed for players in the knowledge files.
 */
class B7dD1Test {
    static JsonObject job(String type, String status, boolean done, Long req) {
        return JsonParser.parseString("{\"job\":{\"type\":\"" + type + "\",\"status\":\"" + status + "\",\"done\":" + done
                + (req != null ? ",\"req\":" + req : ",\"req\":null") + "}}").getAsJsonObject();
    }

    /**
     * The owner's dig of 2026-10-03: the bridge's clear ran out of pickaxes and handed over to a craft job (job.resume),
     * which the bridge reported without the request. The mod said "lost track of the job (...)" two seconds later and
     * never heard the clear's own end. Now it waits for it.
     */
    @Test
    void aTripOfTheJobIsNotLostTrackOf() {
        BridgeLink b = new BridgeLink();
        CommandsTest.Seen s = new CommandsTest.Seen();
        b.report(new JsonObject(), 0);
        Request r = b.submit("pm", "o", "dig 0 38 112 15 54 127", false, null, s, 0);
        b.next(0);
        b.reply(r.id, "started: digging 0 38 112 to 15 54 127, top down, one block at a time", true);
        b.report(job("clear", "digging ...: breaking stone at 3 45 114 (261 broken, ~4019 left)", false, r.id), 20);
        // the craft trip: an older bridge reports it with no request
        for (int i = 0; i < 300; i++) b.report(job("craft", "crafting 4 oak_planks -> 6 stick -> 3 stone_pickaxe", false, null), 40 + 20L * i);
        assertFalse(r.finished, "still waiting: " + s.log);
        // the clear's real end comes through bridgeDone
        b.done(r.id, "stopped: out of pickaxes and making more failed (could not open the crafting table: error: too far (74.3 blocks), walk closer first) - broke 262 blocks; 4018 left");
        assertTrue(r.finished);
        assertTrue(r.doneMsg.startsWith("stopped: out of pickaxes and making more failed (could not open the crafting table"), r.doneMsg);
        assertFalse(s.log.stream().anyMatch(l -> l.contains("lost track")), s.log.toString());
    }

    @Test
    void anotherRequestsJobOrNoJobStillLetsGoQuickly() {
        BridgeLink b = new BridgeLink();
        CommandsTest.Seen s = new CommandsTest.Seen();
        b.report(new JsonObject(), 0);
        Request r = b.submit("pm", "o", "farm", false, null, s, 0);
        b.next(0);
        b.reply(r.id, "started: farming", true);
        b.report(job("seq", "farming", false, r.id), 20);
        b.report(job("seq", "farming", true, null), 40);
        b.report(job("seq", "farming", true, null), 60);
        assertTrue(r.finished && r.doneMsg.startsWith("stopped: lost track of the job (farming)"), s.log.toString());
        // a trip with no request still ends after the patience runs out (a bridge that never reports the end)
        Request q = b.submit("pm", "o", "farm", false, null, s, 100);
        b.next(100);
        b.reply(q.id, "started: farming", true);
        for (int i = 0; i < BridgeLink.INNER_PATIENCE; i++) b.report(job("craft", "x", false, null), 120 + i);
        assertTrue(q.finished && q.doneMsg.startsWith("stopped: lost track of the job"));
    }

    @Test
    void theOreTally() {
        io.github.mojolowjo.entropybot.clear.OreTally.add(java.util.Map.of("minecraft:iron_ore", 2));
        var start = io.github.mojolowjo.entropybot.clear.OreTally.copy();
        io.github.mojolowjo.entropybot.clear.OreTally.add(java.util.Map.of("minecraft:iron_ore", 3, "minecraft:coal_ore", 1));
        assertEquals(3, io.github.mojolowjo.entropybot.clear.OreTally.since(start, id -> id.contains("iron")));
        assertEquals(4, io.github.mojolowjo.entropybot.clear.OreTally.since(start, id -> true));
    }
}
