package com.stream.writer.command;

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
@DisplayName("Writer - CaptureCommandHandler 테스트")
class CaptureCommandHandlerTest {

    @Mock
    private CaptureRepository captureRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private CaptureCommandHandler handler;

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
    }

    @Test
    @DisplayName("handle() - Command를 처리하면 PostgreSQL에 Capture를 저장한다")
    void handle_savesCaptureToDatabase() {
        // given
        CreateCaptureCommand command = new CreateCaptureCommand(1, 1, "/images/capture_001.jpg", "양호", 0.95);
        Capture savedCapture = new Capture();
        savedCapture.setTrailId(1);
        savedCapture.setStreamId(1);
        savedCapture.setImagePath("/images/capture_001.jpg");
        savedCapture.setRoadStatus("양호");
        savedCapture.setConfidence(0.95);
        given(captureRepository.save(any(Capture.class))).willReturn(savedCapture);

        // when
        handler.handle(command);

        // then
        ArgumentCaptor<Capture> captor = ArgumentCaptor.forClass(Capture.class);
        verify(captureRepository).save(captor.capture());
        Capture saved = captor.getValue();
        assertThat(saved.getTrailId()).isEqualTo(1);
        assertThat(saved.getStreamId()).isEqualTo(1);
        assertThat(saved.getImagePath()).isEqualTo("/images/capture_001.jpg");
        assertThat(saved.getRoadStatus()).isEqualTo("양호");
        assertThat(saved.getConfidence()).isEqualTo(0.95);
    }

    @Test
    @DisplayName("handle() - Command를 처리한 후 Redis에 최신 결과를 캐싱한다")
    void handle_cachesLatestCaptureToRedis() {
        // given
        CreateCaptureCommand command = new CreateCaptureCommand(1, 1, "/images/capture_001.jpg", "양호", 0.95);
        Capture savedCapture = new Capture();
        savedCapture.setTrailId(1);
        given(captureRepository.save(any(Capture.class))).willReturn(savedCapture);

        // when
        handler.handle(command);

        // then - Redis key: capture:latest:trail:{trailId}
        verify(valueOperations).set(eq("capture:latest:trail:1"), anyString());
    }

    @Test
    @DisplayName("handle() - Redis 캐시 키는 'capture:latest:trail:{trailId}' 형식이다")
    void handle_usesCorrectRedisKey() {
        // given
        CreateCaptureCommand command = new CreateCaptureCommand(42, 1, "/images/capture_042.jpg", "보통", 0.80);
        Capture savedCapture = new Capture();
        savedCapture.setTrailId(42);
        given(captureRepository.save(any(Capture.class))).willReturn(savedCapture);

        // when
        handler.handle(command);

        // then
        verify(valueOperations).set(eq("capture:latest:trail:42"), anyString());
    }
}
