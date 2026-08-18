package io.crescendo.game.api;

import io.crescendo.game.domain.GameSession;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.List;

/** Request/response DTOs for the game REST API. */
public final class GameDtos {

    private GameDtos() {
    }

    public record CreateGameRequest(@NotBlank String playerName) {
    }

    /** One artist as shown on the draft board, enriched with the seam's score + reasons. */
    public record BoardArtist(
            long artistId,
            String name,
            String genre,
            int salary,
            Double breakoutScore,
            Integer rank,
            List<String> reasons) {
    }

    public record DraftBoardResponse(
            long gameId,
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

    public record GameView(
            long gameId,
            String playerName,
            int salaryCap,
            int salarySpent,
            int rosterSize,
            LocalDate draftAsOfDate,
            LocalDate scoreAsOfDate,
            GameSession.Status status,
            Double playerScore,
            List<RosterEntry> roster) {
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
