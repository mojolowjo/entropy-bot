package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MINING_PLAN, owner additions 2026-10-18: the owner drives AA by hand. One object on the blackboard ({@link #FACT}):
 * <ul>
 * <li>{@code aa ask on|off}: before a rung starts a task it waits ({@link #ASK_MS} at most) with its ranked options
 *     shown ({@link Ask}); {@code aa pick <n>} chooses; the timeout takes AA's own first choice.</li>
 * <li>{@code aa do <need>}: that rung runs now (met or not, its cooldown forgotten); the other rungs wait until it ends.</li>
 * <li>{@code aa skip <need>}: the rung is left out for this run ({@code aa skip none} clears).</li>
 * <li>{@code aa use <option>}: the next pick of the current rung runs that option (matched on the option's id).</li>
 * </ul>
 * Every owner choice that differs from AA's first one is counted and logged (score tuning data). In memory only; aa off
 * clears it. Pure (JUnit: AaManualTest).
 */
public final class AaManual {
    public static final String FACT = "aa.manual";
    /** How long an ask waits for {@code aa pick} before AA takes its own first choice. */
    public static final long ASK_MS = 60_000;

    /** The rungs by their short name ("tools", "surface"...) as {@code aa do/skip} take them. */
    public static final List<String> NEEDS = List.of("area", "woodArea", "tools", "surface", "food", "tableFurnace", "spareTable", "torches", "base", "bed", "bag", "iron");

    /** One option as the ask shows it. */
    public record Row(String label, String command) {}

    /** A rung waiting for the owner's pick. */
    public record Ask(String rung, List<Row> rows, long since) {}

    public boolean ask;
    public Ask pending;
    /** The owner's pick for the pending ask (1-based), 0 none. */
    public int pick;
    /** The option text for {@code aa use}, and the rung it was meant for (null: whichever picks next). */
    public String use, useRung;
    /** The rung id {@code aa do} runs now, or null. */
    public String doRung;
    /** When aa do was given (-1 unknown); a do the rung never reaches is dropped after {@link #DO_MAX_MS}. */
    public long doSince = -1;
    public static final long DO_MAX_MS = 10 * 60_000;
    public final Set<String> skipped = new LinkedHashSet<>();
    public int ownerPicks, differs;
    public String lastDiffer;

    public static AaManual of(Blackboard bb) {
        Object o = bb.fact(FACT);
        if (o instanceof AaManual m) return m;
        AaManual m = new AaManual();
        bb.fact(FACT, m);
        return m;
    }

    /** "tools" / "needs.tools" / "TOOLS" -> "needs.tools"; null when no rung has that name. */
    public static String rungId(String need) {
        if (need == null) return null;
        String t = need.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("needs.")) t = t.substring(6);
        for (String n : NEEDS) if (n.toLowerCase(Locale.ROOT).equals(t)) return "needs." + n;
        // a few spoken forms
        return switch (t) {
            case "table", "furnace", "table_furnace", "tablefurnace" -> "needs.tableFurnace";
            case "spare", "spare_table" -> "needs.spareTable";
            case "wood", "trees", "wood_area" -> "needs.woodArea";
            case "climb", "up" -> "needs.surface";
            case "torch" -> "needs.torches";
            case "deposit", "chest" -> "needs.bag";
            case "pickaxe", "stone" -> "needs.tools";
            case "shelter", "house" -> "needs.base";
            default -> null;
        };
    }

    public boolean skipped(String rungId) { return skipped.contains(rungId); }

    /**
     * The ask gate before a rung starts a task: the command to run now, or null to wait (the rung stays RUNNING).
     *
     * @param rows the ranked options, AA's first choice first (never empty)
     */
    public String gate(String rung, List<Row> rows, long now, java.util.function.Consumer<String> log) {
        if (!ask || rows.isEmpty()) return rows.isEmpty() ? null : rows.get(0).command();
        if (pending == null || !pending.rung().equals(rung)) {
            pending = new Ask(rung, List.copyOf(rows), now);
            pick = 0;
            log.accept("ask: " + rung.substring(rung.indexOf('.') + 1) + " waits for aa pick (" + ASK_MS / 1000 + " s, then my first): " + rowsText(rows));
            return null;
        }
        List<Row> r = pending.rows();
        if (pick > 0 && pick <= r.size()) {
            Row chosen = r.get(pick - 1);
            ownerPicks++;
            if (pick != 1) {
                differs++;
                lastDiffer = rung + ": the owner picked " + pick + " (" + chosen.label() + ") over my 1 (" + r.get(0).label() + ")";
                log.accept("owner pick differs: " + lastDiffer);
            } else log.accept("ask: " + rung + ": the owner picked my first (" + chosen.label() + ")");
            pending = null;
            pick = 0;
            return chosen.command();
        }
        if (now - pending.since() >= ASK_MS) {
            log.accept("ask: " + rung + ": no pick in " + ASK_MS / 1000 + " s - my first choice (" + r.get(0).label() + ")");
            pending = null;
            pick = 0;
            return r.get(0).command();
        }
        return null;
    }

    /** The rung was halted or ended: its pending ask goes. */
    public void dropAsk(String rung) {
        if (pending != null && pending.rung().equals(rung)) {
            pending = null;
            pick = 0;
        }
    }

    /** {@code aa use}: the option for this rung's pick, matched on its label (id first); null = none asked for this rung. */
    public String useFor(String rung) {
        if (use == null || (useRung != null && !useRung.equals(rung))) return null;
        return use;
    }

    /** The {@code aa use} was applied (or found nothing): cleared, a differing choice counted. */
    public void used(String rung, String label, String mine, java.util.function.Consumer<String> log) {
        if (label == null) log.accept("aa use " + use + ": no such option on " + rung + " now - my own choice runs");
        else {
            ownerPicks++;
            if (!label.equals(mine)) {
                differs++;
                lastDiffer = rung + ": the owner's aa use " + label + " over my " + mine;
                log.accept("owner pick differs: " + lastDiffer);
            }
        }
        use = null;
        useRung = null;
    }

    public static String rowsText(List<Row> rows) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < rows.size() && i < 9; i++) out.add((i + 1) + ") " + rows.get(i).label());
        return String.join("  ", out);
    }

    /** For aa status. */
    public String describe(long now) {
        StringBuilder b = new StringBuilder("manual: ask ").append(ask ? "on" : "off");
        if (pending != null) b.append(" (").append(pending.rung()).append(" waits ").append(Math.max(0, (ASK_MS - (now - pending.since())) / 1000))
                .append(" s for aa pick: ").append(rowsText(pending.rows())).append(')');
        if (doRung != null) b.append("; aa do ").append(doRung);
        if (!skipped.isEmpty()) b.append("; skipped ").append(String.join(", ", skipped));
        if (use != null) b.append("; aa use ").append(use).append(useRung == null ? "" : " on " + useRung);
        b.append("; owner picks ").append(ownerPicks).append(" (").append(differs).append(" differ from mine")
                .append(lastDiffer == null ? "" : ", last: " + lastDiffer).append(')');
        return b.toString();
    }
}
