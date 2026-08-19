package io.crescendo.game.live;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Thin live-stats client over the YouTube Data API v3 (v1.4). Given channel ids it returns the
 * CURRENT subscriber / view / video counts in one batched {@code channels.list} call (up to 50 ids
 * for 1 quota unit). This is the "real-time" surface: the app calls it on demand to show live
 * numbers, distinct from the daily cron that builds history.
 *
 * <p>Degrades gracefully: if {@code YOUTUBE_API_KEY} is unset (e.g. local dev) or the call fails,
 * it returns an empty map so callers show "live stats unavailable" rather than erroring.
 */
@Component
public class YouTubeLiveClient {

    private static final Logger log = LoggerFactory.getLogger(YouTubeLiveClient.class);
    private static final int BATCH = 50;

    private final String apiKey;
    private final RestClient http;

    public YouTubeLiveClient(@Value("${crescendo.youtube.api-key:}") String apiKey,
                             RestClient.Builder builder) {
        this.apiKey = apiKey;
        this.http = builder.baseUrl("https://www.googleapis.com/youtube/v3").build();
    }

    /** True when a key is configured; lets callers/endpoints report the feature as enabled. */
    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** One channel's live stats + a short snippet summary. Null if not found / disabled. */
    public record LiveStats(String channelId, String title, String description,
                            long subscribers, long views, long videos) {
    }

    /**
     * Batch-fetch live stats for the given channel ids (order not guaranteed). Returns an empty map
     * when disabled or on any error — never throws to the caller.
     */
    public Map<String, LiveStats> fetch(List<String> channelIds) {
        Map<String, LiveStats> out = new LinkedHashMap<>();
        if (!isEnabled() || channelIds == null || channelIds.isEmpty()) {
            return out;
        }
        try {
            for (int i = 0; i < channelIds.size(); i += BATCH) {
                List<String> chunk = channelIds.subList(i, Math.min(i + BATCH, channelIds.size()));
                Map<?, ?> body = http.get()
                        .uri(uri -> uri.path("/channels")
                                .queryParam("part", "snippet,statistics")
                                .queryParam("id", String.join(",", chunk))
                                .queryParam("maxResults", BATCH)
                                .queryParam("key", apiKey)
                                .build())
                        .retrieve()
                        .body(Map.class);
                parseInto(body, out);
            }
        } catch (Exception e) {  // noqa: broad — live stats are best-effort, degrade to empty
            log.warn("youtube.live.fetch_failed: {}", e.getMessage());
            return Map.of();
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void parseInto(Map<?, ?> body, Map<String, LiveStats> out) {
        if (body == null) {
            return;
        }
        Object items = body.get("items");
        if (!(items instanceof List<?> list)) {
            return;
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> item)) {
                continue;
            }
            String id = String.valueOf(item.get("id"));
            Map<String, Object> snippet = (Map<String, Object>) item.get("snippet");
            Map<String, Object> stats = (Map<String, Object>) item.get("statistics");
            if (snippet == null || stats == null) {
                continue;
            }
            String title = String.valueOf(snippet.getOrDefault("title", ""));
            String desc = String.valueOf(snippet.getOrDefault("description", ""));
            out.put(id, new LiveStats(
                    id, title, summarise(desc),
                    parseLong(stats.get("subscriberCount")),
                    parseLong(stats.get("viewCount")),
                    parseLong(stats.get("videoCount"))));
        }
    }

    /** First sentence / ~200 chars of the channel description, as a lightweight "about" summary. */
    private static String summarise(String desc) {
        if (desc == null || desc.isBlank()) {
            return "";
        }
        String trimmed = desc.strip();
        int dot = trimmed.indexOf(". ");
        String first = dot > 40 ? trimmed.substring(0, dot + 1) : trimmed;
        return first.length() > 200 ? first.substring(0, 197) + "…" : first;
    }

    private static long parseLong(Object v) {
        try {
            return v == null ? 0L : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
