package io.github.mojolowjo.entropybot.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import io.github.mojolowjo.entropybot.ai.provider.ChatCompletionsProvider;
import io.github.mojolowjo.entropybot.ai.provider.DemoProvider;
import io.github.mojolowjo.entropybot.ai.provider.HttpTransport;
import io.github.mojolowjo.entropybot.ai.provider.JdkTransport;
import io.github.mojolowjo.entropybot.ai.provider.Provider;
import io.github.mojolowjo.entropybot.ai.provider.ProviderException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The chat-completions adapter against a fake transport and a loopback HttpServer (no internet). */
class ProvidersTest {
    static final Secret KEY = Secret.of("sk-test-0000-abcd");
    static final Provider.Request REQ = new Provider.Request("sys", List.of(new Provider.Message("user", "hi")), 120, 0.2, Duration.ofSeconds(5));

    /** Answers the queued responses (or throws the queued IOException); records the requests. */
    static final class Fake implements HttpTransport {
        final Deque<Object> answers = new ArrayDeque<>();
        final List<String> bodies = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();
        String lastUrl;

        Fake add(int st, String body, Map<String, String> h) { answers.add(new Response(st, body, h)); return this; }

        Fake add(int st, String body) { return add(st, body, Map.of()); }

        @Override
        public Response post(String url, Map<String, String> h, String body, Duration timeout) throws IOException {
            lastUrl = url;
            bodies.add(body);
            headers.add(h);
            Object a = answers.poll();
            if (a instanceof IOException e) throw e;
            return (Response) a;
        }
    }

    final List<Long> slept = new ArrayList<>();

    ChatCompletionsProvider or(Fake f) throws ProviderException {
        return new ChatCompletionsProvider("openrouter", "", "m:free", KEY, f, slept::add);
    }

    static final String OK = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"status\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":3,\"cost\":0}}";

    @Test
    void openRouterRequestAndCost() throws Exception {
        Fake f = new Fake().add(200, OK);
        Provider.Completion c = or(f).complete(REQ);
        assertEquals("status", c.text());
        assertEquals(1200, c.inTokens());
        assertEquals(3, c.outTokens());
        assertEquals(0.0, c.costUsd());
        assertEquals("https://openrouter.ai/api/v1/chat/completions", f.lastUrl);
        JsonObject b = JsonParser.parseString(f.bodies.get(0)).getAsJsonObject();
        assertEquals("m:free", b.get("model").getAsString());
        assertEquals("system", b.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString());
        assertTrue(b.getAsJsonObject("usage").get("include").getAsBoolean());
        assertEquals("Bearer " + KEY.reveal(), f.headers.get(0).get("Authorization"));
        assertFalse(f.bodies.get(0).contains(KEY.reveal()), "the key is only in the header");
    }

    @Test
    void retries429WithRetryAfterThenSucceeds() throws Exception {
        Fake f = new Fake().add(429, "{\"error\":{\"message\":\"slow down\"}}", Map.of("retry-after", "30")).add(200, OK);
        assertEquals("status", or(f).complete(REQ).text());
        assertEquals(List.of(30_000L), slept);
    }

    @Test
    void serverErrorsRetryTwiceThenFail() throws Exception {
        Fake f = new Fake().add(503, "").add(502, "").add(500, "boom");
        ProviderException e = assertThrows(ProviderException.class, () -> or(f).complete(REQ));
        assertEquals(ProviderException.Kind.SERVER, e.kind);
        assertFalse(e.fatal());
        assertEquals(List.of(2000L, 8000L), slept);
    }

