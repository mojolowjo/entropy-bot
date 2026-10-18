package io.github.mojolowjo.entropybot.aa.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A JobPort for the tests: the test moves the task along (done, fail, gone, progress) by hand. */
final class FakePort implements JobPort {
    long nextId = 1;
    String refuse;
    String lastRefusal;
    final Map<Long, JobState> states = new HashMap<>();
    final Map<Long, Long> progress = new HashMap<>();
    final List<TaskSpec> started = new ArrayList<>();
    final List<String> cancelled = new ArrayList<>(), log = new ArrayList<>(), incidents = new ArrayList<>();
    int paused, resumed;

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

    @Override public long progress(long taskId) { return progress.getOrDefault(taskId, 0L); }

    @Override public void log(String line) { log.add(line); }

    @Override public void incident(String what) { incidents.add(what); }

    void done(long id, String text) { states.put(id, JobState.done(text)); }

    void fail(long id, String text) { states.put(id, JobState.failed(text)); }

    void gone(long id, String who) { states.put(id, JobState.gone(who)); }

    void step(long id) { progress.merge(id, 1L, Long::sum); }
}
