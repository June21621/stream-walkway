package com.stream.backend.controller;

import com.stream.backend.exception.InvalidInternalKeyException;
import com.stream.backend.exception.TrailNotFoundException;
import com.stream.backend.model.Trail;
import com.stream.backend.service.TrailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/trails")
public class TrailController {

    private final TrailService trailService;
    private final String internalApiKey;

    public TrailController(TrailService trailService,
                            @Value("${internal.api-key}") String internalApiKey) {
        this.trailService = trailService;
        this.internalApiKey = internalApiKey;
    }

    @GetMapping
    public ResponseEntity<List<Trail>> getAll(@RequestParam(value = "stream_id", required = false) Long streamId) {
        return ResponseEntity.ok(trailService.findAll(streamId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        Trail trail = trailService.findById(id)
                .orElseThrow(() -> new TrailNotFoundException(id));
        return ResponseEntity.ok(trail);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Trail trail,
                                    @RequestHeader("X-Internal-Key") String internalKey) {
        if (!internalApiKey.equals(internalKey)) {
            throw new InvalidInternalKeyException();
        }
        Trail created = trailService.create(trail);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
