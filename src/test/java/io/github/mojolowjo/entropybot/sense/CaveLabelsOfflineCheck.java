package io.github.mojolowjo.entropybot.sense;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 0.39.z78 J6 (the owner: "a system we can check"): the cave check offline, from a label file alone - no game. Run:
 * {@code $env:CAVE_LABELS='<path>\cave-labels.json'; .\gradlew test --offline --tests '*CaveLabelsOfflineCheck*'}; it
 * prints the derived floor and its classification against the labels and writes them to build/cave-check.txt.
 * Skipped when CAVE_LABELS is not set (every normal build).
 */
class CaveLabelsOfflineCheck {
    @Test
    void checkTheLabelFile() throws java.io.IOException {
        String file = System.getenv("CAVE_LABELS");
        Assumptions.assumeTrue(file != null && !file.isBlank(), "CAVE_LABELS not set: no label file to check");
        int[] bad = {0};
        CaveLabels l = CaveLabels.read(Files.readString(Path.of(file)), bad);
        String text = CaveLabels.checkText(l.all(), CaveLabels.derive(l.all()), null) + (bad[0] > 0 ? "\n" + bad[0] + " unreadable label(s) skipped" : "");
        System.out.println(text);
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build", "cave-check.txt"), text + "\n");
        assertEquals(text, Files.readString(Path.of("build", "cave-check.txt")).stripTrailing());
    }
}
