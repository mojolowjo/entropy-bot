package io.github.mojolowjo.entropybot.route;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Rate-limited logging for the route core (docs/PLANNING.md section 3): the first {@code fullFirst} errors in full
 * (with the stack), after that at most one line per {@code intervalMs} saying how many were held back. The adapter
 * passes a sink that writes to the mod's log with the {@code [entropybot]} prefix.
 */
public final class RouteLog {
    private final Consumer<String> sink;
    private final int fullFirst;
    private final long intervalMs;
    private final LongSupplier clock;
    private int errors;
    private long lastLineMs = Long.MIN_VALUE;
    private int held;

    public RouteLog(Consumer<String> sink, int fullFirst, long intervalMs, LongSupplier clock) {
        this.sink = sink;
        this.fullFirst = fullFirst;
        this.intervalMs = intervalMs;
        this.clock = clock;
    }

    /** 5 in full, then one line a minute. */
    public static RouteLog of(Consumer<String> sink) {
        return new RouteLog(sink, 5, 60_000, System::currentTimeMillis);
    }

    public static RouteLog stderr() {
        return of(s -> System.err.println("[entropybot] " + s));
    }

    public synchronized void error(String where, Throwable t) {
        errors++;
        if (errors <= fullFirst) {
            StringWriter w = new StringWriter();
            if (t != null) t.printStackTrace(new PrintWriter(w));
            emit("route error in " + where + ": " + t + (t == null ? "" : "\n" + w));
            return;
        }
        limited("route error in " + where + ": " + t);
    }

    public synchronized void warn(String line) {
        limited("route: " + line);
    }

    public void info(String line) {
        emit("route: " + line);
    }

    private void limited(String line) {
        long now = clock.getAsLong();
        if (lastLineMs != Long.MIN_VALUE && now - lastLineMs < intervalMs) {
            held++;
            return;
        }
        lastLineMs = now;
        String extra = held > 0 ? " (+" + held + " more held back)" : "";
        held = 0;
        emit(line + extra);
    }

    private void emit(String s) {
        try {
            sink.accept(s);
        } catch (RuntimeException ignored) {
            // a broken log sink must not take the worker down; nothing better to report it to
        }
    }
}
