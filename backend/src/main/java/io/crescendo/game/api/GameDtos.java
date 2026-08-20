package io.crescendo.game.api;

import io.crescendo.game.domain.GameSession;
import io.crescendo.game.domain.League;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.List;

/** Request/response DTOs for the game REST API. */
public final class GameDtos {

    private GameDtos() {
    }

    /** League is optional on the wire; null defaults to EMERGING (back-compat with v1.2 clients). */
    public record CreateGameRequest(@NotBlank String playerName, League league) {
    }

    /** One selectable league for the home-screen picker. */
    public record LeagueOption(String id, String label, String band, String tagline) {
    }

    /** One artist as shown on the draft board, enriched with the seam's score + reasons. */
    public record BoardArtist(
            long artistId,
            String name,
            String genre,
            int salary,
            Double breakoutScore,
            Integer rank,
            List<String> reasons,
            // v1.5: discovery edge (how far above cohort median) + confidence tier
            Double discoveryEdge,
            String confidenceTier) {
    }

    public record DraftBoardResponse(
            long gameId,
            League league,
            int salaryCap,
            int rosterSize,
            LocalDate draftAsOfDate,
            List<BoardArtist> artists) {
    }

    public record DraftRequest(@NotEmpty List<Long> artistIds) {
    }

    public record RosterEntry(
            long artistId,
            String name,
            int salaryPaid,
            Double draftBreakoutScore,
            List<String> draftReasons,
            Double realisedGrowth30d) {
    }

    /** One of the transparent AI opponent's picks, carrying its shown rationale. */
    public record OpponentEntry(
            long artistId,
            String name,
            int salaryPaid,
            Double draftBreakoutScore,
            List<String> draftReasons,
            String rationale,
            Double realisedGrowth30d) {
    }

    /** An artist the AI opponent passed on, with the reason shown to the player. */
    public record OpponentSnub(long artistId, String name, String reason) {
    }

    /**
     * The transparent AI opponent's side of the game: its roster (with per-pick rationale), the
     * artists it passed on and why, its salary spend, and its realised score once scored.
     */
    public record OpponentView(
            String name,
            int salarySpent,
            Double score,
            List<OpponentEntry> roster,
            List<OpponentSnub> snubs) {
    }

    public record GameView(
            long gameId,
            String playerName,
            League league,
            int salaryCap,
            int salarySpent,
            int rosterSize,
            LocalDate draftAsOfDate,
            LocalDate scoreAsOfDate,
            GameSession.Status status,
            Double playerScore,
            List<RosterEntry> roster,
            OpponentView opponent,
            String outcome) {
    }

    public record ScoreRequest(LocalDate scoreAsOfDate) {
    }

    public record LeaderboardEntry(
            long gameId,
            String playerName,
            double playerScore,
            int rosterSize) {
    }
}
