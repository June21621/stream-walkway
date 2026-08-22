# Trail 생성 시 존재하지 않는 stream_id를 400으로 처리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 존재하지 않는 `stream_id`로 Trail을 생성하려 할 때 지금은 원인 불명의 500이 나가는데, 이를 명확한 메시지와 함께 400으로 바꾼다.

**Architecture:** `TrailCommandHandler`가 저장 직전에 `StreamRepository.existsById()`로 해당 하천이 존재하는지 먼저 확인하고, 없으면 `IllegalArgumentException`을 던진다(→ writer의 기존 `@ExceptionHandler`가 400으로 변환). 확인과 저장 사이에 하천이 삭제되는 경쟁 상황을 대비해, FK 제약 위반(`trails_stream_id_fkey`)을 잡는 안전망도 catch 블록에 함께 둔다. `apps/backend`는 이미 writer의 400을 `InvalidTrailGeometryException`(400)으로 매핑하므로 **변경이 필요 없다.**

**Tech Stack:** Java 21, Spring Boot 3.5.x, Spring Data JPA, PostgreSQL, JUnit 5, Mockito, AssertJ

## 계획 수립 전 실측으로 확인한 사실 (추측 아님)

1. **버그는 실재한다.** 2026-08-22 Docker 실기동 검증에서 `POST /api/trails`에 `{"stream_id":999,...}`를 보내니 `{"timestamp":...,"status":500,"error":"Internal Server Error"}`가 나왔다. 우리 `GlobalExceptionHandler`의 형식이 아니라 Spring 기본 500 형식이었다 — 아무도 이 예외를 처리하지 않고 있다는 뜻이다.
2. **FK 제약의 실제 이름은 `trails_stream_id_fkey`다.** 실제 PostgreSQL에 `SELECT conname, contype FROM pg_constraint WHERE conrelid='trails'::regclass`를 실행해서 확인했다(추정 아님). 같은 조회에서 UNIQUE 제약이 `trails_stream_id_camera_number_key`인 것도 재확인했다 — 기존 409 처리가 옳다는 근거.
3. **`services/writer`에 `StreamRepository`가 이미 있다** (`services/writer/src/main/java/com/stream/writer/repository/StreamRepository.java`). 새로 만들 필요 없이 주입만 하면 된다.
4. **기존 테스트 중 하나가 지금의 "다시 던지기" 동작을 검증하고 있다.** `TrailCommandHandlerTest.handle_rethrowsDataIntegrityViolationExceptionOnOtherConstraintViolation`이 FK 위반 시 `DataIntegrityViolationException`이 그대로 나오는 걸 기대한다. 이 동작이 바뀌므로 **이 테스트도 함께 고쳐야 한다.**

## Global Constraints

- **`apps/backend`는 건드리지 않는다.** `TrailServiceImpl`이 이미 `HttpClientErrorException.BadRequest` → `InvalidTrailGeometryException`으로 매핑하고 `GlobalExceptionHandler`가 이를 400으로 내보낸다. writer가 400을 주기 시작하면 backend는 자동으로 400을 전달한다.
- **writer의 `TrailController`도 건드리지 않는다.** 이미 `@ExceptionHandler({ParseException.class, ClassCastException.class, IllegalArgumentException.class})`가 400을 반환하므로, 새로 던지는 `IllegalArgumentException`이 그대로 400이 된다.
- **존재 확인은 값싼 검증들(null 체크, status 검증, WKT 파싱) 뒤, `save()` 직전에 넣는다.** 두 가지 이유: (1) 형식이 틀린 요청은 DB를 건드리기 전에 걸러야 한다, (2) 기존 테스트 10개 중 `save()`까지 도달하는 4개만 `existsById` 스텁이 필요해져서 테스트 변경이 최소화된다.
- **FK catch는 제거하지 않고 안전망으로 남긴다.** 존재 확인과 저장 사이에 하천이 삭제되는 경쟁 상황이 이론상 가능하므로, `trails_stream_id_fkey` 위반도 같은 `IllegalArgumentException`으로 변환한다.
- **UNIQUE 위반(409) 처리는 그대로 유지한다.** `trails_stream_id_camera_number_key` 매칭 로직과 `DuplicateTrailException`은 손대지 않는다.
- Mockito 엄격 모드(strict stubs)를 쓰므로 **필요 없는 스텁을 추가하면 `UnnecessaryStubbingException`으로 실패한다.** `existsById` 스텁은 실제로 그 지점까지 도달하는 테스트에만 추가한다.
- `WriterApplicationTests`는 실제 Postgres 연결이 필요해 이 샌드박스에서 계속 실패한다 — `-Dtest='!WriterApplicationTests'`로 제외한다.
- 패키지 루트는 `com.stream.writer`. Java 21.

