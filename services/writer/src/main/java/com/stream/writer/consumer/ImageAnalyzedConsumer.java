package com.stream.writer.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stream.writer.entity.Capture;
import com.stream.writer.repository.CaptureRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ImageAnalyzedConsumer {

    private final CaptureRepository captureRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ImageAnalyzedConsumer(CaptureRepository captureRepository,
                                  StringRedisTemplate redisTemplate,
                                  ObjectMapper objectMapper) {
        this.captureRepository = captureRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────────────────────
    // image.analyzed 토픽 구독
    // 메시지 수신 → PostgreSQL 저장 → Redis 캐싱
    // ─────────────────────────────────────────
    @KafkaListener(topics = "image.analyzed", groupId = "writer-group")
    public void consume(String message) {
        try {
            Map<String, Object> data = objectMapper.readValue(message, Map.class);
            System.out.println("[writer] 메시지 수신: " + data);

            // PostgreSQL 저장
            Capture capture = new Capture();
            capture.setTrailId(Integer.valueOf(data.get("trailId").toString()));
            capture.setStreamId(Integer.valueOf(data.get("streamId").toString()));
            capture.setImagePath(data.get("imagePath").toString());
            capture.setRoadStatus(data.get("roadStatus").toString());
            capture.setConfidence(Double.valueOf(data.get("confidence").toString()));

            Capture saved = captureRepository.save(capture);
            System.out.println("[writer] PostgreSQL 저장 완료 id=" + saved.getId());

            // Redis 캐싱 (최신 결과를 빠르게 조회하기 위해)
            String redisKey = "capture:latest:trail:" + saved.getTrailId();
            String redisValue = objectMapper.writeValueAsString(data);
            redisTemplate.opsForValue().set(redisKey, redisValue);
            System.out.println("[writer] Redis 캐싱 완료 key=" + redisKey);

        } catch (Exception e) {
            System.err.println("[writer] 처리 실패: " + e.getMessage());
        }
    }
}
