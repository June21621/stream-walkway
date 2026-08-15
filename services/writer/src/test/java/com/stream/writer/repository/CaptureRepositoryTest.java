package com.stream.writer.repository;

import com.stream.shared.entity.Capture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

// @DataJpaTest: H2 인메모리 DB로 교체, ddl-auto: create-drop 자동 적용
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
    @DisplayName("save() - 저장 시 @PrePersist에 의해 createdAt이 자동 설정된다")
    void save_setsCreatedAtViaPrePersist() {
        Capture capture = buildCapture(1, 1, "/images/cap_001.jpg");
        assertThat(capture.getCreatedAt()).isNull(); // 저장 전에는 null

        Capture saved = captureRepository.save(capture);
        entityManager.flush();

        assertThat(saved.getCreatedAt()).isNotNull();
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
