# writer 핸들러 길이 검증 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `streams.name`(VARCHAR(255))과 `trails.direction`(VARCHAR(50))에 상한을 넘는 값이 들어오면 500 대신 400을 반환하게 한다.

**Architecture:** 두 핸들러에 명시적 길이 검증 `if`문을 추가한다. 상한은 이름 있는 상수로 두고 어느 컬럼에서 온 값인지 주석으로 연결한다. 검증은 DB를 건드리기 전에 일어나고, `IllegalArgumentException`을 던지면 기존 컨트롤러 `@ExceptionHandler`가 400으로 바꾼다. 검증 자체는 mock 테스트로, 상수가 컬럼 정의에서 드리프트하지 않는다는 것은 진짜 H2 테스트로 증명한다.

**Tech Stack:** Java 21, Spring Boot 3.5.1, Spring Data JPA, H2 2.3.232, JUnit 5, Mockito, AssertJ

**설계 문서:** `docs/superpowers/specs/2026-08-24-writer-length-validation-design.md`

---

## 계획 수립 전 실측으로 확인한 사실 (추측 아님)

`@DataJpaTest`로 실제 H2에 붙여서 확인했다. 구현 중 다시 의심하지 말 것.

1. **`direction` 51자와 `name` 256자는 실제로 500이 된다.** 둘 다 `org.springframework.dao.DataIntegrityViolationException`(cause: `org.hibernate.exception.DataException` → `org.h2.jdbc.JdbcSQLDataException`, SQLState `22001`)이 나온다. H2 메시지는 각각 `Value too long for column "DIRECTION CHARACTER VARYING(50)"`, `Value too long for column "NAME CHARACTER VARYING(255)"`.

2. **경계값은 통과한다.** `direction` 정확히 50자, `name` 정확히 255자는 저장에 성공한다.

3. **H2는 `VARCHAR(n)`에서 UTF-16 코드 단위를 센다(실측).** 이모지 26개(`String.length()`=52, `codePointCount()`=26)를 `VARCHAR(50)`이 거부했다. 그래서 `String.length()`를 쓴다 — PostgreSQL은 문자(코드포인트) 단위로 센다고 알려져 있으나 직접 확인하지는 않았다. `length() >= codePointCount()`가 항상 성립하므로 이 기준은 H2와 (알려진 바가 맞다면) PostgreSQL 두 해석 모두의 상한이 된다. 다만 VARCHAR를 바이트 길이로 세는 엔진에는 이 논리가 성립하지 않는다 — 이 프로젝트는 H2와 PostgreSQL만 쓴다.

4. **컨트롤러 변경은 필요 없다.** `StreamController`와 `TrailController` 모두 `@ExceptionHandler({ParseException.class, ClassCastException.class, IllegalArgumentException.class})`를 이미 갖고 있어 400을 반환한다.

5. **기준선 (측정값)**: writer `.\mvnw.cmd -B -o test "-Dtest=!WriterApplicationTests"` → `Tests run: 58`. 클래스별로 `StreamCommandHandlerTest` 5개, `TrailCommandHandlerTest` 12개, `TrailCommandHandlerConstraintTest` 6개.

6. **Docker 데몬이 내려가 있으면 `Skipped: 5`가 나오고 BUILD SUCCESS다.** Testcontainers 테스트(`TrailCommandHandlerPostgresTest`) 5개가 조용히 skip된다. 실측으로 재현했다. **이건 회귀가 아니다.** Docker가 살아 있으면 `Skipped: 0`이 된다. 이번 작업은 Postgres를 필요로 하지 않으므로 어느 쪽이든 진행 가능하다.

7. **`data.sql` 시드가 이미 있다.** 모든 `@DataJpaTest`에서 `streams` id 1·2와 `trails` id 1·2·3이 존재하고, IDENTITY는 1000부터 시작한다.

---

## Global Constraints

