package com.stream.shared.dto;

import com.stream.shared.entity.Stream;

import java.time.Instant;

public record StreamView(
        Long id,
        String name,
        String location,
        Instant createdAt
) {
    public static StreamView from(Stream stream) {
        String wkt = stream.getLocation().toText().replaceFirst("\\s+\\(", "(");
        return new StreamView(
                stream.getId(),
                stream.getName(),
                wkt,
                stream.getCreatedAt()
        );
    }
}
