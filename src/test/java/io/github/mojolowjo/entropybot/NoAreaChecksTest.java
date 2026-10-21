package io.github.mojolowjo.entropybot;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * rm-areas (docs/REMOVAL_RULESET.md item 1): the world is open - no "inside / outside my areas" check comes back in mod
 * code. Only the policy ({@code guard/}) and the owner's typed area verbs ({@code commands/PolicyCommands.java}) may
 * know areas; everyone else asks the policy's verdict ({@code workAllowed}, {@code walkVerdict}) or reads a home fact.
 */
class NoAreaChecksTest {
    static final String[] BANNED = {"my areas", "inAreas(", "insideAreas", "areasOnly"};
    static final Path MAIN = Path.of("src/main/java/io/github/mojolowjo/entropybot");

    static boolean allowed(Path p) {
        String rel = MAIN.relativize(p).toString().replace('\\', '/');
        return rel.startsWith("guard/") || rel.equals("commands/PolicyCommands.java");
    }

    @Test
    void noAreaWordsOutsideThePolicy() throws Exception {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> s = Files.walk(MAIN)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                if (allowed(p)) continue;
                List<String> lines = Files.readAllLines(p);
                for (int i = 0; i < lines.size(); i++)
                    for (String bad : BANNED)
                        if (lines.get(i).contains(bad)) hits.add(MAIN.relativize(p) + ":" + (i + 1) + " \"" + bad + "\"");
            }
        }
        assertTrue(hits.isEmpty(), "area checks outside guard/ and the typed area verbs:\n" + String.join("\n", hits));
    }

    @Test
    void theScanSeesTheSource() throws Exception {
        try (Stream<Path> s = Files.walk(MAIN)) {
            assertTrue(s.filter(x -> x.toString().endsWith(".java")).count() > 100, "the scan must read the mod's source");
        }
    }
}
