package io.github.mojolowjo.entropybot.camp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * C5 (inventory hygiene): the junk list (one definition of junk: {@link io.github.mojolowjo.entropybot.aa.brain.Junk}, from the game's data; the owner's {@code junk add|remove|list|default|mode drop|chest}, commands.json "junk"
 * [ids] and "junkMode"). Pure.
 *
 * <p>Never junk, whatever the list says: tools, armor, food, torches, supplies, ore drops and other valuables ({@link #never}).
 *
 * <p>Loader notes: none.
 */
public final class JunkRules {
    private JunkRules() {}

    /** Never on the owner's list (a typo or a bad idea): what the deposit keeps (gear, food, light, bench) and anything worth over {@code junk.neverWorth}. */
    static boolean never(String id) {
        return io.github.mojolowjo.entropybot.aa.brain.Kit.kept(id) || io.github.mojolowjo.entropybot.aa.brain.Kit.itemValue(id) > io.github.mojolowjo.entropybot.aa.brain.Tuning.get("junk.neverWorth");
    }

    public static final String USAGE = "usage: junk list | junk add <item> ... | junk remove <item> ... | junk default | junk mode drop|chest";

    public static String full(String id) {
        String s = id.trim().toLowerCase(Locale.ROOT);
        return s.contains(":") ? s : "minecraft:" + s;
    }

    /** Adds ids; the refused ones (never-junk) come back in refused. */
    public static Set<String> add(Set<String> list, List<String> ids, List<String> refused) {
        Set<String> out = new LinkedHashSet<>(list);
        for (String i : ids) {
            String id = full(i);
            if (never(id)) refused.add(shortId(id));
            else out.add(id);
        }
        return out;
    }

    public static Set<String> remove(Set<String> list, List<String> ids) {
        Set<String> out = new LinkedHashSet<>(list);
        for (String i : ids) out.remove(full(i));
        return out;
    }

    public static String listText(Set<String> junk, String mode) {
        List<String> s = new ArrayList<>();
        for (String id : junk) s.add(shortId(id));
        return "junk (" + ("chest".equals(mode) ? "into the chest marked junk on base trips" : "listed only: nothing throws it away mid-job") + "): "
                + (s.isEmpty() ? "nothing" : String.join(", ", s)) + " - cobblestone keeps 64; tools, food, torches, supplies and ores never go";
    }

    public static String shortId(String id) {
        return id != null && id.startsWith("minecraft:") ? id.substring(10) : id;
    }
}
