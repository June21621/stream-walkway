package com.stream.backend.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 캡처 작업 요청. 새 엔드포인트라 기존 계약이 없어서 여기서 이름을 짓는다.
 *
 * <p>{@code source_url}로 부른다. YouTube 어댑터는 이용약관 때문에 설계 단계에서
 * 만들지 않기로 했으므로 {@code youtube_url}은 사실과 다른 이름이다.
 * youtube-service를 호출할 때만 그쪽의 옛 이름으로 매핑한다.
 */
public record CaptureJobRequest(
        @JsonProperty("stream_id") Long streamId,
        @JsonProperty("trail_id") Long trailId,
        @JsonProperty("source_url") String sourceUrl) {
}
