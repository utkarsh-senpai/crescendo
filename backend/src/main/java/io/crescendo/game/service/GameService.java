package io.crescendo.game.service;

import io.crescendo.game.api.GameDtos.BoardArtist;
import io.crescendo.game.api.GameDtos.DraftBoardResponse;
import io.crescendo.game.api.GameDtos.GameView;
import io.crescendo.game.api.GameDtos.LeaderboardEntry;
import io.crescendo.game.api.GameDtos.RosterEntry;
import io.crescendo.game.domain.Artist;
import io.crescendo.game.domain.ArtistFeatureSnapshot;
import io.crescendo.game.domain.GameSession;
import io.crescendo.game.domain.RosterPick;
import io.crescendo.game.predict.PredictClient;
import io.crescendo.game.predict.PredictDtos.RankedArtist;
import io.crescendo.game.repo.ArtistFeatureSnapshotRepository;
import io.crescendo.game.repo.ArtistRepository;
import io.crescendo.game.repo.GameSessionRepository;
import io.crescendo.game.repo.RosterPickRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The v1.0 game: create a single-player game, present a draft board scored by the /predict seam,
 * draft a salary-cap roster, score it on realised relative growth, and rank games on a leaderboard.
 *
 * <p>Salary-cap rules and roster size come from configuration ({@link GameRules}). The draft board
 * is ordered by the seam's breakout score (transparent: the model's reasons ride along), with a
 * salary-implied fallback when the seam is unavailable so the game is always playable.
 */
@Service
public class GameService {

    private final GameSessionRepository games;
    private final RosterPickRepository picks;
    private final ArtistRepository artists;
    private final ArtistFeatureSnapshotRepository snapshots;
    private final PredictClient predictClient;
    private final GameRules rules;

    public GameService(GameSessionRepository games, RosterPickRepository picks,
                       ArtistRepository artists, ArtistFeatureSnapshotRepository snapshots,
                       PredictClient predictClient, GameRules rules) {
        this.games = games;
        this.picks = picks;
        this.artists = artists;
        this.snapshots = snapshots;
        this.predictClient = predictClient;
        this.rules = rules;
    }

    @Transactional
    public GameView createGame(String playerName) {
        GameSession game = new GameSession(
                playerName, rules.getSalaryCap(), rules.getRosterSize(), rules.getDraftAsOfDate());
        games.save(game);
        return toView(game, List.of());
    }

    /**
     * The draft board: every artist, scored + reason-annotated by the seam as of the game's draft
     * date. Sorted by breakout score descending (seam rank), or by salary descending when the seam
     * is unavailable.
     */
    @Transactional(readOnly = true)
    public DraftBoardResponse draftBoard(long gameId) {
        GameSession game = requireGame(gameId);
        List<Artist> all = artists.findAll();

        List<ArtistFeatureSnapshot> asOf = new ArrayList<>();
        for (Artist a : all) {
            snapshotAsOf(a.getArtistId(), game.getDraftAsOfDate()).ifPresent(asOf::add);
        }
        Map<Long, RankedArtist> ranked = predictClient.rank(game.getDraftAsOfDate(), asOf);

        List<BoardArtist> board = new ArrayList<>();
        for (Artist a : all) {
            RankedArtist r = ranked.get(a.getArtistId());
            board.add(new BoardArtist(
                    a.getArtistId(), a.getName(), a.getGenre(), a.getSalary(),
                    r == null ? null : r.breakoutScore(),
                    r == null ? null : r.rank(),
                    r == null ? List.of() : r.reasons()));
        }
        board.sort(boardOrder());
        return new DraftBoardResponse(
                game.getId(), game.getSalaryCap(), game.getRosterSize(),
                game.getDraftAsOfDate(), board);
    }

    /** Order the board by seam score desc (nulls last), tie-break by lower salary then artist id. */
    private static Comparator<BoardArtist> boardOrder() {
        return Comparator
                .comparing((BoardArtist b) -> b.breakoutScore() == null ? Double.NEGATIVE_INFINITY
                        : b.breakoutScore(), Comparator.reverseOrder())
                .thenComparingInt(BoardArtist::salary)
                .thenComparingLong(BoardArtist::artistId);
    }

