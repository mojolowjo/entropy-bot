package io.github.mojolowjo.entropybot.commands;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 0.33.0 review 3: a job's end carries the status the job set, separate from its text. */
class EndStatusPassTest {
    @Test
    void theStatusTravelsWithTheEnd() {
        JobRequests q = new JobRequests();
        List<Boolean> heard = new ArrayList<>();
        var a = q.local("job", "me", "gather iron", "started", r -> heard.add(r.failed));
        q.done(a.id, "mined 20 iron, 3 coal on the way", false);          // success without an "ok" word
        var b = q.local("job", "me", "goto", "started", r -> heard.add(r.failed));
        q.done(b.id, "ok: arrived, but couldn't open the door", true);    // the job knows it failed
        var c = q.local("job", "me", "x", "started", r -> heard.add(r.failed));
        q.done(c.id, "stopped: attacked by a zombie");                     // no status given: the job's own status word
        assertEquals(List.of(false, true, true), heard);
    }
}
