# Writer Validation & Thread-Safety Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `services/writer`의 `StreamCommandHandler`/`TrailCommandHandler`에 3가지 실제 버그를 고친다 — (1) `location`이 없으면 500이 나는 문제, (2) `name`/`streamId`/`cameraNumber`처럼 DB에 `NOT NULL`인 필드가 비어도 애플리케이션 레벨 검증 없이 DB까지 내려가 500이 나는 문제, (3) `WKTReader`/`WKTWriter`가 스레드 안전하지 않은데 싱글턴 상태로 공유되는 문제.

**Architecture:** `services/writer`의 Command Handler(`StreamCommandHandler`, `TrailCommandHandler`)에 명시적인 필수값 검증을 추가해서 `IllegalArgumentException`(→400)으로 실패하게 만든다. `StreamController`(writer)의 `@ExceptionHandler`가 지금은 `ParseException`/`ClassCastException`만 잡고 `IllegalArgumentException`을 안 잡고 있어서(Trail 쪽은 이미 잡고 있음), 이것부터 Stream에 맞춰준다. `WKTReader`/`WKTWriter`는 싱글턴 빈의 필드/static 상수로 공유되던 걸 매 호출마다 새로 만들거나(`WKTReader`) `Geometry.toText()`로 대체(`WKTWriter`)해서 상태 공유를 없앤다.

**Tech Stack:** Java 21, Spring Boot 3.5.x, JTS (`org.locationtech.jts`), JUnit 5, Mockito, AssertJ

## Global Constraints

- **이 계획은 `services/writer`와 `packages/shared`(dto)만 건드린다.** reader/backend는 이 문제들의 영향을 안 받는다 — reader는 조회만 하고 애초에 WKT를 파싱하지 않으며(mapper 역할만), backend는 writer/reader를 HTTP로 호출만 할 뿐 자기 WKTReader/WKTWriter가 없다.
- **`created_at`에 타임존(`Z`) 정보가 없는 문제(별도로 확인된 버그)는 이 계획의 범위 밖이다.** `LocalDateTime` → `Instant` 타입 변경은 `packages/shared`/`services/reader`/`services/writer`/`apps/backend`에 걸쳐 16개 파일을 건드리는 별도의 큰 작업이라 사용자 요청으로 이번 계획에서 제외하고 별도 브랜치로 나중에 처리한다.
- **Capture 도메인은 이 계획의 범위 밖이다.** `Capture`도 같은 `createdAt` 패턴을 갖고 있지만, Stream 최종 리뷰 때도 명시적으로 범위 밖으로 남겨뒀던 것과 동일한 이유로 이번에도 건드리지 않는다.
- **Stream과 Trail을 대칭적으로 고친다.** 세 버그 모두 `StreamCommandHandler`/`TrailCommandHandler` 양쪽에 동일한 패턴으로 존재하므로, 매 태스크마다 두 파일을 같이 고쳐서 두 도메인 사이에 새로운 비대칭이 생기지 않게 한다.
- **기존 GREEN 테스트는 각 태스크가 끝날 때마다 계속 GREEN이어야 한다.** 특히 writer의 `StreamControllerTest`/`TrailControllerTest`, `StreamCommandHandlerTest`/`TrailCommandHandlerTest`는 이 계획 시작 시점에 이미 있는 테스트이므로 기존 어서션을 깨지 않아야 한다 (에러 메시지 문자열 값 비교가 아니라 `jsonPath("$.error").exists()`처럼 존재 여부만 확인하는 테스트들이라 메시지 문구를 바꿔도 안전하다는 걸 미리 확인했다).
- `WriterApplicationTests`는 실제 Postgres 연결이 필요해 이 샌드박스에서 계속 실패한다 — 이전 계획들과 동일하게 `-Dtest='!WriterApplicationTests'`로 제외하고 실행한다.
- 패키지 루트는 `com.stream.writer`, `com.stream.shared`.
- 모든 클래스는 `mvnw`로 빌드/테스트한다.

---

## Task 1: `location` 필수값 검증 (Stream + Trail)

