package io.github.mojolowjo.entropybot.aa;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Node;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The dashboard's live AA tree: the JSON follows the built tree, lights the running path, ranks the desires. */
class AaLiveTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());
    final AaTree tree = AaTree.build(new AaScorer());

    static void ids(JsonObject n, List<String> out) {
        out.add(n.get("id").getAsString());
        if (n.has("kids")) for (JsonElement k : n.getAsJsonArray("kids")) ids(k.getAsJsonObject(), out);
    }

    @Test
    void theTreeIsTheBuiltTreeNodeForNode() {
        JsonObject o = AaLive.json(tree, bb, false, AaLive.SWITCHES);
        List<String> got = new ArrayList<>(), want = new ArrayList<>();
        ids(o.getAsJsonObject("tree"), got);
        for (Node n : tree.nodes()) want.add(n.id);
        assertEquals(want, got);
        assertEquals("root", o.getAsJsonArray("path").get(0).getAsString());
        assertEquals(0, o.getAsJsonArray("desires").size());          // nothing scored before the first tick
        // a valid JSON document end to end
        assertEquals(o, JsonParser.parseString(o.toString()));
    }

    @Test
    void aRunningTickLightsThePathAndRanksTheDesires() {
        bb.in.areaKnown = false;                                       // a fresh world: the area rung runs
        assertEquals(Status.RUNNING, tree.tick(bb));
        JsonObject o = AaLive.json(tree, bb, true, 5);
        JsonArray path = o.getAsJsonArray("path");
        List<String> p = new ArrayList<>();
        for (JsonElement e : path) p.add(e.getAsString());
        assertEquals(List.of("root", "slot"), p.subList(0, 2));
        assertTrue(p.contains("needs.area"), p.toString());
        assertEquals("needs.area", o.get("running").getAsString());
        JsonArray ds = o.getAsJsonArray("desires");
        assertEquals(tree.brain.desires().size(), ds.size());
        double prev = Double.MAX_VALUE;
        boolean sawRunning = false;
        for (JsonElement e : ds) {
            JsonObject d = e.getAsJsonObject();
            assertTrue(d.has("score") && d.has("parts") && d.has("skip") && d.has("kind"));
            assertTrue(d.get("rank").getAsDouble() <= prev);
            prev = d.get("rank").getAsDouble();
            sawRunning |= d.get("running").getAsBoolean();
        }
        assertTrue(sawRunning);
        // the task started: one decision line in the switches
        assertEquals(1, o.getAsJsonArray("switches").size());
        assertTrue(o.getAsJsonArray("switches").get(0).getAsJsonObject().has("chain"));
        assertEquals("RUNNING", find(o.getAsJsonObject("tree"), "needs.area").get("s").getAsString());
    }

    @Test
    void switchesKeepOnlyTheLastN() {
        for (int i = 0; i < 8; i++) bb.tracer.decision(clock.now() + i, "t", null, "c" + i, "r");
        JsonArray sw = AaLive.json(tree, bb, true, 3).getAsJsonArray("switches");
        assertEquals(3, sw.size());
        assertEquals("c7", sw.get(2).getAsJsonObject().get("chain").getAsString());
    }

    static JsonObject find(JsonObject n, String id) {
        if (n.get("id").getAsString().equals(id)) return n;
        if (n.has("kids")) for (JsonElement k : n.getAsJsonArray("kids")) {
            JsonObject f = find(k.getAsJsonObject(), id);
            if (f != null) return f;
        }
        return null;
    }
}
