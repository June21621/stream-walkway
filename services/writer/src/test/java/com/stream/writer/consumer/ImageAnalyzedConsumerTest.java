package com.stream.writer.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stream.writer.command.CaptureCommandHandler;
import com.stream.writer.command.CreateCaptureCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("Writer - ImageAnalyzedConsumer 테스트")
class ImageAnalyzedConsumerTest {

    @Mock
    private CaptureCommandHandler captureCommandHandler;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ImageAnalyzedConsumer consumer;

    // ─────────────────────────────────────────
    // 정상 메시지 처리
    // ─────────────────────────────────────────

    @Test
    @DisplayName("유효한 image.analyzed 메시지를 수신하면 CaptureCommandHandler에 위임한다")
    void consume_delegatesToHandlerOnValidMessage() {
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

        // when
        consumer.consume(message);

        // then
        ArgumentCaptor<CreateCaptureCommand> captor = ArgumentCaptor.forClass(CreateCaptureCommand.class);
        verify(captureCommandHandler).handle(captor.capture());

        CreateCaptureCommand command = captor.getValue();
        assertThat(command.trailId()).isEqualTo(1);
        assertThat(command.streamId()).isEqualTo(1);
        assertThat(command.imagePath()).isEqualTo("/images/capture_001.jpg");
        assertThat(command.roadStatus()).isEqualTo("양호");
        assertThat(command.confidence()).isEqualTo(0.95);
    }

    @Test
    @DisplayName("메시지의 roadStatus와 confidence가 올바르게 파싱되어 Command에 담긴다")
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

        // when
        consumer.consume(message);

        // then
        ArgumentCaptor<CreateCaptureCommand> captor = ArgumentCaptor.forClass(CreateCaptureCommand.class);
        verify(captureCommandHandler).handle(captor.capture());
        assertThat(captor.getValue().roadStatus()).isEqualTo("불량");
        assertThat(captor.getValue().confidence()).isEqualTo(0.73);
    }

    // ─────────────────────────────────────────
    // 예외 처리 (잘못된 메시지)
    // ─────────────────────────────────────────

    @Test
    @DisplayName("잘못된 JSON 형식의 메시지를 수신해도 예외가 외부로 전파되지 않는다")
    void consume_doesNotThrowOnInvalidJson() {
        // given
        String invalidMessage = "invalid-json-string";

        // when & then
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                consumer.consume(invalidMessage)
        );

        // Handler 위임 시도 없음
        verify(captureCommandHandler, never()).handle(any());
    }

    @Test
    @DisplayName("잘못된 JSON 형식의 메시지를 수신하면 Handler를 호출하지 않는다")
    void consume_doesNotCallHandlerOnInvalidJson() {
        // given
        String invalidMessage = "{ broken json }";

        // when
        consumer.consume(invalidMessage);

        // then
        verify(captureCommandHandler, never()).handle(any());
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

        verify(captureCommandHandler, never()).handle(any());
    }
}
