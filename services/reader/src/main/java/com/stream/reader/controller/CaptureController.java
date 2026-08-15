package com.stream.reader.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stream.reader.repository.CaptureRepository;
import com.stream.shared.dto.CaptureView;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/captures")
public class CaptureController {

    private final CaptureRepository captureRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public CaptureController(CaptureRepository captureRepository,
                              StringRedisTemplate redisTemplate,
                              ObjectMapper objectMapper) {
        this.captureRepository = captureRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────────────────────
    // 전체 조회 (PostgreSQL)
    // ─────────────────────────────────────────
    @GetMapping
    public List<CaptureView> getAll() {
        return captureRepository.findAll().stream()
                .map(CaptureView::from)
                .toList();
    }

    // ─────────────────────────────────────────
    // trailId로 최신 결과 조회
    // Redis 캐시 우선 → 없으면 PostgreSQL에서 진짜 최신 1건 조회 → 캐시 재적재
    // ─────────────────────────────────────────
    @GetMapping("/trail/{trailId}/latest")
    public Object getLatestByTrail(@PathVariable Integer trailId) {
        String redisKey = "capture:latest:trail:" + trailId;
        String cached = redisTemplate.opsForValue().get(redisKey);

        if (cached != null) {
            System.out.println("[reader] Redis 캐시 히트: " + redisKey);
            return Map.of("source", "redis", "data", cached);
        }

        System.out.println("[reader] Redis 캐시 미스 → PostgreSQL 조회");
        Optional<CaptureView> latest = captureRepository.findFirstByTrailIdOrderByCreatedAtDesc(trailId)
                .map(CaptureView::from);

        if (latest.isPresent()) {
            try {
                redisTemplate.opsForValue().set(redisKey, objectMapper.writeValueAsString(latest.get()));
                System.out.println("[reader] Redis 캐시 재적재 완료: " + redisKey);
            } catch (JsonProcessingException e) {
                System.err.println("[reader] Redis 캐시 재적재 실패: " + e.getMessage());
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("source", "postgresql");
        response.put("data", latest.orElse(null));
        return response;
    }
}
