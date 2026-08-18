package io.crescendo.game.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One artist drafted onto a game's roster. Records the salary paid (snapshotted from the artist at
 * draft time so later salary re-pricing can't retroactively change a settled roster's cap math)
 * and the breakout score + reasons the /predict seam returned at draft time — this is what powers
 * the "why did the model like this pick?" UX and, in v1.1, the transparent AI's shown reasoning.
 */
@Entity
@Table(name = "roster_pick")
public class RosterPick {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "game_id", nullable = false)
    private Long gameId;

    @Column(name = "artist_id", nullable = false)
    private Long artistId;

    @Column(nullable = false)
    private int salaryPaid;

    /** Breakout score from /predict at draft time (0..1). */
    private Double draftBreakoutScore;

    /** Newline-joined reasons from /predict at draft time (small, display-only). */
    @Column(length = 1024)
    private String draftReasons;

    protected RosterPick() {
        // JPA
    }

    public RosterPick(Long gameId, Long artistId, int salaryPaid,
                      Double draftBreakoutScore, String draftReasons) {
        this.gameId = gameId;
        this.artistId = artistId;
        this.salaryPaid = salaryPaid;
        this.draftBreakoutScore = draftBreakoutScore;
        this.draftReasons = draftReasons;
    }

    public Long getId() {
        return id;
    }

    public Long getGameId() {
        return gameId;
    }

    public Long getArtistId() {
        return artistId;
    }

    public int getSalaryPaid() {
        return salaryPaid;
    }

    public Double getDraftBreakoutScore() {
        return draftBreakoutScore;
    }

    public String getDraftReasons() {
        return draftReasons;
    }
}
