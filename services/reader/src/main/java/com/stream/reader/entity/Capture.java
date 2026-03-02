package com.stream.reader.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "captures")
public class Capture {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trail_id")
    private Integer trailId;

    @Column(name = "stream_id")
    private Integer streamId;

    @Column(name = "image_path")
    private String imagePath;

    @Column(name = "road_status")
    private String roadStatus;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // Getters
    public Long getId() { return id; }
    public Integer getTrailId() { return trailId; }
    public Integer getStreamId() { return streamId; }
    public String getImagePath() { return imagePath; }
    public String getRoadStatus() { return roadStatus; }
    public Double getConfidence() { return confidence; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
