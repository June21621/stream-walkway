# writer 지오메트리 검증 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** WKT의 Z/M/ZM 좌표와 빈 지오메트리가 DB까지 도달해 500이 나가는 것을 400으로 바꾸고, SRID 4326 범위를 벗어난 좌표를 거부한다.

**Architecture:** `com.stream.writer.command` 패키지에 패키지 전용 static 유틸 `GeometryValidator`를 만들고, 두 핸들러가 WKT를 캐스트한 직후·저장 전에 호출한다. `IllegalArgumentException`을 던지면 두 컨트롤러가 이미 갖고 있는 `@ExceptionHandler`가 400으로 바꾼다. 새 예외 타입도, 컨트롤러/backend 수정도 없다.

**Tech Stack:** Java 21, Spring Boot 3.5.1, JTS 1.19.0, Spring Data JPA, H2 2.3.232, JUnit 5, Mockito, AssertJ

**설계 문서:** `docs/superpowers/specs/2026-08-26-writer-geometry-validation-design.md`
**조사 문서:** `docs/superpowers/specs/2026-08-25-writer-geometry-validation-findings.md`

## Global Constraints

- 새 파일과 수정 파일은 전부 `services/writer` 안에 있다. `reader`, `backend`, `packages/shared`는 건드리지 않는다.
- 빌드 명령은 각 모듈의 `.\mvnw.cmd`다. PATH에 `mvn`이 없다. PowerShell에서는 `-D` 인자를 반드시 따옴표로 감싼다: `.\mvnw.cmd -B -o test "-Dtest=SomeTest"`.
- `WriterApplicationTests`는 실제 Postgres가 필요하다. 전체 스위트를 돌릴 때는 `"-Dtest=!WriterApplicationTests"`로 제외한다.
- Docker 데몬이 내려가 있어 Testcontainers 기반 테스트는 `Skipped: 5`로 나온다. **회귀가 아니다.** 기준선: shared 30 / reader 33(1 기존 RED) / writer 66 / backend 36(5 기존 RED).
- 기존에 이미 400이 나가는 입력(다른 지오메트리 타입, `SRID=3857;` 접두사, 점 하나짜리 LINESTRING)의 동작을 바꾸지 않는다.
- 에러 메시지는 아래 세 가지를 그대로 쓴다:
  - `location must not be an empty geometry`
  - `location must have 2D coordinates only (Z/M ordinates are not supported)`
  - `location coordinate out of WGS84 bounds: (<x>, <y>)`

---

## 계획 수립 전 실측으로 확인한 사실 (추측 아님)

구현 중 다시 의심하지 말 것.

1. **`CoordinateSequence.getDimension()`은 차원 판별에 쓸 수 없다.** JTS 1.19.0에서 12가지 WKT를 실제로 파싱해 확인했다. 순수 2D인 `POINT(126.97 37.55)`도 `getDimension()`이 **3**을 반환한다. 이걸로 `> 2` 판정을 하면 모든 정상 입력이 400이 된다.

2. **`Coordinate.getZ()` / `getM()`의 NaN 검사가 정답이다.** 실측표:

   | 입력 | `getDimension()` | `getMeasures()` | `getZ()` | `getM()` |
   |---|---|---|---|---|
   | `POINT(126.97 37.55)` | 3 | 0 | NaN | NaN |
   | `POINT Z(126.97 37.55 1)` | 3 | 0 | 1.0 | NaN |
   | `POINT M(126.97 37.55 1)` | 3 | 1 | NaN | 1.0 |
   | `POINT ZM(126.97 37.55 1 9)` | 4 | 1 | 1.0 | 9.0 |
   | `POINT(126.97 37.55 1)` | 3 | 0 | 1.0 | NaN |
   | `POINT EMPTY` | 3 | 0 | (좌표 배열이 비어 있음) | (동일) |

   LINESTRING 여섯 가지도 같은 양상이었다. `Geometry.getCoordinates()`로 얻은 `Coordinate`가 실제 서브클래스(`CoordinateXYM`, `CoordinateXYZM`)를 유지하므로 Point/LineString 분기 없이 같은 코드가 동작한다.

3. **Z 키워드 없는 옛 JTS 문법 `POINT(126.97 37.55 1)`도 Z=1.0으로 파싱된다.** 조사 문서에 없던 입력이고, 같은 NaN 검사에 걸린다. 이 동작을 테스트로 고정한다.

4. **컨트롤러 변경은 필요 없다.** `StreamController`와 `TrailController` 모두 `@ExceptionHandler({ParseException.class, ClassCastException.class, IllegalArgumentException.class})`를 이미 갖고 있다.