---

## Task 1: 존재하지 않는 stream_id를 400으로 처리

**Files:**
- Modify: `services/writer/src/main/java/com/stream/writer/command/TrailCommandHandler.java`
- Modify: `services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerTest.java`

**Interfaces:**
- Consumes: `com.stream.writer.repository.StreamRepository`(기존 파일, `JpaRepository<Stream, Long>`이므로 `existsById(Long)`를 그대로 쓸 수 있음)
- Produces: `TrailCommandHandler`의 생성자가 인자 2개(`TrailRepository`, `StreamRepository`)를 받게 됨. 존재하지 않는 `streamId`면 `IllegalArgumentException("stream_id=999 does not exist")` 형태로 던짐.

- [ ] **Step 1: 실패하는 테스트를 먼저 추가한다**

`services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerTest.java`의 마지막 `@Test` 메서드 뒤, 클래스 닫는 `}` 앞에 추가:

```java
    @Test
    @DisplayName("handle() - 존재하지 않는 stream_id면 IllegalArgumentException을 던진다 (저장 시도 안 함)")
    void handle_throwsIllegalArgumentExceptionOnNonExistentStreamId() {
        // given
        CreateTrailCommand command = new CreateTrailCommand(999L, "CAM-100", "POINT(126.97 37.55)", "북", "active");
        given(streamRepository.existsById(999L)).willReturn(false);

        // when & then
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> handler.handle(command));
        assertThat(e.getMessage()).contains("999");
        verify(trailRepository, org.mockito.Mockito.never()).save(any(Trail.class));
    }

    @Test
    @DisplayName("handle() - 존재 확인 직후 하천이 삭제된 경우(FK 위반)도 IllegalArgumentException으로 변환한다")
    void handle_throwsIllegalArgumentExceptionOnForeignKeyViolation() {
        // given
        CreateTrailCommand command = new CreateTrailCommand(1L, "CAM-101", "POINT(126.97 37.55)", "북", "active");
        given(streamRepository.existsById(1L)).willReturn(true);
        willThrow(new DataIntegrityViolationException("insert failed",
                new RuntimeException("insert or update on table \"trails\" violates foreign key constraint \"trails_stream_id_fkey\"")))
                .given(trailRepository).save(any(Trail.class));

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> handler.handle(command));
    }
```

- [ ] **Step 2: `@Mock` 필드와 import를 추가한다**

같은 파일에서, 기존 `@Mock private TrailRepository trailRepository;` 선언 바로 아래에 추가:

```java
    @Mock
    private StreamRepository streamRepository;
```

그리고 import 블록에 추가(기존 `import com.stream.writer.repository.TrailRepository;` 옆):

```java
import com.stream.writer.repository.StreamRepository;
```

`@InjectMocks private TrailCommandHandler handler;`는 그대로 둔다 — Mockito가 생성자 인자 2개를 자동으로 주입한다.

- [ ] **Step 3: 테스트 실행 → 실패 확인**

Run: `cd services/writer && ./mvnw -q -B test -Dtest=TrailCommandHandlerTest`
Expected: FAIL. 새 테스트 2개가 실패한다 — `handler`가 아직 `streamRepository`를 쓰지 않으므로 존재 확인 없이 `save()`로 넘어가고, 첫 번째 테스트는 `IllegalArgumentException`이 안 나서, 두 번째는 `DataIntegrityViolationException`이 그대로 나와서 실패한다. 추가로 Mockito 엄격 모드가 `streamRepository.existsById` 스텁을 안 썼다며 `UnnecessaryStubbingException`을 낼 수도 있는데, 이 역시 예상된 RED 신호다.

- [ ] **Step 4: TrailCommandHandler를 수정한다**

`services/writer/src/main/java/com/stream/writer/command/TrailCommandHandler.java` 전체를 아래로 교체:

