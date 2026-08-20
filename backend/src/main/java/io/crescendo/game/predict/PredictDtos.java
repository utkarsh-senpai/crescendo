package io.crescendo.game.predict;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Wire DTOs for the /predict seam (serving/crescendo_serving/schemas.py). Field names use
 * snake_case via @JsonProperty to match the FastAPI/pydantic contract exactly, so the game
 * backend and the Python seam stay byte-compatible.
 */
public final class PredictDtos {

    private PredictDtos() {
    }

    public record ArtistFeatures(
            @JsonProperty("artist_id") long artistId,
            Map<String, Double> features) {
    }

    public record PredictRequest(
            @JsonProperty("as_of_date") LocalDate asOfDate,
            List<ArtistFeatures> artists) {
    }

    public record RankedArtist(
            @JsonProperty("artist_id") long artistId,
            @JsonProperty("breakout_score") double breakoutScore,
            int rank,
            List<String> reasons,
            // v1.5: discovery edge and confidence tier from the predict seam
            @JsonProperty("discovery_edge") Double discoveryEdge,
            @JsonProperty("confidence_tier") String confidenceTier,
            // v1.7: cross-sectional conformal prediction intervals
            @JsonProperty("prediction_interval_lo") Double predictionIntervalLo,
            @JsonProperty("prediction_interval_hi") Double predictionIntervalHi) {
    }

    public record PredictResponse(
            @JsonProperty("as_of_date") LocalDate asOfDate,
            @JsonProperty("model_kind") String modelKind,
            @JsonProperty("dataset_version") String datasetVersion,
            List<RankedArtist> ranked) {
    }
}
