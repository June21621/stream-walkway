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

    @ExceptionHandler(InvalidTrailGeometryException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidTrailGeometry(InvalidTrailGeometryException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Invalid trail data", "message", e.getMessage()));
    }

    @ExceptionHandler(DuplicateTrailException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateTrail(DuplicateTrailException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Duplicate trail", "message", e.getMessage()));
    }
}
