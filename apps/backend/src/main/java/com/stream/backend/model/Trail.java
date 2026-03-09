package com.stream.backend.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Trail {

    private Long id;

    @JsonProperty("stream_id")
    private Long streamId;

    @JsonProperty("camera_number")
    private String cameraNumber;

    private String location;
    private String direction;
    private String status;

    @JsonProperty("created_at")
    private String createdAt;

    public Trail() {}

    public Trail(Long id, Long streamId, String cameraNumber, String location,
                 String direction, String status, String createdAt) {
        this.id = id;
        this.streamId = streamId;
        this.cameraNumber = cameraNumber;
        this.location = location;
        this.direction = direction;
        this.status = status;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getStreamId() { return streamId; }
    public void setStreamId(Long streamId) { this.streamId = streamId; }

    public String getCameraNumber() { return cameraNumber; }
    public void setCameraNumber(String cameraNumber) { this.cameraNumber = cameraNumber; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
