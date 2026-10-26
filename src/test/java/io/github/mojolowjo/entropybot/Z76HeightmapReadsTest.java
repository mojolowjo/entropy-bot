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
 * 0.39.z76 surface 2 (runs AK, AL, AO: MOTION_BLOCKING_NO_LEAVES read -64 on the client in five places - the walk-up target,
 * the roof and cave checks): the game's heightmaps are read in ONE place, {@code world/SurfaceHeight*} (the pure rule and
 * its live reader, which answers "not known" for a column the client has no data for). A heightmap read anywhere else in
 * the main sources ({@code Heightmap.Types}, {@code getHeight(Heightmap...)}, {@code getHeightmap}) fails the build, as
 * NoNameListsTest does for name lists. Comments and strings are not scanned.
 */
class Z76HeightmapReadsTest {
    static final Path ROOT = Path.of("src/main/java/io/github/mojolowjo/entropybot");
    /** The one place: the pure rule and its live reader. */
    static final String ALLOWED = "world/SurfaceHeight";
    static final Pattern READ = Pattern.compile("Heightmap\\s*\\.\\s*Types|getHeight\\s*\\(\\s*(?:net\\.minecraft\\.world\\.level\\.levelgen\\.)?Heightmap|getHeightmap\\s*\\(");

    /** The source with its comments and string literals blanked (their text is no read). */
    static String code(String src) {
        String s = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        s = s.replaceAll("\"(?:\\\\.|[^\"\\\\\\n])*\"", "\"\"");
        return s.replaceAll("//[^\\n]*", " ");
    }

    static List<String> reads(String rel, String src) {
        List<String> out = new ArrayList<>();
        String[] lines = code(src).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = READ.matcher(lines[i]);
            if (m.find()) out.add(rel + ":" + (i + 1) + " " + lines[i].trim());
        }
        return out;
    }

    @Test
    void heightmapsAreReadOnlyThroughSurfaceHeight() throws IOException {
        List<String> bad = new ArrayList<>();
        int files = 0;
        try (Stream<Path> s = Files.walk(ROOT)) {
            for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".java"))::iterator) {
                files++;
                String rel = ROOT.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith(ALLOWED)) continue;
                bad.addAll(reads(rel, Files.readString(p)));
            }
        }
        assertTrue(files > 300, "the scan must see the main sources; saw " + files);
        assertTrue(bad.isEmpty(), "a heightmap read outside world/SurfaceHeight* (read it through SurfaceHeightLive; the client has no "
                + "MOTION_BLOCKING_NO_LEAVES - it reads the bottom of the world): " + String.join("\n", bad));
    }

    /** The scan catches a read, and not one in a comment or a string. */
    @Test
    void theScanCatchesAProbe() {
        String probe = "class P {\n  int a(Level l) { return l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0); }\n"
                + "  // l.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0)\n  String b = \"Heightmap.Types\";\n"
                + "  int c(Level l) { return l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, 0, 0); }\n}\n";
        List<String> r = reads("P.java", probe);
        assertEquals(2, r.size(), r.toString());
        assertTrue(r.get(0).startsWith("P.java:2 "), r.toString());
        assertTrue(r.get(1).startsWith("P.java:5 "), r.toString());
    }
}