**Files:**
- Modify: `services/writer/src/main/java/com/stream/writer/command/StreamCommandHandler.java`
- Modify: `services/writer/src/main/java/com/stream/writer/command/TrailCommandHandler.java`
- Modify: `services/writer/src/main/java/com/stream/writer/controller/StreamController.java`
- Modify: `services/writer/src/test/java/com/stream/writer/command/StreamCommandHandlerTest.java`
- Modify: `services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerTest.java`
- Modify: `services/writer/src/test/java/com/stream/writer/controller/StreamControllerTest.java`

**Interfaces:**
- Produces: `StreamCommandHandler.handle()`/`TrailCommandHandler.handle()`가 `command.location()`이 `null`이거나 공백이면 `IllegalArgumentException`을 던짐(기존 시그니처 `throws ParseException` 그대로 유지 — `IllegalArgumentException`은 unchecked). `StreamController`(writer)가 `IllegalArgumentException`을 400으로 응답(기존에 Trail 쪽엔 이미 있던 처리를 Stream에도 추가).

- [ ] **Step 1: StreamCommandHandlerTest에 실패하는 테스트를 추가한다**

`services/writer/src/test/java/com/stream/writer/command/StreamCommandHandlerTest.java`의 마지막 `@Test` 메서드(`handle_throwsParseExceptionOnInvalidWkt`) 뒤, 클래스 닫는 `}` 앞에 추가:

```java
    @Test
    @DisplayName("handle() - location이 null이면 IllegalArgumentException을 던진다")
    void handle_throwsIllegalArgumentExceptionOnNullLocation() {
        // given
        CreateStreamCommand command = new CreateStreamCommand("한강 산책로", null);

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> handler.handle(command));
    }
```

- [ ] **Step 2: TrailCommandHandlerTest에 실패하는 테스트를 추가한다**

`services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerTest.java`의 마지막 `@Test` 메서드(`handle_rethrowsDataIntegrityViolationExceptionOnOtherConstraintViolation`) 뒤, 클래스 닫는 `}` 앞에 추가:

```java
    @Test
    @DisplayName("handle() - location이 null이면 IllegalArgumentException을 던진다")
    void handle_throwsIllegalArgumentExceptionOnNullLocation() {
        // given
        CreateTrailCommand command = new CreateTrailCommand(1L, "CAM-008", null, "북", "active");

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> handler.handle(command));
    }
```

- [ ] **Step 3: writer StreamControllerTest에 컨트롤러 레벨 실패 테스트를 추가한다**

`services/writer/src/test/java/com/stream/writer/controller/StreamControllerTest.java`의 마지막 `@Test` 메서드(`create_returns400OnWrongGeometryType`) 뒤, 클래스 닫는 `}` 앞에 추가:

```java
    @Test
    @DisplayName("POST /internal/streams - 필수 필드가 비어있으면 400 Bad Request를 반환한다")
    void create_returns400OnMissingRequiredField() throws Exception {
        given(streamCommandHandler.handle(any(CreateStreamCommand.class)))
                .willThrow(new IllegalArgumentException("location is required (WKT)"));

        String requestBody = """
                {
                  "name": "한강 산책로",
                  "location": null
                }
                """;

        mockMvc.perform(post("/internal/streams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
```

- [ ] **Step 4: 테스트 실행 → 실패 확인**

Run:
```bash
cd services/writer && ./mvnw -q -B test -Dtest=StreamCommandHandlerTest,TrailCommandHandlerTest,StreamControllerTest
```
Expected: 새로 추가한 3개 테스트가 FAIL. `handle_throwsIllegalArgumentExceptionOnNullLocation`(Stream/Trail 둘 다)은 `wktReader.read(null)`이 `IllegalArgumentException`이 아니라 `NullPointerException`을 던져서 실패. `create_returns400OnMissingRequiredField`는 컨트롤러가 아직 `IllegalArgumentException`을 처리하지 않아 500으로 응답해서 실패.