- **패키지 루트는 `com.stream.writer`**, 엔티티는 `com.stream.shared.entity`. Java 21.
- **상한값은 컬럼 정의와 정확히 같아야 한다**: `streams.name`은 255, `trails.direction`은 50. 두 값 모두 `infra/scripts/init-db.sql`과 `services/writer/src/test/resources/schema.sql`에 그렇게 적혀 있다.
- **길이 비교는 `String.length()`를 쓴다.** `codePointCount()`를 쓰지 말 것 — H2가 코드 단위를 세므로 코드포인트 기준으로 검증하면 이모지 같은 값이 검증을 통과하고도 DB에서 거부되어 다시 500이 된다.
- **`direction`은 nullable이므로 null은 통과시킨다.** `name`은 앞선 null/blank 검사에서 이미 걸러지므로 null 체크를 중복하지 않는다.
- **에러 메시지는 영어로 쓴다.** 기존 메시지(`"name is required"`, `"Invalid status: ..."`)와 톤을 맞춘다.
- **컨트롤러, 스키마 파일, `apps/backend`, reader 모듈은 건드리지 않는다.**
- **Mockito 엄격 모드(strict stubs)를 쓴다.** 실제로 호출되지 않는 스텁을 추가하면 `UnnecessaryStubbingException`으로 실패한다.
- **PowerShell에서 `-D` 인자는 반드시 따옴표로 감싼다**: `.\mvnw.cmd -B -o test "-Dtest=StreamCommandHandlerTest"`. 안 그러면 Maven이 lifecycle phase로 오해한다.
- Maven은 각 모듈 디렉터리에서 그 모듈의 `.\mvnw.cmd`로 실행한다. PATH에 `mvn`이 없고 저장소 루트에 래퍼도 없다.
- `WriterApplicationTests`는 실제 Postgres가 필요해 항상 실패한다. `-Dtest=!WriterApplicationTests`로 제외한다.
- 파일은 UTF-8(BOM 없음)이고 한글이 들어 있다. 인코딩을 보존할 것.

---

## Task 1: `streams.name` 길이 검증

**Files:**
- Modify: `services/writer/src/main/java/com/stream/writer/command/StreamCommandHandler.java`
- Modify: `services/writer/src/test/java/com/stream/writer/command/StreamCommandHandlerTest.java`
- Create: `services/writer/src/test/java/com/stream/writer/command/StreamCommandHandlerConstraintTest.java`

**Interfaces:**
- Consumes: `StreamCommandHandler(StreamRepository)` 생성자, `handle(CreateStreamCommand) throws ParseException`, `CreateStreamCommand(String name, String location)`, `com.stream.shared.entity.Stream`.
- Produces: `StreamCommandHandler.MAX_NAME_LENGTH = 255`(private). 255자를 넘는 `name`은 `IllegalArgumentException`이 되고 메시지에 `255`가 들어간다.

- [ ] **Step 1: mock 테스트 2개를 추가한다**

`services/writer/src/test/java/com/stream/writer/command/StreamCommandHandlerTest.java`의 마지막 `@Test` 메서드 뒤, 클래스 닫는 `}` 앞에 추가:

```java
    @Test
    @DisplayName("handle() - name이 255자를 넘으면 IllegalArgumentException을 던진다 (저장 시도 안 함)")
    void handle_throwsIllegalArgumentExceptionOnOverLongName() {
        // given: streams.name은 VARCHAR(255)라 256자는 DB가 거부한다.
        // 검증이 없으면 저장까지 가서 DataIntegrityViolationException → 500이 된다.
        CreateStreamCommand command =
                new CreateStreamCommand("가".repeat(256), "LINESTRING(126.97 37.55, 126.98 37.56)");

        // when & then
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> handler.handle(command));
        assertThat(e.getMessage()).contains("255");
        verify(streamRepository, org.mockito.Mockito.never()).save(any(Stream.class));
    }

    @Test
    @DisplayName("handle() - name이 정확히 255자면 정상 저장된다 (경계값)")
    void handle_acceptsNameAtExactLimit() throws ParseException {
        // given
        String atLimit = "가".repeat(255);
        CreateStreamCommand command =
                new CreateStreamCommand(atLimit, "LINESTRING(126.97 37.55, 126.98 37.56)");
        given(streamRepository.save(any(Stream.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        Stream result = handler.handle(command);

        // then
        assertThat(result.getName()).hasSize(255);
    }
```

