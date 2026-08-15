package com.stream.reader.repository;

import com.stream.shared.entity.Capture;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CaptureRepository extends JpaRepository<Capture, Long> {
    List<Capture> findByTrailId(Integer trailId);
    List<Capture> findByStreamId(Integer streamId);
    Optional<Capture> findFirstByTrailIdOrderByCreatedAtDesc(Integer trailId);
}
