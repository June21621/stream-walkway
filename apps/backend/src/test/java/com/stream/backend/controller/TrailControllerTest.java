package com.stream.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stream.backend.model.Trail;
import com.stream.backend.service.TrailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TrailController.class)
@DisplayName("TrailController 테스트")
class TrailControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TrailService trailService;

    // ─────────────────────────────────────────
    // GET /api/trails
    // ─────────────────────────────────────────

    @Test
    @DisplayName("GET /api/trails - 전체 트레일 목록을 200 OK로 반환한다")
    void getAll_returns200WithTrailList() throws Exception {
        // given
        Trail trail = new Trail(1L, 1L, "CAM-001", "POINT(126.97 37.55)", "북", "active", "2024-01-01T00:00:00Z");
        given(trailService.findAll(null)).willReturn(List.of(trail));

        // when & then
        mockMvc.perform(get("/api/trails"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].stream_id").value(1))
                .andExpect(jsonPath("$[0].camera_number").value("CAM-001"))
                .andExpect(jsonPath("$[0].location").value("POINT(126.97 37.55)"))
                .andExpect(jsonPath("$[0].direction").value("북"))
                .andExpect(jsonPath("$[0].status").value("active"))
                .andExpect(jsonPath("$[0].created_at").value("2024-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("GET /api/trails?stream_id=1 - stream_id로 필터링된 트레일 목록을 반환한다")
    void getAll_returns200FilteredByStreamId() throws Exception {
        // given
        Trail trail = new Trail(1L, 1L, "CAM-001", "POINT(126.97 37.55)", "북", "active", "2024-01-01T00:00:00Z");
        given(trailService.findAll(1L)).willReturn(List.of(trail));

        // when & then
        mockMvc.perform(get("/api/trails").param("stream_id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].stream_id").value(1));
    }

    @Test
    @DisplayName("GET /api/trails - 트레일이 없으면 빈 배열을 200 OK로 반환한다")
    void getAll_returns200WithEmptyList() throws Exception {
        // given
        given(trailService.findAll(null)).willReturn(List.of());

        // when & then
        mockMvc.perform(get("/api/trails"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    // ─────────────────────────────────────────
    // GET /api/trails/{id}
    // ─────────────────────────────────────────

    @Test
    @DisplayName("GET /api/trails/{id} - 존재하는 트레일을 200 OK로 반환한다")
    void getById_returns200WhenFound() throws Exception {
        // given
        Trail trail = new Trail(1L, 1L, "CAM-001", "POINT(126.97 37.55)", "북", "active", "2024-01-01T00:00:00Z");
        given(trailService.findById(1L)).willReturn(Optional.of(trail));

        // when & then
        mockMvc.perform(get("/api/trails/1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.stream_id").value(1))
                .andExpect(jsonPath("$.camera_number").value("CAM-001"))
                .andExpect(jsonPath("$.direction").value("북"))
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    @DisplayName("GET /api/trails/{id} - 존재하지 않는 트레일은 404 Not Found와 에러 바디를 반환한다")
    void getById_returns404WhenNotFound() throws Exception {
        // given
        given(trailService.findById(999L)).willReturn(Optional.empty());

        // when & then
        mockMvc.perform(get("/api/trails/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Trail not found"))
                .andExpect(jsonPath("$.id").value(999));
    }

    // ─────────────────────────────────────────
    // POST /api/trails (내부 전용)
    // ─────────────────────────────────────────

    @Test
    @DisplayName("POST /api/trails - X-Internal-Key 헤더와 함께 트레일을 등록하면 201 Created를 반환한다")
    void create_returns201WithInternalKey() throws Exception {
        // given
        Trail created = new Trail(1L, 1L, "CAM-001", "POINT(126.97 37.55)", "북", "active", "2024-01-01T00:00:00Z");
        given(trailService.create(any(Trail.class))).willReturn(created);

        String requestBody = """
                {
                  "stream_id": 1,
                  "camera_number": "CAM-001",
                  "location": "POINT(126.97 37.55)",
                  "direction": "북",
                  "status": "active"
                }
                """;

        // when & then
        mockMvc.perform(post("/api/trails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Internal-Key", "test-internal-key")
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.stream_id").value(1))
                .andExpect(jsonPath("$.camera_number").value("CAM-001"))
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    @DisplayName("POST /api/trails - X-Internal-Key 헤더 없이 요청하면 400 Bad Request를 반환한다")
    void create_returns400WithoutInternalKey() throws Exception {
        // given
        String requestBody = """
                {
                  "stream_id": 1,
                  "camera_number": "CAM-001",
                  "location": "POINT(126.97 37.55)",
                  "direction": "북",
                  "status": "active"
                }
                """;

        // when & then
        mockMvc.perform(post("/api/trails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }
}
