package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The mod's side of the KubeJS bridge while the bridge still does the jobs (B7a-B7d, docs/BOT_PLAN.md 5.14).
 * The mod reads the PMs and cmd.json and runs chains; a verb the mod does not do yet becomes a {@link Request} the
 * bridge picks up ({@code BotAPI.bridgeNext}), answers ({@code bridgeReply}: the reply, and whether a job started)
 * and, when that job ends, reports ({@code bridgeDone}). Once a second the bridge sends its job and notes
 * ({@code bridgeReport}). Plain Java: the clock is passed in, JUnit drives it.
 */
public final class BridgeLink {
    /** A reload of the bridge script took the request's job with it; a chain runs that step again. */
    public static final String RELOADED = "interrupted: the bridge script reloaded";
    /** A walk replaced by the next command (the bridge's quiet "job.done = true"). */
    public static final String REPLACED = "stopped: replaced by a new command";

    /** An end nobody needs to hear about. */
    public static boolean quiet(String doneMsg) {
        return doneMsg == null || RELOADED.equals(doneMsg) || REPLACED.equals(doneMsg);
    }
    static final long REPORT_FRESH = 100, REPLY_TIMEOUT = 200, LOST_AFTER = 600;

    public interface Listener {
        /** The bridge's answer (r.reply, r.started). */
        void replied(Request r);

        /** The job the request started has ended (r.doneMsg), or the request was lost. */
        void finished(Request r);
    }

    public static final class Request {
        public final long id;
        public final String kind, from, text;
        public final boolean internal;
        public final JsonObject cmd;
        final Listener listener;
        public boolean sent, replied, started, finished;
        /** The mod's own job (B7b): no bridge involved, so a bridge reload or report never ends it. */
        public boolean local;
        public String reply, doneMsg;
        long sentAt, queuedAt;
        int missing;
        String lastStatus;

        Request(long id, String kind, String from, String text, boolean internal, JsonObject cmd, Listener listener, long now) {
            this.id = id;
            this.kind = kind;
            this.from = from;
            this.text = text;
            this.internal = internal;
            this.cmd = cmd;
            this.listener = listener;
            this.queuedAt = now;
        }

