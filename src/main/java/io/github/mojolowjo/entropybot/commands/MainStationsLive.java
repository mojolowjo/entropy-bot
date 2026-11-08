package io.github.mojolowjo.entropybot.commands;

import baritone.api.BaritoneAPI;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.Quiet;
import io.github.mojolowjo.entropybot.aa.MainStations;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.StationFact;
import io.github.mojolowjo.entropybot.io.BotFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * main-stations (0.39.z35, owner 2026-10-23), the game side of {@link MainStations}: aa look, aa station main|list|clear,
 * aa deposit [x y z]; the mains saved per world in {@value #FILE} beside the station memory (restore.json), loaded with it.
 * The look turn is Baritone's: {@code RotationUtils.reachable} (a face in reach) handed to {@code ILookBehavior.updateTarget};
 * out of reach = no turn (0.39.z54 rules gate); no camera setter of ours (Baritone decides how the head turns). The
 * crosshair block is the client's {@code Minecraft.hitResult}. Loader notes: Baritone API, vanilla client hit result.
 */
final class MainStationsLive {
    private MainStationsLive() {}

    static final String FILE = "stations-main.json";
    private static volatile BotFiles files;
    private static volatile boolean readOnly;

    /** With the restore ledger's load (Commands): this world's mains. A line for the log. */
    static String load(BotFiles f) {
        return loadMains(f) + "; " + loadBook(f);
    }

    /** D14: the station book's file (every station placed or seen, gone ones with their reason). */
    static final String BOOK_FILE = "stations-book.json";

    /** D14: this world's station book, and its saver (a file that cannot be read is never overwritten: no saver, said). */
    private static String loadBook(BotFiles f) {
        io.github.mojolowjo.entropybot.aa.StationBook.saver = null;
        String text = f.readJson(BOOK_FILE);
        StringBuilder note = new StringBuilder();
        if (text == null) io.github.mojolowjo.entropybot.aa.StationBook.current = new io.github.mojolowjo.entropybot.aa.StationBook();
        else {
            JsonObject o = JsonStore.parse(text);
            if (o == null) {
                io.github.mojolowjo.entropybot.aa.StationBook.current = new io.github.mojolowjo.entropybot.aa.StationBook();
                String line = BOOK_FILE + ": can't be read - READ-ONLY, not saved this session (fix or delete the file, then restart)";
                Quiet.line(line);
                return line;
            }
            io.github.mojolowjo.entropybot.aa.StationBook.current = io.github.mojolowjo.entropybot.aa.StationBook.fromJson(o, note);
        }
        io.github.mojolowjo.entropybot.aa.StationBook.saver = () -> {
            String r = f.writeJson(BOOK_FILE, io.github.mojolowjo.entropybot.aa.StationBook.now().toJson().toString());
            if (!r.startsWith("ok")) Quiet.line("stations: " + BOOK_FILE + " not saved: " + r);
        };
        return BOOK_FILE + ": " + io.github.mojolowjo.entropybot.aa.StationBook.now().all().size() + " stations" + (note.length() > 0 ? " (" + note + ")" : "");
    }

    private static String loadMains(BotFiles f) {
        files = f;
        readOnly = false;
        String text = f.readJson(FILE);
        if (text == null) {
            MainStations.current = new MainStations();
            return FILE + ": none yet";
        }
        JsonObject o = JsonStore.parse(text);
        if (o == null) {
            readOnly = true;          // like restore.json: a file that cannot be read is never overwritten
            MainStations.current = new MainStations();
            String line = FILE + ": can't be read - READ-ONLY (fix or delete the file, then restart)";
            Quiet.line(line);
            return line;
        }
        StringBuilder note = new StringBuilder();
        MainStations m = MainStations.fromJson(o, note);
        MainStations.current = m;
        return FILE + ": " + m.all().size() + " mains" + (note.length() > 0 ? " (" + note + ")" : "");
    }

    private static String save() {
        BotFiles f = files;
        if (f == null) return " (not saved: no world folder loaded)";
        if (readOnly) return " (not saved: " + FILE + " is READ-ONLY)";
        String r = f.writeJson(FILE, MainStations.now().toJson().toString());
        return r.startsWith("ok") ? "" : " (not saved: " + r + ")";
    }

    private static String kindOf(Commands c, String word) {
        return MainStations.kindOf(word, c.nearby::blocksOfKind);
    }

    private static String kinds() {
        List<String> w = new ArrayList<>();
        for (String k : MainStations.KINDS) w.add(MainStations.word(k));
        return String.join("|", w);
    }

    /** The crosshair block: {x, y, z} and its id, or null (not on a block). */
    private record Hit(int[] at, String block) {}

    private static Hit crosshair() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.hitResult instanceof BlockHitResult bh) || bh.getType() != HitResult.Type.BLOCK) return null;
        BlockPos q = bh.getBlockPos();
        return new Hit(new int[]{q.getX(), q.getY(), q.getZ()}, BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(q).getBlock()).toString());
    }

    /** aa look [smooth <ticks>] | aa look &lt;kind&gt;. */
    static String look(Commands c, AaRuntime aa, String arg) {
        String a = arg == null ? "" : arg.trim();
        if (a.startsWith("smooth")) return aa.look(a.equals("smooth") ? "" : a);
        if (a.isEmpty()) {
            Hit h = crosshair();
            if (h == null) return "aa look: my crosshair is on no block";
            String k = MainStations.kindOfBlock(h.block(), c.nearby::blocksOfKind);
            return "aa look: my crosshair is on " + h.block() + " at " + h.at()[0] + " " + h.at()[1] + " " + h.at()[2]
                    + (k == null ? "" : " (a " + MainStations.word(k) + (MainStations.now().at(h.at()[0], h.at()[1], h.at()[2], null) != null ? ", my main" : "") + ")");
        }
        String kind = kindOf(c, a);
        if (kind == null) return c.nearby.blocksOfKind(io.github.mojolowjo.entropybot.aa.brain.ItemWorth.CRAFT) == null
                ? "aa look: item data not loaded yet" : "usage: aa look [" + kinds() + "] (no station kind \"" + a + "\")";
        StationFact f = c.nearby.factOfKind(kind);
        return switch (f) {
            case StationFact.NotLoaded n -> "aa look: " + StationFact.NOT_LOADED_LINE + " (" + n.why() + ")";
            case StationFact.None n -> "aa look: no " + MainStations.word(kind) + " known (" + n.why() + ")";
            case StationFact.Known k -> {
                Spot s = k.spots().get(0);
                for (Spot x : k.spots()) if (x.dist() < s.dist()) s = x;           // the closest, not the cheapest
                String turn = turnTo(s.x(), s.y(), s.z(), s.dist());
                yield "I look at " + s.block() + " at " + s.x() + " " + s.y() + " " + s.z() + ", " + Math.round(s.dist()) + " m" + turn;
            }
        };
    }

    /**
     * Hands the turn to Baritone's look behaviour when Baritone finds a face in reach; "" or " (why not)". 0.39.z54 rules
     * gate (rule 2, no choosers): out of reach = no turn (the old fallback to the block's centre was a look target chosen here).
     */
    private static String turnTo(int x, int y, int z, double dist) {
        try {
            var b = BaritoneAPI.getProvider().getPrimaryBaritone();
            var ctx = b.getPlayerContext();
            if (ctx.player() == null) return " (no player to turn)";
            Optional<Rotation> r = RotationUtils.reachable(ctx, new BlockPos(x, y, z));
            if (r.isEmpty()) return " (out of reach (" + Math.round(dist) + " m): not turned; Baritone turns only to a face it can reach)";
            b.getLookBehavior().updateTarget(r.get(), false);
            return "";
        } catch (RuntimeException | LinkageError e) {
            Quiet.line("aa look: the turn failed: " + e);
            return " (the turn failed: " + e + ")";
        }
    }

    /** aa station main|list|clear &lt;kind&gt;. */
    static String station(Commands c, String arg) {
        String a = arg == null ? "" : arg.trim();
        String[] w = a.split("\\s+", 2);
        switch (w[0]) {
            case "main" -> {
                Hit h = crosshair();
                if (h == null) return "aa station main: my crosshair is on no block (aa look <" + kinds() + "> first)";
                String k = MainStations.kindOfBlock(h.block(), c.nearby::blocksOfKind);
                if (k == null) return "aa station main: " + h.block() + " at " + h.at()[0] + " " + h.at()[1] + " " + h.at()[2] + " is no station (" + kinds() + ")";
                return mark(c, k, h.at(), h.block());
            }
            case "list", "" -> { return MainStations.now().line(); }
            case "clear" -> {
                String k = w.length > 1 ? kindOf(c, w[1]) : null;
                if (k == null) return "usage: aa station clear <" + kinds() + ">";
                String r = MainStations.now().clear(k);
                c.nearby.forget();
                return "aa station: " + r + save();
            }
            default -> { return "usage: aa station main|list|clear <" + kinds() + ">"; }
        }
    }

    private static String mark(Commands c, String kind, int[] at, String block) {
        if (MainStations.current == null) return "aa station: no world loaded";
        String r = MainStations.now().mark(new MainStations.Main(kind, at[0], at[1], at[2], block));
        c.nearby.forget();                       // the cached station facts price the new main from the next lookup
        return "aa station: " + r + save();
    }

    /** aa deposit: the deposit chests, the main first; aa deposit x y z: mark that chest the main. */
    static String deposit(Commands c, String arg) {
        String a = arg == null ? "" : arg.trim();
        if (!a.isEmpty()) {
            String[] w = a.split("\\s+");
            int[] at;
            try {
                if (w.length != 3) throw new NumberFormatException();
                at = new int[]{Integer.parseInt(w[0]), Integer.parseInt(w[1]), Integer.parseInt(w[2])};
            } catch (NumberFormatException e) {
                return "usage: aa deposit [x y z]";
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return "aa deposit: no world";
            if (!mc.level.isLoaded(new BlockPos(at[0], at[1], at[2]))) return "aa deposit: the chunk at " + a + " is not loaded: stand nearer to mark it";
            String id = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(new BlockPos(at[0], at[1], at[2])).getBlock()).toString();
            var ids = c.nearby.blocksOfKind(MainStations.CONTAINER);
            if (ids == null) {
                // the world's first seconds (runs JS-JX, 2026-11-03: every unattended run's mark was lost to "item data not loaded
                // yet"): a chest is a chest by its block entity (a game fact), so the mark is taken now; the list check below says
                // the item data is still out
                if (!(mc.level.getBlockEntity(new BlockPos(at[0], at[1], at[2])) instanceof net.minecraft.world.Container))
                    return "aa deposit: item data not loaded yet, and " + id + " at " + a + " is no container block";
            } else if (!ids.contains(id)) return "aa deposit: " + id + " at " + a + " is no chest the deposit uses";
            String r = mark(c, MainStations.CONTAINER, at, id);
            // run HQ (rule 7): the mark is said with whether the deposit's own lookup lists it now - a refused or unlisted main is
            // said here, at the mark, not 20 minutes later as "no chest known"
            StationFact f = c.nearby.containers();
            boolean listed = f instanceof StationFact.Known k && k.spots().stream().anyMatch(s -> s.x() == at[0] && s.y() == at[1] && s.z() == at[2]);
            return listed ? r : r + " - BUT the deposit's lookup does not list it: " + switch (f) {
                case StationFact.None n -> n.why();
                case StationFact.NotLoaded n -> n.why();
                case StationFact.Known k -> "not among its " + k.spots().size() + " known chest(s)";
            };
        }
        StationFact f = c.nearby.containers();
        return switch (f) {
            case StationFact.NotLoaded n -> "aa deposit: " + StationFact.NOT_LOADED_LINE + " (" + n.why() + ")";
            case StationFact.None n -> "aa deposit: no chest known (" + n.why() + ")";
            case StationFact.Known k -> {
                List<String> out = new ArrayList<>();
                for (Spot s : k.spots()) {
                    boolean main = MainStations.now().at(s.x(), s.y(), s.z(), null) != null;
                    String line = s.block().replaceFirst("^minecraft:", "") + " " + s.x() + " " + s.y() + " " + s.z() + " " + Math.round(s.dist()) + " m" + (main ? " (main)" : "");
                    if (main) out.add(0, line); else out.add(line);          // the listing shows the main first; the deposit's pick is by price
                }
                yield "aa deposit chests (main first, then by price; the main's bonus " + Math.round(MainStations.bonusS()) + " s): " + String.join(" | ", out)
                        + io.github.mojolowjo.entropybot.aa.Nearby.notesLine(k.notes());
            }
        };
    }
}
