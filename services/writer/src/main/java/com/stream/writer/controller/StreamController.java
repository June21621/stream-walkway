package com.stream.writer.controller;

import com.stream.shared.dto.StreamView;
import com.stream.writer.command.CreateStreamCommand;
import com.stream.writer.command.StreamCommandHandler;
import org.locationtech.jts.io.ParseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

// ─────────────────────────────────────────
// 내부 전용 엔드포인트 — backend가 Stream 등록 요청을 동기 HTTP로 위임하는 대상.
// Capture와 달리 Kafka 이벤트가 아니라 backend의 직접 호출로 생성된다.
// ─────────────────────────────────────────
@RestController
@RequestMapping("/internal/streams")
public class StreamController {

    private final StreamCommandHandler streamCommandHandler;

    public StreamController(StreamCommandHandler streamCommandHandler) {
        this.streamCommandHandler = streamCommandHandler;
    }

    @PostMapping
    public ResponseEntity<StreamView> create(@RequestBody CreateStreamCommand command) throws ParseException {
        var saved = streamCommandHandler.handle(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(StreamView.from(saved));
    }

    @ExceptionHandler({ParseException.class, ClassCastException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> handleInvalidStreamData(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid stream data: " + e.getMessage()));
    }
}
