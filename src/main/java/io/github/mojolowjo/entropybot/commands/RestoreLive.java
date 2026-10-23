package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.guard.Box;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.guard.MixinFlags;
import io.github.mojolowjo.entropybot.guard.Policy;
import io.github.mojolowjo.entropybot.io.BotFiles;
import io.github.mojolowjo.entropybot.restore.BuildSpotter;
import io.github.mojolowjo.entropybot.restore.Ledger;
import io.github.mojolowjo.entropybot.restore.RestoreArgs;
import io.github.mojolowjo.entropybot.restore.RestoreBook;
import io.github.mojolowjo.entropybot.restore.RestorePlan;
import io.github.mojolowjo.entropybot.restore.RestoreRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P1 (survival plan), the game side of the restore ledger: the break hook's handler ({@link #onBreak}, called by
 * RestoreMixinGameMode at HEAD of MultiPlayerGameMode.destroyBlock), the incidental-break contexts (Baritone tunnelling
 * in "mine ... dig", the escape dig-out), restore.json, the restore runs a job's end starts, the "restore" verb, the
 * foreign-build hints and the check lines. A break is recorded only while a job runs and only when it is not that
 * job's purpose: the mine's target block, a clear's / strip mine's / cave's own blocks are never recorded (their
 * jobs set no context), and a person playing in the window (no job) is never recorded.
 */
public final class RestoreLive {
    private static final Logger LOG = LogUtils.getLogger();
    public static final RestoreLive INSTANCE = new RestoreLive();
    static final String FILE = "restore.json";
    static final long FLUSH_AFTER = 20; // structural

    private Commands c;
    private BotFiles files;
    private RestoreBook book = new RestoreBook();
    private boolean readOnly;
    private String loadNote = "not loaded";
    private long dirtySince = -1;
    private String writeError;
    private volatile long breaksSeen, recorded;
    private volatile String hookError;
    private int hookErrors;
    // the incidental contexts
    private volatile long pathJob = -1;
    private volatile Set<String> pathTargets = Set.of();
    private final Set<String> warnedHints = new HashSet<>();

    private RestoreLive() {}

    // ---- files ----

    /** Once, at the first tick in a world. A line for the log. */
    public synchronized String load(Commands commands, BotFiles f) {
        c = commands;
        files = f;
        io.github.mojolowjo.entropybot.clear.OwnStations.mine = this::stationOfMine;     // 0.33.0 (A3)
        io.github.mojolowjo.entropybot.clear.OwnStations.cells = this::stationCells;     // 0.33.0 review: recorded cells only
        io.github.mojolowjo.entropybot.clear.OwnStations.why = this::stationRefusal;
        io.github.mojolowjo.entropybot.clear.OwnStations.placed = this::stationsPlaced;   // station-memory 0.39.z14
        io.github.mojolowjo.entropybot.clear.OwnStations.forget = this::forgetStation;
        io.github.mojolowjo.entropybot.guard.OwnPlaced.cells = this::builtByMe;      // rm-mining: my placements are mine, never a built block
        readOnly = false;                    // 0.31.0 (c): loaded again for another world
        dirtySince = -1;
        String text = f.readJson(FILE);
        if (text == null) {
            book = new RestoreBook();
            loadNote = FILE + ": none yet";
            return loadNote;
        }
        JsonObject o = JsonStore.parse(text);
        if (o == null) {
            // like memory.json: a file that can't be read is never overwritten; the bot records nothing new
            readOnly = true;
            book = new RestoreBook();
            loadNote = FILE + ": can't be read - READ-ONLY (fix or delete the file, then restart)";
            LOG.warn("[entropybot] {}", loadNote);
            return loadNote;
        }
        StringBuilder note = new StringBuilder();
        book = RestoreBook.fromJson(o, note);
        loadNote = FILE + ": " + book.ledger.size() + " blocks to put back, " + book.hints.size() + " build hints, mode " + book.mode
                + (note.length() > 0 ? " (" + note + ")" : "");
        String capNote = book.ledger.takeNote();
        if (capNote != null) LOG.info("[entropybot] restore: {}", capNote);
        dugFromLedger();
        return loadNote;
    }

    /** The refill rule's dug set starts over from this world's ledger: the cells I broke (not the ones I built). */
    private void dugFromLedger() {
        List<String> keys = new ArrayList<>();
        for (Ledger.Entry e : book.ledger.all()) if (e.why == null || !e.why.startsWith("built:")) keys.add(e.key());
        io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.resetDug(keys);
    }

    private void changed() {
        if (dirtySince < 0) dirtySince = Core.INSTANCE.tick();
    }

