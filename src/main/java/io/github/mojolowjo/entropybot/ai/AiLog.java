package io.github.mojolowjo.entropybot.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/**
 * 0.35.0: {@code entropybot/ai/ai.log} (one readable line per turn, rotated at 2 MB x 3 files) and the trial notes
 * {@code entropybot/ai/trial-<date>.md} ({@code | time | wanted | command | answer |}, the trial 1-12 format). Every
 * line goes through {@link Redact} with the known keys first. Never throws (a write error is kept for ai status).
 * Plain Java.
 */
public final class AiLog {
    static final long ROTATE_BYTES = 2L * 1024 * 1024;
    static final int FILES = 3;
    static final int MEMORY = 200;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Path dir;
    private final ZoneId zone;
    private final Supplier<Collection<Secret>> keys;
    private final Deque<String> recent = new ArrayDeque<>();
    private volatile String error;

    public AiLog(Path dir, ZoneId zone, Supplier<Collection<Secret>> keys) {
        this.dir = dir;
        this.zone = zone;
        this.keys = keys;
    }

    public Path dir() { return dir; }

    public String error() { return error; }

    private String time(long now) { return LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone).format(TIME); }

    public synchronized void line(long now, String text) {
        String l = time(now) + " " + Redact.scrub(text, keys.get()).replace('\n', ' ');
        recent.addLast(l);
        while (recent.size() > MEMORY) recent.pollFirst();
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
            Path f = dir.resolve("ai.log");
            if (Files.exists(f) && Files.size(f) > ROTATE_BYTES) rotate(f);
            Files.writeString(f, l + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            error = null;
        } catch (IOException | RuntimeException e) {
            error = "couldn't write ai.log: " + e.getMessage();
        }
    }

    private void rotate(Path f) throws IOException {
        for (int i = FILES - 1; i >= 1; i--) {
            Path from = i == 1 ? f : dir.resolve("ai.log." + (i - 1));
            Path to = dir.resolve("ai.log." + i);
            if (Files.exists(from)) Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The last n lines (newest last). */
    public synchronized List<String> last(int n) {
        List<String> all = new ArrayList<>(recent);
        return all.subList(Math.max(0, all.size() - Math.max(1, n)), all.size());
    }

    static String cell(String s) {
        String t = s == null ? "" : s.replace("|", "/").replace("\n", " ").trim();
        return t.length() > 160 ? t.substring(0, 157) + "..." : t;
    }

    /** One trial notes row. */
    public synchronized void trial(long now, String wanted, String command, String answer) {
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
            String date = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone).toLocalDate().toString();
            Path f = dir.resolve("trial-" + date + ".md");
            if (!Files.exists(f))
                Files.writeString(f, "# AI operator notes " + date + "\n\n| time | wanted | command | answer |\n|---|---|---|---|\n", StandardCharsets.UTF_8);
            String row = "| " + time(now) + " | " + cell(Redact.scrub(wanted, keys.get())) + " | " + cell(Redact.scrub(command, keys.get())) + " | "
                    + cell(Redact.scrub(answer, keys.get())) + " |" + System.lineSeparator();
            Files.writeString(f, row, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            error = "couldn't write the trial notes: " + e.getMessage();
        }
    }
}
