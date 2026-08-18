package io.crescendo.game.repo;

import io.crescendo.game.domain.ArtistFeatureSnapshot;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtistFeatureSnapshotRepository extends JpaRepository<ArtistFeatureSnapshot, Long> {

    /** Latest snapshot for an artist as of (on or before) a date — the as-of feature vector. */
    Optional<ArtistFeatureSnapshot> findFirstByArtistIdAndAsOfDateLessThanEqualOrderByAsOfDateDesc(
            Long artistId, LocalDate asOfDate);

    List<ArtistFeatureSnapshot> findByArtistId(Long artistId);
}
