package com.stream.reader.controller;

import com.stream.reader.repository.TrailRepository;
import com.stream.shared.entity.Trail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TrailController.class)
@DisplayName("Reader - TrailController 테스트")
class TrailControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TrailRepository trailRepository;

    private Trail buildTrail(Long id, Long streamId, String cameraNumber) throws Exception {
        Trail trail = new Trail();
        Field idField = Trail.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(trail, id);
        trail.setStreamId(streamId);
        trail.setCameraNumber(cameraNumber);
        trail.setLocation((org.locationtech.jts.geom.Point)
                new org.locationtech.jts.io.WKTReader().read("POINT(126.97 37.55)"));
        trail.setDirection("북");
        trail.setStatus("active");
        Field createdAtField = Trail.class.getDeclaredField("createdAt");
        createdAtField.setAccessible(true);
        createdAtField.set(trail, LocalDateTime.of(2024, 1, 1, 0, 0, 0));
        return trail;
    }

    @Test
    @DisplayName("GET /trails - 전체 트레일 목록을 200 OK로 반환한다")
    void getAll_returns200WithTrailList() throws Exception {
        // given
        given(trailRepository.findAll()).willReturn(List.of(buildTrail(1L, 1L, "CAM-001")));

        // when & then
        mockMvc.perform(get("/trails"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].streamId").value(1))
                .andExpect(jsonPath("$[0].cameraNumber").value("CAM-001"))
                .andExpect(jsonPath("$[0].location").value("POINT(126.97 37.55)"));
    }

    @Test
    @DisplayName("GET /trails?stream_id=1 - stream_id로 필터링된 트레일 목록을 반환한다")
    void getAll_returns200FilteredByStreamId() throws Exception {
        // given
        given(trailRepository.findByStreamId(1L)).willReturn(List.of(buildTrail(1L, 1L, "CAM-001")));

        // when & then
        mockMvc.perform(get("/trails").param("stream_id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].streamId").value(1));
    }

    @Test
    @DisplayName("GET /trails - 트레일이 없으면 빈 배열을 200 OK로 반환한다")
    void getAll_returns200WithEmptyList() throws Exception {
        given(trailRepository.findAll()).willReturn(List.of());

        mockMvc.perform(get("/trails"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("GET /trails/{id} - 존재하는 트레일을 200 OK로 반환한다")
    void getById_returns200WhenFound() throws Exception {
        given(trailRepository.findById(1L)).willReturn(Optional.of(buildTrail(1L, 1L, "CAM-001")));

        mockMvc.perform(get("/trails/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.cameraNumber").value("CAM-001"));
    }

    @Test
    @DisplayName("GET /trails/{id} - 존재하지 않으면 404를 반환한다")
    void getById_returns404WhenNotFound() throws Exception {
        given(trailRepository.findById(999L)).willReturn(Optional.empty());

        mockMvc.perform(get("/trails/999"))
                .andExpect(status().isNotFound());
    }
}
