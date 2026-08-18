package io.crescendo.game.predict;

import io.crescendo.game.domain.ArtistFeatureSnapshot;
import io.crescendo.game.predict.PredictDtos.ArtistFeatures;
import io.crescendo.game.predict.PredictDtos.PredictRequest;
import io.crescendo.game.predict.PredictDtos.PredictResponse;
import io.crescendo.game.predict.PredictDtos.RankedArtist;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Client for the deployed Crescendo /predict seam (default: the live Render service). Turns stored
 * feature snapshots into the wire request, calls POST /predict, and maps the ranked response back
 * keyed by artist id.
 *
 * <p>The seam is treated as best-effort from the game's point of view: if it is unreachable (e.g.
 * Render free-tier cold start times out, or the network is down), {@link #rank} returns an empty
 * map rather than throwing, and callers fall back to salary-implied ordering. This keeps drafting
 * and scoring functional offline, which also makes the flow testable without a live endpoint.
 */
@Component
public class PredictClient {

    private static final Logger log = LoggerFactory.getLogger(PredictClient.class);

    private final RestClient restClient;

    public PredictClient(RestClient.Builder builder,
                         @Value("${crescendo.predict.base-url}") String baseUrl) {
        // Build on Boot's auto-configured builder (keeps the ISO-8601 LocalDate serialization the
        // seam expects); only layer on generous timeouts for Render's free-tier cold start.
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(10))
                .withReadTimeout(Duration.ofSeconds(75));
        this.restClient = builder
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    /**
     * Rank the given feature snapshots via the seam.
     *
     * @return artistId → ranked result; empty if the seam is unavailable.
     */
    public Map<Long, RankedArtist> rank(LocalDate asOfDate, List<ArtistFeatureSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return Map.of();
        }
        List<ArtistFeatures> artists = snapshots.stream()
                .map(PredictClient::toWire)
                .toList();
        PredictRequest request = new PredictRequest(asOfDate, artists);
        try {
            PredictResponse response = restClient.post()
                    .uri("/predict")
                    .body(request)
                    .retrieve()
                    .body(PredictResponse.class);
            if (response == null || response.ranked() == null) {
                log.warn("predict seam returned empty body; falling back");
                return Map.of();
            }
            Map<Long, RankedArtist> byArtist = new LinkedHashMap<>();
            for (RankedArtist r : response.ranked()) {
                byArtist.put(r.artistId(), r);
            }
            return byArtist;
        } catch (RestClientException e) {
            log.warn("predict seam unavailable ({}); falling back to salary-implied ordering",
                    e.getMessage());
            return Map.of();
        }
    }

    /** Map a snapshot's named columns to the FEATURES dict the seam expects (nulls omitted). */
    private static ArtistFeatures toWire(ArtistFeatureSnapshot s) {
        Map<String, Double> f = new HashMap<>();
        putIfPresent(f, "subs", s.getSubs());
        putIfPresent(f, "growth_7d", s.getGrowth7d());
        putIfPresent(f, "growth_30d", s.getGrowth30d());
        putIfPresent(f, "accel", s.getAccel());
        putIfPresent(f, "consistency", s.getConsistency());
        putIfPresent(f, "views_growth_7d", s.getViewsGrowth7d());
        putIfPresent(f, "upload_rate_30d", s.getUploadRate30d());
        putIfPresent(f, "inorganic_score", s.getInorganicScore());
        return new ArtistFeatures(s.getArtistId(), f);
    }

    private static void putIfPresent(Map<String, Double> map, String key, Double value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
