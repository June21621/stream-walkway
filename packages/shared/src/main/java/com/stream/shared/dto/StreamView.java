package com.stream.shared.dto;

import com.stream.shared.entity.Stream;

import java.time.LocalDateTime;

public record StreamView(
        Long id,
        String name,
        String location,
        LocalDateTime createdAt
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
