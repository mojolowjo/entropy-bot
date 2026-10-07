package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * B7e (E1): "memory [status]" from the mod's own stores (pm/commands/areas.json, places/chests/rs.json, pois, caves,
 * ores, explored), replacing the bridge's memory.json report. Each store writes its file atomically, keeps a
 * {@code *.bak.json} and sets a broken file aside as {@code *.broken.json} (loading the backup where it has one), so
 * the bridge's READ-ONLY state and "memory fresh" can't happen any more. Pure: the text from what the stores say.
 */
public final class MemoryStatus {
    private MemoryStatus() {}

    /**
     * One file: its name, what it holds ("12 places"), when it was last written, its backup and its set-aside broken
     * copy (epoch ms, -1 = none), and the problem found when it was loaded this session (null = none).
     */
    public record Store(String file, String what, long savedAt, long backupAt, long brokenAt, String problem) {}

    public static final String USAGE = "usage: memory [status]";

    public static final String FRESH = "\"memory fresh\" is gone: my notes are in separate files now, and a file that can't be read is set aside "
            + "as *.broken.json and its backup loaded (or it starts empty) by itself, so they never go read-only. debug memory shows how they are";

    /**
     * The problem a store had at load: the part of its load line about this file when that says "broken" ("broken,
     * loaded the backup (12 entries)"), else "broken at start, set aside, started empty" when its broken copy was
     * written this session (the stores without a backup), else null.
     */
    public static String problem(String file, String loadNote, long brokenAt, long sessionStart) {
        if (loadNote != null) {
            for (String part : loadNote.split(";\\s*")) {
                String p = part.trim();
                if (p.startsWith(file + ":") && p.contains("broken")) return p.substring(file.length() + 1).trim();
            }
        }
        if (brokenAt >= 0 && brokenAt >= sessionStart) return "broken at start, set aside, started empty";
        return null;
    }

    /** "ok", "backup" (a broken file was replaced by its backup) or "broken" (one started empty). */
    public static String state(List<Store> stores) {
        String s = "ok";
        for (Store st : stores) {
            if (st.problem() == null) continue;
            if (!st.problem().contains("loaded the backup")) return "broken";
            s = "backup";
        }
        return s;
    }

    public static String text(List<Store> stores, long now, long sessionStart) {
        List<String> problems = new ArrayList<>(), files = new ArrayList<>(), older = new ArrayList<>();
        for (Store st : stores) {
            String broken = st.file().replace(".json", ".broken.json");
            if (st.problem() != null) problems.add(st.file() + " " + st.problem() + " (the unreadable copy is " + broken + ")");
            else if (st.brokenAt() >= 0) older.add(broken + " (" + Texts.ago(st.brokenAt(), now) + ")");
            StringBuilder f = new StringBuilder(st.file()).append(' ').append(st.what());
            f.append(st.savedAt() >= 0 ? ", saved " + Texts.ago(st.savedAt(), now) : ", not written yet");
            if (st.backupAt() >= 0) f.append(", backup ").append(Texts.ago(st.backupAt(), now));
            files.add(f.toString());
        }
        StringBuilder sb = new StringBuilder("memory: ");
        sb.append(problems.isEmpty() ? "ok" : problems.size() + (problems.size() == 1 ? " problem: " : " problems: ") + String.join("; ", problems));
        sb.append(" | ").append(String.join(" | ", files));
        if (!older.isEmpty()) sb.append(" | broken copies set aside earlier: ").append(String.join(", ", older));
        return sb.toString();
    }

    /** state.json's "memory" block (the shape the bridge wrote: state, problem, savedAt, backupAt). */
    public static JsonObject stateBlock(List<Store> stores) {
        JsonObject o = new JsonObject();
        o.addProperty("state", state(stores));
        List<String> problems = new ArrayList<>();
        long saved = -1, backup = -1;
        for (Store st : stores) {
            if (st.problem() != null) problems.add(st.file() + " " + st.problem());
            saved = Math.max(saved, st.savedAt());
            backup = Math.max(backup, st.backupAt());
        }
        if (!problems.isEmpty()) o.addProperty("problem", String.join("; ", problems));
        if (saved >= 0) o.addProperty("savedAt", saved);
        if (backup >= 0) o.addProperty("backupAt", backup);
        return o;
    }

    /** The verb: "memory", "memory status", "memory fresh". */
    public static String command(String rest, java.util.function.Supplier<String> status) {
        String r = rest == null ? "" : rest.trim().toLowerCase();
        if (r.equals("fresh")) return FRESH;
        if (!r.isEmpty() && !r.equals("status")) return USAGE;
        return status.get();
    }
}
