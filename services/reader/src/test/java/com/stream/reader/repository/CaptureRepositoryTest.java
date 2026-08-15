package com.stream.reader.repository;

import com.stream.shared.entity.Capture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

// @DataJpaTest: H2 인메모리 DB로 교체, ddl-auto: create-drop 자동 적용
// @Sql: setter가 없는 Capture 엔티티의 테스트 데이터를 SQL로 직접 삽입
@DataJpaTest
@DisplayName("Reader - CaptureRepository 테스트")
class CaptureRepositoryTest {

    @Autowired
    private CaptureRepository captureRepository;

    // ─────────────────────────────────────────
    // findByTrailId()
    // ─────────────────────────────────────────

    @Test
    @DisplayName("findByTrailId() - 해당 trailId를 가진 캡처 목록을 반환한다")
    @Sql(statements = {
            "INSERT INTO captures (trail_id, stream_id, image_path, road_status, confidence, created_at) VALUES (1, 1, '/images/cap_001.jpg', '양호', 0.95, NOW())",
            "INSERT INTO captures (trail_id, stream_id, image_path, road_status, confidence, created_at) VALUES (1, 1, '/images/cap_002.jpg', '보통', 0.80, NOW())"
    })
    void findByTrailId_returnsMatchingCaptures() {
        List<Capture> result = captureRepository.findByTrailId(1);

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(c -> c.getTrailId().equals(1));
    }

    @Test
    @DisplayName("findByTrailId() - 다른 trailId의 캡처는 포함하지 않는다")
    @Sql(statements = {
            "INSERT INTO captures (trail_id, stream_id, image_path, road_status, confidence, created_at) VALUES (1, 1, '/images/cap_001.jpg', '양호', 0.95, NOW())",
            "INSERT INTO captures (trail_id, stream_id, image_path, road_status, confidence, created_at) VALUES (2, 1, '/images/cap_002.jpg', '보통', 0.80, NOW())"
    })
    void findByTrailId_excludesOtherTrailIds() {
        List<Capture> result = captureRepository.findByTrailId(1);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTrailId()).isEqualTo(1);
    }

    @Test
    @DisplayName("findByTrailId() - 해당 trailId가 없으면 빈 목록을 반환한다")
    void findByTrailId_returnsEmptyListWhenNoMatch() {
        List<Capture> result = captureRepository.findByTrailId(999);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findByTrailId() - 결과에 imagePath, roadStatus, confidence가 올바르게 매핑된다")
    @Sql(statements = "INSERT INTO captures (trail_id, stream_id, image_path, road_status, confidence, created_at) VALUES (1, 2, '/images/cap_001.jpg', '양호', 0.95, NOW())")
    void findByTrailId_fieldsAreMappedCorrectly() {
        List<Capture> result = captureRepository.findByTrailId(1);

        assertThat(result).hasSize(1);
        Capture capture = result.get(0);
        assertThat(capture.getStreamId()).isEqualTo(2);
        assertThat(capture.getImagePath()).isEqualTo("/images/cap_001.jpg");
        assertThat(capture.getRoadStatus()).isEqualTo("양호");
        assertThat(capture.getConfidence()).isEqualTo(0.95);
    }

    // ─────────────────────────────────────────
    // findByStreamId()
    // ─────────────────────────────────────────

    @Test
    @DisplayName("findByStreamId() - 해당 streamId를 가진 캡처 목록을 반환한다")
    @Sql(statements = {
            "INSERT INTO captures (trail_id, stream_id, image_path, road_status, confidence, created_at) VALUES (1, 1, '/images/cap_001.jpg', '양호', 0.95, NOW())",
            "INSERT INTO captures (trail_id, stream_id, image_path, road_status, confidence, created_at) VALUES (2, 1, '/images/cap_002.jpg', '보통', 0.80, NOW())"
    })
    void findByStreamId_returnsMatchingCaptures() {
        List<Capture> result = captureRepository.findByStreamId(1);

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(c -> c.getStreamId().equals(1));
    }

    @Test
    @DisplayName("findByStreamId() - 다른 streamId의 캡처는 포함하지 않는다")
    @Sql(statements = {
            "INSERT INTO captures (trail_id, stream_id, image_path, road_status, confidence, created_at) VALUES (1, 1, '/images/cap_001.jpg', '양호', 0.95, NOW())",
            "INSERT INTO captures (trail_id, stream_id, image_path, road_status, confidence, created_at) VALUES (1, 2, '/images/cap_002.jpg', '보통', 0.80, NOW())"
    })
    void findByStreamId_excludesOtherStreamIds() {
        List<Capture> result = captureRepository.findByStreamId(1);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStreamId()).isEqualTo(1);
    }

    @Test
    @DisplayName("findByStreamId() - 해당 streamId가 없으면 빈 목록을 반환한다")
    void findByStreamId_returnsEmptyListWhenNoMatch() {
        List<Capture> result = captureRepository.findByStreamId(999);

        assertThat(result).isEmpty();
    }

    // ─────────────────────────────────────────
    // findAll() / findById() - JpaRepository 기본 메서드
    // ─────────────────────────────────────────

    @Test
    @DisplayName("findAll() - 저장된 전체 캡처 목록을 반환한다")
    @Sql(statements = {
            "INSERT INTO captures (trail_id, stream_id, image_path, road_status, confidence, created_at) VALUES (1, 1, '/images/cap_001.jpg', '양호', 0.95, NOW())",
            "INSERT INTO captures (trail_id, stream_id, image_path, road_status, confidence, created_at) VALUES (2, 2, '/images/cap_002.jpg', '보통', 0.80, NOW())"
    })
    void findAll_returnsAllCaptures() {
        List<Capture> result = captureRepository.findAll();

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("findById() - 존재하는 ID로 조회하면 캡처를 반환한다")
    @Sql(statements = "INSERT INTO captures (id, trail_id, stream_id, image_path, road_status, confidence, created_at) VALUES (100, 1, 1, '/images/cap_100.jpg', '양호', 0.95, NOW())")
    void findById_returnsCapture() {
        Optional<Capture> result = captureRepository.findById(100L);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("findById() - 존재하지 않는 ID로 조회하면 빈 Optional을 반환한다")
    void findById_returnsEmptyWhenNotFound() {
        Optional<Capture> result = captureRepository.findById(999L);

        assertThat(result).isEmpty();
    }
}