5. **backend 변경도 필요 없다.** `StreamServiceImpl.create()`와 `TrailServiceImpl`이 writer의 `HttpClientErrorException.BadRequest`를 `InvalidStreamGeometryException` / `InvalidTrailGeometryException`으로 변환하고, `GlobalExceptionHandler`가 이를 400으로 내보낸다.

6. **EMPTY를 성공으로 기대하는 기존 테스트는 없다.** 저장소 전체를 `EMPTY`로 grep해 확인했다. 범위 밖 좌표(`999`)를 성공으로 기대하는 테스트도 없다 — `999`가 걸리는 곳은 전부 존재하지 않는 id를 뜻한다.

7. **컬럼 정의:** `streams.location GEOMETRY(LINESTRING, 4326) NOT NULL`, `trails.location GEOMETRY(POINT, 4326) NOT NULL`. 테스트 스키마(`services/writer/src/test/resources/schema.sql:19,27`)와 production 스키마(`infra/scripts/init-db.sql:14,23`)가 같은 정의를 쓴다.

8. **`stream_id = 1`은 `services/writer/src/test/resources/data.sql`이 시드한다.** `@DataJpaTest`에서 Trail을 저장할 때 그대로 쓸 수 있다.

9. **`LINESTRING EMPTY`는 H2 컬럼이 실제로 받아준다** (조사 문서 실측, 201). 우리가 이걸 거부하는 것은 DB 제약이 아니라 **정책 결정**이다. 반면 `POINT EMPTY`는 컬럼이 거부한다(22018). 이 비대칭을 테스트로 남긴다.

---

## File Structure

| 파일 | 책임 |
|---|---|
| `services/writer/src/main/java/com/stream/writer/command/GeometryValidator.java` (신규) | 위치 지오메트리 한 개가 저장 가능한 형태인지 판정하고, 아니면 `IllegalArgumentException`을 던진다. 상태 없음, 패키지 전용. |
| `services/writer/src/main/java/com/stream/writer/command/StreamCommandHandler.java` (수정) | 캐스트 직후 `GeometryValidator.validateLocation()` 호출. |
| `services/writer/src/main/java/com/stream/writer/command/TrailCommandHandler.java` (수정) | 동일. |
| `services/writer/src/test/java/com/stream/writer/command/GeometryValidatorTest.java` (신규) | 검증 규칙 자체를 WKT 단위로 증명한다. Spring도 Mockito도 쓰지 않는다. |
| `services/writer/src/test/java/com/stream/writer/command/GeometryColumnConstraintTest.java` (신규) | 진짜 H2로 "이 검증이 왜 필요한지"를 스키마에 묶는다. 검증을 우회해 직접 저장하면 컬럼이 거부한다는 것, 그리고 `LINESTRING EMPTY`는 컬럼이 받아준다는 것(= 정책 결정)을 기록한다. |
| `services/writer/src/test/java/com/stream/writer/command/StreamCommandHandlerTest.java` (수정) | 핸들러가 검증을 실제로 호출하고 `save()`에 도달하지 않음을 증명. |
| `services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerTest.java` (수정) | 동일. |

**컨트롤러 테스트는 추가하지 않는다.** `StreamControllerTest`/`TrailControllerTest`는 핸들러를 `@MockBean`으로 갈아끼우고 예외를 스텁해서 던진다. 즉 "`IllegalArgumentException` → 400" 매핑만 검증하는데, 그건 기존 `create_returns400On...` 테스트가 이미 커버한다. 지오메트리용으로 하나 더 만들면 예외 메시지만 다른 복제본이 된다. 설계 문서의 테스트 절도 이 판단에 맞춰 Task 4에서 정정한다.

---

## Task 1: `GeometryValidator` — EMPTY와 Z/M/ZM 거부

**Files:**
- Create: `services/writer/src/main/java/com/stream/writer/command/GeometryValidator.java`
- Create: `services/writer/src/test/java/com/stream/writer/command/GeometryValidatorTest.java`

**Interfaces:**
- Consumes: `org.locationtech.jts.geom.Geometry`, `org.locationtech.jts.geom.Coordinate` (JTS 1.19.0).
- Produces: `static void GeometryValidator.validateLocation(Geometry geometry)` — 패키지 전용(`com.stream.writer.command`). 빈 지오메트리이거나 좌표에 Z/M 값이 있으면 `IllegalArgumentException`을 던지고, 아니면 조용히 반환한다. Task 2가 두 핸들러에서 호출하고, Task 3이 좌표 범위 검사를 이 메서드에 덧붙인다.

