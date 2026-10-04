package io.github.mojolowjo.entropybot.commands;

import java.util.function.Predicate;

/**
 * B7e (E1): "b &lt;baritone command&gt;" / "baritone ..." (PM, owner only) and the cmd.json / fast-channel type
 * "baritone": a raw Baritone command through its command manager (chat control stays off), with the bridge's
 * wording. A "set" runs with the protected list reset so settings.txt never gets thousands of blocks
 * ({@code SafetyNet.execute}).
 */
public final class BaritoneVerb {
    private BaritoneVerb() {}

    public static final String USAGE = "usage: b <baritone command> (e.g. b set allowSprint true)";

    /** The game side: Baritone's primary instance, through the safety net. */
    public static String run(String text, boolean isOwner, String owner) {
        return reply(text, isOwner, owner, t -> {
            baritone.api.IBaritone b = io.github.mojolowjo.entropybot.baritone.SafetyNet.primary();
            if (b == null) throw new IllegalStateException("baritone not loaded");
            return io.github.mojolowjo.entropybot.baritone.SafetyNet.INSTANCE.execute(b, t);
        });
    }

    /** Pure: the owner check, the usage, and Baritone's answer as the bridge worded it. */
    static String reply(String text, boolean isOwner, String owner, Predicate<String> execute) {
        if (!isOwner) return "only " + owner + " can send raw Baritone commands";
        String t = text == null ? "" : text.trim();
        if (t.startsWith("#")) t = t.substring(1).trim();
        if (t.isEmpty()) return USAGE;
        try {
            return execute.test(t) ? "ok (check chat for Baritone errors)" : "error: baritone rejected command";
        } catch (IllegalStateException e) {
            return "error: " + e.getMessage();
        } catch (RuntimeException e) {
            return "error: " + e;
        }
    }
}
