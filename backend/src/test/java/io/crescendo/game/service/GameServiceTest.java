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
                out.put(id, new RankedArtist(id, score, rank++, reasons));
            }
            return out;
        });
    }

    @Test
    void draftBoardIsOrderedBySeamScoreDescending() {
        GameView game = gameService.createGame("Ada");
        DraftBoardResponse board = gameService.draftBoard(game.gameId());

        assertThat(board.artists()).hasSize(10);
        // First entry must have the max score; inorganic artists sink to the bottom.
        assertThat(board.artists().get(0).breakoutScore())
                .isGreaterThanOrEqualTo(board.artists().get(board.artists().size() - 1).breakoutScore());
        var last = board.artists().get(board.artists().size() - 1);
        assertThat(last.reasons()).contains("discounted: growth looks inorganic");
    }

    @Test
    void draftEnforcesSalaryCap() {
        GameView game = gameService.createGame("Grace");
        // Pick the five most expensive artists — should blow the cap of 100.
        List<Long> pricey = List.of(109L, 101L, 110L, 102L, 103L); // 24+22+21+20+18 = 105 > 100
        assertThatThrownBy(() -> gameService.draft(game.gameId(), pricey))
                .isInstanceOf(GameException.class)
                .hasMessageContaining("exceeds cap");
    }

    @Test
    void draftEnforcesRosterSize() {
        GameView game = gameService.createGame("Linus");
        assertThatThrownBy(() -> gameService.draft(game.gameId(), List.of(101L, 102L)))
                .isInstanceOf(GameException.class)
                .hasMessageContaining("exactly 5");
    }

    @Test
    void draftRejectsDuplicates() {
        GameView game = gameService.createGame("Dup");
        assertThatThrownBy(() -> gameService.draft(game.gameId(), List.of(101L, 101L, 102L, 103L, 104L)))
                .isInstanceOf(GameException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void fullDraftThenScoreProducesRelativeGrowthScore() {
        GameView game = gameService.createGame("Katherine");
        // A legal organic roster within cap: 105+104+106+107+108 = 15+16+13+11+9 = 64 <= 100.
        List<Long> roster = List.of(105L, 104L, 106L, 107L, 108L);
        GameView drafted = gameService.draft(game.gameId(), roster);
        assertThat(drafted.salarySpent()).isEqualTo(64);
        assertThat(drafted.roster()).hasSize(5);
        assertThat(drafted.roster().get(0).draftReasons()).isNotEmpty();

        GameView scored = gameService.score(game.gameId(), LocalDate.parse("2026-08-01"));
        assertThat(scored.status()).isEqualTo(GameSession.Status.SCORED);
        // Mean of realised growth_30d for 105,104,106,107,108 = (.22+.26+.17+.13+.09)/5 = .174
        assertThat(scored.playerScore()).isCloseTo(0.174, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void scoreRejectsUndraftedGame() {
        GameView game = gameService.createGame("Empty");
        assertThatThrownBy(() -> gameService.score(game.gameId(), LocalDate.parse("2026-08-01")))
                .isInstanceOf(GameException.class)
                .hasMessageContaining("no roster");
    }

    @Test
    void leaderboardRanksScoredGamesByScoreDescending() {
        GameView g1 = gameService.createGame("HighScorer");
        gameService.draft(g1.gameId(), List.of(101L, 102L, 103L, 104L, 105L)); // strong organic
        gameService.score(g1.gameId(), LocalDate.parse("2026-08-01"));

        GameView g2 = gameService.createGame("LowScorer");
        gameService.draft(g2.gameId(), List.of(108L, 107L, 106L, 109L, 110L)); // weak/inorganic realised
        gameService.score(g2.gameId(), LocalDate.parse("2026-08-01"));

        var board = gameService.leaderboard();
        assertThat(board).hasSizeGreaterThanOrEqualTo(2);
        assertThat(board.get(0).playerScore()).isGreaterThanOrEqualTo(board.get(1).playerScore());
    }
}