- [x] **Step 1: 실패하는 테스트를 쓴다**

`services/writer/src/test/java/com/stream/writer/command/GeometryValidatorTest.java`를 새로 만든다:

```java
package com.stream.writer.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ─────────────────────────────────────────
// 검증 규칙 자체를 WKT 단위로 증명한다. Spring도 Mockito도 쓰지 않는다.
// 핸들러가 이 검증을 실제로 호출하는지는 StreamCommandHandlerTest와
// TrailCommandHandlerTest가, 컬럼이 정말 이런 값을 거부하는지는
// GeometryColumnConstraintTest가 증명한다.
// ─────────────────────────────────────────
@DisplayName("Writer - GeometryValidator 테스트")
class GeometryValidatorTest {

    private static Geometry parse(String wkt) {
        try {
            return new WKTReader(new GeometryFactory(new PrecisionModel(), 4326)).read(wkt);
        } catch (ParseException e) {
            throw new IllegalStateException("테스트 입력이 파싱되지 않는다: " + wkt, e);
        }
    }

    @Test
    @DisplayName("validateLocation() - 2D 지오메트리는 통과시킨다")
    void acceptsTwoDimensionalGeometries() {
        for (String wkt : new String[]{
                "POINT(126.97 37.55)",
                "LINESTRING(126.97 37.55, 126.98 37.56)"}) {
            assertThatCode(() -> GeometryValidator.validateLocation(parse(wkt)))
                    .as(wkt)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("validateLocation() - Z/M/ZM 좌표는 거부한다")
    void rejectsHigherDimensionCoordinates() {
        for (String wkt : new String[]{
                "POINT Z(126.97 37.55 1)",
                "POINT M(126.97 37.55 1)",
                "POINT ZM(126.97 37.55 1 9)",
                "LINESTRING Z(126.97 37.55 1, 126.98 37.56 2)",
                "LINESTRING M(126.97 37.55 1, 126.98 37.56 2)",
                "LINESTRING ZM(126.97 37.55 1 9, 126.98 37.56 2 9)"}) {
            assertThatThrownBy(() -> GeometryValidator.validateLocation(parse(wkt)))
                    .as(wkt)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("2D");
        }
    }

    @Test
    @DisplayName("validateLocation() - Z 키워드 없는 옛 JTS 3좌표 문법도 거부한다")
    void rejectsOldJtsThreeOrdinateSyntax() {
        for (String wkt : new String[]{
                "POINT(126.97 37.55 1)",
                "LINESTRING(126.97 37.55 1, 126.98 37.56 2)"}) {
            assertThatThrownBy(() -> GeometryValidator.validateLocation(parse(wkt)))
                    .as(wkt)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("2D");
        }
    }

    @Test
    @DisplayName("validateLocation() - 빈 지오메트리는 양쪽 다 거부한다")
    void rejectsEmptyGeometries() {
        for (String wkt : new String[]{"POINT EMPTY", "LINESTRING EMPTY"}) {
            assertThatThrownBy(() -> GeometryValidator.validateLocation(parse(wkt)))
                    .as(wkt)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("empty");
        }
    }

    @Test
    @DisplayName("validateLocation() - 빈 지오메트리를 차원 메시지가 아니라 empty 메시지로 거부한다 (검사 순서)")
    void reportsEmptyBeforeDimension() {
        assertThatThrownBy(() -> GeometryValidator.validateLocation(parse("POINT EMPTY")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("2D");
    }
}
```

- [x] **Step 2: 컴파일 실패를 확인한다**

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=GeometryValidatorTest"
```
Expected: **FAIL.** `GeometryValidator` 심볼을 찾을 수 없다는 컴파일 에러가 난다(`cannot find symbol: class GeometryValidator`). 테스트가 실행조차 되지 않는 것이 정상이다.

- [x] **Step 3: 최소 구현을 쓴다**

`services/writer/src/main/java/com/stream/writer/command/GeometryValidator.java`:

```java
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
// 도달해 DataIntegrityViolationException(SQLState 22018) → 500이 된다.
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

    private GeometryValidator() {
    }

    static void validateLocation(Geometry geometry) {
        if (geometry.isEmpty()) {
            throw new IllegalArgumentException("location must not be an empty geometry");
        }
        for (Coordinate coordinate : geometry.getCoordinates()) {
            if (!Double.isNaN(coordinate.getZ()) || !Double.isNaN(coordinate.getM())) {
                throw new IllegalArgumentException(
                        "location must have 2D coordinates only (Z/M ordinates are not supported)");
            }
        }
    }
}
```

- [x] **Step 4: 테스트가 통과하는지 확인한다**

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=GeometryValidatorTest"
```
Expected: **PASS.** `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`

