package io.github.mojolowjo.entropybot.io;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

/**
 * JSON files under the bot's own folder, written atomically (a temp file, then a move), which the
 * KubeJS script cannot do itself. Names are relative paths like {@code places.json} or
 * {@code runs/run_a.json}: letters, digits, {@code _ - .} and single {@code /} separators, ending in
 * {@code .json}, never {@code ..}.
 */
public final class BotFiles {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.-]*(/[A-Za-z0-9_][A-Za-z0-9_.-]*)*\\.json$");

    private final Path root;

    public BotFiles(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() { return root; }

    /** Null when the name is fine, else the problem. */
    public static String validate(String name) {
        if (name == null || !NAME.matcher(name).matches()) return "bad file name (use letters, digits, _ - . and / and end in .json)";
        if (name.contains("..")) return "bad file name";
        return null;
    }

    /** Writes the JSON (checked to parse) atomically. "ok: N bytes" or "error: ...". */
    public String writeJson(String name, String json) {
        String bad = validate(name);
        if (bad != null) return "error: " + bad;
        if (json == null) return "error: nothing to write";
        try {
            JsonParser.parseString(json);
        } catch (RuntimeException e) {
            return "error: not valid JSON (" + e.getMessage() + ")";
        }
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root)) return "error: bad file name";
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return "ok: " + bytes.length + " bytes";
        } catch (IOException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            return "error: " + e.getMessage();
        }
    }

    /**
     * 0.28.0: writes text atomically (a temp file, then a move; a plain replace where the move can't be atomic) to a file
     * under this root whose name the {@link #writeJson} pattern would refuse (the chunk pass's {@code sense/<dim>/-3.4.json}).
     * The target must lie under the root. Throws IOException (the caller counts and logs it).
     */
    public void writeAtomic(Path target, String text) throws IOException {
        Path t = target.toAbsolutePath().normalize();
        if (!t.startsWith(root)) throw new IOException("not under " + root + ": " + t);
        writeAtomicAnywhere(t, text);
    }

    /** As {@link #writeAtomic}, without the root check (plain Java callers and tests with their own folder). */
    public static void writeAtomicAnywhere(Path target, String text) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw e;
        }
    }

    /** The file's text, null when it does not exist, "error: ..." when it cannot be read. */
    public String readJson(String name) {
        String bad = validate(name);
        if (bad != null) return "error: " + bad;
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root)) return "error: bad file name";
        if (!Files.exists(target)) return null;
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }
}