```java
package com.stream.writer.command;

import com.stream.shared.entity.Trail;
import com.stream.writer.exception.DuplicateTrailException;
import com.stream.writer.repository.StreamRepository;
import com.stream.writer.repository.TrailRepository;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class TrailCommandHandler {

    private static final Set<String> VALID_STATUSES = Set.of("active", "inactive");

    private final TrailRepository trailRepository;
    private final StreamRepository streamRepository;

    public TrailCommandHandler(TrailRepository trailRepository, StreamRepository streamRepository) {
        this.trailRepository = trailRepository;
        this.streamRepository = streamRepository;
    }

    // ─────────────────────────────────────────
    // CreateTrailCommand 처리 → 필수값 검증 → status 기본값/검증 →
    // WKT 문자열을 Point로 파싱 → stream_id 존재 확인 → PostgreSQL 저장.
    //
    // 존재 확인은 값싼 검증들을 모두 통과한 뒤 save() 직전에 한다
    // (형식이 틀린 요청 때문에 불필요한 DB 조회를 하지 않기 위해).
    //
    // UNIQUE(stream_id, camera_number) 위반은 DuplicateTrailException(409)으로,
    // FK(trails_stream_id_fkey) 위반은 IllegalArgumentException(400)으로 변환한다.
    // FK catch는 존재 확인과 저장 사이에 하천이 삭제되는 경쟁 상황을 위한 안전망이다.
    //
    // WKTReader는 스레드 안전하지 않으므로(JTS 문서 명시) 싱글턴 빈의 필드로 공유하지 않고
    // 매 호출마다 새로 만든다 (생성 비용은 미미함).
    // ─────────────────────────────────────────
    public Trail handle(CreateTrailCommand command) throws ParseException {
        if (command.streamId() == null) {
            throw new IllegalArgumentException("streamId is required");
        }
        if (command.cameraNumber() == null || command.cameraNumber().isBlank()) {
            throw new IllegalArgumentException("cameraNumber is required");
        }
        if (command.location() == null || command.location().isBlank()) {
            throw new IllegalArgumentException("location is required (WKT)");
        }

        String status = command.status() == null ? "active" : command.status();
        if (!VALID_STATUSES.contains(status)) {
            throw new IllegalArgumentException("Invalid status: " + status + " (must be 'active' or 'inactive')");
        }

        WKTReader wktReader = new WKTReader(new GeometryFactory(new PrecisionModel(), Trail.SRID));
        Trail trail = new Trail();
        trail.setStreamId(command.streamId());
        trail.setCameraNumber(command.cameraNumber());
        trail.setLocation((Point) wktReader.read(command.location()));
        trail.setDirection(command.direction());
        trail.setStatus(status);

        if (!streamRepository.existsById(command.streamId())) {
            throw new IllegalArgumentException("stream_id=" + command.streamId() + " does not exist");
        }

        try {
            return trailRepository.save(trail);
        } catch (DataIntegrityViolationException e) {
            String message = e.getMostSpecificCause().getMessage();
            if (message != null && message.contains("trails_stream_id_camera_number_key")) {
                throw new DuplicateTrailException(
                        "stream_id=" + command.streamId() + ", camera_number=" + command.cameraNumber() + " already exists");
            }
            if (message != null && message.contains("trails_stream_id_fkey")) {
                throw new IllegalArgumentException("stream_id=" + command.streamId() + " does not exist");
            }
            throw e;
        }
    }
}
```

- [ ] **Step 5: `save()`까지 도달하는 기존 테스트 4개에 `existsById` 스텁을 추가한다**

이 4개 테스트는 이제 존재 확인을 통과해야 원래 검증하던 지점까지 갈 수 있다. 각 테스트의 `// given` 블록에서 `given(trailRepository.save(...))` 또는 `willThrow(...).given(trailRepository)` 줄 **바로 위**에 해당 스텁을 추가한다.

`handle_savesTrailWithParsedGeometry` (streamId=1):
```java
        given(streamRepository.existsById(1L)).willReturn(true);
```

`handle_defaultsNullStatusToActive` (streamId=1):
```java
        given(streamRepository.existsById(1L)).willReturn(true);
```

`handle_throwsDuplicateTrailExceptionOnConstraintViolation` (streamId=1):
```java
        given(streamRepository.existsById(1L)).willReturn(true);
```

`handle_rethrowsDataIntegrityViolationExceptionOnOtherConstraintViolation` (streamId=1):
```java
        given(streamRepository.existsById(1L)).willReturn(true);
```

