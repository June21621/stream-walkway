package com.stream.writer.command;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

// ─────────────────────────────────────────
// WKT로 들어온 위치 지오메트리가 컬럼에 저장 가능한 형태인지 검사한다.
//
// streams.location은 GEOMETRY(LINESTRING,4326), trails.location은 GEOMETRY(POINT,4326)이다
// (infra/scripts/init-db.sql:14,23 / services/writer/src/test/resources/schema.sql:19,27).
// 이 typmod는 2D 지오메트리만 받는다. 그런데 JTS의 WKTReader는 Z/M/ZM 좌표를 아무 불평 없이
// 파싱하고 (Point)/(LineString) 캐스트도 타입이 맞아 통과하므로, 검사가 없으면 save()까지
// 도달해 (H2 기준) DataIntegrityViolationException(SQLState 22018) → 500이 된다.
// ⚠️ 이 22018/500 관찰은 전부 H2에서 실측한 것이다. Docker가 내려가 있어 이 브랜치는
// PostgreSQL/PostGIS를 한 번도 실측하지 못했다. Z/M/ZM 거부는 PostGIS typmod도 같은
// 의미론이라 안전하지만, POINT EMPTY가 22018인지는 PostGIS에서 확인 전이다.
//
// ⚠️ 차원 판별에 CoordinateSequence.getDimension()을 쓰면 안 된다. JTS 1.19.0에서 실측한
// 결과 순수 2D인 "POINT(126.97 37.55)"에도 3을 반환한다 — CoordinateArraySequence가 기본
// Coordinate 클래스로부터 차원을 추론하기 때문이다. 그래서 좌표의 Z/M이 NaN인지를 본다.
// NaN 검사는 2D / Z / M / ZM / 옛 JTS 3좌표 문법을 정확히 구분한다(실측 확인).
//
// 빈 지오메트리 검사가 먼저인 이유: 빈 지오메트리에는 볼 좌표가 없어 차원 판단의 근거가 없다.
// 참고로 GEOMETRY(POINT,4326)은 POINT EMPTY를 거부하지만 GEOMETRY(LINESTRING,4326)은
// LINESTRING EMPTY를 받아준다(실측). 둘 다 거부하는 것은 DB 제약이 아니라 정책 결정이다 —
// 좌표 없는 하천/카메라 위치는 도메인상 의미가 없고, 두 엔드포인트가 같게 동작해야 한다.
// ─────────────────────────────────────────
final class GeometryValidator {

    // SRID 4326은 WGS84 경위도 좌표계다. 경도 ±180, 위도 ±90을 벗어난 값은 존재하지 않는다.
    // 컬럼이 이 범위를 강제하지는 않으므로(POINT(999 999)가 실제로 저장된다) 500은 아니지만,
    // 지도에 찍을 수 없는 좌표가 조용히 쌓이는 것을 막는다. 경계값은 포함한다.
    private static final double MAX_ABS_LONGITUDE = 180.0;
    private static final double MAX_ABS_LATITUDE = 90.0;

    private GeometryValidator() {
    }

    static void validateLocation(Geometry geometry) {
        if (geometry.isEmpty()) {
            throw new IllegalArgumentException("location must not be an empty geometry");
        }
        // 루프를 두 번 도는 것은 의도적이다. 한 루프에서 두 검사를 하면
        // "LINESTRING(999 999, 1 1 5)" 같은 입력에서 범위 메시지가 먼저 나와
        // 어느 규칙을 어겼는지가 좌표 순서에 따라 달라진다. 지오메트리 하나의
        // 좌표 수는 많아야 수백 개라 비용은 무의미하다.
        Coordinate[] coordinates = geometry.getCoordinates();
        for (Coordinate coordinate : coordinates) {
            if (!Double.isNaN(coordinate.getZ()) || !Double.isNaN(coordinate.getM())) {
                throw new IllegalArgumentException(
                        "location must have 2D coordinates only (Z/M ordinates are not supported)");
            }
        }
        for (Coordinate coordinate : coordinates) {
            if (Math.abs(coordinate.getX()) > MAX_ABS_LONGITUDE
                    || Math.abs(coordinate.getY()) > MAX_ABS_LATITUDE) {
                throw new IllegalArgumentException(
                        "location coordinate out of WGS84 bounds: ("
                                + coordinate.getX() + ", " + coordinate.getY() + ")");
            }
        }
    }
}