- [ ] **Step 5: StreamCommandHandler에 location 검증을 추가한다**

`services/writer/src/main/java/com/stream/writer/command/StreamCommandHandler.java`의 `handle()` 메서드 시작 부분(`Stream stream = new Stream();` 바로 위)에 추가:

```java
    public Stream handle(CreateStreamCommand command) throws ParseException {
        if (command.location() == null || command.location().isBlank()) {
            throw new IllegalArgumentException("location is required (WKT)");
        }

        Stream stream = new Stream();
        stream.setName(command.name());
        stream.setLocation((LineString) wktReader.read(command.location()));
        return streamRepository.save(stream);
    }
```

- [ ] **Step 6: TrailCommandHandler에 location 검증을 추가한다**

`services/writer/src/main/java/com/stream/writer/command/TrailCommandHandler.java` 전체를 아래로 교체:

```java
package com.stream.writer.command;

import com.stream.shared.entity.Trail;
import com.stream.writer.exception.DuplicateTrailException;
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
    private final WKTReader wktReader = new WKTReader(
            new GeometryFactory(new PrecisionModel(), Trail.SRID));

    public TrailCommandHandler(TrailRepository trailRepository) {
        this.trailRepository = trailRepository;
    }

    // ─────────────────────────────────────────
    // CreateTrailCommand 처리 → status 기본값/검증 → WKT 문자열을 Point로 파싱 → PostgreSQL 저장
    // UNIQUE(stream_id, camera_number) 위반은 DuplicateTrailException(409)으로 변환한다.
    // ─────────────────────────────────────────
    public Trail handle(CreateTrailCommand command) throws ParseException {
        if (command.location() == null || command.location().isBlank()) {
            throw new IllegalArgumentException("location is required (WKT)");
        }

        String status = command.status() == null ? "active" : command.status();
        if (!VALID_STATUSES.contains(status)) {
            throw new IllegalArgumentException("Invalid status: " + status + " (must be 'active' or 'inactive')");
        }

        Trail trail = new Trail();
        trail.setStreamId(command.streamId());
        trail.setCameraNumber(command.cameraNumber());
        trail.setLocation((Point) wktReader.read(command.location()));
        trail.setDirection(command.direction());
        trail.setStatus(status);

        try {
            return trailRepository.save(trail);
        } catch (DataIntegrityViolationException e) {
            String message = e.getMostSpecificCause().getMessage();
            if (message != null && message.contains("trails_stream_id_camera_number_key")) {
                throw new DuplicateTrailException(
                        "stream_id=" + command.streamId() + ", camera_number=" + command.cameraNumber() + " already exists");
            }
            throw e;
        }
    }
}
```

(변경점은 `handle()` 맨 앞에 `location` null/blank 체크 3줄이 추가된 것뿐 — 나머지는 현재 파일과 동일. `wktReader`/`WKT_WRITER` 필드 자체를 없애는 스레드 안전성 리팩터링은 Task 3에서 별도로 처리한다.)

- [ ] **Step 7: writer StreamController가 IllegalArgumentException을 400으로 처리하도록 수정한다**

`services/writer/src/main/java/com/stream/writer/controller/StreamController.java` 전체를 아래로 교체:

```java
package com.stream.writer.controller;

import com.stream.shared.dto.StreamView;
import com.stream.writer.command.CreateStreamCommand;
import com.stream.writer.command.StreamCommandHandler;
import org.locationtech.jts.io.ParseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// ─────────────────────────────────────────
// 내부 전용 엔드포인트 — backend가 Stream 등록 요청을 동기 HTTP로 위임하는 대상.
// Capture와 달리 Kafka 이벤트가 아니라 backend의 직접 호출로 생성된다.
// ─────────────────────────────────────────
@RestController
@RequestMapping("/internal/streams")
public class StreamController {

    private final StreamCommandHandler streamCommandHandler;

    public StreamController(StreamCommandHandler streamCommandHandler) {
        this.streamCommandHandler = streamCommandHandler;
    }

    @PostMapping
    public ResponseEntity<StreamView> create(@RequestBody CreateStreamCommand command) throws ParseException {
        var saved = streamCommandHandler.handle(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(StreamView.from(saved));
    }

    @ExceptionHandler({ParseException.class, ClassCastException.class, IllegalArgumentException.class})
    public ResponseEntity<java.util.Map<String, String>> handleInvalidStreamData(Exception e) {
        return ResponseEntity.badRequest().body(java.util.Map.of("error", "Invalid stream data: " + e.getMessage()));
    }
}
```

