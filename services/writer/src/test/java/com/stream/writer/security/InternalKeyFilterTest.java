package com.stream.writer.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("Writer - InternalKeyFilter 테스트")
class InternalKeyFilterTest {

    private static final String KEY = "test-internal-key";

    private final InternalKeyFilter filter = new InternalKeyFilter(KEY);

    private MockHttpServletRequest request(String uri, String key) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        if (key != null) {
            req.addHeader("X-Internal-Key", key);
        }
        return req;
    }

    @Test
    @DisplayName("올바른 키면 통과시킨다")
    void passesWithValidKey() throws Exception {
        // given
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        // when
        filter.doFilter(request("/internal/streams", KEY), response, chain);

        // then
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("키가 틀리면 401을 내고 체인을 타지 않는다")
    void rejectsWrongKey() throws Exception {
        // given
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        // when
        filter.doFilter(request("/internal/streams", "wrong-key"), response, chain);

        // then - 체인을 타지 않는 것이 핵심이다. 401만 확인하면 핸들러가
        // 이미 DB에 쓴 뒤에 401을 붙였어도 통과한다.
        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Unauthorized");
    }

    @Test
    @DisplayName("키 헤더가 아예 없으면 401을 낸다")
    void rejectsMissingKey() throws Exception {
        // given
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        // when
        filter.doFilter(request("/internal/trails", null), response, chain);

        // then
        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("401 본문은 UTF-8 JSON이다")
    void writesJsonBody() throws Exception {
        // given
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        filter.doFilter(request("/internal/streams", null), response, mock(FilterChain.class));

        // then
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
        assertThat(response.getContentAsString())
                .contains("\"error\"")
                .contains("X-Internal-Key");
    }

    @Test
    @DisplayName("/health는 키 없이도 통과시킨다")
    void allowsHealthCheck() throws Exception {
        // given - 헬스체크가 막히면 컨테이너가 죽는다
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        // when
        filter.doFilter(request("/health", null), response, chain);

        // then
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("공개 목록에 없는 경로는 전부 키를 요구한다")
    void rejectsEverythingNotExplicitlyPublic() throws Exception {
        // given - 접두사 거부 목록이었을 때 %69nternal이 통과했다. 허용 목록은
        // 정규화되지 않은 경로든 처음 보는 경로든 전부 닫는다.
        for (String uri : new String[] {
                "/%69nternal/streams", "/INTERNAL/streams", "/internalish",
                "/internal/streams/", "/foo/../internal/streams", "/actuator/health"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            // when
            filter.doFilter(request(uri, null), response, chain);

            // then
            verify(chain, never()).doFilter(any(), any());
            assertThat(response.getStatus()).as(uri).isEqualTo(401);
        }
    }
}
