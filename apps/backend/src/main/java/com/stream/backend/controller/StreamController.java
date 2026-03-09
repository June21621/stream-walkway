package com.stream.backend.controller;

import com.stream.backend.model.Stream;
import com.stream.backend.service.StreamService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/streams")
public class StreamController {

    private final StreamService streamService;

    public StreamController(StreamService streamService) {
        this.streamService = streamService;
    }

    @GetMapping
    public ResponseEntity<List<Stream>> getAll() {
        // TODO: 구현 필요 (TDD - RED 단계)
        throw new UnsupportedOperationException("Not implemented");
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        // TODO: 구현 필요 (TDD - RED 단계)
        throw new UnsupportedOperationException("Not implemented");
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Stream stream,
                                    @RequestHeader("X-Internal-Key") String internalKey) {
        // TODO: 구현 필요 (TDD - RED 단계)
        throw new UnsupportedOperationException("Not implemented");
    }
}
