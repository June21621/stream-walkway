package com.stream.writer.controller;

import com.stream.shared.dto.TrailView;
import com.stream.writer.command.CreateTrailCommand;
import com.stream.writer.command.TrailCommandHandler;
import com.stream.writer.exception.DuplicateTrailException;
import org.locationtech.jts.io.ParseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// ─────────────────────────────────────────
// 내부 전용 엔드포인트 — backend가 Trail 등록 요청을 동기 HTTP로 위임하는 대상.
// Stream과 동일하게 Kafka 이벤트가 아니라 직접 호출로 생성된다.
// ─────────────────────────────────────────
@RestController
@RequestMapping("/internal/trails")
public class TrailController {

    private final TrailCommandHandler trailCommandHandler;

    public TrailController(TrailCommandHandler trailCommandHandler) {
        this.trailCommandHandler = trailCommandHandler;
    }

    @PostMapping
    public ResponseEntity<TrailView> create(@RequestBody CreateTrailCommand command) throws ParseException {
        var saved = trailCommandHandler.handle(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(TrailView.from(saved));
    }

    @ExceptionHandler({ParseException.class, ClassCastException.class, IllegalArgumentException.class})
    public ResponseEntity<java.util.Map<String, String>> handleInvalidTrailData(Exception e) {
        return ResponseEntity.badRequest().body(java.util.Map.of("error", "Invalid trail data: " + e.getMessage()));
    }

    @ExceptionHandler(DuplicateTrailException.class)
    public ResponseEntity<java.util.Map<String, String>> handleDuplicateTrail(DuplicateTrailException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(java.util.Map.of("error", "Duplicate trail", "message", e.getMessage()));
    }
}
