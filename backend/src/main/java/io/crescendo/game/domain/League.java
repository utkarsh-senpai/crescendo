package io.crescendo.game.domain;

/**
 * A draftable artist pool. One league is chosen per game and the whole draft (player board + AI
 * opponent) is scoped to it — no cross-league rosters (v1.3).
 *
 * <p>All four leagues are scored by the SAME /predict model — it reads channel-level momentum, so
 * for the bigger pools it answers "who's growing fastest right now" rather than literal "breakout".
 * The emerging/rising split is by subscriber band; top/bollywood are curated star pools. Data is
 * synthetic demo data until the real collected signal matures.
 */
public enum League {
    POP("Pop", "Global pop superstars", "Who's still accelerating at the top?"),
    EDM("EDM", "Electronic / dance", "Which drop is pulling ahead?"),
    BOLLYWOOD("Bollywood", "Indian film music", "The playback giants — who's surging now?");

    private final String label;
    private final String band;
    private final String tagline;

    League(String label, String band, String tagline) {
        this.label = label;
        this.band = band;
        this.tagline = tagline;
    }

    public String getLabel() {
        return label;
    }

    public String getBand() {
        return band;
    }

    public String getTagline() {
        return tagline;
    }
}
