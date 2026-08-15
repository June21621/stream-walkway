package com.stream.reader.dto;

import com.stream.reader.entity.Capture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Reader - CaptureView 테스트")
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
        setField(capture, "trailId", 2);
        setField(capture, "streamId", 3);
        setField(capture, "imagePath", "/images/capture_001.jpg");
        setField(capture, "roadStatus", "양호");
        setField(capture, "confidence", 0.95);
        setField(capture, "createdAt", LocalDateTime.of(2024, 1, 1, 0, 0, 0));

        CaptureView view = CaptureView.from(capture);

        assertThat(view.id()).isEqualTo(1L);
        assertThat(view.trailId()).isEqualTo(2);
        assertThat(view.streamId()).isEqualTo(3);
        assertThat(view.imagePath()).isEqualTo("/images/capture_001.jpg");
        assertThat(view.roadStatus()).isEqualTo("양호");
        assertThat(view.confidence()).isEqualTo(0.95);
        assertThat(view.createdAt()).isEqualTo(LocalDateTime.of(2024, 1, 1, 0, 0, 0));
    }
}
