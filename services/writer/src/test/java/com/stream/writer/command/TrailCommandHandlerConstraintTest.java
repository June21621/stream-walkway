package com.stream.writer.command;

import com.stream.shared.entity.Stream;
import com.stream.shared.entity.Trail;
import com.stream.writer.exception.DuplicateTrailException;
import com.stream.writer.repository.StreamRepository;
import com.stream.writer.repository.TrailRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import org.hibernate.Session;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

// ─────────────────────────────────────────
// 손으로 쓴 가짜 예외 메시지가 아니라 H2가 실제로 일으킨 제약 위반으로
// TrailCommandHandler의 FK/UNIQUE 분기를 검증한다.
//
// 이 테스트가 존재하는 이유: 이전에는 테스트 스키마에 FK가 아예 없어서
// 어떤 자동화 테스트도 진짜 제약 위반을 만들 수 없었고, 그 때문에
// "없는 stream_id로 Trail 생성 시 500" 버그가 유닛 테스트 176개를
// 통과하면서도 Docker 실기동에서야 발견됐다.
//
// ⚠️ H2의 에러 문자열 자체는 단언하지 않는다. H2의 UNIQUE 위반 메시지는
// 제약 이름이 아니라 인덱스 이름(..._KEY_INDEX_9)을 담고, 뒤의 숫자는
// H2 내부 객체 카운터라 불안정하다. 예외 타입과 동작만 검증한다.
// 실제 Postgres 문자열 검증은 TrailCommandHandlerPostgresTest가 담당한다.
// ─────────────────────────────────────────
@DataJpaTest
@DisplayName("Writer - TrailCommandHandler 실제 제약 위반 테스트 (H2)")
class TrailCommandHandlerConstraintTest {

    @Autowired
    private TrailRepository trailRepository;

    @Autowired
    private StreamRepository streamRepository;

    @Autowired
    private TestEntityManager entityManager;

    private static final long SEED_STREAM_ID = 1L;

    private static WKTReader wkt() {
        return new WKTReader(new GeometryFactory(new PrecisionModel(), Trail.SRID));
    }

    // 선체크를 항상 통과시키는 핸들러. 존재 확인과 저장 사이에 하천이 삭제된
    // 경쟁 상황을 재현해서 FK catch 분기까지 도달하게 만든다.
    private TrailCommandHandler handlerThatSkipsExistenceCheck() {
        StreamRepository alwaysExists = mock(StreamRepository.class);
        given(alwaysExists.existsById(anyLong())).willReturn(true);
        return new TrailCommandHandler(trailRepository, alwaysExists);
    }

