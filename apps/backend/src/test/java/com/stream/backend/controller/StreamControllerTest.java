package com.stream.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stream.backend.model.Stream;
import com.stream.backend.service.StreamService;
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
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StreamController.class)
@DisplayName("StreamController 테스트")
class StreamControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private StreamService streamService;

    // ─────────────────────────────────────────
    // GET /api/streams
    // ─────────────────────────────────────────

    @Test
    @DisplayName("GET /api/streams - 전체 스트림 목록을 200 OK로 반환한다")
    void getAll_returns200WithStreamList() throws Exception {
        // given
        Stream stream = new Stream(1L, "한강 산책로",
                "LINESTRING(126.97 37.55, 126.98 37.56)", "2024-01-01T00:00:00Z");
        given(streamService.findAll()).willReturn(List.of(stream));

        // when & then
        mockMvc.perform(get("/api/streams"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("한강 산책로"))
                .andExpect(jsonPath("$[0].location").value("LINESTRING(126.97 37.55, 126.98 37.56)"))
                .andExpect(jsonPath("$[0].created_at").value("2024-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("GET /api/streams - 스트림이 없으면 빈 배열을 200 OK로 반환한다")
    void getAll_returns200WithEmptyList() throws Exception {
        // given
        given(streamService.findAll()).willReturn(List.of());

        // when & then
        mockMvc.perform(get("/api/streams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    // ─────────────────────────────────────────
    // GET /api/streams/{id}
    // ─────────────────────────────────────────

    @Test
    @DisplayName("GET /api/streams/{id} - 존재하는 스트림을 200 OK로 반환한다")
    void getById_returns200WhenFound() throws Exception {
        // given
        Stream stream = new Stream(1L, "한강 산책로",
                "LINESTRING(126.97 37.55, 126.98 37.56)", "2024-01-01T00:00:00Z");
        given(streamService.findById(1L)).willReturn(Optional.of(stream));

        // when & then
        mockMvc.perform(get("/api/streams/1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("한강 산책로"))
                .andExpect(jsonPath("$.location").value("LINESTRING(126.97 37.55, 126.98 37.56)"))
                .andExpect(jsonPath("$.created_at").value("2024-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("GET /api/streams/{id} - 존재하지 않는 스트림은 404 Not Found와 에러 바디를 반환한다")
    void getById_returns404WhenNotFound() throws Exception {
        // given
        given(streamService.findById(999L)).willReturn(Optional.empty());

        // when & then
        mockMvc.perform(get("/api/streams/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Stream not found"))
                .andExpect(jsonPath("$.id").value(999));
    }

    // ─────────────────────────────────────────
    // POST /api/streams (내부 전용)
    // ─────────────────────────────────────────

    @Test
    @DisplayName("POST /api/streams - X-Internal-Key 헤더와 함께 스트림을 등록하면 201 Created를 반환한다")
    void create_returns201WithInternalKey() throws Exception {
        // given
        Stream created = new Stream(1L, "한강 산책로",
                "LINESTRING(126.97 37.55, 126.98 37.56)", "2024-01-01T00:00:00Z");
        given(streamService.create(any(Stream.class))).willReturn(created);

        String requestBody = """
                {
                  "name": "한강 산책로",
                  "location": "LINESTRING(126.97 37.55, 126.98 37.56)"
                }
                """;

        // when & then
        mockMvc.perform(post("/api/streams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Internal-Key", "test-internal-key")
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("한강 산책로"))
                .andExpect(jsonPath("$.location").value("LINESTRING(126.97 37.55, 126.98 37.56)"))
                .andExpect(jsonPath("$.created_at").exists());
    }

    @Test
    @DisplayName("POST /api/streams - X-Internal-Key 헤더 없이 요청하면 400 Bad Request를 반환한다")
    void create_returns400WithoutInternalKey() throws Exception {
        // given
        String requestBody = """
                {
                  "name": "한강 산책로",
                  "location": "LINESTRING(126.97 37.55, 126.98 37.56)"
                }
                """;

        // when & then
        mockMvc.perform(post("/api/streams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }
}
