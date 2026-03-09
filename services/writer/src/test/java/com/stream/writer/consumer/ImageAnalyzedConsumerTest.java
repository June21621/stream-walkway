package com.stream.writer.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stream.writer.entity.Capture;
import com.stream.writer.repository.CaptureRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Writer - ImageAnalyzedConsumer 테스트")
class ImageAnalyzedConsumerTest {

    @Mock
    private CaptureRepository captureRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ImageAnalyzedConsumer consumer;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
    }

    // ─────────────────────────────────────────
    // 정상 메시지 처리
    // ─────────────────────────────────────────

    @Test
    @DisplayName("유효한 image.analyzed 메시지를 수신하면 PostgreSQL에 Capture를 저장한다")
    void consume_savesCaptureToDatabaseOnValidMessage() {
        // given
        String message = """
                {
                  "trailId": 1,
                  "streamId": 1,
                  "imagePath": "/images/capture_001.jpg",
                  "roadStatus": "양호",
                  "confidence": 0.95
                }
                """;
        Capture savedCapture = new Capture();
        savedCapture.setTrailId(1);
        savedCapture.setStreamId(1);
        savedCapture.setImagePath("/images/capture_001.jpg");
        savedCapture.setRoadStatus("양호");
        savedCapture.setConfidence(0.95);
        given(captureRepository.save(any(Capture.class))).willReturn(savedCapture);

        // when
        consumer.consume(message);

        // then
        ArgumentCaptor<Capture> captureCaptor = ArgumentCaptor.forClass(Capture.class);
        verify(captureRepository).save(captureCaptor.capture());

        Capture saved = captureCaptor.getValue();
        assertThat(saved.getTrailId()).isEqualTo(1);
        assertThat(saved.getStreamId()).isEqualTo(1);
        assertThat(saved.getImagePath()).isEqualTo("/images/capture_001.jpg");
        assertThat(saved.getRoadStatus()).isEqualTo("양호");
        assertThat(saved.getConfidence()).isEqualTo(0.95);
    }

    @Test
    @DisplayName("유효한 메시지를 처리한 후 Redis에 최신 결과를 캐싱한다")
    void consume_cachesLatestCaptureToRedis() {
        // given
        String message = """
                {
                  "trailId": 1,
                  "streamId": 1,
                  "imagePath": "/images/capture_001.jpg",
                  "roadStatus": "양호",
                  "confidence": 0.95
                }
                """;
        Capture savedCapture = new Capture();
        savedCapture.setTrailId(1);
        savedCapture.setStreamId(1);
        given(captureRepository.save(any(Capture.class))).willReturn(savedCapture);

        // when
        consumer.consume(message);

        // then - Redis key: capture:latest:trail:{trailId}
        verify(valueOperations).set(eq("capture:latest:trail:1"), anyString());
    }

    @Test
    @DisplayName("Redis 캐시 키는 'capture:latest:trail:{trailId}' 형식이다")
    void consume_usesCorrectRedisKey() {
        // given
        String message = """
                {
                  "trailId": 42,
                  "streamId": 1,
                  "imagePath": "/images/capture_042.jpg",
                  "roadStatus": "보통",
                  "confidence": 0.80
                }
                """;
        Capture savedCapture = new Capture();
        savedCapture.setTrailId(42);
        given(captureRepository.save(any(Capture.class))).willReturn(savedCapture);

        // when
        consumer.consume(message);

        // then
        verify(valueOperations).set(eq("capture:latest:trail:42"), anyString());
    }

    @Test
    @DisplayName("메시지의 roadStatus와 confidence가 올바르게 파싱되어 저장된다")
    void consume_parsesRoadStatusAndConfidenceCorrectly() {
        // given
        String message = """
                {
                  "trailId": 1,
                  "streamId": 1,
                  "imagePath": "/images/capture_001.jpg",
                  "roadStatus": "불량",
                  "confidence": 0.73
                }
                """;
        Capture savedCapture = new Capture();
        savedCapture.setTrailId(1);
        given(captureRepository.save(any(Capture.class))).willReturn(savedCapture);

        // when
        consumer.consume(message);

        // then
        ArgumentCaptor<Capture> captor = ArgumentCaptor.forClass(Capture.class);
        verify(captureRepository).save(captor.capture());
        assertThat(captor.getValue().getRoadStatus()).isEqualTo("불량");
        assertThat(captor.getValue().getConfidence()).isEqualTo(0.73);
    }

    // ─────────────────────────────────────────
    // 예외 처리 (잘못된 메시지)
    // ─────────────────────────────────────────

    @Test
    @DisplayName("잘못된 JSON 형식의 메시지를 수신해도 예외가 외부로 전파되지 않는다")
    void consume_doesNotThrowOnInvalidJson() {
        // given
        String invalidMessage = "invalid-json-string";

        // when & then - 예외가 발생하지 않아야 한다
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                consumer.consume(invalidMessage)
        );

        // PostgreSQL 저장 시도 없음
        verify(captureRepository, never()).save(any());
    }

    @Test
    @DisplayName("잘못된 JSON 형식의 메시지를 수신하면 Redis 캐싱을 시도하지 않는다")
    void consume_doesNotCacheOnInvalidJson() {
        // given
        String invalidMessage = "{ broken json }";

        // when
        consumer.consume(invalidMessage);

        // then
        verify(valueOperations, never()).set(anyString(), anyString());
    }

    @Test
    @DisplayName("필수 필드(trailId)가 누락된 메시지를 수신해도 예외가 외부로 전파되지 않는다")
    void consume_doesNotThrowOnMissingRequiredField() {
        // given - trailId 누락
        String messageWithoutTrailId = """
                {
                  "streamId": 1,
                  "imagePath": "/images/capture_001.jpg",
                  "roadStatus": "양호",
                  "confidence": 0.95
                }
                """;

        // when & then
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                consumer.consume(messageWithoutTrailId)
        );
    }
}
