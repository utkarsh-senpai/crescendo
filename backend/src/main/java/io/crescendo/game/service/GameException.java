package io.crescendo.game.service;

/** Domain-rule violation (bad draft, unknown game, etc.) surfaced to the client as HTTP 400/404. */
public class GameException extends RuntimeException {

    public enum Kind {
        NOT_FOUND,
        BAD_REQUEST
    }

    private final Kind kind;

    public GameException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }

    public static GameException notFound(String message) {
        return new GameException(Kind.NOT_FOUND, message);
    }

    public static GameException badRequest(String message) {
        return new GameException(Kind.BAD_REQUEST, message);
    }
}
