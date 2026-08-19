package com.stream.reader.repository;

import com.stream.shared.entity.Trail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TrailRepository extends JpaRepository<Trail, Long> {
    List<Trail> findByStreamId(Long streamId);
}