(변경점: `@ExceptionHandler` 목록에 `IllegalArgumentException.class` 추가, 핸들러 메서드명을 `handleInvalidGeometry` → `handleInvalidStreamData`로, 에러 메시지 접두사를 `"Invalid WKT geometry: "` → `"Invalid stream data: "`로 변경 — 이제 geometry뿐 아니라 필수 필드 누락도 다루므로. 기존 테스트는 메시지 문구가 아니라 `$.error` 존재 여부만 확인해서 안 깨진다.)

- [ ] **Step 8: 테스트 실행 → 통과 확인**

Run:
```bash
cd services/writer && ./mvnw -q -B test -Dtest=StreamCommandHandlerTest,TrailCommandHandlerTest,StreamControllerTest,TrailControllerTest
```
Expected: PASS 전부 (Stream 7개, Trail 7개, writer StreamControllerTest 4개, writer TrailControllerTest 5개)

- [ ] **Step 9: writer 전체 테스트 실행 → 회귀 없는지 확인**

Run: `cd services/writer && ./mvnw -q -B test -Dtest='!WriterApplicationTests'`
Expected: 전부 PASS

- [ ] **Step 10: 커밋**

```bash
git add services/writer/src/main/java/com/stream/writer/command/StreamCommandHandler.java services/writer/src/main/java/com/stream/writer/command/TrailCommandHandler.java services/writer/src/main/java/com/stream/writer/controller/StreamController.java services/writer/src/test/java/com/stream/writer/command/StreamCommandHandlerTest.java services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerTest.java services/writer/src/test/java/com/stream/writer/controller/StreamControllerTest.java
git commit -m "fix(writer): location 누락 시 500 대신 400 반환 - Stream/Trail 양쪽 + StreamController 예외 처리 보강"
```

---

## Task 2: 필수 필드 검증 확장 (Stream: name / Trail: streamId, cameraNumber) + 실제 지오메트리 타입 오류 경로 테스트 보강

**Files:**
- Modify: `services/writer/src/main/java/com/stream/writer/command/StreamCommandHandler.java`
- Modify: `services/writer/src/main/java/com/stream/writer/command/TrailCommandHandler.java`
- Modify: `services/writer/src/test/java/com/stream/writer/command/StreamCommandHandlerTest.java`
- Modify: `services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerTest.java`

**Interfaces:**
- Consumes: Task 1에서 이미 `IllegalArgumentException` → 400으로 매핑된 `StreamController`(writer)의 예외 처리 (`TrailController`는 원래부터 있었음)
- Produces: `name`(Stream)/`streamId`,`cameraNumber`(Trail)이 비어있으면 `IllegalArgumentException`. 실제 WKT 파싱을 거쳐 지오메트리 타입이 안 맞을 때(`LINESTRING`을 Trail에, `POINT`를 Stream에) `ClassCastException`이 실제로 발생하는지 핸들러 레벨에서 검증하는 테스트 추가(지금까지는 컨트롤러 테스트가 `ClassCastException`을 mock으로 흉내만 냈지, 핸들러가 진짜 그 예외를 만드는지는 검증된 적이 없었다).

- [ ] **Step 1: StreamCommandHandlerTest에 실패하는 테스트 2개를 추가한다**

`services/writer/src/test/java/com/stream/writer/command/StreamCommandHandlerTest.java`의 Task 1에서 추가한 `handle_throwsIllegalArgumentExceptionOnNullLocation` 테스트 뒤에 추가:

