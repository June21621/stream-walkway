package com.stream.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("Backend - HttpClientConfig 테스트")
class HttpClientConfigTest {

    @Test
    @DisplayName("writerRestClient는 모든 요청에 X-Internal-Key를 싣는다")
    void writerClientSendsInternalKey() {
        // given - writer의 /internal/**가 이 헤더를 요구한다. 헤더 이름이나
        // 값이 어긋나면 실기동에서 401이 되는데, 그것을 여기서 잡는다.
        RestClient.Builder builder =
                HttpClientConfig.writerRestClientBuilder("http://writer:8080", "the-key");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://writer:8080/internal/streams"))
                .andExpect(header("X-Internal-Key", "the-key"))
                .andRespond(withSuccess());

        // when
        builder.build().post().uri("/internal/streams").retrieve().toBodilessEntity();

        // then
        server.verify();
    }
}
