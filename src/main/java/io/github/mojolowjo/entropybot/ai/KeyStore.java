package io.github.mojolowjo.entropybot.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 0.35.0: the API keys, {@code <gameDir>/entropybot/ai-keys.json} (per game instance, never in the world or the repo),
 * readable by the owner's Windows account only (an ACL with one entry; rw------- on POSIX). Without a key in the file
 * the environment is the fallback: ENTROPYBOT_OPENROUTER_KEY / OPENROUTER_API_KEY, ENTROPYBOT_OPENAI_KEY /
 * OPENAI_API_KEY. A key is never logged or shown: only {@link Secret#hint()}. Plain Java (java.nio).
 *
 * <p>Honest limit: the ACL stops other Windows users, not other programs run by the same user.
 */
public final class KeyStore {
    private final Path file;
    private final Function<String, String> env;
    private volatile String permsNote;

    public KeyStore(Path file, Function<String, String> env) {
        this.file = file;
        this.env = env == null ? k -> null : env;
    }

    public Path file() { return file; }

    /** null: tightened (or not tried yet); else why the file's permissions could not be made owner-only. */
    public String permsNote() { return permsNote; }

    static List<String> envNames(String provider) {
        return switch (provider) {
            case "openrouter" -> List.of("ENTROPYBOT_OPENROUTER_KEY", "OPENROUTER_API_KEY");
            case "openai" -> List.of("ENTROPYBOT_OPENAI_KEY", "OPENAI_API_KEY");
            case "anthropic" -> List.of("ENTROPYBOT_ANTHROPIC_KEY", "ANTHROPIC_API_KEY");
            default -> List.of();
        };
    }

    private JsonObject read() {
        try {
            if (file == null || !Files.exists(file)) return new JsonObject();
            JsonElement e = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (IOException | RuntimeException e) {
            return new JsonObject();
        }
    }

    /** The key from the file, else from the environment, else {@link Secret#NONE}. */
    public Secret key(String provider) {
        JsonObject o = read();
        String p = provider.toLowerCase(Locale.ROOT);
        if (o.has(p) && o.get(p).isJsonPrimitive() && !o.get(p).getAsString().isBlank()) return Secret.of(o.get(p).getAsString());
        for (String n : envNames(p)) {
            String v = env.apply(n);
            if (v != null && !v.isBlank()) return Secret.of(v);
        }
        return Secret.NONE;
    }

    /** "set (…ab12)", "from environment (…ab12)", "missing", "not needed". */
    public String source(String provider) {
        if (provider.equals("local") || provider.equals("demo")) {
            Secret k = key(provider);
            return k.isEmpty() ? "not needed" : "set (" + k.hint() + ")";
        }
        JsonObject o = read();
        String p = provider.toLowerCase(Locale.ROOT);
        if (o.has(p) && o.get(p).isJsonPrimitive() && !o.get(p).getAsString().isBlank()) return "set (" + Secret.of(o.get(p).getAsString()).hint() + ")";
        for (String n : envNames(p)) {
            String v = env.apply(n);
            if (v != null && !v.isBlank()) return "from environment " + n + " (" + Secret.of(v).hint() + ")";
        }
        return "missing";
    }

    /** Stores (or with a blank key removes) the provider's key, then makes the file owner-only. Returns the perms note. */
    public synchronized String set(String provider, String key) throws IOException {
        JsonObject o = read();
        String p = provider.toLowerCase(Locale.ROOT);
        String k = key == null ? "" : key.trim();
        if (k.isEmpty()) o.remove(p);
        else o.addProperty(p, k);
        Files.createDirectories(file.toAbsolutePath().getParent());
        if (!Files.exists(file)) {
            Files.writeString(file, "{}", StandardCharsets.UTF_8);
        }
        tighten(file);                                     // before the key is in it
        Files.writeString(file, o.toString(), StandardCharsets.UTF_8);
        return permsNote;
    }

    /** Owner-only permissions; failure is not fatal (it shows in ai status). */
    void tighten(Path f) {
        try {
            AclFileAttributeView acl = Files.getFileAttributeView(f, AclFileAttributeView.class);
            if (acl != null) {
                UserPrincipal owner = Files.getFileAttributeView(f, FileOwnerAttributeView.class).getOwner();
                AclEntry e = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
                List<AclEntry> one = new ArrayList<>();
                one.add(e);
                acl.setAcl(one);                           // replaces the inherited entries too
                permsNote = null;
                return;
            }
            PosixFileAttributeView px = Files.getFileAttributeView(f, PosixFileAttributeView.class);
            if (px != null) {
                px.setPermissions(PosixFilePermissions.fromString("rw-------"));
                permsNote = null;
                return;
            }
            permsNote = "not tightened - this file system has no ACLs or POSIX permissions";
        } catch (IOException | RuntimeException e) {
            permsNote = "not tightened - " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
