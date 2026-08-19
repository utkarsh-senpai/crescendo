package io.crescendo.game.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One artist drafted by the transparent AI opponent (v1.1). Mirrors {@link RosterPick} — same
 * salary + seam score + reasons snapshotted at draft time — but adds the bot's plain-language
 * {@code rationale} ("best organic value: ... per $"), which is the whole point of a <i>transparent</i>
 * opponent: the player can see exactly why the AI made each pick, not just what it picked.
 */
@Entity
@Table(name = "opponent_pick")
public class OpponentPick {

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

    /** Newline-joined reasons from /predict at draft time (display-only). */
    @Column(length = 1024)
    private String draftReasons;

    /** The bot's shown rationale for this pick (the transparent "why"). */
    @Column(length = 512)
    private String rationale;

    protected OpponentPick() {
        // JPA
    }

    public OpponentPick(Long gameId, Long artistId, int salaryPaid,
                        Double draftBreakoutScore, String draftReasons, String rationale) {
        this.gameId = gameId;
        this.artistId = artistId;
        this.salaryPaid = salaryPaid;
        this.draftBreakoutScore = draftBreakoutScore;
        this.draftReasons = draftReasons;
        this.rationale = rationale;
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

    public String getRationale() {
        return rationale;
    }
}
