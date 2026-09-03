package com.stream.backend.exception;

/** youtube-service가 응답하지 못했다. 게이트웨이는 이것을 502로 낸다. */
public class CaptureJobFailedException extends RuntimeException {
    public CaptureJobFailedException(String message) {
        super(message);
    }
}