**나머지 6개 테스트에는 절대 추가하지 않는다** — 그 테스트들은 존재 확인에 도달하기 전에 예외로 끝나므로, 스텁을 넣으면 Mockito 엄격 모드가 `UnnecessaryStubbingException`으로 실패시킨다. 해당 6개: `handle_throwsIllegalArgumentExceptionOnInvalidStatus`, `handle_throwsParseExceptionOnInvalidWkt`, `handle_throwsIllegalArgumentExceptionOnNullLocation`, `handle_throwsIllegalArgumentExceptionOnNullStreamId`, `handle_throwsIllegalArgumentExceptionOnNullCameraNumber`, `handle_throwsClassCastExceptionOnNonPointGeometry`.

- [ ] **Step 6: 이제 의미가 달라진 기존 테스트 하나를 고친다**

`handle_rethrowsDataIntegrityViolationExceptionOnOtherConstraintViolation`은 원래 FK 위반이 그대로 다시 던져지는 걸 검증했는데, 이제 FK 위반은 `IllegalArgumentException`으로 변환된다. 그래서 이 테스트가 검증하려던 원래 의도("우리가 아는 제약이 아닌 무결성 위반은 감추지 않고 그대로 드러낸다")를 유지하려면, FK도 UNIQUE도 아닌 제약으로 바꿔야 한다.

이 테스트 메서드 전체를 아래로 교체:

```java
    @Test
    @DisplayName("handle() - 우리가 아는 제약(UNIQUE/FK)이 아닌 무결성 위반은 DataIntegrityViolationException을 그대로 던진다")
    void handle_rethrowsDataIntegrityViolationExceptionOnOtherConstraintViolation() {
        // given
        CreateTrailCommand command = new CreateTrailCommand(1L, "CAM-001", "POINT(126.97 37.55)", "북", "active");
        given(streamRepository.existsById(1L)).willReturn(true);
        willThrow(new DataIntegrityViolationException("insert failed",
                new RuntimeException("new row for relation \"trails\" violates check constraint \"trails_status_check\"")))
                .given(trailRepository).save(any(Trail.class));

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                DataIntegrityViolationException.class, () -> handler.handle(command));
    }
```

(`trails_status_check`는 실제 DB에 존재하는 CHECK 제약 이름이다 — `pg_constraint` 조회로 확인했다. 애플리케이션 레벨 status 검증을 통과했는데도 DB CHECK에 걸리는 상황은 정상 경로에선 발생하지 않으므로, "예상 못 한 무결성 위반"의 예시로 적절하다.)

- [ ] **Step 7: 테스트 실행 → 통과 확인**

Run: `cd services/writer && ./mvnw -q -B test -Dtest=TrailCommandHandlerTest`
Expected: PASS (기존 10개 + 새로 추가한 2개 = 12개)

- [ ] **Step 8: writer 전체 테스트 실행 → 회귀 없는지 확인**

Run: `cd services/writer && ./mvnw -B test -Dtest='!WriterApplicationTests'`
Expected: 전부 PASS. 특히 `TrailControllerTest`(writer)가 `TrailCommandHandler`를 `@MockBean`으로 대체하므로 생성자 변경의 영향을 받지 않는지 확인한다.

- [ ] **Step 9: 커밋**

```bash
git add services/writer/src/main/java/com/stream/writer/command/TrailCommandHandler.java services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerTest.java
git commit -m "fix(writer): 존재하지 않는 stream_id로 Trail 생성 시 500 대신 400 반환"
```

---

## Task 2: Docker 실기동으로 실제 400이 나가는지 검증

**Files:**
- 코드 변경 없음. 실행/검증만 한다.

**Interfaces:**
- Consumes: Task 1에서 수정한 `TrailCommandHandler`

**배경**: 이 버그는 애초에 Docker 실기동 검증에서만 발견됐다(유닛 테스트는 mock repository를 쓰므로 진짜 FK 제약을 건드리지 않는다). 따라서 고쳤다는 것도 실제 PostgreSQL에 붙여서 확인해야 한다.

- [ ] **Step 1: 스택을 새로 올린다**

