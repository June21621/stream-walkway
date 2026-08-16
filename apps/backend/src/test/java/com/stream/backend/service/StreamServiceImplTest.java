package com.stream.backend.service;

import com.stream.backend.model.Stream;
import com.stream.shared.dto.StreamView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
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
                LocalDateTime.of(2024, 1, 1, 0, 0, 0));

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
        assertThat(result.get(0).getCreatedAt()).isEqualTo("2024-01-01T00:00");
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
}