```java
    @Test
    @DisplayName("handle() - name이 null이면 IllegalArgumentException을 던진다")
    void handle_throwsIllegalArgumentExceptionOnNullName() {
        // given
        CreateStreamCommand command = new CreateStreamCommand(null, "LINESTRING(126.97 37.55, 126.98 37.56)");

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> handler.handle(command));
    }

    @Test
    @DisplayName("handle() - LineString이 아닌 WKT(POINT 등)를 넘기면 실제로 ClassCastException을 던진다")
    void handle_throwsClassCastExceptionOnNonLineStringGeometry() {
        // given
        CreateStreamCommand command = new CreateStreamCommand("점 좌표", "POINT(1 2)");

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                ClassCastException.class, () -> handler.handle(command));
    }
```

- [ ] **Step 2: TrailCommandHandlerTest에 실패하는 테스트 3개를 추가한다**

`services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerTest.java`의 Task 1에서 추가한 `handle_throwsIllegalArgumentExceptionOnNullLocation` 테스트 뒤에 추가:

```java
    @Test
    @DisplayName("handle() - streamId가 null이면 IllegalArgumentException을 던진다")
    void handle_throwsIllegalArgumentExceptionOnNullStreamId() {
        // given
        CreateTrailCommand command = new CreateTrailCommand(null, "CAM-009", "POINT(126.97 37.55)", "북", "active");

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> handler.handle(command));
    }

    @Test
    @DisplayName("handle() - cameraNumber가 null이면 IllegalArgumentException을 던진다")
    void handle_throwsIllegalArgumentExceptionOnNullCameraNumber() {
        // given
        CreateTrailCommand command = new CreateTrailCommand(1L, null, "POINT(126.97 37.55)", "북", "active");

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> handler.handle(command));
    }

    @Test
    @DisplayName("handle() - Point가 아닌 WKT(LINESTRING 등)를 넘기면 실제로 ClassCastException을 던진다")
    void handle_throwsClassCastExceptionOnNonPointGeometry() {
        // given
        CreateTrailCommand command = new CreateTrailCommand(1L, "CAM-010", "LINESTRING(0 0, 1 1)", "북", "active");

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                ClassCastException.class, () -> handler.handle(command));
    }
```

- [ ] **Step 3: 테스트 실행 → 실패 확인**

Run: `cd services/writer && ./mvnw -q -B test -Dtest=StreamCommandHandlerTest,TrailCommandHandlerTest`
Expected: 새로 추가한 5개 중 `handle_throwsIllegalArgumentExceptionOnNullName`/`handle_throwsIllegalArgumentExceptionOnNullStreamId`/`handle_throwsIllegalArgumentExceptionOnNullCameraNumber`는 FAIL(검증 로직이 아직 없어서 `NullPointerException`이 다른 지점에서 발생하거나 DB 저장까지 감). `handle_throwsClassCastExceptionOnNon...Geometry` 2개는 이미 PASS일 수 있음(캐스팅 로직 자체는 이미 있으므로) — 이 2개는 기존 동작을 굳히는 회귀 방지 테스트로 취급한다.

- [ ] **Step 4: StreamCommandHandler에 name 검증을 추가한다**

`services/writer/src/main/java/com/stream/writer/command/StreamCommandHandler.java`의 `handle()` 메서드를 아래로 교체:

```java
    public Stream handle(CreateStreamCommand command) throws ParseException {
        if (command.name() == null || command.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (command.location() == null || command.location().isBlank()) {
            throw new IllegalArgumentException("location is required (WKT)");
        }

        Stream stream = new Stream();
        stream.setName(command.name());
        stream.setLocation((LineString) wktReader.read(command.location()));
        return streamRepository.save(stream);
    }
```

- [ ] **Step 5: TrailCommandHandler에 streamId/cameraNumber 검증을 추가한다**

`services/writer/src/main/java/com/stream/writer/command/TrailCommandHandler.java`의 `handle()` 메서드를 아래로 교체(파일의 다른 부분 — 필드, 생성자, catch 블록 등 — 은 Task 1에서 만든 상태 그대로 안 바뀜):

