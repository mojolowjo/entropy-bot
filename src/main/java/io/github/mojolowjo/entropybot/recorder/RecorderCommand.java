package io.github.mojolowjo.entropybot.recorder;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code recorder} verb (B7e contract, owner only): parsed here (pure), carried out by the installed recorder
 * through {@link Controls}. Dispatch: {@code RecorderCommand.handle(core.recorder, rest, isOwner, owner())}.
 */
public final class RecorderCommand {
    private RecorderCommand() {}

    public static final String USAGE = "usage: recorder [off|light|normal|detailed|max] [for <N>m|<N>h] | range <chunks> | "
            + "trail <ticks|Ns> | snapshot <blocks>|now | states on|off | keep <hours> | mark <note>";

    public enum Kind { SHOW, PRESET, BOOST, RANGE, TRAIL, SNAPSHOT, SNAPSHOT_NOW, STATES, KEEP, MARK, ERROR }

    /** word: the preset, the mark's note or the error text; value: minutes, chunks, ticks, blocks, hours or 1/0. */
    public record Cmd(Kind kind, String word, int value) {}

    /** What the installed recorder does with a command (FlightRecorder; a fake in the tests). */
    public interface Controls {
        String run(Cmd cmd);
    }

    private static final Pattern BOOST = Pattern.compile("^(off|light|normal|detailed|max)\\s+for\\s+(\\d{1,6})\\s*(m|min|mins|minutes?|h|hours?)$");
    private static final Pattern TRAIL = Pattern.compile("^(\\d{1,6}(?:\\.\\d{1,3})?)\\s*(t|ticks?|s|secs?|seconds?)?$");
    private static final Pattern HOURS = Pattern.compile("^(\\d{1,6})\\s*(h|hours?|d|days?)?$");

    public static String handle(Recorder r, String rest, boolean isOwner, String owner) {
        if (!isOwner) return "only " + owner + " can use the recorder";
        if (!(r instanceof Controls c)) return r == null ? Recorder.NONE.summary() : r.summary();
        Cmd cmd;
        try {
            cmd = parse(rest);
        } catch (RuntimeException e) {
            return "error: " + USAGE;
        }
        if (cmd.kind() == Kind.ERROR) return "error: " + cmd.word();
        try {
            return c.run(cmd);
        } catch (RuntimeException e) {
            return "error: the recorder failed: " + e;
        }
    }

    public static Cmd parse(String rest) {
        String t = rest == null ? "" : rest.trim().replaceAll("\\s+", " ");
        String low = t.toLowerCase(Locale.ROOT);
        if (low.isEmpty() || low.equals("status") || low.equals("show")) return new Cmd(Kind.SHOW, null, 0);
        if (RecorderSettings.PRESETS.contains(low)) return new Cmd(Kind.PRESET, low, 0);
        Matcher m = BOOST.matcher(low);
        if (m.find()) {
            long n = Long.parseLong(m.group(2));
            long minutes = m.group(3).startsWith("h") ? n * 60 : n;
            if (m.group(1).equals("off")) return err("a boost needs a preset that records (light, normal, detailed, max)");
            if (minutes < 1 || minutes > 24 * 60) return err("a boost lasts 1 minute to 24 hours");
            return new Cmd(Kind.BOOST, m.group(1), (int) minutes);
        }
        String[] w = low.split(" ", 2);
        String arg = w.length > 1 ? w[1].trim() : "";
        switch (w[0]) {
            case "range" -> {
                Integer n = intOf(arg);
                if (n == null || n < 1 || n > RecorderSettings.RANGE_MAX) return err("range is 1 to " + RecorderSettings.RANGE_MAX + " chunks (the render distance caps it)");
                return new Cmd(Kind.RANGE, null, n);
            }
            case "trail" -> {
                Matcher tm = TRAIL.matcher(arg);
                if (!tm.find()) return err("trail <ticks> or trail <N>s (1 tick to 10 s)");
                double v = Double.parseDouble(tm.group(1));
                String unit = tm.group(2);
                long ticks = unit != null && unit.startsWith("s") ? Math.round(v * 20) : Math.round(v);
                if (unit == null && tm.group(1).contains(".")) return err("trail <ticks> or trail <N>s (1 tick to 10 s)");
                if (ticks < 1 || ticks > RecorderSettings.TRAIL_MAX) return err("trail is 1 tick to 10 s");
                return new Cmd(Kind.TRAIL, null, (int) ticks);
            }
            case "snapshot", "snap" -> {
                if (arg.equals("now")) return new Cmd(Kind.SNAPSHOT_NOW, null, 0);
                Integer n = intOf(arg);
                if (n == null || n < RecorderSettings.SNAP_MIN || n > RecorderSettings.SNAP_MAX)
                    return err("snapshot <blocks> is the box's half-size, " + RecorderSettings.SNAP_MIN + " to " + RecorderSettings.SNAP_MAX + " (or snapshot now)");
                return new Cmd(Kind.SNAPSHOT, null, n);
            }
            case "states", "state" -> {
                if (arg.equals("on")) return new Cmd(Kind.STATES, null, 1);
                if (arg.equals("off")) return new Cmd(Kind.STATES, null, 0);
                return err("states on|off");
            }
            case "keep" -> {
                Matcher hm = HOURS.matcher(arg);
                if (!hm.find()) return err("keep <hours> (1 to " + RecorderSettings.KEEP_MAX + ")");
                long h = Long.parseLong(hm.group(1));
                if (hm.group(2) != null && hm.group(2).startsWith("d")) h *= 24;
                if (h < 1 || h > RecorderSettings.KEEP_MAX) return err("keep is 1 to " + RecorderSettings.KEEP_MAX + " hours");
                return new Cmd(Kind.KEEP, null, (int) h);
            }
            case "mark" -> {
                String note = t.length() > 4 ? t.substring(4).trim() : "";
                if (note.isEmpty()) return err("mark <note>, e.g. recorder mark starting the dig test");
                if (note.length() > 200) note = note.substring(0, 200);
                return new Cmd(Kind.MARK, note, 0);
            }
            default -> { return err(USAGE); }
        }
    }

    private static Cmd err(String s) { return new Cmd(Kind.ERROR, s, 0); }

    private static Integer intOf(String s) {
        try {
            return s.matches("^\\d{1,6}$") ? Integer.parseInt(s) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
