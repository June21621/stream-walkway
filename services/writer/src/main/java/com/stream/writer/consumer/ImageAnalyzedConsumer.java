package com.stream.writer.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stream.writer.command.CaptureCommandHandler;
import com.stream.writer.command.CreateCaptureCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ImageAnalyzedConsumer {

    private static final Logger log = LoggerFactory.getLogger(ImageAnalyzedConsumer.class);

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
            log.info("메시지 수신: {}", data);

            CreateCaptureCommand command = new CreateCaptureCommand(
                    Integer.valueOf(data.get("trailId").toString()),
                    Integer.valueOf(data.get("streamId").toString()),
                    data.get("imagePath").toString(),
                    data.get("roadStatus").toString(),
                    Double.valueOf(data.get("confidence").toString())
            );

            captureCommandHandler.handle(command);

        } catch (Exception e) {
            log.error("처리 실패, 메시지를 건너뛴다", e);
        }
    }
}
