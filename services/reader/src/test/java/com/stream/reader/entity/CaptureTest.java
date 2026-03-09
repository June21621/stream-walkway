package com.stream.reader.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Reader - Capture Entity 테스트")
class CaptureTest {

    // Capture는 setter가 없으므로 리플렉션으로 필드 설정
    private Capture capture;

    @BeforeEach
    void setUp() throws Exception {
        capture = new Capture();
        setField(capture, "id", 1L);
        setField(capture, "trailId", 2);
        setField(capture, "streamId", 3);
        setField(capture, "imagePath", "/images/capture_001.jpg");
        setField(capture, "roadStatus", "양호");
        setField(capture, "confidence", 0.95);
        setField(capture, "createdAt", LocalDateTime.of(2024, 1, 1, 0, 0, 0));
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ─────────────────────────────────────────
    // Getter 테스트
    // ─────────────────────────────────────────

    @Test
    @DisplayName("getId() - id를 반환한다")
    void getId_returnsId() {
        assertThat(capture.getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("getTrailId() - trailId를 반환한다")
    void getTrailId_returnsTrailId() {
        assertThat(capture.getTrailId()).isEqualTo(2);
    }

    @Test
    @DisplayName("getStreamId() - streamId를 반환한다")
    void getStreamId_returnsStreamId() {
        assertThat(capture.getStreamId()).isEqualTo(3);
    }

    @Test
    @DisplayName("getImagePath() - imagePath를 반환한다")
    void getImagePath_returnsImagePath() {
        assertThat(capture.getImagePath()).isEqualTo("/images/capture_001.jpg");
    }

    @Test
    @DisplayName("getRoadStatus() - roadStatus를 반환한다")
    void getRoadStatus_returnsRoadStatus() {
        assertThat(capture.getRoadStatus()).isEqualTo("양호");
    }

    @Test
    @DisplayName("getConfidence() - confidence를 반환한다")
    void getConfidence_returnsConfidence() {
        assertThat(capture.getConfidence()).isEqualTo(0.95);
    }

    @Test
    @DisplayName("getCreatedAt() - createdAt을 반환한다")
    void getCreatedAt_returnsCreatedAt() {
        assertThat(capture.getCreatedAt()).isEqualTo(LocalDateTime.of(2024, 1, 1, 0, 0, 0));
    }

    // ─────────────────────────────────────────
    // 기본 상태 테스트
    // ─────────────────────────────────────────

    @Test
    @DisplayName("기본 생성자로 만들면 모든 필드가 null이다")
    void defaultConstructor_allFieldsAreNull() {
        Capture empty = new Capture();
        assertThat(empty.getId()).isNull();
        assertThat(empty.getTrailId()).isNull();
        assertThat(empty.getStreamId()).isNull();
        assertThat(empty.getImagePath()).isNull();
        assertThat(empty.getRoadStatus()).isNull();
        assertThat(empty.getConfidence()).isNull();
        assertThat(empty.getCreatedAt()).isNull();
    }
}
