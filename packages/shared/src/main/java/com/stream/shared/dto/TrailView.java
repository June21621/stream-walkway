package com.stream.shared.dto;

import com.stream.shared.entity.Trail;

import java.time.LocalDateTime;

public record TrailView(
        Long id,
        Long streamId,
        String cameraNumber,
        String location,
        String direction,
        String status,
        LocalDateTime createdAt
) {
    public static TrailView from(Trail trail) {
        String wkt = trail.getLocation().toText().replaceFirst("\\s+\\(", "(");
        return new TrailView(
                trail.getId(),
                trail.getStreamId(),
                trail.getCameraNumber(),
                wkt,
                trail.getDirection(),
                trail.getStatus(),
                trail.getCreatedAt()
        );
    }
}
