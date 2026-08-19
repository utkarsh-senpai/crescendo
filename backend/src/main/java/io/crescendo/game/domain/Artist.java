package io.crescendo.game.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A draftable emerging artist. Mirrors the ML side's notion of an artist (external
 * {@code artist_id} keyed to the collected YouTube channel) plus the game-only fields:
 * a display name, a genre tag, and a salary-cap cost.
 *
 * <p>The as-of feature vector used for prediction is NOT stored here — the game sends
 * whatever features it holds to the /predict seam at draft/score time. Salary is a
 * game-economy number derived (at seed time) from the artist's current standing so that
 * bigger channels cost more of the cap.
 */
@Entity
@Table(name = "artist")
public class Artist {

    @Id
    @Column(name = "artist_id")
    private Long artistId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String genre;

    /** The draftable pool this artist belongs to; a game is scoped to one league. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private League league;

    /** Salary-cap cost of drafting this artist (credits). */
    @Column(nullable = false)
    private int salary;

    protected Artist() {
        // JPA
    }

    public Artist(Long artistId, String name, String genre, League league, int salary) {
        this.artistId = artistId;
        this.name = name;
        this.genre = genre;
        this.league = league;
        this.salary = salary;
    }

    public Long getArtistId() {
        return artistId;
    }

    public String getName() {
        return name;
    }

    public String getGenre() {
        return genre;
    }

    public League getLeague() {
        return league;
    }

    public int getSalary() {
        return salary;
    }
}
