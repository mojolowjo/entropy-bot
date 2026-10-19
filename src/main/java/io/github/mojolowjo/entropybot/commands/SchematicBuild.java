package io.github.mojolowjo.entropybot.commands;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.MaskSchematic;
import baritone.api.schematic.format.ISchematicFormat;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.aa.brain.Blueprint;
import io.github.mojolowjo.entropybot.clear.BuilderReport;
import io.github.mojolowjo.entropybot.clear.ClearBox;
import io.github.mojolowjo.entropybot.clear.ClearJob;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.slf4j.Logger;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * B7 (BRAIN_PLAN 2.12): the game side of schematic builds. Loads {@code entropybot/builds/<name>.litematic|.schem|.schematic}
 * through Baritone's {@code ISchematicSystem} (Baritone API: {@code IBaritoneProvider.getSchematicSystem().getByFile(File)},
 * {@code ISchematicFormat.parse(InputStream)} -> {@code IStaticSchematic.getDirect(x, y, z)}), counts each cell's item
 * ({@code Block.asItem}; the upper door half and the bed head count 0), scans the world against it, and runs the build
 * step {@code build schematic <name> <x> <y> <z>}: Baritone's {@code IBuilderProcess.build(name, ISchematic, Vec3i)}
 * with {@code buildInLayers} (bottom up, 1 high), placing only ({@code allowBreak} off, {@code buildIgnoreExisting} on),
 * inside a place lease over the box. Skipped blocks (air, liquids, fire, portals) are masked out.
 *
 * <p>Loader: Baritone API + vanilla registries/state properties only; no NeoForge event, no mixin. On Fabric the same
 * code works with Baritone's Fabric build.
 */
final class SchematicBuild {
    private SchematicBuild() {}

    private static final Logger LOG = LogUtils.getLogger();
    static final String KIND = "schematic";

    /** The loaded files by name (memory only, as the goals). */
    static final Map<String, Loaded> LOADED = new ConcurrentHashMap<>();
    /** The last stop text of each build's step, read into Build.lastStop by AaRuntime. */
    static final Map<String, String> LAST_STOP = new ConcurrentHashMap<>();

    record Loaded(String name, File file, IStaticSchematic s, Map<String, Integer> need, List<String> skipped) {}

    static Path dir() {
        return Core.INSTANCE.files() == null ? null : Core.INSTANCE.files().root().resolve("builds");
    }

    /** Loads (or reloads) a build by name; the error text in err[0] when it fails. */
    static Loaded load(String name, String[] err) {
        Path d = dir();
        if (d == null) { err[0] = "error: no world files yet"; return null; }
        try { Files.createDirectories(d); } catch (Exception ignored) {}
        File f = null;
        for (String ext : List.of("litematic", "schem", "schematic")) {
            File c = d.resolve(name + "." + ext).toFile();
            if (c.isFile()) { f = c; break; }
        }
        if (f == null) {
            if (d.resolve(name + ".nbt").toFile().isFile()) { err[0] = "error: " + name + ".nbt is a structure file; I read .litematic, .schem and .schematic only"; return null; }
            err[0] = "error: no " + name + ".litematic/.schem/.schematic in " + d;
            return null;
        }
        try {
            Optional<ISchematicFormat> fmt = BaritoneAPI.getProvider().getSchematicSystem().getByFile(f);
            if (fmt.isEmpty()) { err[0] = "error: Baritone does not know the format of " + f.getName(); return null; }
            IStaticSchematic s;
            try (InputStream in = Files.newInputStream(f.toPath())) { s = fmt.get().parse(in); }
            long cells = (long) s.widthX() * s.heightY() * s.lengthZ();
            if (cells > Blueprint.MAX_CELLS) { err[0] = "error: " + name + " is " + s.widthX() + "x" + s.heightY() + "x" + s.lengthZ() + " = " + cells + " cells; I take up to " + Blueprint.MAX_CELLS; return null; }
            Map<String, Integer> need = new TreeMap<>();
            Map<String, Integer> skipped = new TreeMap<>();
            for (int y = 0; y < s.heightY(); y++)
                for (int x = 0; x < s.widthX(); x++)
                    for (int z = 0; z < s.lengthZ(); z++) {
                        BlockState st = s.getDirect(x, y, z);
                        String bid = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
                        if (Blueprint.skipped(bid)) {
                            if (!st.isAir()) skipped.merge(bid.replaceFirst("^minecraft:", ""), 1, Integer::sum);
                            continue;
                        }
                        String item = itemOf(st);
                        if (item == null) {
                            if (!secondHalf(st)) skipped.merge(bid.replaceFirst("^minecraft:", "") + " (no item)", 1, Integer::sum);
                            continue;
                        }
                        need.merge(item, 1, Integer::sum);
                    }
            List<String> sk = new ArrayList<>();
            for (var e : skipped.entrySet()) sk.add(e.getValue() + " " + e.getKey());
            Loaded l = new Loaded(name, f, s, need, sk);
            LOADED.put(name, l);
            LOG.info("[entropybot] build: loaded {} ({}x{}x{}), {}{}", f.getName(), s.widthX(), s.heightY(), s.lengthZ(), Blueprint.materials(need),
                    sk.isEmpty() ? "" : "; left out: " + String.join(", ", sk));
            return l;
        } catch (Throwable t) {
            LOG.warn("[entropybot] build: could not read {}: {}", f, t.toString());
            err[0] = "error: could not read " + f.getName() + " (" + t.getMessage() + ")";
            return null;
        }
    }

