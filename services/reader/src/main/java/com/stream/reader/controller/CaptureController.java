package com.stream.reader.controller;

import com.stream.reader.dto.CaptureView;
import com.stream.reader.repository.CaptureRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/captures")
public class CaptureController {

    private final CaptureRepository captureRepository;
    private final StringRedisTemplate redisTemplate;

    public CaptureController(CaptureRepository captureRepository,
                              StringRedisTemplate redisTemplate) {
        this.captureRepository = captureRepository;
        this.redisTemplate = redisTemplate;
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
    // Redis 캐시 우선 → 없으면 PostgreSQL 조회
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
        List<CaptureView> views = captureRepository.findByTrailId(trailId).stream()
                .map(CaptureView::from)
                .toList();
        return Map.of("source", "postgresql", "data", views);
    }
}
