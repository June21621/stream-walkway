package com.stream.writer.command;

import com.stream.shared.entity.Stream;
import com.stream.shared.entity.Trail;
import com.stream.writer.repository.StreamRepository;
import com.stream.writer.repository.TrailRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ─────────────────────────────────────────
// GeometryValidator가 왜 필요한지를 진짜 H2 스키마에 묶는다.
//
// 검증 로직 자체는 GeometryValidatorTest가, 핸들러가 그걸 호출하는지는
// StreamCommandHandlerTest/TrailCommandHandlerTest가 증명한다.
// 이 클래스는 검증을 우회했을 때 컬럼이 실제로 어떻게 반응하는지를 기록한다.
// 검증이 삭제되면 3D 케이스가 500으로 돌아간다는 것을 여기서 알 수 있다.
//
// 세 번째 테스트가 중요하다: LINESTRING EMPTY는 컬럼이 받아준다. 즉 우리가
// 이걸 거부하는 것은 DB 제약이 아니라 정책이다. 나중에 "왜 막았지?"라는
// 질문이 나왔을 때 답이 여기 있다.
//
// ⚠️ H2의 에러 문자열은 단언하지 않는다. 예외 타입만 본다
// (TrailCommandHandlerConstraintTest와 같은 이유).
//
// stream_id=1은 services/writer/src/test/resources/data.sql이 시드한다.
// ─────────────────────────────────────────
@DataJpaTest
@DisplayName("Writer - 지오메트리 컬럼 제약 테스트 (H2 테스트 스키마 기준)")
class GeometryColumnConstraintTest {

    @Autowired
    private StreamRepository streamRepository;

    @Autowired
    private TrailRepository trailRepository;

    private static final long SEED_STREAM_ID = 1L;

    private static WKTReader wkt(int srid) {
        return new WKTReader(new GeometryFactory(new PrecisionModel(), srid));
    }

    @Test
    @DisplayName("3D LineString을 검증 없이 직접 저장하면 컬럼이 거부한다 - Z 검사가 필요한 이유")
    void threeDimensionalLineStringIsRejectedByTheColumn() throws ParseException {
        Stream stream = new Stream();
        stream.setName("검증 우회");
        stream.setLocation((LineString) wkt(Stream.SRID)
                .read("LINESTRING Z(126.97 37.55 1, 126.98 37.56 2)"));

        assertThatThrownBy(() -> streamRepository.saveAndFlush(stream))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("POINT EMPTY를 검증 없이 직접 저장하면 컬럼이 거부한다 - Trail EMPTY 검사가 필요한 이유")
    void emptyPointIsRejectedByTheColumn() throws ParseException {
        Trail trail = new Trail();
        trail.setStreamId(SEED_STREAM_ID);
        trail.setCameraNumber("CAM-EMPTY-RAW");
        trail.setLocation((Point) wkt(Trail.SRID).read("POINT EMPTY"));
        trail.setDirection("북");
        trail.setStatus("active");

        assertThatThrownBy(() -> trailRepository.saveAndFlush(trail))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("LINESTRING EMPTY는 컬럼이 받아준다 - 이걸 막는 것은 DB 제약이 아니라 정책이다")
    void emptyLineStringIsAcceptedByTheColumn() throws ParseException {
        Stream stream = new Stream();
        stream.setName("빈 라인스트링");
        stream.setLocation((LineString) wkt(Stream.SRID).read("LINESTRING EMPTY"));

        Stream saved = streamRepository.saveAndFlush(stream);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getLocation().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("2D 지오메트리는 실제로 저장된다 - 검증이 과하게 엄격하지 않음")
    void twoDimensionalGeometryIsPersisted() throws ParseException {
        Stream stream = new Stream();
        stream.setName("정상 하천");
        stream.setLocation((LineString) wkt(Stream.SRID)
                .read("LINESTRING(126.97 37.55, 126.98 37.56)"));

        Stream saved = streamRepository.saveAndFlush(stream);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getLocation().getSRID()).isEqualTo(4326);
    }

    // twoDimensionalGeometryIsPersisted가 Stream 쪽 양성 대조군이다. Trail 쪽에는
    // emptyPointIsRejectedByTheColumn(예외 타입만 확인)의 대조군이 없었다 — 시드 데이터
    // 누락이나 trails_status_check/유니크 제약 같은 무관한 회귀도 같은
    // DataIntegrityViolationException을 던져 그 테스트를 통과시켜 버릴 수 있다.
    @Test
    @DisplayName("2D Point는 실제로 저장된다 - Trail 쪽 양성 대조군 (POINT EMPTY 거부 테스트가 " +
            "저장 경로 전체가 아니라 지오메트리 검사만 잡아냄을 보장)")
    void twoDimensionalPointIsPersisted() throws ParseException {
        Trail trail = new Trail();
        trail.setStreamId(SEED_STREAM_ID);
        trail.setCameraNumber("CAM-2D-OK");
        trail.setLocation((Point) wkt(Trail.SRID).read("POINT(126.97 37.55)"));
        trail.setDirection("북");
        trail.setStatus("active");

        Trail saved = trailRepository.saveAndFlush(trail);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getLocation().getSRID()).isEqualTo(4326);
    }
}
