package com.stream.reader.controller;

import com.stream.shared.entity.Capture;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
    @DisplayName("GET /captures/trail/{trailId}/latest - Redis 캐시 미스 시 PostgreSQL의 최신 데이터를 반환한다")
    void getLatestByTrail_returnsPostgresDataOnRedisMiss() throws Exception {
        // given
        String redisKey = "capture:latest:trail:1";
        given(valueOperations.get(redisKey)).willReturn(null); // cache miss

        Capture capture = new Capture();
        given(captureRepository.findFirstByTrailIdOrderByCreatedAtDesc(1)).willReturn(java.util.Optional.of(capture));

        // when & then
        mockMvc.perform(get("/captures/trail/1/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("postgresql"))
                .andExpect(jsonPath("$.data").exists());
    }

    @Test
    @DisplayName("GET /captures/trail/{trailId}/latest - Redis 캐시 미스이고 결과도 없으면 data가 null이다")
    void getLatestByTrail_returnsNullDataOnRedisMissAndNoData() throws Exception {
        // given
        String redisKey = "capture:latest:trail:999";
        given(valueOperations.get(redisKey)).willReturn(null); // cache miss
        given(captureRepository.findFirstByTrailIdOrderByCreatedAtDesc(999)).willReturn(java.util.Optional.empty());

        // when & then
        mockMvc.perform(get("/captures/trail/999/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("postgresql"))
                .andExpect(jsonPath("$.data").doesNotExist());
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
                .findFirstByTrailIdOrderByCreatedAtDesc(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("GET /captures/trail/{trailId}/latest - Redis 캐시 미스 시 조회 결과를 Redis에 다시 채워넣는다")
    void getLatestByTrail_repopulatesCacheOnRedisMiss() throws Exception {
        // given
        String redisKey = "capture:latest:trail:1";
        given(valueOperations.get(redisKey)).willReturn(null);

        Capture capture = new Capture();
        given(captureRepository.findFirstByTrailIdOrderByCreatedAtDesc(1)).willReturn(java.util.Optional.of(capture));

        // when
        mockMvc.perform(get("/captures/trail/1/latest"))
                .andExpect(status().isOk());

        // then
        org.mockito.Mockito.verify(valueOperations).set(org.mockito.ArgumentMatchers.eq(redisKey), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("GET /captures/trail/{trailId}/latest - 재적재된 캐시 값은 CaptureView 형태의 단일 JSON 객체다 (배열이 아님)")
    void getLatestByTrail_repopulatedCacheIsSingleObjectNotArray() throws Exception {
        // given
        String redisKey = "capture:latest:trail:1";
        given(valueOperations.get(redisKey)).willReturn(null);

        Capture capture = new Capture();
        given(captureRepository.findFirstByTrailIdOrderByCreatedAtDesc(1)).willReturn(java.util.Optional.of(capture));

        // when
        mockMvc.perform(get("/captures/trail/1/latest"))
                .andExpect(status().isOk());

        // then
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(valueOperations).set(org.mockito.ArgumentMatchers.eq(redisKey), captor.capture());
        assertThat(captor.getValue()).doesNotStartWith("[");
    }

    @Test
    @DisplayName("GET /captures/trail/{trailId}/latest - Redis 캐시 미스이고 결과도 없으면 Redis에 캐싱하지 않는다")
    void getLatestByTrail_doesNotCacheOnEmptyResult() throws Exception {
        // given
        String redisKey = "capture:latest:trail:999";
        given(valueOperations.get(redisKey)).willReturn(null);
        given(captureRepository.findFirstByTrailIdOrderByCreatedAtDesc(999)).willReturn(java.util.Optional.empty());

        // when
        mockMvc.perform(get("/captures/trail/999/latest"))
                .andExpect(status().isOk());

        // then
        org.mockito.Mockito.verify(valueOperations, org.mockito.Mockito.never())
                .set(org.mockito.ArgumentMatchers.eq(redisKey), org.mockito.ArgumentMatchers.anyString());
    }
}
