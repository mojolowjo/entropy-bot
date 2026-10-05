package io.github.mojolowjo.entropybot.clear;

/**
 * 0.19.5: a coordinate word, Minecraft style: {@code 12} (absolute), {@code ~} (the base), {@code ~N} / {@code ~+N} /
 * {@code ~-N} (the base plus N). The base is the bot's feet block (floor of x/y/z) when the command is parsed.
 * Game-free.
 */
public final class RelCoord {
    private RelCoord() {}

    /** The coordinate, or null for a word that isn't one. */
    public static Integer parse(String token, int base) {
        if (token == null) return null;
        String t = token.trim();
        if (t.isEmpty()) return null;
        try {
            if (t.charAt(0) != '~') return Integer.parseInt(t);
            if (t.length() == 1) return base;
            String r = t.substring(1);
            if (r.startsWith("+")) r = r.substring(1);
            if (r.isEmpty() || r.startsWith("+")) return null;
            return Math.addExact(base, Integer.parseInt(r));
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    /** True when the word uses the {@code ~} form. */
    public static boolean relative(String token) {
        return token != null && token.trim().startsWith("~");
    }
}
