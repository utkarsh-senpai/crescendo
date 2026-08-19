package io.crescendo.game.api;

import io.crescendo.game.domain.Artist;
import io.crescendo.game.domain.GameSession;
import io.crescendo.game.live.YouTubeLiveClient;
import io.crescendo.game.live.YouTubeLiveClient.LiveStats;
import io.crescendo.game.repo.ArtistRepository;
import io.crescendo.game.repo.GameSessionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Real-time YouTube stats surface (v1.4). Uses the API key on demand to show CURRENT
 * subscriber/view counts + a short channel summary for the artists in a game's league — making the
 * board feel live, distinct from the daily-history cron. Degrades cleanly when no key is set.
 */
@RestController
@RequestMapping("/api/live")
public class LiveController {

    private final YouTubeLiveClient live;
    private final GameSessionRepository games;
    private final ArtistRepository artists;

    public LiveController(YouTubeLiveClient live, GameSessionRepository games,
                          ArtistRepository artists) {
        this.live = live;
        this.games = games;
        this.artists = artists;
    }

    /** Whether the real-time feature is on (API key configured). */
    @GetMapping("/enabled")
    public Map<String, Boolean> enabled() {
        return Map.of("enabled", live.isEnabled());
    }

    /** One artist's live stats, keyed by artistId. */
    public record LiveArtist(long artistId, String name, String channelId,
                             long subscribers, long views, long videos, String summary) {
    }

    public record LiveBoardResponse(boolean enabled, List<LiveArtist> artists) {
    }

    /**
     * Live stats for every artist in the given game's league. One batched YouTube call
     * (1 quota unit / 50 channels). Returns {@code enabled=false} + empty list when no key is set.
     */
    @GetMapping("/board/{gameId}")
    public LiveBoardResponse board(@PathVariable long gameId) {
        GameSession game = games.findById(gameId).orElse(null);
        if (game == null || !live.isEnabled()) {
            return new LiveBoardResponse(false, List.of());
        }
        List<Artist> pool = artists.findByLeague(game.getLeague());
        List<String> channelIds = pool.stream()
                .map(Artist::getChannelId)
                .filter(c -> c != null && !c.isBlank())
                .collect(Collectors.toList());

        Map<String, LiveStats> stats = live.fetch(channelIds);
        List<LiveArtist> out = new ArrayList<>();
        for (Artist a : pool) {
            LiveStats s = a.getChannelId() == null ? null : stats.get(a.getChannelId());
            if (s != null) {
                out.add(new LiveArtist(a.getArtistId(), a.getName(), a.getChannelId(),
                        s.subscribers(), s.views(), s.videos(), s.description()));
            }
        }
        return new LiveBoardResponse(true, out);
    }
}
