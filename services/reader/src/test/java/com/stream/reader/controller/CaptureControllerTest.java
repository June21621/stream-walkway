package com.stream.reader.controller;

import com.stream.shared.entity.Capture;
import com.stream.reader.repository.CaptureRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
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
        given(captureRepository.findFiltered(eq(null), eq(null), any(Pageable.class)))
                .willReturn(List.of(capture));

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
        given(captureRepository.findFiltered(eq(null), eq(null), any(Pageable.class)))
                .willReturn(List.of());

        // when & then
        mockMvc.perform(get("/captures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("GET /captures?trail_id=1&limit=2 - 필터와 개수 제한이 리포지토리에 전달된다")
    void getAll_passesFilterAndLimitToRepository() throws Exception {
        // given
        given(captureRepository.findFiltered(eq(null), eq(1), any(Pageable.class)))
                .willReturn(List.of());

        // when
        mockMvc.perform(get("/captures").param("trail_id", "1").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // then
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(captureRepository).findFiltered(eq(null), eq(1), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(2);
        assertThat(captor.getValue().getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("GET /captures - 파라미터가 없으면 limit 20, createdAt 내림차순이 기본값이다")
    void getAll_usesDefaultLimitAndSort() throws Exception {
        // given
        given(captureRepository.findFiltered(eq(null), eq(null), any(Pageable.class)))
                .willReturn(List.of());

        // when
        mockMvc.perform(get("/captures")).andExpect(status().isOk());

        // then
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(captureRepository).findFiltered(eq(null), eq(null), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
        assertThat(captor.getValue().getSort().getOrderFor("createdAt")).isNotNull();
    }

    @Test
    @DisplayName("GET /captures?stream_id=3&trail_id=1 - 두 필터가 함께 전달된다")
    void getAll_passesBothFilters() throws Exception {
        // given
        given(captureRepository.findFiltered(eq(3), eq(1), any(Pageable.class)))
                .willReturn(List.of());

        // when & then
        mockMvc.perform(get("/captures").param("stream_id", "3").param("trail_id", "1"))
                .andExpect(status().isOk());

        verify(captureRepository).findFiltered(eq(3), eq(1), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /captures?stream_id=&trail_id= - 빈 값 파라미터는 null 필터로 바인딩된다")
    void getAll_bindsEmptyParamsAsNull() throws Exception {
        // backend의 CaptureServiceImpl이 필터 없이 호출하면 Spring의 URI 템플릿
        // 확장이 null을 빈 문자열로 만들어 "?stream_id=&trail_id=" 를 보낸다
        // (DefaultUriBuilderFactory.expand 실측). backend 쪽 테스트는 RestClient를
        // stub하므로 이 URI가 실제로 만들어지는 것을 보지 못한다.
        // 그 경계를 여기서 고정한다.
        given(captureRepository.findFiltered(eq(null), eq(null), any(Pageable.class)))
                .willReturn(List.of());

        mockMvc.perform(get("/captures")
                        .param("stream_id", "")
                        .param("trail_id", "")
                        .param("limit", "20")
                        .param("sort", "created_at"))
                .andExpect(status().isOk());

        verify(captureRepository).findFiltered(eq(null), eq(null), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /captures?sort=drop_table - 허용하지 않는 정렬 키는 400을 반환한다")
    void getAll_returns400OnUnknownSortKey() throws Exception {
        mockMvc.perform(get("/captures").param("sort", "drop_table"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("GET /captures?limit=0 - 0 이하의 limit은 400을 반환한다")
    void getAll_returns400OnNonPositiveLimit() throws Exception {
        mockMvc.perform(get("/captures").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // ─────────────────────────────────────────
    // GET /captures/{id}
    // ─────────────────────────────────────────

    // Capture는 id/createdAt/updatedAt에 setter가 없다(@PrePersist와 DB가 채우는 값).
    // 테스트에서 특정 값을 넣으려면 리플렉션이 필요하다.
    private void setField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("GET /captures/{id} - 존재하는 캡처를 200과 CaptureView로 반환한다")
    void getById_returns200WhenFound() throws Exception {
        // given
        Capture capture = new Capture();
        setField(capture, "id", 7L);
        capture.setTrailId(1);
        capture.setStreamId(2);
        capture.setImagePath("/images/capture_007.jpg");
        capture.setRoadStatus("양호");
        capture.setConfidence(0.95);
        setField(capture, "createdAt", Instant.parse("2024-01-01T00:00:00Z"));
        setField(capture, "updatedAt", Instant.parse("2024-01-02T00:00:00Z"));
        given(captureRepository.findById(7L)).willReturn(Optional.of(capture));

        // when & then
        mockMvc.perform(get("/captures/7"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.trailId").value(1))
                .andExpect(jsonPath("$.streamId").value(2))
                .andExpect(jsonPath("$.imagePath").value("/images/capture_007.jpg"))
                .andExpect(jsonPath("$.roadStatus").value("양호"))
                .andExpect(jsonPath("$.confidence").value(0.95))
                .andExpect(jsonPath("$.createdAt").value("2024-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2024-01-02T00:00:00Z"));
    }

    @Test
    @DisplayName("GET /captures/{id} - 없으면 본문 없는 404를 반환한다")
    void getById_returns404WhenNotFound() throws Exception {
        // given
        given(captureRepository.findById(999L)).willReturn(Optional.empty());

        // when & then
        mockMvc.perform(get("/captures/999"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
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
