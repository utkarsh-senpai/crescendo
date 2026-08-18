package io.crescendo.game.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * A single-player game. Holds the player's roster (via {@link RosterPick}), the salary cap and
 * roster-size rules in force for this game, and the two dates that anchor prediction and scoring:
 * {@code draftAsOfDate} (the as-of date sent to /predict when the player drafts) and
 * {@code scoreAsOfDate} (set when the game is scored on realised forward growth).
 *
 * <p>v1.0 is single-player; the transparent AI opponent arrives in v1.1. The status field already
 * distinguishes DRAFTING from SCORED so the v1.1 opponent slots in without a schema change.
 */
@Entity
@Table(name = "game_session")
public class GameSession {

    public enum Status {
        DRAFTING,
        SCORED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String playerName;

    @Column(nullable = false)
    private int salaryCap;

    @Column(nullable = false)
    private int rosterSize;

    @Column(nullable = false)
    private LocalDate draftAsOfDate;

    private LocalDate scoreAsOfDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    /** Player's realised score once the game is scored (null while DRAFTING). */
    private Double playerScore;

    /** The transparent AI opponent's realised score, set at score time (null while DRAFTING). */
    private Double opponentScore;

    /**
     * The opponent's shown "why not" snubs, encoded as {@code artistId|name|reason} lines. Small,
     * display-only; captured at draft time so the reveal is stable. Null until the bot has drafted.
     */
    @Column(length = 2048)
    private String opponentSnubs;

    protected GameSession() {
        // JPA
    }

    public GameSession(String playerName, int salaryCap, int rosterSize, LocalDate draftAsOfDate) {
        this.playerName = playerName;
        this.salaryCap = salaryCap;
        this.rosterSize = rosterSize;
        this.draftAsOfDate = draftAsOfDate;
        this.status = Status.DRAFTING;
    }

    public Long getId() {
        return id;
    }

    public String getPlayerName() {
        return playerName;
    }

    public int getSalaryCap() {
        return salaryCap;
    }

    public int getRosterSize() {
        return rosterSize;
    }

    public LocalDate getDraftAsOfDate() {
        return draftAsOfDate;
    }

    public LocalDate getScoreAsOfDate() {
        return scoreAsOfDate;
    }

    public void setScoreAsOfDate(LocalDate scoreAsOfDate) {
        this.scoreAsOfDate = scoreAsOfDate;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Double getPlayerScore() {
        return playerScore;
    }

    public void setPlayerScore(Double playerScore) {
        this.playerScore = playerScore;
    }

    public Double getOpponentScore() {
        return opponentScore;
    }

    public void setOpponentScore(Double opponentScore) {
        this.opponentScore = opponentScore;
    }

    public String getOpponentSnubs() {
        return opponentSnubs;
    }

    public void setOpponentSnubs(String opponentSnubs) {
        this.opponentSnubs = opponentSnubs;
    }
}
