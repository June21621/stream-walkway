package com.stream.shared.dto;

import com.stream.shared.entity.Capture;

import java.time.Instant;

public record CaptureView(
        Long id,
        Integer trailId,
        Integer streamId,
        String imagePath,
        String roadStatus,
        Double confidence,
        Instant createdAt,
        Instant updatedAt
) {
    public static CaptureView from(Capture capture) {
        return new CaptureView(
                capture.getId(),
                capture.getTrailId(),
                capture.getStreamId(),
                capture.getImagePath(),
                capture.getRoadStatus(),
                capture.getConfidence(),
                capture.getCreatedAt(),
                capture.getUpdatedAt()
        );
    }
}
