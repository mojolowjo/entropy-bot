package io.github.mojolowjo.entropybot.ai;

/**
 * 0.35.0: an API key. {@link #toString} never shows it ("****ab12"); only the provider's request header reads
 * {@link #reveal}. Plain Java.
 */
public final class Secret {
    public static final Secret NONE = new Secret("");

    private final String value;

    private Secret(String value) { this.value = value; }

    public static Secret of(String value) {
        String v = value == null ? "" : value.trim();
        return v.isEmpty() ? NONE : new Secret(v);
    }

    public boolean isEmpty() { return value.isEmpty(); }

    /** The key itself: for the Authorization header and the redaction list only. */
    public String reveal() { return value; }

    /** "…ab12" (the last 4), or "missing". */
    public String hint() {
        if (value.isEmpty()) return "missing";
        return "…" + (value.length() <= 8 ? "" : value.substring(value.length() - 4));
    }

    @Override
    public String toString() { return value.isEmpty() ? "(no key)" : "****" + hint().substring(1); }
}
