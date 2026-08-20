package io.crescendo.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import io.crescendo.game.api.GameDtos.DraftBoardResponse;
import io.crescendo.game.api.GameDtos.GameView;
import io.crescendo.game.domain.GameSession;
import io.crescendo.game.predict.PredictClient;
import io.crescendo.game.predict.PredictDtos.RankedArtist;
import io.crescendo.game.repo.ArtistFeatureSnapshotRepository;
import io.crescendo.game.repo.ArtistRepository;
import io.crescendo.game.repo.GameSessionRepository;
import io.crescendo.game.repo.RosterPickRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exercises the game rules against the real repositories (H2) + the seeded demo world, with the
 * /predict seam mocked so the test is deterministic and offline. Verifies salary-cap enforcement,
 * roster-size rules, seam-scored board ordering, and relative-growth scoring.
 */
@SpringBootTest
@Transactional
class GameServiceTest {

    @Autowired
    GameService gameService;

    @Autowired
    ArtistRepository artists;

    @Autowired
    ArtistFeatureSnapshotRepository snapshots;

    @Autowired
    GameSessionRepository games;

    @Autowired
    RosterPickRepository picks;

    @MockBean
    PredictClient predictClient;

    @BeforeEach
    void stubSeam() {
        // Deterministic scores: organic artists high, inorganic (109/110) low with a discount reason.
        when(predictClient.rank(any(LocalDate.class), anyList())).thenAnswer(inv -> {
            Map<Long, RankedArtist> out = new java.util.LinkedHashMap<>();
            List<?> snaps = inv.getArgument(1);
            int rank = 1;
            for (Object o : snaps) {
                var s = (io.crescendo.game.domain.ArtistFeatureSnapshot) o;
                long id = s.getArtistId();
                boolean inorganic = s.getInorganicScore() != null && s.getInorganicScore() > 0.5;
                double score = inorganic ? 0.10 : Math.min(0.95, 0.40 + (s.getGrowth30d() == null ? 0 : s.getGrowth30d()));
                List<String> reasons = inorganic
                        ? List.of("discounted: growth looks inorganic")
                        : List.of("steady organic momentum");
                out.put(id, new RankedArtist(id, score, rank++, reasons, null, null));
            }
            return out;
        });
    }

    @Test
    void draftBoardIsOrderedBySeamScoreDescending() {
        GameView game = gameService.createGame("Ada"); // default league POP (ids 501–515)
        DraftBoardResponse board = gameService.draftBoard(game.gameId());

        assertThat(board.artists()).hasSize(20); // v1.5: POP expanded to 20
        // Ordered by seam score descending (highest first).
        assertThat(board.artists().get(0).breakoutScore())
                .isGreaterThanOrEqualTo(board.artists().get(board.artists().size() - 1).breakoutScore());
    }

    @Test
    void draftEnforcesSalaryCap() {
        GameView game = gameService.createGame("Grace");
        // Five most expensive POP artists — should blow the cap of 100.
        List<Long> pricey = List.of(501L, 502L, 503L, 504L, 505L); // 24+23+21+20+19 = 107 > 100
        assertThatThrownBy(() -> gameService.draft(game.gameId(), pricey))
                .isInstanceOf(GameException.class)
                .hasMessageContaining("exceeds cap");
    }

    @Test
    void draftEnforcesRosterSize() {
        GameView game = gameService.createGame("Linus");
        assertThatThrownBy(() -> gameService.draft(game.gameId(), List.of(501L, 502L)))
                .isInstanceOf(GameException.class)
                .hasMessageContaining("exactly 5");
    }

