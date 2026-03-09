package com.stream.backend.controller;

import com.stream.backend.model.Capture;
import com.stream.backend.service.CaptureService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CaptureController.class)
@DisplayName("CaptureController 테스트")
class CaptureControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CaptureService captureService;

    // ─────────────────────────────────────────
    // GET /api/captures
    // ─────────────────────────────────────────

    @Test
    @DisplayName("GET /api/captures - 전체 캡처 목록을 200 OK로 반환한다")
    void getAll_returns200WithCaptureList() throws Exception {
        // given
        Capture capture = new Capture(1L, 1L, 1L, "/images/capture_001.jpg",
                "양호", 0.95, "2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z");
        given(captureService.findAll(null, null, 20, "created_at")).willReturn(List.of(capture));

        // when & then
        mockMvc.perform(get("/api/captures"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].trail_id").value(1))
                .andExpect(jsonPath("$[0].stream_id").value(1))
                .andExpect(jsonPath("$[0].image_path").value("/images/capture_001.jpg"))
                .andExpect(jsonPath("$[0].road_status").value("양호"))
                .andExpect(jsonPath("$[0].confidence").value(0.95))
                .andExpect(jsonPath("$[0].created_at").value("2024-01-01T00:00:00Z"))
                .andExpect(jsonPath("$[0].updated_at").value("2024-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("GET /api/captures?stream_id=1&trail_id=1&limit=20&sort=created_at - 쿼리 파라미터로 필터링된 결과를 반환한다")
    void getAll_returns200WithQueryParams() throws Exception {
        // given
        Capture capture = new Capture(1L, 1L, 1L, "/images/capture_001.jpg",
                "양호", 0.95, "2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z");
        given(captureService.findAll(1L, 1L, 20, "created_at")).willReturn(List.of(capture));

        // when & then
        mockMvc.perform(get("/api/captures")
                        .param("stream_id", "1")
                        .param("trail_id", "1")
                        .param("limit", "20")
                        .param("sort", "created_at"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].stream_id").value(1))
                .andExpect(jsonPath("$[0].trail_id").value(1));
    }

    @Test
    @DisplayName("GET /api/captures - 캡처가 없으면 빈 배열을 200 OK로 반환한다")
    void getAll_returns200WithEmptyList() throws Exception {
        // given
        given(captureService.findAll(null, null, 20, "created_at")).willReturn(List.of());

        // when & then
        mockMvc.perform(get("/api/captures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    // ─────────────────────────────────────────
    // GET /api/captures/{id}
    // ─────────────────────────────────────────

    @Test
    @DisplayName("GET /api/captures/{id} - 존재하는 캡처를 200 OK로 반환한다")
    void getById_returns200WhenFound() throws Exception {
        // given
        Capture capture = new Capture(1L, 1L, 1L, "/images/capture_001.jpg",
                "양호", 0.95, "2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z");
        given(captureService.findById(1L)).willReturn(Optional.of(capture));

        // when & then
        mockMvc.perform(get("/api/captures/1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.trail_id").value(1))
                .andExpect(jsonPath("$.stream_id").value(1))
                .andExpect(jsonPath("$.image_path").value("/images/capture_001.jpg"))
                .andExpect(jsonPath("$.road_status").value("양호"))
                .andExpect(jsonPath("$.confidence").value(0.95));
    }

    @Test
    @DisplayName("GET /api/captures/{id} - 존재하지 않는 캡처는 404 Not Found와 에러 바디를 반환한다")
    void getById_returns404WhenNotFound() throws Exception {
        // given
        given(captureService.findById(999L)).willReturn(Optional.empty());

        // when & then
        mockMvc.perform(get("/api/captures/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Capture not found"))
                .andExpect(jsonPath("$.id").value(999));
    }
}
