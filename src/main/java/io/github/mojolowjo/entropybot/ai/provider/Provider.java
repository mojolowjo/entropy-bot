package io.github.mojolowjo.entropybot.ai.provider;

import java.time.Duration;
import java.util.List;

/**
 * 0.35.0 AI operator: one model service. Plain Java (no Minecraft or NeoForge types), so it moves to any loader as is.
 * {@link #complete} blocks and is only ever called on the AI worker thread, never on the game or render thread.
 */
public interface Provider {
    /** One chat turn. Throws with a precise kind and reason; never returns null. */
    Completion complete(Request r) throws ProviderException;

    /** A short name for logs and the status line ("openrouter nvidia/...:free"). Never contains the key. */
    String describe();

    /** One message of the rolling history (role: user or assistant). */
    record Message(String role, String content) {}

    /** What is sent: the static system block, the rolling messages, the answer cap. */
    record Request(String system, List<Message> messages, int maxTokens, double temperature, Duration timeout) {}

    /** The answer: text (may be empty), token counts, cost in USD when the service tells it (null otherwise). */
    record Completion(String text, long inTokens, long outTokens, Double costUsd, String finish, long millis) {}
}
