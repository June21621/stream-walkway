package com.stream.reader.controller;

import com.stream.reader.repository.StreamRepository;
import com.stream.shared.entity.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StreamController.class)
@DisplayName("Reader - StreamController 테스트")
class StreamControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StreamRepository streamRepository;

    private Stream buildStream(Long id, String name) throws Exception {
        Stream stream = new Stream();
        Field idField = Stream.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(stream, id);
        stream.setName(name);
        stream.setLocation((org.locationtech.jts.geom.LineString)
                new org.locationtech.jts.io.WKTReader().read("LINESTRING(126.97 37.55, 126.98 37.56)"));
        Field createdAtField = Stream.class.getDeclaredField("createdAt");
        createdAtField.setAccessible(true);
        createdAtField.set(stream, Instant.parse("2024-01-01T00:00:00Z"));
        return stream;
    }

    @Test
    @DisplayName("GET /streams - 전체 스트림 목록을 200 OK로 반환한다")
    void getAll_returns200WithStreamList() throws Exception {
        // given
        given(streamRepository.findAll()).willReturn(List.of(buildStream(1L, "한강 산책로")));

        // when & then
        mockMvc.perform(get("/streams"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("한강 산책로"))
                .andExpect(jsonPath("$[0].location").value("LINESTRING(126.97 37.55, 126.98 37.56)"))
                .andExpect(jsonPath("$[0].createdAt").value("2024-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("GET /streams - 스트림이 없으면 빈 배열을 200 OK로 반환한다")
    void getAll_returns200WithEmptyList() throws Exception {
        given(streamRepository.findAll()).willReturn(List.of());

        mockMvc.perform(get("/streams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("GET /streams/{id} - 존재하는 스트림을 200 OK로 반환한다")
    void getById_returns200WhenFound() throws Exception {
        given(streamRepository.findById(1L)).willReturn(Optional.of(buildStream(1L, "한강 산책로")));

        mockMvc.perform(get("/streams/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("한강 산책로"));
    }

    @Test
    @DisplayName("GET /streams/{id} - 존재하지 않으면 404를 반환한다")
    void getById_returns404WhenNotFound() throws Exception {
        given(streamRepository.findById(999L)).willReturn(Optional.empty());

        mockMvc.perform(get("/streams/999"))
                .andExpect(status().isNotFound());
    }
}
