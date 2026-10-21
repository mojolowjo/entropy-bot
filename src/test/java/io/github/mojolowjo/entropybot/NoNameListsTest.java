package io.github.mojolowjo.entropybot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rm-names (RULES row 4, REMOVAL_RULESET item 6): no vanilla item, block or mob name in a {@code Map.of / List.of / Set.of}
 * literal in the brain ({@code aa/brain/**}) and the junk rules. What an item is comes from the game's data
 * ({@code ItemWorth}, {@code MiningTiers}, tags, recipes). The allow-list below is the verb vocabulary and the owner's
 * word lists, not items; test data lives in src/test and is not scanned.
 */
class NoNameListsTest {
    static final Path ROOT = Path.of("src/main/java/io/github/mojolowjo/entropybot");
    static final List<String> SCANNED_DIRS = List.of(".");
    static final List<String> SCANNED_FILES = List.of();
    /** The verb vocabulary and other words that are no item: allowed in a literal even when they look like one. */
    static final Set<String> ALLOWED = Set.of();
    /**
     * Files whose literals are the owner's vocabulary (kind words, family labels, task and verb keys, option names), not item
     * lists: the scan skips them. Each has a reason.
     */
    /** Tag paths (the game's own tag names, not items) a converted file passes to the tag reader. */
    static final java.util.Map<String, Set<String>> ALLOWED_IN = java.util.Map.of("restore/RestoreRules.java", Set.of("dirt", "sand", "mud"));
    static final Set<String> VOCABULARY = Set.of("aa/AaManual.java", "aa/AaTree.java", "commands/VerbTable.java", "commands/PolicyCommands.java",
            "vocab/Kinds.java", "surface/SurfaceFamily.java", "surface/SurfaceExport.java", "aa/FoodOptions.java",
            // the owner's typed words: the hotbar kinds (pickaxe, sword, ... torch), the goal grammar's verbs (furnace, table...); what a word is comes from the game
            "engine/HotbarRules.java", "plan/GoalGrammar.java");
    /** Files not converted yet: none (rm-final emptied the list; a new name list in a collection literal fails the build). */
    static final Set<String> PENDING = Set.of();

    /** Whole words (split on _ and :) that name vanilla items, blocks and mobs. */
    static final Set<String> NAMES = Set.of("cobblestone", "cobbled", "dirt", "gravel", "sand", "netherrack", "deepslate", "andesite", "diorite", "granite", "tuff",
            "ingot", "iron", "gold", "golden", "diamond", "copper", "coal", "charcoal", "redstone", "lapis", "emerald", "obsidian", "planks", "log", "torch", "beef", "porkchop", "mutton", "chicken", "rabbit", "bread", "apple", "wool", "leather", "string",
            "bucket", "furnace", "crafting", "chest", "cow", "pig", "sheep", "zombie", "creeper", "skeleton", "spider", "wooden", "netherite", "chainmail", "ore",
            "stone", "oak", "feather", "snowball", "flesh", "potato", "carrot", "kelp", "cod", "salmon", "minecraft");

    static final Pattern CALL = Pattern.compile("\\b(Map|List|Set)\\.(of|ofEntries|copyOf)\\(|Map\\.entry\\(");
    static final Pattern STR = Pattern.compile("\"([a-z0-9_:/.*-]+)\"");
    static final Pattern STEP_ARGS = Pattern.compile("new (?:[\\w.]*\\.)?Step\\.\\w+\\([^()]*\\)");

    @Test
    void noVanillaNamesInCollectionLiteralsOfTheBrainAndJunkRules() throws IOException {
        List<String> hits = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        for (String d : SCANNED_DIRS) try (Stream<Path> s = Files.walk(ROOT.resolve(d))) { s.filter(p -> p.toString().endsWith(".java")).forEach(files::add); }
        for (String f : SCANNED_FILES) files.add(ROOT.resolve(f));
        for (Path p : files) {
            String rel = ROOT.relativize(p).toString().replace('\\', '/');
            if (VOCABULARY.contains(rel) || PENDING.contains(rel)) continue;
            String src = Files.readString(p);
            Matcher m = CALL.matcher(src);
            while (m.find()) {
                int end = close(src, m.end() - 1);
                // rm-execution2: a typed step's own target (new Step.Craft("torch", n): the item the need is about) is one
                // argument of one step, no name list; the steps a list holds are read without their arguments
                String body = STEP_ARGS.matcher(src.substring(m.end(), end)).replaceAll("Step()");
                Matcher s = STR.matcher(body);
                while (s.find()) {
                    String lit = s.group(1);
                    if (ALLOWED.contains(lit) || (ALLOWED_IN.getOrDefault(rel, Set.of())).contains(lit)) continue;
                    for (String w : lit.split("[_:/.*-]+")) if (NAMES.contains(w)) {
                        hits.add(ROOT.relativize(p) + ":" + line(src, m.start()) + " " + lit);
                        break;
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), "vanilla names in collection literals (read the game's data instead): " + hits);
    }

    private static int close(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') { i++; while (i < s.length() && s.charAt(i) != '"') { if (s.charAt(i) == '\\') i++; i++; } }
            else if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return s.length();
    }

    private static int line(String s, int at) {
        int n = 1;
        for (int i = 0; i < at; i++) if (s.charAt(i) == '\n') n++;
        return n;
    }
}

