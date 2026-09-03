package com.stream.backend.controller;

import com.stream.backend.exception.CaptureJobNotFoundException;
import com.stream.backend.exception.CaptureNotFoundException;
import com.stream.backend.exception.InvalidInternalKeyException;
import com.stream.backend.model.Capture;
import com.stream.backend.model.CaptureJob;
import com.stream.backend.model.CaptureJobRequest;
import com.stream.backend.service.CaptureService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/captures")
public class CaptureController {

    private final CaptureService captureService;
    private final String internalApiKey;

    public CaptureController(CaptureService captureService,
                             @Value("${internal.api-key}") String internalApiKey) {
        this.captureService = captureService;
        this.internalApiKey = internalApiKey;
    }

    // limit/sort 기본값은 여기서 정한다. 서비스 계층이 아니라 HTTP 경계에서
    // 채우는 편이 Stream/Trail과 일관되고, 서비스는 받은 값을 그대로 reader에
    // 넘기기만 하면 된다.
    //
    // value = "stream_id"를 반드시 명시한다. 파라미터 이름(streamId)만으로는
    // ?stream_id=1 쿼리와 바인딩되지 않는다 - TrailController에서 같은 누락이
    // 발견된 적이 있다.
    @GetMapping
    public ResponseEntity<List<Capture>> getAll(
            @RequestParam(value = "stream_id", required = false) Long streamId,
            @RequestParam(value = "trail_id", required = false) Long trailId,
            @RequestParam(value = "limit", defaultValue = "20") Integer limit,
            @RequestParam(value = "sort", defaultValue = "created_at") String sort) {
        return ResponseEntity.ok(captureService.findAll(streamId, trailId, limit, sort));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        Capture capture = captureService.findById(id)
                .orElseThrow(() -> new CaptureNotFoundException(id));
        return ResponseEntity.ok(capture);
    }

    // ─────────────────────────────────────────
    // 캡처 파이프라인의 입구.
    //
    // 명령은 HTTP로 보내고 그 뒤(분석 → 저장)는 Kafka로 흐른다. 게이트웨이는
    // 202 + jobId를 받고 끝이라 youtube-service와 시간적으로 묶이지 않는다.
    //
    // "15분마다"를 세는 스케줄러는 여기 없다. 지금 데이터 출처가 녹화된
    // 영상이라 벽시계 주기가 의미를 갖지 않기 때문이다 — 설계 문서의
    // 후속 작업 참고. 이 엔드포인트는 호출될 때마다 한 번 뜬다.
    // ─────────────────────────────────────────

    @PostMapping("/jobs")
    public ResponseEntity<CaptureJob> createJob(@RequestBody CaptureJobRequest request,
                                                @RequestHeader("X-Internal-Key") String internalKey) {
        if (!internalApiKey.equals(internalKey)) {
            throw new InvalidInternalKeyException();
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(captureService.createJob(request));
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<CaptureJob> getJob(@PathVariable String jobId) {
        CaptureJob job = captureService.findJob(jobId)
                .orElseThrow(() -> new CaptureJobNotFoundException(jobId));
        return ResponseEntity.ok(job);
    }
}