    /** 0.31.0 (c): written now, before the folder changes to another world's. */
    public synchronized void flushNow() {
        if (dirtySince >= 0) flush();
    }

    private synchronized void flush() {
        if (files == null || readOnly) return;
        String r = files.writeJson(FILE, book.toJson().toString());
        if (r.startsWith("ok")) {
            dirtySince = -1;
            writeError = null;
        } else if (writeError == null) {
            writeError = r;
            LOG.warn("[entropybot] restore: couldn't write {}: {}", FILE, r);
        }
    }

    /** Every tick in a world: pending breaks settled, the file written a second after a change, the cap's note logged. */
    public void tick(LocalPlayer p, long now) {
        if (files == null) return;
        if (now % 5 == 0 && !book.ledger.pending().isEmpty()) {
            Level level = Minecraft.getInstance().level;
            int ch = book.ledger.settle((dim, x, y, z) -> {
                if (level == null || !Guard.dimOf(level).equals(dim)) return Ledger.Cell.UNKNOWN;
                BlockPos bp = new BlockPos(x, y, z);
                if (!level.isLoaded(bp)) return Ledger.Cell.UNKNOWN;
                BlockState st = level.getBlockState(bp);
                return st.isAir() || !st.getFluidState().isEmpty() ? Ledger.Cell.EMPTY : Ledger.Cell.FULL;
            }, now);
            if (ch > 0) changed();
        }
        String note = book.ledger.takeNote();
        if (note != null) LOG.info("[entropybot] restore: {}", note);
        if (dirtySince >= 0 && now - dirtySince >= FLUSH_AFTER) flush();
    }

    // ---- the hook ----

    public void hookError(Throwable t) {
        hookError = t.toString();
        if (hookErrors++ < 5) LOG.warn("[entropybot] restore hook: {}", t.toString());
    }

    /** From the mixin, before the client breaks pos. Never throws (the mixin catches too). */
    public void onBreak(BlockPos pos) {
        breaksSeen++;
        Commands cm = c;
        Minecraft mc = Minecraft.getInstance();
        if (cm == null || mc.level == null || readOnly) return;
        Jobs.Job j = cm.jobs.running() ? cm.jobs.job : null;
        if (j == null) return;                               // a person playing in the window: not the bot's doing
        String dim = Guard.dimOf(mc.level);
        String key = dim + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        BlockState st = mc.level.getBlockState(pos);
        if (st.isAir()) return;
        String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
        // owner's place rule (aa372): every cell a job of mine breaks may be refilled for about nothing (BreakPolicy)
        io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.markDug(key);
        String reason;
        if (pathJob == j.id && !pathTargets.contains(id) && !purpose(id)) reason = Ledger.PATH;
        // mining plan phase 1: a walk's break under the travel policy (the guard marked it) is a path break of that walk
        else if (io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.takeTravelBreak(key)) reason = Ledger.PATH;
        else return;                                         // the job's purpose (or no incidental context)
        // V1a: the ledger is off inside a destroy area (AreaTypeRules.restore)
        if (!io.github.mojolowjo.entropybot.guard.AreaTypeRules.restore(Guard.INSTANCE.core.basePolicy().typeAt(dim, pos.getX(), pos.getY(), pos.getZ()))) return;
        boolean ore = st.is(Tags.Blocks.ORES);
        String why = RestoreRules.whyNot(id, ore);
        List<String> items = why == null ? RestoreRules.itemsFor(id) : List.of();
        if ("not terrain".equals(why)) {
            LOG.warn("[entropybot] restore: broke non-terrain {} at {} {} {} (guard gap)", id, pos.getX(), pos.getY(), pos.getZ());
            Core.INSTANCE.events.push("guard", "restore: broke non-terrain " + id + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " (guard gap)", null);
        }
        Ledger.Entry e = book.ledger.add(pos.getX(), pos.getY(), pos.getZ(), dim, id, st.toString(), items, why, j.id, j.label, reason,
                Core.INSTANCE.tick(), System.currentTimeMillis());
        if (e != null) {
            recorded++;
            changed();
        }
    }

    // ---- the contexts ----

    public Commands commands() { return c; }

    /** 0.24.3: the ledger says the bot built this cell itself (why starts "built:"). */
    boolean builtByMe(int x, int y, int z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        return book.ledger.placedByMe(Guard.dimOf(mc.level), x, y, z);
    }

    /** 0.33.0 (A3): a station I placed myself (ledger "built: station") that may be picked up. */
    boolean stationOfMine(int x, int y, int z) {
        return stationRefusal(x, y, z) == null;
    }

