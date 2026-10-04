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
    /** The dashboard's address on the laptop, e.g. {@code http://10.0.0.181:8765}. */
    public String url = "http://10.0.0.181:8765";
    /** The dashboard key (the part after {@code ?key=} in the link {@code bridge.ps1 dashboard} prints). */
    public String key = "";
    /** Seconds between posts (1 to 60). */
    public int intervalSeconds = 2;

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
        return this;
    }

    /** True when posts should go out: switched on, with an address and a key. */
    public boolean active() {
        return enabled && !url.isEmpty() && !key.isEmpty() && endpoint() != null;
    }

    /** {@code <url>/api/owner}, or null when the url is not an http(s) address. */
    public URI endpoint() {
        try {
            String u = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            URI uri = URI.create(u + "/api/owner");
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
                + ", intervalSeconds=" + intervalSeconds + "}";
    }
}