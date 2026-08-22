package com.stream.backend.service;

import com.stream.backend.model.Stream;
import com.stream.shared.dto.StreamView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayName("Backend - StreamServiceImpl 테스트")
class StreamServiceImplTest {

    @Test
    @DisplayName("findAll() - reader의 GET /streams 응답을 Stream 모델 리스트로 변환한다")
    void findAll_mapsReaderResponseToStreamList() {
        // given
        RestClient readerClient = mock(RestClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        RestClient writerClient = mock(RestClient.class);
        StreamView view = new StreamView(1L, "한강 산책로", "LINESTRING(126.97 37.55, 126.98 37.56)",
                Instant.parse("2024-01-01T00:00:00Z"));

        given(readerClient.get()
                .uri("/streams")
                .retrieve()
                .body(any(org.springframework.core.ParameterizedTypeReference.class)))
                .willReturn(List.of(view));

        StreamServiceImpl service = new StreamServiceImpl(readerClient, writerClient);

        // when
        List<Stream> result = service.findAll();

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(1L);
        assertThat(result.get(0).getName()).isEqualTo("한강 산책로");
        assertThat(result.get(0).getLocation()).isEqualTo("LINESTRING(126.97 37.55, 126.98 37.56)");
        assertThat(result.get(0).getCreatedAt()).isEqualTo("2024-01-01T00:00:00Z");
    }

    @Test
    @DisplayName("findById() - reader가 404를 주면 빈 Optional을 반환한다")
    void findById_returnsEmptyOnReader404() {
        RestClient readerClient = mock(RestClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        RestClient writerClient = mock(RestClient.class);

        given(readerClient.get()
                .uri("/streams/{id}", 999L)
                .retrieve()
                .body(StreamView.class))
                .willThrow(org.springframework.web.client.HttpClientErrorException.NotFound.class);

        StreamServiceImpl service = new StreamServiceImpl(readerClient, writerClient);

        Optional<Stream> result = service.findById(999L);

        assertThat(result).isEmpty();
    }

    // writerClient.post().uri(...).contentType(...).body(...).retrieve() 체인을 deep-stub 대신
    // 단계별로 직접 mock해서 연결한다 (create() 테스트에서 deep-stub 체이닝이 실제 프로덕션 호출과
    // 어긋나 null을 반환하는 문제가 있었음 - 각 인터페이스를 명시적으로 mock하면 확실히 매칭된다).
    private RestClient.ResponseSpec stubWriterCreateCall(RestClient writerClient) {
        // RETURNS_SELF: uri()/contentType()/body() 전부 이 mock 자신과 호환되는 타입을 반환하므로
        // 인자와 무관하게 체이닝이 계속 같은 mock으로 이어진다 (개별 인자 매처를 등록할 필요 없음).
        RestClient.RequestBodyUriSpec bodyUriSpec =
                mock(RestClient.RequestBodyUriSpec.class, org.mockito.Answers.RETURNS_SELF);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        given(writerClient.post()).willReturn(bodyUriSpec);
        given(bodyUriSpec.retrieve()).willReturn(responseSpec);
        return responseSpec;
    }

    @Test
    @DisplayName("create() - writer의 POST /internal/streams 응답을 Stream 모델로 변환한다")
    void create_mapsWriterResponseToStream() {
        // given
        RestClient readerClient = mock(RestClient.class);
        RestClient writerClient = mock(RestClient.class);
        StreamView created = new StreamView(1L, "한강 산책로", "LINESTRING(126.97 37.55, 126.98 37.56)",
                Instant.parse("2024-01-01T00:00:00Z"));

        RestClient.ResponseSpec responseSpec = stubWriterCreateCall(writerClient);
        given(responseSpec.body(StreamView.class)).willReturn(created);

        StreamServiceImpl service = new StreamServiceImpl(readerClient, writerClient);
        Stream input = new Stream(null, "한강 산책로", "LINESTRING(126.97 37.55, 126.98 37.56)", null);

        // when
        Stream result = service.create(input);

        // then
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("한강 산책로");
        assertThat(result.getLocation()).isEqualTo("LINESTRING(126.97 37.55, 126.98 37.56)");
        assertThat(result.getCreatedAt()).isEqualTo("2024-01-01T00:00:00Z");
    }

    @Test
    @DisplayName("create() - writer가 400을 반환하면 InvalidStreamGeometryException을 던진다")
    void create_throwsInvalidStreamGeometryExceptionOnWriter400() {
        // given
        RestClient readerClient = mock(RestClient.class);
        RestClient writerClient = mock(RestClient.class);

        RestClient.ResponseSpec responseSpec = stubWriterCreateCall(writerClient);
        // willThrow(Class)로 Mockito가 리플렉션으로 예외를 만들면 responseBody 바이트 배열이 null로
        // 남아 getResponseBodyAsString() 호출 시 NPE가 나므로, 실제 팩토리 메서드로 완전한 인스턴스를 만든다.
        given(responseSpec.body(StreamView.class))
                .willThrow(org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Bad Request",
                        org.springframework.http.HttpHeaders.EMPTY,
                        "Invalid WKT geometry".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.UTF_8));

        StreamServiceImpl service = new StreamServiceImpl(readerClient, writerClient);
        Stream input = new Stream(null, "잘못된 스트림", "NOT-A-WKT", null);

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                com.stream.backend.exception.InvalidStreamGeometryException.class,
                () -> service.create(input));
    }

    @Test
    @DisplayName("create() - writer가 null 응답을 반환하면 InvalidStreamGeometryException을 던진다")
    void create_throwsInvalidStreamGeometryExceptionOnNullResponse() {
        // given
        RestClient readerClient = mock(RestClient.class);
        RestClient writerClient = mock(RestClient.class);

        RestClient.ResponseSpec responseSpec = stubWriterCreateCall(writerClient);
        given(responseSpec.body(StreamView.class)).willReturn(null);

        StreamServiceImpl service = new StreamServiceImpl(readerClient, writerClient);
        Stream input = new Stream(null, "한강 산책로", "LINESTRING(126.97 37.55, 126.98 37.56)", null);

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                com.stream.backend.exception.InvalidStreamGeometryException.class,
                () -> service.create(input));
    }
}
