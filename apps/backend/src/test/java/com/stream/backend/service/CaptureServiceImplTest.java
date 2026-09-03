package com.stream.backend.service;

import com.stream.backend.exception.CaptureJobFailedException;
import com.stream.backend.exception.InvalidCaptureJobException;
import com.stream.backend.model.Capture;
import com.stream.backend.model.CaptureJob;
import com.stream.backend.model.CaptureJobRequest;
import com.stream.shared.dto.CaptureView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

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

        CaptureServiceImpl service = new CaptureServiceImpl(readerClient, mock(RestClient.class));

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

        CaptureServiceImpl service = new CaptureServiceImpl(readerClient, mock(RestClient.class));

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

        CaptureServiceImpl service = new CaptureServiceImpl(readerClient, mock(RestClient.class));

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

        CaptureServiceImpl service = new CaptureServiceImpl(readerClient, mock(RestClient.class));

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

        CaptureServiceImpl service = new CaptureServiceImpl(readerClient, mock(RestClient.class));

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

        CaptureServiceImpl service = new CaptureServiceImpl(readerClient, mock(RestClient.class));

        // when
        Optional<Capture> result = service.findById(1L);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().getCreatedAt()).isNull();
        assertThat(result.get().getUpdatedAt()).isNull();
    }

    // ─────────────────────────────────────────
    // createJob / findJob — youtube-service 프록시
    //
    // 여기서만 MockRestServiceServer를 쓴다. 위 조회 테스트들이 쓰는 딥 스텁
    // 목은 나가는 JSON 본문을 볼 수 없는데, 이 경로의 핵심이 바로 그 본문
    // (source_url -> youtube_url 매핑)이라 실제 직렬화를 확인해야 한다.
    // ─────────────────────────────────────────

    private record Fixture(CaptureServiceImpl service, MockRestServiceServer server) {}

    private static Fixture youtubeFixture() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://youtube-service:3000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new CaptureServiceImpl(mock(RestClient.class), builder.build()), server);
    }

    @Test
    @DisplayName("createJob() - source_url을 youtube-service의 youtube_url로 매핑해 POST /download를 호출한다")
    void createJob_mapsSourceUrlToYoutubeUrl() {
        // given
        Fixture f = youtubeFixture();
        f.server().expect(requestTo("http://youtube-service:3000/download"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.stream_id").value(1))
                .andExpect(jsonPath("$.trail_id").value(2))
                .andExpect(jsonPath("$.youtube_url").value("https://example.com/s.m3u8"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.ACCEPTED)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .body("""
                                {"jobId":"job-1","status":"pending","stream_id":1,
                                 "trail_id":2,"youtube_url":"https://example.com/s.m3u8"}
                                """));

        // when
        CaptureJob job = f.service().createJob(
                new CaptureJobRequest(1L, 2L, "https://example.com/s.m3u8"));

        // then
        assertThat(job.jobId()).isEqualTo("job-1");
        assertThat(job.status()).isEqualTo("pending");
        // 트리거 응답에는 진행률이 없다. 응답에 없는 키가 0으로 채워지면 안 된다.
        assertThat(job.progress()).isNull();
        assertThat(job.downloadedCount()).isNull();
        f.server().verify();
    }

    @Test
    @DisplayName("createJob() - 필수 필드가 없으면 호출 전에 InvalidCaptureJobException을 던진다")
    void createJob_rejectsMissingFields() {
        // given
        Fixture f = youtubeFixture();

        // when & then - 서버 기대를 하나도 등록하지 않았으므로 호출이 나가면 실패한다
        assertThatThrownBy(() -> f.service().createJob(new CaptureJobRequest(1L, 2L, null)))
                .isInstanceOf(InvalidCaptureJobException.class)
                .hasMessageContaining("source_url");
        f.server().verify();
    }

    @Test
    @DisplayName("createJob() - youtube-service가 5xx면 CaptureJobFailedException으로 바꾼다")
    void createJob_wrapsServerError() {
        // given
        Fixture f = youtubeFixture();
        f.server().expect(requestTo("http://youtube-service:3000/download"))
                .andRespond(withServerError().body("{\"error\":\"failed to create job\"}"));

        // when & then
        assertThatThrownBy(() -> f.service().createJob(
                new CaptureJobRequest(1L, 2L, "https://example.com/s.m3u8")))
                .isInstanceOf(CaptureJobFailedException.class);
    }

    @Test
    @DisplayName("findJob() - youtube-service의 상태 응답을 CaptureJob으로 옮긴다")
    void findJob_mapsStatusResponse() {
        // given
        Fixture f = youtubeFixture();
        f.server().expect(requestTo("http://youtube-service:3000/status/job-1"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"jobId":"job-1","status":"completed","progress":100,"downloaded_count":1}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        // when
        Optional<CaptureJob> job = f.service().findJob("job-1");

        // then
        assertThat(job).isPresent();
        assertThat(job.get().status()).isEqualTo("completed");
        assertThat(job.get().progress()).isEqualTo(100);
        assertThat(job.get().downloadedCount()).isEqualTo(1);
        f.server().verify();
    }

    @Test
    @DisplayName("findJob() - youtube-service가 404면 빈 Optional을 준다")
    void findJob_returnsEmptyOn404() {
        // given
        Fixture f = youtubeFixture();
        f.server().expect(requestTo("http://youtube-service:3000/status/nope"))
                .andRespond(withResourceNotFound().body("{\"error\":\"Job not found\"}"));

        // when & then
        assertThat(f.service().findJob("nope")).isEmpty();
    }
}
