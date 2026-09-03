package com.stream.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(StreamNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleStreamNotFound(StreamNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Stream not found", "id", e.getId()));
    }

    @ExceptionHandler(InvalidStreamGeometryException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidStreamGeometry(InvalidStreamGeometryException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Invalid stream geometry", "message", e.getMessage()));
    }

    @ExceptionHandler(InvalidInternalKeyException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidInternalKey(InvalidInternalKeyException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Unauthorized", "message", e.getMessage()));
    }

    @ExceptionHandler(TrailNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleTrailNotFound(TrailNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Trail not found", "id", e.getId()));
    }

    @ExceptionHandler(CaptureNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleCaptureNotFound(CaptureNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Capture not found", "id", e.getId()));
    }

    @ExceptionHandler(InvalidTrailGeometryException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidTrailGeometry(InvalidTrailGeometryException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Invalid trail data", "message", e.getMessage()));
    }

    @ExceptionHandler(CaptureJobNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleCaptureJobNotFound(CaptureJobNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Capture job not found", "jobId", e.getJobId()));
    }

    @ExceptionHandler(InvalidCaptureJobException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidCaptureJob(InvalidCaptureJobException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Invalid capture job request", "message", e.getMessage()));
    }

    // youtube-service가 응답하지 못한 것이지 클라이언트 잘못이 아니다.
    @ExceptionHandler(CaptureJobFailedException.class)
    public ResponseEntity<Map<String, Object>> handleCaptureJobFailed(CaptureJobFailedException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Capture service unavailable", "message", e.getMessage()));
    }

    @ExceptionHandler(DuplicateTrailException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateTrail(DuplicateTrailException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Duplicate trail", "message", e.getMessage()));
    }
}