- [ ] **Step 2: 테스트를 돌려서 실패를 확인한다**

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=StreamCommandHandlerTest"
```
Expected: **FAIL.** `handle_throwsIllegalArgumentExceptionOnOverLongName`이 실패한다 — 검증이 없어서 `IllegalArgumentException`이 아예 던져지지 않고, mock `save()`가 기본값 `null`을 반환하며 정상 종료된다.

`handle_acceptsNameAtExactLimit`은 **이미 통과한다.** 이건 정상이다 — 이 테스트는 RED를 만드는 게 목적이 아니라, 수정 후에 검증이 과하게 엄격해지지 않았음을 지키는 회귀 방지용이다.

- [ ] **Step 3: 진짜 H2 테스트 클래스를 새로 만든다**

`services/writer/src/test/java/com/stream/writer/command/StreamCommandHandlerConstraintTest.java`:

```java
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
```

- [ ] **Step 4: 새 클래스를 돌려서 실패를 확인한다**

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=StreamCommandHandlerConstraintTest"
```
Expected: **FAIL.** `overLongNameIsRejectedBeforeReachingDatabase`가 실패한다 — 실제로 던져지는 예외는 `org.springframework.dao.DataIntegrityViolationException`(H2 메시지: `Value too long for column "NAME CHARACTER VARYING(255)"`)이다. **이것이 지금 클라이언트가 500을 받는 이유를 눈으로 확인하는 단계다.**

`nameAtExactColumnLimitIsPersisted`는 이미 통과한다.

- [ ] **Step 5: `StreamCommandHandler`에 검증을 추가한다**

`services/writer/src/main/java/com/stream/writer/command/StreamCommandHandler.java`에서, `public class StreamCommandHandler {` 바로 다음 줄에 상수를 추가한다:

```java
    // streams.name 컬럼이 VARCHAR(255)다 (infra/scripts/init-db.sql).
    // 길이 비교는 String.length()(UTF-16 코드 단위)로 한다.
    // H2가 VARCHAR 길이를 코드 단위로 세는 것은 실측으로 확인했다.
    // PostgreSQL은 문자(코드포인트) 단위로 센다고 알려져 있으나 직접 확인하지는 않았다.
    // 다만 length() >= codePointCount()이므로 이 기준은 두 해석 모두의 상한이고,
    // 어느 쪽이 맞든 검증을 통과한 값은 컬럼에 들어간다. (VARCHAR를 바이트 길이로
    // 세는 엔진에는 이 논리가 성립하지 않지만 이 프로젝트는 H2와 PostgreSQL만 쓴다.)
    // 대가는 astral 문자(이모지 등)에 대해 PostgreSQL보다 엄격할 수 있다는 것뿐이다.
    private static final int MAX_NAME_LENGTH = 255;
```

그리고 `handle` 메서드의 `name` null/blank 검사 **바로 뒤**, `location` 검사 앞에 추가한다:

```java
        if (command.name().length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "name must be " + MAX_NAME_LENGTH + " characters or fewer");
        }
```

(`command.name()`이 null이 아님은 바로 위 검사가 보장하므로 null 체크를 다시 하지 않는다.)

