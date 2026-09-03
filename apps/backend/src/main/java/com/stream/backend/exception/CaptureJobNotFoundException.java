package com.stream.backend.exception;

/** 그런 jobId가 없다. Redis TTL이 지나 사라진 경우도 여기로 온다. */
public class CaptureJobNotFoundException extends RuntimeException {
    private final String jobId;

    public CaptureJobNotFoundException(String jobId) {
        super("Capture job not found: " + jobId);
        this.jobId = jobId;
    }

    public String getJobId() {
        return jobId;
    }
}
