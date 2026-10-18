package io.github.mojolowjo.entropybot.ai.provider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Java 21's {@code java.net.http.HttpClient} (no extra jar, so nothing to shade). Synchronous {@code send}: it runs on
 * the AI worker thread only. Loader-neutral.
 */
public final class JdkTransport implements HttpTransport {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    /** null when java.net.http is there, else why not (the self-check says it instead of crashing). */
    public static String missing() {
        try {
            Class.forName("java.net.http.HttpClient");
            return null;
        } catch (Throwable t) {
            return "java.net.http is missing in this Java runtime: " + t;
        }
    }

    @Override
    public Response post(String url, Map<String, String> headers, String body, Duration timeout) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(b::header);
        HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Map<String, String> h = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : r.headers().map().entrySet())
            if (!e.getValue().isEmpty()) h.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().get(0));
        return new Response(r.statusCode(), r.body() == null ? "" : r.body(), h);
    }
}
