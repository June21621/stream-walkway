package com.stream.reader.controller;

import com.stream.reader.repository.StreamRepository;
import com.stream.shared.dto.StreamView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/streams")
public class StreamController {

    private final StreamRepository streamRepository;

    public StreamController(StreamRepository streamRepository) {
        this.streamRepository = streamRepository;
    }

    @GetMapping
    public List<StreamView> getAll() {
        return streamRepository.findAll().stream()
                .map(StreamView::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<StreamView> getById(@PathVariable Long id) {
        Optional<StreamView> view = streamRepository.findById(id).map(StreamView::from);
        return view.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
