package com.stream.writer.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Writer - DeadLetterPublisher 테스트")
class DeadLetterPublisherTest {

    // 페이로드 계약은 apps/ml-service/tests/test_dlq.py 와 같아야 한다.
    // 한쪽만 고치면 두 DLQ를 다른 방법으로 읽어야 한다.

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private DeadLetterPublisher publisher;

    private final ObjectMapper reader = new ObjectMapper();

    private JsonNode publishAndCapture(String rawMessage, Exception cause) throws Exception {
        when(kafkaTemplate.send(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        publisher.publish("image.analyzed", "writer", rawMessage, cause);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(anyString(), body.capture());
        return reader.readTree(body.getValue());
    }

    @Test
    @DisplayName("원본 토픽 이름에 .dlq 를 붙여 발행한다")
    void publish_appendsDlqSuffixToSourceTopic() {
        when(kafkaTemplate.send(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        publisher.publish("image.analyzed", "writer", "{}", new IllegalStateException("boom"));

        ArgumentCaptor<String> topic = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topic.capture(), anyString());
        assertThat(topic.getValue()).isEqualTo("image.analyzed.dlq");
    }

    @Test
    @DisplayName("payload 에 원본 메시지를 문자열 그대로 담는다")
    void publish_keepsRawMessageAsString() throws Exception {
        JsonNode node = publishAndCapture("{ broken json }", new IllegalStateException("boom"));
        assertThat(node.get("payload").asText()).isEqualTo("{ broken json }");
    }

    @Test
    @DisplayName("reason 은 '예외타입: 메시지' 한 줄이다")
    void publish_reasonIsTypeAndMessage() throws Exception {
        JsonNode node = publishAndCapture("{}", new IllegalStateException("DB down"));
        assertThat(node.get("reason").asText()).isEqualTo("IllegalStateException: DB down");
    }

    @Test
    @DisplayName("reason 이 500자를 넘으면 자른다")
    void publish_truncatesReasonAt500() throws Exception {
        JsonNode node = publishAndCapture("{}", new IllegalStateException("x".repeat(900)));
        assertThat(node.get("reason").asText()).hasSize(500);
    }

    @Test
    @DisplayName("failedAt 은 Z 로 끝나는 UTC 시각이다")
    void publish_failedAtIsUtcWithZ() throws Exception {
        JsonNode node = publishAndCapture("{}", new IllegalStateException("boom"));
        assertThat(node.get("failedAt").asText()).endsWith("Z");
    }

    @Test
    @DisplayName("필드는 정확히 다섯 개다 (ml-service 와 같은 모양)")
    void publish_hasExactlyFiveFields() throws Exception {
        JsonNode node = publishAndCapture("{}", new IllegalStateException("boom"));
        assertThat(node.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("source", "consumer", "failedAt", "reason", "payload");
    }

    @Test
    @DisplayName("source 와 consumer 를 그대로 담는다")
    void publish_recordsSourceAndConsumer() throws Exception {
        JsonNode node = publishAndCapture("{}", new IllegalStateException("boom"));
        assertThat(node.get("source").asText()).isEqualTo("image.analyzed");
        assertThat(node.get("consumer").asText()).isEqualTo("writer");
    }

    @Test
    @DisplayName("발행이 비동기로 실패해도 예외가 밖으로 나오지 않는다")
    void publish_doesNotThrowWhenSendFailsAsynchronously() {
        when(kafkaTemplate.send(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        assertDoesNotThrow(() ->
                publisher.publish("image.analyzed", "writer", "{}", new IllegalStateException("boom")));
    }

    @Test
    @DisplayName("send() 가 그 자리에서 던져도 예외가 밖으로 나오지 않는다")
    void publish_doesNotThrowWhenSendThrowsSynchronously() {
        when(kafkaTemplate.send(anyString(), anyString()))
                .thenThrow(new RuntimeException("buffer full"));

        assertDoesNotThrow(() ->
                publisher.publish("image.analyzed", "writer", "{}", new IllegalStateException("boom")));
    }

    @Test
    @DisplayName("메시지가 null 인 예외도 reason 을 만든다")
    void publish_handlesExceptionWithNullMessage() throws Exception {
        JsonNode node = publishAndCapture("{}", new NullPointerException());
        assertThat(node.get("reason").asText()).isEqualTo("NullPointerException: null");
    }
}