- [x] **Step 5: 커밋하지 않는다**

Task 2에서 핸들러 배선까지 끝낸 뒤 한 커밋으로 묶는다. 지금 커밋하면 아무도 호출하지 않는 죽은 코드가 히스토리에 남는다. 다음 태스크로 넘어간다.

---

## Task 2: 두 핸들러에 검증 배선 + 진짜 H2로 필요성 증명

**Files:**
- Modify: `services/writer/src/main/java/com/stream/writer/command/StreamCommandHandler.java`
- Modify: `services/writer/src/main/java/com/stream/writer/command/TrailCommandHandler.java`
- Modify: `services/writer/src/test/java/com/stream/writer/command/StreamCommandHandlerTest.java`
- Modify: `services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerTest.java`
- Create: `services/writer/src/test/java/com/stream/writer/command/GeometryColumnConstraintTest.java`

**Interfaces:**
- Consumes: `GeometryValidator.validateLocation(Geometry)` (Task 1), `StreamCommandHandler(StreamRepository)`, `TrailCommandHandler(TrailRepository, StreamRepository)`, `CreateStreamCommand(String name, String location)`, `CreateTrailCommand(Long streamId, String cameraNumber, String location, String direction, String status)`, `com.stream.shared.entity.Stream`(`Stream.SRID` 포함), `com.stream.shared.entity.Trail`(`Trail.SRID` 포함).
- Produces: 두 핸들러가 Z/M/ZM/EMPTY 위치에 대해 `save()` 이전에 `IllegalArgumentException`을 던진다. Task 3이 같은 호출 지점을 그대로 쓴다(핸들러 재수정 없음).

- [x] **Step 1: Stream 핸들러의 실패하는 테스트를 쓴다**

`services/writer/src/test/java/com/stream/writer/command/StreamCommandHandlerTest.java`의 마지막 `@Test` 메서드 뒤, 클래스 닫는 `}` 앞에 추가:

```java
    @Test
    @DisplayName("handle() - location에 Z/M/ZM 좌표가 있으면 IllegalArgumentException을 던진다 (저장 시도 안 함)")
    void handle_throwsIllegalArgumentExceptionOnNon2dLocation() {
        // given: streams.location은 GEOMETRY(LINESTRING,4326)이라 3D/4D 좌표를 컬럼이 거부한다.
        // 검증이 없으면 저장까지 가서 DataIntegrityViolationException(22018) → 500이 된다.
        for (String wkt : new String[]{
                "LINESTRING Z(126.97 37.55 1, 126.98 37.56 2)",
                "LINESTRING M(126.97 37.55 1, 126.98 37.56 2)",
                "LINESTRING ZM(126.97 37.55 1 9, 126.98 37.56 2 9)"}) {
            CreateStreamCommand command = new CreateStreamCommand("한강 산책로", wkt);

            // when & then
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handle(command))
                    .as(wkt)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("2D");
        }
        verify(streamRepository, org.mockito.Mockito.never()).save(any(Stream.class));
    }

    @Test
    @DisplayName("handle() - location이 빈 지오메트리면 IllegalArgumentException을 던진다 (저장 시도 안 함)")
    void handle_throwsIllegalArgumentExceptionOnEmptyLocation() {
        // given: LINESTRING EMPTY는 지금 201로 저장된다. Trail의 POINT EMPTY(500)와
        // 동작이 갈리는 비대칭이라 양쪽 다 400으로 맞춘다.
        CreateStreamCommand command = new CreateStreamCommand("한강 산책로", "LINESTRING EMPTY");

        // when & then
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        verify(streamRepository, org.mockito.Mockito.never()).save(any(Stream.class));
    }
```

- [x] **Step 2: Trail 핸들러의 실패하는 테스트를 쓴다**

`services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerTest.java`의 마지막 `@Test` 메서드 뒤, 클래스 닫는 `}` 앞에 추가:

