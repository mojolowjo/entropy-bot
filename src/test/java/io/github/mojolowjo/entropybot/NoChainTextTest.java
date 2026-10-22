package io.github.mojolowjo.entropybot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rm-execution2 ("AA produces no chain text"): no class under {@code aa/} builds a command string. A string literal there
 * fails the build when it joins steps (" then ", not a reason's "; then" / ", then") or starts a command
 * ("goal ", "goto ", "mine ", "craft ") outside a reason (a reason has a ':'). AA's options and desires are typed
 * {@code aa/moves/Step}s; their text() is for logs only. Exempt, each with its reason, below. Comments are not scanned.
 */
class NoChainTextTest {
    static final Path AA = Path.of("src/main/java/io/github/mojolowjo/entropybot/aa");

    /** Files allowed to hold such literals, and why. */
    static final Map<String, String> EXEMPT = Map.of(
            "moves/Step.java", "Step.text(): the step's line for logs and the failure's stage, never parsed into a step",
            "door/Door.java", "the owner's typed vocabulary: it reads the owner's own lines (refuses a chain in 'aa want')",
            "StepTimes.java", "the prediction ledger reads the running step's label (logs), it builds no command");

    static final Pattern LIT = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
    static final Pattern JOIN = Pattern.compile("(?<![;,]) then ");
    static final Pattern HEAD = Pattern.compile("^(goal|goto|mine|craft) ");

    @Test
    void aaBuildsNoChainText() throws IOException {
        List<String> hits = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> s = Files.walk(AA)) { files = s.filter(p -> p.toString().endsWith(".java")).toList(); }
        for (Path p : files) {
            String rel = AA.relativize(p).toString().replace('\\', '/');
            if (EXEMPT.containsKey(rel)) continue;
            String src = stripComments(Files.readString(p));
            Matcher m = LIT.matcher(src);
            while (m.find()) {
                String lit = m.group(1);
                if (JOIN.matcher(lit).find() || (HEAD.matcher(lit).find() && !lit.contains(":")))
                    hits.add(rel + ":" + line(src, m.start()) + " \"" + lit + "\"");
            }
        }
        assertTrue(hits.isEmpty(), "chain text built under aa/ (emit typed Steps instead): " + hits);
    }

    /** The scan itself works: a probe with a chain join and a command head is caught, a reason is not. */
    @Test
    void theScanCatchesAProbe() {
        String probe = stripComments("// \"goal x\" in a comment\nString a = \"burrow then wait 120\"; String b = \"goal \" + g; String c = \"mine coal: no pickaxe\";");
        List<String> found = new ArrayList<>();
        Matcher m = LIT.matcher(probe);
        while (m.find()) if (JOIN.matcher(m.group(1)).find() || (HEAD.matcher(m.group(1)).find() && !m.group(1).contains(":"))) found.add(m.group(1));
        assertTrue(found.equals(List.of("burrow then wait 120", "goal ")), found.toString());
    }

    /** Line and block comments out (string literals kept as they are). */
    static String stripComments(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                int j = i + 1;
                while (j < s.length() && s.charAt(j) != '"' && s.charAt(j) != '\n') { if (s.charAt(j) == '\\') j++; j++; }
                b.append(s, i, Math.min(j + 1, s.length()));
                i = j;
            } else if (c == '\'' && i + 2 < s.length()) {
                int j = s.charAt(i + 1) == '\\' ? i + 3 : i + 2;
                b.append(s, i, Math.min(j + 1, s.length()));
                i = j;
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') i++;
                b.append('\n');
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int j = s.indexOf("*/", i + 2);
                String block = s.substring(i, j < 0 ? s.length() : j + 2);
                for (char x : block.toCharArray()) if (x == '\n') b.append('\n');
                i = j < 0 ? s.length() : j + 1;
            } else b.append(c);
        }
        return b.toString();
    }

    private static int line(String s, int at) {
        int n = 1;
        for (int i = 0; i < at; i++) if (s.charAt(i) == '\n') n++;
        return n;
    }
}
