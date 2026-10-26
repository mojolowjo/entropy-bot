package io.github.mojolowjo.entropybot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.39.z76 send-back 4 (run AV 02:43:29 / 02:52:43 / 02:56:09: three working strips cut "no progress 30 s ... no block
 * broken"; no monitor line in runs AC-AW ever showed a break stage): Baritone's break helper sets the game's isDestroying
 * flag back to false after every break tick, so a stage gated on it is -1 for every Baritone dig. The break stage is read in
 * ONE place, {@code commands/StepSignalsLive.destroyStage}, and that read does not consult isDestroying. Comments and strings
 * are not scanned (Z76HeightmapReadsTest.code).
 */
class Z76DestroyStageReadTest {
    static final Path ROOT = Path.of("src/main/java/io/github/mojolowjo/entropybot");
    static final String ONE = "commands/StepSignalsLive.java";
    static final Pattern STAGE = Pattern.compile("getDestroyStage\\s*\\(|::\\s*getDestroyStage\\b");

    /** The scan catches a call and a method reference, not a comment or a string. */
    @Test
    void theScanCatchesACallAndAMethodReference() {
        String probe = "class P {\n  int a(G g) { return g.getDestroyStage(); }\n  IntSupplier b(G g) { return g :: getDestroyStage; }\n"
                + "  // g.getDestroyStage()\n  String c = \"::getDestroyStage\";\n}\n";
        String[] lines = Z76HeightmapReadsTest.code(probe).split("\n", -1);
        int hits = 0;
        for (String l : lines) if (STAGE.matcher(l).find()) hits++;
        org.junit.jupiter.api.Assertions.assertEquals(2, hits);
    }

    @Test
    void theBreakStageIsReadOnlyInStepSignalsLiveWithoutTheDestroyingFlag() throws IOException {
        List<String> bad = new ArrayList<>();
        int files = 0;
        try (Stream<Path> s = Files.walk(ROOT)) {
            for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".java"))::iterator) {
                files++;
                String rel = ROOT.relativize(p).toString().replace('\\', '/');
                if (rel.equals(ONE)) continue;
                String[] lines = Z76HeightmapReadsTest.code(Files.readString(p)).split("\n", -1);
                for (int i = 0; i < lines.length; i++) if (STAGE.matcher(lines[i]).find()) bad.add(rel + ":" + (i + 1) + " " + lines[i].trim());
            }
        }
        assertTrue(files > 300, "the scan must see the main sources; saw " + files);
        assertTrue(bad.isEmpty(), "a break-stage read outside StepSignalsLive.destroyStage: " + String.join("\n", bad));
        String one = Z76HeightmapReadsTest.code(Files.readString(ROOT.resolve(ONE)));
        assertTrue(STAGE.matcher(one).find(), "StepSignalsLive reads the stage");
        assertFalse(one.contains("isDestroying"), "Baritone clears isDestroying after each break tick: never a gate for the stage");
    }
}
