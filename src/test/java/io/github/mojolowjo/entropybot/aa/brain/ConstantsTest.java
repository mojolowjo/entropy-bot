package io.github.mojolowjo.entropybot.aa.brain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Round 4A T: every moved hand constant is a Tuning entry with its old default; aa tune lists it; the legacy names agree. */
class ConstantsTest {
    private static final Map<String, Double> OLD = Map.ofEntries(
            Map.entry("urgent", 75.0), Map.entry("margin", 15.0), Map.entry("capExempt", 90.0), Map.entry("trumpFood", 4.0),
            Map.entry("shelterMax", 99.0), Map.entry("shelterAt1", 85.0), Map.entry("torchCut", 0.05), Map.entry("lightFloor", 0.7),
            Map.entry("stashMax", 74.0), Map.entry("diedS", 60.0), Map.entry("dawnClimb", 45.0),
            Map.entry("respawnS", 5.0), Map.entry("healTo", 16.0), Map.entry("hideDayUnder", 10.0), Map.entry("pointS", 6.0),
            Map.entry("sourceFactor", 0.2), Map.entry("flow", 0.9), Map.entry("stationBase", 40.0), Map.entry("stationShare", 40.0),
            Map.entry("chestMin", 35.0), Map.entry("hidePerHp", 7.0), Map.entry("slowPauseS", 20.0), Map.entry("pausedRepriceS", 10.0),
            Map.entry("bag.enter", 3.0), Map.entry("bag.leave", 10.0), Map.entry("band.torchOn", 8.0), Map.entry("band.torchOff", 16.0),
            Map.entry("band.foodOn", 10.0), Map.entry("band.foodOff", 18.0), Map.entry("torch.target", 16.0),
            Map.entry("torch.base", 30.0), Map.entry("torch.span", 30.0),
            Map.entry("bag.base", 20.0), Map.entry("bag.span", 60.0), Map.entry("cost.station", 60.0), Map.entry("cost.spareTable", 10.0),
            Map.entry("cost.base", 240.0), Map.entry("cost.bed", 120.0), Map.entry("cost.iron", 600.0), Map.entry("need.defaultScore", 30.0),
            Map.entry("sprint.drainFloor", 0.25), Map.entry("quarry.radius", 48.0), Map.entry("quarry.cacheMs", 10000.0),
            Map.entry("quarry.digK", 1.5), Map.entry("run.weight", 2.0),
            Map.entry("mine.maxMin", 20.0), Map.entry("mine.stallS", 60.0));

    @Test void everyMovedConstantIsRegisteredWithItsOldDefault() {
        Tuning.reset();
        List<String> lines = Tuning.lines();
        for (var e : OLD.entrySet()) {
            assertTrue(Tuning.names().contains(e.getKey()), e.getKey() + " is not registered");
            assertEquals(e.getValue(), Tuning.defOf(e.getKey()), 1e-12, e.getKey());
            assertEquals(e.getValue(), Tuning.get(e.getKey()), 1e-12, e.getKey());
            assertTrue(lines.stream().anyMatch(l -> l.startsWith(e.getKey() + " ")), "aa tune lists " + e.getKey());
        }
    }

    @Test void unknownNameFailsLoudly() {
        assertThrows(IllegalArgumentException.class, () -> Tuning.get("no.such.number"));
    }

