package io.github.mojolowjo.entropybot.ai.provider;

import io.github.mojolowjo.entropybot.ai.AiSettings;
import io.github.mojolowjo.entropybot.ai.Secret;

/** 0.35.0: the provider for the settings (openrouter / openai / local share one wire format; demo has no network). */
public final class Providers {
    private Providers() {}

    public static Provider create(AiSettings s, Secret key, HttpTransport http) throws ProviderException {
        return switch (s.provider) {
            case "demo" -> new DemoProvider(s.demoScript);
            case "openrouter", "openai", "local" -> new ChatCompletionsProvider(s.provider, s.baseUrl, s.model, key, http, Thread::sleep).reasoning(s.reasoning);
            default -> throw new ProviderException(ProviderException.Kind.CONFIG, 0, "unknown provider " + s.provider + " (openrouter, openai, local, demo)");
        };
    }
}
