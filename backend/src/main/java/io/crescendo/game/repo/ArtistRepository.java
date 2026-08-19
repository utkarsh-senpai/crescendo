package io.crescendo.game.repo;

import io.crescendo.game.domain.Artist;
import io.crescendo.game.domain.League;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtistRepository extends JpaRepository<Artist, Long> {

    List<Artist> findByLeague(League league);
}
