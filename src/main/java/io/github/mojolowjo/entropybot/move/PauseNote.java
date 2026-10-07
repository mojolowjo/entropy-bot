package io.github.mojolowjo.entropybot.move;

/**
 * 0.23.4: the words a long walk logs (and {@code path status} shows) when it comes back from a pause, so a fight's
 * outcome is explicit: {@code resumed leg 2/4 after fight (moved 1 block)} or
 * {@code re-planned route after fight (moved 8 blocks)}. The rule itself is {@link LegJudge#resume} (3 blocks). Pure.
 */
public final class PauseNote {
    private PauseNote() {}

    /** A reflex word or reason to the short cause: fighting/fleeing/retreating -> fight, eating -> meal, else as given. */
    public static String cause(String c) {
        if (c == null || c.isBlank()) return "pause";
        String s = c.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.startsWith("after ")) s = s.substring(6);
        if (s.startsWith("fight") || s.startsWith("flee") || s.startsWith("retreat")) return "fight";
        if (s.startsWith("eat")) return "meal";
        if (s.startsWith("fetch")) return "food fetch";
        return s;
    }

    public static String resumed(int leg, int legs, String cause, long moved) {
        return "resumed leg " + leg + "/" + legs + " after " + cause + moved(moved);
    }

    public static String replanned(String cause, long moved) {
        return "re-planned route after " + cause + moved(moved);
    }

    private static String moved(long n) {
        return n < 0 ? "" : " (moved " + n + (n == 1 ? " block)" : " blocks)");
    }
}