```java
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

        Trail trail = new Trail();
        trail.setStreamId(command.streamId());
        trail.setCameraNumber(command.cameraNumber());
        trail.setLocation((Point) wktReader.read(command.location()));
        trail.setDirection(command.direction());
        trail.setStatus(status);

        try {
            return trailRepository.save(trail);
        } catch (DataIntegrityViolationException e) {
            String message = e.getMostSpecificCause().getMessage();
            if (message != null && message.contains("trails_stream_id_camera_number_key")) {
                throw new DuplicateTrailException(
                        "stream_id=" + command.streamId() + ", camera_number=" + command.cameraNumber() + " already exists");
            }
            throw e;
        }
    }
```

- [ ] **Step 6: 테스트 실행 → 통과 확인**

Run: `cd services/writer && ./mvnw -q -B test -Dtest=StreamCommandHandlerTest,TrailCommandHandlerTest`
Expected: PASS 전부 (Stream 8개, Trail 10개)

- [ ] **Step 7: writer 전체 테스트 실행 → 회귀 없는지 확인**

Run: `cd services/writer && ./mvnw -q -B test -Dtest='!WriterApplicationTests'`
Expected: 전부 PASS

- [ ] **Step 8: 커밋**

```bash
git add services/writer/src/main/java/com/stream/writer/command/StreamCommandHandler.java services/writer/src/main/java/com/stream/writer/command/TrailCommandHandler.java services/writer/src/test/java/com/stream/writer/command/StreamCommandHandlerTest.java services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerTest.java
git commit -m "fix(writer): 필수 필드(name/streamId/cameraNumber) 누락 검증 추가, 실제 지오메트리 타입 오류 경로 테스트 보강"
```

---

## Task 3: WKTReader/WKTWriter 스레드 안전성 확보

**Files:**
- Modify: `packages/shared/src/main/java/com/stream/shared/dto/StreamView.java`
- Modify: `packages/shared/src/main/java/com/stream/shared/dto/TrailView.java`
- Modify: `services/writer/src/main/java/com/stream/writer/command/StreamCommandHandler.java`
- Modify: `services/writer/src/main/java/com/stream/writer/command/TrailCommandHandler.java`

**Interfaces:**
- 이 태스크는 순수 리팩터링이다 — 외부에서 관찰 가능한 동작(반환값, 예외, HTTP 응답)은 전혀 바뀌지 않는다. 그래서 새로운 실패 테스트를 먼저 쓰는 대신, 기존 테스트 스위트가 리팩터링 전후로 계속 GREEN인지를 회귀 검증으로 삼는다.

**배경**: `WKTWriter`/`WKTReader`는 JTS 문서상 스레드 안전을 보장하지 않는 클래스다. `StreamView`/`TrailView`의 `private static final WKTWriter WKT_WRITER`는 모든 요청이 공유하는 전역 상태이고, `StreamCommandHandler`/`TrailCommandHandler`의 `WKTReader` 인스턴스 필드는 `@Component`(싱글턴 빈)에 붙어있어 역시 모든 요청이 공유한다. 동시 요청이 몰리면 파싱/출력 결과가 깨지거나 예외가 날 수 있는데, 낮은 동시성에서는 거의 재현되지 않는다.

- [ ] **Step 1: 리팩터링 전 baseline으로 관련 테스트를 실행해둔다**

Run:
```bash
cd packages/shared && ./mvnw -q -B test
cd ../../services/writer && ./mvnw -q -B test -Dtest='!WriterApplicationTests'
```
Expected: 전부 PASS (이 결과가 Step 5의 "회귀 없음" 비교 기준이 된다)

- [ ] **Step 2: StreamView에서 static WKTWriter를 제거하고 Geometry.toText()를 쓴다**

`packages/shared/src/main/java/com/stream/shared/dto/StreamView.java` 전체를 아래로 교체:

