package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.memory.Limits;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The whispers waiting to go out (one every 25 ticks), with package H's caps: one answer takes at most
 * {@link Limits#WHISPER_PARTS} whispers, the queue holds {@link Limits#OUTBOX}. Past that, longer answers are dropped
 * (and returned, for the log), but a one-line message to the owner still gets in ("I died at ...", "parked", "left my
 * areas" must not be lost to a long list), up to twice the cap. Drops put one note at the front of the queue:
 * "(N whispers dropped: ...)". Plain Java, thread-safe; JUnit drives it.
 */
public final class Outbox {
    /**
     * 0.22.3: true when a whisper to {@code to} can go out as {@code /msg}: the name is in the tab list (case-insensitive).
     * False (singleplayer, the owner offline): the bot prints it in its own chat instead of the server's "No player was found".
     */
    public static boolean canMsg(String to, java.util.Collection<String> online) {
        if (to == null || online == null) return false;
        for (String n : online) if (to.equalsIgnoreCase(n)) return true;
        return false;
    }

    private final ArrayDeque<String[]> queue = new ArrayDeque<>();
    private String[] dropNote;
    private int dropped;

    /** Queues the text for {@code to}; returns the parts dropped (empty when all went in). */
    public synchronized List<String> add(String to, String text, String owner) {
        List<String> parts = Limits.capParts(Texts.whisperParts(text), Limits.WHISPER_PARTS), lost = new ArrayList<>();
        boolean urgent = parts.size() == 1 && to != null && to.equalsIgnoreCase(owner);
        for (String part : parts) {
            if (queue.size() >= Limits.OUTBOX && !(urgent && queue.size() < 2 * Limits.OUTBOX)) {
                dropped++;
                lost.add(part);
                if (dropNote == null) {
                    dropNote = new String[]{owner, ""};
                    queue.addFirst(dropNote);
                }
                dropNote[1] = "(" + dropped + " whispers dropped: too many at once - the game log has them)";
                continue;
            }
            queue.add(new String[]{to, part});
        }
        return lost;
    }

    /** The next whisper {to, text}, or null. */
    public synchronized String[] poll() {
        String[] m = queue.poll();
        if (m != null && m == dropNote) {
            dropNote = null;
            dropped = 0;
        }
        return m;
    }

    public synchronized int size() { return queue.size(); }
}
