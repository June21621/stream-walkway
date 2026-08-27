package com.stream.backend.service;

import com.stream.backend.model.Capture;
import com.stream.shared.dto.CaptureView;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

@Service
public class CaptureServiceImpl implements CaptureService {

    private final RestClient readerClient;

    // writerRestClient를 주입하지 않는다. 캡처는 Kafka image.analyzed 이벤트로만
    // 생성되고 HTTP 생성 경로가 설계상 없다 — CaptureService 인터페이스에
    // create가 없는 것과 같은 이유다. Stream/Trail 서비스와 다른 점이다.
    public CaptureServiceImpl(@Qualifier("readerRestClient") RestClient readerClient) {
        this.readerClient = readerClient;
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
}
