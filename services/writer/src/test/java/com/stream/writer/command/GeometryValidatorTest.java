package com.stream.writer.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ─────────────────────────────────────────
// 검증 규칙 자체를 WKT 단위로 증명한다. Spring도 Mockito도 쓰지 않는다.
// 핸들러가 이 검증을 실제로 호출하는지는 StreamCommandHandlerTest와
// TrailCommandHandlerTest가, 컬럼이 정말 이런 값을 거부하는지는
// GeometryColumnConstraintTest가 증명한다.
// ─────────────────────────────────────────
@DisplayName("Writer - GeometryValidator 테스트")
class GeometryValidatorTest {

    private static Geometry parse(String wkt) {
        try {
            return new WKTReader(new GeometryFactory(new PrecisionModel(), 4326)).read(wkt);
        } catch (ParseException e) {
            throw new IllegalStateException("테스트 입력이 파싱되지 않는다: " + wkt, e);
        }
    }

    @Test
    @DisplayName("validateLocation() - 2D 지오메트리는 통과시킨다")
    void acceptsTwoDimensionalGeometries() {
        for (String wkt : new String[]{
                "POINT(126.97 37.55)",
                "LINESTRING(126.97 37.55, 126.98 37.56)"}) {
            assertThatCode(() -> GeometryValidator.validateLocation(parse(wkt)))
                    .as(wkt)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("validateLocation() - Z/M/ZM 좌표는 거부한다")
    void rejectsHigherDimensionCoordinates() {
        for (String wkt : new String[]{
                "POINT Z(126.97 37.55 1)",
                "POINT M(126.97 37.55 1)",
                "POINT ZM(126.97 37.55 1 9)",
                "LINESTRING Z(126.97 37.55 1, 126.98 37.56 2)",
                "LINESTRING M(126.97 37.55 1, 126.98 37.56 2)",
                "LINESTRING ZM(126.97 37.55 1 9, 126.98 37.56 2 9)"}) {
            assertThatThrownBy(() -> GeometryValidator.validateLocation(parse(wkt)))
                    .as(wkt)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("2D");
        }
    }

    @Test
    @DisplayName("validateLocation() - Z 키워드 없는 옛 JTS 3좌표 문법도 거부한다")
    void rejectsOldJtsThreeOrdinateSyntax() {
        for (String wkt : new String[]{
                "POINT(126.97 37.55 1)",
                "LINESTRING(126.97 37.55 1, 126.98 37.56 2)"}) {
            assertThatThrownBy(() -> GeometryValidator.validateLocation(parse(wkt)))
                    .as(wkt)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("2D");
        }
    }

    @Test
    @DisplayName("validateLocation() - 빈 지오메트리는 양쪽 다 거부한다")
    void rejectsEmptyGeometries() {
        for (String wkt : new String[]{"POINT EMPTY", "LINESTRING EMPTY"}) {
            assertThatThrownBy(() -> GeometryValidator.validateLocation(parse(wkt)))
                    .as(wkt)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("empty");
        }
    }

    @Test
    @DisplayName("validateLocation() - 빈 지오메트리를 차원 메시지가 아니라 empty 메시지로 거부한다 (검사 순서)")
    void reportsEmptyBeforeDimension() {
        assertThatThrownBy(() -> GeometryValidator.validateLocation(parse("POINT EMPTY")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("2D");
    }

    @Test
    @DisplayName("validateLocation() - WGS84 범위를 벗어난 좌표는 거부한다")
    void rejectsCoordinatesOutOfWgs84Bounds() {
        for (String wkt : new String[]{
                "POINT(999 999)",
                "LINESTRING(999 999, 1000 1000)",
                "POINT(180.1 37.55)",
                "POINT(126.97 90.1)",
                "POINT(-180.1 37.55)",
                "POINT(126.97 -90.1)",
                // 첫 좌표는 정상이고 두 번째만 범위를 벗어난 경우도 잡아야 한다
                "LINESTRING(126.97 37.55, 999 37.56)"}) {
            assertThatThrownBy(() -> GeometryValidator.validateLocation(parse(wkt)))
                    .as(wkt)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("WGS84 bounds");
        }
    }

    @Test
    @DisplayName("validateLocation() - 경계값 ±180 / ±90은 통과시킨다")
    void acceptsCoordinatesAtWgs84Bounds() {
        for (String wkt : new String[]{
                "POINT(180 90)",
                "POINT(-180 -90)",
                "LINESTRING(-180 -90, 180 90)"}) {
            assertThatCode(() -> GeometryValidator.validateLocation(parse(wkt)))
                    .as(wkt)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("validateLocation() - 범위와 차원이 둘 다 틀리면 차원 메시지가 먼저 나온다 (검사 순서)")
    void reportsDimensionBeforeBounds() {
        assertThatThrownBy(() -> GeometryValidator.validateLocation(parse("POINT Z(999 999 1)")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2D");
    }
}
