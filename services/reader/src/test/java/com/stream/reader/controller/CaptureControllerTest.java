package com.stream.reader.controller;

import com.stream.reader.entity.Capture;
import com.stream.reader.repository.CaptureRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CaptureController.class)
@DisplayName("Reader - CaptureController 테스트")
class CaptureControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CaptureRepository captureRepository;

    @MockBean
    private StringRedisTemplate redisTemplate;

    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        valueOperations = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
    }

    private Capture createCapture(Long id, Integer trailId, Integer streamId) {
        Capture capture = new Capture();
        // Capture는 setter가 없으므로 리플렉션 없이 필드를 검증하는 방식으로 테스트
        // 실제 구현 시 setter 또는 생성자 추가 필요
        return capture;
    }

    // ─────────────────────────────────────────
    // GET /captures
    // ─────────────────────────────────────────

    @Test
    @DisplayName("GET /captures - 전체 캡처 목록을 200 OK로 반환한다")
    void getAll_returns200WithCaptureList() throws Exception {
        // given
        Capture capture = new Capture();
        given(captureRepository.findAll()).willReturn(List.of(capture));

        // when & then
        mockMvc.perform(get("/captures"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET /captures - 캡처가 없으면 빈 배열을 200 OK로 반환한다")
    void getAll_returns200WithEmptyList() throws Exception {
        // given
        given(captureRepository.findAll()).willReturn(List.of());

        // when & then
        mockMvc.perform(get("/captures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    // ─────────────────────────────────────────
    // GET /captures/trail/{trailId}/latest
    // ─────────────────────────────────────────

    @Test
    @DisplayName("GET /captures/trail/{trailId}/latest - Redis 캐시 히트 시 캐시 데이터를 반환한다")
    void getLatestByTrail_returnsCachedDataOnRedisHit() throws Exception {
        // given
        String redisKey = "capture:latest:trail:1";
        String cachedJson = "{\"roadStatus\":\"양호\",\"confidence\":0.95}";
        given(valueOperations.get(redisKey)).willReturn(cachedJson);

        // when & then
        mockMvc.perform(get("/captures/trail/1/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("redis"))
                .andExpect(jsonPath("$.data").exists());
    }

    @Test
    @DisplayName("GET /captures/trail/{trailId}/latest - Redis 캐시 미스 시 PostgreSQL 데이터를 반환한다")
    void getLatestByTrail_returnsPostgresDataOnRedisMiss() throws Exception {
        // given
        String redisKey = "capture:latest:trail:1";
        given(valueOperations.get(redisKey)).willReturn(null); // cache miss

        Capture capture = new Capture();
        given(captureRepository.findByTrailId(1)).willReturn(List.of(capture));

        // when & then
        mockMvc.perform(get("/captures/trail/1/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("postgresql"))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("GET /captures/trail/{trailId}/latest - Redis 캐시 미스이고 결과도 없으면 빈 배열을 반환한다")
    void getLatestByTrail_returnsEmptyListOnRedisMissAndNoData() throws Exception {
        // given
        String redisKey = "capture:latest:trail:999";
        given(valueOperations.get(redisKey)).willReturn(null); // cache miss
        given(captureRepository.findByTrailId(999)).willReturn(List.of());

        // when & then
        mockMvc.perform(get("/captures/trail/999/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("postgresql"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("GET /captures/trail/{trailId}/latest - Redis 캐시 히트 시 PostgreSQL을 조회하지 않는다")
    void getLatestByTrail_doesNotQueryPostgresOnCacheHit() throws Exception {
        // given
        String redisKey = "capture:latest:trail:1";
        String cachedJson = "{\"roadStatus\":\"양호\"}";
        given(valueOperations.get(redisKey)).willReturn(cachedJson);

        // when & then
        mockMvc.perform(get("/captures/trail/1/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("redis"));

        // PostgreSQL 조회 없음을 검증
        org.mockito.Mockito.verify(captureRepository, org.mockito.Mockito.never())
                .findByTrailId(org.mockito.ArgumentMatchers.any());
    }
}