    @Test
    void draftRejectsDuplicates() {
        GameView game = gameService.createGame("Dup");
        assertThatThrownBy(() -> gameService.draft(game.gameId(), List.of(501L, 501L, 502L, 503L, 504L)))
                .isInstanceOf(GameException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void fullDraftThenScoreProducesRelativeGrowthScore() {
        GameView game = gameService.createGame("Katherine");
        // A legal roster within cap: 511+512+513+514+515 = 14+13+12+11+10 = 60 <= 100.
        List<Long> roster = List.of(511L, 512L, 513L, 514L, 515L);
        GameView drafted = gameService.draft(game.gameId(), roster);
        assertThat(drafted.salarySpent()).isEqualTo(80); // v1.5 salaries: 18+17+16+15+14=80
        assertThat(drafted.roster()).hasSize(5);
        assertThat(drafted.roster().get(0).draftReasons()).isNotEmpty();

        GameView scored = gameService.score(game.gameId(), LocalDate.parse("2026-08-01"));
        assertThat(scored.status()).isEqualTo(GameSession.Status.SCORED);
        // Mean realised growth_30d for 511,512,513,514,515 = (.22+.20+.18+.16+.14)/5 = .18 (v1.5 seeds)
        assertThat(scored.playerScore()).isCloseTo(0.18, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void scoreRejectsUndraftedGame() {
        GameView game = gameService.createGame("Empty");
        assertThatThrownBy(() -> gameService.score(game.gameId(), LocalDate.parse("2026-08-01")))
                .isInstanceOf(GameException.class)
                .hasMessageContaining("no roster");
    }

    @Test
    void draftAlsoDraftsTheTransparentAiOpponent() {
        GameView game = gameService.createGame("Ada");
        GameView drafted = gameService.draft(game.gameId(), List.of(508L, 509L, 510L, 511L, 512L));

        assertThat(drafted.opponent()).isNotNull();
        assertThat(drafted.opponent().name()).isEqualTo("Crescendo AI");
        assertThat(drafted.opponent().roster()).hasSize(5);
        assertThat(drafted.opponent().salarySpent()).isLessThanOrEqualTo(100);
        // Every AI pick shows its rationale (transparency).
        assertThat(drafted.opponent().roster()).allSatisfy(p ->
                assertThat(p.rationale()).isNotBlank());
    }

    @Test
    void scoreSetsOpponentScoreAndOutcome() {
        GameView game = gameService.createGame("Ada");
        gameService.draft(game.gameId(), List.of(506L, 508L, 510L, 511L, 512L)); // legal roster within cap
        GameView scored = gameService.score(game.gameId(), LocalDate.parse("2026-08-01"));

        assertThat(scored.opponent().score()).isNotNull();
        assertThat(scored.outcome()).isIn("PLAYER_WINS", "AI_WINS", "TIE");
    }

    @Test
    void leaderboardRanksScoredGamesByScoreDescending() {
        GameView g1 = gameService.createGame("HighScorer");
        // v1.5 salaries: 506(23)+508(21)+510(19)+511(18)+512(17)=98 <= 100
        gameService.draft(g1.gameId(), List.of(506L, 508L, 510L, 511L, 512L)); // stronger realised
        gameService.score(g1.gameId(), LocalDate.parse("2026-08-01"));

        GameView g2 = gameService.createGame("LowScorer");
        gameService.draft(g2.gameId(), List.of(509L, 510L, 511L, 512L, 508L)); // weaker realised
        gameService.score(g2.gameId(), LocalDate.parse("2026-08-01"));

        var board = gameService.leaderboard();
        assertThat(board).hasSizeGreaterThanOrEqualTo(2);
        assertThat(board.get(0).playerScore()).isGreaterThanOrEqualTo(board.get(1).playerScore());
    }

    @Test
    void boardIsScopedToTheGamesLeague() {
        GameView game = gameService.createGame("Rhea", io.crescendo.game.domain.League.BOLLYWOOD);
        DraftBoardResponse board = gameService.draftBoard(game.gameId());

        assertThat(board.league()).isEqualTo(io.crescendo.game.domain.League.BOLLYWOOD);
        assertThat(board.artists()).hasSize(17); // v1.5: Bollywood expanded to 17
        // Every artist on the board is a Bollywood id (701–717); no cross-league leakage.
        assertThat(board.artists()).allSatisfy(a ->
                assertThat(a.artistId()).isBetween(701L, 717L));
    }

    @Test
    void cannotDraftAnArtistFromAnotherLeague() {
        GameView game = gameService.createGame("Rhea", io.crescendo.game.domain.League.BOLLYWOOD);
        // 501 is a POP artist; drafting it into a Bollywood game must fail.
        assertThatThrownBy(() ->
                gameService.draft(game.gameId(), List.of(701L, 702L, 703L, 704L, 501L)))
                .isInstanceOf(GameException.class)
                .hasMessageContaining("not in the BOLLYWOOD league");
    }

    @Test
    void leaguesListsAllThreeGenres() {
        var leagues = gameService.leagues();
        assertThat(leagues).hasSize(3);
        assertThat(leagues).extracting(io.crescendo.game.api.GameDtos.LeagueOption::id)
                .containsExactly("POP", "EDM", "BOLLYWOOD");
    }
}
