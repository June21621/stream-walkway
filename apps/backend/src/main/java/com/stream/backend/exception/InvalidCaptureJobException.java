package com.stream.backend.exception;

/** 캡처 작업 요청에 필수 필드가 없다. */
public class InvalidCaptureJobException extends RuntimeException {
    public InvalidCaptureJobException(String message) {
        super(message);
    }
}
