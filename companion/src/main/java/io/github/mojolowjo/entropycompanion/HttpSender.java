package io.github.mojolowjo.entropycompanion;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** The real network: java.net.http, a 2 s connect timeout, the key in an X-Key header (never in the URL). */
public final class HttpSender implements PostLoop.Sender, CmdClient.Http {
    /** Answers are read up to this size (a /api/map/boxes answer is a few KB). */
    static final int MAX_BODY = 256 * 1024;

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

    @Override
    public CmdClient.Resp send(String method, URI uri, String key, String body, int timeoutMs) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeoutMs)).header("X-Key", key);
        if ("POST".equals(method)) b.header("Content-Type", "text/plain; charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        else b.GET();
        try {
            HttpResponse<InputStream> r = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = r.body()) {
                return new CmdClient.Resp(r.statusCode(), new String(in.readNBytes(MAX_BODY), StandardCharsets.UTF_8));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
