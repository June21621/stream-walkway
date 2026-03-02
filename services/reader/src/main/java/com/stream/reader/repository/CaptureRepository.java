package com.stream.reader.repository;

import com.stream.reader.entity.Capture;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CaptureRepository extends JpaRepository<Capture, Long> {
    List<Capture> findByTrailId(Integer trailId);
    List<Capture> findByStreamId(Integer streamId);
}
