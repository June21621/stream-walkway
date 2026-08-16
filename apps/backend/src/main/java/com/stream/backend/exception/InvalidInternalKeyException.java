package com.stream.backend.exception;

public class InvalidInternalKeyException extends RuntimeException {

    public InvalidInternalKeyException() {
        super("Invalid or missing X-Internal-Key");
    }
}
