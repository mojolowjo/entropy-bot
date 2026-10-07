package io.github.mojolowjo.entropybot.brain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * B1 (BRAIN_PLAN 4.4, the owner's answer 4.2): before a task, is there enough on the bot for it, sized to the task?
 * A dig of a box: pickaxe durability for its block count (and shovel durability for the dirt part when known);
 * {@code mine strip|cave} and {@code explore}: a kit per minute (food 1 per 5 min, a pickaxe per 20 min of mining,
 * 8 torches per 10 min of mining). Short: a supply chain to run first and the whisper that says so. The stage lowers
 * the bar: at {@code nothing} no pickaxes are planned (nothing to make them from) and food is halved. Pure.
 */
public final class SupplyCheck {
    private SupplyCheck() {}

    /** The steps to run first and the line that says why. */
    public record Supply(String chain, String why) {}

    public static int durability(String tier) {
        return switch (tier) {
            case "wooden" -> 59;
            case "stone" -> 131;
            case "iron" -> 250;
            case "diamond" -> 1561;
            default -> 131;
        };
    }

    /** The pickaxe the stage can make: wood -> wooden, stone -> stone, iron and up -> iron; nothing -> null. */
    public static String tier(String stage) {
        return switch (stage == null ? "" : stage) {
            case "wood" -> "wooden";
            case "stone" -> "stone";
            case "iron", "diamond" -> "iron";
            default -> null;
        };
    }

    /** The supply chain for a job, or null when nothing is short (or the job is not one it sizes). dirtBlocks: of the box, when known. */
    public static Supply before(String job, BrainState s, int dirtBlocks, int shovelDurability) {
        if (job == null) return null;
        String[] w = job.trim().toLowerCase(Locale.ROOT).split("\\s+");
        List<String> steps = new ArrayList<>(), why = new ArrayList<>();
        String tier = tier(s.stage);
        if (w[0].equals("dig") && w.length >= 7 && allInts(w, 1, 7)) {
            long blocks = 1;
            for (int i = 0; i < 3; i++) blocks *= Math.abs(Long.parseLong(w[1 + i]) - Long.parseLong(w[4 + i])) + 1;
            long stone = Math.max(0, blocks - dirtBlocks);
            if (tier != null && stone > s.pickDurability) {
                long n = (long) Math.ceil((stone - s.pickDurability) / (double) durability(tier));
                steps.add("craft " + tier + "_pickaxe " + n);
                why.add(n + " " + tier + " pickaxe" + (n == 1 ? "" : "s"));
            }
            if (tier != null && dirtBlocks > shovelDurability) {
                long n = (long) Math.ceil((dirtBlocks - shovelDurability) / (double) durability(tier));
                steps.add("craft " + tier + "_shovel " + n);
                why.add(n + " " + tier + " shovel" + (n == 1 ? "" : "s"));
            }
            return steps.isEmpty() ? null : new Supply(String.join(" then ", steps), "getting " + String.join(" and ", why) + " first (dig of " + blocks + " blocks)");
        }
        int minutes;
        boolean mining;
        if (w[0].equals("mine") && w.length >= 2 && (w[1].equals("strip") || w[1].equals("cave"))) {
            if (w.length >= 3 && w[2].matches("status|reset|turn|ores")) return null;
            mining = true;
            minutes = 20;
            for (String x : w) if (x.matches("^\\d{1,3}m$")) minutes = Integer.parseInt(x.substring(0, x.length() - 1));
        } else if (w[0].equals("explore")) {
            mining = false;
            minutes = 5;
            if (w.length >= 2 && w[1].matches("^\\d{1,3}m?$")) minutes = Integer.parseInt(w[1].replace("m", ""));
            else if (w.length >= 2) return null;            // explore status, explore <area>...
        } else return null;
        int food = (int) Math.ceil(minutes / 5.0);
        if ("nothing".equals(s.stage)) food = (food + 1) / 2;
        if (s.foodItems < food) {
            steps.add("get food " + (food - s.foodItems));
            why.add((food - s.foodItems) + " food");
        }
        if (mining && tier != null) {
            int picks = (int) Math.ceil(minutes / 20.0);
            if (s.pickaxes < picks) {
                steps.add("craft " + tier + "_pickaxe " + (picks - s.pickaxes));
                why.add((picks - s.pickaxes) + " " + tier + " pickaxe" + (picks - s.pickaxes == 1 ? "" : "s"));
            }
            int torches = (int) Math.ceil(minutes / 10.0) * 8;
            if (s.torches < torches) {
                steps.add("craft torch " + (torches - s.torches));
                why.add((torches - s.torches) + " torches");
            }
        }
        return steps.isEmpty() ? null : new Supply(String.join(" then ", steps), "getting " + String.join(" and ", why) + " first (" + minutes + " min of " + (mining ? "mining" : "exploring") + ")");
    }

    static boolean allInts(String[] w, int from, int to) {
        for (int i = from; i < to; i++) if (!w[i].matches("^-?\\d{1,7}$")) return false;
        return true;
    }
}
