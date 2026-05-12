package com.rewardhub.core.exception;

/** The request conflicts with current state (e.g. duplicate, version clash). */
public class ConflictException extends DomainException {

    public ConflictException(String message) {
        super(message);
    }
}
