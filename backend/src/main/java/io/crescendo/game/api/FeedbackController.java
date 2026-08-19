package io.crescendo.game.api;

import io.crescendo.game.domain.Feedback;
import io.crescendo.game.service.FeedbackService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST API for collecting player feedback (v1.2). */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    /** Wire contract for a feedback submission. Rating is optional; message is required. */
    public record FeedbackRequest(
            @Min(1) @Max(5) Integer rating,
            @NotBlank @Size(max = 2000) String message,
            @Size(max = 120) String name) {
    }

    public record FeedbackResponse(long id, Instant createdAt) {
    }

    @PostMapping
    public ResponseEntity<FeedbackResponse> submit(@Valid @RequestBody FeedbackRequest request) {
        Feedback saved = feedbackService.submit(request.rating(), request.message(), request.name());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new FeedbackResponse(saved.getId(), saved.getCreatedAt()));
    }

    /** Public count so the app can show a subtle "N pieces of feedback so far" social proof. */
    @GetMapping("/count")
    public Map<String, Long> count() {
        return Map.of("count", feedbackService.count());
    }
}
