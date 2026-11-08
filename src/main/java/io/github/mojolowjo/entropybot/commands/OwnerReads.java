package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.StationBook;
import io.github.mojolowjo.entropybot.aa.moves.Reservations;
import io.github.mojolowjo.entropybot.aa.trail.Trail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The owner's reads of the memory systems, plain text, one line per item, "none" when empty, and a rule-7 line when a
 * source is not readable: {@code aa stations} (D14's station book), {@code aa trail} (D4), {@code aa routes} (the fork's
 * route memory, read by name), {@code aa reserved} (D1's ledger). The text functions are pure (JUnit: OwnerReadsTest);
 * the live wrappers read the one store each (rule 23) and never keep a copy. Loader notes: none (vanilla and Baritone
 * reached only through {@link Jobs#baritone}, {@link TrailRuntime}).
 */
final class OwnerReads {
    private OwnerReads() {}

    // ---- aa stations ---------------------------------------------------------------------------------------------------

    /** "stations: 2 in the book" then "  crafting_table at x y z: mine, there, last seen 4 min ago"; "stations: none ...". */
    static String stationsText(List<StationBook.Rec> recs, long now) {
        if (recs.isEmpty()) return "stations: none (the book holds the stations the bot placed or looked at in this world; one it never looked at is not in it)";
        List<String> out = new ArrayList<>();
        out.add("stations: " + recs.size() + " in the book");
        for (var r : recs)
            out.add("  " + r.block().replaceFirst("^minecraft:", "") + " at " + r.at() + ": " + (r.mine() ? "mine" : "seen") + ", "
                    + (r.present() ? "there" : "gone (" + (r.goneWhy() == null ? "no reason kept" : r.goneWhy()) + ")") + ", last seen " + ago(now - r.lastSeen()));
        return String.join("\n", out);
    }

    /** {@code arg}: "forget x y z"; null or "": the list. */
    static String stations(String arg, StationBook book, long now) {
        String a = arg == null ? "" : arg.trim();
        if (a.isEmpty()) return stationsText(book.all(), now);
        String[] w = a.split("\\s+");
        if (!w[0].equalsIgnoreCase("forget")) return "usage: aa stations | aa stations forget <x y z>";
        int[] c = cell(w, 1);
        if (c == null) return "usage: aa stations forget <x y z> (three whole numbers, a cell from aa stations)";
        var old = book.forget(c[0], c[1], c[2]);
        return old == null ? "stations: nothing in the book at " + c[0] + " " + c[1] + " " + c[2] + " (aa stations lists the cells)"
                : "stations: forgot the " + old.block().replaceFirst("^minecraft:", "") + " at " + old.at() + " (the next lookup that sees it writes it again)";
    }

    // ---- aa trail ------------------------------------------------------------------------------------------------------

    /** State, start cell, one line per segment, the price line ({@code price} null: not priced, {@code priceWhy} says why). */
    static String trailText(Trail t, Trail.Price price, String priceWhy) {
        if (t == null) return "trail: not readable (no world or no AA runtime yet)";
        if (t.state() == Trail.State.NOT_LOADED) return "trail: not loaded yet (this world's trail.json is read at join)";
        List<String> out = new ArrayList<>();
        out.add("trail: " + t.state() + ", start " + (t.start() == null ? "none" : t.start().text()) + ", " + t.segs().size() + " segment" + (t.segs().size() == 1 ? "" : "s")
                + (t.broken() == null ? "" : ", " + t.broken()) + " (recorded " + t.recorded + ", chosen " + t.chosen + ", walked " + t.walked + ", breaks " + t.breaks + " since the game started)");
        int i = 1;
        for (var s : t.segs())
            out.add(String.format(Locale.ROOT, "  %d: %s -> %s, %s, %.0f s%s%s", i++, s.from().text(), s.to().text(),
                    s.oneWay() ? "one-way drop " + s.drop() + " (" + s.cells().size() + " cells)" : "walk", s.walkedS(),
                    s.breakS() > 0 ? String.format(Locale.ROOT, " + %.0f s breaking", s.breakS()) : "", s.forkRoute() ? ", a fork route" : ""));
        if (t.segs().isEmpty()) out.add("  segments: none");
        out.add(t.state() == Trail.State.CLOSED ? "  no way down is open (the bot is out, or has not gone under yet)"
                : price != null ? "  " + price.line() : "  home by the trail: not priced (" + priceWhy + ")");
        return String.join("\n", out);
    }

    static String trail(String arg) {
        Trail t = TrailRuntime.here();
        String a = arg == null ? "" : arg.trim();
        if (a.equalsIgnoreCase("clear")) {
            if (t == null) return "trail: not readable (no world or no AA runtime yet)";
            if (t.state() == Trail.State.NOT_LOADED) return "trail: not loaded yet, nothing to clear";
            String line = t.clear("cleared by the owner");
            return line != null ? line : "trail: nothing to clear (" + t.state() + ", 0 segments)";
        }
        if (!a.isEmpty()) return "usage: aa trail | aa trail clear";
        Trail.Price p = null;
        String why = "no player";
        var pl = net.minecraft.client.Minecraft.getInstance().player;
        if (t != null && pl != null && t.state() == Trail.State.OPEN) {
            try {
                p = TrailRuntime.priced(pl, t, null);
                if (p == null) why = "no trail";
                else if (!p.whole()) { why = p.broken(); p = null; }
            } catch (RuntimeException | LinkageError e) {
                why = "the climb price was not read: " + e;
            }
        }
        return trailText(t, p, why);
    }

    // ---- aa routes -----------------------------------------------------------------------------------------------------

    static final String ROUTES_MISSING = "routes not readable in this fork jar (IPricedPath.routeList is absent)";

    /** {@code lines} null: not readable. One line per stored route, numbered from 1 (aa routes forget <n>). */
    static String routesText(List<String> lines) {
        if (lines == null) return ROUTES_MISSING;
        if (lines.isEmpty()) return "routes: none (the fork stores a route when a walk Baritone ran to its goal ended)";
        List<String> out = new ArrayList<>();
        out.add("routes: " + lines.size() + " stored by the fork");
        int i = 1;
        for (String l : lines) out.add("  " + i++ + ": " + l);
        return String.join("\n", out);
    }

    /** The fork's route list by name; null: absent or no Baritone. */
    @SuppressWarnings("unchecked")
    static List<String> routeList() {
        var b = Jobs.baritone();
        if (b == null) return null;
        try {
            Object pp = b.getPricedPath();
            Object o = pp.getClass().getMethod("routeList").invoke(pp);
            return o instanceof List<?> l ? (List<String>) l : null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    static String routes(String arg) {
        String a = arg == null ? "" : arg.trim();
        if (a.isEmpty()) return Jobs.baritone() == null ? "routes not readable (Baritone is not up: no world)" : routesText(routeList());
        String[] w = a.split("\\s+");
        if (!w[0].equalsIgnoreCase("forget") || w.length != 2) return "usage: aa routes | aa routes forget <n> (n from aa routes)";
        int n;
        try { n = Integer.parseInt(w[1]); } catch (NumberFormatException e) { return "usage: aa routes forget <n> (a whole number from aa routes)"; }
        var b = Jobs.baritone();
        if (b == null) return "routes not readable (Baritone is not up: no world)";
        List<String> before = routeList();
        if (before == null) return ROUTES_MISSING;
        if (n < 1 || n > before.size()) return "routes: no route " + n + " (" + before.size() + " stored; aa routes lists them)";
        try {
            Object pp = b.getPricedPath();
            Object r = pp.getClass().getMethod("forget", int.class).invoke(pp, n - 1);
            return Boolean.TRUE.equals(r) ? "routes: forgot route " + n + " (" + before.get(n - 1) + ")" : "routes: the fork did not forget route " + n + " (it answered no)";
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return "routes: forget is not readable in this fork jar (" + e + ")";
        }
    }

    // ---- aa reserved ---------------------------------------------------------------------------------------------------

    /** One line per reserve: "  stick 2 for craft stone_pickaxe 1"; "reserved: none ..."; the rule-15 unknown and the note after it. */
    static String reservedText(Reservations r) {
        if (r == null) return "reserved: not readable (no AA runtime yet)";
        List<String> out = new ArrayList<>();
        if (r.list().isEmpty()) out.add("reserved: none (no run holds the bag, or its steps claim nothing)");
        else {
            out.add("reserved: " + r.list().size() + " claim" + (r.list().size() == 1 ? "" : "s") + " on the bag");
            for (var x : r.list()) out.add("  " + Reservations.forText(x));
        }
        if (r.unknown() != null) out.add("  not loaded: " + r.unknown());
        if (r.note() != null) out.add("  note: " + r.note());
        return String.join("\n", out);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    static String ago(long ms) {
        if (ms < 0) return "just now";
        long s = ms / 1000;
        if (s < 90) return s + " s ago";
        if (s < 5400) return Math.round(s / 60.0) + " min ago";
        return String.format(Locale.ROOT, "%.1f h ago", s / 3600.0);
    }

    private static int[] cell(String[] w, int from) {
        if (w.length != from + 3) return null;
        try { return new int[]{Integer.parseInt(w[from]), Integer.parseInt(w[from + 1]), Integer.parseInt(w[from + 2])}; }
        catch (NumberFormatException e) { return null; }
    }
}