    /**
     * 0.33.0 review: why the cell's station is not one of mine to pick up (null: it is). Matched by position, kind and
     * placement (a removal heard since the placement makes it someone's), and a container only when read empty since.
     */
    String stationRefusal(int x, int y, int z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || c == null) return "not in a world";
        String dim = Guard.dimOf(mc.level);
        io.github.mojolowjo.entropybot.restore.Ledger.Entry e = book.ledger.at(dim, x, y, z);
        if (e == null || !"built: station".equals(e.why)) return "I didn't place a station at " + x + " " + y + " " + z;
        String now = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(new net.minecraft.core.BlockPos(x, y, z)).getBlock()).getPath();
        if (!io.github.mojolowjo.entropybot.clear.OwnStations.station(now)) return "no station at " + x + " " + y + " " + z + " any more";
        Long gone = stationRemoved.get(dim + " " + x + " " + y + " " + z);
        Integer contents = null;
        long seen = 0;
        com.google.gson.JsonObject note = Core.INSTANCE.knowledge.chests().get(x + " " + y + " " + z);
        if (note != null && note.has("items") && note.has("seen") && note.get("seen").getAsLong() >= e.at) {
            int n = 0;
            for (var it : note.getAsJsonObject("items").entrySet()) n += it.getValue().getAsInt();
            contents = n;
            seen = note.get("seen").getAsLong();
        }
        contents = io.github.mojolowjo.entropybot.clear.OwnStations.contentsKnown(contents, seen, null, e.at);
        return io.github.mojolowjo.entropybot.clear.OwnStations.pickUp(e.block, now, gone != null && gone >= e.at, now, contents, x, y, z);
    }

    /** 0.33.0 review: station cells of mine whose block was removed (ms), from the block-change hook; a new placement clears it. */
    private final java.util.Map<String, Long> stationRemoved = new java.util.concurrent.ConcurrentHashMap<>();

    /** The block-change hook (any thread, never throws): a recorded station cell lost its block. */
    public void blockChanged(String dim, int x, int y, int z, String fromId, String toId) {
        try {
            if (fromId == null || fromId.equals(toId)) return;
            if (!io.github.mojolowjo.entropybot.clear.OwnStations.station(fromId)) {
                // rm-mining: a block I placed is gone (broken, replaced): the cell is no longer mine (a later block there is not)
                io.github.mojolowjo.entropybot.restore.Ledger.Entry p = book.ledger.at(dim, x, y, z);
                if (p != null && PLACED.equals(p.why) && fromId.equals(p.block) && book.ledger.remove(p)) changed();
                return;
            }
            io.github.mojolowjo.entropybot.restore.Ledger.Entry e = book.ledger.at(dim, x, y, z);
            if (e != null && "built: station".equals(e.why)) stationRemoved.put(dim + " " + x + " " + y + " " + z, System.currentTimeMillis());
        } catch (RuntimeException ex) {
            LOG.warn("[entropybot] station change hook: {}", ex.toString());
        }
    }

    /** 0.33.0 review: my recorded station cells inside the box (the ledger's entries, not every cell of the box). */
    List<int[]> stationCells(int x1, int y1, int z1, int x2, int y2, int z2) {
        Minecraft mc = Minecraft.getInstance();
        List<int[]> out = new ArrayList<>();
        if (mc.level == null) return out;
        String dim = Guard.dimOf(mc.level);
        for (io.github.mojolowjo.entropybot.restore.Ledger.Entry e : book.ledger.all()) {
            if (!"built: station".equals(e.why) || !dim.equals(e.dim)) continue;
            int[] q = e.pos();
            if (q[0] >= x1 && q[0] <= x2 && q[1] >= y1 && q[1] <= y2 && q[2] >= z1 && q[2] <= z2) out.add(q);
        }
        return out;
    }

    /** station-memory (0.39.z14): every station I placed in this dimension, with the block the ledger recorded. */
    List<io.github.mojolowjo.entropybot.clear.OwnStations.Placed> stationsPlaced() {
        Minecraft mc = Minecraft.getInstance();
        List<io.github.mojolowjo.entropybot.clear.OwnStations.Placed> out = new ArrayList<>();
        if (mc.level == null || book == null) return out;
        String dim = Guard.dimOf(mc.level);
        for (io.github.mojolowjo.entropybot.restore.Ledger.Entry e : book.ledger.all()) {
            if ("built: station".equals(e.why) && dim.equals(e.dim)) out.add(new io.github.mojolowjo.entropybot.clear.OwnStations.Placed(e.x, e.y, e.z, e.block));
        }
        return out;
    }

    /** station-memory: the station record at the cell leaves the ledger (the bot arrived and it is gone); the log line. */
    synchronized String forgetStation(int x, int y, int z, String why) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || book == null) return "not in a world: nothing forgotten at " + x + " " + y + " " + z;
        String dim = Guard.dimOf(mc.level);
        io.github.mojolowjo.entropybot.restore.Ledger.Entry e = book.ledger.at(dim, x, y, z);
        if (e == null || !"built: station".equals(e.why)) return "no station of mine recorded at " + x + " " + y + " " + z + " (" + why + ")";
        if (readOnly) return "the station record at " + x + " " + y + " " + z + " stays: " + loadNote;
        if (!book.ledger.remove(e)) return "the station record at " + x + " " + y + " " + z + " was already gone";
        stationRemoved.remove(dim + " " + x + " " + y + " " + z);
        changed();
        String line = "forgot my " + e.block + " at " + x + " " + y + " " + z + ": " + why;
        LOG.info("[entropybot] stations: {}", line);
        return line;
    }

    /** 0.24.3: blocks the bot placed itself (a shelter): ledger entries never re-placed, its own spot for the build hint. */
    void noteBuilt(List<int[]> cells, String block, String why) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || readOnly || cells == null) return;
        String dim = Guard.dimOf(mc.level);
        Commands cm = c;
        long job = cm != null && cm.jobs.running() ? cm.jobs.job.id : 0;
        String label = cm != null && cm.jobs.running() ? cm.jobs.job.label : "shelter";
        int n = 0;
        for (int[] p : cells) {
            stationRemoved.remove(dim + " " + p[0] + " " + p[1] + " " + p[2]);     // 0.33.0 review: a new placement of mine
            if (book.ledger.add(p[0], p[1], p[2], dim, block, block, List.of(), why, job, label, why, Core.INSTANCE.tick(), System.currentTimeMillis()) != null) n++;
        }
        if (n > 0) {
            changed();
            if (io.github.mojolowjo.entropybot.restore.PlacedPlan.LITTER.equals(why)) LOG.debug("[entropybot] restore: {} blocks noted as {}", n, why);      // one per scaffold block
            else LOG.info("[entropybot] restore: {} blocks noted as {}", n, why);
        }
    }

    /** 0.24.4: a block of the gathered terrain family (RestoreRules.purposeBreak) is the job's purpose. */
    private boolean purpose(String id) {
        for (String t : pathTargets) if (io.github.mojolowjo.entropybot.restore.RestoreRules.purposeBreak(t, id)) return true;
        return false;
    }

    /** "mine ... dig": while this job runs, every block but the target that Baritone breaks is a path break. */
    /** 0.28.0: the job's breaks are its purpose from here on (a known-ore dig): no path context. */
    void endPathContext(long job) {
        if (pathJob == job) {
            pathJob = -1;
            pathTargets = Set.of();
        }
    }

    void pathContext(long job, String targetId) {
        pathTargets = Set.of(targetId);
        pathJob = job;
    }

    // ---- runs ----

    boolean auto() { return "auto".equals(book.mode); }

    /** A job ends: the run that puts its entries back (null: nothing to do, or the mode says not now). */
    RestoreRun afterJob(Jobs.Job j, int[] here, String dim) {
        List<Ledger.Entry> mine = book.ledger.forJob(j.id);
        if (mine.isEmpty()) return null;
        if (!auto()) return null;
        RestorePlan.Plan plan = RestorePlan.plan(mine, here, dim, RestorePlan.afterJob(), Gui0.inventory());
        return new RestoreRun(plan);
    }

    /** The end line's restore part when no run happens (stopped, manual mode): "3 blocks to put back (restore now)". */
    String waitingNote(Jobs.Job j) {
        int n = 0;
        for (Ledger.Entry e : book.ledger.forJob(j.id)) if (e.restorable()) n++;
        if (n == 0) return null;
        return n + (n == 1 ? " block" : " blocks") + " to put back (restore now)";
    }

    /** The job's end line with the restore summary added. */
    String endText(String msg, Jobs.Job j, RestoreRun run) {
        List<String> parts = new ArrayList<>();
        String sum = run == null ? null : run.summary();
        if (sum != null) parts.add(sum);
        if (run != null && !run.left.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (RestorePlan.Left l : run.left) sb.append(l.entry().x).append(' ').append(l.entry().y).append(' ').append(l.entry().z).append(" (").append(l.why()).append(") ");
            LOG.info("[entropybot] restore: not put back: {}", sb.toString().trim());
        }
        if (parts.isEmpty()) return msg;
        return msg + "; " + String.join("; ", parts);
    }

    void drop(Ledger.Entry e) {
        if (book.ledger.remove(e)) changed();
    }

    // ---- the verb ----

    /** The instant forms (everything but "restore now"). */
    String command(LocalPlayer p, String rest) {
        RestoreArgs.Cmd cmd = RestoreArgs.parse(rest);
        switch (cmd.kind()) {
            case STATUS -> { return status(p); }
            case USAGE -> { return cmd.error(); }
            case FORGET -> {
                if (readOnly) return "error: " + loadNote;
                int n = book.ledger.forgetOldest(cmd.n());
                changed();
                return "ok: forgot the " + n + " oldest blocks to put back (" + book.ledger.waiting() + " left)";
            }
            case FORGET_ALL -> {
                if (readOnly) return "error: " + loadNote;
                int n = book.ledger.forgetAll();
                changed();
                return "ok: forgot all " + n + " blocks to put back";
            }
            case KEEP -> { return keepLitter(p, cmd.mode()); }
            case IGNORE -> {
                int[] q = cmd.pos();
                if (!book.ignore(q[0], q[1], q[2])) return "error: no build hint at " + q[0] + " " + q[1] + " " + q[2] + " (restore status lists them)";
                changed();
                return "ok: I won't mention the build at " + q[0] + " " + q[1] + " " + q[2] + " again";
            }
            case MODE -> {
                book.mode = cmd.mode();
                changed();
                return "ok: restore mode " + book.mode + switch (book.mode) {
                    case "auto" -> " (I put back what I broke on the way when a job ends)";
                    case "manual" -> " (I note what I break on the way; restore now puts it back)";
                    default -> " (I only note what I break on the way)";
                };
            }
            default -> { return RestoreArgs.USAGE; }
        }
    }

    String status(LocalPlayer p) {
        StringBuilder sb = new StringBuilder();
        sb.append("restore: hook ").append(MixinFlags.restoreApplied ? "active" : "NOT in").append(", ").append(breaksSeen)
                .append(" breaks seen this session, ").append(recorded).append(" recorded; mode ").append(book.mode);
        if (readOnly) sb.append("; ").append(loadNote);
        int w = book.ledger.waiting();
        sb.append("; ").append(w).append(w == 1 ? " block" : " blocks").append(" to put back");
        String br = book.ledger.breakdown();
        if (!br.isEmpty()) sb.append(" (").append(br).append(")");
        if (p != null && w > 0) {
            int[] me = Jobs.here(p);
            List<Ledger.Entry> all = new ArrayList<>(book.ledger.confirmed());
            all.sort((a, b) -> Long.compare(d2(a, me), d2(b, me)));
            List<String> near = new ArrayList<>();
            for (int i = 0; i < Math.min(5, all.size()); i++) {
                Ledger.Entry e = all.get(i);
                near.add(e.x + " " + e.y + " " + e.z + " " + RestoreRules.shortId(e.block) + (e.restorable() ? "" : " (" + e.why + ")"));
            }
            sb.append("; nearest: ").append(String.join(", ", near));
        }
        List<RestoreBook.BuildHint> open = book.openHints(protectBoxes());
        if (!open.isEmpty()) {
            sb.append("; builds to protect: ");
            List<String> h = new ArrayList<>();
            for (RestoreBook.BuildHint b : open) h.add(b.center[0] + " " + b.center[1] + " " + b.center[2] + " (" + b.command() + ")");
            sb.append(String.join(", ", h));
        }
        return sb.toString();
    }

    private static long d2(Ledger.Entry e, int[] me) {
        long dx = e.x - me[0], dy = e.y - me[1], dz = e.z - me[2];
        return dx * dx + dy * dy + dz * dz;
    }

    // ---- aa-litter: the scaffold the bot placed on the way ----

    /** What Baritone throws away as scaffold: only these, placed under a walk's travel intent, are litter. */
    private static boolean scaffold(String itemPath) { return io.github.mojolowjo.entropybot.clear.FloorFill.isFallback(itemPath.contains(":") ? itemPath : "minecraft:" + itemPath); }

    /** rm-mining: the ledger note of any other block a job of mine placed (a station from the builder leg, a torch, a block). */
    static final String PLACED = "built: placed";

    /**
     * From the place hook (client thread, after the guard let the placement through), while a job of mine runs: a walk's
     * throwaway block (travel intent, a cell it did not dig itself) is noted {@code built: litter} until the step's end takes
     * it down ({@code restore placed}) or keeps it ({@code restore keep}); rm-mining: every other placement is noted as mine
     * ({@code built: station} for a station, else {@link #PLACED}), so the guard never protects it from me as a "built
     * block" (guard/OwnPlaced). A cell the ledger already holds keeps its first note. Never throws.
     */
    public void notePlaced(BlockPos pos, String itemPath) {
        try {
            Minecraft mc = Minecraft.getInstance();
            Commands cm = c;
            if (cm == null || mc.level == null || readOnly || !cm.jobs.running() || itemPath == null) return;
            String dim = Guard.dimOf(mc.level);
            if (book.ledger.at(dim, pos.getX(), pos.getY(), pos.getZ()) != null) return;
            List<int[]> cell = List.of(new int[]{pos.getX(), pos.getY(), pos.getZ()});
            if (scaffold(itemPath) && io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.intent() == io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.TRAVEL) {
                if (io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.dug(dim, pos.getX(), pos.getY(), pos.getZ())) return;     // a refill of my own hole
                noteBuilt(cell, "minecraft:" + itemPath, io.github.mojolowjo.entropybot.restore.PlacedPlan.LITTER);
                return;
            }
            noteBuilt(cell, "minecraft:" + itemPath, io.github.mojolowjo.entropybot.clear.OwnStations.station(itemPath) ? "built: station" : PLACED);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] restore: the placement note at {} {} {} failed: {}", pos.getX(), pos.getY(), pos.getZ(), e.toString());
        }
    }

    /** "restore keep <what>": the litter standing within 48 of me becomes a kept structure of mine (never taken down later). */
    String keepLitter(LocalPlayer p, String what) {
        if (readOnly) return "error: " + loadNote;
        String dim = Guard.dimOf(p.level());
        int[] me = Jobs.here(p);
        int n = 0;
        // only the newest step's scaffold (the job that placed the latest litter): earlier steps were priced "take down"
        long lastJob = -1, lastSeq = -1;
        for (Ledger.Entry e : book.ledger.all()) if (io.github.mojolowjo.entropybot.restore.PlacedPlan.litter(e) && e.seq > lastSeq) { lastSeq = e.seq; lastJob = e.job; }
        for (Ledger.Entry e : new ArrayList<>(book.ledger.all())) {
            if (!io.github.mojolowjo.entropybot.restore.PlacedPlan.litter(e) || e.job != lastJob || !dim.equals(e.dim) || d2(e, me) > 48L * 48) continue;
            if (book.ledger.rewrite(e, io.github.mojolowjo.entropybot.restore.PlacedPlan.keptWhy(what, e.x, e.y, e.z, "reuse pays for it"), io.github.mojolowjo.entropybot.restore.PlacedPlan.KEPT.trim())) n++;
        }
        if (n > 0) changed();
        LOG.info("[entropybot] litter: kept {} blocks as {}", n, what);
        return "ok: kept " + n + (n == 1 ? " block" : " blocks") + " as " + what;
    }

    /** {@code aa litter}: what is standing to take down and what was kept. */
    public String litterText() {
        return io.github.mojolowjo.entropybot.restore.PlacedPlan.text(book.ledger.all());
    }

    /** "restore placed [r]": a job that walks to and breaks the scaffold the bot placed within r (the step's end). */
    String startPlaced(LocalPlayer p, String rest) {
        RestoreArgs.Cmd cmd = RestoreArgs.parse(rest);
        if (cmd.kind() != RestoreArgs.Kind.PLACED) return RestoreArgs.USAGE;
        if (readOnly) return "error: " + loadNote;
        RestorePlan.Plan plan = io.github.mojolowjo.entropybot.restore.PlacedPlan.plan(book.ledger.confirmed(), Jobs.here(p), Guard.dimOf(p.level()), cmd.n());
        if (plan.actions().isEmpty())
            return plan.left().isEmpty() ? "ok: nothing of mine left standing" : "error: " + io.github.mojolowjo.entropybot.restore.PlacedPlan.summary(0, plan.left());
        return c.jobs.startRestore(new RestoreRun(plan, true), "taking down " + plan.actions().size() + (plan.actions().size() == 1 ? " block" : " blocks") + " I placed");
    }

    /** AA_STEP2 3: blocks waiting in the ledger to be put back (aa status "restore pending: N blocks"). */
    public int pendingCount() {
        int n = 0;
        for (Ledger.Entry e : book.ledger.confirmed()) if (e.restorable()) n++;
        return n;
    }

    /** "restore now [r]": a job that walks to and puts back every waiting block within r. */
    String startNow(LocalPlayer p, String rest) {
        if (RestoreArgs.parse(rest).kind() == RestoreArgs.Kind.PLACED) return startPlaced(p, rest);
        RestoreArgs.Cmd cmd = RestoreArgs.parse(rest);
        if (cmd.kind() != RestoreArgs.Kind.NOW) return RestoreArgs.USAGE;
        if (readOnly) return "error: " + loadNote;
        List<Ledger.Entry> all = new ArrayList<>();
        for (Ledger.Entry e : book.ledger.confirmed()) if (e.restorable()) all.add(e);
        if (all.isEmpty()) return "ok: nothing to put back";
        RestorePlan.Plan plan = RestorePlan.plan(all, Jobs.here(p), Guard.dimOf(p.level()), cmd.n(), Gui0.inventory());
        if (plan.actions().isEmpty()) return "error: nothing I can put back from here - " + RestorePlan.summary(0, plan.left());
        return c.jobs.startRestore(new RestoreRun(plan), "putting back " + plan.actions().size() + (plan.actions().size() == 1 ? " block" : " blocks"));
    }

    // ---- foreign builds (owner's answer 1: suggest, never auto-protect) ----

    private static List<Box> protectBoxes() {
        Policy pol = Core.INSTANCE.guard.core.policy();
        return pol == null || pol.protect == null ? List.of() : pol.protect;
    }

    /** A job is about to work around center: built blocks clustered there and outside every protect box get one whisper per spot. */
    void scanBuilds(LocalPlayer p, int[] center) {
        try {
            scanBuilds0(p, center);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] restore: build scan failed: {}", e.toString());
        }
    }

    private void scanBuilds0(LocalPlayer p, int[] center) {
        BuildSpotter.Hint h = spotBuild(center);
        if (h == null) return;
        String dim = Guard.dimOf(Minecraft.getInstance().level);
        RestoreBook.BuildHint added = book.addHint(h, dim, System.currentTimeMillis());
        if (added == null) return;                            // this spot was mentioned before
        changed();
        LOG.info("[entropybot] restore: {} built blocks near {} - suggested {}", h.count(), Jobs.fmt(h.center()), h.command());
        if (warnedHints.add(h.key())) c.whisper(c.owner(), h.whisper());
    }

    /** 0.24.3: the cluster holds a place, a noted chest or a ledger block, or lies near where the bot respawned. */
    private boolean ownSpot(BuildSpotter.Hint h, String dim) {
        List<int[]> places = new ArrayList<>(), chests = new ArrayList<>(), own = new ArrayList<>();
        for (JsonObject pl : Core.INSTANCE.knowledge.places().values()) if (Jobs.dimOf(pl).equals(dim)) places.add(Jobs.pos(pl));
        java.util.regex.Pattern num = java.util.regex.Pattern.compile("-?\\d+");
        for (String k : Core.INSTANCE.knowledge.chests().keySet()) {
            java.util.regex.Matcher m = num.matcher(k);
            int[] p = new int[3];
            int i = 0;
            while (i < 3 && m.find()) p[i++] = Integer.parseInt(m.group());
            if (i == 3) chests.add(p);
        }
        for (io.github.mojolowjo.entropybot.restore.Ledger.Entry e : book.ledger.all()) if (dim.equals(e.dim)) own.add(e.pos());
        if (c != null) for (String k : ShelterJob.ownBlocks(c)) {           // its shelters
            String[] w = k.split(" ");
            if (w.length == 3) own.add(new int[]{Integer.parseInt(w[0]), Integer.parseInt(w[1]), Integer.parseInt(w[2])});
        }
        List<int[]> homes = new ArrayList<>(c == null ? List.of() : c.respawn.homes());
        Level lv = Minecraft.getInstance().level;
        if (lv != null && dim.equals("minecraft:overworld")) {
            BlockPos sp = lv.getSharedSpawnPos();                // the world spawn: where a bot without a bed comes back
            homes.add(new int[]{sp.getX(), sp.getY(), sp.getZ()});
        }
        return BuildSpotter.ownSpot(h, places, chests, own, homes);
    }

    /** The biggest built cluster around center outside every protect box (BuildSpotter), or null. */
    private BuildSpotter.Hint spotBuild(int[] center) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        Guard g = Core.INSTANCE.guard;
        if (level == null || c == null || !g.floorReady()) return null;
        String dim = Guard.dimOf(level);
        // the owner's own places (base, mine, farm...) are known to them: no hint there
        for (JsonObject pl : Core.INSTANCE.knowledge.places().values()) {
            if (!Jobs.dimOf(pl).equals(dim)) continue;
            if (Jobs.distSq(Jobs.pos(pl), center) <= 24 * 24) return null;
        }
        List<Box> protect = protectBoxes();
        List<int[]> built = new ArrayList<>();
        java.util.Map<String, Integer> kinds = new java.util.TreeMap<>();   // 0.19.7: what the count is made of, in the log
        int r = BuildSpotter.radius();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    int x = center[0] + dx, y = center[1] + dy, z = center[2] + dz;
                    m.set(x, y, z);
                    if (!level.isLoaded(m)) continue;
                    BlockState st = level.getBlockState(m);
                    if (st.isAir()) continue;
                    if (!g.isProtectedBlock(st.getBlock())) continue;
                    if (BuildSpotter.ownKind(BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString())) continue;
                    boolean covered = false;
                    for (Box b : protect) if (b.contains(dim, x, y, z)) { covered = true; break; }
                    if (!covered) {
                        built.add(new int[]{x, y, z});
                        kinds.merge(BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath(), 1, Integer::sum);
                    }
                }
            }
        }
        BuildSpotter.Hint h = BuildSpotter.spot(built);
        if (h != null && ownSpot(h, dim)) {
            LOG.debug("[entropybot] build scan near {}: the bot's own spot (a place, a noted chest, its blocks or its spawn) - no hint", Jobs.fmt(h.center()));
            return null;
        }
        if (h != null) {
            // 0.33.0: mineshaft planks and fences, a village, a ruined portal... are the world's own, not a build: the game's structure data says
            String natural = null;
            StructureSource.install();
            try {
                natural = io.github.mojolowjo.entropybot.vocab.NaturalStructures.explains(dim, center[0], center[1], center[2], r);
            } catch (RuntimeException ex) {
                LOG.warn("[entropybot] natural structure check failed: {}", ex.toString());
            }
            if (natural != null) {
                LOG.info("[entropybot] build scan near {}: {} (the world's own) - no hint; kinds {}", Jobs.fmt(center), natural, kinds);
                return null;
            }
            if (!io.github.mojolowjo.entropybot.vocab.NaturalStructures.known()) LOG.info("[entropybot] build scan near {}: no structure data on a server, cannot say whether it is the world's own", Jobs.fmt(center));
            LOG.debug("[entropybot] build scan near {}: kinds {}", Jobs.fmt(center), kinds);
        }
        return h;
    }

    // ---- check ----

    List<SelfCheck.Finding> findings() {
        List<SelfCheck.Finding> f = new ArrayList<>();
        if (!MixinFlags.restoreApplied) {
            f.add(new SelfCheck.Finding("restorehook", "the restore hook (RestoreMixinGameMode) is not in: blocks I break on the way are not noted or put back",
                    "jar status (reinstall the mod)"));
        } else if (hookError != null) {
            f.add(new SelfCheck.Finding("restorehook", "the restore hook failed: " + hookError, "restore status"));
        }
        if (readOnly) f.add(new SelfCheck.Finding("restorefile", loadNote, "fix or delete entropybot\\" + FILE + ", then restart"));
        else if (writeError != null) f.add(new SelfCheck.Finding("restorefile", "couldn't write " + FILE + ": " + writeError, "restore status"));
        int w = book.ledger.waiting();
        if (w > 0) {
            long oldest = Long.MAX_VALUE;
            for (Ledger.Entry e : book.ledger.confirmed()) if (e.restorable()) oldest = Math.min(oldest, e.at);
            long min = Math.max(0, (System.currentTimeMillis() - oldest) / 60_000);
            f.add(new SelfCheck.Finding("restore", "restore: " + w + (w == 1 ? " block" : " blocks") + " waiting since " + min + " min", "restore now (restore status lists them)"));
        }
        for (RestoreBook.BuildHint b : book.openHints(protectBoxes())) {
            f.add(new SelfCheck.Finding("build " + b.key, "looks like a build at " + b.center[0] + " " + b.center[1] + " " + b.center[2] + " that no protect box covers",
                    b.command() + " (or restore ignore " + b.center[0] + " " + b.center[1] + " " + b.center[2] + ")"));
        }
        return f;
    }

    /** For the ready log line and status: "active" or "MISSING". */
    public static String hookState() { return MixinFlags.restoreApplied ? "active" : "MISSING"; }

    /** The bag, by item id (a tiny indirection so the plan never sees game classes). */
    static final class Gui0 {
        static Map<String, Integer> inventory() {
            LocalPlayer p = Minecraft.getInstance().player;
            return p == null ? Map.of() : io.github.mojolowjo.entropybot.gui.Gui.inventory(p);
        }
    }
}
