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
        // given - writer가 이 헤더를 요구한다. 헤더 이름이 어긋나면 실기동에서
        // 401이 되는데 그것을 여기서 잡는다. 다만 빌더를 직접 부르므로 빈이
        // 올바른 프로퍼티(${internal.api-key})를 넘기는지까지는 보지 못한다 -
        // 그쪽은 프로퍼티가 없으면 기동 자체가 실패해서 위험이 낮다.
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
