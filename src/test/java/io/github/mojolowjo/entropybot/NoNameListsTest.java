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
    static final List<String> SCANNED_DIRS = List.of("aa/brain");
    static final List<String> SCANNED_FILES = List.of("camp/JunkRules.java", "commands/ItemWorthSource.java", "commands/MiningTierSource.java");
    /** The verb vocabulary and other words that are no item: allowed in a literal even when they look like one. */
    static final Set<String> ALLOWED = Set.of();

    /** Whole words (split on _ and :) that name vanilla items, blocks and mobs. */
    static final Set<String> NAMES = Set.of("cobblestone", "cobbled", "dirt", "gravel", "sand", "netherrack", "deepslate", "andesite", "diorite", "granite", "tuff",
            "ingot", "iron", "gold", "golden", "diamond", "copper", "coal", "charcoal", "redstone", "lapis", "emerald", "obsidian", "planks", "log", "pickaxe", "axe", "shovel",
            "sword", "helmet", "chestplate", "leggings", "boots", "torch", "beef", "porkchop", "mutton", "chicken", "rabbit", "bread", "apple", "wool", "leather", "string",
            "bucket", "furnace", "crafting", "bed", "chest", "cow", "pig", "sheep", "zombie", "creeper", "skeleton", "spider", "wooden", "netherite", "chainmail", "ore",
            "stone", "oak", "feather", "snowball", "flesh", "potato", "carrot", "kelp", "cod", "salmon", "minecraft");

    static final Pattern CALL = Pattern.compile("\\b(Map|List|Set)\\.(of|ofEntries|copyOf)\\(|Map\\.entry\\(");
    static final Pattern STR = Pattern.compile("\"([a-z0-9_:/.*-]+)\"");

    @Test
    void noVanillaNamesInCollectionLiteralsOfTheBrainAndJunkRules() throws IOException {
        List<String> hits = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        for (String d : SCANNED_DIRS) try (Stream<Path> s = Files.walk(ROOT.resolve(d))) { s.filter(p -> p.toString().endsWith(".java")).forEach(files::add); }
        for (String f : SCANNED_FILES) files.add(ROOT.resolve(f));
        for (Path p : files) {
            String src = Files.readString(p);
            Matcher m = CALL.matcher(src);
            while (m.find()) {
                int end = close(src, m.end() - 1);
                String body = src.substring(m.end(), end);
                Matcher s = STR.matcher(body);
                while (s.find()) {
                    String lit = s.group(1);
                    if (ALLOWED.contains(lit)) continue;
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
