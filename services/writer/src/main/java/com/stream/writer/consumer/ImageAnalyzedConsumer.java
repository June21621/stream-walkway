package com.stream.writer.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stream.writer.command.CaptureCommandHandler;
import com.stream.writer.command.CreateCaptureCommand;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ImageAnalyzedConsumer {

    private final CaptureCommandHandler captureCommandHandler;
    private final ObjectMapper objectMapper;

    public ImageAnalyzedConsumer(CaptureCommandHandler captureCommandHandler,
                                  ObjectMapper objectMapper) {
        this.captureCommandHandler = captureCommandHandler;
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────────────────────
    // image.analyzed 토픽 구독
    // 메시지 수신 → CreateCaptureCommand로 변환 → CaptureCommandHandler에 위임
    // ─────────────────────────────────────────
    @KafkaListener(topics = "image.analyzed", groupId = "writer-group")
    public void consume(String message) {
        try {
            Map<String, Object> data = objectMapper.readValue(message, Map.class);
            System.out.println("[writer] 메시지 수신: " + data);

            CreateCaptureCommand command = new CreateCaptureCommand(
                    Integer.valueOf(data.get("trailId").toString()),
                    Integer.valueOf(data.get("streamId").toString()),
                    data.get("imagePath").toString(),
                    data.get("roadStatus").toString(),
                    Double.valueOf(data.get("confidence").toString())
            );

            captureCommandHandler.handle(command);

        } catch (Exception e) {
            System.err.println("[writer] 처리 실패: " + e.getMessage());
        }
    }
}
