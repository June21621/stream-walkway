package com.stream.backend.exception;

public class CaptureNotFoundException extends RuntimeException {

    private final Long id;

    public CaptureNotFoundException(Long id) {
        super("Capture not found: " + id);
        this.id = id;
    }

    public Long getId() {
        return id;
    }
}
