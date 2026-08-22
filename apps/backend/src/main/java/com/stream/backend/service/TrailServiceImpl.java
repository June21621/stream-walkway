package com.stream.backend.service;

import com.stream.backend.exception.DuplicateTrailException;
import com.stream.backend.exception.InvalidTrailGeometryException;
import com.stream.backend.model.Trail;
import com.stream.shared.dto.TrailView;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

@Service
public class TrailServiceImpl implements TrailService {

    private final RestClient readerClient;
    private final RestClient writerClient;

    public TrailServiceImpl(@Qualifier("readerRestClient") RestClient readerClient,
                             @Qualifier("writerRestClient") RestClient writerClient) {
        this.readerClient = readerClient;
        this.writerClient = writerClient;
    }

    private Trail toModel(TrailView view) {
        return new Trail(
                view.id(),
                view.streamId(),
                view.cameraNumber(),
                view.location(),
                view.direction(),
                view.status(),
                view.createdAt() == null ? null : view.createdAt().toString()
        );
    }

    @Override
    public List<Trail> findAll(Long streamId) {
        List<TrailView> views;
        if (streamId == null) {
            views = readerClient.get()
                    .uri("/trails")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<TrailView>>() {});
        } else {
            views = readerClient.get()
                    .uri("/trails?stream_id={streamId}", streamId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<TrailView>>() {});
        }
        return views == null ? List.of() : views.stream().map(this::toModel).toList();
    }

    @Override
    public Optional<Trail> findById(Long id) {
        try {
            TrailView view = readerClient.get()
                    .uri("/trails/{id}", id)
                    .retrieve()
                    .body(TrailView.class);
            return Optional.ofNullable(view).map(this::toModel);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    @Override
    public Trail create(Trail trail) {
        TrailView created;
        try {
            created = writerClient.post()
                    .uri("/internal/trails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CreateTrailRequest(trail.getStreamId(), trail.getCameraNumber(),
                            trail.getLocation(), trail.getDirection(), trail.getStatus()))
                    .retrieve()
                    .body(TrailView.class);
        } catch (HttpClientErrorException.BadRequest e) {
            throw new InvalidTrailGeometryException("Writer rejected the trail data: " + e.getResponseBodyAsString());
        } catch (HttpClientErrorException.Conflict e) {
            throw new DuplicateTrailException("Writer rejected duplicate trail: " + e.getResponseBodyAsString());
        }
        if (created == null) {
            throw new InvalidTrailGeometryException("Writer returned an empty response for trail creation");
        }
        return toModel(created);
    }

    private record CreateTrailRequest(Long streamId, String cameraNumber, String location, String direction, String status) {}
}
