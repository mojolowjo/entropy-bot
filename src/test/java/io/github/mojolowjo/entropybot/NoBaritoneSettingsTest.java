package io.github.mojolowjo.entropybot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.39.z8 (owner 2026-10-22, "remove the old job habit"): the mod never sets Baritone's {@code allowBreak},
 * {@code allowPlace} or {@code allowBreakAnyway}. Each fork process that breaks or places (chop, mine via mineWith,
 * builder, burrow) owns them for its own run and puts them back at its end; walks use Baritone's travel settings as it
 * ships them. Which blocks stays the policy's ({@code blocksToDisallowBreaking}, the cost hook). Only
 * {@code baritone/BaritoneHook.java} (its FIXED_OFF list) may write Baritone settings by name. Comments are not scanned.
 */
class NoBaritoneSettingsTest {
    static final Path MAIN = Path.of("src/main/java/io/github/mojolowjo/entropybot");
    static final String EXEMPT = "baritone/BaritoneHook.java";

    /** A direct write: {@code x.allowBreak.value = ...} (not {@code ==}). */
    static final Pattern WRITE = Pattern.compile("\\ballow(Break|Place|BreakAnyway)\\s*\\.\\s*value\\s*=(?!=)");
    /** A write by name: {@code byLowerName.get("allowbreak")} and the like. */
    static final Pattern BY_NAME = Pattern.compile("byLowerName\\s*\\.\\s*get\\(\\s*\"allow(break|place|breakanyway)\"", Pattern.CASE_INSENSITIVE);

    @Test
    void theModSetsNoBreakOrPlaceSetting() throws IOException {
        List<String> hits = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> s = Files.walk(MAIN)) { files = s.filter(p -> p.toString().endsWith(".java")).toList(); }
        for (Path p : files) {
            String rel = MAIN.relativize(p).toString().replace('\\', '/');
            if (rel.equals(EXEMPT)) continue;
            hits.addAll(scan(rel, NoChainTextTest.stripComments(Files.readString(p))));
        }
        assertTrue(hits.isEmpty(), "Baritone's allowBreak / allowPlace / allowBreakAnyway set by the mod (the fork processes own them): " + hits);
    }

    /** The scan itself works: writes are caught, reads, comparisons and comments are not. */
    @Test
    void theScanCatchesAProbe() {
        String probe = NoChainTextTest.stripComments(String.join("\n",
                "// s.allowBreak.value = true; in a comment",
                "s.allowBreak.value = true;",
                "boolean b = s.allowPlace.value;",
                "if (s.allowPlace.value == x) y();",
                "st.allowBreakAnyway . value=list;",
                "var t = s.byLowerName.get(\"allowplace\");"));
        assertEquals(List.of("probe:2", "probe:5", "probe:6"), scan("probe", probe));
    }

    static List<String> scan(String rel, String src) {
        List<String> hits = new ArrayList<>();
        for (Pattern pat : List.of(WRITE, BY_NAME)) {
            Matcher m = pat.matcher(src);
            while (m.find()) hits.add(rel + ":" + line(src, m.start()));
        }
        hits.sort((a, b) -> Integer.compare(Integer.parseInt(a.substring(a.lastIndexOf(':') + 1)), Integer.parseInt(b.substring(b.lastIndexOf(':') + 1))));
        return hits;
    }

    private static int line(String s, int at) {
        int n = 1;
        for (int i = 0; i < at; i++) if (s.charAt(i) == '\n') n++;
        return n;
    }
}
