package io.github.mojolowjo.entropybot.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 0.31.0 (c): per-world mod state. World-specific files (areas, places/chests/rs, POIs, caves, the ore notes, the
 * restore ledger, the sense summaries, map tiles, route tiles and penalties, known air, seen faces) live under
 * {@code entropybot/worlds/<key>/}; everything else (the bridge's and dashboard's control files: fast, pm, commands,
 * owner, brain, blind, recorder, watch, stock, ...) stays at the root. Plain Java (no game classes): the key, which
 * names are world files, and the one-time migration of the root's world files for the first multiplayer server.
 * Loader API: none (java.nio only).
 */
public final class WorldState {
    private WorldState() {}

    public static final String WORLDS = "worlds";
    /** Written once the root's old world files were copied into a multiplayer world's folder (so it happens once). */
    public static final String MIGRATED = "migrated.json";
    /** At the root: which world folder is current (bridge.ps1 and the dashboard read it). */
    public static final String POINTER = "world.json";

    /** Base names (before the first dot) of the world's files at the root. */
    static final Set<String> FILES = Set.of("areas", "places", "chests", "rs", "pois", "caves", "ores", "explored",
            "restore", "knownair", "seenfaces", "commands-world", "corpses");
    /** 0.31.0: commands.json's world-bound keys (they live in the world's commands-world.json). */
    public static final Set<String> COMMANDS_WORLD_KEYS = Set.of("home", "mine", "mines", "minesMoved", "deaths", "lastDeath",
            "parked", "zone", "farm", "furnaces", "released", "releasedAt", "run");
    public static final String COMMANDS_WORLD = "commands-world.json";

    /** Folders that are the world's. */
    static final Set<String> DIRS = Set.of("sense", "map", "routes", "route");

    /** A folder-safe key: letters, digits, {@code _ - .} only; other characters become {@code _}. Null when nothing is left. */
    public static String sanitize(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replaceAll("[^A-Za-z0-9_.-]", "_").replaceAll("_+", "_");
        while (s.startsWith(".")) s = s.substring(1);
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        if (s.length() > 64) s = s.substring(0, 64);
        return s.isEmpty() ? null : s;
    }

    /** The key: the singleplayer level folder's name, or the server's host_port (25565 when no port is given). */
    public static String key(boolean multiplayer, String levelFolder, String serverAddress) {
        if (!multiplayer) return sanitize(levelFolder);
        if (serverAddress == null || serverAddress.isBlank()) return null;
        String a = serverAddress.trim().toLowerCase();
        int c = a.lastIndexOf(':');
        if (c < 0 || a.indexOf(']') > c) a = a + ":25565";
        return sanitize(a.replace(':', '_'));
    }

    /** True when the name (a relative path like {@code areas.bak.json} or {@code sense/overworld/1.2.json}) is a world file. */
    public static boolean isWorldName(String name) {
        if (name == null || name.isEmpty()) return false;
        String n = name.replace('\\', '/');
        int slash = n.indexOf('/');
        if (slash >= 0) return DIRS.contains(n.substring(0, slash));
        if (DIRS.contains(n)) return true;
        int dot = n.indexOf('.');
        return FILES.contains(dot < 0 ? n : n.substring(0, dot));
    }

    /**
     * Makes {@code root/worlds/<key>} and, for the first multiplayer world seen (no {@link #MIGRATED} yet), copies the
     * root's world files into it (existing targets are left alone). Lines for the log; throws on a write problem
     * (the caller logs it loudly; the marker is only written after a full copy, so the next join tries again).
     */
    /**
     * The main server (the real bot's server) is named only by {@code entropybot/main-server.txt} (host:port), which
     * bridge.ps1 writes from its config.json; no address lives in the (public) source. Without it nothing migrates.
     */
    public static final String MAIN_FILE = "main-server.txt";

    /** The main server's key: main-server.txt's first line when present and valid, else null (no migration). */
    public static String mainKey(Path root) {
        try {
            Path f = root.resolve(MAIN_FILE);
            if (Files.exists(f)) {
                String k = key(true, null, Files.readString(f, StandardCharsets.UTF_8).lines().findFirst().orElse(""));
                if (k != null) return k;
            }
        } catch (IOException | RuntimeException ignored) {}
        return null;
    }

    /** As {@link #enter(Path, String, boolean, String)} with the main server from {@link #mainKey}. */
    public static List<String> enter(Path root, String key, boolean multiplayer) throws IOException {
        return enter(root, key, multiplayer, mainKey(root));
    }

    /**
     * review 1: the migration goes only into the main server's folder (key == mainKey), never a LAN world or another
     * server (they start empty, and the root's files stay for the main server).
     */
    public static List<String> enter(Path root, String key, boolean multiplayer, String mainKey) throws IOException {
        List<String> out = new ArrayList<>();
        if (sanitize(key) == null || !key.equals(sanitize(key))) throw new IOException("bad world key '" + key + "'");
        Path worlds = root.resolve(WORLDS), dir = worlds.resolve(key);
        boolean existed = Files.isDirectory(dir);
        Files.createDirectories(dir);
        if (existed) out.add("world folder worlds/" + key + " (existing)");
        else out.add("world folder worlds/" + key + " (new)");
        Path marker = worlds.resolve(MIGRATED);
        if (!multiplayer) {
            if (!existed) out.add("singleplayer world: starts empty (the root's files are not copied)");
            return out;
        }
        if (Files.exists(marker)) return out;
        if (mainKey == null) {
            out.add("no main-server.txt (bridge.ps1 writes it): nothing migrated yet, the root's files are kept");
            return out;
        }
        if (!key.equals(mainKey)) {
            if (!existed) out.add("not the main server (" + mainKey + "): starts empty, the root's files are kept for it");
            return out;
        }
        int files = 0, skipped = 0;
        try (Stream<Path> s = Files.list(root)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString();
                if (n.equals(WORLDS) || n.endsWith(".tmp") || !isWorldName(n)) continue;
                int[] c = copyTree(p, dir.resolve(n));
                files += c[0];
                skipped += c[1];
            }
        }
        Files.writeString(marker, "{\"key\":\"" + key + "\",\"at\":" + System.currentTimeMillis() + ",\"files\":" + files + "}",
                StandardCharsets.UTF_8);
        out.add("migrated the root's world files into worlds/" + key + ": " + files + " copied"
                + (skipped > 0 ? ", " + skipped + " already there left alone" : "") + " (the root copies stay as a backup)");
        return out;
    }

    private static int[] copyTree(Path from, Path to) throws IOException {
        int[] c = new int[2];
        if (Files.isDirectory(from)) {
            Files.createDirectories(to);
            try (Stream<Path> s = Files.walk(from)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    Path t = to.resolve(from.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(t);
                    else if (Files.exists(t)) c[1]++;
                    else { Files.copy(p, t, StandardCopyOption.COPY_ATTRIBUTES); c[0]++; }
                }
            }
        } else if (Files.exists(to)) c[1]++;
        else { Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES); c[0]++; }
        return c;
    }

    /** The pointer file's JSON. */
    public static String pointerJson(String key, boolean multiplayer) {
        return "{\"key\":\"" + key + "\",\"dir\":\"" + WORLDS + "/" + key + "\",\"multiplayer\":" + multiplayer + "}";
    }
}
