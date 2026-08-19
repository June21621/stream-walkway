package com.stream.shared.entity;

import jakarta.persistence.*;
import org.locationtech.jts.geom.Point;

import java.time.LocalDateTime;

@Entity
@Table(name = "trails")
public class Trail {

    public static final int SRID = 4326;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stream_id", nullable = false)
    private Long streamId;

    @Column(name = "camera_number", nullable = false)
    private String cameraNumber;

    @Column(nullable = false, columnDefinition = "geometry(Point,4326)")
    private Point location;

    private String direction;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }

    public Long getStreamId() { return streamId; }
    public void setStreamId(Long streamId) { this.streamId = streamId; }

    public String getCameraNumber() { return cameraNumber; }
    public void setCameraNumber(String cameraNumber) { this.cameraNumber = cameraNumber; }

    public Point getLocation() { return location; }
    public void setLocation(Point location) { this.location = location; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
