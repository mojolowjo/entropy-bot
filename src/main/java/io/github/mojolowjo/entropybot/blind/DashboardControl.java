package io.github.mojolowjo.entropybot.blind;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The config screen's dashboard buttons (0.26.0, DEVNOTES roadmap 9), plain Java (no game or loader classes; JUnit
 * drives the pure parts). The bot project is found through {@code entropybot/fast.json}: its {@code keyFile} is
 * {@code <project>\dashboard\key.txt}, so the project is that path's grandparent, and the buttons run
 * {@code powershell.exe -NoProfile -ExecutionPolicy Bypass -File <project>\bridge.ps1 dashboard [stop]} as a separate
 * process (its output is discarded: the bridge prints the dashboard link with the key). The key is never read here,
 * never shown, never logged: the link on the screen is {@code ?key=****}.
 */
public final class DashboardControl {
    public static final int DASHBOARD_PORT = 8765, CONNECT_MS = 300, START_WAIT_MS = 10_000;

    private DashboardControl() {}

    /** Where the bridge script is, or why the buttons are off. */
    public record Project(Path dir, Path script, String problem) {
        public boolean ok() { return problem == null; }
    }

    /** Pure-ish: reads fast.json in the entropybot folder and finds the project and its bridge.ps1. */
    public static Project find(Path entropybotDir) {
        if (entropybotDir == null) return new Project(null, null, "no entropybot folder yet (start the game once)");
        Path f = entropybotDir.resolve("fast.json");
        if (!Files.exists(f)) return new Project(null, null, "no entropybot\\fast.json (run any bridge.ps1 command once, e.g. bridge.ps1 status)");
        String text;
        try {
            text = Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return new Project(null, null, "couldn't read fast.json: " + e.getMessage());
        }
        return fromFastJson(text, p -> Files.exists(p));
    }

    /** Pure: the project from fast.json's text; exists says whether a file is there. */
    public static Project fromFastJson(String text, java.util.function.Predicate<Path> exists) {
        String keyFile;
        try {
            JsonElement e = JsonParser.parseString(text == null ? "" : text);
            JsonObject o = e.getAsJsonObject();
            if (!o.has("keyFile") || !o.get("keyFile").isJsonPrimitive()) return new Project(null, null, "fast.json has no keyFile");
            keyFile = o.get("keyFile").getAsString();
        } catch (RuntimeException x) {
            return new Project(null, null, "fast.json is not a JSON object");
        }
        Path key;
        try {
            key = Path.of(keyFile);
        } catch (RuntimeException x) {
            return new Project(null, null, "fast.json's keyFile is not a path");
        }
        Path dash = key.getParent();
        Path dir = dash == null ? null : dash.getParent();
        if (dir == null) return new Project(null, null, "fast.json's keyFile has no project folder above it");
        Path script = dir.resolve("bridge.ps1");
        if (!exists.test(script)) return new Project(dir, script, "no bridge.ps1 in " + dir);
        return new Project(dir, script, null);
    }

    /** Pure: the command line for a button (stop = "dashboard stop"). */
    public static List<String> command(Path script, boolean stop) {
        return stop ? List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString(), "dashboard", "stop")
                : List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString(), "dashboard");
    }

    /** Pure: the link the screen shows, key masked. */
    public static String maskedLink(String host, int port) {
        return "http://" + host + ":" + port + "/?key=****";
    }

    /** Whether something answers on 127.0.0.1:port within CONNECT_MS (blocks up to that long: call it off the game thread). */
    public static boolean answers(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_MS);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Pure: the status line. up null = not checked yet. */
    public static String statusLine(Boolean up, String host) {
        if (up == null) return "dashboard: checking port " + DASHBOARD_PORT + "...";
        return up ? "dashboard: up on port " + DASHBOARD_PORT + " - " + maskedLink(host, DASHBOARD_PORT)
                : "dashboard: not running (nothing answers on port " + DASHBOARD_PORT + ")";
    }
}
