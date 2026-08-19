package io.crescendo.game.api;

import io.crescendo.game.domain.Feedback;
import io.crescendo.game.service.FeedbackService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST API for collecting player feedback (v1.2) + an admin read (v1.3.1). */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;

    /**
     * Shared-secret for the admin read endpoint. Set CRESCENDO_ADMIN_TOKEN in the environment
     * (Render dashboard) to enable GET /api/feedback. Left blank → the read endpoint is disabled
     * (403), so it is never open by default.
     */
    private final String adminToken;

    public FeedbackController(FeedbackService feedbackService,
                              @Value("${crescendo.admin.token:}") String adminToken) {
        this.feedbackService = feedbackService;
        this.adminToken = adminToken;
    }

    /** One feedback row as returned to the admin reader. */
    public record FeedbackItem(long id, Integer rating, String message, String name, Instant createdAt) {
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

    /**
     * Admin read: most recent feedback first. Requires the X-Admin-Token header to match
     * CRESCENDO_ADMIN_TOKEN. Disabled (403) if no token is configured, so it is never public by
     * default. Example: {@code curl -H "X-Admin-Token: $TOKEN" https://.../api/feedback?limit=100}
     */
    @GetMapping
    public ResponseEntity<List<FeedbackItem>> list(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        if (adminToken == null || adminToken.isBlank() || !adminToken.equals(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        List<FeedbackItem> items = feedbackService.recent(limit).stream()
                .map(f -> new FeedbackItem(
                        f.getId(), f.getRating(), f.getMessage(), f.getName(), f.getCreatedAt()))
                .toList();
        return ResponseEntity.ok(items);
    }
}
