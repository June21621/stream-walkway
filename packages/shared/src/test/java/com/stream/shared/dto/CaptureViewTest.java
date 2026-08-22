package com.stream.shared.dto;

import com.stream.shared.entity.Capture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Shared - CaptureView 테스트")
class CaptureViewTest {

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("from() - Capture 엔티티의 모든 필드를 CaptureView로 변환한다")
    void from_mapsAllFieldsFromEntity() throws Exception {
        Capture capture = new Capture();
        setField(capture, "id", 1L);
        capture.setTrailId(2);
        capture.setStreamId(3);
        capture.setImagePath("/images/capture_001.jpg");
        capture.setRoadStatus("양호");
        capture.setConfidence(0.95);
        setField(capture, "createdAt", Instant.parse("2024-01-01T00:00:00Z"));

        CaptureView view = CaptureView.from(capture);

        assertThat(view.id()).isEqualTo(1L);
        assertThat(view.trailId()).isEqualTo(2);
        assertThat(view.streamId()).isEqualTo(3);
        assertThat(view.imagePath()).isEqualTo("/images/capture_001.jpg");
        assertThat(view.roadStatus()).isEqualTo("양호");
        assertThat(view.confidence()).isEqualTo(0.95);
        assertThat(view.createdAt()).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
    }
}
