package io.github.mojolowjo.entropybot.surface;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * chunks-0.23.5: which surface files are the bot's own and which came from the companion (through the dashboard's
 * {@code POST /api/chunks}, written with {@code "src":"companion"} right after {@code t}). The export deletes its own files
 * on unload and wipes them on join; a companion file stays unless it names another dimension than its folder's (a
 * misplaced file). Plain Java, JUnit on a temp folder. Loader notes: none.
 */
public final class SurfaceFiles {
    /** Bytes read from the head of a file: v, dim (64 at most), cx, cz, t and src all fit. */
    public static final int HEAD = 256;

    private SurfaceFiles() {}

    public static boolean companion(String head) {
        return head != null && head.contains("\"src\":\"companion\"");
    }

    /** Kept through the export's unload and wipe: a companion file of this folder's dimension. */
    public static boolean keep(String head, String dim) {
        return companion(head) && head.contains("\"dim\":\"" + SurfaceColumns.escape(dim) + "\"");
    }

    /** The first {@link #HEAD} bytes of a file, or null when it is gone. */
    public static String head(Path f) throws IOException {
        try (InputStream in = Files.newInputStream(f)) {
            return new String(in.readNBytes(HEAD), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    /** The unload rule: deletes f unless it is a kept companion file. True when it deleted it. */
    public static boolean deleteOwn(Path f, String dim) throws IOException {
        String h = head(f);
        if (h == null || keep(h, dim)) return false;
        return Files.deleteIfExists(f);
    }

    /** The wipe rule over a folder: the bot's files (and temp files) go, the companion's of this dim stay. Returns {deleted, kept}. */
    public static int[] wipe(Path dir, String dim) throws IOException {
        int deleted = 0, kept = 0;
        if (!Files.isDirectory(dir)) return new int[] {0, 0};
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json*")) {
            for (Path f : ds) {
                if (f.getFileName().toString().endsWith(".json") && dim != null && keep(head(f), dim)) kept++;
                else if (Files.deleteIfExists(f)) deleted++;
            }
        }
        return new int[] {deleted, kept};
    }

    /** {own, companion} file counts of a folder (status). */
    public static int[] count(Path dir) throws IOException {
        int own = 0, comp = 0;
        if (dir == null || !Files.isDirectory(dir)) return new int[] {0, 0};
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path f : ds) {
                String h = head(f);
                if (h == null) continue;
                if (companion(h)) comp++;
                else own++;
            }
        }
        return new int[] {own, comp};
    }
}
