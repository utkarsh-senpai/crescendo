package io.crescendo.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.crescendo.game.service.OpponentDrafter.Candidate;
import io.crescendo.game.service.OpponentDrafter.Result;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the transparent AI opponent's pure draft strategy: greedy best organic
 * value-per-dollar, always fielding a legal roster, and surfacing its "why-not" snubs.
 */
class OpponentDrafterTest {

    private static Candidate c(long id, int salary, Double score, boolean inorganic) {
        return new Candidate(id, "artist " + id, salary, score,
                inorganic ? List.of("discounted: growth looks inorganic") : List.of("organic"),
                inorganic);
    }

    @Test
    void picksBestValuePerDollarUnderCap() {
        // Two cheap high-value artists dominate value/$; expensive one is worse value.
        List<Candidate> pool = List.of(
                c(1, 10, 0.90, false),   // value .090
                c(2, 10, 0.80, false),   // value .080
                c(3, 50, 0.60, false),   // value .012
                c(4, 5, 0.30, false),    // value .060
                c(5, 5, 0.20, false));   // value .040
        Result r = OpponentDrafter.draft(pool, 30, 3);

        assertThat(r.roster()).hasSize(3);
        assertThat(r.roster()).extracting(OpponentDrafter.Selection::artistId)
                .containsExactly(1L, 2L, 4L); // by value desc: .090, .080, .060
        int spent = r.roster().stream().mapToInt(OpponentDrafter.Selection::salary).sum();
        assertThat(spent).isLessThanOrEqualTo(30);
    }

    @Test
    void passesOnInorganicAndSnubsItWithAReason() {
        // The seam discounts inorganic artists to a LOW score, so their value/$ is low and the
        // value-greedy drafter naturally passes them. The transparent snub names why.
        List<Candidate> pool = List.of(
                c(1, 20, 0.90, false),
                c(2, 20, 0.85, false),
                c(3, 20, 0.80, false),
                c(4, 20, 0.10, true));   // inorganic, seam-discounted → lowest value, gets passed
        Result r = OpponentDrafter.draft(pool, 100, 3);

        assertThat(r.roster()).extracting(OpponentDrafter.Selection::artistId)
                .doesNotContain(4L);
        assertThat(r.snubs()).anySatisfy(s -> {
            assertThat(s.artistId()).isEqualTo(4L);
            assertThat(s.reason()).contains("inorganic");
        });
    }

    @Test
    void alwaysFieldsALegalRosterEvenWhenGreedyWouldOverspend() {
        // Highest value is also the priciest; naive greedy would take it and then be unable to fill
        // 3 slots under a tight cap. The feasibility guard must still return a legal roster.
        List<Candidate> pool = List.of(
                c(1, 40, 0.99, false),   // best value AND priciest
                c(2, 20, 0.50, false),
                c(3, 20, 0.50, false),
                c(4, 20, 0.50, false));
        Result r = OpponentDrafter.draft(pool, 60, 3);

        assertThat(r.roster()).hasSize(3);
        int spent = r.roster().stream().mapToInt(OpponentDrafter.Selection::salary).sum();
        assertThat(spent).isLessThanOrEqualTo(60);
        // It must NOT have taken artist 1 (40) — that leaves only 20 for two more 20-cost artists.
        assertThat(r.roster()).extracting(OpponentDrafter.Selection::artistId)
                .containsExactlyInAnyOrder(2L, 3L, 4L);
    }

    @Test
    void everyPickHasAShownRationale() {
        List<Candidate> pool = List.of(
                c(1, 10, 0.90, false), c(2, 10, 0.80, false), c(3, 10, 0.70, false));
        Result r = OpponentDrafter.draft(pool, 30, 3);
        assertThat(r.roster()).allSatisfy(sel ->
                assertThat(sel.rationale()).isNotBlank());
    }

    @Test
    void throwsWhenNoLegalRosterFits() {
        List<Candidate> pool = List.of(
                c(1, 50, 0.9, false), c(2, 50, 0.9, false), c(3, 50, 0.9, false));
        assertThatThrownBy(() -> OpponentDrafter.draft(pool, 60, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no legal roster");
    }

    @Test
    void degradesToCheapestWhenSeamUnavailable() {
        // Null scores => value 0 for all; drafter falls back to a legal (cheapest-feasible) roster.
        List<Candidate> pool = List.of(
                c(1, 30, null, false), c(2, 10, null, false),
                c(3, 10, null, false), c(4, 10, null, false));
        Result r = OpponentDrafter.draft(pool, 40, 3);
        assertThat(r.roster()).hasSize(3);
        assertThat(r.roster().stream().mapToInt(OpponentDrafter.Selection::salary).sum())
                .isLessThanOrEqualTo(40);
    }
}
