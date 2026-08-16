package com.stream.backend.controller;

import com.stream.backend.exception.InvalidInternalKeyException;
import com.stream.backend.exception.StreamNotFoundException;
import com.stream.backend.model.Stream;
import com.stream.backend.service.StreamService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/streams")
public class StreamController {

    private final StreamService streamService;
    private final String internalApiKey;

    public StreamController(StreamService streamService,
                             @Value("${internal.api-key}") String internalApiKey) {
        this.streamService = streamService;
        this.internalApiKey = internalApiKey;
    }

    @GetMapping
    public ResponseEntity<List<Stream>> getAll() {
        return ResponseEntity.ok(streamService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        Stream stream = streamService.findById(id)
                .orElseThrow(() -> new StreamNotFoundException(id));
        return ResponseEntity.ok(stream);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Stream stream,
                                    @RequestHeader("X-Internal-Key") String internalKey) {
        if (!internalApiKey.equals(internalKey)) {
            throw new InvalidInternalKeyException();
        }
        Stream created = streamService.create(stream);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
