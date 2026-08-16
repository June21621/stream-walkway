package com.stream.reader.repository;

import com.stream.shared.entity.Stream;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StreamRepository extends JpaRepository<Stream, Long> {
}
