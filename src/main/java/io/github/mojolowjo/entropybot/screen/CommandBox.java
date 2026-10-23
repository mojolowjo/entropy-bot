package io.github.mojolowjo.entropybot.screen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * 0.39.z24 the config screen's command box: one line goes to the bot exactly as {@code /bot <line>} does
 * ({@code Commands.localLine}: queued like a PM from the keyboard, same gate, same rights). The bot's answers to
 * the keyboard land in {@link #REPLIES}, the box shows the last few. Pure apart from the shared ring (JUnit).
 */
public final class CommandBox {
    private CommandBox() {}

    public static final int KEEP = 6; // structural: lines kept for the screen

    /** The answers to the keyboard (oldest first), filled from {@code Commands.whisperSent}. */
    public static final Ring REPLIES = new Ring(KEEP);

    /** Sends the line through dispatch (the same consumer /bot uses); returns what to show, or null when sent. */
    public static String submit(String line, Consumer<String> dispatch) {
        String t = line == null ? "" : line.trim();
        if (t.startsWith("/bot ")) t = t.substring(5).trim();
        else if (t.startsWith("/")) t = t.substring(1).trim();
        if (t.isEmpty()) return "type a command first (e.g. aa status)";
        dispatch.accept(t);
        REPLIES.add("> " + t);
        return null;
    }

    public static final class Ring {
        private final int max;
        private final Deque<String> lines = new ArrayDeque<>();

        public Ring(int max) { this.max = max; }

        public synchronized void add(String line) {
            if (line == null) return;
            lines.addLast(line);
            while (lines.size() > max) lines.removeFirst();
        }

        public synchronized List<String> lines() { return new ArrayList<>(lines); }
    }
}
