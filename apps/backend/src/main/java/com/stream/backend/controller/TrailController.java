package com.stream.backend.controller;

import com.stream.backend.model.Trail;
import com.stream.backend.service.TrailService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/trails")
public class TrailController {

    private final TrailService trailService;

    public TrailController(TrailService trailService) {
        this.trailService = trailService;
    }

    @GetMapping
    public ResponseEntity<List<Trail>> getAll(@RequestParam(required = false) Long streamId) {
        // TODO: 구현 필요 (TDD - RED 단계)
        throw new UnsupportedOperationException("Not implemented");
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        // TODO: 구현 필요 (TDD - RED 단계)
        throw new UnsupportedOperationException("Not implemented");
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Trail trail,
                                    @RequestHeader("X-Internal-Key") String internalKey) {
        // TODO: 구현 필요 (TDD - RED 단계)
        throw new UnsupportedOperationException("Not implemented");
    }
}
