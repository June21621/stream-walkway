package com.stream.writer.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * {@code /internal/**}에 {@code X-Internal-Key}를 요구한다.
 *
 * <p>이전까지 이 검사는 게이트웨이(`apps/backend`)에만 있었다. 그런데 writer의
 * 포트는 compose에서 호스트로 열려 있어(`${WRITER_PORT:-8002}:8080`) 게이트웨이를
 * 건너뛰고 직접 쓰기가 가능했다 — 게이트웨이의 키 검사가 우회 가능한 장식이었다.
 *
 * <p>컨트롤러마다 검사를 복사하지 않고 필터로 둔다. 앞으로 `/internal/captures`
 * 같은 것이 생겨도 자동으로 덮인다. backend는 컨트롤러마다 복사한 것이 이미
 * 세 벌인데, 같은 실수를 새 모듈에서 되풀이하지 않는다.
 */
@Component
public class InternalKeyFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Internal-Key";
    private static final String PROTECTED_PREFIX = "/internal/";
    private static final String UNAUTHORIZED_BODY =
            "{\"error\":\"Unauthorized\",\"message\":\"Invalid or missing X-Internal-Key\"}";

    private final String apiKey;

    public InternalKeyFilter(@Value("${internal.api-key}") String apiKey) {
        this.apiKey = apiKey;
    }

    // 헬스체크와 액추에이터까지 막으면 컨테이너가 죽는다. 보호 대상은
    // /internal/ 아래뿐이다 - 접두사에 끝 슬래시를 넣어 /internalish 같은
    // 이름이 우연히 걸리지 않게 한다.
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PROTECTED_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!apiKey.equals(request.getHeader(HEADER))) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(UNAUTHORIZED_BODY);
            return;
        }
        chain.doFilter(request, response);
    }
}
