package com.stream.writer.command;

import com.stream.shared.entity.Trail;
import com.stream.writer.exception.DuplicateTrailException;
import com.stream.writer.repository.StreamRepository;
import com.stream.writer.repository.TrailRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

// ─────────────────────────────────────────
// TrailCommandHandler의 제약 이름 매칭을 "진짜 PostgreSQL이 만든 에러 문자열"로 검증한다.
//
// H2로 하는 TrailCommandHandlerConstraintTest와 목적이 다르다.
// H2는 제약 이름을 대문자로, 그것도 UNIQUE는 인덱스 이름 형태로 출력하기 때문에
// 운영에서 실제로 마주치는 문자열을 재현하지 못한다.
// 지난 버그(없는 stream_id → 500)가 유닛 테스트를 전부 통과하고 Docker 실기동에서야
// 드러난 이유가 정확히 이 간극이었다.
//
// 스키마를 손으로 옮겨 적지 않고 운영 infra/scripts/init-db.sql을 그대로 적용한다.
// 그래서 이 테스트는 스키마 드리프트도 함께 막아준다.
//
// Docker가 없으면 실패가 아니라 skip된다(@EnabledIfDockerAvailable).
// ─────────────────────────────────────────
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfDockerAvailable
@DisplayName("Writer - TrailCommandHandler 실제 PostgreSQL 제약 위반 테스트")
class TrailCommandHandlerPostgresTest {

    private static final Path INIT_DB_SQL =
            Path.of("..", "..", "infra", "scripts", "init-db.sql");

    // static: 클래스당 컨테이너 1개만 띄운다.
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:15-3.3-alpine")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("stream_db")
                    .withUsername("test")
                    .withPassword("test");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // 운영 스키마를 그대로 쓰므로 Spring의 스크립트 초기화(schema.sql/data.sql)를 끈다.
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Autowired
    private TrailRepository trailRepository;

    @Autowired
    private StreamRepository streamRepository;

    @Autowired
    private DataSource dataSource;

    private static final long TEST_STREAM_ID = 1L;

    // 운영 init-db.sql에는 $$ ... $$ 로 감싼 plpgsql 함수 본문이 있다.
    // Testcontainers의 withInitScript는 세미콜론 기준으로 문장을 쪼개서 이 블록을 깨뜨린다.
    // 파일 전체를 하나의 Statement로 넘기면 PostgreSQL이 알아서 처리한다.
    @BeforeAll
    static void applyProductionSchema() throws Exception {
        String sql = Files.readString(INIT_DB_SQL, StandardCharsets.UTF_8);
        try (Connection c = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute(sql);
            s.execute("INSERT INTO streams (id, name, location) VALUES "
                    + "(1, 'pg-test-stream', ST_GeomFromText('LINESTRING(126.97 37.55, 126.98 37.56)', 4326))");
        }
    }

    private TrailCommandHandler handlerThatSkipsExistenceCheck() {
        StreamRepository alwaysExists = mock(StreamRepository.class);
        given(alwaysExists.existsById(anyLong())).willReturn(true);
        return new TrailCommandHandler(trailRepository, alwaysExists);
    }

    @Test
    @DisplayName("운영 스키마가 적용되고 제약 이름이 기대한 그대로다")
    void productionSchemaHasExpectedConstraintNames() throws Exception {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             var rs = s.executeQuery(
                     "SELECT conname FROM pg_constraint WHERE conrelid = 'trails'::regclass ORDER BY conname")) {
            var names = new java.util.ArrayList<String>();
            while (rs.next()) {
                names.add(rs.getString(1));
            }
            assertThat(names).contains(
                    "trails_stream_id_camera_number_key",
                    "trails_stream_id_fkey",
                    "trails_status_check");
        }
    }

    @Test
    @DisplayName("실제 PostgreSQL FK 위반 메시지에 trails_stream_id_fkey가 들어있고 400 경로로 변환된다")
    void realPostgresForeignKeyViolationBecomesIllegalArgumentException() {
        TrailCommandHandler handler = handlerThatSkipsExistenceCheck();
        CreateTrailCommand command =
                new CreateTrailCommand(999999L, "CAM-PG-FK", "POINT(126.97 37.55)", "N", "active");

        assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999999")
                .hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("PostgreSQL이 실제로 내보내는 FK 에러 문자열이 소문자 제약 이름을 담고 있다")
    void postgresErrorMessageActuallyContainsLowercaseConstraintName() {
        Trail orphan = new Trail();
        orphan.setStreamId(999999L);
        orphan.setCameraNumber("CAM-PG-RAW");
        orphan.setLocation(new org.locationtech.jts.geom.GeometryFactory(
                new org.locationtech.jts.geom.PrecisionModel(), Trail.SRID)
                .createPoint(new org.locationtech.jts.geom.Coordinate(126.97, 37.55)));
        orphan.setStatus("active");

        assertThatThrownBy(() -> trailRepository.save(orphan))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> {
                    String message = ((DataIntegrityViolationException) e)
                            .getMostSpecificCause().getMessage();
                    // 핸들러가 이 문자열을 매칭한다. 이게 이 테스트의 존재 이유다.
                    assertThat(message).contains("trails_stream_id_fkey");
                });
    }

    @Test
    @DisplayName("실제 PostgreSQL UNIQUE 위반이 409 경로로 변환되고 메시지에 소문자 제약 이름이 들어있다")
    void realPostgresUniqueViolationBecomesDuplicateTrailException() throws Exception {
        TrailCommandHandler handler = new TrailCommandHandler(trailRepository, streamRepository);
        handler.handle(new CreateTrailCommand(
                TEST_STREAM_ID, "CAM-PG-DUP", "POINT(126.97 37.55)", "N", "active"));

        CreateTrailCommand duplicate = new CreateTrailCommand(
                TEST_STREAM_ID, "CAM-PG-DUP", "POINT(126.97 37.55)", "N", "active");

        assertThatThrownBy(() -> handler.handle(duplicate))
                .isInstanceOf(DuplicateTrailException.class)
                .hasMessageContaining("CAM-PG-DUP");
    }

    @Test
    @DisplayName("PostgreSQL이 실제로 내보내는 UNIQUE 에러 문자열이 소문자 제약 이름을 담고 있다")
    void postgresUniqueErrorMessageActuallyContainsLowercaseConstraintName() throws Exception {
        TrailCommandHandler handler = new TrailCommandHandler(trailRepository, streamRepository);
        handler.handle(new CreateTrailCommand(
                TEST_STREAM_ID, "CAM-PG-RAW-DUP", "POINT(126.97 37.55)", "N", "active"));

        Trail duplicate = new Trail();
        duplicate.setStreamId(TEST_STREAM_ID);
        duplicate.setCameraNumber("CAM-PG-RAW-DUP");
        duplicate.setLocation(new org.locationtech.jts.geom.GeometryFactory(
                new org.locationtech.jts.geom.PrecisionModel(), Trail.SRID)
                .createPoint(new org.locationtech.jts.geom.Coordinate(126.97, 37.55)));
        duplicate.setStatus("active");

        assertThatThrownBy(() -> trailRepository.save(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> {
                    String message = ((DataIntegrityViolationException) e)
                            .getMostSpecificCause().getMessage();
                    assertThat(message).contains("trails_stream_id_camera_number_key");
                });
    }
}
