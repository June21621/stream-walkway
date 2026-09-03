package com.stream.backend.service;

import com.stream.backend.model.Capture;
import com.stream.backend.model.CaptureJob;
import com.stream.backend.model.CaptureJobRequest;

import java.util.List;
import java.util.Optional;

public interface CaptureService {
    List<Capture> findAll(Long streamId, Long trailId, Integer limit, String sort);
    Optional<Capture> findById(Long id);

    /** youtube-service에 캡처 작업을 지시한다. 응답은 202 직후 상태다. */
    CaptureJob createJob(CaptureJobRequest request);

    /** 작업 상태를 조회한다. 없는 jobId면 빈 Optional. */
    Optional<CaptureJob> findJob(String jobId);
}
