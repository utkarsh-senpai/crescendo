package io.crescendo.game.service;

import io.crescendo.game.api.GameDtos.BoardArtist;
import io.crescendo.game.api.GameDtos.DraftBoardResponse;
import io.crescendo.game.api.GameDtos.GameView;
import io.crescendo.game.api.GameDtos.LeaderboardEntry;
import io.crescendo.game.api.GameDtos.LeagueOption;
import io.crescendo.game.api.GameDtos.OpponentEntry;
import io.crescendo.game.api.GameDtos.OpponentSnub;
import io.crescendo.game.api.GameDtos.OpponentView;
import io.crescendo.game.api.GameDtos.RosterEntry;
import io.crescendo.game.domain.Artist;
import io.crescendo.game.domain.ArtistFeatureSnapshot;
import io.crescendo.game.domain.GameSession;
import io.crescendo.game.domain.League;
import io.crescendo.game.domain.OpponentPick;
import io.crescendo.game.domain.RosterPick;
import io.crescendo.game.predict.PredictClient;
import io.crescendo.game.predict.PredictDtos.RankedArtist;
import io.crescendo.game.repo.ArtistFeatureSnapshotRepository;
import io.crescendo.game.repo.ArtistRepository;
import io.crescendo.game.repo.GameSessionRepository;
import io.crescendo.game.repo.OpponentPickRepository;
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
 * The Crescendo game: create a game, present a draft board scored by the /predict seam, draft a
 * salary-cap roster, and score it on realised relative growth. In v1.1 a <b>transparent AI
 * opponent</b> drafts its own salary-cap roster off the same board (greedy best organic
 * value-per-dollar), shows its reasoning and the artists it passed on, and is scored head-to-head
 * against the player.
 *
 * <p>Salary-cap rules and roster size come from configuration ({@link GameRules}). The draft board
 * is ordered by the seam's breakout score (transparent: the model's reasons ride along), with a
 * salary-implied fallback when the seam is unavailable so the game is always playable.
 */
@Service
public class GameService {

    private static final String SNUB_SEP = "|";

    private final GameSessionRepository games;
    private final RosterPickRepository picks;
    private final OpponentPickRepository opponentPicks;
    private final ArtistRepository artists;
    private final ArtistFeatureSnapshotRepository snapshots;
    private final PredictClient predictClient;
    private final GameRules rules;

    public GameService(GameSessionRepository games, RosterPickRepository picks,
                       OpponentPickRepository opponentPicks, ArtistRepository artists,
                       ArtistFeatureSnapshotRepository snapshots,
                       PredictClient predictClient, GameRules rules) {
        this.games = games;
        this.picks = picks;
        this.opponentPicks = opponentPicks;
        this.artists = artists;
        this.snapshots = snapshots;
        this.predictClient = predictClient;
        this.rules = rules;
    }

    @Transactional
    public GameView createGame(String playerName) {
        return createGame(playerName, League.POP);
    }

    @Transactional
    public GameView createGame(String playerName, League league) {
        League chosen = league != null ? league : League.POP;
        GameSession game = new GameSession(
                playerName, chosen, rules.getSalaryCap(), rules.getRosterSize(),
                rules.getDraftAsOfDate());
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
        List<Artist> all = artists.findByLeague(game.getLeague());
        Map<Long, RankedArtist> ranked = rankAll(all, game.getDraftAsOfDate());

        List<BoardArtist> board = new ArrayList<>();
        for (Artist a : all) {
            RankedArtist r = ranked.get(a.getArtistId());
            board.add(new BoardArtist(
                    a.getArtistId(), a.getName(), a.getGenre(), a.getSalary(),
                    r == null ? null : r.breakoutScore(),
                    r == null ? null : r.rank(),
                    r == null ? List.of() : r.reasons(),
                    r == null ? null : r.discoveryEdge(),
                    r == null ? null : r.confidenceTier()));
        }
        board.sort(boardOrder());
        return new DraftBoardResponse(
                game.getId(), game.getLeague(), game.getSalaryCap(), game.getRosterSize(),
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

    /**
     * Draft the player's roster and, in the same move, have the transparent AI opponent draft its
     * own roster off the same board (best organic value-per-dollar under the cap). Both rosters and
     * the bot's shown reasoning + snubs are persisted so the reveal is stable.
     */
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
            Artist a = artists.findById(id).orElseThrow(
                    () -> GameException.badRequest("unknown artist " + id));
            if (a.getLeague() != game.getLeague()) {
                throw GameException.badRequest(
                        "artist " + id + " is not in the " + game.getLeague() + " league");
            }
            chosen.add(a);
        }
        int spent = chosen.stream().mapToInt(Artist::getSalary).sum();
        if (spent > game.getSalaryCap()) {
            throw GameException.badRequest(
                    "roster salary " + spent + " exceeds cap " + game.getSalaryCap());
        }

        // One seam call scores every artist in this league as of the draft date: used for both the
        // player's recorded pick scores and the bot's value-based draft off the same board.
        List<Artist> all = artists.findByLeague(game.getLeague());
        Map<Long, RankedArtist> ranked = rankAll(all, game.getDraftAsOfDate());

        for (Artist a : chosen) {
            RankedArtist r = ranked.get(a.getArtistId());
            picks.save(new RosterPick(
                    game.getId(), a.getArtistId(), a.getSalary(),
                    r == null ? null : r.breakoutScore(),
                    r == null ? null : String.join("\n", r.reasons())));
        }

        draftOpponent(game, all, ranked);
        return getGame(gameId);
    }

