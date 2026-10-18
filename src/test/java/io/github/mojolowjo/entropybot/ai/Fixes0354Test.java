package io.github.mojolowjo.entropybot.ai;

import io.github.mojolowjo.entropybot.ai.provider.ChatCompletionsProvider;
import io.github.mojolowjo.entropybot.ai.provider.HttpTransport;
import io.github.mojolowjo.entropybot.ai.provider.Provider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 0.35.4: the review of 0.35.2/0.35.3. */
class Fixes0354Test {
    @Test
    void goalMetMatchesKindsAndExactIds() {
        assertTrue(GoalMet.matches("oak_log", "log"));
        assertTrue(GoalMet.matches("crimson_stem", "log"));
        assertFalse(GoalMet.matches("stripped_oak_log", "log"));
        assertFalse(GoalMet.matches("iron_pickaxe", "iron"));
        assertFalse(GoalMet.matches("stone_bricks", "stone"));
        assertTrue(GoalMet.matches("cobblestone", "cobblestone"));
        assertTrue(GoalMet.matches("iron_ore", "ore"));
        assertNull(GoalMet.hint("cut 3 logs", null, Map.of("oak_log", 9)), "no start, no hint");
        assertNotNull(GoalMet.hint("cut 3 logs", Map.of(), Map.of("oak_log", 3)));
        assertNull(GoalMet.hint("mine 3 iron", Map.of(), Map.of("iron_pickaxe", 3)));
    }

    @Test
    void reasoningDroppedOnceAfterA400() throws Exception {
        List<String> bodies = new ArrayList<>(), notes = new ArrayList<>();
        HttpTransport t = (u, h, b, to) -> {
            bodies.add(b);
            return b.contains("\"reasoning\"") ? new HttpTransport.Response(400, "{\"error\":{\"message\":\"Reasoning is mandatory for this endpoint\"}}", Map.of())
                    : new HttpTransport.Response(200, "{\"choices\":[{\"message\":{\"content\":\"status\"}}]}", Map.of());
        };
        int[] posts = {0};
        ChatCompletionsProvider p = new ChatCompletionsProvider("openrouter", "", "test/must-reason:free", Secret.of("sk-test-0000-abcd"), t, ms -> {})
                .onNote(notes::add).onRetry(() -> posts[0]++);
        Provider.Request r = new Provider.Request("s", List.of(), 10, 0, Duration.ofSeconds(1));
        assertEquals("status", p.complete(r).text());
        assertEquals(2, bodies.size());
        assertEquals(1, posts[0], "the second post counts");
        assertEquals(1, notes.size());
        p.complete(r);
        assertEquals(3, bodies.size(), "remembered: no reasoning field the next time");
        HttpTransport other = (u, h, b, to) -> new HttpTransport.Response(400, "{\"error\":{\"message\":\"bad temperature\"}}", Map.of());
        assertThrows(Exception.class, () -> new ChatCompletionsProvider("openai", "https://api.example.com/v1", "m", Secret.of("sk-test-0000-abcd"), other, ms -> {})
                .reasoning("low").complete(r), "another 400 is not retried");
    }

    @Test
    void sayIsOnlyForExplicitTalkWords() throws Exception {
        java.util.regex.Pattern p = (java.util.regex.Pattern) getField("ASKS_TO_SAY");
        for (String g : List.of("tell Steve hello", "say hi to everyone", "chat with the players", "greet alex"))
            assertTrue(p.matcher(g).find(), g);
        for (String g : List.of("cut 3 logs", "tell me what you have", "answer the question", "ask for wood", "reply when done"))
            assertFalse(p.matcher(g).find(), g);
    }

    private static Object getField(String n) throws Exception {
        var f = AiLoop.class.getDeclaredField(n);
        f.setAccessible(true);
        return f.get(null);
    }
}
