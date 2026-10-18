package io.github.mojolowjo.entropybot.aa.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A JobPort for the tests: the test moves the task along (done, fail, gone, progress) by hand. */
public final class FakePort implements JobPort {
    public long nextId = 1;
    /** A refusal for every start (null = starts); resolve's answer: "*" = the text itself, null = still planning, else the chain. */
    public String refuse, resolveTo = "*";
    public String lastRefusal;
    public final Map<Long, JobState> states = new HashMap<>();
    public final Map<Long, Long> progress = new HashMap<>();
    public final List<TaskSpec> started = new ArrayList<>();
    public final List<String> cancelled = new ArrayList<>(), log = new ArrayList<>(), incidents = new ArrayList<>();
    public int paused, resumed;

    @Override public long start(TaskSpec t) {
        if (refuse != null) {
            lastRefusal = refuse;
            return -1;
        }
        long id = nextId++;
        started.add(t);
        states.put(id, JobState.RUNNING);
        progress.put(id, 0L);
        return id;
    }

    @Override public String lastRefusal() { return lastRefusal; }

    @Override public JobState poll(long taskId) { return states.getOrDefault(taskId, JobState.gone("nobody knows it")); }

    @Override public void cancel(long taskId, String why) {
        cancelled.add(taskId + ": " + why);
        states.remove(taskId);
    }

    @Override public void pause(long taskId, String why) { paused++; }

    @Override public void resume(long taskId) { resumed++; }

    @Override public String resolve(String chain) { return "*".equals(resolveTo) ? chain : resolveTo; }

    @Override public long progress(long taskId) { return progress.getOrDefault(taskId, 0L); }

    @Override public void log(String line) { log.add(line); }

    @Override public void incident(String what) { incidents.add(what); }

    public void done(long id, String text) { states.put(id, JobState.done(text)); }

    public void fail(long id, String text) { states.put(id, JobState.failed(text)); }

    public void gone(long id, String who) { states.put(id, JobState.gone(who)); }

    public void step(long id) { progress.merge(id, 1L, Long::sum); }
}