        public boolean open() { return !finished && !(replied && !started); }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("kind", kind);
            if (from != null) o.addProperty("from", from);
            o.addProperty("text", text == null ? "" : text);
            o.addProperty("internal", internal);
            if (cmd != null) o.add("cmd", cmd);
            return o;
        }
    }

    private final ArrayDeque<Request> queue = new ArrayDeque<>();
    private final Map<Long, Request> open = new LinkedHashMap<>();
    private long nextId = 1, lastReport = -100000, lastHello = -100000, helloCount;
    private JsonObject report;

    /** A request for the bridge; stop and the walk's end jump the queue. */
    public synchronized Request submit(String kind, String from, String text, boolean internal, JsonObject cmd, Listener l, long now) {
        Request r = new Request(nextId++, kind, from, text, internal, cmd, l, now);
        if (kind.equals("stop") || kind.equals("endwalk")) queue.addFirst(r);
        else queue.addLast(r);
        return r;
    }

    /**
     * A request for a job the mod runs itself: already answered (reply, started), open until {@link #done}. Its
     * caller delivers the reply; the listener hears only the end.
     */
    public synchronized Request local(String kind, String from, String text, String reply, Listener l, long now) {
        Request r = new Request(nextId++, kind, from, text, false, null, l, now);
        r.local = true;
        r.sent = true;
        r.sentAt = now;
        r.replied = true;
        r.started = true;
        r.reply = reply;
        open.put(r.id, r);
        return r;
    }

    /** The next request as JSON for the bridge, or "" when none waits. */
    public synchronized String next(long now) {
        Request r = queue.pollFirst();
        if (r == null) return "";
        r.sent = true;
        r.sentAt = now;
        open.put(r.id, r);
        return r.toJson().toString();
    }

    /** The bridge's answer to request id; started = it began a job that will report when it ends. */
    public void reply(long id, String text, boolean started) {
        Request r;
        synchronized (this) {
            r = open.get(id);
            if (r == null || r.replied || r.finished) return;
            r.replied = true;
            r.reply = text;
            r.started = started;
            if (!started) open.remove(id);
        }
        if (r.listener != null) r.listener.replied(r);
    }

    /** The job request id started has ended with msg. */
    public void done(long id, String msg) {
        Request r;
        synchronized (this) {
            r = open.remove(id);
            if (r == null || r.finished) return;
            r.finished = true;
            r.doneMsg = msg;
        }
        if (r.listener != null) r.listener.finished(r);
    }

    /** The bridge (re)loaded: whatever it was doing for us is gone. */
    public void hello(long now) {
        List<Request> lost;
        synchronized (this) {
            lastHello = now;
            lastReport = now;
            helloCount++;
            lost = new ArrayList<>();
            for (Request r : open.values()) if (!r.local) lost.add(r);
            for (Request r : lost) open.remove(r.id);
            for (Request r : lost) {
                r.finished = true;
                r.doneMsg = RELOADED;
            }
        }
        for (Request r : lost) if (r.listener != null) r.listener.finished(r);
    }

    /** Once a second from the bridge: {job:{type,status,done,req?}, memory, screen, container, bagRoom, supplies, orePrefer...}. */
    public void report(JsonObject o, long now) {
        List<Request> ended = new ArrayList<>();
        synchronized (this) {
            report = o;
            lastReport = now;
            JsonObject job = o != null && o.has("job") && o.get("job").isJsonObject() ? o.getAsJsonObject("job") : null;
            long req = job != null && job.has("req") && !job.get("req").isJsonNull() ? job.get("req").getAsLong() : -1;
            for (Request r : open.values()) {
                if (!r.started || r.local) continue;
                if (req == r.id) {
                    r.lastStatus = str(job, "status");
                    r.missing = 0;
                    if (job.has("done") && job.get("done").getAsBoolean()) {
                        r.finished = true;
                        r.doneMsg = r.lastStatus == null ? "ok: done" : r.lastStatus;
                        ended.add(r);
                    }
                } else if (++r.missing >= 2) {
                    // the job went away without a word (replaced by a walk, say): don't wait for it forever
                    r.finished = true;
                    r.doneMsg = "stopped: lost track of the job" + (r.lastStatus == null ? "" : " (" + r.lastStatus + ")");
                    ended.add(r);
                }
            }
            for (Request r : ended) open.remove(r.id);
        }
        for (Request r : ended) if (r.listener != null) r.listener.finished(r);
    }

    /** Once a tick: requests the bridge never answered, jobs of a bridge that went away. */
    public void tick(long now) {
        List<Request> noReply = new ArrayList<>(), lost = new ArrayList<>();
        synchronized (this) {
            boolean here = present(now);
            for (Iterator<Request> it = queue.iterator(); it.hasNext(); ) {
                Request r = it.next();
                if (!here && now - r.queuedAt > REPLY_TIMEOUT) {
                    it.remove();
                    noReply.add(r);
                }
            }
            for (Iterator<Request> it = open.values().iterator(); it.hasNext(); ) {
                Request r = it.next();
                if (r.local) continue;
                if (!r.replied && now - r.sentAt > REPLY_TIMEOUT) {
                    it.remove();
                    noReply.add(r);
                } else if (r.started && !here && now - lastReport > LOST_AFTER) {
                    it.remove();
                    r.finished = true;
                    r.doneMsg = "stopped: the bridge script stopped";
                    lost.add(r);
                }
            }
            for (Request r : noReply) {
                r.replied = true;
                r.started = false;
                r.reply = "error: no answer from the bridge script (KubeJS) - is it loaded?";
            }
        }
        for (Request r : noReply) if (r.listener != null) r.listener.replied(r);
        for (Request r : lost) if (r.listener != null) r.listener.finished(r);
    }

    /** "stop": requests not yet picked up are dropped (their listeners hear "ok: stopped"). */
    public void dropQueued() {
        List<Request> dropped;
        synchronized (this) {
            dropped = new ArrayList<>(queue);
            queue.clear();
            for (Request r : dropped) {
                r.replied = true;
                r.started = false;
                r.reply = "ok: stopped";
            }
        }
        for (Request r : dropped) if (r.listener != null) r.listener.replied(r);
    }

    public synchronized boolean present(long now) { return now - lastReport <= REPORT_FRESH; }

    public synchronized long helloCount() { return helloCount; }

    /** The bridge's last report while it is fresh, else null. */
    public synchronized JsonObject report(long now) { return present(now) ? report : null; }

    /** True while a request waits or runs, or the bridge reports a job of its own. */
    public synchronized boolean busy(long now) {
        if (!queue.isEmpty() || !open.isEmpty()) return true;
        return jobRunning(now);
    }

    /**
     * A request waits for the bridge, or one it took has not ended yet (package G's /wait: between the bridge's
     * "started" and its next report, the job shows nowhere else). The mod's own jobs ({@link Request#local}) don't count.
     */
    public synchronized boolean waitingOnBridge() {
        if (!queue.isEmpty()) return true;
        for (Request r : open.values()) if (!r.local) return true;
        return false;
    }

    /** The bridge's job is running (from its last fresh report). */
    public synchronized boolean jobRunning(long now) {
        JsonObject j = job(now);
        return j != null && !(j.has("done") && j.get("done").getAsBoolean());
    }

    public synchronized JsonObject job(long now) {
        JsonObject r = report(now);
        return r != null && r.has("job") && r.get("job").isJsonObject() ? r.getAsJsonObject("job") : null;
    }

    public synchronized int queued() { return queue.size(); }

    static String str(JsonObject o, String k) {
        if (o == null || !o.has(k)) return null;
        JsonElement e = o.get(k);
        return e.isJsonNull() ? null : e.getAsString();
    }
}
