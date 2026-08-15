package com.stream.writer.repository;

import com.stream.shared.entity.Capture;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaptureRepository extends JpaRepository<Capture, Long> {
}