- [ ] **Step 6: 두 테스트 클래스를 돌려서 통과를 확인한다**

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=StreamCommandHandlerTest,StreamCommandHandlerConstraintTest"
```
Expected: PASS. `StreamCommandHandlerTest` 7개(기존 5 + 신규 2), `StreamCommandHandlerConstraintTest` 2개.

- [ ] **Step 7: 상수가 진짜로 스키마에 묶여 있는지 변이 테스트로 확인한다**

`MAX_NAME_LENGTH`를 임시로 `300`으로 바꾼다.

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=StreamCommandHandlerConstraintTest"
```
Expected: **FAIL.** `overLongNameIsRejectedBeforeReachingDatabase`가 실패해야 한다 — 256자가 검증을 통과해 DB까지 가서 `DataIntegrityViolationException`이 나기 때문이다. 실패하지 않으면 이 테스트가 상수를 지키고 있지 않다는 뜻이므로 테스트를 고쳐야 한다.

그다음 `255`로 되돌리고 다시 돌려서 통과를 확인한다. 실제로 본 실패 메시지를 기록해둘 것.

- [ ] **Step 8: writer 전체 테스트로 회귀를 확인한다**

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=!WriterApplicationTests"
```
Expected: `Tests run: 62, Failures: 0, Errors: 0` (기준선 58 + 4), `BUILD SUCCESS`. Docker가 내려가 있으면 `Skipped: 5`가 함께 나오는데 이는 회귀가 아니다.

- [ ] **Step 9: 커밋**

```bash
git add services/writer/src/main/java/com/stream/writer/command/StreamCommandHandler.java services/writer/src/test/java/com/stream/writer/command/StreamCommandHandlerTest.java services/writer/src/test/java/com/stream/writer/command/StreamCommandHandlerConstraintTest.java
git commit -m "fix(writer): name이 255자를 넘으면 500 대신 400 반환"
```

---

## Task 2: `trails.direction` 길이 검증

**Files:**
- Modify: `services/writer/src/main/java/com/stream/writer/command/TrailCommandHandler.java`
- Modify: `services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerTest.java`
- Modify: `services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerConstraintTest.java`

**Interfaces:**
- Consumes: `TrailCommandHandler(TrailRepository, StreamRepository)` 생성자, `handle(CreateTrailCommand) throws ParseException`, `CreateTrailCommand(Long streamId, String cameraNumber, String location, String direction, String status)`, `com.stream.shared.entity.Trail`.
- Produces: `TrailCommandHandler.MAX_DIRECTION_LENGTH = 50`(private). 50자를 넘는 `direction`은 `IllegalArgumentException`이 되고 메시지에 `direction`과 `50`이 들어간다.

**⚠️ 이 태스크에는 테스트가 엉뚱한 이유로 통과하는 함정이 있다.** 아래 Step 2에서 자세히 설명한다. 반드시 읽을 것.

- [ ] **Step 1: mock 테스트 2개를 추가한다**

`services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerTest.java`의 마지막 `@Test` 메서드 뒤, 클래스 닫는 `}` 앞에 추가:

```java
    @Test
    @DisplayName("handle() - direction이 50자를 넘으면 IllegalArgumentException을 던진다 (저장 시도 안 함)")
    void handle_throwsIllegalArgumentExceptionOnOverLongDirection() {
        // given: trails.direction은 VARCHAR(50)이라 51자는 DB가 거부한다.
        // 검증이 없으면 저장까지 가서 DataIntegrityViolationException → 500이 된다.
        CreateTrailCommand command = new CreateTrailCommand(
                1L, "CAM-LEN", "POINT(126.97 37.55)", "북".repeat(51), "active");

        // when & then
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> handler.handle(command));
        // 메시지까지 확인해야 한다. 이 단언이 없으면 스텁하지 않은 existsById가
        // 기본값 false를 반환해 던지는 "stream_id=1 does not exist" 예외 때문에
        // 검증이 없어도 테스트가 통과해버린다.
        assertThat(e.getMessage()).contains("direction");
        assertThat(e.getMessage()).contains("50");
        verify(trailRepository, org.mockito.Mockito.never()).save(any(Trail.class));
    }

    @Test
    @DisplayName("handle() - direction이 정확히 50자면 정상 저장된다 (경계값)")
    void handle_acceptsDirectionAtExactLimit() throws ParseException {
        // given
        String atLimit = "북".repeat(50);
        CreateTrailCommand command = new CreateTrailCommand(
                1L, "CAM-LEN-OK", "POINT(126.97 37.55)", atLimit, "active");
        given(streamRepository.existsById(1L)).willReturn(true);
        given(trailRepository.save(any(Trail.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        Trail result = handler.handle(command);

        // then
        assertThat(result.getDirection()).hasSize(50);
    }
```

- [ ] **Step 2: 테스트를 돌려서 실패를 확인한다 — 그리고 실패 이유를 확인한다**

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=TrailCommandHandlerTest"
```
Expected: **FAIL.** `handle_throwsIllegalArgumentExceptionOnOverLongDirection`이 실패한다.

**실패 메시지를 반드시 확인할 것.** 올바른 RED는 `IllegalArgumentException`은 던져졌지만 메시지가 `"stream_id=1 does not exist"`라서 `contains("direction")` 단언이 깨지는 형태다. 이유는 이렇다 — 검증이 없으면 핸들러가 `existsById(1L)`까지 진행하는데, 그 스텁을 두지 않았으므로 Mockito가 기본값 `false`를 반환하고 핸들러가 "존재하지 않는 stream_id" 예외를 던진다. **즉 예외 타입만 봤다면 이 테스트는 수정 전에도 통과했을 것이다.** 메시지 단언이 진짜 RED를 만든다.

다른 형태로 실패한다면 멈추고 보고할 것.

`handle_acceptsDirectionAtExactLimit`은 이미 통과한다(회귀 방지용).

- [ ] **Step 3: 진짜 H2 테스트 2개를 추가한다**

`services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerConstraintTest.java`의 마지막 `@Test` 메서드 뒤, 클래스 닫는 `}` 앞에 추가:

```java
    // ─────────────────────────────────────────
    // 아래 두 테스트는 제약 위반이 아니라 컬럼 길이 상한을 다룬다.
    // 핸들러의 MAX_DIRECTION_LENGTH 상수가 실제 컬럼 정의(VARCHAR(50))에서
    // 드리프트하지 않는지 지킨다:
    //   - 정확히 50자가 실제로 저장되어야 → 상수가 컬럼보다 엄격하지 않음
    //   - 51자가 500이 아니라 400이어야   → 상수가 컬럼보다 느슨하지 않음
    // ─────────────────────────────────────────

    @Test
    @DisplayName("direction이 51자면 DB에 닿기 전에 IllegalArgumentException(400 경로)이 된다")
    void overLongDirectionIsRejectedBeforeReachingDatabase() {
        TrailCommandHandler handler = new TrailCommandHandler(trailRepository, streamRepository);

        assertThatThrownBy(() -> handler.handle(new CreateTrailCommand(
                SEED_STREAM_ID, "CAM-LEN", "POINT(126.97 37.55)", "북".repeat(51), "active")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("direction");
    }

    @Test
    @DisplayName("direction이 정확히 50자면 실제로 저장된다 - 상수가 컬럼보다 엄격하지 않음을 증명한다")
    void directionAtExactColumnLimitIsPersisted() throws ParseException {
        TrailCommandHandler handler = new TrailCommandHandler(trailRepository, streamRepository);
        String atLimit = "북".repeat(50);

        Trail saved = handler.handle(new CreateTrailCommand(
                SEED_STREAM_ID, "CAM-LEN-OK", "POINT(126.97 37.55)", atLimit, "active"));
        entityManager.flush();
        entityManager.clear();

        Trail reloaded = trailRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getDirection()).isEqualTo(atLimit);
        assertThat(reloaded.getDirection()).hasSize(50);
    }
```

- [ ] **Step 4: 새 테스트를 돌려서 실패를 확인한다**

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=TrailCommandHandlerConstraintTest"
```
Expected: **FAIL.** `overLongDirectionIsRejectedBeforeReachingDatabase`가 실패한다 — 실제로 던져지는 예외는 `org.springframework.dao.DataIntegrityViolationException`(H2 메시지: `Value too long for column "DIRECTION CHARACTER VARYING(50)"`)이다.

여기서는 mock이 아니라 진짜 `StreamRepository`를 쓰고 시드에 `streams` id 1이 존재하므로, Step 2의 `existsById` 함정이 없다. 핸들러가 실제로 `save()`까지 도달해서 DB가 거부한다. **이것이 지금 클라이언트가 500을 받는 경로 그대로다.**

`directionAtExactColumnLimitIsPersisted`는 이미 통과한다.

- [ ] **Step 5: `TrailCommandHandler`에 검증을 추가한다**

`services/writer/src/main/java/com/stream/writer/command/TrailCommandHandler.java`에서, 기존 상수 선언

```java
    private static final Set<String> VALID_STATUSES = Set.of("active", "inactive");
```

바로 아래에 추가한다:

```java
    // trails.direction 컬럼이 VARCHAR(50)이다 (infra/scripts/init-db.sql).
    // 길이 비교는 String.length()(UTF-16 코드 단위)로 한다.
    // H2가 VARCHAR 길이를 코드 단위로 세는 것은 실측으로 확인했다.
    // PostgreSQL은 문자(코드포인트) 단위로 센다고 알려져 있으나 직접 확인하지는 않았다.
    // 다만 length() >= codePointCount()이므로 이 기준은 두 해석 모두의 상한이고,
    // 어느 쪽이 맞든 검증을 통과한 값은 컬럼에 들어간다. (VARCHAR를 바이트 길이로
    // 세는 엔진에는 이 논리가 성립하지 않지만 이 프로젝트는 H2와 PostgreSQL만 쓴다.)
    // 대가는 astral 문자(이모지 등)에 대해 PostgreSQL보다 엄격할 수 있다는 것뿐이다.
    private static final int MAX_DIRECTION_LENGTH = 50;
```

그리고 `handle` 메서드의 status 화이트리스트 검사

```java
        if (!VALID_STATUSES.contains(status)) {
            throw new IllegalArgumentException("Invalid status: " + status + " (must be 'active' or 'inactive')");
        }
```

**바로 뒤**, `WKTReader wktReader = ...` 줄 앞에 추가한다:

```java
        if (command.direction() != null && command.direction().length() > MAX_DIRECTION_LENGTH) {
            throw new IllegalArgumentException(
                    "direction must be " + MAX_DIRECTION_LENGTH + " characters or fewer");
        }
```

(`direction`은 nullable 컬럼이므로 null은 통과시킨다.)

- [ ] **Step 6: 두 테스트 클래스를 돌려서 통과를 확인한다**

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=TrailCommandHandlerTest,TrailCommandHandlerConstraintTest"
```
Expected: PASS. `TrailCommandHandlerTest` 14개(기존 12 + 신규 2), `TrailCommandHandlerConstraintTest` 8개(기존 6 + 신규 2).

- [ ] **Step 7: 상수가 진짜로 스키마에 묶여 있는지 변이 테스트로 확인한다**

`MAX_DIRECTION_LENGTH`를 임시로 `100`으로 바꾼다.

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=TrailCommandHandlerConstraintTest"
```
Expected: **FAIL.** `overLongDirectionIsRejectedBeforeReachingDatabase`가 실패해야 한다 — 51자가 검증을 통과해 DB까지 가서 `DataIntegrityViolationException`이 나기 때문이다.

그다음 `50`으로 되돌리고 다시 돌려서 통과를 확인한다. 실제로 본 실패 메시지를 기록해둘 것.

- [ ] **Step 8: writer 전체 테스트로 회귀를 확인한다**

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=!WriterApplicationTests"
```
Expected: `Tests run: 66, Failures: 0, Errors: 0` (Task 1 이후 62 + 4), `BUILD SUCCESS`. Docker가 내려가 있으면 `Skipped: 5`가 함께 나오는데 이는 회귀가 아니다.

- [ ] **Step 9: 커밋**

```bash
git add services/writer/src/main/java/com/stream/writer/command/TrailCommandHandler.java services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerTest.java services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerConstraintTest.java
git commit -m "fix(writer): direction이 50자를 넘으면 500 대신 400 반환"
```

---

## 후속 작업 (이 계획엔 포함 안 함, 참고용)

### 최우선: WKT에 Z/ZM 좌표가 오면 여전히 500이다 (같은 버그 클래스)

전체 브랜치 리뷰에서 실측으로 발견됐다. `LINESTRING Z(126.97 37.55 1, 126.98 37.56 2)`나
`POINT Z(1 2 3)`를 보내면 `WKTReader.read()`가 3D 지오메트리를 정상 파싱하고 `(LineString)`/
`(Point)` 캐스트도 통과해서 `save()`까지 도달한다. 그러면 컬럼 typmod가 거부한다:

```
DataIntegrityViolationException: Data conversion error converting "X'01020000a0...'
(STREAMS: ""LOCATION"" GEOMETRY(LINESTRING, 4326) NOT NULL)" [22018-232]
```

두 컨트롤러의 `@ExceptionHandler`가 `DataIntegrityViolationException`을 나열하지 않고
`TrailCommandHandler`의 catch도 제약 이름을 못 찾아 rethrow하므로 writer가 500을 내고,
`StreamServiceImpl`이 `BadRequest`만 잡으므로 backend도 500을 그대로 전달한다.
**이번 브랜치가 고친 것과 구조가 완전히 동일한 버그이며 같은 엔드포인트에 남아 있다.**
(PostGIS에서는 확인하지 못했다 — 확인 시점에 Docker가 내려가 있었다.)

### 상수가 운영 스키마가 아니라 H2 테스트 스키마에 묶여 있다

리뷰어가 `infra/scripts/init-db.sql`의 두 컬럼 폭만 줄이고 상수와 H2 스키마는 그대로 두었더니
**writer 스위트가 전부 green이었다.** `TestSchemaSyncTest`는 writer↔reader 사본만 비교하고,
`init-db.sql`을 읽는 유일한 테스트인 `TrailCommandHandlerPostgresTest`에는 길이 케이스가 없으며
Docker가 없으면 조용히 skip된다. 이번에는 주석을 정직하게 고치는 데 그쳤다.

실질적 해결은 둘 중 하나다:
- `TestSchemaSyncTest`를 확장해 `schema.sql`과 `init-db.sql`의 `VARCHAR(n)` 값을 비교한다.
  Docker 없이 돌아가므로 이쪽이 더 견고하다.
- `TrailCommandHandlerPostgresTest`(운영 `init-db.sql`을 그대로 적용한다)에 길이 케이스를 추가한다.
  더 직접적이지만 Docker가 없으면 skip된다.

### 길이 검증 근거 주석이 두 핸들러에 중복돼 있다

`StreamCommandHandler`와 `TrailCommandHandler`에 8줄짜리 동일한 주석이 있다. 이미 한 번
드리프트했다 — 커밋 `7b02aed`가 같은 문구를 세 군데에서 고쳐야 했다. PostgreSQL의 실제 계산
규칙을 확인하는 날 두 핸들러 + 계획 문서 2곳 + 설계 문서 1곳, 총 5벌을 함께 고쳐야 하는데
부분 수정을 잡아주는 테스트가 없다.

### `StreamCommandHandlerConstraintTest`의 이름이 내용과 안 맞는다

파일 이름은 `Constraint`인데 내용은 길이 테스트뿐이다. Trail 쪽은 진짜 제약 위반 테스트 클래스
안에 길이 테스트를 별도 배너로 넣었다. 같은 종류의 테스트가 구조적으로 다른 두 곳에 산다.

- **`CaptureCommandHandler`의 검증 누락 + Kafka 메시지 조용한 유실**: `captures`의 `image_path`(VARCHAR(500)), `road_status`(VARCHAR(10) + CHECK), `confidence`(DECIMAL(3,2), 최대 9.99), 그리고 `trail_id`/`stream_id` FK가 전부 미검증이다. 다만 Kafka 경로라 `ImageAnalyzedConsumer.consume`이 예외를 삼키고 정상 리턴해서 오프셋이 커밋되므로, 증상은 500이 아니라 **메시지 유실**이다. 검증만 추가하면 삼켜지는 예외 종류만 바뀌므로 재시도/DLQ 설계와 함께 다뤄야 한다.
- **Testcontainers silent-skip**: Docker가 없으면 `TrailCommandHandlerPostgresTest` 5개가 skip되고 BUILD SUCCESS가 난다. 실측으로 재현했다. CI가 생기면 skip 개수를 검사하는 게 좋다.
- **Bean Validation 전환 검토**: `@Size` 같은 선언적 검증으로 옮기면 상수와 컬럼의 이중 관리가 줄지만, 새 의존성과 예외 타입 변경이 따라오고 기존 `if` 검증들과 두 방식이 섞인다. 전환한다면 그것만으로 하나의 사이클이 되어야 한다.
- **`camera_number`가 `VARCHAR`(무제한)이다**: 다른 문자열 컬럼과 달리 상한이 없다. 의도된 것인지 확인이 필요하다.

## 최종 검증

### 전체 모듈 테스트 (2026-08-24)

| 모듈 | 결과 | 기준선 대비 |
|---|---|---|
| shared | `Tests run: 30, Failures: 0, Errors: 0` | 변화 없음 |
| reader | `Tests run: 33, Failures: 0, Errors: 1` | 변화 없음 (에러 1건은 기존 RED `ReaderApplicationTests.contextLoads`) |
| writer | `Tests run: 66, Failures: 0, Errors: 0, Skipped: 5` | 58 → 66 (+8) |
| backend | `Tests run: 36, Failures: 0, Errors: 5` | 변화 없음 (에러 5건은 기존 RED `CaptureControllerTest` 스텁) |

writer의 `Skipped: 5`는 이 시점에 Docker 데몬이 내려가 있어 Testcontainers 클래스
`TrailCommandHandlerPostgresTest`가 skip된 것이다. 회귀가 아니며, Docker가 살아 있으면
`Skipped: 0`이 된다. 이번 작업은 PostgreSQL을 필요로 하지 않는다.

### 상수가 스키마에 묶여 있는지 (변이 테스트)

두 태스크 모두 상수를 일부러 컬럼보다 크게 바꿨을 때 진짜 DB 테스트가 실패하는 것을 확인했다.

- `MAX_NAME_LENGTH`를 255 → 300으로: `overLongNameIsRejectedBeforeReachingDatabase`가
  `DataIntegrityViolationException`(H2 `Value too long for column "NAME CHARACTER VARYING(255)"`,
  SQLState 22001)으로 실패. 255로 되돌리니 통과.
- `MAX_DIRECTION_LENGTH`를 50 → 100으로: `overLongDirectionIsRejectedBeforeReachingDatabase`가
  같은 형태로 실패(`Value too long for column "DIRECTION CHARACTER VARYING(50)"`). 50으로 되돌리니 통과.

반대 방향(상수가 컬럼보다 엄격해지는 경우)은 경계값 테스트가 잡는다. 정확히 255자/50자를
`assertThrows` 없이 그대로 호출하므로, 상수를 낮추면 예상치 못한 예외로 실패한다.

### RED이 엉뚱한 이유로 통과하지 않았는지

Task 2의 mock 테스트는 함정이 있었고 계획이 예측한 형태 그대로 실패했다 — 검증이 없으면
스텁하지 않은 `existsById`가 기본값 `false`를 반환해 `IllegalArgumentException`이 **어차피**
던져진다. 예외 타입만 단언했다면 수정 전에도 통과했을 것이다. 실제 실패는 메시지 단언
(`contains("direction")`)이 `"stream_id=1 does not exist"`와 맞지 않아 일어났다.