```java
    @Test
    @DisplayName("handle() - location에 Z/M/ZM 좌표가 있으면 IllegalArgumentException을 던진다 (저장 시도 안 함)")
    void handle_throwsIllegalArgumentExceptionOnNon2dLocation() {
        // given: trails.location은 GEOMETRY(POINT,4326)이라 3D/4D 좌표를 컬럼이 거부한다.
        for (String wkt : new String[]{
                "POINT Z(126.97 37.55 1)",
                "POINT M(126.97 37.55 1)",
                "POINT ZM(126.97 37.55 1 9)"}) {
            CreateTrailCommand command = new CreateTrailCommand(1L, "CAM-GEO", wkt, "북", "active");

            // when & then
            // 메시지까지 확인해야 한다. 이 단언이 없으면 스텁하지 않은 existsById가
            // 기본값 false를 반환해 던지는 "stream_id=1 does not exist" 예외 때문에
            // 검증이 없어도 테스트가 통과해버린다.
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handle(command))
                    .as(wkt)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("2D");
        }
        verify(trailRepository, org.mockito.Mockito.never()).save(any(Trail.class));
    }

    @Test
    @DisplayName("handle() - location이 빈 지오메트리면 IllegalArgumentException을 던진다 (저장 시도 안 함)")
    void handle_throwsIllegalArgumentExceptionOnEmptyLocation() {
        // given: POINT EMPTY는 지금 컬럼까지 도달해 500이 된다.
        CreateTrailCommand command = new CreateTrailCommand(1L, "CAM-EMPTY", "POINT EMPTY", "북", "active");

        // when & then
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        verify(trailRepository, org.mockito.Mockito.never()).save(any(Trail.class));
    }
```

- [x] **Step 3: 테스트를 돌려서 RED를 확인한다**

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=StreamCommandHandlerTest+TrailCommandHandlerTest"
```
Expected: **FAIL.** 새로 넣은 네 개가 전부 실패한다.

**실패 형태를 확인할 것** — 예상되는 모습은 두 가지다:
- Stream 쪽: 검증이 없어 예외가 아예 안 나고, mock `save()`가 `null`을 반환하며 정상 종료된다 → `Expecting code to raise a throwable` 형태의 실패.
- Trail 쪽: 예외는 나지만 `existsById`가 기본값 `false`라 메시지가 `stream_id=1 does not exist`다 → `hasMessageContaining("2D")` 단언이 실패한다.

Trail 쪽이 이 함정 형태로 실패하는 것이 정상이다. 만약 Trail 테스트가 **통과**한다면 메시지 단언이 빠진 것이니 Step 2를 다시 확인한다.

- [x] **Step 4: `StreamCommandHandler`를 수정한다**

`services/writer/src/main/java/com/stream/writer/command/StreamCommandHandler.java`의 `handle()` 끝부분을 바꾼다.

바꾸기 전:
```java
        WKTReader wktReader = new WKTReader(new GeometryFactory(new PrecisionModel(), Stream.SRID));
        Stream stream = new Stream();
        stream.setName(command.name());
        stream.setLocation((LineString) wktReader.read(command.location()));
        return streamRepository.save(stream);
```

바꾼 뒤:
```java
        WKTReader wktReader = new WKTReader(new GeometryFactory(new PrecisionModel(), Stream.SRID));
        // 캐스트를 먼저 하고 검증한다. 그래야 LineString이 아닌 WKT는 지금처럼
        // ClassCastException 메시지로 400이 나가고 기존 동작이 바뀌지 않는다.
        LineString location = (LineString) wktReader.read(command.location());
        GeometryValidator.validateLocation(location);

        Stream stream = new Stream();
        stream.setName(command.name());
        stream.setLocation(location);
        return streamRepository.save(stream);
```

- [x] **Step 5: `TrailCommandHandler`를 수정한다**

`services/writer/src/main/java/com/stream/writer/command/TrailCommandHandler.java`의 `handle()`에서 WKT 파싱 블록을 바꾼다.

바꾸기 전:
```java
        WKTReader wktReader = new WKTReader(new GeometryFactory(new PrecisionModel(), Trail.SRID));
        Trail trail = new Trail();
        trail.setStreamId(command.streamId());
        trail.setCameraNumber(command.cameraNumber());
        trail.setLocation((Point) wktReader.read(command.location()));
        trail.setDirection(command.direction());
        trail.setStatus(status);
```

바꾼 뒤:
```java
        WKTReader wktReader = new WKTReader(new GeometryFactory(new PrecisionModel(), Trail.SRID));
        // 캐스트를 먼저 하고 검증한다. 그래야 Point가 아닌 WKT는 지금처럼
        // ClassCastException 메시지로 400이 나가고 기존 동작이 바뀌지 않는다.
        // 검증은 existsById 조회보다 앞이라 잘못된 지오메트리로는 DB를 건드리지 않는다.
        Point location = (Point) wktReader.read(command.location());
        GeometryValidator.validateLocation(location);

        Trail trail = new Trail();
        trail.setStreamId(command.streamId());
        trail.setCameraNumber(command.cameraNumber());
        trail.setLocation(location);
        trail.setDirection(command.direction());
        trail.setStatus(status);