    /**
     * The transparent AI opponent's draft. Builds a value candidate per artist from the seam scores,
     * runs the greedy value drafter, and persists the bot's roster + its shown "why-not" snubs.
     */
    private void draftOpponent(GameSession game, List<Artist> all, Map<Long, RankedArtist> ranked) {
        List<OpponentDrafter.Candidate> candidates = new ArrayList<>();
        for (Artist a : all) {
            RankedArtist r = ranked.get(a.getArtistId());
            boolean inorganic = r != null && r.reasons().stream()
                    .anyMatch(s -> s.toLowerCase().contains("inorganic"));
            candidates.add(new OpponentDrafter.Candidate(
                    a.getArtistId(), a.getName(), a.getSalary(),
                    r == null ? null : r.breakoutScore(),
                    r == null ? List.of() : r.reasons(),
                    inorganic));
        }

        OpponentDrafter.Result result;
        try {
            result = OpponentDrafter.draft(candidates, game.getSalaryCap(), game.getRosterSize());
        } catch (IllegalArgumentException e) {
            // No legal roster fits (shouldn't happen with the seeded world); skip the opponent
            // rather than fail the player's draft.
            return;
        }

        for (OpponentDrafter.Selection sel : result.roster()) {
            opponentPicks.save(new OpponentPick(
                    game.getId(), sel.artistId(), sel.salary(), sel.score(),
                    sel.reasons() == null ? null : String.join("\n", sel.reasons()),
                    sel.rationale()));
        }
        game.setOpponentSnubs(encodeSnubs(result.snubs()));
        games.save(game);
    }

    /**
     * Score a drafted game head-to-head. Each roster's realised growth is the mean of its picks'
     * {@code growth_30d} as of the score date (relative-growth framing — roster size doesn't inflate
     * the score). Both the player and the transparent AI opponent are scored, and an outcome is set.
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

        double playerScore = meanRealisedGrowth(
                roster.stream().map(RosterPick::getArtistId).toList(), asOf);
        double opponentScore = meanRealisedGrowth(
                opponentPicks.findByGameId(gameId).stream()
                        .map(OpponentPick::getArtistId).toList(), asOf);

        game.setScoreAsOfDate(asOf);
        game.setPlayerScore(playerScore);
        game.setOpponentScore(opponentScore);
        game.setStatus(GameSession.Status.SCORED);
        games.save(game);
        return getGame(gameId);
    }

    /**
     * v1.4 no-op seam for real-momentum scoring. When {@code crescendo.game.use-real-momentum} is
     * enabled AND the daily cron has accumulated enough real snapshots, this is where scoring will
     * switch to realised momentum from collected data. Until then it returns empty and the caller
     * falls through to the seeded synthetic snapshots — so flipping the flag before data matures is
     * safe (it simply no-ops to today's behaviour).
     */
    private Optional<Double> realMomentum(Long artistId, LocalDate asOf) {
        if (!rules.isUseRealMomentum()) {
            return Optional.empty();
        }
        // TODO(v1.4+): compute realised momentum from cron-collected snapshots once history exists.
        // No real history yet → no-op to synthetic. Kept as an explicit seam, not dead code.
        return Optional.empty();
    }

    /** Mean realised {@code growth_30d} across a set of artists as of the score date. */
    private double meanRealisedGrowth(List<Long> artistIds, LocalDate asOf) {
        double sum = 0.0;
        int counted = 0;
        for (Long id : artistIds) {
            // Prefer real cron-collected momentum when enabled + available; else synthetic snapshot.
            Double growth = realMomentum(id, asOf).orElseGet(() -> snapshotAsOf(id, asOf)
                    .map(ArtistFeatureSnapshot::getGrowth30d)
                    .orElse(null));
            if (growth != null) {
                sum += growth;
                counted++;
            }
        }
        return counted == 0 ? 0.0 : sum / counted;
    }

    @Transactional(readOnly = true)
    public GameView getGame(long gameId) {
        GameSession game = requireGame(gameId);
        return toView(game, picks.findByGameId(gameId));
    }

