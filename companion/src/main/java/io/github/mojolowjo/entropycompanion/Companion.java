package io.github.mojolowjo.entropycompanion;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The companion's shared state and the {@code /bot} dispatcher (0.2.0, COMPANION_PLAN C1 + C3). Everything the
 * keys, the menu, the overlay and the box view need goes through here. Network calls run on worker threads; chat
 * and overlay lines are put on the client thread with {@code mc.execute}. Never throws out of a public method.
 */
public final class Companion {
    public static final String VERSION = "0.2.0";
    private static final Logger LOG = LogUtils.getLogger();
    private static final Pattern UNKNOWN = Pattern.compile("^unknown command \"([^\"]+)\"");
    static final long BOX_POLL_MS = 5000;

    static Companion INSTANCE;

    final Supplier<CompanionConfig> config;
    final PostLoop posts;
    final CmdClient.Link link = new CmdClient.Link();
    final CmdClient cmds;
    final HttpSender http;
    final ReplyQueue replies = new ReplyQueue();
    final Corners corners = new Corners();
    final OwnerState ownerState = new OwnerState();
    private final ExecutorService cmdWorker = daemon("entropy-companion-cmd");
    private final ExecutorService boxWorker = daemon("entropy-companion-boxes");
    private final AtomicBoolean boxBusy = new AtomicBoolean();
    private final Deque<String> recent = new ArrayDeque<>();
    final Set<String> unknownVerbs = ConcurrentHashMap.newKeySet();
    // box view
    volatile boolean boxView;
    volatile List<BoxSet.Box> boxes = List.of();
    volatile boolean boxesStale;
    private volatile long boxNextAt;
    private volatile boolean boxDownNoticed;
    volatile int framesDrawn, boxesDrawn;
    // follow toggle and errors
    volatile boolean following;
    private int errors;
    private long lastErrorLogAt;
    volatile String lastError = "none";

    Companion(Supplier<CompanionConfig> config, PostLoop posts, HttpSender http) {
        this.config = config;
        this.posts = posts;
        this.http = http;
        this.cmds = new CmdClient(http, link, System::currentTimeMillis);
        posts.link(link, this::notice);
    }

