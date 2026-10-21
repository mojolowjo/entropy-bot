package io.github.mojolowjo.entropybot.aa;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Round 4A N: the ladder's shape never comes back - no Rung class, no gates, no needs.surface desire. */
class NoRungTest {
    @Test
    void noRungClass() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("io.github.mojolowjo.entropybot.aa.Rung"));
    }

    @Test
    void noGateMethods() {
        for (Class<?> c : new Class<?>[]{AaLadder.class, AaDesires.class, Need.class, Ways.class})
            for (Method m : c.getDeclaredMethods())
                assertFalse(m.getName().equals("openGate") || m.getName().equals("needsOpenGround") || m.getName().equals("gated") || m.getName().equals("gateWhy"),
                        c.getSimpleName() + "." + m.getName() + " is a gate");
    }

    @Test
    void noGateNamesInTheSource() throws Exception {
        Path root = Path.of("src/main/java/io/github/mojolowjo/entropybot/aa");
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                String t = Files.readString(p);
                for (String bad : new String[]{"class Rung ", "openGate(", "needsOpenGround(", "\"needs.surface\"", "\"needs.area\"", "\"needs.woodArea\""})
                    assertFalse(t.contains(bad), p + " contains " + bad);
            }
        }
    }

    @Test
    void theNeedsHaveNoSurfaceGate() {
        for (Need n : AaDesires.needs()) assertNotEquals("needs.surface", n.id);
    }
}
