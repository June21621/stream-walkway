package com.stream.writer.repository;

import com.stream.writer.entity.Capture;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaptureRepository extends JpaRepository<Capture, Long> {
}