    private static ExecutorService daemon(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    // ---- output ----

    /** A reply: one gray chat line and the overlay. Any thread. */
    void say(String text) {
        String t = CmdClient.mask(config.get(), text);
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            try {
                if (mc.player == null) return;
                mc.gui.getChat().addMessage(prefix().append(Component.literal(t)));
                int secs = config.get().overlaySeconds;
                if (secs > 0) replies.add("[bot] " + t, System.currentTimeMillis(), secs * 1000L);
            } catch (RuntimeException e) {
                error("chat", e);
            }
        });
    }

    /** The link changed: "dashboard unreachable" stays in the overlay until "connected". */
    void notice(String text) {
        replies.sticky(text.equals("connected") ? null : "[bot] " + text);
        say(text);
    }

    static MutableComponent prefix() {
        return Component.literal("[bot] ").withStyle(ChatFormatting.GRAY);
    }

    /** First 3 errors in full, then one line a minute. */
    synchronized void error(String where, Throwable t) {
        errors++;
        lastError = where + ": " + t;
        long now = System.currentTimeMillis();
        if (errors <= 3) LOG.error("[entropycompanion] {} error #{}", where, errors, t);
        else if (now - lastErrorLogAt >= 60_000) {
            lastErrorLogAt = now;
            LOG.warn("[entropycompanion] {} error #{} {}", where, errors, t.toString());
        }
    }

    // ---- commands ----

    /** Sends a line to the bot (as the owner, through the dashboard). Any thread; the answer comes back by say(). */
    void send(String text) {
        try {
            String line = text == null ? "" : text.trim();
            if (line.isEmpty()) return;
            CompanionConfig c = config.get();
            Minecraft mc = Minecraft.getInstance();
            if (!c.accountOk(mc.getUser().getName())) {
                say("not sending: this account is not the ownerName in config/" + CompanionConfig.FILE_NAME);
                return;
            }
            String no = cmds.admit();
            if (no != null) {
                say(no);
                return;
            }
            remember(line);
            cmdWorker.execute(() -> {
                String reply = cmds.run(c, line);
                Matcher m = UNKNOWN.matcher(reply);
                if (m.find()) unknownVerbs.add(m.group(1).toLowerCase(Locale.ROOT));
                say(reply);
            });
        } catch (RuntimeException e) {
            error("send", e);
            say("companion error: " + e.getClass().getSimpleName());
        }
    }

    private synchronized void remember(String line) {
        recent.remove(line);
        recent.addFirst(line);
        while (recent.size() > 3) recent.removeLast();
    }

    synchronized List<String> recent() { return new ArrayList<>(recent); }

    /** True when the bot answered "unknown command" for this line's verb this session (the menu hides it). */
    boolean known(String line) {
        String verb = line.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        return !unknownVerbs.contains(verb);
    }

    /** {@code /bot <text>}: local sub-commands first, everything else goes to the bot. Client thread. */
    void handle(String text) {
        try {
            String t = text == null ? "" : text.trim();
            String lower = t.toLowerCase(Locale.ROOT);
            if (t.isEmpty() || lower.equals("help")) {
                say("/bot <command> sends it to the bot. Here: /bot that (point), /bot that? (show only), /bot companion, "
                        + "/bot corner1|corner2, /bot corners area|destroy|main|safe <name>, /bot corners clear, /bot boxes on|off. Bot help: /bot help <verb>");
                return;
            }
            if (lower.equals("status")) {
                say(statusLine());
                send("status");
                return;
            }
            if (lower.equals("companion")) {
                say(statusLine());
                say(counters());
                return;
            }
            if (lower.equals("that") || lower.equals("that?")) {
                point(lower.endsWith("?"));
                return;
            }
            if (lower.equals("corner1") || lower.equals("corner2")) {
                corner(lower.endsWith("1") ? 1 : 2);
                return;
            }
            if (lower.startsWith("corners")) {
                corners(t.substring("corners".length()).trim());
                return;
            }
            if (lower.startsWith("boxes")) {
                String a = lower.substring("boxes".length()).trim();
                setBoxView(a.isEmpty() ? !boxView : a.equals("on"));
                return;
            }
            send(t);
        } catch (RuntimeException e) {
            error("command", e);
            say("companion error: " + e.getClass().getSimpleName());
        }
    }

    /** Point-and-command: what the crosshair hits, as a command (dryRun: only show it). */
    void point(boolean dryRun) {
        CompanionConfig c = config.get();
        PointRules.Result r = PointRules.decide(Pointer.hit(Minecraft.getInstance()), c.pointMineCount, c.pointChopCount);
        if (!r.ok()) say(r.refusal());
        else if (dryRun) say("would send: " + r.command());
        else {
            say("> " + r.command());
            send(r.command());
        }
    }

    // ---- C3 corners ----

    void corner(int which) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        int[] b = Pointer.cornerBlock(mc);
        Corners.Pos p = new Corners.Pos(b[0], b[1], b[2], mc.level.dimension().location().toString());
        corners.set(which, p);
        say("corner " + which + " = " + p.text() + (b[3] == 1 ? " (the block you look at)" : " (your feet)"));
        if (corners.missing() == null) promptCorners();
    }

    /** After both corners: clickable lines that put "/bot corners area " or "... protect " in the chat box. */
    void promptCorners() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player == null) return;
            MutableComponent line = prefix().append(Component.literal("both corners set - name it: "));
            line.append(choice("[area]", "/bot corners area ", "a neutral area: the bot may walk and dig natural blocks (all heights)", ChatFormatting.WHITE));
            line.append(Component.literal(" "));
            line.append(choice("[destroy]", "/bot corners destroy ", "a destroy area between the corners' heights: dig <name> breaks everything there, built blocks too (never chests)", ChatFormatting.RED));
            line.append(Component.literal(" "));
            line.append(choice("[main]", "/bot corners main ", "the main area (the base): never breaks built blocks (all heights)", ChatFormatting.BLUE));
            line.append(Component.literal(" "));
            line.append(choice("[safe]", "/bot corners safe ", "a safe area (someone's build): walk only, never dig; 8 below to 16 above", ChatFormatting.GREEN));
            mc.gui.getChat().addMessage(line);
        });
    }

    private static MutableComponent choice(String label, String cmd, String hover, ChatFormatting color) {
        return Component.literal(label).withStyle(s -> s.withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, cmd))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover + " - type the name, then Enter"))));
    }

    /** "/bot corners area|areay|protect <name>" or "corners clear". */
    void corners(String rest) {
        String[] w = rest.split("\\s+");
        String sub = w.length > 0 ? w[0].toLowerCase(Locale.ROOT) : "";
        String name = w.length > 1 ? w[1] : "";
        if (sub.equals("clear")) {
            corners.clear();
            say("corners cleared");
            return;
        }
        String cmd = switch (sub) {
            case "area", "neutral" -> corners.areaCommand(name, "neutral", false);
            case "areay" -> corners.areaCommand(name, "neutral", true);
            case "destroy", "main", "safe" -> corners.areaCommand(name, sub, false);
            case "protect" -> corners.areaCommand(name, "safe", false);      // the old button word: a safe area now
            default -> "error: /bot corners area|destroy|main|safe <name> | clear";
        };
        if (cmd.startsWith("error:")) {
            say(cmd);
            return;
        }
        say("> " + cmd);
        send(cmd);
        boxNextAt = 0;     // the box view shows the new box at the next poll
    }

    // ---- C3 box view ----

    void setBoxView(boolean on) {
        boxView = on;
        boxNextAt = 0;
        boxDownNoticed = false;
        say("box view " + (on ? "on (white: neutral, red: destroy, blue: main, green: safe areas, yellow: your corners)" : "off"));
    }

    /** From the client tick: fetch /api/map/boxes every 5 s while the box view is on. */
    void tickBoxes() {
        if (!boxView || System.currentTimeMillis() < boxNextAt || !boxBusy.compareAndSet(false, true)) return;
        boxNextAt = System.currentTimeMillis() + BOX_POLL_MS;
        CompanionConfig c = config.get();
        boxWorker.execute(() -> {
            try {
                if (!c.active()) return;
                URI uri = c.apiUri("/api/map/boxes");
                CmdClient.Resp r = http.send("GET", uri, c.key, null, PostLoop.TIMEOUT_MS);
                if (r.status() != 200) throw new java.io.IOException("HTTP " + r.status());
                boxes = Collections.unmodifiableList(BoxSet.parse(r.body()));
                boxesStale = false;
                boxDownNoticed = false;
            } catch (Exception e) {
                boxesStale = true;          // keep the last boxes, greyed
                if (!boxDownNoticed) {
                    boxDownNoticed = true;
                    say("box view: dashboard unreachable (" + e.getClass().getSimpleName() + ") - showing the last boxes, greyed");
                }
            } finally {
                boxBusy.set(false);
            }
        });
    }

    // ---- status ----

    String statusLine() {
        CompanionConfig c = config.get();
        long last = posts.lastOkAt();
        String posting = !c.active() ? (c.enabled ? "not set up (url and key in config/" + CompanionConfig.FILE_NAME + ")" : "disabled")
                : link.down() ? "dashboard unreachable"
                : last == 0 ? "no post yet" : "posting ok (last " + String.format(Locale.ROOT, "%.1f", (System.currentTimeMillis() - last) / 1000.0) + " s ago)";
        long reply = cmds.lastReplyAt();
        return "companion " + VERSION + ": dashboard " + (c.url.isEmpty() ? "(no url)" : c.url) + ", " + posting
                + ", last reply " + (reply == 0 ? "none" : (System.currentTimeMillis() - reply) / 1000 + " s ago")
                + ", keys: " + Keys.summary() + ", overlay " + (c.overlaySeconds > 0 ? "on" : "off") + ", box view " + (boxView ? "on" : "off");
    }

    String counters() {
        return "posts ok " + posts.okCount() + ", failed " + posts.failedCount() + " (last: " + posts.lastError() + "); commands sent "
                + cmds.sent() + ", failed " + cmds.failed() + " (last: " + cmds.lastError() + "); boxes " + boxes.size()
                + (boxesStale ? " (stale)" : "") + ", frames drawn " + framesDrawn + ", boxes drawn " + boxesDrawn + "; errors: " + lastError;
    }
}