Run:
```bash
cd services/writer && ./mvnw -q -B package -DskipTests
cd ../.. && docker compose --env-file .env -f infra/docker/docker-compose.yml down
docker compose --env-file .env -f infra/docker/docker-compose.yml up -d --build postgres redis kafka writer reader backend
```
Expected: 6개 컨테이너가 올라온다. `docker ps`로 `stream-postgres`/`stream-redis`/`stream-kafka`가 `healthy`, 나머지 3개가 `Up`인지 확인한다.

(참고: 볼륨이 남아있어도 이번 변경은 스키마를 건드리지 않으므로 `-v`가 필요 없다. 이전 검증에서 만든 stream id=1 데이터가 남아있을 수 있는데, 아래 시나리오는 그래도 성립한다.)

- [ ] **Step 2: 앱이 뜰 때까지 기다린다**

Run:
```bash
for i in $(seq 1 60); do code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://localhost:8080/api/streams 2>/dev/null || echo 000); if [ "$code" = "200" ]; then echo "READY after ${i} polls"; break; fi; sleep 3; done
```
Expected: `READY` 출력. (`/actuator/health`는 이 프로젝트에 없으므로 쓰지 않는다 — 404가 난다.)

- [ ] **Step 3: 검증에 쓸 하천을 새로 만들고 그 id를 변수에 담는다**

기존 데이터에 의존하지 않도록 항상 새로 하나 만든다. 한글은 셸 인코딩 문제를 일으킬 수 있으므로 ASCII 이름을 쓰고, 응답에서 id를 뽑아 셸 변수에 담는다:

```bash
printf '%s' '{"name":"fk-test-stream","location":"LINESTRING(126.97 37.55, 126.98 37.56)"}' > /tmp/s.json
RESP=$(curl -s -X POST http://localhost:8080/api/streams -H "Content-Type: application/json" -H "X-Internal-Key: dev-internal-key-change-me" --data-binary @/tmp/s.json)
echo "$RESP"
SID=$(echo "$RESP" | sed -E 's/.*"id":([0-9]+).*/\1/')
echo "SID=$SID"
```
Expected: 201 응답 JSON이 출력되고, `SID=` 뒤에 숫자가 찍힌다(예: `SID=2`). 이 `$SID`를 Step 5에서 그대로 쓴다 — **Step 4~6은 같은 셸 세션에서 이어서 실행해야 변수가 유지된다.**

- [ ] **Step 4: 존재하지 않는 stream_id로 Trail 생성 → 400 확인 (이번 수정의 핵심)**

Run:
```bash
printf '%s' '{"stream_id":999999,"camera_number":"CAM-FK-TEST","location":"POINT(126.97 37.55)","direction":"N","status":"active"}' > /tmp/t.json
curl -s -w "\nHTTP %{http_code}\n" -X POST http://localhost:8080/api/trails -H "Content-Type: application/json" -H "X-Internal-Key: dev-internal-key-change-me" --data-binary @/tmp/t.json
```
Expected: **HTTP 400**, 그리고 응답 본문에 `999999`와 `does not exist`가 포함된다. 수정 전에는 `{"timestamp":...,"status":500,"error":"Internal Server Error"}`가 나왔다.

- [ ] **Step 5: 정상 생성이 여전히 되는지 확인 (회귀 방지)**

Step 3에서 담아둔 `$SID`를 그대로 쓴다(같은 셸 세션에서 이어 실행):
```bash
printf '{"stream_id":%s,"camera_number":"CAM-FK-OK","location":"POINT(126.97 37.55)","direction":"N","status":"active"}' "$SID" > /tmp/t2.json
cat /tmp/t2.json
curl -s -w "\nHTTP %{http_code}\n" -X POST http://localhost:8080/api/trails -H "Content-Type: application/json" -H "X-Internal-Key: dev-internal-key-change-me" --data-binary @/tmp/t2.json
```
Expected: `cat`으로 찍은 JSON의 `stream_id`가 Step 3의 숫자와 같고, **HTTP 201**과 함께 생성된 Trail JSON(`created_at`이 `Z`로 끝남)이 돌아온다.

- [ ] **Step 6: 중복 409가 여전히 동작하는지 확인 (회귀 방지)**

Step 5와 똑같은 요청을 한 번 더 보낸다:
```bash
curl -s -w "\nHTTP %{http_code}\n" -X POST http://localhost:8080/api/trails -H "Content-Type: application/json" -H "X-Internal-Key: dev-internal-key-change-me" --data-binary @/tmp/t2.json
```
Expected: **HTTP 409**, 본문에 `Duplicate trail`. 이번 변경이 UNIQUE 처리를 망가뜨리지 않았다는 확인이다.

