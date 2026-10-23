package io.github.mojolowjo.entropybot.aa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 0.39.z29 (owner 2026-10-23): {@code aa debug} writes its tree to {@code <game dir>/entropybot/aa-debug.log} (one file
 * per instance, beside runs.csv and predictions.csv) and chat gets one line. Same writer as runs.csv / predictions.csv
 * (Files.writeString UTF-8, CREATE + APPEND; rotate by Files.move REPLACE_EXISTING). The tree text is untouched. Pure
 * except the file it is given; loader notes: none (java.nio only).
 */
public final class DebugLog {
    private DebugLog() {}

    public static final String FILE = "aa-debug.log", OLD = "aa-debug.1.log", SHOWN = "entropybot\\aa-debug.log";
    static final String SEP = "=".repeat(72);    // structural: the owner's block separator

    /** One block: separator, header "time | world | typed line", blank, the tree as is, blank. LF only. */
    public static String block(String time, String world, String typed, String tree) {
        String body = tree == null ? "" : tree.replace("\r\n", "\n");
        return SEP + "\n" + time + " | " + world + " | " + typed + "\n\n" + body + "\n\n";
    }

    /** The tree's line count. */
    public static int lines(String tree) { return tree == null || tree.isEmpty() ? 0 : tree.split("\n", -1).length; }

    /** The pick line: the tree's first line under its header (a one-line answer, a refusal, is itself). */
    public static String pickLine(String tree) {
        if (tree == null || tree.isEmpty()) return "";
        String[] l = tree.split("\n");
        return (l.length > 1 ? l[1] : l[0]).trim();
    }

    /** The chat line for a written block. */
    public static String chat(String tree) {
        return "aa debug: " + lines(tree) + " lines written to " + SHOWN + " (" + pickLine(tree) + ")";
    }

    /**
     * Appends one block to dir/aa-debug.log; at maxBytes the file first becomes aa-debug.1.log. Returns the chat text
     * (the chat line, plus a rollover line when one happened, or the write failure with its exception). Never throws.
     */
    public static String write(Path dir, long maxBytes, String time, String world, String typed, String tree) {
        if (dir == null) return "aa debug: not written (no entropybot folder yet: no game dir)\n" + tree;
        Path f = dir.resolve(FILE);
        String roll = "";
        try {
            Files.createDirectories(dir);
            if (Files.exists(f) && Files.size(f) >= maxBytes) {
                Files.move(f, dir.resolve(OLD), StandardCopyOption.REPLACE_EXISTING);
                roll = "aa debug: " + FILE + " reached " + maxBytes + " bytes (debug.logMaxMb): renamed to " + OLD + ", a fresh file starts\n";
            }
            Files.writeString(f, block(time, world, typed, tree), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return roll + chat(tree);
        } catch (IOException | RuntimeException e) {
            return roll + "aa debug: could not write " + SHOWN + ": " + e + " (" + pickLine(tree) + ")";
        }
    }
}
