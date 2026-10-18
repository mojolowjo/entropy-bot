package io.github.mojolowjo.entropybot.ai.provider;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/** The one HTTP POST the providers need; the JDK client in game, a fake in JUnit (no network in tests). */
public interface HttpTransport {
    /** headers: lower-case names, first value. */
    record Response(int status, String body, Map<String, String> headers) {}

    Response post(String url, Map<String, String> headers, String body, Duration timeout) throws IOException, InterruptedException;
}
