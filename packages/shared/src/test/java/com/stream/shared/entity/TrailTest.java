package com.stream.shared.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Shared - Trail Entity 테스트")
class TrailTest {

    private final WKTReader wktReader = new WKTReader();

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("setStreamId() / getStreamId() - streamId를 저장하고 반환한다")
    void streamId_setAndGet() {
        Trail trail = new Trail();
        trail.setStreamId(1L);
        assertThat(trail.getStreamId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("setCameraNumber() / getCameraNumber() - cameraNumber를 저장하고 반환한다")
    void cameraNumber_setAndGet() {
        Trail trail = new Trail();
        trail.setCameraNumber("CAM-001");
        assertThat(trail.getCameraNumber()).isEqualTo("CAM-001");
    }

    @Test
    @DisplayName("setLocation() / getLocation() - Point를 저장하고 반환한다")
    void location_setAndGet() throws ParseException {
        Trail trail = new Trail();
        Point point = (Point) wktReader.read("POINT(126.97 37.55)");

        trail.setLocation(point);

        assertThat(trail.getLocation().toText()).isEqualTo("POINT (126.97 37.55)");
    }

    @Test
    @DisplayName("setDirection() / getDirection() - direction을 저장하고 반환한다")
    void direction_setAndGet() {
        Trail trail = new Trail();
        trail.setDirection("북");
        assertThat(trail.getDirection()).isEqualTo("북");
    }

    @Test
    @DisplayName("setStatus() / getStatus() - status를 저장하고 반환한다")
    void status_setAndGet() {
        Trail trail = new Trail();
        trail.setStatus("active");
        assertThat(trail.getStatus()).isEqualTo("active");
    }

    @Test
    @DisplayName("getId() - 저장 전에는 id가 null이다")
    void getId_isNullBeforePersist() {
        assertThat(new Trail().getId()).isNull();
    }

    @Test
    @DisplayName("getCreatedAt() - onCreate() 호출 전에는 createdAt이 null이다")
    void getCreatedAt_isNullBeforeOnCreate() {
        assertThat(new Trail().getCreatedAt()).isNull();
    }

    @Test
    @DisplayName("@PrePersist - onCreate() 호출 시 createdAt이 현재 시각으로 설정된다")
    void onCreate_setsCreatedAt() throws Exception {
        Trail trail = new Trail();
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        Method onCreateMethod = Trail.class.getDeclaredMethod("onCreate");
        onCreateMethod.setAccessible(true);
        onCreateMethod.invoke(trail);

        LocalDateTime after = LocalDateTime.now().plusSeconds(1);
        assertThat(trail.getCreatedAt()).isNotNull();
        assertThat(trail.getCreatedAt()).isAfterOrEqualTo(before);
        assertThat(trail.getCreatedAt()).isBeforeOrEqualTo(after);
    }

    @Test
    @DisplayName("기본 생성자로 만들면 모든 필드가 null이다")
    void defaultConstructor_allFieldsAreNull() {
        Trail empty = new Trail();
        assertThat(empty.getId()).isNull();
        assertThat(empty.getStreamId()).isNull();
        assertThat(empty.getCameraNumber()).isNull();
        assertThat(empty.getLocation()).isNull();
        assertThat(empty.getDirection()).isNull();
        assertThat(empty.getStatus()).isNull();
        assertThat(empty.getCreatedAt()).isNull();
    }
}
