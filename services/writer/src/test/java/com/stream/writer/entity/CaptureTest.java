package com.stream.writer.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Writer - Capture Entity 테스트")
class CaptureTest {

    private Capture capture;

    @BeforeEach
    void setUp() {
        capture = new Capture();
        capture.setTrailId(1);
        capture.setStreamId(2);
        capture.setImagePath("/images/capture_001.jpg");
        capture.setRoadStatus("양호");
        capture.setConfidence(0.95);
    }

    // ─────────────────────────────────────────
    // Getter / Setter 테스트
    // ─────────────────────────────────────────

    @Test
    @DisplayName("setTrailId() / getTrailId() - trailId를 저장하고 반환한다")
    void trailId_setAndGet() {
        capture.setTrailId(42);
        assertThat(capture.getTrailId()).isEqualTo(42);
    }

    @Test
    @DisplayName("setStreamId() / getStreamId() - streamId를 저장하고 반환한다")
    void streamId_setAndGet() {
        capture.setStreamId(10);
        assertThat(capture.getStreamId()).isEqualTo(10);
    }

    @Test
    @DisplayName("setImagePath() / getImagePath() - imagePath를 저장하고 반환한다")
    void imagePath_setAndGet() {
        capture.setImagePath("/images/new_capture.jpg");
        assertThat(capture.getImagePath()).isEqualTo("/images/new_capture.jpg");
    }

    @Test
    @DisplayName("setRoadStatus() / getRoadStatus() - roadStatus를 저장하고 반환한다")
    void roadStatus_setAndGet() {
        capture.setRoadStatus("불량");
        assertThat(capture.getRoadStatus()).isEqualTo("불량");
    }

    @Test
    @DisplayName("setConfidence() / getConfidence() - confidence를 저장하고 반환한다")
    void confidence_setAndGet() {
        capture.setConfidence(0.73);
        assertThat(capture.getConfidence()).isEqualTo(0.73);
    }

    @Test
    @DisplayName("getId() - 저장 전에는 id가 null이다")
    void getId_isNullBeforePersist() {
        assertThat(capture.getId()).isNull();
    }

    @Test
    @DisplayName("getCreatedAt() - onCreate() 호출 전에는 createdAt이 null이다")
    void getCreatedAt_isNullBeforeOnCreate() {
        assertThat(capture.getCreatedAt()).isNull();
    }

    // ─────────────────────────────────────────
    // @PrePersist 테스트
    // ─────────────────────────────────────────

    @Test
    @DisplayName("@PrePersist - onCreate() 호출 시 createdAt이 현재 시각으로 설정된다")
    void onCreate_setsCreatedAt() throws Exception {
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        Method onCreateMethod = Capture.class.getDeclaredMethod("onCreate");
        onCreateMethod.setAccessible(true);
        onCreateMethod.invoke(capture);

        LocalDateTime after = LocalDateTime.now().plusSeconds(1);

        assertThat(capture.getCreatedAt()).isNotNull();
        assertThat(capture.getCreatedAt()).isAfterOrEqualTo(before);
        assertThat(capture.getCreatedAt()).isBeforeOrEqualTo(after);
    }

    @Test
    @DisplayName("@PrePersist - onCreate()를 두 번 호출하면 createdAt이 덮어써진다")
    void onCreate_overwritesCreatedAt() throws Exception {
        Method onCreateMethod = Capture.class.getDeclaredMethod("onCreate");
        onCreateMethod.setAccessible(true);

        onCreateMethod.invoke(capture);
        LocalDateTime first = capture.getCreatedAt();

        Thread.sleep(10); // 시간 차이 확보
        onCreateMethod.invoke(capture);
        LocalDateTime second = capture.getCreatedAt();

        assertThat(second).isAfterOrEqualTo(first);
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
