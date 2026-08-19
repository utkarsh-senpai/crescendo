package io.crescendo.game.repo;

import io.crescendo.game.domain.OpponentPick;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OpponentPickRepository extends JpaRepository<OpponentPick, Long> {

    List<OpponentPick> findByGameId(Long gameId);
}
