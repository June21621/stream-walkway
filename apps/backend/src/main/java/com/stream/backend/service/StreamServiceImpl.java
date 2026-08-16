package com.stream.backend.service;

import com.stream.backend.model.Stream;
import com.stream.shared.dto.StreamView;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

@Service
public class StreamServiceImpl implements StreamService {

    private final RestClient readerClient;
    private final RestClient writerClient;

    public StreamServiceImpl(@Qualifier("readerRestClient") RestClient readerClient,
                              @Qualifier("writerRestClient") RestClient writerClient) {
        this.readerClient = readerClient;
        this.writerClient = writerClient;
    }

    private Stream toModel(StreamView view) {
        return new Stream(
                view.id(),
                view.name(),
                view.location(),
                view.createdAt() == null ? null : view.createdAt().toString()
        );
    }

    @Override
    public List<Stream> findAll() {
        List<StreamView> views = readerClient.get()
                .uri("/streams")
                .retrieve()
                .body(new ParameterizedTypeReference<List<StreamView>>() {});
        return views == null ? List.of() : views.stream().map(this::toModel).toList();
    }

    @Override
    public Optional<Stream> findById(Long id) {
        try {
            StreamView view = readerClient.get()
                    .uri("/streams/{id}", id)
                    .retrieve()
                    .body(StreamView.class);
            return Optional.ofNullable(view).map(this::toModel);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    @Override
    public Stream create(Stream stream) {
        StreamView created = writerClient.post()
                .uri("/internal/streams")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(new CreateStreamRequest(stream.getName(), stream.getLocation()))
                .retrieve()
                .body(StreamView.class);
        return toModel(created);
    }

    private record CreateStreamRequest(String name, String location) {}
}
