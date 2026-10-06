package io.github.mojolowjo.entropybot.commands;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.camp.DayNight;
import io.github.mojolowjo.entropybot.gui.Gui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * C4 {@code sleep}: walk to the nearest bed within 24 blocks (or put down the bed it carries next to itself when it is
 * night and none is near), right-click it, and stay in it until morning or {@code stop}. {@code sleep status}.
 * A Seq job: [place bed] -> walk -> the "sleep" step here, so the walk has the usual unsticking and fence checks.
 *
 * <p>Steve's Realistic Sleep (in the pack) doesn't skip the night when only some players sleep: it speeds the night up
 * by the share of players in bed, so with the owner awake the night passes faster but not at once. The job just waits
 * in the bed until the server says it is day (or 15 minutes pass), and reports {@code slept until morning}. A refused
 * click reports the server's own message ({@code could not sleep: You may not rest now; there are monsters nearby}),
 * caught from the chat event ({@link #noteChat}).
 *
 * <p>Loader notes: the bed click is vanilla {@code MultiPlayerGameMode.useItemOn} (Jobs.useBlock); waking is the vanilla
 * {@code ServerboundPlayerCommandPacket STOP_SLEEPING}; the refusal text comes from NeoForge's
 * {@code ClientChatReceivedEvent} (system/overlay messages; Fabric: {@code ClientReceiveMessageEvents.GAME}).
 */
public final class SleepJob {
    private static final Logger LOG = LogUtils.getLogger();
    static final long MAX_TICKS = 15 * 60 * 20;
    static final int SEARCH = 24;

    private SleepJob() {}

    /** The last bed answer from the server (text) and its tick. */
    private static volatile String lastBed;
    private static volatile long lastBedAt = -1;

    /** From Commands.onChat: a system message about a bed or sleeping. */
    static void noteChat(net.minecraft.network.chat.Component msg, long tick) {
        try {
            String key = msg.getContents() instanceof TranslatableContents tc ? tc.getKey() : "";
            String text = msg.getString();
            String low = text.toLowerCase();
            if (key.startsWith("block.minecraft.bed.")
                    || low.contains("rest now") || low.contains("sleep only") || low.contains("bed is") || low.contains("you can sleep")) {
                lastBed = text;
                lastBedAt = tick;
            }
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] sleep chat: {}", e.toString());
        }
    }

    static boolean night(ClientLevel lv) {
        return lv.isThundering() || DayNight.night(lv.getDayTime());
    }

    static String status(LocalPlayer p) {
        ClientLevel lv = Minecraft.getInstance().level;
        String bed = Jobs.findBlock(p, "_bed " + SEARCH);
        String carried = carriedBed(p);
        return "sleep: it is " + DayNight.clock(lv.getDayTime()) + (night(lv) ? " (night: beds work)" : " (day: beds don't work)")
                + (p.isSleeping() ? ", I am in bed" : "") + "; " + (bed.startsWith("found") ? "nearest bed at " + bed.split(" ")[1] + " " + bed.split(" ")[2] + " " + bed.split(" ")[3]
                : "no bed within " + SEARCH) + (carried != null ? "; I carry a " + carried.replace("minecraft:", "") : "")
                + " - rule when night do sleep sleeps every night";
    }

    static String carriedBed(LocalPlayer p) {
        for (Map.Entry<String, Integer> e : Gui.inventory(p).entrySet()) if (e.getKey().endsWith("_bed") && e.getValue() > 0) return e.getKey();
        return null;
    }

    /** "sleep" / "sleep status". */
    static String start(Commands c, LocalPlayer p, String rest) {
        if (rest != null && rest.trim().equalsIgnoreCase("status")) return status(p);
        if (rest != null && !rest.isBlank()) return "usage: sleep | sleep status";
        ClientLevel lv = Minecraft.getInstance().level;
        if (p.isSleeping()) return "ok: I am already in bed";
        if (!night(lv)) return Hints.next("error: could not sleep: it is day (" + DayNight.clock(lv.getDayTime()) + ")", "rule when night do sleep (I sleep every night by myself)");
        List<Seq.Step> steps = new ArrayList<>();
        int[] bed;
        String f = Jobs.findBlock(p, "_bed " + SEARCH);
        String placed = null;
        if (f.startsWith("found")) {
            String[] w = f.split(" ");
            bed = new int[]{Integer.parseInt(w[1]), Integer.parseInt(w[2]), Integer.parseInt(w[3])};
        } else {
            String id = carriedBed(p);
            if (id == null) return Hints.next("error: no bed within " + SEARCH + " blocks and I carry none", "give me a bed (or craft white_bed 1: 3 wool + 3 planks), then sleep");
            bed = bedSpot(lv, Jobs.here(p));
            if (bed == null) return Hints.next("error: no room for my bed next to me (2 free blocks in a row on solid ground)", "goto an open spot, then sleep");
            steps.add(Clearing.placeStep(bed, id));
            placed = id;
        }
        String why = c.jobs.goalAllowed(bed[0], bed[1], bed[2]);
        if (why != null) return Jobs.withAreaHint(why);
        steps.add(Seq.Step.walk(bed, true));
        Seq.Step s = new Seq.Step("sleep");
        s.pos = bed;
        steps.add(s);
        String r = c.jobs.startSeq(new Seq(c.jobs, c.storage, placed != null ? "putting my bed down and sleeping" : "going to bed at " + Jobs.fmt(bed), steps, "fail"), "fail");
        Jobs.Job j = c.jobs.job;
        if (j != null) {
            Runnable prev = j.onEnd;
            j.onEnd = () -> {
                if (prev != null) prev.run();
                wake();
            };
        }
        return r;
    }

    /** A cell next to the bot with the next one in the same direction free too (the bed's foot and head). */
    static int[] bedSpot(ClientLevel lv, int[] f) {
        int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        for (int[] d : dirs) {
            BlockPos a = new BlockPos(f[0] + d[0], f[1], f[2] + d[1]), b = new BlockPos(f[0] + 2 * d[0], f[1], f[2] + 2 * d[1]);
            if (CampCommands.placeable(lv, a) && CampCommands.placeable(lv, b) && lv.getBlockState(a.above()).isAir()) return new int[]{a.getX(), a.getY(), a.getZ()};
        }
        return null;
    }

    static void wake() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null && p.isSleeping() && p.connection != null) {
            p.connection.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.STOP_SLEEPING));
            LOG.info("[entropybot] sleep: got out of bed");
        }
    }

    private static final class State {
        String stage = "click";
        long at;
    }

    /** The Seq "sleep" step. */
    static String step(Seq s, Seq.Step st, LocalPlayer p) {
        State ps = st.state instanceof State x ? x : new State();
        st.state = ps;
        ClientLevel lv = Minecraft.getInstance().level;
        long now = s.now();
        switch (ps.stage) {
            case "click" -> {
                if (!night(lv)) {
                    s.jobs.finish("error: could not sleep: it is day (" + DayNight.clock(lv.getDayTime()) + ") - next: rule when night do sleep");
                    return "wait";
                }
                String r = Jobs.useBlock(p, Jobs.fmt(st.pos));
                if (!r.startsWith("ok")) return "couldn't click the bed at " + Jobs.fmt(st.pos) + ": " + r.replaceFirst("^error: ", "");
                ps.stage = "check";
                ps.at = now;
                return "wait";
            }
            case "check" -> {
                if (p.isSleeping()) {
                    ps.stage = "sleeping";
                    ps.at = now;
                    s.setStatus("sleeping (" + DayNight.clock(lv.getDayTime()) + ")");
                    return "wait";
                }
                if (now - ps.at < 40) return "wait";
                String msg = lastBedAt >= ps.at - 2 ? lastBed : null;
                s.jobs.finish("error: could not sleep: " + (msg != null ? msg : "the bed didn't take me (no answer from the server)"));
                return "wait";
            }
            default -> {
                if (now % 100 == 0) s.setStatus("sleeping (" + DayNight.clock(lv.getDayTime()) + ") - stop gets me up");
                if (!p.isSleeping()) {
                    s.jobs.finish(night(lv) && lv.getDayTime() % 24000 < 23000 ? "ok: woke up at " + DayNight.clock(lv.getDayTime()) + " (still night: the server woke me)"
                            : "ok: slept until morning (" + DayNight.clock(lv.getDayTime()) + ")");
                    return "wait";
                }
                if (now - ps.at > MAX_TICKS) {
                    wake();
                    s.jobs.finish("ok: stayed in bed 15 min, it is " + DayNight.clock(lv.getDayTime()) + " (the night passes faster the more players sleep)");
                }
                return "wait";
            }
        }
    }
}
