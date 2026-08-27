package com.stream.reader.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stream.reader.repository.CaptureRepository;
import com.stream.shared.dto.CaptureView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/captures")
public class CaptureController {

    private static final Logger log = LoggerFactory.getLogger(CaptureController.class);

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

    // 정렬 키 화이트리스트. API 명세에 created_at 하나뿐이고, 임의 문자열을
    // Sort.by()에 그대로 넘기면 엔티티에 없는 속성명일 때 500이 난다.
    // 값은 JPA 엔티티의 속성명이다(DB 컬럼명 created_at이 아니라 createdAt).
    private static final Map<String, String> SORT_KEYS = Map.of("created_at", "createdAt");

    // 애노테이션 defaultValue는 String 컴파일 타임 상수만 받는다.
    private static final String DEFAULT_LIMIT = "20";

    // ─────────────────────────────────────────
    // 목록 조회 (PostgreSQL)
    // stream_id / trail_id 필터, limit 개수 제한, sort 정렬 키를 받는다.
    // 넷 다 선택적이며 기본값은 전체 / 20건 / createdAt 내림차순이다.
    // ─────────────────────────────────────────
    @GetMapping
    public List<CaptureView> getAll(
            @RequestParam(value = "stream_id", required = false) Integer streamId,
            @RequestParam(value = "trail_id", required = false) Integer trailId,
            @RequestParam(value = "limit", required = false, defaultValue = DEFAULT_LIMIT) Integer limit,
            @RequestParam(value = "sort", required = false, defaultValue = "created_at") String sort) {

        String property = SORT_KEYS.get(sort);
        if (property == null) {
            throw new IllegalArgumentException(
                    "Unsupported sort key: " + sort + " (supported: " + SORT_KEYS.keySet() + ")");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }

        // 최신순. 캡처는 최근 관측이 먼저 보여야 한다.
        Pageable pageable = PageRequest.of(0, limit, Sort.by(property).descending());

        return captureRepository.findFiltered(streamId, trailId, pageable).stream()
                .map(CaptureView::from)
                .toList();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleInvalidQuery(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    // ─────────────────────────────────────────
    // 단건 조회 (PostgreSQL)
    // 본문 없는 404를 낸다 — 에러 본문 조립은 게이트웨이(backend) 책임이며,
    // backend가 HttpClientErrorException.NotFound를 잡아 Optional.empty()로 바꾼다.
    // ─────────────────────────────────────────
    @GetMapping("/{id}")
    public ResponseEntity<CaptureView> getById(@PathVariable Long id) {
        return captureRepository.findById(id)
                .map(CaptureView::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
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
            log.debug("Redis 캐시 히트 key={}", redisKey);
            return Map.of("source", "redis", "data", cached);
        }

        log.debug("Redis 캐시 미스, PostgreSQL 조회 key={}", redisKey);
        Optional<CaptureView> latest = captureRepository.findFirstByTrailIdOrderByCreatedAtDesc(trailId)
                .map(CaptureView::from);

        if (latest.isPresent()) {
            try {
                redisTemplate.opsForValue().set(redisKey, objectMapper.writeValueAsString(latest.get()));
                log.debug("Redis 캐시 재적재 완료 key={}", redisKey);
            } catch (JsonProcessingException e) {
                log.warn("Redis 캐시 재적재 실패 key={}", redisKey, e);
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("source", "postgresql");
        response.put("data", latest.orElse(null));
        return response;
    }
}
