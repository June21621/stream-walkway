package com.stream.shared.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "captures")
public class Capture {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trail_id", nullable = false)
    private Integer trailId;

    @Column(name = "stream_id", nullable = false)
    private Integer streamId;

    @Column(name = "image_path", nullable = false)
    private String imagePath;

    @Column(name = "road_status")
    private String roadStatus;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Integer getTrailId() { return trailId; }
    public void setTrailId(Integer trailId) { this.trailId = trailId; }
    public Integer getStreamId() { return streamId; }
    public void setStreamId(Integer streamId) { this.streamId = streamId; }
    public String getImagePath() { return imagePath; }
    public void setImagePath(String imagePath) { this.imagePath = imagePath; }
    public String getRoadStatus() { return roadStatus; }
    public void setRoadStatus(String roadStatus) { this.roadStatus = roadStatus; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
