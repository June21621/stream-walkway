package com.stream.writer.repository;

import com.stream.shared.entity.Capture;
import org.hibernate.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

// @DataJpaTest: H2 인메모리 DB로 교체한다. ddl-auto는 각 모듈 application.yaml에서
// none으로 고정돼 있어 테스트에서도 Hibernate가 테이블을 만들지 않는다 -
// src/test/resources/schema.sql이 테이블을, data.sql이 시드 행을 만드는
// 유일한 스키마 소스다.
@DataJpaTest
@DisplayName("Writer - CaptureRepository 테스트")
class CaptureRepositoryTest {

    @Autowired
    private CaptureRepository captureRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Capture buildCapture(Integer trailId, Integer streamId, String imagePath) {
        Capture capture = new Capture();
        capture.setTrailId(trailId);
        capture.setStreamId(streamId);
        capture.setImagePath(imagePath);
        capture.setRoadStatus("양호");
        capture.setConfidence(0.95);
        return capture;
    }

    // ─────────────────────────────────────────
    // save()
    // ─────────────────────────────────────────

    @Test
    @DisplayName("save() - 캡처를 저장하면 ID가 자동 생성된다")
    void save_generatesId() {
        Capture capture = buildCapture(1, 1, "/images/cap_001.jpg");

        Capture saved = captureRepository.save(capture);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getId()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("save() - 저장된 캡처의 필드 값이 올바르게 유지된다")
    void save_persistsAllFields() {
        Capture capture = buildCapture(1, 2, "/images/cap_001.jpg");
        capture.setRoadStatus("불량");
        capture.setConfidence(0.73);

        Capture saved = captureRepository.save(capture);
        entityManager.flush();
        entityManager.clear();

        Capture found = captureRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getTrailId()).isEqualTo(1);
        assertThat(found.getStreamId()).isEqualTo(2);
        assertThat(found.getImagePath()).isEqualTo("/images/cap_001.jpg");
        assertThat(found.getRoadStatus()).isEqualTo("불량");
        assertThat(found.getConfidence()).isEqualTo(0.73);
    }

    @Test
    @DisplayName("save() - 저장 시 @PrePersist로 createdAt이 자동 설정되고, DB 왕복 후에도 같은 시각이며, created_at 컬럼이 실제로 TIMESTAMPTZ임을 raw JDBC 타입으로 증명한다")
    void save_setsCreatedAtViaPrePersist() {
        Capture capture = buildCapture(1, 1, "/images/cap_001.jpg");
        assertThat(capture.getCreatedAt()).isNull(); // 저장 전에는 null

        Instant before = Instant.now().minusSeconds(1);
        Capture saved = captureRepository.save(capture);
        entityManager.flush();
        Instant after = Instant.now().plusSeconds(1);

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getCreatedAt()).isBetween(before, after);

        // DB를 실제로 왕복시켜 Instant 값이 유지되는지 확인한다.
        // 단, 같은 JVM·같은 H2 세션 안에서는 write/read에 JVM 기본 타임존 변환이
        // 대칭적으로 적용되므로, 이 왕복 비교만으로는 컬럼이 TIMESTAMP인지
        // TIMESTAMP WITH TIME ZONE인지 구분할 수 없다. 그 구분은 아래 raw JDBC 검증이 한다.
        Instant inMemory = saved.getCreatedAt();
        entityManager.clear();
        Capture reloaded = captureRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getCreatedAt()).isBetween(before, after);
        assertThat(reloaded.getCreatedAt().getEpochSecond()).isEqualTo(inMemory.getEpochSecond());

        // created_at 컬럼이 실제로 TIMESTAMP WITH TIME ZONE인지는 raw JDBC 값의 런타임 타입으로 확인한다.
        // H2에서 TIMESTAMP WITH TIME ZONE 컬럼은 OffsetDateTime으로 읽히지만 평범한 TIMESTAMP
        // 컬럼은 그렇지 않으므로, 이 단언은 스키마가 TIMESTAMP로 되돌아가면 실제로 실패하는
        // 진짜 판별 기준이다.
        Object rawCreatedAt = entityManager.getEntityManager()
                .unwrap(Session.class)
                .doReturningWork(connection -> {
                    try (PreparedStatement stmt = connection.prepareStatement(
                            "SELECT created_at FROM captures WHERE id = ?")) {
                        stmt.setLong(1, saved.getId());
                        try (ResultSet rs = stmt.executeQuery()) {
                            rs.next();
                            return rs.getObject("created_at");
                        }
                    }
                });
        assertThat(rawCreatedAt).isInstanceOf(OffsetDateTime.class);
    }

    // ─────────────────────────────────────────
    // findById()
    // ─────────────────────────────────────────

    @Test
    @DisplayName("findById() - 저장된 캡처를 ID로 조회한다")
    void findById_returnsCapture() {
        Capture capture = entityManager.persistAndFlush(buildCapture(1, 1, "/images/cap_001.jpg"));

        Optional<Capture> result = captureRepository.findById(capture.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(capture.getId());
    }

    @Test
    @DisplayName("findById() - 존재하지 않는 ID는 빈 Optional을 반환한다")
    void findById_returnsEmptyWhenNotFound() {
        Optional<Capture> result = captureRepository.findById(999L);

        assertThat(result).isEmpty();
    }

    // ─────────────────────────────────────────
    // findAll()
    // ─────────────────────────────────────────

    @Test
    @DisplayName("findAll() - 저장된 모든 캡처를 반환한다")
    void findAll_returnsAllSavedCaptures() {
        entityManager.persistAndFlush(buildCapture(1, 1, "/images/cap_001.jpg"));
        entityManager.persistAndFlush(buildCapture(2, 1, "/images/cap_002.jpg"));
        entityManager.persistAndFlush(buildCapture(3, 2, "/images/cap_003.jpg"));

        List<Capture> result = captureRepository.findAll();

        assertThat(result).hasSize(3);
    }

    @Test
    @DisplayName("findAll() - 저장된 캡처가 없으면 빈 목록을 반환한다")
    void findAll_returnsEmptyListWhenNothingSaved() {
        List<Capture> result = captureRepository.findAll();

        assertThat(result).isEmpty();
    }

    // ─────────────────────────────────────────
    // delete()
    // ─────────────────────────────────────────

    @Test
    @DisplayName("deleteById() - 저장된 캡처를 삭제하면 조회되지 않는다")
    void deleteById_removesCapture() {
        Capture capture = entityManager.persistAndFlush(buildCapture(1, 1, "/images/cap_001.jpg"));
        Long savedId = capture.getId();

        captureRepository.deleteById(savedId);
        entityManager.flush();

        assertThat(captureRepository.findById(savedId)).isEmpty();
    }
}