```java
package com.stream.shared.dto;

import com.stream.shared.entity.Stream;

import java.time.LocalDateTime;

public record StreamView(
        Long id,
        String name,
        String location,
        LocalDateTime createdAt
) {
    public static StreamView from(Stream stream) {
        String wkt = stream.getLocation().toText().replaceFirst("\\s+\\(", "(");
        return new StreamView(
                stream.getId(),
                stream.getName(),
                wkt,
                stream.getCreatedAt()
        );
    }
}
```

(`Geometry.toText()`는 내부에서 매번 새 `WKTWriter`를 만들어서 쓰기 때문에 `new WKTWriter().write(geometry)`와 완전히 동일한 출력을 내면서 공유 상태가 없다. `WKTWriter` import와 static 필드 자체가 사라진다.)

- [ ] **Step 3: TrailView에서도 동일하게 처리한다**

`packages/shared/src/main/java/com/stream/shared/dto/TrailView.java` 전체를 아래로 교체:

```java
package com.stream.shared.dto;

import com.stream.shared.entity.Trail;

import java.time.LocalDateTime;

public record TrailView(
        Long id,
        Long streamId,
        String cameraNumber,
        String location,
        String direction,
        String status,
        LocalDateTime createdAt
) {
    public static TrailView from(Trail trail) {
        String wkt = trail.getLocation().toText().replaceFirst("\\s+\\(", "(");
        return new TrailView(
                trail.getId(),
                trail.getStreamId(),
                trail.getCameraNumber(),
                wkt,
                trail.getDirection(),
                trail.getStatus(),
                trail.getCreatedAt()
        );
    }
}
```

- [ ] **Step 4: StreamCommandHandler의 WKTReader를 인스턴스 필드에서 메서드 지역 변수로 옮긴다**

`services/writer/src/main/java/com/stream/writer/command/StreamCommandHandler.java` 전체를 아래로 교체:

```java
package com.stream.writer.command;

import com.stream.shared.entity.Stream;
import com.stream.writer.repository.StreamRepository;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.springframework.stereotype.Component;

@Component
public class StreamCommandHandler {

    private final StreamRepository streamRepository;

    public StreamCommandHandler(StreamRepository streamRepository) {
        this.streamRepository = streamRepository;
    }

    // ─────────────────────────────────────────
    // CreateStreamCommand 처리 → WKT 문자열을 LineString으로 파싱 → PostgreSQL 저장
    // WKTReader는 스레드 안전하지 않으므로(JTS 문서 명시) 싱글턴 빈의 필드로 공유하지 않고
    // 매 호출마다 새로 만든다 (생성 비용은 미미함).
    // ─────────────────────────────────────────
    public Stream handle(CreateStreamCommand command) throws ParseException {
        if (command.name() == null || command.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (command.location() == null || command.location().isBlank()) {
            throw new IllegalArgumentException("location is required (WKT)");
        }

        WKTReader wktReader = new WKTReader(new GeometryFactory(new PrecisionModel(), Stream.SRID));
        Stream stream = new Stream();
        stream.setName(command.name());
        stream.setLocation((LineString) wktReader.read(command.location()));
        return streamRepository.save(stream);
    }
}
```

- [ ] **Step 5: TrailCommandHandler도 동일하게 처리한다**

`services/writer/src/main/java/com/stream/writer/command/TrailCommandHandler.java` 전체를 아래로 교체:

```java
package com.stream.writer.command;

import com.stream.shared.entity.Trail;
import com.stream.writer.exception.DuplicateTrailException;
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

    public TrailCommandHandler(TrailRepository trailRepository) {
        this.trailRepository = trailRepository;
    }

    // ─────────────────────────────────────────
    // CreateTrailCommand 처리 → 필수값 검증 → status 기본값/검증 →
    // WKT 문자열을 Point로 파싱 → PostgreSQL 저장.
    // UNIQUE(stream_id, camera_number) 위반은 DuplicateTrailException(409)으로 변환한다.
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

        try {
            return trailRepository.save(trail);
        } catch (DataIntegrityViolationException e) {
            String message = e.getMostSpecificCause().getMessage();
            if (message != null && message.contains("trails_stream_id_camera_number_key")) {
                throw new DuplicateTrailException(
                        "stream_id=" + command.streamId() + ", camera_number=" + command.cameraNumber() + " already exists");
            }
            throw e;
        }
    }
}
```

