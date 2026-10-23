package io.github.mojolowjo.entropybot.screen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 0.39.z24 the config screen's dashboard: the same process {@code bridge.ps1 dashboard} launches
 * ({@code javaw Dashboard.java "<game dir>" 8765} in the dashboard folder, its pid in {@code server.pid}, output in
 * {@code server.log} / {@code server.err.log}), started and stopped by the mod itself. OFF by default: the mod never
 * starts it on its own. The bridge and the screen share the pid file, so either one can stop what the other started.
 *
 * <p>Config (entropybot/screen.json): {@code dashboardDir} (default: the folder of the key file named in
 * entropybot/fast.json, i.e. the bot project's dashboard folder the bridge uses) and {@code dashboardJava}
 * (default: the JDK 21 javaw the bridge uses). Plain Java: no loader API. The key is read only for the clipboard link
 * and never logged.
 */
public final class DashboardProcess {
    /** Lazy: the pure rules stay usable in JUnit, where no logger is on the class path. */
    private static Logger log() { return org.slf4j.LoggerFactory.getLogger(DashboardProcess.class); }
    public static final int PORT = 8765; // structural: the bridge's port
    public static final String DEFAULT_JAVA = "C:\\Program Files\\Java\\jdk-21\\bin\\javaw.exe"; // structural: the bridge's javaw

    private DashboardProcess() {}

    // ---- pure ----

    /** Why the folder can't be used, or null. */
    public static String folderProblem(Path dir, Predicate<Path> exists) {
        if (dir == null) return "no dashboard folder (set dashboardDir in entropybot\\screen.json, or run any bridge.ps1 command once)";
        if (!exists.test(dir.resolve("Dashboard.java"))) return "no Dashboard.java in " + dir;
        return null;
    }

    public static String javaProblem(Path java, Predicate<Path> exists) {
        if (java == null || !exists.test(java)) return "java not found at " + java + " (dashboardJava in entropybot\\screen.json)";
        return null;
    }

    /** The pid in server.pid's text, or null. */
    public static Long readPid(String text) {
        if (text == null) return null;
        try { return Long.parseLong(text.trim()); } catch (NumberFormatException e) { return null; }
    }

    /** The status line: problem first, then the pid file and whether its process is a live java. */
    public static String statusText(String problem, Long pid, boolean aliveJava) {
        if (problem != null) return "dashboard: " + problem;
        if (pid == null) return "dashboard: off";
        if (!aliveJava) return "dashboard: off (old pid file, process " + pid + " is gone)";
        return "dashboard: running on port " + PORT + " (pid " + pid + ")";
    }

    public static List<String> command(Path java, Path gameDir, int port) {
        return List.of(java.toString(), "Dashboard.java", gameDir.toString(), String.valueOf(port));
    }

    public static String link(String host, int port, String key) {
        return "http://" + host + ":" + port + "/?key=" + key;
    }

    // ---- config ----

    public record Where(Path dir, Path java) {}

    /** entropybot/screen.json, else fast.json's key folder; java from screen.json, else the bridge's javaw. */
    public static Where where(Path botDir) {
        Path dir = null, java = Path.of(DEFAULT_JAVA);
        try {
            JsonObject s = json(botDir == null ? null : botDir.resolve("screen.json"));
            if (s != null && s.has("dashboardDir")) dir = Path.of(s.get("dashboardDir").getAsString());
            if (s != null && s.has("dashboardJava")) java = Path.of(s.get("dashboardJava").getAsString());
            if (dir == null) {
                JsonObject f = json(botDir == null ? null : botDir.resolve("fast.json"));
                if (f != null && f.has("keyFile")) dir = Path.of(f.get("keyFile").getAsString()).getParent();
            }
        } catch (RuntimeException e) {
            log().warn("[entropybot] dashboard: screen.json / fast.json unreadable: {}", e.toString());
        }
        return new Where(dir, java);
    }

    private static JsonObject json(Path f) {
        if (f == null || !Files.exists(f)) return null;
        try {
            return JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            log().warn("[entropybot] dashboard: couldn't read {}: {}", f.getFileName(), e.toString());
            return null;
        }
    }

    // ---- the process ----

    static Long pid(Path dir) {
        try {
            Path p = dir.resolve("server.pid");
            return Files.exists(p) ? readPid(Files.readString(p, StandardCharsets.UTF_8)) : null;
        } catch (IOException | RuntimeException e) {
            log().warn("[entropybot] dashboard: server.pid unreadable: {}", e.toString());
            return null;
        }
    }

    static Optional<ProcessHandle> liveJava(Long pid) {
        if (pid == null) return Optional.empty();
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
                .filter(h -> h.info().command().map(c -> c.toLowerCase().contains("java")).orElse(false));
    }

    public static String status(Path botDir) {
        Where w = where(botDir);
        String problem = folderProblem(w.dir(), Files::exists);
        if (problem == null) problem = javaProblem(w.java(), Files::exists);
        Long pid = problem == null ? pid(w.dir()) : null;
        return statusText(problem, pid, liveJava(pid).isPresent());
    }

    /** Start: what to show (an error starts with "error: "). */
    public static String start(Path botDir, Path gameDir) {
        Where w = where(botDir);
        String problem = folderProblem(w.dir(), Files::exists);
        if (problem == null) problem = javaProblem(w.java(), Files::exists);
        if (problem != null) return "error: " + problem;
        Long old = pid(w.dir());
        if (liveJava(old).isPresent()) return "dashboard already running (pid " + old + ")";
        try {
            Process p = new ProcessBuilder(command(w.java(), gameDir, PORT)).directory(w.dir().toFile())
                    .redirectOutput(w.dir().resolve("server.log").toFile())
                    .redirectError(w.dir().resolve("server.err.log").toFile()).start();
            Files.writeString(w.dir().resolve("server.pid"), String.valueOf(p.pid()), StandardCharsets.UTF_8);
            log().info("[entropybot] dashboard: started (pid {}) from the config screen", p.pid());
            return "dashboard started (pid " + p.pid() + "); it answers in a few seconds";
        } catch (IOException | RuntimeException e) {
            log().warn("[entropybot] dashboard: start failed: {}", e.toString());
            return "error: dashboard did not start: " + e.getMessage();
        }
    }

    public static String stop(Path botDir) {
        Where w = where(botDir);
        String problem = folderProblem(w.dir(), Files::exists);
        if (problem != null) return "error: " + problem;
        Long pid = pid(w.dir());
        Optional<ProcessHandle> h = liveJava(pid);
        try {
            if (h.isEmpty()) {
                Files.deleteIfExists(w.dir().resolve("server.pid"));
                return "dashboard was not running";
            }
            h.get().destroy();
            Files.deleteIfExists(w.dir().resolve("server.pid"));
            log().info("[entropybot] dashboard: stopped (pid {}) from the config screen", pid);
            return "dashboard stopped";
        } catch (IOException | RuntimeException e) {
            log().warn("[entropybot] dashboard: stop failed: {}", e.toString());
            return "error: dashboard stop failed: " + e.getMessage();
        }
    }

    /** The full link with the key for the clipboard; an "error: ..." text when the key can't be read. Never logged. */
    public static String linkWithKey(Path botDir) {
        Where w = where(botDir);
        if (w.dir() == null) return "error: no dashboard folder";
        try {
            String key = Files.readString(w.dir().resolve("key.txt"), StandardCharsets.UTF_8).trim();
            if (key.isEmpty()) return "error: key.txt is empty";
            return link(lanHost(), PORT, key);
        } catch (IOException | RuntimeException e) {
            return "error: no key.txt in the dashboard folder";
        }
    }

    /** The first site-local IPv4 of this computer (for another computer on the LAN), else localhost. */
    static String lanHost() {
        try {
            Enumeration<NetworkInterface> ns = NetworkInterface.getNetworkInterfaces();
            while (ns != null && ns.hasMoreElements()) {
                NetworkInterface n = ns.nextElement();
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : java.util.Collections.list(n.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
            }
        } catch (IOException | RuntimeException e) {
            log().warn("[entropybot] dashboard: no LAN address, using localhost: {}", e.toString());
        }
        return "localhost";
    }
}
