package com.stream.writer.command;

import com.stream.shared.entity.Stream;
import com.stream.writer.repository.StreamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.ParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ─────────────────────────────────────────
// 핸들러의 길이 상한 상수가 실제 컬럼 정의(streams.name VARCHAR(255))와
// 어긋나지 않는지를 진짜 H2로 검증한다.
//
// 검증 로직 자체는 StreamCommandHandlerTest(mock)가 증명한다.
// 이 클래스가 따로 존재하는 이유는 상수와 스키마가 따로 놀 수 있기 때문이다:
//   - 정확히 255자가 실제로 저장되어야 → 상수가 컬럼보다 엄격하지 않음
//   - 256자가 500이 아니라 400이어야   → 상수가 컬럼보다 느슨하지 않음
// 두 방향이 모두 있어야 상수가 스키마에 묶인다.
// ─────────────────────────────────────────
@DataJpaTest
@DisplayName("Writer - StreamCommandHandler 실제 컬럼 길이 제한 테스트 (H2)")
class StreamCommandHandlerConstraintTest {

    @Autowired
    private StreamRepository streamRepository;

    @Autowired
    private TestEntityManager entityManager;

    private static final String VALID_WKT = "LINESTRING(126.97 37.55, 126.98 37.56)";

    @Test
    @DisplayName("name이 256자면 DB에 닿기 전에 IllegalArgumentException(400 경로)이 된다")
    void overLongNameIsRejectedBeforeReachingDatabase() {
        StreamCommandHandler handler = new StreamCommandHandler(streamRepository);

        assertThatThrownBy(() -> handler.handle(
                new CreateStreamCommand("가".repeat(256), VALID_WKT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("255");
    }

    @Test
    @DisplayName("name이 정확히 255자면 실제로 저장된다 - 상수가 컬럼보다 엄격하지 않음을 증명한다")
    void nameAtExactColumnLimitIsPersisted() throws ParseException {
        StreamCommandHandler handler = new StreamCommandHandler(streamRepository);
        String atLimit = "가".repeat(255);

        Stream saved = handler.handle(new CreateStreamCommand(atLimit, VALID_WKT));
        entityManager.flush();
        entityManager.clear();

        Stream reloaded = streamRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo(atLimit);
        assertThat(reloaded.getName()).hasSize(255);
    }
}
