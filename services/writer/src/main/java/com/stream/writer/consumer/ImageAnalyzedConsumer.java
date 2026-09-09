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

    // @KafkaListener의 topics와 DLQ 발행이 같은 값을 봐야 한다.
    private static final String SOURCE_TOPIC = "image.analyzed";
    private static final String CONSUMER_NAME = "writer";

    private final CaptureCommandHandler captureCommandHandler;
    private final ObjectMapper objectMapper;
    private final DeadLetterPublisher deadLetterPublisher;

    public ImageAnalyzedConsumer(CaptureCommandHandler captureCommandHandler,
                                  ObjectMapper objectMapper,
                                  DeadLetterPublisher deadLetterPublisher) {
        this.captureCommandHandler = captureCommandHandler;
        this.objectMapper = objectMapper;
        this.deadLetterPublisher = deadLetterPublisher;
    }

    // ─────────────────────────────────────────
    // image.analyzed 토픽 구독
    // 메시지 수신 → CreateCaptureCommand로 변환 → CaptureCommandHandler에 위임
    // ─────────────────────────────────────────
    @KafkaListener(topics = SOURCE_TOPIC, groupId = "writer-group")
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
            // 예전에는 여기서 메시지를 버렸다. 이제 DLQ로 보낸다.
            //
            // 파싱 실패든 DB 장애든 구분하지 않는다. 재시도가 없으므로
            // DB 장애 중에는 정상 메시지도 DLQ로 밀리는데, 그 대가를 알고
            // 고른 설계다 - 유실 0이 우선이고 reason으로 골라낼 수 있다.
            log.error("처리 실패, DLQ로 보낸다", e);
            deadLetterPublisher.publish(SOURCE_TOPIC, CONSUMER_NAME, message, e);
        }
    }
}
