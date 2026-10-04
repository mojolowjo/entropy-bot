package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.cave.Caves;
import io.github.mojolowjo.entropybot.cave.MineNotes;
import io.github.mojolowjo.entropybot.memory.Knowledge;
import io.github.mojolowjo.entropybot.poi.Pois;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * B7e (E1): the game side of "memory [status|fresh]" ({@link MemoryStatus} has the rules and the wording): what each
 * of the mod's stores holds, when its file and backup were written (the files' times), and what went wrong at load.
 */
public final class MemoryCommand {
    private MemoryCommand() {}

    /** The verb (owner; guests are refused before it). */
    public static String command(Core core, Commands c, String rest) {
        return MemoryStatus.command(rest, () -> {
            List<MemoryStatus.Store> stores = gather(core, c);
            if (stores == null) return "memory: not in a world yet";
            return MemoryStatus.text(stores, System.currentTimeMillis(), sessionStart());
        });
    }

    /** state.json's "memory" block for writeState (E2): state, problem, savedAt, backupAt. */
    public static JsonObject stateBlock(Core core, Commands c) {
        List<MemoryStatus.Store> stores = gather(core, c);
        if (stores == null) {
            JsonObject o = new JsonObject();
            o.addProperty("state", "ok");
            return o;
        }
        return MemoryStatus.stateBlock(stores);
    }

    static long sessionStart() {
        try { return java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime(); } catch (Throwable t) { return 0; }
    }

    static List<MemoryStatus.Store> gather(Core core, Commands c) {
        if (core.files() == null) return null;
        Path root = core.files().root();
        long start = sessionStart();
        List<MemoryStatus.Store> out = new ArrayList<>();
        String kn = core.knowledge.loadNote();
        out.add(store(root, Knowledge.PLACES, core.knowledge.places().size() + " places", kn, start));
        out.add(store(root, Knowledge.CHESTS, core.knowledge.chests().size() + " chests", kn, start));
        out.add(store(root, Knowledge.RS, core.knowledge.rs().size() + " RS readings", kn, start));
        for (JsonStore js : c.stores()) out.add(store(root, js.name(), what(js), js.loadNote(), start));
        out.add(store(root, Pois.FILE, core.pois.size() + " points of interest", null, start));
        out.add(store(root, Caves.FILE, core.caves.size() + " caves", null, start));
        out.add(store(root, MineNotes.ORES, core.mineNotes.oreCount() + " listed ores", null, start));
        out.add(store(root, MineNotes.EXPLORED, core.mineNotes.exploredCount() + " explored chunks", null, start));
        return out;
    }

    private static MemoryStatus.Store store(Path root, String file, String what, String loadNote, long start) {
        long broken = mtime(root.resolve(file.replace(".json", ".broken.json")));
        return new MemoryStatus.Store(file, what, mtime(root.resolve(file)), mtime(root.resolve(file.replace(".json", ".bak.json"))), broken,
                MemoryStatus.problem(file, loadNote, broken, start));
    }

    private static String what(JsonStore js) {
        JsonObject d = js.data();
        switch (js.name()) {
            case "pm.json" -> { return (d.has("allowed") && d.get("allowed").isJsonArray() ? d.getAsJsonArray("allowed").size() : 0) + " allowed players"; }
            case "commands.json" -> { return size(d, "routines") + " routines, " + size(d, "rules") + " rules"; }
            case "areas.json" -> { return size(d, "areas") + " areas, " + size(d, "protect") + " protect boxes"; }
            default -> { return d.size() + " keys"; }
        }
    }

    private static int size(JsonObject d, String key) {
        if (!d.has(key)) return 0;
        if (d.get(key).isJsonArray()) return d.getAsJsonArray(key).size();
        if (d.get(key).isJsonObject()) return d.getAsJsonObject(key).size();
        return 0;
    }

    private static long mtime(Path p) {
        try { return Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() : -1; } catch (Exception e) { return -1; }
    }
}
