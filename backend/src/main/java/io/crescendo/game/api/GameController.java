package io.crescendo.game.api;

import io.crescendo.game.api.GameDtos.CreateGameRequest;
import io.crescendo.game.api.GameDtos.DraftBoardResponse;
import io.crescendo.game.api.GameDtos.DraftRequest;
import io.crescendo.game.api.GameDtos.GameView;
import io.crescendo.game.api.GameDtos.LeaderboardEntry;
import io.crescendo.game.api.GameDtos.LeagueOption;
import io.crescendo.game.api.GameDtos.ScoreRequest;
import io.crescendo.game.service.GameService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST API for the Crescendo v1.0 salary-cap draft game. */
@RestController
@RequestMapping("/api")
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    @GetMapping("/leagues")
    public List<LeagueOption> leagues() {
        return gameService.leagues();
    }

    @PostMapping("/games")
    public ResponseEntity<GameView> createGame(@Valid @RequestBody CreateGameRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(gameService.createGame(request.playerName(), request.league(), request.replayDate()));
    }

    @GetMapping("/games/{gameId}")
    public GameView getGame(@PathVariable long gameId) {
        return gameService.getGame(gameId);
    }

    @GetMapping("/games/{gameId}/board")
    public DraftBoardResponse draftBoard(@PathVariable long gameId) {
        return gameService.draftBoard(gameId);
    }

    @PostMapping("/games/{gameId}/draft")
    public GameView draft(@PathVariable long gameId, @Valid @RequestBody DraftRequest request) {
        return gameService.draft(gameId, request.artistIds());
    }

    @PostMapping("/games/{gameId}/score")
    public GameView score(@PathVariable long gameId, @RequestBody(required = false) ScoreRequest request) {
        return gameService.score(gameId, request == null ? null : request.scoreAsOfDate());
    }

    @GetMapping("/leaderboard")
    public List<LeaderboardEntry> leaderboard() {
        return gameService.leaderboard();
    }
}
