package io.github.mojolowjo.entropycompanion;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The overlay's lines (COMPANION_PLAN 2.1 item 5): each reply shows for {@code lifeMs}, at most {@link #MAX} lines,
 * newest on top. A sticky line (the "dashboard unreachable" notice) stays until cleared. Thread-safe: replies arrive
 * on the worker thread, the overlay reads on the render thread.
 */
public final class ReplyQueue {
    public static final int MAX = 3;
    public static final int MAX_CHARS = 120;

    private record Line(String text, long until) {}

    private final Deque<Line> lines = new ArrayDeque<>();
    private String sticky;

    public synchronized void add(String text, long now, long lifeMs) {
        if (text == null || text.isBlank()) return;
        String t = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS - 3) + "..." : text;
        lines.addFirst(new Line(t, now + Math.max(500, lifeMs)));
        while (lines.size() > MAX) lines.removeLast();
    }

    public synchronized void sticky(String text) { sticky = text; }

    public synchronized String sticky() { return sticky; }

    /** The lines to draw now, newest first (the sticky line first of all); expired ones are dropped. */
    public synchronized List<String> visible(long now) {
        lines.removeIf(l -> l.until <= now);
        List<String> out = new ArrayList<>();
        if (sticky != null) out.add(sticky);
        for (Line l : lines) out.add(l.text);
        return out;
    }
}
