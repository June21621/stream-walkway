package com.stream.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = BackendApplication.class)
@DisplayName("Backend - Health Check 테스트")
class HealthCheckTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /health - 200 OK를 반환한다")
    void health_returns200() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /health - status 필드가 'ok'이다")
    void health_statusIsOk() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    @DisplayName("GET /health - service 필드가 'backend'이다")
    void health_serviceIsBackend() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(jsonPath("$.service").value("backend"));
    }
}