```

- [x] **Step 6: 테스트가 통과하는지 확인한다**

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=StreamCommandHandlerTest+TrailCommandHandlerTest+GeometryValidatorTest"
```
Expected: **PASS.** 실패 0.

- [x] **Step 7: 진짜 H2로 "왜 이 검증이 필요한가"를 고정하는 테스트를 만든다**

`services/writer/src/test/java/com/stream/writer/command/GeometryColumnConstraintTest.java`:

```java
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
}
```

- [x] **Step 8: H2 테스트를 돌린다**

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=GeometryColumnConstraintTest"
```
Expected: **PASS, 4개.**

만약 `threeDimensionalLineStringIsRejectedByTheColumn`이나 `emptyPointIsRejectedByTheColumn`이 실패한다면(= H2가 받아준다면) 조사 문서의 실측과 어긋나는 것이다. 그때는 **테스트를 지우지 말고** 무엇이 실제로 일어났는지 예외 타입/메시지를 기록하고 보고한다.

`emptyLineStringIsAcceptedByTheColumn`이 실패한다면(= H2가 거부한다면) 조사 문서의 "LINESTRING EMPTY는 201" 실측과 어긋난다. 마찬가지로 보고한다.

- [x] **Step 9: writer 전체 스위트를 돌린다**

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=!WriterApplicationTests"
```
Expected: **PASS.** 기준선 66개에 이번 태스크에서 추가한 테스트 메서드 수만큼 늘어난다. `Skipped: 5`는 Docker가 내려가 있어서 나는 것으로 회귀가 아니다. **실패가 0인지만 본다.**

- [x] **Step 10: 커밋**

```bash
git add services/writer/src/main/java/com/stream/writer/command/GeometryValidator.java services/writer/src/main/java/com/stream/writer/command/StreamCommandHandler.java services/writer/src/main/java/com/stream/writer/command/TrailCommandHandler.java services/writer/src/test/java/com/stream/writer/command/GeometryValidatorTest.java services/writer/src/test/java/com/stream/writer/command/GeometryColumnConstraintTest.java services/writer/src/test/java/com/stream/writer/command/StreamCommandHandlerTest.java services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerTest.java
git commit -m "fix(writer): Z/M/ZM 좌표와 빈 지오메트리에 500 대신 400 반환"
```

---

## Task 3: 좌표 범위 검사 (SRID 4326)

**Files:**
- Modify: `services/writer/src/main/java/com/stream/writer/command/GeometryValidator.java`
- Modify: `services/writer/src/test/java/com/stream/writer/command/GeometryValidatorTest.java`
- Modify: `services/writer/src/test/java/com/stream/writer/command/StreamCommandHandlerTest.java`
- Modify: `services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerTest.java`

**Interfaces:**
- Consumes: Task 1의 `GeometryValidator.validateLocation(Geometry)`, Task 2가 배선한 두 핸들러의 호출 지점.
- Produces: 같은 메서드 시그니처(`static void validateLocation(Geometry)`). 핸들러 코드는 손대지 않는다. 경도 절댓값이 180을 넘거나 위도 절댓값이 90을 넘는 좌표가 하나라도 있으면 `IllegalArgumentException`을 던진다.

- [x] **Step 1: 실패하는 테스트를 쓴다**

`services/writer/src/test/java/com/stream/writer/command/GeometryValidatorTest.java`의 마지막 `@Test` 뒤, 클래스 닫는 `}` 앞에 추가:

```java
    @Test
    @DisplayName("validateLocation() - WGS84 범위를 벗어난 좌표는 거부한다")
    void rejectsCoordinatesOutOfWgs84Bounds() {
        for (String wkt : new String[]{
                "POINT(999 999)",
                "LINESTRING(999 999, 1000 1000)",
                "POINT(180.1 37.55)",
                "POINT(126.97 90.1)",
                "POINT(-180.1 37.55)",
                "POINT(126.97 -90.1)",
                // 첫 좌표는 정상이고 두 번째만 범위를 벗어난 경우도 잡아야 한다
                "LINESTRING(126.97 37.55, 999 37.56)"}) {
            assertThatThrownBy(() -> GeometryValidator.validateLocation(parse(wkt)))
                    .as(wkt)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("WGS84 bounds");
        }
    }

    @Test
    @DisplayName("validateLocation() - 경계값 ±180 / ±90은 통과시킨다")
    void acceptsCoordinatesAtWgs84Bounds() {
        for (String wkt : new String[]{
                "POINT(180 90)",
                "POINT(-180 -90)",
                "LINESTRING(-180 -90, 180 90)"}) {
            assertThatCode(() -> GeometryValidator.validateLocation(parse(wkt)))
                    .as(wkt)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("validateLocation() - 범위와 차원이 둘 다 틀리면 차원 메시지가 먼저 나온다 (검사 순서)")
    void reportsDimensionBeforeBounds() {
        assertThatThrownBy(() -> GeometryValidator.validateLocation(parse("POINT Z(999 999 1)")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2D");
    }
```

