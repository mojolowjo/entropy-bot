package io.github.mojolowjo.entropybot.commands;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The requests the mod's own jobs carry (B7e: what is left of the KubeJS bridge link). A job that starts gets a
 * {@link Request}, already answered (its reply, started); the requester (a PM's whisper, a cmd.json notify, a chain
 * waiting for its step) hears its end through the {@link Listener} when {@link #done} is called. Plain Java: JUnit
 * drives it.
 */
public final class JobRequests {
    /** A walk replaced by the next command (or a twerk toggled off): an end nobody needs to hear about. */
    public static final String REPLACED = "stopped: replaced by a new command";

    /** An end nobody needs to hear about. */
    public static boolean quiet(String doneMsg) {
        return doneMsg == null || REPLACED.equals(doneMsg);
    }

    /** Hears the end of the job a request started. */
    @FunctionalInterface
    public interface Listener {
        /** The job has ended (r.doneMsg). */
        void finished(Request r);
    }

    public static final class Request {
        public final long id;
        public final String kind, from, text;
        final Listener listener;
        /** Always true for a job's request (it started and was answered when it was made); kept for the chains' checks. */
        public boolean replied = true, started = true, finished;
        public String reply, doneMsg;

        Request(long id, String kind, String from, String text, String reply, Listener listener) {
            this.id = id;
            this.kind = kind;
            this.from = from;
            this.text = text;
            this.reply = reply;
            this.listener = listener;
        }

        public boolean open() { return !finished; }
    }

    private final Map<Long, Request> open = new LinkedHashMap<>();
    private long nextId = 1;

    /**
     * A request for a job the mod runs: answered (reply, started), open until {@link #done}. Its caller delivers the
     * reply; the listener hears only the end.
     */
    public synchronized Request local(String kind, String from, String text, String reply, Listener l) {
        Request r = new Request(nextId++, kind, from, text, reply, l);
        open.put(r.id, r);
        return r;
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

    /** True while a job's request is open. */
    public synchronized boolean busy() { return !open.isEmpty(); }

    /** How many requests are open. */
    public synchronized int open() { return open.size(); }
}
