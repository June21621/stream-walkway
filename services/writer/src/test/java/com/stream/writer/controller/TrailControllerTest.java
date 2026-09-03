package com.stream.writer.controller;

import com.stream.shared.entity.Trail;
import com.stream.writer.command.CreateTrailCommand;
import com.stream.writer.command.TrailCommandHandler;
import com.stream.writer.exception.DuplicateTrailException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TrailController.class)
@TestPropertySource(properties = "internal.api-key=test-internal-key")
@DisplayName("Writer - TrailController(내부 전용) 테스트")
class TrailControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TrailCommandHandler trailCommandHandler;

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("POST /internal/trails - Trail을 생성하면 201 Created와 TrailView를 반환한다")
    void create_returns201WithTrailView() throws Exception {
        // given
        Trail saved = new Trail();
        setField(saved, "id", 1L);
        saved.setStreamId(1L);
        saved.setCameraNumber("CAM-001");
        saved.setLocation((org.locationtech.jts.geom.Point)
                new org.locationtech.jts.io.WKTReader().read("POINT(126.97 37.55)"));
        saved.setDirection("북");
        saved.setStatus("active");
        setField(saved, "createdAt", Instant.parse("2024-01-01T00:00:00Z"));

        given(trailCommandHandler.handle(any(CreateTrailCommand.class))).willReturn(saved);

        String requestBody = """
                {
                  "streamId": 1,
                  "cameraNumber": "CAM-001",
                  "location": "POINT(126.97 37.55)",
                  "direction": "북",
                  "status": "active"
                }
                """;

        // when & then
        mockMvc.perform(post("/internal/trails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Internal-Key", "test-internal-key")
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.streamId").value(1))
                .andExpect(jsonPath("$.cameraNumber").value("CAM-001"))
                .andExpect(jsonPath("$.location").value("POINT(126.97 37.55)"))
                .andExpect(jsonPath("$.direction").value("북"))
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    @DisplayName("POST /internal/trails - 파싱 불가능한 WKT면 400 Bad Request를 반환한다")
    void create_returns400OnUnparseableWkt() throws Exception {
        given(trailCommandHandler.handle(any(CreateTrailCommand.class)))
                .willThrow(new org.locationtech.jts.io.ParseException("Unknown geometry type: NOT-A-WKT"));

        String requestBody = """
                {
                  "streamId": 1,
                  "cameraNumber": "CAM-005",
                  "location": "NOT-A-WKT",
                  "direction": "북",
                  "status": "active"
                }
                """;

        mockMvc.perform(post("/internal/trails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Internal-Key", "test-internal-key")
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /internal/trails - Point가 아닌 WKT(LINESTRING 등)면 400 Bad Request를 반환한다")
    void create_returns400OnWrongGeometryType() throws Exception {
        given(trailCommandHandler.handle(any(CreateTrailCommand.class)))
                .willThrow(new ClassCastException("class org.locationtech.jts.geom.LineString cannot be cast to class org.locationtech.jts.geom.Point"));

        String requestBody = """
                {
                  "streamId": 1,
                  "cameraNumber": "CAM-006",
                  "location": "LINESTRING(0 0, 1 1)",
                  "direction": "북",
                  "status": "active"
                }
                """;

        mockMvc.perform(post("/internal/trails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Internal-Key", "test-internal-key")
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /internal/trails - status가 active/inactive가 아니면 400 Bad Request를 반환한다")
    void create_returns400OnInvalidStatus() throws Exception {
        given(trailCommandHandler.handle(any(CreateTrailCommand.class)))
                .willThrow(new IllegalArgumentException("Invalid status: unknown (must be 'active' or 'inactive')"));

        String requestBody = """
                {
                  "streamId": 1,
                  "cameraNumber": "CAM-007",
                  "location": "POINT(126.97 37.55)",
                  "direction": "북",
                  "status": "unknown"
                }
                """;

        mockMvc.perform(post("/internal/trails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Internal-Key", "test-internal-key")
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /internal/trails - (stream_id, camera_number) 중복이면 409 Conflict를 반환한다")
    void create_returns409OnDuplicateTrail() throws Exception {
        given(trailCommandHandler.handle(any(CreateTrailCommand.class)))
                .willThrow(new DuplicateTrailException("stream_id=1, camera_number=CAM-001 already exists"));

        String requestBody = """
                {
                  "streamId": 1,
                  "cameraNumber": "CAM-001",
                  "location": "POINT(126.97 37.55)",
                  "direction": "북",
                  "status": "active"
                }
                """;

        mockMvc.perform(post("/internal/trails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Internal-Key", "test-internal-key")
                        .content(requestBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Duplicate trail"))
                .andExpect(jsonPath("$.message").value("stream_id=1, camera_number=CAM-001 already exists"));
    }
}
