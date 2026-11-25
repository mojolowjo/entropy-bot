package io.github.mojolowjo.entropybot.aa.brain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * deposit-goal review 4 (point 1: the put's "chest at X full: N items left" and "chest at X gone" parked the deposit goal
 * as a place park, so every move woke it, it walked back to the same chest and failed again, without end): the one store
 * (rule 23) of the main chest's refusal as a fact that holds while it is true. A full chest holds until its content can
 * have changed as far as the mod can know: the owner's word ({@code aa deposit x y z}, {@link #lift}), a new main chest
 * set (the reader's cell differs), or the chest opened again showing room ({@link #roomSeen}). A gone chest holds until a
 * main chest is set again or its block is read again as a container. No timer: nothing the mod sees says when someone
 * emptied the chest. While it holds the reader ({@link MainChest#of}) answers {@link MainChest.Held}. Each record and each
 * lift returns its line, said once by the caller (rule 7). No Minecraft or Baritone types. Loader notes: none.
 */
public final class MainChestHold {
    /** The blackboard fact: the store. */
    public static final String FACT = "aa.mainChestHold";

    /** The start of a gone chest's words. */
    public static final String GONE_WORDS = "the main chest at ";

    /** The deposit leg's words (DepositRule.full, DepositRule.gone): "chest at x y z full: N item(s) left" / "chest at x y z gone". */
    private static final Pattern FULL = Pattern.compile("chest at (-?\\d+) (-?\\d+) (-?\\d+) full: (\\d+) items? left");
    private static final Pattern GONE = Pattern.compile("chest at (-?\\d+) (-?\\d+) (-?\\d+) gone");

    /** What a refusal is. */
    public enum Kind { FULL, GONE }

    /** The hold: its kind, its cell, its words. */
    public record Hold(Kind kind, int x, int y, int z, String why) {
        boolean at(int ax, int ay, int az) { return x == ax && y == ay && z == az; }
    }

    /** The reader's answer with the hold applied, and the line to say (null: nothing changed). */
    public record Applied(MainChest.Answer answer, String line) {}

    private Hold hold;

    /** The hold now; null: none. */
    public Hold hold() { return hold; }

    /** The refusal in a step's or a run's words; null: none. */
    public static Hold refusal(String text) {
        if (text == null) return null;
        Matcher m = FULL.matcher(text);
        if (m.find()) {
            int x = Integer.parseInt(m.group(1)), y = Integer.parseInt(m.group(2)), z = Integer.parseInt(m.group(3));
            // review 6: a chest that refused the items while it showed empty slots is held as refusing them, not as full
            int room = text.indexOf(DepositRule.ROOM_SEEN);
            String what = room < 0 ? "is full" : "refused the last put while it showed " + text.substring(room + DepositRule.ROOM_SEEN.length(), text.indexOf(')', room)).replace(" at the count", "");
            return new Hold(Kind.FULL, x, y, z, "the main chest at " + x + " " + y + " " + z + " " + what + " (" + m.group(4) + " left out of the last put)");
        }
        m = GONE.matcher(text);
        if (m.find()) {
            int x = Integer.parseInt(m.group(1)), y = Integer.parseInt(m.group(2)), z = Integer.parseInt(m.group(3));
            return new Hold(Kind.GONE, x, y, z, GONE_WORDS + x + " " + y + " " + z + " is gone (no container there at the put)");
        }
        return null;
    }

    /** What lifts a hold of this kind, in words. */
    public static String liftWords(Kind k) {
        return k == Kind.FULL
                ? "the owner's word (aa deposit x y z), a new main chest, or the chest opened showing room"
                : "a main chest set (aa deposit x y z), or its block seen again as a container";
    }

    /** A put's refusal in {@code text} becomes the hold; the line, or null when the text holds none or the same hold stands. */
    public String refused(String text) {
        Hold h = refusal(text);
        if (h == null) return null;
        // review 6 (rule 7, once): the same kind at the same cell is the same hold, whatever words recorded it
        if (hold != null && hold.kind() == h.kind() && hold.at(h.x(), h.y(), h.z())) return null;
        hold = h;
        return "deposit held: " + h.why() + "; it holds until " + liftWords(h.kind());
    }

    /** The owner's word on the main chest ({@code why}): the hold is lifted; the line, or null when none held. */
    public String lift(String why) {
        if (hold == null) return null;
        Hold h = hold;
        hold = null;
        return "deposit hold lifted: " + h.why() + " - " + why;
    }

    /** The chest at the cell was opened with {@code free} empty slots: a full hold there is lifted when it shows room. */
    public String roomSeen(int x, int y, int z, int free) {
        if (hold == null || hold.kind() != Kind.FULL || !hold.at(x, y, z) || free <= 0) return null;
        return lift("the chest was opened showing room in " + free + " slot" + (free == 1 ? "" : "s") + " (empty, or a stack of an owed item below its size)");
    }

    /** The reader's answer with the hold applied (see the class). */
    public Applied apply(MainChest.Answer a) {
        int[] c = switch (a) {
            case MainChest.None n -> null;
            case MainChest.NotLoaded n -> new int[]{n.x(), n.y(), n.z()};
            case MainChest.Known k -> new int[]{k.x(), k.y(), k.z()};
            case MainChest.Held h -> new int[]{h.x(), h.y(), h.z()};
        };
        String line = null;
        if (hold != null && (c == null || !hold.at(c[0], c[1], c[2])))
            line = lift(c == null ? "no main chest is set now" : "a new main chest is set at " + c[0] + " " + c[1] + " " + c[2]);
        if (a instanceof MainChest.Held h && (hold == null || hold.kind() != Kind.GONE)) {
            // the live read: the block is no container - held as gone, so an unloaded chunk later still says it
            hold = new Hold(Kind.GONE, h.x(), h.y(), h.z(), h.why());
            String rec = "deposit held: " + h.why() + "; it holds until " + liftWords(Kind.GONE);
            return new Applied(held(), line == null ? rec : line + "; " + rec);
        }
        if (hold != null && hold.kind() == Kind.GONE && a instanceof MainChest.Known) {
            String l = lift("its block is a container again");
            return new Applied(a, line == null ? l : line + "; " + l);
        }
        if (hold != null) return new Applied(held(), line);
        return new Applied(a, line);
    }

    private MainChest.Held held() {
        return new MainChest.Held(hold.x(), hold.y(), hold.z(), hold.why() + "; it holds until " + liftWords(hold.kind()));
    }
}
