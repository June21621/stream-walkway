package com.stream.shared.dto;

import com.stream.shared.entity.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Shared - StreamView 테스트")
class StreamViewTest {

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("from() - Stream 엔티티를 StreamView로 변환한다 (location은 공백 없는 WKT 문자열)")
    void from_mapsAllFieldsFromEntity() throws Exception {
        Stream stream = new Stream();
        setField(stream, "id", 1L);
        stream.setName("한강 산책로");
        stream.setLocation((org.locationtech.jts.geom.LineString)
                new WKTReader().read("LINESTRING(126.97 37.55, 126.98 37.56)"));
        setField(stream, "createdAt", LocalDateTime.of(2024, 1, 1, 0, 0, 0));

        StreamView view = StreamView.from(stream);

        assertThat(view.id()).isEqualTo(1L);
        assertThat(view.name()).isEqualTo("한강 산책로");
        assertThat(view.location()).isEqualTo("LINESTRING(126.97 37.55, 126.98 37.56)");
        assertThat(view.createdAt()).isEqualTo(LocalDateTime.of(2024, 1, 1, 0, 0, 0));
    }

    @Test
    @DisplayName("from() - WKTWriter가 기본으로 넣는 공백('LINESTRING (')이 제거된다")
    void from_stripsSpaceAfterGeometryKeyword() throws ParseException {
        Stream stream = new Stream();
        stream.setName("테스트");
        stream.setLocation((org.locationtech.jts.geom.LineString)
                new WKTReader().read("LINESTRING(0 0, 1 1)"));

        StreamView view = StreamView.from(stream);

        assertThat(view.location()).doesNotContain("LINESTRING (");
        assertThat(view.location()).startsWith("LINESTRING(");
    }
}
