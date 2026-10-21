package io.github.mojolowjo.entropybot.aa.moves;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backbone step 2 (BARITONE_BACKBONE.md, owner: "AA is the driver, Baritone is the car"): no mod code presses keys,
 * sets the camera, or breaks / places by hand outside {@code aa/moves} (Moves and its Baritone car), the air and hit
 * reflexes ({@code engine/Reflexes}), the lava / fire reflex ({@code engine/FireReflex}) and Baritone itself (not in
 * this tree). The other sites the movers audit (docs/runs/movers-audit.md) found are the exception list below, each
 * with its count today as the ceiling: a new caller anywhere, or one more in a listed file, fails the build. Each later
 * backbone step lowers a count or drops a line; the goal is an empty list.
 */
class MoversTest {
    static final Pattern CALL = Pattern.compile(
            "setKey\\(|\\.setDown\\(|KeyMapping\\.set\\(|(?<![mM]oves)\\.lookAt\\(|setYRot\\(|setXRot\\(|continueDestroyBlock\\(|startDestroyBlock\\(|useItemOn\\(");

    /** Files under the mod's package root allowed any number (the movers' homes). */
    static final List<String> HOMES = List.of("aa/moves/");

    /** The reflexes (allowed by the design) and the audit's sites still to move: path -> the most calls allowed. */
    static final Map<String, Integer> CEILING = new TreeMap<>(Map.ofEntries(
            // allowed by the design: the air reflex and the hit response, the lava / fire reflex
            Map.entry("engine/Reflexes.java", 19),
            Map.entry("engine/FireReflex.java", 8),
            // the mixin names the vanilla method it intercepts (not a caller)
            Map.entry("mixin/GuardMixinGameMode.java", 1),
            // to move in backbone steps 3-4 (movers-audit.md items 4-21)
            Map.entry("motor/Motor.java", 1),
            Map.entry("engine/CreeperDuel.java", 12),
            Map.entry("engine/Escort.java", 1),
            Map.entry("commands/Jobs.java", 13),
            Map.entry("commands/Seq.java", 1),
            Map.entry("commands/Clearing.java", 6),
            Map.entry("commands/ShelterJob.java", 8),
            Map.entry("commands/RestoreRun.java", 3),
            Map.entry("commands/FarmPlanting.java", 10),
            Map.entry("farm/McFarmWorld.java", 5),
            Map.entry("commands/FloorSteps.java", 2),
            Map.entry("commands/Fishing.java", 1),
            Map.entry("commands/Mule.java", 1),
            Map.entry("commands/Crafting.java", 2),
            Map.entry("altar/McAltarWorld.java", 3),
            Map.entry("move/MovePackage.java", 5),
            Map.entry("commands/Commands.java", 2),
            Map.entry("commands/AaRuntime.java", 2)));

    @Test
    void noMoverOutsideMovesReflexesAndTheExceptionList() throws IOException {
        Path root = Path.of("src/main/java/io/github/mojolowjo/entropybot");
        assertTrue(Files.isDirectory(root), "run from the project root: " + root.toAbsolutePath());
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                if (HOMES.stream().anyMatch(rel::startsWith)) continue;
                String src = Files.readString(f);
                Matcher m = CALL.matcher(src);
                int n = 0;
                while (m.find()) n++;
                int max = CEILING.getOrDefault(rel, 0);
                if (n > max) bad.add(rel + ": " + n + " key/camera/break/place calls, " + max + " allowed (use aa/moves/Moves)");
            }
        }
        assertTrue(bad.isEmpty(), "physical actions outside Moves (BARITONE_BACKBONE.md):\n" + String.join("\n", bad));
    }

    @Test
    void theDangerWaysAreOffTheListForGood() {
        assertTrue(!CEILING.containsKey("engine/DangerMoves.java"), "DangerMoves is a thin caller of Moves now");
    }
}
