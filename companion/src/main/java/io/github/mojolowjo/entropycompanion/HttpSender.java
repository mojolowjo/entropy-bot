package io.github.mojolowjo.entropycompanion;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** The real network: java.net.http, a 2 s connect and request timeout, the key in an X-Key header (never in the URL). */
public final class HttpSender implements PostLoop.Sender {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(PostLoop.TIMEOUT_MS))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public int post(URI endpoint, String key, String body, int timeoutMs) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("X-Key", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            return client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}