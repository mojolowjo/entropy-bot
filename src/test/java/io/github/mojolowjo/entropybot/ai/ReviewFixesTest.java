package io.github.mojolowjo.entropybot.ai;

import io.github.mojolowjo.entropybot.ai.provider.ChatCompletionsProvider;
import io.github.mojolowjo.entropybot.ai.provider.HttpTransport;
import io.github.mojolowjo.entropybot.ai.provider.Provider;
import io.github.mojolowjo.entropybot.ai.provider.ProviderException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 0.35.1: the Sonnet review's findings 1-8 and the live finding (a). */
class ReviewFixesTest {
    @TempDir
    Path dir;

    @Test
    void loopbackIsAnIpLiteralOnly() {
        assertFalse(ChatCompletionsProvider.isLoopback("http://127.evil.com/v1"));
        assertFalse(ChatCompletionsProvider.isLoopback("http://127.0.0.1.evil.com/v1"));
        assertNotNull(ChatCompletionsProvider.urlProblem("http://127.evil.com/v1"));
        assertTrue(ChatCompletionsProvider.isLoopback("http://127.0.0.1:11434/v1"));
        assertTrue(ChatCompletionsProvider.isLoopback("http://localhost:1234/v1"));
        assertTrue(ChatCompletionsProvider.isLoopback("http://[::1]:1234/v1"));
    }

    @Test
    void localWithARemoteUrlIsNotFree() {
        AiSettings s = new AiSettings();
        s.provider = "local";
        s.model = "m";
        s.baseUrl = "https://api.example.com/v1";
        assertFalse(Budget.freeService(s));
        assertNotNull(new Budget(s, new UsageLedger(null, ZoneId.of("UTC"))).startProblem());
        s.baseUrl = "";
        assertTrue(Budget.freeService(s));
        s.baseUrl = "http://127.evil.com/v1";
        assertFalse(Budget.freeService(s));
    }

    @Test
    void queuedLinesAreGatedToo() {
        CommandGate g = new CommandGate(List.of(), List.of());
        for (String v : List.of("queue give bob diamond", "queue restart", "queue server on", "queue add allow bob", "queue status then confirm"))
            assertEquals(CommandGate.Kind.DENIED, g.check(v).kind(), v);
        assertEquals(CommandGate.Kind.RUN, g.check("queue").kind());
        assertEquals(CommandGate.Kind.RUN, g.check("queue mine iron").kind());
    }

    @Test
    void corruptLedgerFailsClosed() throws Exception {
        Path f = dir.resolve("ai-usage.json");
        Files.writeString(f, "{broken");
        UsageLedger l = new UsageLedger(f, ZoneId.of("UTC"));
        assertNotNull(l.corrupt());
        Budget.Cap c = new Budget(new AiSettings(), l).refuse(0, 10);
        assertNotNull(c);
        assertTrue(c.reason().contains("delete"), c.reason());
        l.record(0, 5, 0);
        assertEquals("{broken", Files.readString(f), "the file is left for the owner");
    }

    @Test
    void failuresAndRetriesAreCounted() throws Exception {
        UsageLedger l = new UsageLedger(null, ZoneId.of("UTC"));
        AiSettings s = new AiSettings();
        CommandGate gate = new CommandGate(List.of(), List.of());
        AiLoop loop = new AiLoop(s, gate, new PromptBuilder(null, gate, false, "b", "o"), new Budget(s, l), new AiLoop.Sink() {
            public void notice(String t) {}
            public void log(long n, String x) {}
            public void trial(long n, String w, String c, String a) {}
        });
        loop.enable(0);
        loop.ownerRequest("x", 0);
        assertNotNull(loop.poll(0, new AiLoop.World(true, false, false)));
        assertEquals(1, l.hourRequests(0));
        long est = l.dayTokens(0);
        assertTrue(est > 0, "the estimate is counted when sent");
        loop.onFailure(new ProviderException(ProviderException.Kind.TIMEOUT, 0, "t"), 0);
        assertEquals(est, l.dayTokens(0), "a timeout still costs");
        // retries: each post is a request
        HttpTransport fail = (u, h, b, t) -> new HttpTransport.Response(503, "", Map.of());
        ChatCompletionsProvider p = new ChatCompletionsProvider("openrouter", "", "m:free", Secret.of("sk-test-0000-abcd"), fail, ms -> {})
                .onRetry(() -> l.record(0, 0, 0, 1));
        assertThrows(ProviderException.class, () -> p.complete(new Provider.Request("s", List.of(), 10, 0, Duration.ofSeconds(1))));
        assertEquals(3, l.hourRequests(0));
    }

    @Test
    void hardDenyAfterAliases() {
        CommandGate g = new CommandGate(List.of("restart", "allow"), List.of());
        assertNotNull(g.denied("restart", "ok"));
        assertNotNull(g.denied("allow", "bob"));
    }

    @Test
    void ownerStopPausesAndStatusSaysSo() {
        AiSettings s = new AiSettings();
        CommandGate gate = new CommandGate(List.of(), List.of());
        AiLoop loop = new AiLoop(s, gate, new PromptBuilder(null, gate, false, "b", "o"), new Budget(s, new UsageLedger(null, ZoneId.of("UTC"))), new AiLoop.Sink() {
            public void notice(String t) {}
            public void log(long n, String x) {}
            public void trial(long n, String w, String c, String a) {}
        });
        loop.enable(0);
        loop.ownerRequest("build", 0);
        loop.ownerStop(1000);
        assertEquals(AiLoop.State.PAUSED, loop.state());
        assertTrue(loop.status(1000).get(0).contains("paused (until your next request)"));
    }
}