    @Test void everyTuningGetLiteralInTheSourcesIsRegistered() throws Exception {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("Tuning\\.(?:get|i|l|need)\\(\"([^\"]+)\"\\)|\\bt\\(\"([^\"]+)\"\\)");
        java.util.List<String> missing = new java.util.ArrayList<>();
        try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))) {
            for (var f : (Iterable<java.nio.file.Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
                var m = p.matcher(java.nio.file.Files.readString(f));
                while (m.find()) {
                    String n = m.group(1) != null ? m.group(1) : m.group(2);
                    if (!Tuning.names().contains(n)) missing.add(f.getFileName() + ": " + n);
                }
            }
        }
        assertTrue(missing.isEmpty(), "not registered in Tuning: " + missing);
    }

    @Test void legacyNamesAgreeWithTuning() {
        assertEquals(75, Brain.urgentV(), 0);
        assertEquals(15, Brain.bonus(), 0);
        assertEquals(90, Brain.capExempt(), 0);
        assertEquals(4, Brain.trumpFood());
        assertEquals(99, Costs.shelterMax(), 0);
        assertEquals(85, Costs.shelterAt1(), 0);
        assertEquals(60, Kit.diedS(), 0);
        assertEquals(74, Kit.stashMax(), 0);
        assertEquals(0.9, Curves.flow(), 0);
    }

    @Test void tuningReachesTheCode() {
        try {
            Tuning.set("stationBase", 10);
            assertEquals(10 + 40, Worth.table(null, null), 1e-9);
            Tuning.set("shelterMax", 50);
            assertEquals(50, Costs.shelterScore(5), 1e-9);
        } finally { Tuning.reset(); }
        assertEquals(80, Worth.table(null, null), 1e-9);
    }

    // ---- rm-constants (0.39.u2): the sources are scanned, not listed ------------------------------------------------------

    private static final java.nio.file.Path MAIN = java.nio.file.Path.of("src/main/java/io/github/mojolowjo/entropybot");

    /** Where a decision literal is checked: these packages whole, and these command files. */
    private static final List<String> SCOPE_DIRS = List.of("aa", "engine", "threat", "guard", "clear", "cave", "camp");
    private static final List<String> SCOPE_COMMANDS = List.of("AaRuntime", "Jobs", "Clearing", "DigCommands", "FloorSteps", "StepHands", "StepRun", "Crafting");

    /** 0, 1, 2 and a half are the identity, the step, the pair and the middle: arithmetic, not hand numbers. */
    private static final java.util.Set<Double> TRIVIAL = java.util.Set.of(0.0, 1.0, 2.0, 0.5);

    private static final java.util.regex.Pattern MARKER = java.util.regex.Pattern.compile("(?:structural|game fact)");
    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile(
            "(?<![\\w.])(\\d[\\d_]*\\.?\\d*(?:[eE][-+]?\\d+)?[fFdDlL]?|\\.\\d+[fFdD]?)(?![\\w.])");
    private static final java.util.regex.Pattern STATIC_FINAL_NUM = java.util.regex.Pattern.compile("\\bstatic\\s+final\\s+(?:double|int|long|float)\\s+\\w+\\s*=");
    private static final java.util.regex.Pattern OP_BEFORE = java.util.regex.Pattern.compile("(?<![<>&|-])(?:<=|>=|==|!=|<|>|\\+=|-=|\\*=|/=|\\+|-|\\*|/|%)\\s*$");
    private static final java.util.regex.Pattern OP_AFTER = java.util.regex.Pattern.compile("^\\s*(?:<=|>=|==|!=|<(?!<)|>(?!>)|\\+=|-=|\\*=|/=|\\+|-(?!>)|\\*|/|%)");
    private static final java.util.regex.Pattern INCDEC_BEFORE = java.util.regex.Pattern.compile("(?:\\+\\+|--)\\s*$");
    private static final java.util.regex.Pattern INCDEC_AFTER = java.util.regex.Pattern.compile("^\\s*(?:\\+\\+|--)");

    /** One source line: its code with comments, strings and chars blanked, and its comment text. */
    record SrcLine(int no, String code, String comment) {}

    /** Lexes Java source into lines of blanked code and comments (strings, chars, text blocks, // and block comments). */
    static List<SrcLine> lex(String src) {
        List<SrcLine> out = new java.util.ArrayList<>();
        StringBuilder code = new StringBuilder(), com = new StringBuilder();
        int n = src.length(), i = 0, no = 1;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\r') { i++; continue; }
            if (c == '\n') { out.add(new SrcLine(no++, code.toString(), com.toString())); code.setLength(0); com.setLength(0); i++; continue; }
            if (src.startsWith("//", i)) {
                int j = src.indexOf('\n', i); if (j < 0) j = n;
                com.append(src, i, j); code.append(" ".repeat(j - i)); i = j; continue;
            }
            if (src.startsWith("/*", i)) {
                int j = src.indexOf("*/", i + 2); j = j < 0 ? n : j + 2;
                for (int k = i; k < j; k++) {
                    char d = src.charAt(k);
                    if (d == '\r') continue;
                    if (d == '\n') { out.add(new SrcLine(no++, code.toString(), com.toString())); code.setLength(0); com.setLength(0); }
                    else { code.append(' '); com.append(d); }
                }
                i = j; continue;
            }
            if (src.startsWith("\"\"\"", i)) {
                int j = src.indexOf("\"\"\"", i + 3); j = j < 0 ? n : j + 3;
                for (int k = i; k < j; k++) {
                    char d = src.charAt(k);
                    if (d == '\r') continue;
                    if (d == '\n') { out.add(new SrcLine(no++, code.toString(), com.toString())); code.setLength(0); com.setLength(0); }
                    else code.append(' ');
                }
                i = j; continue;
            }
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && src.charAt(j) != c && src.charAt(j) != '\n') j += src.charAt(j) == '\\' ? 2 : 1;
                int end = Math.min(j + 1, n);
                code.append(" ".repeat(end - i)); i = end; continue;
            }
            code.append(c); i++;
        }
        out.add(new SrcLine(no, code.toString(), com.toString()));
        return out;
    }

    private static double valueOf(String tok) {
        String t = tok.replace("_", "");
        char last = t.charAt(t.length() - 1);
        if ("fFdDlL".indexOf(last) >= 0) t = t.substring(0, t.length() - 1);
        try { return Double.parseDouble(t); } catch (NumberFormatException e) { return Double.NaN; }
    }

    /** Declarations of numeric static finals whose line carries neither marker (the initialiser is a plain number or an expression of numbers). */
    static List<String> unmarkedConstants(String file, List<SrcLine> lines) {
        List<String> bad = new java.util.ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            SrcLine l = lines.get(i);
            if (!STATIC_FINAL_NUM.matcher(l.code()).find()) continue;
            String after = l.code().substring(l.code().indexOf('=') + 1);
            if (!NUMBER.matcher(after).find()) continue;                                   // an alias or a constant of the JDK: no hand number
            if (!MARKER.matcher(l.comment()).find() && !marked(lines, i)) bad.add(file + ":" + l.no() + " " + l.code().trim());
        }
        return bad;
    }

    /** True when the comment-only line above carries a marker. */
    private static boolean marked(List<SrcLine> lines, int i) {
        if (i == 0) return false;
        SrcLine p = lines.get(i - 1);
        return p.code().isBlank() && MARKER.matcher(p.comment()).find();
    }

    /** Inline numbers in a comparison or arithmetic that are not 0 / 1 / 2 / 0.5 and have no marker and no Tuning read on their line. */
    static List<String> unmarkedInline(String file, List<SrcLine> lines) {
        List<String> bad = new java.util.ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            SrcLine l = lines.get(i);
            String code = l.code();
            if (STATIC_FINAL_NUM.matcher(code).find() || code.stripLeading().startsWith("@")) continue;
            if (code.contains("Tuning.") || MARKER.matcher(l.comment()).find() || marked(lines, i)) continue;
            var m = NUMBER.matcher(code);
            while (m.find()) {
                double v = valueOf(m.group(1));
                if (Double.isNaN(v) || TRIVIAL.contains(v)) continue;
                String pre = code.substring(0, m.start()), post = code.substring(m.end());
                boolean before = OP_BEFORE.matcher(pre).find() && !INCDEC_BEFORE.matcher(pre).find();
                boolean after = OP_AFTER.matcher(post).find() && !INCDEC_AFTER.matcher(post).find();
                if (before || after) { bad.add(file + ":" + l.no() + " " + m.group(1) + "  in: " + code.trim()); break; }
            }
        }
        return bad;
    }

    private static List<java.nio.file.Path> sources(boolean scopeOnly) throws java.io.IOException {
        try (var s = java.nio.file.Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                if (!scopeOnly) return true;
                String rel = MAIN.relativize(p).toString().replace('\\', '/');
                String top = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : "";
                if (SCOPE_DIRS.contains(top)) return true;
                return top.equals("commands") && SCOPE_COMMANDS.contains(p.getFileName().toString().replace(".java", ""));
            }).sorted().toList();
        }
    }

    private static String rel(java.nio.file.Path p) { return MAIN.relativize(p).toString().replace('\\', '/'); }

    @Test void everyNumericStaticFinalInTheSourcesIsMarkedStructuralOrGameFact() throws Exception {
        List<String> bad = new java.util.ArrayList<>();
        for (var p : sources(false)) bad.addAll(unmarkedConstants(rel(p), lex(java.nio.file.Files.readString(p))));
        assertTrue(bad.isEmpty(), "a numeric static final with no '// structural' or '// game fact: ...' (a hand number is a Tuning entry read through Tuning.get): " + String.join("\n", bad));
    }

    @Test void everyInlineNumberInADecisionIsMarkedOrATuningRead() throws Exception {
        List<String> bad = new java.util.ArrayList<>();
        var files = sources(true);
        assertTrue(files.size() > 150, "the scan must see the aa, engine, threat, guard, clear, cave and camp packages; saw " + files.size());
        for (var p : files) bad.addAll(unmarkedInline(rel(p), lex(java.nio.file.Files.readString(p))));
        assertTrue(bad.isEmpty(), "an inline number in a comparison or arithmetic with no '// structural', '// game fact: ...' or Tuning read on its line (0, 1, 2 and 0.5 excepted): " + String.join("\n", bad));
    }

    @Test void noCodeReadsTheDefaultInsteadOfTheTunedValue() throws Exception {
        List<String> bad = new java.util.ArrayList<>();
        for (var p : sources(false)) {
            if (p.getFileName().toString().equals("Tuning.java")) continue;
            for (SrcLine l : lex(java.nio.file.Files.readString(p))) if (l.code().contains("Tuning.defOf(")) bad.add(rel(p) + ":" + l.no());
        }
        assertTrue(bad.isEmpty(), "Tuning.defOf reads the default, so aa tune would not reach it; read Tuning.get: " + bad);
    }

    @Test void theScansCatchAProbe() {
        String probeConst = "class P {\n    static final int PROBE = 37;\n    static final int OK = 38; // structural\n    // game fact: a block is 16 wide\n    static final int ABOVE = 16;\n}\n";
        assertEquals(1, unmarkedConstants("P.java", lex(probeConst)).size(), "only the unmarked constant is caught");
        String probeInline = "class P {\n    boolean a(int x) { return x > 37; }\n    boolean b(int x) { return x > 38; } // structural\n"
                + "    boolean c(int x) { return x > Tuning.i(\"a.b\") + 5; }\n    boolean d(int x) { return x > 0 && x + 1 < 2; }\n"
                + "    String e = \"x > 99\"; // a string and a comment: x > 98\n    int f = 3 * 7;\n    int g(int a) { return a++ + 0; }\n}\n";
        var bad = unmarkedInline("P.java", lex(probeInline));
        assertEquals(2, bad.size(), "x > 37 and 3 * 7 are caught, nothing else: " + bad);
    }

    @Test void aaTuneListsByPrefix() {
        Tuning.reset();
        List<String> lines = Tuning.lines();
        List<String> headers = lines.stream().filter(l -> l.startsWith("== ")).toList();
        assertTrue(headers.size() > 20, "grouped by prefix: " + headers.size() + " groups");
        assertTrue(headers.stream().anyMatch(h -> h.startsWith("== danger ")), "danger. is a group");
        assertTrue(headers.stream().anyMatch(h -> h.startsWith("== tools ")), "tools. is a group");
        assertTrue(headers.stream().anyMatch(h -> h.startsWith("== brain ")), "the dotless names are the brain's own");
        int count = 0;
        for (String l : lines) if (!l.startsWith("== ")) count++;
        assertEquals(Tuning.names().size(), count, "every entry is listed once");
        for (int i = 1; i < headers.size(); i++) assertTrue(headers.get(i - 1).compareTo(headers.get(i)) < 0, "groups are in order");
        assertEquals(headers, Tuning.groups(), "aa tune alone lists the groups");
        assertTrue(Tuning.lines("danger").size() >= 10 && Tuning.lines("danger").stream().allMatch(l -> l.startsWith("danger.")), "aa tune danger lists the group");
        assertEquals(1, Tuning.lines("urgent").size(), "aa tune <name> lists one number");
        assertTrue(Tuning.lines("no.such").isEmpty(), "nothing matches");
    }
}
