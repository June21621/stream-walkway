package com.stream.backend.exception;

public class StreamNotFoundException extends RuntimeException {

    private final Long id;

    public StreamNotFoundException(Long id) {
        super("Stream not found: " + id);
        this.id = id;
    }

    public Long getId() {
        return id;
    }
}