    private static boolean secondHalf(BlockState st) {
        if (st.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && st.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return true;
        return st.hasProperty(BlockStateProperties.BED_PART) && st.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD;
    }

    /** The item that places this cell, null for none (or the second half of a door/bed: placed with the first). */
    static String itemOf(BlockState st) {
        if (secondHalf(st)) return null;
        Item it = st.getBlock().asItem();
        if (it == null || it == Items.AIR) return null;
        return BuiltInRegistries.ITEM.getKey(it).toString();
    }

    /** True when the cell counts (has an item and is not skipped). */
    private static boolean counts(BlockState st) {
        return !Blueprint.skipped(BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString()) && itemOf(st) != null;
    }

    /** Build runs in a row that placed nothing, by name (review 3); read into Build.fails by AaRuntime. */
    static final Map<String, Integer> FAILS = new ConcurrentHashMap<>();

    /** Null while a part of the box is not loaded. The world against the schematic: what is left per item (whole, current layer), placed/occupied, the layer. */
    static Blueprint.Scan scan(Loaded l, int[] o, Level lv) {
        IStaticSchematic s = l.s();
        int h = s.heightY();
        int[] total = new int[h], done = new int[h];
        Map<String, Integer> left = new HashMap<>();
        List<Map<String, Integer>> perLayer = new ArrayList<>();
        int placed = 0, occupied = 0, all = 0;
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int y = 0; y < h; y++) {
            Map<String, Integer> ll = new HashMap<>();
            perLayer.add(ll);
            for (int x = 0; x < s.widthX(); x++)
                for (int z = 0; z < s.lengthZ(); z++) {
                    BlockState want = s.getDirect(x, y, z);
                    if (!counts(want)) continue;                        // skipped, or a second half (its first half counts)
                    mp.set(o[0] + x, o[1] + y, o[2] + z);
                    if (!lv.hasChunkAt(mp)) return null;                // review 2: part not loaded = no scan (keep the last one)
                    BlockState w = lv.getBlockState(mp);
                    boolean ok = w.getBlock() == want.getBlock();       // block only: a wrong facing counts as placed
                    total[y]++;
                    all++;
                    if (ok) { placed++; done[y]++; continue; }
                    if (!w.isAir() && !w.canBeReplaced()) { occupied++; done[y]++; continue; }
                    String item = itemOf(want);
                    left.merge(item, 1, Integer::sum);
                    ll.merge(item, 1, Integer::sum);
                }
        }
        int layer = Blueprint.currentLayer(total, done);
        return new Blueprint.Scan(left, layer < h ? perLayer.get(layer) : Map.of(), placed, all, occupied, layer, h);
    }

    // ---- the step: build schematic <name> <x> <y> <z> ----

