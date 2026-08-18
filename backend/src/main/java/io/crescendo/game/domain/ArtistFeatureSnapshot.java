package io.crescendo.game.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * A stored as-of feature vector for an artist on a given date. The game keeps these so it can
 * (a) send them to the /predict seam when drafting or scoring and (b) show players what the
 * model saw. Feature names match the ML canonical FEATURES list exactly; storing them as named
 * columns keeps the /predict request construction a straight field-to-map copy.
 */
@Entity
@Table(name = "artist_feature_snapshot")
public class ArtistFeatureSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "artist_id", nullable = false)
    private Long artistId;

    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    private Double subs;
    private Double growth7d;
    private Double growth30d;
    private Double accel;
    private Double consistency;
    private Double viewsGrowth7d;
    private Double uploadRate30d;
    private Double inorganicScore;

    protected ArtistFeatureSnapshot() {
        // JPA
    }

    public ArtistFeatureSnapshot(Long artistId, LocalDate asOfDate, Double subs, Double growth7d,
                                 Double growth30d, Double accel, Double consistency,
                                 Double viewsGrowth7d, Double uploadRate30d, Double inorganicScore) {
        this.artistId = artistId;
        this.asOfDate = asOfDate;
        this.subs = subs;
        this.growth7d = growth7d;
        this.growth30d = growth30d;
        this.accel = accel;
        this.consistency = consistency;
        this.viewsGrowth7d = viewsGrowth7d;
        this.uploadRate30d = uploadRate30d;
        this.inorganicScore = inorganicScore;
    }

    public Long getArtistId() {
        return artistId;
    }

    public LocalDate getAsOfDate() {
        return asOfDate;
    }

    public Double getSubs() {
        return subs;
    }

    public Double getGrowth7d() {
        return growth7d;
    }

    public Double getGrowth30d() {
        return growth30d;
    }

    public Double getAccel() {
        return accel;
    }

    public Double getConsistency() {
        return consistency;
    }

    public Double getViewsGrowth7d() {
        return viewsGrowth7d;
    }

    public Double getUploadRate30d() {
        return uploadRate30d;
    }

    public Double getInorganicScore() {
        return inorganicScore;
    }
}
