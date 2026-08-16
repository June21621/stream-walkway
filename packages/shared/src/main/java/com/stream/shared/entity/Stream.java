package com.stream.shared.entity;

import jakarta.persistence.*;
import org.locationtech.jts.geom.LineString;

import java.time.LocalDateTime;

@Entity
@Table(name = "streams")
public class Stream {

    public static final int SRID = 4326;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, columnDefinition = "geometry(LineString,4326)")
    private LineString location;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public LineString getLocation() { return location; }
    public void setLocation(LineString location) { this.location = location; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
