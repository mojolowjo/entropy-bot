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
import io.github.mojolowjo.entropybot.restore.EscapePlan;
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
    static final long FLUSH_AFTER = 20;

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
    private final Map<String, Long> expected = new HashMap<>();
    private final Set<String> warnedHints = new HashSet<>();

    private RestoreLive() {}

    // ---- files ----

    /** Once, at the first tick in a world. A line for the log. */
    public synchronized String load(Commands commands, BotFiles f) {
        c = commands;
        files = f;
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
        return loadNote;
    }

    private void changed() {
        if (dirtySince < 0) dirtySince = Core.INSTANCE.tick();
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
        String reason;
        Long esc;
        synchronized (expected) { esc = expected.remove(key); }
        if (esc != null && esc == j.id) reason = Ledger.ESCAPE;
        else if (pathJob == j.id && !pathTargets.contains(id)) reason = Ledger.PATH;
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

    /** "mine ... dig": while this job runs, every block but the target that Baritone breaks is a path break. */
    void pathContext(long job, String targetId) {
        pathTargets = Set.of(targetId);
        pathJob = job;
    }

    /** The escape dig-out is about to break these cells for this job. */
    void expectEscape(long job, List<int[]> cells, String dim) {
        synchronized (expected) {
            expected.clear();
            for (int[] p : cells) expected.put(dim + " " + p[0] + " " + p[1] + " " + p[2], job);
        }
    }

    // ---- runs ----

    boolean auto() { return "auto".equals(book.mode); }

    /** A job ends: the run that puts its entries back (null: nothing to do, or the mode says not now). */
    RestoreRun afterJob(Jobs.Job j, int[] here, String dim) {
        List<Ledger.Entry> mine = book.ledger.forJob(j.id);
        if (mine.isEmpty()) return null;
        if (!auto()) return null;
        RestorePlan.Plan plan = RestorePlan.plan(mine, here, dim, RestorePlan.AFTER_JOB, Gui0.inventory());
        return new RestoreRun(plan);
    }

    /** The escape entries of a travel job, put back once the bot is out (mid-walk). Null when none or not in auto. */
    RestoreRun escapeRun(Jobs.Job j, int[] here, String dim) {
        if (!auto()) return null;
        List<Ledger.Entry> esc = new ArrayList<>();
        for (Ledger.Entry e : book.ledger.forJob(j.id)) if (Ledger.ESCAPE.equals(e.reason)) esc.add(e);
        if (esc.isEmpty()) return null;
        return new RestoreRun(RestorePlan.plan(esc, here, dim, RestorePlan.AFTER_JOB, Gui0.inventory()));
    }

    /** True once the bot stands more than 3 blocks from every escape entry of the job (and some exist). */
    boolean outOfEscape(Jobs.Job j, int[] here) {
        boolean any = false;
        for (Ledger.Entry e : book.ledger.forJob(j.id)) {
            if (!Ledger.ESCAPE.equals(e.reason)) continue;
            any = true;
            long dx = e.x - here[0], dy = e.y - here[1], dz = e.z - here[2];
            if (dx * dx + dy * dy + dz * dz <= 9) return false;
        }
        return any;
    }

    /** The end line's restore part when no run happens (stopped, manual mode): "3 blocks to put back (restore now)". */
    String waitingNote(Jobs.Job j) {
        int n = 0;
        for (Ledger.Entry e : book.ledger.forJob(j.id)) if (e.restorable()) n++;
        if (n == 0) return null;
        return n + (n == 1 ? " block" : " blocks") + " to put back (restore now)";
    }

    /** The job's end line with the escape report and the restore summary added. */
    String endText(String msg, Jobs.Job j, RestoreRun run) {
        List<String> parts = new ArrayList<>();
        if (j.escapeDug > 0) parts.add(EscapePlan.report(j.escapeDug, j.escapeAt, j.escapeRestored + (run == null ? 0 : run.placedEscape)));
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

    /** "restore now [r]": a job that walks to and puts back every waiting block within r. */
    String startNow(LocalPlayer p, String rest) {
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

    /**
     * 0.23.4 (run 8): a walk passes center: a built cluster there that no safe or main area covers, outside the near-me
     * zone, gets one whisper per spot ("looks like a build at x y z - area here 8 name safe?"). True when it whispered.
     */
    boolean walkHint(LocalPlayer p, int[] center) {
        try {
            BuildSpotter.Hint h = spotBuild(center);
            if (h == null) return false;
            String dim = Guard.dimOf(Minecraft.getInstance().level);
            int[] k = h.center();
            Policy pol = Core.INSTANCE.guard.core.policy();
            io.github.mojolowjo.entropybot.guard.AreaType t = pol == null ? null : pol.typeAt(dim, k[0], k[1], k[2]);
            Box near = Core.INSTANCE.guard.core.nearBox();
            boolean inNear = near != null && near.contains(dim, k[0], k[1], k[2]);
            boolean mentioned = warnedHints.contains(h.key());
            if (!BuildSpotter.walkHintAllowed(t == null ? null : t.word(), inNear, mentioned)) return false;
            if (book.addHint(h, dim, System.currentTimeMillis()) != null) changed();
            warnedHints.add(h.key());
            LOG.info("[entropybot] walk: {} built blocks near {} (area type {}) - hint whispered", h.count(), Jobs.fmt(k), t == null ? "none" : t.word());
            c.whisper(c.owner(), h.walkWhisper());
            return true;
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] walk build hint failed: {}", e.toString());
            return false;
        }
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
        int r = BuildSpotter.RADIUS;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    int x = center[0] + dx, y = center[1] + dy, z = center[2] + dz;
                    m.set(x, y, z);
                    if (!level.isLoaded(m)) continue;
                    BlockState st = level.getBlockState(m);
                    if (st.isAir() || !g.isProtectedBlock(st.getBlock())) continue;
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
        if (h != null) LOG.debug("[entropybot] build scan near {}: kinds {}", Jobs.fmt(center), kinds);
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