    @Transactional
    public GameView draft(long gameId, List<Long> artistIds) {
        GameSession game = requireGame(gameId);
        if (game.getStatus() != GameSession.Status.DRAFTING) {
            throw GameException.badRequest("game " + gameId + " is not in DRAFTING status");
        }
        if (!picks.findByGameId(gameId).isEmpty()) {
            throw GameException.badRequest("game " + gameId + " already has a drafted roster");
        }
        List<Long> distinct = artistIds.stream().distinct().toList();
        if (distinct.size() != artistIds.size()) {
            throw GameException.badRequest("roster contains duplicate artists");
        }
        if (distinct.size() != game.getRosterSize()) {
            throw GameException.badRequest(
                    "roster must contain exactly " + game.getRosterSize() + " artists");
        }

        List<Artist> chosen = new ArrayList<>();
        for (Long id : distinct) {
            chosen.add(artists.findById(id).orElseThrow(
                    () -> GameException.badRequest("unknown artist " + id)));
        }
        int spent = chosen.stream().mapToInt(Artist::getSalary).sum();
        if (spent > game.getSalaryCap()) {
            throw GameException.badRequest(
                    "roster salary " + spent + " exceeds cap " + game.getSalaryCap());
        }

        // Snapshot the seam's draft-time score + reasons so the roster records what the model saw.
        List<ArtistFeatureSnapshot> asOf = new ArrayList<>();
        for (Artist a : chosen) {
            snapshotAsOf(a.getArtistId(), game.getDraftAsOfDate()).ifPresent(asOf::add);
        }
        Map<Long, RankedArtist> ranked = predictClient.rank(game.getDraftAsOfDate(), asOf);
        for (Artist a : chosen) {
            RankedArtist r = ranked.get(a.getArtistId());
            picks.save(new RosterPick(
                    game.getId(), a.getArtistId(), a.getSalary(),
                    r == null ? null : r.breakoutScore(),
                    r == null ? null : String.join("\n", r.reasons())));
        }
        return getGame(gameId);
    }

    /**
     * Score a drafted game on realised relative growth. Each pick's realised growth is its
     * {@code growth_30d} from the snapshot as of the score date; the player's score is the mean
     * across the roster (relative-growth framing — roster size doesn't inflate the score).
     */
    @Transactional
    public GameView score(long gameId, LocalDate scoreAsOfDate) {
        GameSession game = requireGame(gameId);
        List<RosterPick> roster = picks.findByGameId(gameId);
        if (roster.isEmpty()) {
            throw GameException.badRequest("game " + gameId + " has no roster to score");
        }
        LocalDate asOf = scoreAsOfDate != null ? scoreAsOfDate : rules.getScoreAsOfDate();
        if (!asOf.isAfter(game.getDraftAsOfDate())) {
            throw GameException.badRequest("score date must be after the draft date");
        }

        double sum = 0.0;
        int counted = 0;
        for (RosterPick pick : roster) {
            Double growth = snapshotAsOf(pick.getArtistId(), asOf)
                    .map(ArtistFeatureSnapshot::getGrowth30d)
                    .orElse(null);
            if (growth != null) {
                sum += growth;
                counted++;
            }
        }
        double playerScore = counted == 0 ? 0.0 : sum / counted;
        game.setScoreAsOfDate(asOf);
        game.setPlayerScore(playerScore);
        game.setStatus(GameSession.Status.SCORED);
        games.save(game);
        return getGame(gameId);
    }

    @Transactional(readOnly = true)
    public GameView getGame(long gameId) {
        GameSession game = requireGame(gameId);
        return toView(game, picks.findByGameId(gameId));
    }

    @Transactional(readOnly = true)
    public List<LeaderboardEntry> leaderboard() {
        return games.findByStatusOrderByPlayerScoreDesc(GameSession.Status.SCORED).stream()
                .map(g -> new LeaderboardEntry(
                        g.getId(), g.getPlayerName(),
                        g.getPlayerScore() == null ? 0.0 : g.getPlayerScore(),
                        picks.findByGameId(g.getId()).size()))
                .toList();
    }

    private GameView toView(GameSession game, List<RosterPick> roster) {
        List<RosterEntry> entries = new ArrayList<>();
        int spent = 0;
        for (RosterPick pick : roster) {
            spent += pick.getSalaryPaid();
            String name = artists.findById(pick.getArtistId())
                    .map(Artist::getName).orElse("artist " + pick.getArtistId());
            Double realised = game.getScoreAsOfDate() == null ? null
                    : snapshotAsOf(pick.getArtistId(), game.getScoreAsOfDate())
                            .map(ArtistFeatureSnapshot::getGrowth30d).orElse(null);
            entries.add(new RosterEntry(
                    pick.getArtistId(), name, pick.getSalaryPaid(),
                    pick.getDraftBreakoutScore(),
                    pick.getDraftReasons() == null || pick.getDraftReasons().isBlank()
                            ? List.of() : List.of(pick.getDraftReasons().split("\n")),
                    realised));
        }
        return new GameView(
                game.getId(), game.getPlayerName(), game.getSalaryCap(), spent,
                game.getRosterSize(), game.getDraftAsOfDate(), game.getScoreAsOfDate(),
                game.getStatus(), game.getPlayerScore(), entries);
    }

    private Optional<ArtistFeatureSnapshot> snapshotAsOf(Long artistId, LocalDate asOf) {
        return snapshots.findFirstByArtistIdAndAsOfDateLessThanEqualOrderByAsOfDateDesc(artistId, asOf);
    }

    private GameSession requireGame(long gameId) {
        return games.findById(gameId)
                .orElseThrow(() -> GameException.notFound("game " + gameId + " not found"));
    }
}
