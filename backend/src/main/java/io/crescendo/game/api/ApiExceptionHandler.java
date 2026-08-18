package io.crescendo.game.api;

import io.crescendo.game.service.GameException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain exceptions to HTTP responses with a small JSON error body. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(GameException.class)
    public ResponseEntity<Map<String, String>> handleGame(GameException e) {
        HttpStatus status = e.getKind() == GameException.Kind.NOT_FOUND
                ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
    }
}
