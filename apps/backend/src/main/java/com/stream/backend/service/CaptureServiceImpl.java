package com.stream.backend.service;

import com.stream.backend.model.Capture;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class CaptureServiceImpl implements CaptureService {

    @Override
    public List<Capture> findAll(Long streamId, Long trailId, Integer limit, String sort) {
        // TODO: 구현 필요 (TDD - RED 단계)
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public Optional<Capture> findById(Long id) {
        // TODO: 구현 필요 (TDD - RED 단계)
        throw new UnsupportedOperationException("Not implemented");
    }
}
