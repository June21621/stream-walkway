package com.stream.backend.controller;

import com.stream.backend.model.Capture;
import com.stream.backend.service.CaptureService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/captures")
public class CaptureController {

    private final CaptureService captureService;

    public CaptureController(CaptureService captureService) {
        this.captureService = captureService;
    }

    @GetMapping
    public ResponseEntity<List<Capture>> getAll(
            @RequestParam(required = false) Long streamId,
            @RequestParam(required = false) Long trailId,
            @RequestParam(defaultValue = "20") Integer limit,
            @RequestParam(defaultValue = "created_at") String sort) {
        // TODO: 구현 필요 (TDD - RED 단계)
        throw new UnsupportedOperationException("Not implemented");
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        // TODO: 구현 필요 (TDD - RED 단계)
        throw new UnsupportedOperationException("Not implemented");
    }
}
