package io.github.mojolowjo.entropybot.aa.core;

/**
 * AA v0 (AA_PLAN 4): a request on the board: who wants what run. Sources: "owner" (a PM order, the queue), "ai" (the
 * AI operator), later "food", "rule", "assist". Only AaRuntime turns a taken request into a chain.
 */
public record Request(String source, TaskKind kind, String chain, int priority, long postedAt, long expiresAt, String reason) {
    public boolean expired(long now) { return expiresAt > 0 && now > expiresAt; }
}
