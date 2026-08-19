package com.stream.backend.exception;

public class TrailNotFoundException extends RuntimeException {

    private final Long id;

    public TrailNotFoundException(Long id) {
        super("Trail not found: " + id);
        this.id = id;
    }

    public Long getId() {
        return id;
    }
}
