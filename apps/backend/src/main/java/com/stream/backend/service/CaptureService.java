package com.stream.backend.service;

import com.stream.backend.model.Capture;

import java.util.List;
import java.util.Optional;

public interface CaptureService {
    List<Capture> findAll(Long streamId, Long trailId, Integer limit, String sort);
    Optional<Capture> findById(Long id);
}
