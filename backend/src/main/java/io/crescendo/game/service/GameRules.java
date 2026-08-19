package io.crescendo.game.service;

import java.time.LocalDate;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Salary-cap and scheduling rules for a game, bound from {@code crescendo.game.*} config so the
 * economy (cap, roster size) and the fixed draft/score dates are tunable without code changes.
 * Dates are configured rather than "now" so a demo game is deterministic against the seeded
 * feature history.
 */
@Component
@ConfigurationProperties(prefix = "crescendo.game")
public class GameRules {

    private int salaryCap = 100;
    private int rosterSize = 5;
    private LocalDate draftAsOfDate = LocalDate.parse("2026-07-01");
    private LocalDate scoreAsOfDate = LocalDate.parse("2026-08-01");

    /**
     * v1.4 no-op switch: when true, the game scores on REAL momentum collected by the daily cron
     * instead of the seeded synthetic snapshots. Default false — real history isn't mature yet
     * (~weeks of daily collection needed), so today it no-ops to the synthetic-demo behaviour.
     * Flip to true (crescendo.game.use-real-momentum) once the cron has enough real snapshots.
     */
    private boolean useRealMomentum = false;

    public int getSalaryCap() {
        return salaryCap;
    }

    public void setSalaryCap(int salaryCap) {
        this.salaryCap = salaryCap;
    }

    public int getRosterSize() {
        return rosterSize;
    }

    public void setRosterSize(int rosterSize) {
        this.rosterSize = rosterSize;
    }

    public LocalDate getDraftAsOfDate() {
        return draftAsOfDate;
    }

    public void setDraftAsOfDate(LocalDate draftAsOfDate) {
        this.draftAsOfDate = draftAsOfDate;
    }

    public LocalDate getScoreAsOfDate() {
        return scoreAsOfDate;
    }

    public void setScoreAsOfDate(LocalDate scoreAsOfDate) {
        this.scoreAsOfDate = scoreAsOfDate;
    }

    public boolean isUseRealMomentum() {
        return useRealMomentum;
    }

    public void setUseRealMomentum(boolean useRealMomentum) {
        this.useRealMomentum = useRealMomentum;
    }
}
