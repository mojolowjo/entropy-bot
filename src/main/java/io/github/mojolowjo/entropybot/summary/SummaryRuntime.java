package io.github.mojolowjo.entropybot.summary;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.camp.DayNight;
import io.github.mojolowjo.entropybot.gui.Gui;
import io.github.mojolowjo.entropybot.io.BotFiles;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;

/**
 * 0.23.6 daily summary, the game side: once a second it compares the bag (gathered / deposited) and the position
 * (distance walked); at dawn (the day key goes from night to day, {@link DayNight#key}) it whispers the day's line,
 * writes {@code entropybot/summary.json} and starts a new tally. {@code summary} answers the running tally at once.
 * Never throws (errors logged, the first 5 in full).
 *
 * <p>Loader notes: vanilla only (ClientLevel.getDayTime, Player position, the inventory through Gui.inventory).
 */
public final class SummaryRuntime {
    private static final Logger LOG = LogUtils.getLogger();
    public static final SummaryRuntime INSTANCE = new SummaryRuntime();

    private Map<String, Integer> lastBag;
    private double lx = Double.NaN, ly, lz;
    private String lastKey, lastDim;
    private int errors;

    private SummaryRuntime() {
        DaySummary.INSTANCE.reset(System.currentTimeMillis());
    }

    /** Once a second from Core. whisper: to the owner. */
    public void tick(Minecraft mc, LocalPlayer p, BotFiles files, Consumer<String> whisper) {
        try {
            if (p == null || mc.level == null) return;
            Map<String, Integer> bag = Gui.inventory(p);
            boolean open = p.containerMenu != p.inventoryMenu;
            if (lastBag != null) DaySummary.INSTANCE.bag(lastBag, bag, open);
            lastBag = bag;
            String dim = mc.level.dimension().location().toString();
            if (!Double.isNaN(lx) && dim.equals(lastDim) && p.isAlive()) DaySummary.INSTANCE.walked(Math.sqrt(sq(p.getX() - lx) + sq(p.getY() - ly) + sq(p.getZ() - lz)));
            lx = p.getX();
            ly = p.getY();
            lz = p.getZ();
            lastDim = dim;
            long t = mc.level.getDayTime();
            String key = DayNight.key(t);
            if (DaySummary.dawn(lastKey, key)) {
                String day = "day " + (t / 24000);
                String line = DaySummary.INSTANCE.text(day);
                write(files, day);
                whisper.accept(line);
                LOG.info("[entropybot] {}", line);
                DaySummary.INSTANCE.reset(System.currentTimeMillis());
            }
            lastKey = key;
        } catch (RuntimeException e) {
            if (errors++ < 5) LOG.warn("[entropybot] summary: {}", e.toString());
        }
    }

    /** "summary": the tally so far (also written to summary.json). */
    public String now(BotFiles files) {
        Minecraft mc = Minecraft.getInstance();
        long t = mc.level == null ? -1 : mc.level.getDayTime();
        String day = t < 0 ? "today" : "day " + (t / 24000) + " so far (" + DayNight.clock(t) + ")";
        write(files, day);
        return DaySummary.INSTANCE.text(day);
    }

    private void write(BotFiles files, String day) {
        if (files == null) return;
        try {
            String r = files.writeJson("summary.json", DaySummary.INSTANCE.json(day, System.currentTimeMillis()).toString());
            if (!r.startsWith("ok") && errors++ < 5) LOG.warn("[entropybot] summary.json: {}", r);
        } catch (Exception e) {
            if (errors++ < 5) LOG.warn("[entropybot] summary.json: {}", e.toString());
        }
    }

    private static double sq(double v) { return v * v; }
}