    @Test
    @DisplayName("진짜 FK 제약 위반이 IllegalArgumentException(400 경로)으로 변환된다")
    void realForeignKeyViolationBecomesIllegalArgumentException() {
        TrailCommandHandler handler = handlerThatSkipsExistenceCheck();
        CreateTrailCommand command =
                new CreateTrailCommand(999999L, "CAM-FK", "POINT(126.97 37.55)", "N", "active");

        assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999999")
                .hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("진짜 UNIQUE 제약 위반이 DuplicateTrailException(409 경로)으로 변환된다")
    void realUniqueViolationBecomesDuplicateTrailException() throws ParseException {
        TrailCommandHandler handler = new TrailCommandHandler(trailRepository, streamRepository);
        CreateTrailCommand first =
                new CreateTrailCommand(SEED_STREAM_ID, "CAM-DUP", "POINT(126.97 37.55)", "N", "active");
        handler.handle(first);

        CreateTrailCommand duplicate =
                new CreateTrailCommand(SEED_STREAM_ID, "CAM-DUP", "POINT(126.97 37.55)", "N", "active");

        assertThatThrownBy(() -> handler.handle(duplicate))
                .isInstanceOf(DuplicateTrailException.class)
                .hasMessageContaining("CAM-DUP");
    }

    @Test
    @DisplayName("리포지토리 수준에서도 FK 위반이 실제로 발생한다 (스키마에 제약이 살아있는지 확인)")
    void schemaActuallyEnforcesForeignKey() throws ParseException {
        Trail orphan = new Trail();
        orphan.setStreamId(999999L);
        orphan.setCameraNumber("CAM-ORPHAN");
        orphan.setLocation((org.locationtech.jts.geom.Point) wkt().read("POINT(126.97 37.55)"));
        orphan.setStatus("active");

        assertThatThrownBy(() -> trailRepository.save(orphan))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("geometry가 SRID 4326을 유지한 채 DB를 왕복한다")
    void geometrySurvivesRoundTrip() throws ParseException {
        TrailCommandHandler handler = new TrailCommandHandler(trailRepository, streamRepository);
        Trail saved = handler.handle(
                new CreateTrailCommand(SEED_STREAM_ID, "CAM-GEO", "POINT(126.97 37.55)", "N", "active"));

        entityManager.flush();
        entityManager.clear();

        Trail reloaded = trailRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getLocation().getSRID()).isEqualTo(4326);
        assertThat(reloaded.getLocation().getX()).isEqualTo(126.97);
        assertThat(reloaded.getLocation().getY()).isEqualTo(37.55);
    }

    @Test
    @DisplayName("createdAt이 정확히 왕복하고, created_at 컬럼이 실제로 TIMESTAMPTZ임을 raw JDBC 타입으로 증명한다")
    void createdAtSurvivesRoundTripAndColumnIsTimestamptz() throws ParseException {
        TrailCommandHandler handler = new TrailCommandHandler(trailRepository, streamRepository);

        Instant before = Instant.now().minusSeconds(1);
        Trail saved = handler.handle(
                new CreateTrailCommand(SEED_STREAM_ID, "CAM-TS", "POINT(126.97 37.55)", "N", "active"));
        Instant after = Instant.now().plusSeconds(1);

        Instant inMemory = saved.getCreatedAt();
        assertThat(inMemory).isBetween(before, after);

        entityManager.flush();
        entityManager.clear();

        // 이 왕복 비교만으로는 컬럼이 TIMESTAMP인지 TIMESTAMP WITH TIME ZONE인지 구분하지 못한다.
        // Hibernate가 쓰기와 읽기에 같은 JVM 기본 시간대 변환을 적용하고 그 변환은 self-inverse라서,
        // 한 프로세스 안에서는 어느 쪽이든 값이 그대로 돌아온다(실측 확인).
        // 여기서 잡히는 건 값이 멈췄거나 정밀도가 깎이는 경우다.
        Trail reloaded = trailRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getCreatedAt()).isBetween(before, after);
        assertThat(reloaded.getCreatedAt().getEpochSecond()).isEqualTo(inMemory.getEpochSecond());

        // 컬럼 타입은 raw JDBC 값의 런타임 타입으로 확인한다.
        // H2에서 TIMESTAMP WITH TIME ZONE 컬럼은 OffsetDateTime으로 읽히지만 평범한 TIMESTAMP는
        // 그렇지 않으므로, 스키마가 TIMESTAMP로 되돌아가면 실제로 실패하는 진짜 판별 기준이다.
        Object rawCreatedAt = entityManager.getEntityManager()
                .unwrap(Session.class)
                .doReturningWork(connection -> {
                    try (PreparedStatement stmt = connection.prepareStatement(
                            "SELECT created_at FROM trails WHERE id = ?")) {
                        stmt.setLong(1, saved.getId());
                        try (ResultSet rs = stmt.executeQuery()) {
                            rs.next();
                            return rs.getObject("created_at");
                        }
                    }
                });
        assertThat(rawCreatedAt).isInstanceOf(OffsetDateTime.class);
    }

    @Test
    @DisplayName("ON DELETE CASCADE가 동작한다 - 하천을 지우면 산책로도 사라진다")
    void deletingStreamCascadesToTrails() throws ParseException {
        Stream stream = new Stream();
        stream.setName("cascade-target");
        stream.setLocation((LineString) wkt().read("LINESTRING(126.97 37.55, 126.98 37.56)"));
        Stream savedStream = streamRepository.save(stream);

        TrailCommandHandler handler = new TrailCommandHandler(trailRepository, streamRepository);
        Trail trail = handler.handle(
                new CreateTrailCommand(savedStream.getId(), "CAM-CASCADE", "POINT(126.97 37.55)", "N", "active"));
        Long trailId = trail.getId();

        entityManager.flush();
        streamRepository.deleteById(savedStream.getId());
        entityManager.flush();
        entityManager.clear();

        assertThat(trailRepository.findById(trailId)).isEmpty();
    }
}
