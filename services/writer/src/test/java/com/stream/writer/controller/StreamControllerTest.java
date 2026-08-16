package com.stream.writer.controller;

import com.stream.shared.entity.Stream;
import com.stream.writer.command.CreateStreamCommand;
import com.stream.writer.command.StreamCommandHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StreamController.class)
@DisplayName("Writer - StreamController(내부 전용) 테스트")
class StreamControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StreamCommandHandler streamCommandHandler;

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("POST /internal/streams - Stream을 생성하면 201 Created와 StreamView를 반환한다")
    void create_returns201WithStreamView() throws Exception {
        // given
        Stream saved = new Stream();
        setField(saved, "id", 1L);
        saved.setName("한강 산책로");
        saved.setLocation((org.locationtech.jts.geom.LineString)
                new org.locationtech.jts.io.WKTReader().read("LINESTRING(126.97 37.55, 126.98 37.56)"));
        setField(saved, "createdAt", LocalDateTime.of(2024, 1, 1, 0, 0, 0));

        given(streamCommandHandler.handle(any(CreateStreamCommand.class))).willReturn(saved);

        String requestBody = """
                {
                  "name": "한강 산책로",
                  "location": "LINESTRING(126.97 37.55, 126.98 37.56)"
                }
                """;

        // when & then
        mockMvc.perform(post("/internal/streams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("한강 산책로"))
                .andExpect(jsonPath("$.location").value("LINESTRING(126.97 37.55, 126.98 37.56)"));
    }

    @Test
    @DisplayName("POST /internal/streams - 파싱 불가능한 WKT면 400 Bad Request를 반환한다")
    void create_returns400OnUnparseableWkt() throws Exception {
        given(streamCommandHandler.handle(any(CreateStreamCommand.class)))
                .willThrow(new org.locationtech.jts.io.ParseException("Unknown geometry type: NOT-A-WKT"));

        String requestBody = """
                {
                  "name": "잘못된 스트림",
                  "location": "NOT-A-WKT"
                }
                """;

        mockMvc.perform(post("/internal/streams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /internal/streams - LineString이 아닌 WKT(POINT 등)면 400 Bad Request를 반환한다")
    void create_returns400OnWrongGeometryType() throws Exception {
        given(streamCommandHandler.handle(any(CreateStreamCommand.class)))
                .willThrow(new ClassCastException("class org.locationtech.jts.geom.Point cannot be cast to class org.locationtech.jts.geom.LineString"));

        String requestBody = """
                {
                  "name": "점 좌표",
                  "location": "POINT(1 2)"
                }
                """;

        mockMvc.perform(post("/internal/streams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}
