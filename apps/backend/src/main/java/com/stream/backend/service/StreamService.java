package com.stream.backend.service;

import com.stream.backend.model.Stream;

import java.util.List;
import java.util.Optional;

public interface StreamService {
    List<Stream> findAll();
    Optional<Stream> findById(Long id);
    Stream create(Stream stream);
}