- [ ] **Step 7: 스택을 내린다**

Run: `bash infra/scripts/dev-down.sh`
Expected: `✅ stream-walkway dev 환경이 내려갔습니다.`

- [ ] **Step 8: 검증 결과를 계획 문서에 기록한다**

이 문서(`docs/superpowers/plans/2026-08-22-trail-fk-validation.md`) 맨 아래 `## 최종 검증` 섹션에 Step 4/5/6의 실제 HTTP 응답 코드와 본문을 붙여넣는다. 그 다음 커밋:

```bash
git add docs/superpowers/plans/2026-08-22-trail-fk-validation.md
git commit -m "docs: Trail FK 검증 수정의 Docker 실기동 결과 기록"
```

---

## 후속 작업 (이 계획엔 포함 안 함, 참고용)

- **`InvalidTrailGeometryException` 이름이 부정확함**: `apps/backend`에서 writer의 400을 전부 이 예외로 받는데, 이제 geometry와 무관한 경우(필수값 누락, 존재하지 않는 stream_id)가 대부분이다. `InvalidTrailDataException` 같은 이름이 맞다. 이전 계획들의 후속 목록에도 이미 기록돼 있다.
- **`apps/backend`의 Stream 에러 메시지도 같은 문제**: `GlobalExceptionHandler`가 `"Invalid stream geometry"`라고 하는데 이제 geometry 외 오류도 포함한다.
- **`CaptureCommandHandler`의 필수값 검증 누락**: Capture도 `trail_id`/`stream_id` FK가 있는데 검증이 없다. Kafka 컨슈머 경로라 증상은 다르다(400이 아니라 컨슈머 예외).

## 최종 검증

- [x] **writer 전체 테스트 + Docker 실기동 결과**

```bash
cd services/writer && ./mvnw -B test -Dtest='!WriterApplicationTests'
```
Expected: GREEN.
실제 결과: `Tests run: 46, Failures: 0, Errors: 0, Skipped: 0` — GREEN. `BUILD SUCCESS`.

### Docker 실기동 결과 (2026-08-22)

writer 이미지를 재빌드(`docker compose ... up -d --build`)해서 Task 1의 수정이 반영된 상태로 스택을 새로 올린 뒤 검증했다. Step 3에서 새 stream(`id=2`)을 만들어 사용했다.

**Step 4: 존재하지 않는 stream_id(999999)로 Trail 생성**

Request:
```bash
curl -s -w "\nHTTP %{http_code}\n" -X POST http://localhost:8080/api/trails \
  -H "Content-Type: application/json" -H "X-Internal-Key: dev-internal-key-change-me" \
  --data-binary '{"stream_id":999999,"camera_number":"CAM-FK-TEST","location":"POINT(126.97 37.55)","direction":"N","status":"active"}'
```

Response:
```
{"error":"Invalid trail data","message":"Writer rejected the trail data: {\"error\":\"Invalid trail data: stream_id=999999 does not exist\"}"}
HTTP 400
```

수정 전에는 `{"timestamp":...,"status":500,"error":"Internal Server Error"}`였던 자리가 이제 `999999`와 `does not exist`를 포함한 명확한 400으로 바뀌었다. **이번 수정의 핵심 확인 완료.**

**Step 5: 정상 생성 (회귀 방지)**

Request body (`$SID=2`):
```json
{"stream_id":2,"camera_number":"CAM-FK-OK","location":"POINT(126.97 37.55)","direction":"N","status":"active"}
```

Response:
```
{"id":4,"location":"POINT(126.97 37.55)","direction":"N","status":"active","stream_id":2,"camera_number":"CAM-FK-OK","created_at":"2026-08-22T11:59:36.144927261Z"}
HTTP 201
```

정상 생성 여전히 동작. `created_at`이 `Z`로 끝남.

**Step 6: 중복 409 (회귀 방지)**

같은 요청을 한 번 더 전송:

Response:
```
{"error":"Duplicate trail","message":"Writer rejected duplicate trail: {\"error\":\"Duplicate trail\",\"message\":\"stream_id=2, camera_number=CAM-FK-OK already exists\"}"}
HTTP 409
```

UNIQUE(stream_id, camera_number) 처리도 그대로 동작. 세 시나리오 모두 기대한 대로 통과했다.
