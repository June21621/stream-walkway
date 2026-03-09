package com.stream.backend.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Capture {

    private Long id;

    @JsonProperty("trail_id")
    private Long trailId;

    @JsonProperty("stream_id")
    private Long streamId;

    @JsonProperty("image_path")
    private String imagePath;

    @JsonProperty("road_status")
    private String roadStatus;

    private Double confidence;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;

    public Capture() {}

    public Capture(Long id, Long trailId, Long streamId, String imagePath,
                   String roadStatus, Double confidence, String createdAt, String updatedAt) {
        this.id = id;
        this.trailId = trailId;
        this.streamId = streamId;
        this.imagePath = imagePath;
        this.roadStatus = roadStatus;
        this.confidence = confidence;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTrailId() { return trailId; }
    public void setTrailId(Long trailId) { this.trailId = trailId; }

    public Long getStreamId() { return streamId; }
    public void setStreamId(Long streamId) { this.streamId = streamId; }

    public String getImagePath() { return imagePath; }
    public void setImagePath(String imagePath) { this.imagePath = imagePath; }

    public String getRoadStatus() { return roadStatus; }
    public void setRoadStatus(String roadStatus) { this.roadStatus = roadStatus; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