    @Test
    void authErrorsNeverRetry() throws Exception {
        Fake f = new Fake().add(401, "{\"error\":{\"message\":\"bad key sk-test-0000-abcd\"}}");
        ProviderException e = assertThrows(ProviderException.class, () -> or(f).complete(REQ));
        assertEquals(ProviderException.Kind.AUTH, e.kind);
        assertTrue(e.fatal());
        assertTrue(e.reason().startsWith("401: the key was refused"), e.reason());
        assertFalse(e.reason().contains("abcd"), "scrubbed: " + e.reason());
        assertTrue(slept.isEmpty());
        assertEquals(ProviderException.Kind.BAD_MODEL, assertThrows(ProviderException.class, () -> or(new Fake().add(404, "")).complete(REQ)).kind);
        assertEquals(ProviderException.Kind.NO_CREDIT, assertThrows(ProviderException.class, () -> or(new Fake().add(402, "")).complete(REQ)).kind);
    }

    @Test
    void networkErrorsRetryAndKeepTheKeyOut() throws Exception {
        Fake f = new Fake();
        for (int i = 0; i < 3; i++) f.answers.add(new IOException("refused, Authorization: Bearer " + KEY.reveal()));
        ProviderException e = assertThrows(ProviderException.class, () -> or(f).complete(REQ));
        assertEquals(ProviderException.Kind.NETWORK, e.kind);
        assertFalse(e.getMessage().contains(KEY.reveal()), e.getMessage());
    }

    @Test
    void emptyAndGarbledAnswers() throws Exception {
        assertEquals("", or(new Fake().add(200, "{\"choices\":[{\"message\":{\"content\":null}}]}")).complete(REQ).text());
        assertEquals(ProviderException.Kind.BAD_RESPONSE, assertThrows(ProviderException.class, () -> or(new Fake().add(200, "<html>")).complete(REQ)).kind);
        assertEquals(ProviderException.Kind.BAD_RESPONSE, assertThrows(ProviderException.class, () -> or(new Fake().add(200, "{\"error\":{\"message\":\"x\"}}")).complete(REQ)).kind);
    }

    @Test
    void configChecks() {
        assertEquals(ProviderException.Kind.CONFIG, assertThrows(ProviderException.class,
                () -> new ChatCompletionsProvider("openai", "http://example.com/v1", "m", KEY, new Fake(), ms -> {})).kind);
        assertEquals(ProviderException.Kind.CONFIG, assertThrows(ProviderException.class,
                () -> new ChatCompletionsProvider("openrouter", "", "m", Secret.NONE, new Fake(), ms -> {})).kind);
        assertDoesNotThrow(() -> new ChatCompletionsProvider("local", "", "llama3", Secret.NONE, new Fake(), ms -> {}));
        assertNull(ChatCompletionsProvider.urlProblem("http://127.0.0.1:1234/v1"));
        assertNotNull(ChatCompletionsProvider.urlProblem("ftp://x/"));
    }

    @Test
    void localNeedsNoKeyHeader() throws Exception {
        Fake f = new Fake().add(200, OK);
        new ChatCompletionsProvider("local", "", "llama3", Secret.NONE, f, ms -> {}).complete(REQ);
        assertEquals("http://127.0.0.1:11434/v1/chat/completions", f.lastUrl);
        assertNull(f.headers.get(0).get("Authorization"));
    }

    @Test
    void realSocketOnLoopback() throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> auth = new ArrayList<>();
        srv.createContext("/v1/chat/completions", ex -> {
            auth.add(ex.getRequestHeaders().getFirst("Authorization"));
            ex.getRequestBody().readAllBytes();
            byte[] b = OK.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        srv.start();
        try {
            String base = "http://127.0.0.1:" + srv.getAddress().getPort() + "/v1";
            Provider p = new ChatCompletionsProvider("openai", base, "m", KEY, new JdkTransport(), ms -> {});
            assertEquals("status", p.complete(REQ).text());
            assertEquals("Bearer " + KEY.reveal(), auth.get(0));
            assertFalse(p.describe().contains(KEY.reveal()));
        } finally {
            srv.stop(0);
        }
        assertNull(JdkTransport.missing());
    }

    @Test
    void demoReplaysItsScript() {
        DemoProvider d = new DemoProvider(List.of("status", "finished"));
        assertEquals("status", d.complete(REQ).text());
        assertEquals("finished", d.complete(REQ).text());
        assertEquals("wait", d.complete(REQ).text());
    }
}
