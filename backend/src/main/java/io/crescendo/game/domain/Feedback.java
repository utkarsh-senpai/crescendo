package io.crescendo.game.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A single piece of player feedback submitted from the app. Deliberately tiny: an optional 1–5
 * rating, a free-text message, and an optional name, plus a server-stamped {@code createdAt}. This
 * is the v1.2 "share it and collect feedback" surface — rows persist in Neon so a spun-down Render
 * instance never loses them.
 */
@Entity
@Table(name = "feedback")
public class Feedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Optional 1–5 star rating; null if the player only left a message. */
    private Integer rating;

    @Column(length = 2000, nullable = false)
    private String message;

    /** Optional display name / handle; may be blank. */
    @Column(length = 120)
    private String name;

    @Column(nullable = false)
    private Instant createdAt;

    protected Feedback() {
        // JPA
    }

    public Feedback(Integer rating, String message, String name, Instant createdAt) {
        this.rating = rating;
        this.message = message;
        this.name = name;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Integer getRating() {
        return rating;
    }

    public String getMessage() {
        return message;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
