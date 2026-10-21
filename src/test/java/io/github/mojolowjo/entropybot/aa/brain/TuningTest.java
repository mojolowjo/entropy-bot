package io.github.mojolowjo.entropybot.aa.brain;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * rm-constants (0.39.u2): every Tuning entry has the default its hand constant had. {@code tuning-defaults.txt} (test resources) holds
 * name and default of every entry; a default that moves, an entry that is missing here or a line here that names no entry fails.
 * A new entry is added to both places on purpose, so a default never changes by accident.
 */
class TuningTest {
    private static Map<String, Double> expected() throws Exception {
        Map<String, Double> m = new LinkedHashMap<>();
        for (String l : Files.readAllLines(Path.of("src/test/resources/tuning-defaults.txt"))) {
            if (l.isBlank() || l.startsWith("#")) continue;
            String[] p = l.split("\t");
            assertEquals(2, p.length, "name<TAB>value: " + l);
            m.put(p[0], Double.parseDouble(p[1]));
        }
        return m;
    }

    @Test void everyDefaultIsUnchanged() throws Exception {
        Tuning.reset();
        List<String> moved = new java.util.ArrayList<>();
        for (var e : expected().entrySet()) {
            if (!Tuning.names().contains(e.getKey())) { moved.add(e.getKey() + " is gone"); continue; }
            if (Math.abs(Tuning.defOf(e.getKey()) - e.getValue()) > 1e-12 * Math.max(1, Math.abs(e.getValue())))
                moved.add(e.getKey() + " default " + Tuning.defOf(e.getKey()) + " was " + e.getValue());
            if (Tuning.get(e.getKey()) != Tuning.defOf(e.getKey())) moved.add(e.getKey() + " reads " + Tuning.get(e.getKey()) + " not its default");
        }
        assertTrue(moved.isEmpty(), "defaults moved: " + moved);
    }

    @Test void everyEntryIsInTheDefaultsFile() throws Exception {
        Set<String> listed = new HashSet<>(expected().keySet());
        Set<String> missing = new HashSet<>(Tuning.names());
        missing.removeAll(listed);
        assertTrue(missing.isEmpty(), "entries with no line in tuning-defaults.txt (add name<TAB>default): " + missing);
        assertEquals(Tuning.names().size(), listed.size(), "the file names no entry that does not exist");
    }

    @Test void everyEntryHasARunAndAReason() {
        for (String l : Tuning.lines()) {
            if (l.startsWith("== ")) continue;
            int dash = l.indexOf(" - ");
            assertTrue(dash > 0 && l.indexOf(": ", dash) > dash + 3 && !l.endsWith(": "), "name, run and why: " + l);
        }
    }

    @Test void theTypedReadsRound() {
        Tuning.reset();
        assertEquals(75, Tuning.i("urgent"));
        assertEquals(600_000L, Tuning.l("aaDesires.helpTimeoutMs"));
        Tuning.set("urgent", 74.6);
        try { assertEquals(75, Tuning.i("urgent"), "i rounds"); } finally { Tuning.reset(); }
    }
}
