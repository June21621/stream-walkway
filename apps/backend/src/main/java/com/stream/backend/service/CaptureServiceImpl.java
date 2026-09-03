package com.stream.backend.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.stream.backend.exception.CaptureJobFailedException;
import com.stream.backend.exception.InvalidCaptureJobException;
import com.stream.backend.model.Capture;
import com.stream.backend.model.CaptureJob;
import com.stream.backend.model.CaptureJobRequest;
import com.stream.shared.dto.CaptureView;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class CaptureServiceImpl implements CaptureService {

    // CAPTURE_SOURCE=hls면 이 값이 youtube-service에서 ffmpeg -i 인자가 된다.
    // ffmpeg는 file:/concat: 같은 스킴도 받고 그렇게 읽은 프레임은 공개 버킷에
    // 올라가므로, 스킴을 좁히지 않으면 임의 파일 읽기 겸 유출 경로가 된다.
    // youtube-service에 -protocol_whitelist가 없어 여기가 유일한 관문이다.
    private static final Pattern SUPPORTED_SCHEME =
            Pattern.compile("^(?:https?|rtsps?)://.+", Pattern.CASE_INSENSITIVE);

    private final RestClient readerClient;
    private final RestClient youtubeClient;

    // writerRestClient를 주입하지 않는다. 캡처 행(row)은 Kafka image.analyzed
    // 이벤트로만 생성되고 HTTP 생성 경로가 설계상 없다 — CaptureService에
    // create가 없는 것과 같은 이유다. Stream/Trail 서비스와 다른 점이다.
    //
    // 대신 youtubeClient가 있다. 이것은 행을 만드는 게 아니라 파이프라인의
    // 첫 단계를 지시하는 것이다. 명령은 HTTP로 보내고 그 뒤 단계(분석 → 저장)는
    // Kafka로 흐른다 — backend는 첫 단계만 지시하고 이후에 관여하지 않는다.
    public CaptureServiceImpl(@Qualifier("readerRestClient") RestClient readerClient,
                              @Qualifier("youtubeRestClient") RestClient youtubeClient) {
        this.readerClient = readerClient;
        this.youtubeClient = youtubeClient;
    }

    // CaptureView의 trailId/streamId는 Integer이고 backend 모델은 Long이라
    // 경계에서 변환한다. DB 컬럼이 captures.trail_id INTEGER(참조 대상
    // trails.id가 SERIAL=int4)라 reader 쪽 타입이 DB와 맞는 쪽이다.
    private static Capture toModel(CaptureView view) {
        return new Capture(
                view.id(),
                view.trailId() == null ? null : view.trailId().longValue(),
                view.streamId() == null ? null : view.streamId().longValue(),
                view.imagePath(),
                view.roadStatus(),
                view.confidence(),
                view.createdAt() == null ? null : view.createdAt().toString(),
                view.updatedAt() == null ? null : view.updatedAt().toString()
        );
    }

    @Override
    public List<Capture> findAll(Long streamId, Long trailId, Integer limit, String sort) {
        List<CaptureView> views = readerClient.get()
                .uri("/captures?stream_id={streamId}&trail_id={trailId}&limit={limit}&sort={sort}",
                        streamId, trailId, limit, sort)
                .retrieve()
                .body(new ParameterizedTypeReference<List<CaptureView>>() {});
        return views == null ? List.of() : views.stream().map(CaptureServiceImpl::toModel).toList();
    }

    @Override
    public Optional<Capture> findById(Long id) {
        try {
            CaptureView view = readerClient.get()
                    .uri("/captures/{id}", id)
                    .retrieve()
                    .body(CaptureView.class);
            return Optional.ofNullable(view).map(CaptureServiceImpl::toModel);
        } catch (HttpClientErrorException.NotFound e) {
            // reader는 본문 없는 404를 낸다. 에러 본문 조립은 게이트웨이 책임이라
            // 여기서는 빈 Optional로만 바꾸고, 404 본문은 컨트롤러가 만든다.
            return Optional.empty();
        }
    }

    @Override
    public CaptureJob createJob(CaptureJobRequest request) {
        // 나가기 전에 막는다. youtube-service도 같은 검사를 하지만, 그쪽 400 본문은
        // "youtube_url is required"라 우리가 공개한 필드 이름(source_url)과 다르다.
        require(request.streamId(), "stream_id");
        require(request.trailId(), "trail_id");
        require(request.sourceUrl(), "source_url");
        requireSupportedScheme(request.sourceUrl());

        CaptureJob job;
        try {
            job = youtubeClient.post()
                    .uri("/download")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new DownloadRequest(request.streamId(), request.trailId(), request.sourceUrl()))
                    .retrieve()
                    .body(CaptureJob.class);
        } catch (RestClientException e) {
            // 위 검사를 통과했는데도 400이 오면 두 서비스의 계약이 어긋난 것이다.
            // 그것은 클라이언트 잘못이 아니라 통합 버그이므로 다른 실패와 같이 502로 낸다.
            // 다운스트림 본문을 인용하지도 않는다 - 그쪽 400은 "youtube_url is required"라
            // 우리가 source_url로 이름을 바꾼 이유를 스스로 무너뜨린다.
            throw new CaptureJobFailedException("youtube-service call failed: " + e.getMessage());
        }
        if (job == null) {
            throw new CaptureJobFailedException("youtube-service returned an empty response");
        }
        return job;
    }

    @Override
    public Optional<CaptureJob> findJob(String jobId) {
        try {
            return Optional.ofNullable(youtubeClient.get()
                    .uri("/status/{jobId}", jobId)
                    .retrieve()
                    .body(CaptureJob.class));
        } catch (HttpClientErrorException.NotFound e) {
            // 404 본문은 게이트웨이가 다시 만든다. findById와 같은 방식이다.
            return Optional.empty();
        } catch (RestClientException e) {
            throw new CaptureJobFailedException("youtube-service call failed: " + e.getMessage());
        }
    }

    private static void requireSupportedScheme(String url) {
        if (!SUPPORTED_SCHEME.matcher(url).matches()) {
            throw new InvalidCaptureJobException("source_url must be an http(s) or rtsp(s) URL");
        }
    }

    private static void require(Object value, String field) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            throw new InvalidCaptureJobException(field + " is required");
        }
    }

    // youtube-service의 POST /download는 snake_case 이름을 그대로 요구한다.
    // source_url -> youtube_url 매핑이 일어나는 곳은 여기 한 군데뿐이다.
    private record DownloadRequest(
            @JsonProperty("stream_id") Long streamId,
            @JsonProperty("trail_id") Long trailId,
            @JsonProperty("youtube_url") String youtubeUrl) {}
}
