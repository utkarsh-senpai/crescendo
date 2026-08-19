package io.crescendo.game.service;

import io.crescendo.game.domain.Feedback;
import io.crescendo.game.repo.FeedbackRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores player feedback. Validation of the wire input lives at the DTO layer; this service is the
 * thin persistence seam (server-stamps {@code createdAt}, trims/normalises the fields) so it stays
 * testable without the web tier.
 */
@Service
public class FeedbackService {

    private final FeedbackRepository repository;

    public FeedbackService(FeedbackRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Feedback submit(Integer rating, String message, String name) {
        if (rating != null && (rating < 1 || rating > 5)) {
            throw GameException.badRequest("rating must be between 1 and 5");
        }
        String trimmedMessage = message == null ? "" : message.strip();
        if (trimmedMessage.isEmpty()) {
            throw GameException.badRequest("message must not be blank");
        }
        String trimmedName = (name == null || name.isBlank()) ? null : name.strip();
        return repository.save(new Feedback(rating, trimmedMessage, trimmedName, Instant.now()));
    }

    public long count() {
        return repository.count();
    }
}
