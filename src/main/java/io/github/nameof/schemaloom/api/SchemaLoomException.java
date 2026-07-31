package io.github.nameof.schemaloom.api;

public class SchemaLoomException extends RuntimeException {
    public SchemaLoomException(String message) {
        super(message);
    }

    public SchemaLoomException(String message, Throwable cause) {
        super(message, cause);
    }
}
