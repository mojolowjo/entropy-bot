package io.github.mojolowjo.entropybot.move;

/** 0.23.3 movement counters for {@code path status}. Plain fields, game thread only. */
public final class MoveStats {
    public long walks, longWalks, legs, legsLong, replans, resumes, replansAfterPause, fails, reports, digOuts, keepMoving,
            readyHits, readyPlanned, readyFailed, penalised, chainPrePlans;
    /** Command-to-first-move in ticks: last, sum and count (for the average). -1 = none yet. */
    public long firstMoveLast = -1, firstMoveSum, firstMoveN;
    /** 0.23.4: command-to-dig-out-start in ticks (a walk that began sealed in): last, count; and walks that never moved. */
    public long digOutStartLast = -1, digOutStartN, noMove;
    /** Ticks standing between a chain's walk steps: last, sum, count. */
    public long gapLast = -1, gapSum, gapN;

    public void firstMove(long ticks) {
        firstMoveLast = ticks;
        firstMoveSum += ticks;
        firstMoveN++;
    }

    public void gap(long ticks) {
        gapLast = ticks;
        gapSum += ticks;
        gapN++;
    }

    static String avg(long sum, long n) {
        return n == 0 ? "-" : String.valueOf(Math.round(sum * 10.0 / n) / 10.0);
    }

    public void digOutStart(long ticks) {
        digOutStartLast = ticks;
        digOutStartN++;
    }

    public String firstMoveText() {
        return "first move " + (firstMoveLast < 0 ? "-" : firstMoveLast) + " ticks (avg " + avg(firstMoveSum, firstMoveN) + " of " + firstMoveN + ")"
                + (digOutStartN == 0 ? "" : ", dig-out began " + digOutStartLast + " ticks after the command (" + digOutStartN + " walks)")
                + (noMove == 0 ? "" : ", walks that never moved " + noMove);
    }

    public String gapText() {
        return "chain gaps " + (gapLast < 0 ? "-" : gapLast) + " ticks (avg " + avg(gapSum, gapN) + " of " + gapN + ")";
    }
}