- [x] **Step 2: 테스트를 돌려서 RED를 확인한다**

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=GeometryValidatorTest"
```
Expected: **FAIL.** `rejectsCoordinatesOutOfWgs84Bounds`가 `Expecting code to raise a throwable` 형태로 실패한다.

`acceptsCoordinatesAtWgs84Bounds`와 `reportsDimensionBeforeBounds`는 **이미 통과한다.** 정상이다 — 이 둘은 RED를 만드는 게 목적이 아니라 구현이 과하게 엄격해지거나 검사 순서가 뒤집히는 것을 막는 회귀 방지용이다.

- [x] **Step 3: `GeometryValidator`에 범위 검사를 추가한다**

`services/writer/src/main/java/com/stream/writer/command/GeometryValidator.java`의 `final class GeometryValidator {` 본문 전체를 아래로 바꾼다(클래스 앞 주석 블록은 그대로 둔다):

```java
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
        for (Coordinate coordinate : geometry.getCoordinates()) {
            if (!Double.isNaN(coordinate.getZ()) || !Double.isNaN(coordinate.getM())) {
                throw new IllegalArgumentException(
                        "location must have 2D coordinates only (Z/M ordinates are not supported)");
            }
        }
        for (Coordinate coordinate : geometry.getCoordinates()) {
            if (Math.abs(coordinate.getX()) > MAX_ABS_LONGITUDE
                    || Math.abs(coordinate.getY()) > MAX_ABS_LATITUDE) {
                throw new IllegalArgumentException(
                        "location coordinate out of WGS84 bounds: ("
                                + coordinate.getX() + ", " + coordinate.getY() + ")");
            }
        }
    }
}
```

- [x] **Step 4: 테스트가 통과하는지 확인한다**

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=GeometryValidatorTest"
```
Expected: **PASS, 8개.**

- [x] **Step 5: 두 핸들러에 범위 테스트를 한 건씩 추가한다**

`services/writer/src/test/java/com/stream/writer/command/StreamCommandHandlerTest.java`의 클래스 닫는 `}` 앞에:

```java
    @Test
    @DisplayName("handle() - location 좌표가 WGS84 범위를 벗어나면 IllegalArgumentException을 던진다 (저장 시도 안 함)")
    void handle_throwsIllegalArgumentExceptionOnOutOfBoundsLocation() {
        // given: SRID 4326인데 위도 999는 존재할 수 없다. 컬럼은 이걸 막지 않아
        // 검증이 없으면 201로 저장된다.
        CreateStreamCommand command = new CreateStreamCommand("한강 산책로", "LINESTRING(999 999, 1000 1000)");

        // when & then
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WGS84 bounds");
        verify(streamRepository, org.mockito.Mockito.never()).save(any(Stream.class));
    }
```

`services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerTest.java`의 클래스 닫는 `}` 앞에:

```java
    @Test
    @DisplayName("handle() - location 좌표가 WGS84 범위를 벗어나면 IllegalArgumentException을 던진다 (저장 시도 안 함)")
    void handle_throwsIllegalArgumentExceptionOnOutOfBoundsLocation() {
        // given: SRID 4326인데 위도 999는 존재할 수 없다. 컬럼은 이걸 막지 않아
        // 검증이 없으면 201로 저장된다.
        CreateTrailCommand command = new CreateTrailCommand(1L, "CAM-OOB", "POINT(999 999)", "북", "active");

        // when & then
        // 메시지 단언이 없으면 스텁하지 않은 existsById가 던지는 예외로 통과해버린다.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WGS84 bounds");
        verify(trailRepository, org.mockito.Mockito.never()).save(any(Trail.class));
    }
```

