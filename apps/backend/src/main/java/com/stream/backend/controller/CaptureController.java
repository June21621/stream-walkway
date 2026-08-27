package com.stream.backend.controller;

import com.stream.backend.exception.CaptureNotFoundException;
import com.stream.backend.model.Capture;
import com.stream.backend.service.CaptureService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/captures")
public class CaptureController {

    private final CaptureService captureService;

    public CaptureController(CaptureService captureService) {
        this.captureService = captureService;
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
}
