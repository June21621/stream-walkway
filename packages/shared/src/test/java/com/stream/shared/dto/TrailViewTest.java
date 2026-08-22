package com.stream.shared.dto;

import com.stream.shared.entity.Trail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Shared - TrailView 테스트")
class TrailViewTest {

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("from() - Trail 엔티티를 TrailView로 변환한다 (location은 공백 없는 WKT 문자열)")
    void from_mapsAllFieldsFromEntity() throws Exception {
        Trail trail = new Trail();
        setField(trail, "id", 1L);
        trail.setStreamId(1L);
        trail.setCameraNumber("CAM-001");
        trail.setLocation((org.locationtech.jts.geom.Point)
                new WKTReader().read("POINT(126.97 37.55)"));
        trail.setDirection("북");
        trail.setStatus("active");
        setField(trail, "createdAt", Instant.parse("2024-01-01T00:00:00Z"));

        TrailView view = TrailView.from(trail);

        assertThat(view.id()).isEqualTo(1L);
        assertThat(view.streamId()).isEqualTo(1L);
        assertThat(view.cameraNumber()).isEqualTo("CAM-001");
        assertThat(view.location()).isEqualTo("POINT(126.97 37.55)");
        assertThat(view.direction()).isEqualTo("북");
        assertThat(view.status()).isEqualTo("active");
        assertThat(view.createdAt()).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("from() - WKTWriter가 기본으로 넣는 공백('POINT (')이 제거된다")
    void from_stripsSpaceAfterGeometryKeyword() throws ParseException {
        Trail trail = new Trail();
        trail.setStreamId(1L);
        trail.setCameraNumber("CAM-999");
        trail.setLocation((org.locationtech.jts.geom.Point)
                new WKTReader().read("POINT(0 0)"));
        trail.setStatus("active");

        TrailView view = TrailView.from(trail);

        assertThat(view.location()).doesNotContain("POINT (");
        assertThat(view.location()).startsWith("POINT(");
    }
}
