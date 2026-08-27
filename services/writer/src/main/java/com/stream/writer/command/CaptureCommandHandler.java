package com.stream.writer.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stream.shared.dto.CaptureView;
import com.stream.shared.entity.Capture;
import com.stream.writer.repository.CaptureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class CaptureCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CaptureCommandHandler.class);

    private final CaptureRepository captureRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public CaptureCommandHandler(CaptureRepository captureRepository,
                                  StringRedisTemplate redisTemplate,
                                  ObjectMapper objectMapper) {
        this.captureRepository = captureRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────────────────────
    // CreateCaptureCommand 처리 → PostgreSQL 저장 → Redis에 CaptureView로 캐싱
    // (reader와 동일한 CaptureView 모양으로 캐싱해야 캐시 미스 시 reader의
    //  재적재 로직과 페이로드 모양이 일치한다)
    // ─────────────────────────────────────────
    public Capture handle(CreateCaptureCommand command) {
        Capture capture = new Capture();
        capture.setTrailId(command.trailId());
        capture.setStreamId(command.streamId());
        capture.setImagePath(command.imagePath());
        capture.setRoadStatus(command.roadStatus());
        capture.setConfidence(command.confidence());

        Capture saved = captureRepository.save(capture);
        log.info("PostgreSQL 저장 완료 id={}", saved.getId());

        String redisKey = "capture:latest:trail:" + saved.getTrailId();
        try {
            String redisValue = objectMapper.writeValueAsString(CaptureView.from(saved));
            redisTemplate.opsForValue().set(redisKey, redisValue);
            log.info("Redis 캐싱 완료 key={}", redisKey);
        } catch (JsonProcessingException e) {
            log.warn("Redis 캐싱 실패 key={}", redisKey, e);
        }

        return saved;
    }
}
