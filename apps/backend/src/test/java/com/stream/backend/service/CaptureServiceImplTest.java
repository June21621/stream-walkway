package com.stream.backend.service;

import com.stream.backend.model.Capture;
import com.stream.shared.dto.CaptureView;
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
@DisplayName("Backend - CaptureServiceImpl 테스트")
class CaptureServiceImplTest {

    private static CaptureView view() {
        return new CaptureView(1L, 2, 3, "/images/capture_001.jpg", "양호", 0.95,
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-01-02T00:00:00Z"));
    }

    @Test
    @DisplayName("findAll() - reader 응답을 Capture 모델 리스트로 변환한다")
    void findAll_mapsReaderResponseToCaptureList() {
        // given
        RestClient readerClient = mock(RestClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        given(readerClient.get()
                .uri("/captures?stream_id={streamId}&trail_id={trailId}&limit={limit}&sort={sort}",
                        3L, 2L, 20, "created_at")
                .retrieve()
                .body(any(org.springframework.core.ParameterizedTypeReference.class)))
                .willReturn(List.of(view()));

        CaptureServiceImpl service = new CaptureServiceImpl(readerClient);

        // when
        List<Capture> result = service.findAll(3L, 2L, 20, "created_at");

        // then
        assertThat(result).hasSize(1);
        Capture capture = result.get(0);
        assertThat(capture.getId()).isEqualTo(1L);
        assertThat(capture.getTrailId()).isEqualTo(2L);
        assertThat(capture.getStreamId()).isEqualTo(3L);
        assertThat(capture.getImagePath()).isEqualTo("/images/capture_001.jpg");
        assertThat(capture.getRoadStatus()).isEqualTo("양호");
        assertThat(capture.getConfidence()).isEqualTo(0.95);
        // createdAt과 updatedAt에 서로 다른 날짜를 쓴다. 같은 값이면 한 값을
        // 두 자리에 넣는 실수를 잡지 못한다.
        assertThat(capture.getCreatedAt()).isEqualTo("2024-01-01T00:00:00Z");
        assertThat(capture.getUpdatedAt()).isEqualTo("2024-01-02T00:00:00Z");
    }

    @Test
    @DisplayName("findAll() - reader가 본문 없이 응답하면 빈 리스트를 반환한다")
    void findAll_returnsEmptyListWhenBodyIsNull() {
        // given
        RestClient readerClient = mock(RestClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        given(readerClient.get()
                .uri("/captures?stream_id={streamId}&trail_id={trailId}&limit={limit}&sort={sort}",
                        null, null, 20, "created_at")
                .retrieve()
                .body(any(org.springframework.core.ParameterizedTypeReference.class)))
                .willReturn(null);

        CaptureServiceImpl service = new CaptureServiceImpl(readerClient);

        // when
        List<Capture> result = service.findAll(null, null, 20, "created_at");

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findById() - reader의 GET /captures/{id} 응답을 Capture 모델로 변환한다")
    void findById_mapsReaderResponseToCapture() {
        // given
        RestClient readerClient = mock(RestClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        given(readerClient.get()
                .uri("/captures/{id}", 1L)
                .retrieve()
                .body(CaptureView.class))
                .willReturn(view());

        CaptureServiceImpl service = new CaptureServiceImpl(readerClient);

        // when
        Optional<Capture> result = service.findById(1L);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(1L);
        assertThat(result.get().getUpdatedAt()).isEqualTo("2024-01-02T00:00:00Z");
    }

    @Test
    @DisplayName("findById() - reader가 404를 주면 Optional.empty()를 반환한다")
    void findById_returnsEmptyWhenReaderReturns404() {
        // given
        RestClient readerClient = mock(RestClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        given(readerClient.get()
                .uri("/captures/{id}", 999L)
                .retrieve()
                .body(CaptureView.class))
                .willThrow(org.springframework.web.client.HttpClientErrorException.NotFound.class);

        CaptureServiceImpl service = new CaptureServiceImpl(readerClient);

        // when
        Optional<Capture> result = service.findById(999L);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findById() - reader가 본문 없이 200을 주면 Optional.empty()를 반환한다")
    void findById_returnsEmptyWhenBodyIsNull() {
        // given
        RestClient readerClient = mock(RestClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        given(readerClient.get()
                .uri("/captures/{id}", 1L)
                .retrieve()
                .body(CaptureView.class))
                .willReturn(null);

        CaptureServiceImpl service = new CaptureServiceImpl(readerClient);

        // when
        Optional<Capture> result = service.findById(1L);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("변환 - createdAt/updatedAt이 null이면 null을 유지한다")
    void mapping_keepsNullTimestamps() {
        // given
        RestClient readerClient = mock(RestClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        CaptureView noTimestamps = new CaptureView(1L, 2, 3, "/p.jpg", "양호", 0.95, null, null);
        given(readerClient.get()
                .uri("/captures/{id}", 1L)
                .retrieve()
                .body(CaptureView.class))
                .willReturn(noTimestamps);

        CaptureServiceImpl service = new CaptureServiceImpl(readerClient);

        // when
        Optional<Capture> result = service.findById(1L);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().getCreatedAt()).isNull();
        assertThat(result.get().getUpdatedAt()).isNull();
    }
}
