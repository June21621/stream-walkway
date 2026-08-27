package com.stream.shared.entity;

import jakarta.persistence.*;

import java.time.Instant;

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
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        // DB의 DEFAULT CURRENT_TIMESTAMP가 채운 값은 save() 직후 엔티티에
        // 반영되지 않아 CaptureView.from(saved)이 null을 담게 된다.
        // createdAt이 이미 같은 이유로 여기서 채워진다.
        //
        // @PreUpdate는 두지 않는다. 캡처는 Kafka image.analyzed로 INSERT만 되고
        // 애플리케이션에 UPDATE 경로가 없으며, 갱신은 DB 트리거
        // (update_captures_updated_at)가 담당한다.
        updatedAt = createdAt;
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
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
