package com.stream.shared.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Shared - Stream Entity 테스트")
class StreamTest {

    private final WKTReader wktReader = new WKTReader();

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("setName() / getName() - name을 저장하고 반환한다")
    void name_setAndGet() {
        Stream stream = new Stream();
        stream.setName("한강 산책로");
        assertThat(stream.getName()).isEqualTo("한강 산책로");
    }

    @Test
    @DisplayName("setLocation() / getLocation() - LineString을 저장하고 반환한다")
    void location_setAndGet() throws ParseException {
        Stream stream = new Stream();
        LineString line = (LineString) wktReader.read("LINESTRING(126.97 37.55, 126.98 37.56)");

        stream.setLocation(line);

        assertThat(stream.getLocation().toText()).isEqualTo("LINESTRING (126.97 37.55, 126.98 37.56)");
    }

    @Test
    @DisplayName("getId() - 저장 전에는 id가 null이다")
    void getId_isNullBeforePersist() {
        assertThat(new Stream().getId()).isNull();
    }

    @Test
    @DisplayName("getCreatedAt() - onCreate() 호출 전에는 createdAt이 null이다")
    void getCreatedAt_isNullBeforeOnCreate() {
        assertThat(new Stream().getCreatedAt()).isNull();
    }

    @Test
    @DisplayName("@PrePersist - onCreate() 호출 시 createdAt이 현재 시각으로 설정된다")
    void onCreate_setsCreatedAt() throws Exception {
        Stream stream = new Stream();
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        Method onCreateMethod = Stream.class.getDeclaredMethod("onCreate");
        onCreateMethod.setAccessible(true);
        onCreateMethod.invoke(stream);

        LocalDateTime after = LocalDateTime.now().plusSeconds(1);
        assertThat(stream.getCreatedAt()).isNotNull();
        assertThat(stream.getCreatedAt()).isAfterOrEqualTo(before);
        assertThat(stream.getCreatedAt()).isBeforeOrEqualTo(after);
    }

    @Test
    @DisplayName("기본 생성자로 만들면 모든 필드가 null이다")
    void defaultConstructor_allFieldsAreNull() {
        Stream empty = new Stream();
        assertThat(empty.getId()).isNull();
        assertThat(empty.getName()).isNull();
        assertThat(empty.getLocation()).isNull();
        assertThat(empty.getCreatedAt()).isNull();
    }
}
