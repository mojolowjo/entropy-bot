package io.github.mojolowjo.entropybot.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.ai.provider.ChatCompletionsProvider;
import io.github.mojolowjo.entropybot.ai.provider.HttpTransport;
import io.github.mojolowjo.entropybot.ai.provider.Provider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 0.35.2: reasoning in the request, "ran out while thinking", a chain's end wakes the AI. */
class Fixes0352Test {
    static final Provider.Request REQ = new Provider.Request("s", List.of(new Provider.Message("user", "hi")), 400, 0.2, Duration.ofSeconds(5));

    JsonObject body(String kind, String base, String reasoning) throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpTransport t = (u, h, b, to) -> {
            bodies.add(b);
            return new HttpTransport.Response(200, "{\"choices\":[{\"message\":{\"content\":\"status\"}}]}", Map.of());
        };
        new ChatCompletionsProvider(kind, base, "m:free", Secret.of("sk-test-0000-abcd"), t, ms -> {}).reasoning(reasoning).complete(REQ);
        return JsonParser.parseString(bodies.get(0)).getAsJsonObject();
    }

    @Test
    void reasoningTheOpenRouterWay() throws Exception {
        assertFalse(body("openrouter", "", "off").getAsJsonObject("reasoning").get("enabled").getAsBoolean());
        assertEquals("low", body("openrouter", "", "low").getAsJsonObject("reasoning").get("effort").getAsString());
        assertTrue(body("openrouter", "", "on").getAsJsonObject("reasoning").get("enabled").getAsBoolean());
        assertEquals("low", body("openai", "https://api.example.com/v1", "low").get("reasoning_effort").getAsString());
        assertFalse(body("openai", "https://api.example.com/v1", "off").has("reasoning_effort"));
        JsonObject local = body("local", "", "low");
        assertFalse(local.has("reasoning") || local.has("reasoning_effort"), "dropped for local");
        AiSettings s = new AiSettings();
        assertEquals("off", s.reasoning);
        assertEquals(400, s.maxOutputTokens);
        s.reasoning = "max";
        assertFalse(s.problems().isEmpty());
    }

    AiLoop loop(AiSettings s) {
        CommandGate gate = new CommandGate(List.of(), List.of());
        AiLoop l = new AiLoop(s, gate, new PromptBuilder(null, gate, false, "b", "o"), new Budget(s, new UsageLedger(null, ZoneId.of("UTC"))), new AiLoop.Sink() {
            public void notice(String t) {}
            public void log(long n, String x) {}
            public void trial(long n, String w, String c, String a) {}
        });
        l.enable(0);
        return l;
    }

    @Test
    void emptyBecauseOfLengthSaysSo() {
        AiLoop l = loop(new AiSettings());
        l.ownerRequest("x", 0);
        l.poll(0, new AiLoop.World(true, false, false));
        l.onCompletion(new Provider.Completion("", 100, 400, null, "length", 10), 0);
        assertTrue(l.lastError().contains("ran out of tokens while thinking"), l.lastError());
    }

    @Test
    void aChainEndWakesTheAiWithItsText() {
        AiLoop l = loop(new AiSettings());
        AiLoop.World busy = new AiLoop.World(true, true, false), idle = new AiLoop.World(true, false, false);
        l.ownerRequest("cut 3 logs", 0);
        l.poll(0, idle);
        assertEquals("aa want oak_log 3 then aa drop oak_log", l.onCompletion(new Provider.Completion("aa want oak_log 3 then aa drop oak_log", 1, 1, null, "stop", 1), 0));
        l.onCommandResult("aa want oak_log 3 then aa drop oak_log", "started: chain", false, 0);   // a chain answers at once, no pending
        assertNotNull(l.poll(2000, busy));
        assertNull(l.onCompletion(new Provider.Completion("wait", 1, 1, null, "stop", 1), 2000));
        l.onJobEnd("chain: stopped at step 1 (cut 3 logs): I chop only inside my areas", 3000);
        Provider.Request r = l.poll(5000, idle);
        assertNotNull(r, "the end wakes it");
        assertTrue(r.messages().get(r.messages().size() - 1).content().contains("stopped at step 1"));
    }
}