- [x] **Step 6: writer 전체 스위트를 돌린다**

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=!WriterApplicationTests"
```
Expected: **PASS.** 실패 0. `Skipped: 5`는 정상.

- [x] **Step 7: 나머지 세 모듈도 돌려서 회귀가 없는지 본다**

`packages/shared`를 먼저 install해야 reader/writer가 빌드된다.

Run (`packages/shared`에서):
```
.\mvnw.cmd -B -o install "-DskipTests" && .\mvnw.cmd -B -o test
```
Run (`services/reader`에서):
```
.\mvnw.cmd -B -o test
```
Run (`apps/backend`에서):
```
.\mvnw.cmd -B -o test
```
Expected: shared 30 통과. reader 33 중 1개 기존 RED. backend 36 중 5개 기존 RED. **기존 RED 개수가 늘어나지 않았는지만 확인한다.** 이 세 모듈은 이번 변경과 무관하므로 새 실패가 생기면 멈추고 보고한다.

- [x] **Step 8: 커밋**

```bash
git add services/writer/src/main/java/com/stream/writer/command/GeometryValidator.java services/writer/src/test/java/com/stream/writer/command/GeometryValidatorTest.java services/writer/src/test/java/com/stream/writer/command/StreamCommandHandlerTest.java services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerTest.java
git commit -m "fix(writer): SRID 4326 범위를 벗어난 좌표를 400으로 거부"
```

---

## Task 4: 검증 결과 기록과 설계 문서 정정

**Files:**
- Create: `docs/superpowers/specs/2026-08-26-writer-geometry-validation-verification.md`
- Modify: `docs/superpowers/specs/2026-08-26-writer-geometry-validation-design.md`
- Modify: `docs/superpowers/plans/2026-08-26-writer-geometry-validation.md`

**Interfaces:**
- Consumes: Task 2와 Task 3에서 실제로 관찰한 테스트 출력.
- Produces: 없음(문서 전용).

- [x] **Step 1: 검증 결과 문서를 쓴다**

`docs/superpowers/specs/2026-08-26-writer-geometry-validation-verification.md`에 **실제로 본 것만** 기록한다. 최소한 다음을 담는다:

- 네 모듈의 최종 테스트 수치(통과/실패/스킵)와 기준선 대비 증감
- Task 2 Step 3의 RED가 계획이 예측한 형태(Trail은 `stream_id=1 does not exist` 메시지로 실패)로 실패했는지, 아니면 다른 형태였는지
- `GeometryColumnConstraintTest` 네 건의 결과 — 특히 `LINESTRING EMPTY`를 H2가 받아준다는 것이 재확인됐는지
- 조사 문서에서 500이던 7가지가 이제 400인지에 대한 근거(어느 테스트가 그것을 증명하는지)
- Postgres/PostGIS 실측은 하지 않았다는 사실과 그 이유(Docker 데몬 미기동, 그리고 이 검증이 DB 도달 전에 일어나므로 엔진 무관)

과장하지 않는다. 돌리지 않은 것은 돌리지 않았다고 쓴다.

- [x] **Step 2: 설계 문서의 테스트 절을 실제와 맞춘다**

`docs/superpowers/specs/2026-08-26-writer-geometry-validation-design.md`의 "테스트" 절에 있는 **컨트롤러 테스트** 항목을 지우고, 그 자리에 `GeometryColumnConstraintTest`(진짜 H2)를 넣는다. 이유도 함께 적는다 — 컨트롤러 테스트는 핸들러를 `@MockBean`으로 갈아끼우고 예외를 스텁해 던지므로 `IllegalArgumentException → 400` 매핑만 검증하는데, 그건 기존 `create_returns400On...` 테스트가 이미 커버한다.

- [x] **Step 3: 계획 문서의 체크박스를 채운다**

이 파일(`docs/superpowers/plans/2026-08-26-writer-geometry-validation.md`)의 `- [ ]`를 완료한 것만 `- [x]`로 바꾼다. 건너뛴 스텝이 있으면 그대로 두고 검증 문서에 이유를 적는다.

- [x] **Step 4: 커밋**

```bash
git add docs/
git commit -m "docs: 지오메트리 검증 작업의 최종 검증 결과 기록"
```

---

## 완료 조건

- `docs/superpowers/specs/2026-08-25-writer-geometry-validation-findings.md`에서 500으로 확인된 7가지 입력이 전부 `IllegalArgumentException` → 400이 된다.
- `LINESTRING EMPTY`가 400이 된다(기존 201에서 변경).
- WGS84 범위를 벗어난 좌표가 400이 된다(기존 201에서 변경).
- 조사에서 이미 400이던 입력들(다른 지오메트리 타입, `SRID=3857;` 접두사, 점 하나짜리 LINESTRING)의 동작이 그대로다.
- writer 스위트 실패 0. 나머지 세 모듈의 기존 RED 개수가 늘지 않았다.
- 커밋 3개(기능 2 + 문서 1)로 나뉘어 있다.
