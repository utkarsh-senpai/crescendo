package io.crescendo.game.repo;

import io.crescendo.game.domain.RosterPick;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RosterPickRepository extends JpaRepository<RosterPick, Long> {

    List<RosterPick> findByGameId(Long gameId);
}
