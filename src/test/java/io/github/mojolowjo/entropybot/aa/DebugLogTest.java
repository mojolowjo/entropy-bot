package io.github.mojolowjo.entropybot.aa;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DebugLogTest {
    static final String TREE = "aa debug want torch 4 (dry)\n  score: 40 (x)\n  ways:\n    craft 12 s";

    @Test
    void blockFormat() {
        String b = DebugLog.block("2026-10-23 10:00:00", "seed", "aa debug want torch 4", TREE);
        assertEquals("=".repeat(72) + "\n2026-10-23 10:00:00 | seed | aa debug want torch 4\n\n" + TREE + "\n\n", b);
    }

    @Test
    void chatLine() {
        assertEquals("aa debug: 4 lines written to entropybot\\aa-debug.log (score: 40 (x))", DebugLog.chat(TREE));
        assertEquals("aa debug: 1 lines written to entropybot\\aa-debug.log (aa want: no item zzz)", DebugLog.chat("aa want: no item zzz"));
    }

    @Test
    void appendsAndRollsOver(@TempDir Path dir) throws Exception {
        assertEquals(DebugLog.chat(TREE), DebugLog.write(dir, 300, "t", "w", "aa debug want torch", TREE));
        String two = DebugLog.write(dir, 300, "t", "w", "aa debug want torch", TREE);
        assertEquals(DebugLog.chat(TREE), two);       // one block (~180 bytes) is under 300 before this write
        String three = DebugLog.write(dir, 300, "t", "w", "aa debug x", "refused");
        assertTrue(three.contains("renamed to aa-debug.1.log"), three);
        assertEquals(2 * DebugLog.block("t", "w", "aa debug want torch", TREE).length(), Files.readString(dir.resolve("aa-debug.1.log"), StandardCharsets.UTF_8).length());
        assertEquals(DebugLog.block("t", "w", "aa debug x", "refused"), Files.readString(dir.resolve("aa-debug.log"), StandardCharsets.UTF_8));
    }

    @Test
    void writeFailureIsSaid(@TempDir Path dir) throws Exception {
        Path notDir = dir.resolve("file");
        Files.writeString(notDir, "x");
        String r = DebugLog.write(notDir, 100, "t", "w", "aa debug want torch", TREE);
        assertTrue(r.startsWith("aa debug: could not write entropybot\\aa-debug.log: java.nio.file."), r);
        assertTrue(r.endsWith("(score: 40 (x))"), r);
    }
}
