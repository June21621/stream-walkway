package com.stream.writer.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 처리에 실패한 메시지를 {@code <원본토픽>.dlq} 로 보낸다.
 *
 * <p>Spring의 {@code DeadLetterPublishingRecoverer} 대신 직접 만든 이유가 있다.
 * 그쪽은 실패 사유를 Kafka 헤더에 넣고 본문은 원본 그대로 두는데, ml-service는
 * 본문에 담는다. 그대로 두면 두 DLQ를 서로 다른 방법으로 읽어야 한다.
 * 진단용 데이터일수록 읽는 쪽이 단순해야 한다.
 *
 * <p>페이로드 계약은 {@code apps/ml-service/dlq.py} 와 **같아야 한다.**
 * 설계 문서: {@code docs/superpowers/specs/2026-09-08-kafka-dlq-design.md}
 */
@Component
public class DeadLetterPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterPublisher.class);

    private static final String DLQ_SUFFIX = ".dlq";

    // 스택트레이스를 넣지 않는 것과 같은 이유로 길이를 자른다.
    private static final int REASON_MAX_LEN = 500;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public DeadLetterPublisher(KafkaTemplate<String, String> kafkaTemplate,
                               ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 실패한 메시지를 DLQ로 보낸다. <b>예외를 던지지 않는다.</b>
     *
     * @param sourceTopic 구독 중인 원본 토픽. DLQ 토픽 이름은 여기서 파생된다
     * @param consumer    실패한 주체 ({@code "writer"})
     * @param rawMessage  받은 원문. 파싱하지 않고 그대로 담는다
     * @param cause       실패 원인
     */
    public void publish(String sourceTopic, String consumer, String rawMessage, Exception cause) {
        String topic = sourceTopic + DLQ_SUFFIX;

        String body;
        try {
            body = objectMapper.writeValueAsString(record(sourceTopic, consumer, rawMessage, cause));
        } catch (Exception e) {
            log.error("DLQ 페이로드 조립 실패 topic={} payload={}", topic, rawMessage, e);
            return;
        }

        try {
            kafkaTemplate.send(topic, body).whenComplete((result, ex) -> {
                if (ex != null) {
                    // 마지막 흔적이다. 원본 메시지를 통째로 남긴다.
                    log.error("DLQ 발행 실패 topic={} payload={}", topic, rawMessage, ex);
                } else {
                    log.warn("DLQ 적재 topic={} reason={}", topic, reason(cause));
                }
            });
        } catch (Exception e) {
            // send()가 그 자리에서 던지는 경우(직렬화 실패, 버퍼 가득)도 삼킨다.
            log.error("DLQ 발행 실패 topic={} payload={}", topic, rawMessage, e);
        }
    }

    private Map<String, Object> record(String sourceTopic, String consumer,
                                       String rawMessage, Exception cause) {
        // LinkedHashMap: 필드 순서를 문서와 맞춰 사람이 읽기 좋게 한다.
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("source", sourceTopic);
        record.put("consumer", consumer);
        record.put("failedAt", Instant.now().toString());
        record.put("reason", reason(cause));
        record.put("payload", rawMessage);
        return record;
    }

    private String reason(Exception cause) {
        String text = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        return text.length() <= REASON_MAX_LEN ? text : text.substring(0, REASON_MAX_LEN);
    }
}
