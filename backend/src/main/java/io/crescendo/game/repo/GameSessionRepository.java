package io.crescendo.game.repo;

import io.crescendo.game.domain.GameSession;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameSessionRepository extends JpaRepository<GameSession, Long> {

    List<GameSession> findByStatusOrderByPlayerScoreDesc(GameSession.Status status);
}
