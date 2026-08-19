package com.stream.reader.controller;

import com.stream.reader.repository.TrailRepository;
import com.stream.shared.dto.TrailView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/trails")
public class TrailController {

    private final TrailRepository trailRepository;

    public TrailController(TrailRepository trailRepository) {
        this.trailRepository = trailRepository;
    }

    @GetMapping
    public List<TrailView> getAll(@RequestParam(value = "stream_id", required = false) Long streamId) {
        var trails = streamId == null
                ? trailRepository.findAll()
                : trailRepository.findByStreamId(streamId);
        return trails.stream().map(TrailView::from).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<TrailView> getById(@PathVariable Long id) {
        Optional<TrailView> view = trailRepository.findById(id).map(TrailView::from);
        return view.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
