package com.stream.backend.service;

import com.stream.backend.model.Trail;

import java.util.List;
import java.util.Optional;

public interface TrailService {
    List<Trail> findAll(Long streamId);
    Optional<Trail> findById(Long id);
    Trail create(Trail trail);
}