- [ ] **Step 6: shared 재빌드 + 설치**

Run:
```bash
cd packages/shared && ./mvnw -q -B test
cd packages/shared && ./mvnw -q -B install -DskipTests
```
Expected: 전부 PASS, install 성공 (reader/writer/backend가 갱신된 shared jar을 쓸 수 있도록)

- [ ] **Step 7: writer 전체 테스트 실행 → Step 1의 baseline과 비교해서 회귀 없는지 확인**

Run: `cd services/writer && ./mvnw -q -B test -Dtest='!WriterApplicationTests'`
Expected: Step 1과 동일하게 전부 PASS (테스트 개수와 결과가 리팩터링 전후로 같아야 한다 — 동작이 안 바뀌는 순수 리팩터링이므로)

- [ ] **Step 8: reader/backend도 갱신된 shared를 쓰는 만큼 컴파일 확인**

Run:
```bash
cd services/reader && ./mvnw -q -B test -Dtest='!ReaderApplicationTests'
cd ../../apps/backend && ./mvnw -q -B test -Dtest='!CaptureControllerTest'
```
Expected: 전부 PASS (StreamView/TrailView의 공개 API — 필드/`from()` 시그니처 — 가 그대로라 reader/backend 코드는 수정 없이 컴파일된다)

- [ ] **Step 9: 커밋**

```bash
git add packages/shared/src/main/java/com/stream/shared/dto/StreamView.java packages/shared/src/main/java/com/stream/shared/dto/TrailView.java services/writer/src/main/java/com/stream/writer/command/StreamCommandHandler.java services/writer/src/main/java/com/stream/writer/command/TrailCommandHandler.java
git commit -m "fix(shared,writer): WKTReader/WKTWriter 싱글턴 공유 상태 제거 - 스레드 안전성 확보"
```

---

## 후속 작업 (이 계획엔 포함 안 함, 참고용)

- **`created_at`에 타임존 정보 없는 문제**: `StreamView`/`TrailView`의 `createdAt`을 `LocalDateTime` → `Instant`로 바꾸는 별도 계획 필요. `packages/shared`(엔티티+DTO), `services/reader`/`services/writer`(테스트 픽스처), `apps/backend`(ServiceImpl의 포맷팅 로직 + 테스트) 총 16개 파일에 걸친 큰 작업.
- **Capture 도메인의 동일 패턴**: `Capture` 엔티티도 `createdAt`을 갖고 있어 위와 같은 문제가 있을 수 있으나 범위 밖으로 남겨둠 (Stream 최종 리뷰 때도 동일하게 유보).
- **writer의 `@ExceptionHandler`를 `@RestControllerAdvice`로 승격**: 지금은 `StreamController`/`TrailController` 각자 컨트롤러 로컬 핸들러를 갖고 있어서 로직이 거의 동일하게 두 곳에 있다. 통합할 수 있지만 이번 계획의 버그 수정과는 결이 달라 범위 밖으로 둠.
- **writer 빈 응답을 502로 매핑**: `apps/backend`의 `TrailServiceImpl`/`StreamServiceImpl`이 writer로부터 빈 응답을 받으면 지금은 400으로 처리하는데, 서버 쪽 문제이니 502가 더 정확하다는 지적이 있었음. 구조 개선 성격이라 범위 밖으로 둠.

## 최종 검증

- [ ] **관련 모듈 전체 테스트 재실행**

```bash
cd packages/shared && ./mvnw -q -B test
cd ../../services/reader && ./mvnw -q -B test -Dtest='!ReaderApplicationTests'
cd ../writer && ./mvnw -q -B test -Dtest='!WriterApplicationTests'
cd ../../apps/backend && ./mvnw -q -B test -Dtest='!CaptureControllerTest'
```

Expected: 4개 모듈 전부 GREEN.
