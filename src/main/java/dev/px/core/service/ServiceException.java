package dev.px.core.service;

/** Thrown when services cannot be ordered or one fails to start. */
public final class ServiceException extends RuntimeException {

    public ServiceException(String message) {
        super(message);
    }

    public ServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
