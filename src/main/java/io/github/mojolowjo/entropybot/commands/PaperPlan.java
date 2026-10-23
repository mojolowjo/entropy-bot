package io.github.mojolowjo.entropybot.commands;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.Quiet;
import org.slf4j.Logger;

/**
 * AA's walks out of a shaft (pillar, tunnel up): one travel job to a cell straight above, with Baritone's own travel
 * settings; {@link #owned} while it runs (the safety net restores the leftovers when it ends). The old {@code plan path}
 * verb (the paper A* search) is gone. Loader API: none (the travel job, Baritone through it).
 */
public final class PaperPlan {
    private static final Logger LOG = LogUtils.getLogger();
    public static final PaperPlan INSTANCE = new PaperPlan();

    private PaperPlan() {}

    /** review 3: the plan-go job itself (not its id): a new job never matches a stale owner. */
    private volatile Object goJobObj;
    private volatile long goJob = -1;

    /** The plan-go job runs (0.39.z8: it owns no Baritone setting; its walk uses Baritone's own travel settings). */
    public static boolean owned() {
        PaperPlan pp = INSTANCE;
        Object o = pp.goJobObj;
        if (o == null) return false;
        try {
            var j = Core.INSTANCE.commands.jobs.job;
            return j != null && !j.done && j == o;
        } catch (Throwable t) {
            Quiet.warn("PaperPlan.owned failed, carrying on with the fallback", t);
            return false;
        }
    }

    /** Core tick: a plan-go job that ended lets go of the plan-go slot (and the stop leftovers are put back). */
    public void tick() {
        if (goJobObj != null && !owned()) {
            goJobObj = null;
            goJob = -1;
            try {
                io.github.mojolowjo.entropybot.baritone.SafetyNet.INSTANCE.restore();
                LOG.info("[entropybot] plan go: the job ended");
            } catch (Throwable t) {
                LOG.warn("[entropybot] plan go: couldn't restore the settings: {}", t.toString());
            }
        }
    }

    /** review 3: leaving or switching the world: no owner. */
    public void reset(String why) {
        boolean was = goJobObj != null;
        goJobObj = null;
        goJob = -1;
        if (was) {
            try { io.github.mojolowjo.entropybot.baritone.SafetyNet.INSTANCE.restore(); } catch (Throwable caught) { Quiet.warn("PaperPlan.reset failed, carrying on with the fallback", caught); }
            LOG.info("[entropybot] plan go: {}: the walk let go", why);
        }
    }

    /**
     * 0.36.a6: AA's pillar out of a shaft: a plain walk to {@code e} (straight above) with Baritone's own travel settings (0.39.z8: no setting is turned on
     * for it), so Baritone pillars up with its throwaway blocks.
     */
    String pillar(Commands c, int[] e, String from) {
        if (owned()) return "busy: a plan go is walking";
        String s = c.jobs.startPlanGo(e, from, id -> { goJob = id; goJobObj = c.jobs.job; });
        LOG.info("[entropybot] pillar: to {} (Baritone's own travel settings), job {}: {}", Jobs.fmt(e), goJob, s);
        return s;
    }

    /** aa-replan 2: tunnel up + pillar to {@code e} (straight over me): with Baritone's own travel settings. */
    String tunnelUp(Commands c, int[] e, String from) {
        if (owned()) return "busy: a plan go is walking";
        String s = c.jobs.startPlanGo(e, from, id -> { goJob = id; goJobObj = c.jobs.job; });
        LOG.info("[entropybot] tunnel up: to {} (Baritone's own travel settings), job {}: {}", Jobs.fmt(e), goJob, s);
        return s;
    }

}
