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
import java.security.MessageDigest;
import java.util.Set;

/**
 * {@code /health}를 제외한 모든 요청에 {@code X-Internal-Key}를 요구한다.
 *
 * <p>이전까지 이 검사는 게이트웨이(`apps/backend`)에만 있었다. 그런데 writer의
 * 포트는 compose에서 호스트로 열려 있어(`${WRITER_PORT:-8002}:8080`) 게이트웨이를
 * 건너뛰고 직접 쓰기가 가능했다 — 게이트웨이의 키 검사가 우회 가능한 장식이었다.
 *
 * <p>컨트롤러마다 검사를 복사하지 않고 필터로 둔다. 앞으로 `/internal/captures`
 * 같은 것이 생겨도 자동으로 덮인다. backend는 컨트롤러마다 복사한 것이 이미
 * 세 벌인데, 같은 실수를 새 모듈에서 되풀이하지 않는다.
 *
 * <p>보호 대상을 경로 접두사로 고르지 않고 <b>공개 경로만 나열</b>한다. 이유는
 * {@link #shouldNotFilter} 주석 참고 - 접두사 방식은 실제로 우회됐다.
 */
@Component
public class InternalKeyFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Internal-Key";

    // 열어둘 경로를 나열한다. "/internal/로 시작하면 막는다"는 반대 방향은
    // 안 된다 - getRequestURI()는 디코딩되지 않은 원본을 주는데 Spring MVC는
    // 디코딩된 경로로 라우팅해서, POST /%69nternal/streams가 필터를 지나쳐
    // 핸들러까지 닿는다(실측함). 거부 목록은 정규화하지 않은 경로 앞에서
    // 열린 채로 실패하고, 허용 목록은 닫힌 채로 실패한다.
    private static final Set<String> PUBLIC_PATHS = Set.of("/health");
    private static final String UNAUTHORIZED_BODY =
            "{\"error\":\"Unauthorized\",\"message\":\"Invalid or missing X-Internal-Key\"}";

    private final String apiKey;

    public InternalKeyFilter(@Value("${internal.api-key}") String apiKey) {
        this.apiKey = apiKey;
    }

    // 헬스체크까지 막으면 컨테이너가 죽는다. 그 밖의 모든 경로는 키를 요구한다.
    // writer에 공개 API가 없어 가능한 선택이다 - 조회는 reader가 맡는다.
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return PUBLIC_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!matches(request.getHeader(HEADER))) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(UNAUTHORIZED_BODY);
            return;
        }
        chain.doFilter(request, response);
    }

    // 길이가 같은 문자열에서 String.equals는 첫 불일치 위치에 따라 시간이 달라진다.
    // HTTP 너머의 타이밍 공격은 현실적이지 않지만 줄 수가 같다.
    private boolean matches(String presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
                apiKey.getBytes(StandardCharsets.UTF_8));
    }
}
