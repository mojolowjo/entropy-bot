package io.github.mojolowjo.entropybot;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * No silent failure (RULES row 7): a caught exception is logged with its site; a repeating site logs the first hit and then
 * every 100th. The game's logger is used when it is on the class path (unit tests run without it: stderr then).
 */
public final class Quiet {
    private static final Object LOG = logger();
    private static final ConcurrentHashMap<String, AtomicInteger> HITS = new ConcurrentHashMap<>();

    private Quiet() {}

    private static Object logger() {
        try { return com.mojang.logging.LogUtils.getLogger(); } catch (LinkageError e) { return null; }   // no game classes: stderr
    }

    /** A warning line with no exception (the caller decides how often): the game's log, else stderr. */
    public static void line(String line) {
        if (LOG instanceof org.slf4j.Logger l) l.warn("[entropybot] {}", line); else System.err.println("[entropybot] " + line);
    }

    /** 0.39.z76 send-back 1 (run AN 23:30:07): the first frame of this mod in a throw ("Mining.exploreStep0:1228"); "an unknown frame" with none. */
    public static String where(Throwable e) {
        for (StackTraceElement f : e.getStackTrace())
            if (f.getClassName().startsWith("io.github.mojolowjo.entropybot."))
                return f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName() + ":" + f.getLineNumber();
        return "an unknown frame";
    }

    public static void warn(String reason, Throwable t) {
        int n = HITS.computeIfAbsent(reason, k -> new AtomicInteger()).incrementAndGet();
        if (n != 1 && n % 100 != 0) return;
        String line = reason + " (x" + n + "): " + t;
        if (LOG instanceof org.slf4j.Logger l) l.warn("{}", line); else System.err.println("[entropybot] " + line);
    }
}