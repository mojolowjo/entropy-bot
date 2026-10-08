package io.github.mojolowjo.entropycompanion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code config/entropy-companion.json}. Created with these defaults on the first run; read again when the file
 * changes, so the owner can switch it off or fix the key without restarting the game. Nothing is sent while the
 * url or the key is empty. The key is never logged: {@link #toString()} hides it.
 */
public final class CompanionConfig {
    public static final String FILE_NAME = "entropy-companion.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Master switch. */
    public boolean enabled = true;
    /** The dashboard's address on the laptop, e.g. {@code http://192.168.1.20:8765} (empty until the owner fills it in). */
    public String url = "";
    /** The dashboard key (the part after {@code ?key=} in the link {@code bridge.ps1 dashboard} prints). */
    public String key = "";
    /** Seconds between posts (1 to 60). */
    public int intervalSeconds = 2;
    /** 0.2.0: when set, commands are only sent while this account is logged in (a shared PC). Empty = any account. */
    public String ownerName = "";
    /** 0.2.0: the short form of /bot (default "b"; empty = none, for a server that has its own /b). */
    public String commandAlias = "b";
    /** 0.2.0: point-and-command: how many ores "mine" asks for, how many logs "chop" asks for. */
    public int pointMineCount = 8;
    public int pointChopCount = 16;
    /** 0.2.0: seconds a reply stays in the overlay (0 to 30; 0 = chat only). */
    public int overlaySeconds = 6;
    /** 0.3.0: share the surface of the chunks this client has loaded with the bot (through the dashboard). */
    public boolean shareChunks = true;
    /** 0.4.0: record the owner's action log (config/entropy-companion/log/, and posted to the dashboard when set up). */
    public boolean actionLog = true;

    /**
     * 0.3.0: sets shareChunks in the file, keeping every other value as written (the file is read again on its next
     * change). Returns null when saved, else why not.
     */
    public static String saveShareChunks(Path file, boolean on) {
        return saveBoolean(file, "shareChunks", on);
    }

    /** 0.4.0: sets one true/false value in the file, keeping every other value. Returns null when saved, else why not. */
    public static String saveBoolean(Path file, String name, boolean on) {
        try {
            com.google.gson.JsonObject o = Files.exists(file)
                    ? com.google.gson.JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject()
                    : GSON.toJsonTree(new CompanionConfig()).getAsJsonObject();
            o.addProperty(name, on);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(o) + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return null;
        } catch (IOException | RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    /** Reads the file, writing the defaults first when there is none. A broken file gives the defaults and is left alone. */
    public static CompanionConfig load(Path file) {
        try {
            if (!Files.exists(file)) {
                if (file.getParent() != null) Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(new CompanionConfig()) + "\n", StandardCharsets.UTF_8);
                return new CompanionConfig();
            }
            CompanionConfig c = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), CompanionConfig.class);
            return c == null ? new CompanionConfig() : c.normalised();
        } catch (IOException | RuntimeException e) {
            CompanionConfig c = new CompanionConfig();
            c.enabled = false; // an unreadable file never starts sending with the defaults
            return c;
        }
    }

    /** Fills a missing value and clamps the interval. */
    CompanionConfig normalised() {
        if (url == null) url = "";
        if (key == null) key = "";
        url = url.trim();
        key = key.trim();
        intervalSeconds = Math.max(1, Math.min(60, intervalSeconds));
        if (ownerName == null) ownerName = "";
        ownerName = ownerName.trim();
        if (commandAlias == null) commandAlias = "";
        commandAlias = commandAlias.trim();
        if (!commandAlias.matches("[a-z0-9_]{0,16}") || commandAlias.equals("bot")) commandAlias = "b";
        pointMineCount = Math.max(1, Math.min(64, pointMineCount));
        pointChopCount = Math.max(1, Math.min(256, pointChopCount));
        overlaySeconds = Math.max(0, Math.min(30, overlaySeconds));
        return this;
    }

    /** True when this account may send commands (no ownerName set, or it matches, ignoring case). */
    public boolean accountOk(String account) {
        return ownerName.isEmpty() || ownerName.equalsIgnoreCase(account);
    }

    /** True when posts should go out: switched on, with an address and a key. */
    public boolean active() {
        return enabled && !url.isEmpty() && !key.isEmpty() && endpoint() != null;
    }

    /** {@code <url>/api/owner}, or null when the url is not an http(s) address. */
    public URI endpoint() {
        return apiUri("/api/owner");
    }

    /** {@code <url><path>}, or null when the url is not an http(s) address. */
    public URI apiUri(String path) {
        try {
            String u = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            URI uri = URI.create(u + path);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return null;
            return uri.getHost() == null ? null : uri;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "CompanionConfig{enabled=" + enabled + ", url=" + url + ", key=" + (key.isEmpty() ? "(empty)" : "(set)")
                + ", intervalSeconds=" + intervalSeconds + ", ownerName=" + (ownerName.isEmpty() ? "(any)" : ownerName)
                + ", commandAlias=" + (commandAlias.isEmpty() ? "(off)" : commandAlias) + "}";
    }
}