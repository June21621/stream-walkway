package com.stream.writer;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

// ─────────────────────────────────────────
// writer와 reader의 테스트 스키마(schema.sql)/시드 데이터(data.sql)는 운영 스키마를
// 두 벌로 손으로 옮겨 적은 것이다. "내용이 바이트 단위로 동일해야 한다"는 요구는
// 지금까지 각 파일 상단의 주석뿐이었고, 이를 강제하는 자동화 장치는 없었다.
//
// 이 테스트가 왜 필요한가: 리뷰에서 reader의 schema.sql에 있는 제약(FK 2개,
// UNIQUE 1개, CHECK 2개)을 전부 지워도 reader 전체 테스트 스위트가 그대로
// GREEN이었다 — reader에 추가된 테스트가 전부 happy-path라 제약을 위반시켜
// 보는 테스트가 하나도 없기 때문이다. 즉 reader 쪽 스키마 사본이 조용히
// 드리프트하거나(혹은 통째로 비어도) 아무도 알아채지 못한다.
//
// 이 테스트는 reader 스키마가 "제약으로서 옳다"는 것을 증명하지 않는다.
// writer 쪽에서 실제 제약 위반으로 검증된(TrailCommandHandlerConstraintTest,
// TrailCommandHandlerPostgresTest) 스키마를 reader가 바이트 단위로 그대로
// 베끼고 있다는 사실만 보증한다. 그것만으로도 "누가 reader만 고치고 writer는
// 안 고쳤다" 같은 드리프트는 잡아낼 수 있다.
//
// ⚠️ 이 클래스는 reader 모듈에도 똑같이 존재한다. 고의적인 중복이다 — 지우지
// 말 것. 이 저장소에는 CI가 없어서 writer 스위트만 돌리는 사람과 reader
// 스위트만 돌리는 사람이 따로 있을 수 있다. 어느 쪽 스위트를 단독으로
// 돌리더라도 드리프트를 잡아낼 수 있어야 하므로, 한쪽에만 두면 그 목적을
// 이룰 수 없다.
//
// @DataJpaTest/@SpringBootTest를 쓰지 않는다 — 파일을 읽어 바이트를 비교할
// 뿐 DB도 Spring 컨텍스트도 필요 없다. 순수 JUnit 5 테스트다.
// ─────────────────────────────────────────
@DisplayName("writer/reader 테스트 스키마 동기화 검증")
class TestSchemaSyncTest {

    // TrailCommandHandlerPostgresTest가 init-db.sql을 찾을 때와 같은 방식으로,
    // 모듈 베이스 디렉터리(예: services/writer) 기준 상대 경로를 쓴다.
    private static final Path OWN_SCHEMA = Path.of("src", "test", "resources", "schema.sql");
    private static final Path READER_SCHEMA = Path.of("..", "reader", "src", "test", "resources", "schema.sql");
    private static final Path OWN_DATA = Path.of("src", "test", "resources", "data.sql");
    private static final Path READER_DATA = Path.of("..", "reader", "src", "test", "resources", "data.sql");

    // schema.sql 쌍과 data.sql 쌍을 한 테스트에서 함께 검증한다. SoftAssertions를
    // 써서 둘 중 하나만 깨져도 조용히 넘어가지 않고, 두 쌍이 동시에 어긋나면
    // 실패 메시지에 둘 다 나열한다.
    @Test
    @DisplayName("schema.sql과 data.sql - writer와 reader 사본이 각각 바이트 단위로 동일하다")
    void schemaAndDataSqlMatchReaderCopies() throws IOException {
        SoftAssertions softly = new SoftAssertions();
        assertIdenticalBytes(softly, OWN_SCHEMA, READER_SCHEMA);
        assertIdenticalBytes(softly, OWN_DATA, READER_DATA);
        softly.assertAll();
    }

    // 텍스트로 줄 단위 비교를 하지 않고 바이트로 비교한다. 줄바꿈(CRLF/LF)이나
    // 인코딩이 한쪽 파일에서만 바뀌어도 두 파일은 "다른 파일"이고, 그 차이도
    // 드리프트이므로 잡아내야 한다.
    private static void assertIdenticalBytes(SoftAssertions softly, Path own, Path other) throws IOException {
        byte[] ownBytes = Files.readAllBytes(own);
        byte[] otherBytes = Files.readAllBytes(other);

        softly.assertThat(ownBytes)
                .withFailMessage(
                        "%s 와(과) %s 의 내용이 다르다. writer와 reader의 이 파일은 " +
                                "내용이 바이트 단위로 완전히 동일해야 한다 - 한쪽을 고쳤다면 " +
                                "다른 쪽에도 그대로 복사할 것.",
                        own, other)
                .isEqualTo(otherBytes);
    }
}
