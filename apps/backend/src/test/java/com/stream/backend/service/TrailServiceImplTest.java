package com.stream.backend.service;

import com.stream.backend.exception.DuplicateTrailException;
import com.stream.backend.exception.InvalidTrailGeometryException;
import com.stream.backend.model.Trail;
import com.stream.shared.dto.TrailView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayName("Backend - TrailServiceImpl 테스트")
class TrailServiceImplTest {

    @Test
    @DisplayName("findAll(null) - reader의 GET /trails 응답을 Trail 모델 리스트로 변환한다")
    void findAll_withNullStreamId_mapsReaderResponseToTrailList() {
        // given
        RestClient readerClient = mock(RestClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        RestClient writerClient = mock(RestClient.class);
        TrailView view = new TrailView(1L, 1L, "CAM-001", "POINT(126.97 37.55)", "북", "active",
                Instant.parse("2024-01-01T00:00:00Z"));

        given(readerClient.get()
                .uri("/trails")
                .retrieve()
                .body(any(org.springframework.core.ParameterizedTypeReference.class)))
                .willReturn(List.of(view));

        TrailServiceImpl service = new TrailServiceImpl(readerClient, writerClient);

        // when
        List<Trail> result = service.findAll(null);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(1L);
        assertThat(result.get(0).getStreamId()).isEqualTo(1L);
        assertThat(result.get(0).getCameraNumber()).isEqualTo("CAM-001");
        assertThat(result.get(0).getLocation()).isEqualTo("POINT(126.97 37.55)");
        assertThat(result.get(0).getDirection()).isEqualTo("북");
        assertThat(result.get(0).getStatus()).isEqualTo("active");
        assertThat(result.get(0).getCreatedAt()).isEqualTo("2024-01-01T00:00:00Z");
    }

    @Test
    @DisplayName("findAll(streamId) - reader의 GET /trails?stream_id= 를 호출한다")
    void findAll_withStreamId_callsFilteredEndpoint() {
        // given
        RestClient readerClient = mock(RestClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        RestClient writerClient = mock(RestClient.class);
        TrailView view = new TrailView(1L, 1L, "CAM-001", "POINT(126.97 37.55)", "북", "active",
                Instant.parse("2024-01-01T00:00:00Z"));

        given(readerClient.get()
                .uri("/trails?stream_id={streamId}", 1L)
                .retrieve()
                .body(any(org.springframework.core.ParameterizedTypeReference.class)))
                .willReturn(List.of(view));

        TrailServiceImpl service = new TrailServiceImpl(readerClient, writerClient);

        // when
        List<Trail> result = service.findAll(1L);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStreamId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("findById() - reader가 404를 주면 빈 Optional을 반환한다")
    void findById_returnsEmptyOnReader404() {
        // given
        RestClient readerClient = mock(RestClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        RestClient writerClient = mock(RestClient.class);

        given(readerClient.get()
                .uri("/trails/{id}", 999L)
                .retrieve()
                .body(TrailView.class))
                .willThrow(org.springframework.web.client.HttpClientErrorException.NotFound.class);

        TrailServiceImpl service = new TrailServiceImpl(readerClient, writerClient);

        // when
        Optional<Trail> result = service.findById(999L);

        // then
        assertThat(result).isEmpty();
    }

    // writerClient.post().uri(...).contentType(...).body(...).retrieve() 체인을 deep-stub 대신
    // 단계별로 직접 mock해서 연결한다 (Stream 작업에서 deep-stub 체이닝이 실제 프로덕션 호출과
    // 어긋나 null을 반환하는 문제가 있었음 - 각 인터페이스를 명시적으로 mock하면 확실히 매칭된다).
    private RestClient.ResponseSpec stubWriterCreateCall(RestClient writerClient) {
        RestClient.RequestBodyUriSpec bodyUriSpec =
                mock(RestClient.RequestBodyUriSpec.class, org.mockito.Answers.RETURNS_SELF);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        given(writerClient.post()).willReturn(bodyUriSpec);
        given(bodyUriSpec.retrieve()).willReturn(responseSpec);
        return responseSpec;
    }

    @Test
    @DisplayName("create() - writer의 POST /internal/trails 응답을 Trail 모델로 변환한다")
    void create_mapsWriterResponseToTrail() {
        // given
        RestClient readerClient = mock(RestClient.class);
        RestClient writerClient = mock(RestClient.class);
        TrailView created = new TrailView(1L, 1L, "CAM-001", "POINT(126.97 37.55)", "북", "active",
                Instant.parse("2024-01-01T00:00:00Z"));

        RestClient.ResponseSpec responseSpec = stubWriterCreateCall(writerClient);
        given(responseSpec.body(TrailView.class)).willReturn(created);

        TrailServiceImpl service = new TrailServiceImpl(readerClient, writerClient);
        Trail input = new Trail(null, 1L, "CAM-001", "POINT(126.97 37.55)", "북", "active", null);

        // when
        Trail result = service.create(input);

        // then
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getStreamId()).isEqualTo(1L);
        assertThat(result.getCameraNumber()).isEqualTo("CAM-001");
        assertThat(result.getStatus()).isEqualTo("active");
        assertThat(result.getCreatedAt()).isEqualTo("2024-01-01T00:00:00Z");
    }

    @Test
    @DisplayName("create() - writer가 400을 반환하면 InvalidTrailGeometryException을 던진다")
    void create_throwsInvalidTrailGeometryExceptionOnWriter400() {
        // given
        RestClient readerClient = mock(RestClient.class);
        RestClient writerClient = mock(RestClient.class);

        RestClient.ResponseSpec responseSpec = stubWriterCreateCall(writerClient);
        given(responseSpec.body(TrailView.class))
                .willThrow(org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Bad Request",
                        org.springframework.http.HttpHeaders.EMPTY,
                        "Invalid trail data".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.UTF_8));

        TrailServiceImpl service = new TrailServiceImpl(readerClient, writerClient);
        Trail input = new Trail(null, 1L, "CAM-001", "NOT-A-WKT", "북", "active", null);

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                InvalidTrailGeometryException.class,
                () -> service.create(input));
    }

    @Test
    @DisplayName("create() - writer가 409를 반환하면 DuplicateTrailException을 던진다")
    void create_throwsDuplicateTrailExceptionOnWriter409() {
        // given
        RestClient readerClient = mock(RestClient.class);
        RestClient writerClient = mock(RestClient.class);

        RestClient.ResponseSpec responseSpec = stubWriterCreateCall(writerClient);
        given(responseSpec.body(TrailView.class))
                .willThrow(org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "Conflict",
                        org.springframework.http.HttpHeaders.EMPTY,
                        "stream_id=1, camera_number=CAM-001 already exists".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.UTF_8));

        TrailServiceImpl service = new TrailServiceImpl(readerClient, writerClient);
        Trail input = new Trail(null, 1L, "CAM-001", "POINT(126.97 37.55)", "북", "active", null);

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                DuplicateTrailException.class,
                () -> service.create(input));
    }

    @Test
    @DisplayName("create() - writer가 null 응답을 반환하면 InvalidTrailGeometryException을 던진다")
    void create_throwsInvalidTrailGeometryExceptionOnNullResponse() {
        // given
        RestClient readerClient = mock(RestClient.class);
        RestClient writerClient = mock(RestClient.class);

        RestClient.ResponseSpec responseSpec = stubWriterCreateCall(writerClient);
        given(responseSpec.body(TrailView.class)).willReturn(null);

        TrailServiceImpl service = new TrailServiceImpl(readerClient, writerClient);
        Trail input = new Trail(null, 1L, "CAM-001", "POINT(126.97 37.55)", "북", "active", null);

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                InvalidTrailGeometryException.class,
                () -> service.create(input));
    }
}
