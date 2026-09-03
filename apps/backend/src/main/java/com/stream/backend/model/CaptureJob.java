package com.stream.backend.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 캡처 작업의 상태. 트리거 응답과 조회 응답 둘 다 이 모양으로 낸다.
 *
 * <p>트리거 직후에는 progress/downloadedCount/error가 없으므로 NON_NULL로
 * 숨긴다. 필드 이름은 youtube-service의 {@code GET /status/:jobId} 응답을
 * 그대로 따른다 - 게이트웨이가 모양을 다시 정의할 이유가 없다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaptureJob(
        String jobId,
        String status,
        Integer progress,
        @JsonProperty("downloaded_count") Integer downloadedCount,
        String error) {
}
