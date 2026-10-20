package io.github.mojolowjo.entropybot.aa.door;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * AA_CLEANUP step 2, the one door. Every job start ({@code Jobs.start*}, {@code replaceWalk}), chain start and clear
 * and the reflex job stop ask {@link #allow(String)}. The asker is the current {@link Driver} scope ({@link #scope});
 * no scope counts as {@link Driver#OTHER}. With AA off everything passes (behaviour unchanged). With AA on only AA,
 * the owner and a true reflex pass; a refusal writes one log line and counts per driver, both at most once per
 * {@link #REPEAT_MS} for the same driver and action, so a timer asking every second is one refusal, not sixty.
 * Client thread only (like the rest of the bot's tick). Loader-neutral: no game imports.
 */
public final class Door {
    private Door() {}

    /** The same driver and action are counted and logged again only after this long. */
    public static final long REPEAT_MS = 30_000;

    private static BooleanSupplier aaOn = () -> false;
    private static Consumer<String> log = s -> {};
    private static LongSupplier clock = System::currentTimeMillis;
    private static Driver current;
    private static final EnumMap<Driver, Integer> refused = new EnumMap<>(Driver.class);
    private static final EnumMap<Driver, String> lastWhat = new EnumMap<>(Driver.class);
    private static final Map<String, Long> lastAt = new HashMap<>();

    /** Wiring: the AA switch and the log sink (Commands, at start). */
    public static void wire(BooleanSupplier aa, Consumer<String> logSink) {
        aaOn = aa == null ? () -> false : aa;
        log = logSink == null ? s -> {} : logSink;
    }

    /** For tests. */
    static void clock(LongSupplier c) { clock = c; }

    /** For tests and a new world: counters and scope cleared. */
    public static void reset() {
        refused.clear();
        lastWhat.clear();
        lastAt.clear();
        current = null;
    }

    public static boolean aaOn() {
        try { return aaOn.getAsBoolean(); } catch (RuntimeException e) { return false; }
    }

    /** The driver of the running code, or null when no scope is open (counts as OTHER at the door). */
    public static Driver current() { return current; }

    /** A driver scope; close it to restore the previous one. A null driver opens a "no scope" (OTHER) section. */
    public static Scope scope(Driver d) {
        Scope s = new Scope(current);
        current = d;
        return s;
    }

    /** Try-with-resources handle for {@link #scope}. */
    public static final class Scope implements AutoCloseable {
        private final Driver prev;
        private Scope(Driver prev) { this.prev = prev; }
        @Override public void close() { current = prev; }
    }

    /** The current driver may do {@code what}? */
    public static boolean allow(String what) { return allow(current == null ? Driver.OTHER : current, what); }

    /** May {@code d} do {@code what} (a short text: "chain escape stairs", "travel goto 1 2 3")? Refusals are counted. */
    public static boolean allow(Driver d, String what) {
        if (d == null) d = Driver.OTHER;
        if (d.passesUnderAa() || !aaOn()) return true;
        refuse(d, what);
        return false;
    }

    /** Counts and logs a refusal (rate-limited per driver and action). */
    public static void refuse(Driver d, String what) {
        String w = what == null ? "?" : what;
        String key = d + "|" + w;
        long now = clock.getAsLong();
        Long at = lastAt.get(key);
        if (at != null && now - at < REPEAT_MS) return;
        lastAt.put(key, now);
        refused.merge(d, 1, Integer::sum);
        lastWhat.put(d, w);
        try { log.accept("door: refused " + d.label() + ": " + w + " (aa is on)"); } catch (RuntimeException ignored) {}
    }

    /** The reply text for a refused start. */
    public static String refusal(String what) {
        Driver d = current == null ? Driver.OTHER : current;
        return "error: refused: aa is on, " + d.label() + " may not start or stop a job (" + what + ")";
    }

    public static int refused(Driver d) { return refused.getOrDefault(d, 0); }

    /** "refused starts: danger 3 (last: retreat home from zombie), other 1 (last: chain escape stairs)" or "refused starts: none". */
    public static String text() {
        if (refused.isEmpty()) return "refused starts: none";
        StringBuilder b = new StringBuilder("refused starts: ");
        boolean first = true;
        for (Map.Entry<Driver, Integer> e : refused.entrySet()) {
            if (!first) b.append(", ");
            first = false;
            b.append(e.getKey().label()).append(' ').append(e.getValue());
            String w = lastWhat.get(e.getKey());
            if (w != null) b.append(" (last: ").append(w).append(')');
        }
        return b.toString();
    }

    /**
     * Under AA the AI operator only translates: "aa want ...", "aa drop ...", and read-only questions
     * ("aa status", "aa why", "status"). Everything else it sends is refused.
     */
    public static boolean aiMayUnderAa(String line) {
        if (line == null) return false;
        String l = line.trim().toLowerCase().replaceAll("\\s+", " ");
        return l.matches("aa (want|drop|why)( .*)?") || l.equals("aa status") || l.equals("status") || l.equals("aa");
    }
}
