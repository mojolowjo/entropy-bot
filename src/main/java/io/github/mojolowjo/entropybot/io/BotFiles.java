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