    /** The selectable leagues for the home-screen picker, in enum order. */
    public List<LeagueOption> leagues() {
        return java.util.Arrays.stream(League.values())
                .map(l -> new LeagueOption(
                        l.name(), l.getLabel(), l.getBand(), l.getTagline()))
                .toList();
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
            String name = artistName(pick.getArtistId());
            Double realised = realisedGrowth(game, pick.getArtistId());
            entries.add(new RosterEntry(
                    pick.getArtistId(), name, pick.getSalaryPaid(),
                    pick.getDraftBreakoutScore(),
                    splitReasons(pick.getDraftReasons()),
                    realised));
        }
        OpponentView opponent = buildOpponentView(game);
        String outcome = outcome(game);
        return new GameView(
                game.getId(), game.getPlayerName(), game.getLeague(), game.getSalaryCap(), spent,
                game.getRosterSize(), game.getDraftAsOfDate(), game.getScoreAsOfDate(),
                game.getStatus(), game.getPlayerScore(), entries, opponent, outcome);
    }

    /** Build the opponent's side of the view from its persisted picks + snubs (null if not drafted). */
    private OpponentView buildOpponentView(GameSession game) {
        List<OpponentPick> botRoster = opponentPicks.findByGameId(game.getId());
        if (botRoster.isEmpty()) {
            return null;
        }
        List<OpponentEntry> entries = new ArrayList<>();
        int spent = 0;
        for (OpponentPick pick : botRoster) {
            spent += pick.getSalaryPaid();
            entries.add(new OpponentEntry(
                    pick.getArtistId(), artistName(pick.getArtistId()), pick.getSalaryPaid(),
                    pick.getDraftBreakoutScore(), splitReasons(pick.getDraftReasons()),
                    pick.getRationale(), realisedGrowth(game, pick.getArtistId())));
        }
        return new OpponentView(
                "Crescendo AI", spent, game.getOpponentScore(), entries,
                decodeSnubs(game.getOpponentSnubs()));
    }

    /** Head-to-head outcome once scored; null while still drafting. */
    private static String outcome(GameSession game) {
        if (game.getStatus() != GameSession.Status.SCORED
                || game.getPlayerScore() == null || game.getOpponentScore() == null) {
            return null;
        }
        double diff = game.getPlayerScore() - game.getOpponentScore();
        if (Math.abs(diff) < 1e-9) {
            return "TIE";
        }
        return diff > 0 ? "PLAYER_WINS" : "AI_WINS";
    }

    private Double realisedGrowth(GameSession game, Long artistId) {
        if (game.getScoreAsOfDate() == null) {
            return null;
        }
        return snapshotAsOf(artistId, game.getScoreAsOfDate())
                .map(ArtistFeatureSnapshot::getGrowth30d).orElse(null);
    }

    private String artistName(Long artistId) {
        return artists.findById(artistId).map(Artist::getName).orElse("artist " + artistId);
    }

    private static List<String> splitReasons(String joined) {
        return joined == null || joined.isBlank() ? List.of() : List.of(joined.split("\n"));
    }

    /** Encode snubs as {@code artistId|name|reason} lines (small, display-only). */
    private static String encodeSnubs(List<OpponentDrafter.Snub> snubs) {
        StringBuilder sb = new StringBuilder();
        for (OpponentDrafter.Snub s : snubs) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(s.artistId()).append(SNUB_SEP)
                    .append(s.name()).append(SNUB_SEP)
                    .append(s.reason());
        }
        return sb.toString();
    }

    private static List<OpponentSnub> decodeSnubs(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        List<OpponentSnub> out = new ArrayList<>();
        for (String line : encoded.split("\n")) {
            String[] parts = line.split("\\" + SNUB_SEP, 3);
            if (parts.length == 3) {
                out.add(new OpponentSnub(Long.parseLong(parts[0]), parts[1], parts[2]));
            }
        }
        return out;
    }

    /** Score every artist via the seam as of {@code asOf}; empty map when the seam is unavailable. */
    private Map<Long, RankedArtist> rankAll(List<Artist> all, LocalDate asOf) {
        List<ArtistFeatureSnapshot> asOfSnaps = new ArrayList<>();
        for (Artist a : all) {
            snapshotAsOf(a.getArtistId(), asOf).ifPresent(asOfSnaps::add);
        }
        return predictClient.rank(asOf, asOfSnaps);
    }

    private Optional<ArtistFeatureSnapshot> snapshotAsOf(Long artistId, LocalDate asOf) {
        return snapshots.findFirstByArtistIdAndAsOfDateLessThanEqualOrderByAsOfDateDesc(artistId, asOf);
    }

    private GameSession requireGame(long gameId) {
        return games.findById(gameId)
                .orElseThrow(() -> GameException.notFound("game " + gameId + " not found"));
    }
}
