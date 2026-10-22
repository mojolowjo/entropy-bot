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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rm-catches (RULES row 7, REMOVAL_RULESET item 7): no silent failure. A catch whose variable is named {@code ignored}, or
 * whose body is empty (or only comments), fails the build. A catch that must stay silent (a best-effort render or recorder
 * path) says why in its body with a comment that starts {@code silent:}; those are the allow-list. Everything else logs
 * ({@code Quiet.warn} or {@code LOG.warn}) or answers a failed result with the reason.
 */
class NoSilentCatchTest {
    static final Path ROOT = Path.of("src/main/java/io/github/mojolowjo/entropybot");
    static final Pattern CATCH = Pattern.compile("catch\\s*\\(([^)]*)\\)\\s*\\{");

    @Test
    void noSilentCatches() throws IOException {
        List<String> bad = new ArrayList<>();
        try (Stream<Path> st = Files.walk(ROOT)) {
            for (Path p : (Iterable<Path>) st.filter(x -> x.toString().endsWith(".java"))::iterator) {
                String rel = ROOT.relativize(p).toString().replace('\\', '/');
                String t = Files.readString(p);
                Matcher m = CATCH.matcher(t);
                while (m.find()) {
                    int i = m.end(), d = 1;
                    while (d > 0 && i < t.length()) {
                        char c = t.charAt(i++);
                        if (c == '{') d++; else if (c == '}') d--;
                    }
                    String body = t.substring(m.end(), i - 1);
                    if (body.contains("silent:")) continue;
                    String[] head = m.group(1).trim().split("\\s+");
                    String code = body.replaceAll("(?s)/\\*.*?\\*/|//[^\\n]*", "").trim();
                    int line = t.substring(0, m.start()).split("\n", -1).length;
                    if (head[head.length - 1].equals("ignored")) bad.add(rel + ":" + line + " catch (... ignored)");
                    else if (code.isEmpty()) bad.add(rel + ":" + line + " empty catch");
                }
            }
        }
        assertTrue(bad.isEmpty(), "silent catches (log it, return a failed result, or say why with '// silent: reason'):\n" + String.join("\n", bad));
    }
}
