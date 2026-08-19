package com.stream.shared.dto;

import com.stream.shared.entity.Trail;
import org.locationtech.jts.io.WKTWriter;

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
    private static final WKTWriter WKT_WRITER = new WKTWriter();

    public static TrailView from(Trail trail) {
        String wkt = WKT_WRITER.write(trail.getLocation()).replaceFirst("\\s+\\(", "(");
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
