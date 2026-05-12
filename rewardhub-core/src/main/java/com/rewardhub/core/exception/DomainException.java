package com.rewardhub.core.exception;

/** Base class for expected business-rule violations that map to 4xx API responses. */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }

    protected DomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