    /** Extra state for a schematic build step (on BuildState.schem). */
    static final class Run {
        Loaded l;
        int[] o;
        int startPlaced, lastPlaced;
        long lastScan;
        Boolean savedLayers, savedOrder;
        Integer savedHeight;

        void restore() {
            try {
                Settings s = BaritoneAPI.getSettings();
                if (savedLayers != null) s.buildInLayers.value = savedLayers;
                if (savedOrder != null) s.layerOrder.value = savedOrder;
                if (savedHeight != null) s.layerHeight.value = savedHeight;
            } catch (Throwable ignored) {}
            savedLayers = null;
            savedOrder = null;
            savedHeight = null;
        }
    }

    static final String USAGE = "usage: build schematic <name> <x> <y> <z> (aa build <name> here|at x y z makes it a goal)";

    static String start(Commands c, LocalPlayer p, String[] parts) {
        if (parts.length != 5 || !parts[2].matches("-?\\d+") || !parts[3].matches("-?\\d+") || !parts[4].matches("-?\\d+")) return USAGE;
        String name = parts[1].toLowerCase();
        int[] o = {Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), Integer.parseInt(parts[4])};
        String why = BuilderDig.unavailable();
        if (why != null) return "error: Baritone's builder is not available (" + why + ")";
        String bad = BuilderDig.settingsProblem();
        if (bad != null) return "error: a schematic build needs Baritone's " + bad + " - b set it back";
        String[] err = new String[1];
        Loaded l = LOADED.get(name);
        if (l == null) l = load(name, err);
        if (l == null) return err[0];
        Level lv = p.level();
        Blueprint.Scan sc = scan(l, o, lv);
        boolean far = sc == null;                                   // part not loaded: Baritone walks there, the step scans later
        if (far) sc = new Blueprint.Scan(Map.of(), Map.of(), 0, 0, 0, 0, l.s().heightY());
        else if (sc.done()) return "done: " + name + " stands (" + sc.placed + "/" + sc.total + " placed" + (sc.occupied > 0 ? ", " + sc.occupied + " held by other blocks" : "") + ")";
        IBaritone b = Jobs.baritone();
        if (b == null) return "error: baritone not loaded";
        IStaticSchematic s = l.s();
        ClearBox box = ClearBox.of(o[0], o[1], o[2], o[0] + s.widthX() - 1, o[1] + s.heightY() - 1, o[2] + s.lengthZ() - 1);
        Clearing.BuildState bs = new Clearing.BuildState();
        bs.shape = KIND;
        bs.area = name;
        bs.status = "building " + name;
        String le = bs.leases.take(bs.status, box, true, false);
        if (le != null) return le;
        bs.box = box;
        Run r = new Run();
        r.l = l;
        r.o = o;
        r.startPlaced = far ? -1 : sc.placed;                  // -1: not scanned yet
        r.lastPlaced = Math.max(0, r.startPlaced);
        bs.schem = r;
        try {
            Settings st = BaritoneAPI.getSettings();
            r.savedLayers = st.buildInLayers.value;
            r.savedOrder = st.layerOrder.value;
            r.savedHeight = st.layerHeight.value;
            st.buildInLayers.value = true;
            st.layerOrder.value = false;
            st.layerHeight.value = 1;
            st.allowPlace.value = true;
            st.allowBreak.value = false;
            io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.setIntent(io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.BUILD, "build");
            st.buildIgnoreExisting.value = true;
            st.allowInventory.value = true;
            Clearing.applyLook(bs);
            Clearing.fallOn(bs);
            b.getCommandManager().execute("sel clear");
            MaskSchematic masked = new MaskSchematic(s) {
                @Override
                protected boolean partOfMask(int x, int y, int z, BlockState cur) {
                    BlockState d = s.getDirect(x, y, z);
                    return counts(d) || secondHalf(d);
                }
            };
            b.getBuilderProcess().build(name, masked, new BlockPos(o[0], o[1], o[2]));
            bs.start = Core.INSTANCE.tick();
            bs.lastChange = bs.start;
            Clearing.placingOwned = true;
            Seq.Step step = Clearing.clearStep(new ClearJob.Options().box(box).label(bs.status));
            step.kind = KIND;
            step.state = bs;
            String started = c.jobs.startSeq(new Seq(c.jobs, c.storage, bs.status, List.of(step), "fail"), "fail");
            if (started == null || !started.startsWith("started") || c.jobs.job == null) {
                Clearing.endBuild(bs);
                return "error: the build did not start (" + started + ")";
            }
            c.jobs.job.holdOnFight = true;
        } catch (RuntimeException e) {
            Clearing.endBuild(bs);
            LOG.warn("[entropybot] build: schematic {} could not start", name, e);
            return "error: the build did not start (" + e + ")";
        }
        LOG.info("[entropybot] build: schematic {} at {} {} {}: layer {}/{}, {}/{} placed", name, o[0], o[1], o[2], sc.layer + 1, sc.layers, sc.placed, sc.total);
        return "started: building " + name + " at " + o[0] + " " + o[1] + " " + o[2] + " (layer " + (sc.layer + 1) + "/" + sc.layers + ", " + sc.placed + "/" + sc.total + " placed)";
    }

    /** The step's tick (Clearing.step hands KIND here): a scan every 5 s, the builder's watch, the end text. */
    static String run(Seq seq, Clearing.BuildState bs, LocalPlayer p) {
        if (!(bs.schem instanceof Run r)) return "the schematic build was not set up";
        if (bs.done) return "next";
        long now = seq.now();
        if (now - bs.start < 60 || now - r.lastScan < 100) return "wait";
        r.lastScan = now;
        Blueprint.Scan sc;
        try {
            sc = scan(r.l, r.o, p.level());
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] build: scan failed: {}", e.toString());
            return "wait";
        }
        if (sc == null) {                       // part of the box not loaded yet (walking there): no stall clock
            bs.lastChange = now;
            return "wait";
        }
        if (r.startPlaced < 0) r.startPlaced = r.lastPlaced = sc.placed;
        if (sc.placed > r.lastPlaced) {
            r.lastPlaced = sc.placed;
            bs.lastChange = now;
        }
        seq.setStatus(bs.status + " - layer " + Math.min(sc.layer + 1, sc.layers) + "/" + sc.layers + ", " + sc.placed + "/" + sc.total + " placed");
        IBaritone b = Jobs.baritone();
        BuilderReport.End e = b == null ? BuilderReport.End.IDLE
                : BuilderReport.watch(!Jobs.idle(b) && b.getBuilderProcess().isActive(), b.getBuilderProcess().isPaused(), now - bs.lastChange);
        if (sc.done() && e == null) e = BuilderReport.End.IDLE;
        if (e == null) return "wait";
        int gained = sc.placed - r.startPlaced;
        String head = r.l.name() + ": layer " + Math.min(sc.layer + 1, sc.layers) + "/" + sc.layers + ", " + sc.placed + "/" + sc.total + " placed";
        String msg;
        if (gained > 0) FAILS.remove(r.l.name()); else FAILS.merge(r.l.name(), 1, Integer::sum);      // review 3
        if (sc.done()) msg = "done: built " + r.l.name() + " (" + sc.placed + "/" + sc.total + " placed" + (sc.occupied > 0 ? ", " + sc.occupied + " held by other blocks" : "") + ")";
        else {
            String why = switch (e) {
                case PAUSED -> "Baritone paused (missing blocks or no way to place the rest)";
                case STALLED -> "nothing placed for " + BuilderReport.STALL_SECONDS + " s";
                default -> "Baritone's builder stopped";
            };
            List<String> miss = new ArrayList<>();
            for (var m : sc.left.entrySet()) if (miss.size() < 4) miss.add(m.getValue() + " " + Blueprint.bagKey(m.getKey()));
            String rest = head + (miss.isEmpty() ? "" : ", left " + String.join(", ", miss)) + " - " + why;
            LAST_STOP.put(r.l.name(), rest);
            msg = gained > 0 ? "done: placed " + gained + " of " + rest : "stopped: " + rest;
        }
        LOG.info("[entropybot] build: {}", msg);
        Clearing.endBuild(bs);
        seq.jobs.finish(msg, msg.startsWith("stopped"));
        return "wait";
    }
}
