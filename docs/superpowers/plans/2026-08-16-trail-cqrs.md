# Trail CQRS (DB 계층) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `Trail`(카메라 스테이션) 도메인을 Stream과 동일한 CQRS 구조로 `packages/shared`(엔티티+DTO), `services/writer`(쓰기), `services/reader`(읽기)에 추가한다. 이 계획은 DB에 직접 접근하는 세 모듈만 다룬다 — `apps/backend` 게이트웨이 연동은 별도 계획/브랜치에서 나중에 진행한다.

**Architecture:** `packages/shared`에 `Trail` JPA 엔티티(`org.locationtech.jts.geom.Point` 필드, `stream_id` FK를 원시 `Long` 컬럼으로 보관)와 `TrailView` 읽기 DTO(위치를 WKT 문자열로 변환)를 추가한다. Writer는 `POST /internal/trails`로 동기 생성 요청을 받아 저장한다(Kafka 미사용 — Stream과 동일한 이유). `status`는 애플리케이션 레벨에서 검증(누락 시 `"active"` 기본값, `active`/`inactive` 외 값은 400)하고, `(stream_id, camera_number)` UNIQUE 제약 위반은 409로 변환한다. Reader는 `GET /trails`(선택적 `stream_id` 쿼리 필터), `GET /trails/{id}`로 조회를 제공한다.

**Tech Stack:** Java 21, Spring Boot 3.5.x, Spring Data JPA, Hibernate Spatial, JTS (`org.locationtech.jts`), JUnit 5, Mockito, AssertJ

## Global Constraints

- **`apps/backend`는 이 계획의 범위 밖이다.** `TrailServiceImpl`/`TrailController` 구현, backend 전용 예외(`TrailNotFoundException`/`InvalidTrailGeometryException`/`DuplicateTrailException`), `GlobalExceptionHandler` 확장은 별도 계획/브랜치에서 진행한다. `apps/backend`는 DB에 직접 접근하지 않고 reader/writer를 HTTP로 호출만 하므로 이 계획과 독립적이다.
- **Kafka를 쓰지 않는다.** Trail 생성은 (미래의) backend → writer `POST /internal/trails` 동기 HTTP 호출로 처리한다. Capture처럼 비동기 이벤트로 만들지 않는다.
- **GIS는 Point로 매핑한다.** DB 실제 타입은 `GEOMETRY(POINT,4326)`이므로, 엔티티 필드는 `org.locationtech.jts.geom.Point`를 쓴다 (Stream의 `LineString`과 다름).
- **WKT 문자열 포맷은 공백 없이 `POINT(...)`이다.** JTS의 기본 `WKTWriter`는 `POINT (...)`(키워드 뒤 공백)를 출력하므로, `TrailView`에서 그 공백을 제거해 `"POINT(126.97 37.55)"` 포맷을 만든다 (Stream의 `StreamView`와 동일한 `replaceFirst("\\s+\\(", "(")` 처리).
- **`stream_id`는 JPA 연관관계로 매핑하지 않는다.** `Trail` 엔티티의 `streamId` 필드는 원시 `Long` 컬럼이다 (`@ManyToOne` 안 씀). reader/writer가 조인 없이 단순 CRUD만 하므로 Stream의 설계 원칙과 일관성을 유지하고, FK 무결성은 DB의 `REFERENCES streams(id) ON DELETE CASCADE`가 보장한다.
- **`status` 필드는 애플리케이션 레벨에서 검증한다.** `CreateTrailCommand.status()`가 `null`이면 writer가 `"active"`로 채운다. `"active"`/`"inactive"`가 아닌 값이면 `IllegalArgumentException`을 던져 400으로 응답한다 (DB의 `CHECK IN ('active','inactive')` 제약에만 의존하지 않는다).
- **`(stream_id, camera_number)` 중복은 409로 응답한다.** DB의 `UNIQUE(stream_id, camera_number)` 제약을 위반하면 `DataIntegrityViolationException`이 발생하는데, 이를 writer 모듈 내부의 `DuplicateTrailException`으로 변환해서 `409 Conflict` + 명확한 에러 메시지로 응답한다 (500을 그대로 노출하지 않는다).
- **reader의 `GET /trails` 쿼리 파라미터명은 `stream_id`(스네이크 케이스)다.** `@RequestParam(value = "stream_id", required = false) Long streamId`처럼 `value`를 명시적으로 지정해야 한다 — `value` 없이 `@RequestParam`만 쓰면 Spring이 자바 파라미터명(`streamId`)으로 바인딩해서 `?stream_id=1` 쿼리가 조용히 무시된다 (이 계획의 설계 문서 자체 리뷰에서 발견한 문제, `apps/backend`의 기존 RED 스텁 컨트롤러에도 동일한 문제가 있음 — 이 계획에서는 reader 쪽만 다루고, backend 쪽 수정은 후속 계획에서 처리한다).
- **Repository 레벨의 실제 DB 통합 테스트(H2 등)는 이 계획의 범위 밖이다.** Stream 구현을 확인한 결과 `StreamRepository`에 대한 `@DataJpaTest`도 작성되지 않았다 — 모든 테스트는 순수 단위 테스트(Mockito) 또는 `@WebMvcTest`(리포지토리를 `@MockBean`으로 목업)다. `services/{reader,writer}/src/test/resources/schema.sql`(H2, `captures` 테이블만 정의)은 수정하지 않는다.
- 패키지 루트는 `com.stream.reader`, `com.stream.writer`, 공유 모듈은 `com.stream.shared`를 쓴다.
- Java 21, `jakarta.persistence.*` 어노테이션을 쓴다.
- 모든 신규 클래스는 `mvnw`(각 서비스에 이미 있는 Maven Wrapper)로 빌드/테스트한다.
- `packages/shared/pom.xml`은 이미 `hibernate-spatial`/`jts-core` 의존성을 갖고 있다(Stream 작업에서 추가됨) — 이 계획에서 추가 의존성 변경은 필요 없다.
- 기존 GREEN 테스트(shared, reader, writer의 `HealthCheckTest`, Stream/Capture 관련 테스트 전부)는 각 태스크가 끝날 때마다 계속 GREEN이어야 한다.

---

## Task 1: `packages/shared` — Trail 엔티티

**Files:**
- Create: `packages/shared/src/main/java/com/stream/shared/entity/Trail.java`
- Create: `packages/shared/src/test/java/com/stream/shared/entity/TrailTest.java`

**Interfaces:**
- Produces: `com.stream.shared.entity.Trail` — `@Entity @Table(name = "trails")`, 필드 `id: Long, streamId: Long, cameraNumber: String, location: org.locationtech.jts.geom.Point, direction: String, status: String, createdAt: LocalDateTime`, `@PrePersist onCreate()`로 `createdAt` 자동 설정. `getId()`, `getStreamId()/setStreamId()`, `getCameraNumber()/setCameraNumber()`, `getLocation()/setLocation()`, `getDirection()/setDirection()`, `getStatus()/setStatus()`, `getCreatedAt()` 제공 (id/createdAt은 getter만). `public static final int SRID = 4326;` 상수 제공.

- [ ] **Step 1: Trail 엔티티 테스트를 먼저 작성한다**

`packages/shared/src/test/java/com/stream/shared/entity/TrailTest.java`:

```java
package com.stream.shared.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Shared - Trail Entity 테스트")
class TrailTest {

    private final WKTReader wktReader = new WKTReader();

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("setStreamId() / getStreamId() - streamId를 저장하고 반환한다")
    void streamId_setAndGet() {
        Trail trail = new Trail();
        trail.setStreamId(1L);
        assertThat(trail.getStreamId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("setCameraNumber() / getCameraNumber() - cameraNumber를 저장하고 반환한다")
    void cameraNumber_setAndGet() {
        Trail trail = new Trail();
        trail.setCameraNumber("CAM-001");
        assertThat(trail.getCameraNumber()).isEqualTo("CAM-001");
    }

    @Test
    @DisplayName("setLocation() / getLocation() - Point를 저장하고 반환한다")
    void location_setAndGet() throws ParseException {
        Trail trail = new Trail();
        Point point = (Point) wktReader.read("POINT(126.97 37.55)");

        trail.setLocation(point);

        assertThat(trail.getLocation().toText()).isEqualTo("POINT (126.97 37.55)");
    }

    @Test
    @DisplayName("setDirection() / getDirection() - direction을 저장하고 반환한다")
    void direction_setAndGet() {
        Trail trail = new Trail();
        trail.setDirection("북");
        assertThat(trail.getDirection()).isEqualTo("북");
    }

    @Test
    @DisplayName("setStatus() / getStatus() - status를 저장하고 반환한다")
    void status_setAndGet() {
        Trail trail = new Trail();
        trail.setStatus("active");
        assertThat(trail.getStatus()).isEqualTo("active");
    }

    @Test
    @DisplayName("getId() - 저장 전에는 id가 null이다")
    void getId_isNullBeforePersist() {
        assertThat(new Trail().getId()).isNull();
    }

    @Test
    @DisplayName("getCreatedAt() - onCreate() 호출 전에는 createdAt이 null이다")
    void getCreatedAt_isNullBeforeOnCreate() {
        assertThat(new Trail().getCreatedAt()).isNull();
    }

    @Test
    @DisplayName("@PrePersist - onCreate() 호출 시 createdAt이 현재 시각으로 설정된다")
    void onCreate_setsCreatedAt() throws Exception {
        Trail trail = new Trail();
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        Method onCreateMethod = Trail.class.getDeclaredMethod("onCreate");
        onCreateMethod.setAccessible(true);
        onCreateMethod.invoke(trail);

        LocalDateTime after = LocalDateTime.now().plusSeconds(1);
        assertThat(trail.getCreatedAt()).isNotNull();
        assertThat(trail.getCreatedAt()).isAfterOrEqualTo(before);
        assertThat(trail.getCreatedAt()).isBeforeOrEqualTo(after);
    }

    @Test
    @DisplayName("기본 생성자로 만들면 모든 필드가 null이다")
    void defaultConstructor_allFieldsAreNull() {
        Trail empty = new Trail();
        assertThat(empty.getId()).isNull();
        assertThat(empty.getStreamId()).isNull();
        assertThat(empty.getCameraNumber()).isNull();
        assertThat(empty.getLocation()).isNull();
        assertThat(empty.getDirection()).isNull();
        assertThat(empty.getStatus()).isNull();
        assertThat(empty.getCreatedAt()).isNull();
    }
}
```

- [ ] **Step 2: 테스트 실행 → 컴파일 실패 확인**

Run: `cd packages/shared && ./mvnw -q -B test -Dtest=TrailTest`
Expected: FAIL — `Trail` 클래스가 없어서 컴파일 에러

- [ ] **Step 3: Trail 엔티티를 작성한다**

`packages/shared/src/main/java/com/stream/shared/entity/Trail.java`:

```java
package com.stream.shared.entity;

import jakarta.persistence.*;
import org.locationtech.jts.geom.Point;

import java.time.LocalDateTime;

@Entity
@Table(name = "trails")
public class Trail {

    public static final int SRID = 4326;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stream_id", nullable = false)
    private Long streamId;

    @Column(name = "camera_number", nullable = false)
    private String cameraNumber;

    @Column(nullable = false, columnDefinition = "geometry(Point,4326)")
    private Point location;

    private String direction;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }

    public Long getStreamId() { return streamId; }
    public void setStreamId(Long streamId) { this.streamId = streamId; }

    public String getCameraNumber() { return cameraNumber; }
    public void setCameraNumber(String cameraNumber) { this.cameraNumber = cameraNumber; }

    public Point getLocation() { return location; }
    public void setLocation(Point location) { this.location = location; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `cd packages/shared && ./mvnw -q -B test -Dtest=TrailTest`
Expected: PASS (9개 테스트 전부 통과)

- [ ] **Step 5: shared 전체 테스트 실행 → 회귀 없는지 확인 후 로컬 저장소에 재설치**

Run:
```bash
cd packages/shared && ./mvnw -q -B test
cd packages/shared && ./mvnw -q -B install -DskipTests
```
Expected: 기존 Stream/Capture 테스트 포함 전부 PASS, install 성공 (reader/writer가 갱신된 shared jar을 쓸 수 있도록)

- [ ] **Step 6: 커밋**

```bash
git add packages/shared/src/main/java/com/stream/shared/entity/Trail.java packages/shared/src/test/java/com/stream/shared/entity/TrailTest.java
git commit -m "feat(shared): Trail 엔티티 추가 (JTS Point 매핑)"
```

---

## Task 2: `packages/shared` — TrailView 읽기 DTO

**Files:**
- Create: `packages/shared/src/main/java/com/stream/shared/dto/TrailView.java`
- Create: `packages/shared/src/test/java/com/stream/shared/dto/TrailViewTest.java`

**Interfaces:**
- Consumes: `com.stream.shared.entity.Trail` (Task 1)
- Produces: `com.stream.shared.dto.TrailView` — record `(Long id, Long streamId, String cameraNumber, String location, String direction, String status, LocalDateTime createdAt)`, `location`은 공백 없는 WKT 문자열(`"POINT(...)"`). 정적 팩토리 `TrailView.from(Trail trail)` 제공.

- [ ] **Step 1: TrailView 테스트를 먼저 작성한다**

`packages/shared/src/test/java/com/stream/shared/dto/TrailViewTest.java`:

```java
package com.stream.shared.dto;

import com.stream.shared.entity.Trail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Shared - TrailView 테스트")
class TrailViewTest {

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("from() - Trail 엔티티를 TrailView로 변환한다 (location은 공백 없는 WKT 문자열)")
    void from_mapsAllFieldsFromEntity() throws Exception {
        Trail trail = new Trail();
        setField(trail, "id", 1L);
        trail.setStreamId(1L);
        trail.setCameraNumber("CAM-001");
        trail.setLocation((org.locationtech.jts.geom.Point)
                new WKTReader().read("POINT(126.97 37.55)"));
        trail.setDirection("북");
        trail.setStatus("active");
        setField(trail, "createdAt", LocalDateTime.of(2024, 1, 1, 0, 0, 0));

        TrailView view = TrailView.from(trail);

        assertThat(view.id()).isEqualTo(1L);
        assertThat(view.streamId()).isEqualTo(1L);
        assertThat(view.cameraNumber()).isEqualTo("CAM-001");
        assertThat(view.location()).isEqualTo("POINT(126.97 37.55)");
        assertThat(view.direction()).isEqualTo("북");
        assertThat(view.status()).isEqualTo("active");
        assertThat(view.createdAt()).isEqualTo(LocalDateTime.of(2024, 1, 1, 0, 0, 0));
    }

    @Test
    @DisplayName("from() - WKTWriter가 기본으로 넣는 공백('POINT (')이 제거된다")
    void from_stripsSpaceAfterGeometryKeyword() throws ParseException {
        Trail trail = new Trail();
        trail.setStreamId(1L);
        trail.setCameraNumber("CAM-999");
        trail.setLocation((org.locationtech.jts.geom.Point)
                new WKTReader().read("POINT(0 0)"));
        trail.setStatus("active");

        TrailView view = TrailView.from(trail);

        assertThat(view.location()).doesNotContain("POINT (");
        assertThat(view.location()).startsWith("POINT(");
    }
}
```

- [ ] **Step 2: 테스트 실행 → 컴파일 실패 확인**

Run: `cd packages/shared && ./mvnw -q -B test -Dtest=TrailViewTest`
Expected: FAIL — `TrailView` 클래스가 없어서 컴파일 에러

- [ ] **Step 3: TrailView DTO를 작성한다**

`packages/shared/src/main/java/com/stream/shared/dto/TrailView.java`:

```java
package com.stream.shared.dto;

import com.stream.shared.entity.Trail;
import org.locationtech.jts.io.WKTWriter;

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
    private static final WKTWriter WKT_WRITER = new WKTWriter();

    public static TrailView from(Trail trail) {
        String wkt = WKT_WRITER.write(trail.getLocation()).replaceFirst("\\s+\\(", "(");
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

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `cd packages/shared && ./mvnw -q -B test -Dtest=TrailViewTest`
Expected: PASS (2개 테스트 전부 통과)

- [ ] **Step 5: shared 전체 테스트 실행 → 회귀 없는지 확인 후 로컬 저장소에 재설치**

Run:
```bash
cd packages/shared && ./mvnw -q -B test
cd packages/shared && ./mvnw -q -B install -DskipTests
```
Expected: 전부 PASS, install 성공

- [ ] **Step 6: 커밋**

```bash
git add packages/shared/src/main/java/com/stream/shared/dto/TrailView.java packages/shared/src/test/java/com/stream/shared/dto/TrailViewTest.java
git commit -m "feat(shared): TrailView 읽기 DTO 추가 (WKT 변환, 공백 제거)"
```

---

## Task 3: Writer — Trail 쓰기 (Command/Handler/내부 REST 엔드포인트)

**Files:**
- Create: `services/writer/src/main/java/com/stream/writer/repository/TrailRepository.java`
- Create: `services/writer/src/main/java/com/stream/writer/command/CreateTrailCommand.java`
- Create: `services/writer/src/main/java/com/stream/writer/exception/DuplicateTrailException.java`
- Create: `services/writer/src/main/java/com/stream/writer/command/TrailCommandHandler.java`
- Create: `services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerTest.java`
- Create: `services/writer/src/main/java/com/stream/writer/controller/TrailController.java`
- Create: `services/writer/src/test/java/com/stream/writer/controller/TrailControllerTest.java`

**Interfaces:**
- Consumes: `com.stream.shared.entity.Trail` (Task 1), `com.stream.shared.dto.TrailView` (Task 2)
- Produces: `POST /internal/trails` — 요청 바디 `{"streamId": Long, "cameraNumber": String, "location": String, "direction": String, "status": String|null}` (camelCase, 내부 전용 계약 — Stream의 `CreateStreamCommand`와 동일하게 외부 API의 snake_case 규칙을 따르지 않는다). 응답: 201 + `TrailView`(camelCase JSON), 400(`{"error": "Invalid trail data: ..."}`, WKT 파싱 실패/geometry 타입 오류/status 값 오류), 409(`{"error": "Duplicate trail", "message": "..."}`, `(stream_id, camera_number)` 중복).

- [ ] **Step 1: TrailRepository를 작성한다 (테스트 없음 — Stream의 StreamRepository와 동일하게 순수 위임 인터페이스)**

`services/writer/src/main/java/com/stream/writer/repository/TrailRepository.java`:

```java
package com.stream.writer.repository;

import com.stream.shared.entity.Trail;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrailRepository extends JpaRepository<Trail, Long> {
}
```

- [ ] **Step 2: CreateTrailCommand record를 작성한다 (테스트 없음 — Stream의 CreateStreamCommand와 동일하게 단순 데이터 캐리어)**

`services/writer/src/main/java/com/stream/writer/command/CreateTrailCommand.java`:

```java
package com.stream.writer.command;

public record CreateTrailCommand(
        Long streamId,
        String cameraNumber,
        String location,
        String direction,
        String status
) {}
```

- [ ] **Step 3: DuplicateTrailException을 작성한다 (테스트 없음 — 단순 예외 클래스)**

`services/writer/src/main/java/com/stream/writer/exception/DuplicateTrailException.java`:

```java
package com.stream.writer.exception;

public class DuplicateTrailException extends RuntimeException {

    public DuplicateTrailException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: TrailCommandHandler 테스트를 먼저 작성한다**

`services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerTest.java`:

```java
package com.stream.writer.command;

import com.stream.shared.entity.Trail;
import com.stream.writer.exception.DuplicateTrailException;
import com.stream.writer.repository.TrailRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.io.ParseException;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("Writer - TrailCommandHandler 테스트")
class TrailCommandHandlerTest {

    @Mock
    private TrailRepository trailRepository;

    @InjectMocks
    private TrailCommandHandler handler;

    @Test
    @DisplayName("handle() - Command를 처리하면 WKT를 파싱해서 PostgreSQL에 Trail을 저장한다")
    void handle_savesTrailWithParsedGeometry() throws ParseException {
        // given
        CreateTrailCommand command = new CreateTrailCommand(1L, "CAM-001", "POINT(126.97 37.55)", "북", "active");
        Trail savedTrail = new Trail();
        savedTrail.setCameraNumber("CAM-001");
        given(trailRepository.save(any(Trail.class))).willReturn(savedTrail);

        // when
        Trail result = handler.handle(command);

        // then
        ArgumentCaptor<Trail> captor = ArgumentCaptor.forClass(Trail.class);
        verify(trailRepository).save(captor.capture());

        Trail toSave = captor.getValue();
        assertThat(toSave.getStreamId()).isEqualTo(1L);
        assertThat(toSave.getCameraNumber()).isEqualTo("CAM-001");
        assertThat(toSave.getLocation().toText()).isEqualTo("POINT (126.97 37.55)");
        assertThat(toSave.getLocation().getSRID()).isEqualTo(4326);
        assertThat(toSave.getDirection()).isEqualTo("북");
        assertThat(toSave.getStatus()).isEqualTo("active");
        assertThat(result).isEqualTo(savedTrail);
    }

    @Test
    @DisplayName("handle() - status가 null이면 'active'로 기본값을 채운다")
    void handle_defaultsNullStatusToActive() throws ParseException {
        // given
        CreateTrailCommand command = new CreateTrailCommand(1L, "CAM-002", "POINT(126.97 37.55)", "북", null);
        given(trailRepository.save(any(Trail.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        Trail result = handler.handle(command);

        // then
        assertThat(result.getStatus()).isEqualTo("active");
    }

    @Test
    @DisplayName("handle() - status가 active/inactive가 아니면 IllegalArgumentException을 던진다")
    void handle_throwsIllegalArgumentExceptionOnInvalidStatus() {
        // given
        CreateTrailCommand command = new CreateTrailCommand(1L, "CAM-003", "POINT(126.97 37.55)", "북", "unknown");

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> handler.handle(command));
    }

    @Test
    @DisplayName("handle() - 잘못된 WKT 문자열이면 ParseException을 던진다")
    void handle_throwsParseExceptionOnInvalidWkt() {
        // given
        CreateTrailCommand command = new CreateTrailCommand(1L, "CAM-004", "NOT-A-VALID-WKT", "북", "active");

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(ParseException.class, () -> handler.handle(command));
    }

    @Test
    @DisplayName("handle() - 저장 시 UNIQUE 제약 위반이면 DuplicateTrailException을 던진다")
    void handle_throwsDuplicateTrailExceptionOnConstraintViolation() {
        // given
        CreateTrailCommand command = new CreateTrailCommand(1L, "CAM-001", "POINT(126.97 37.55)", "북", "active");
        willThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"))
                .given(trailRepository).save(any(Trail.class));

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                DuplicateTrailException.class, () -> handler.handle(command));
    }
}
```

- [ ] **Step 5: 테스트 실행 → 컴파일 실패 확인**

Run: `cd services/writer && ./mvnw -q -B test -Dtest=TrailCommandHandlerTest`
Expected: FAIL — `TrailCommandHandler` 클래스가 없어서 컴파일 에러

- [ ] **Step 6: TrailCommandHandler를 작성한다**

`services/writer/src/main/java/com/stream/writer/command/TrailCommandHandler.java`:

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
            throw new DuplicateTrailException(
                    "stream_id=" + command.streamId() + ", camera_number=" + command.cameraNumber() + " already exists");
        }
    }
}
```

- [ ] **Step 7: 테스트 실행 → 통과 확인**

Run: `cd services/writer && ./mvnw -q -B test -Dtest=TrailCommandHandlerTest`
Expected: PASS (5개 테스트 전부 통과)

- [ ] **Step 8: writer TrailController 테스트를 먼저 작성한다**

`services/writer/src/test/java/com/stream/writer/controller/TrailControllerTest.java`:

```java
package com.stream.writer.controller;

import com.stream.shared.entity.Trail;
import com.stream.writer.command.CreateTrailCommand;
import com.stream.writer.command.TrailCommandHandler;
import com.stream.writer.exception.DuplicateTrailException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TrailController.class)
@DisplayName("Writer - TrailController(내부 전용) 테스트")
class TrailControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TrailCommandHandler trailCommandHandler;

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("POST /internal/trails - Trail을 생성하면 201 Created와 TrailView를 반환한다")
    void create_returns201WithTrailView() throws Exception {
        // given
        Trail saved = new Trail();
        setField(saved, "id", 1L);
        saved.setStreamId(1L);
        saved.setCameraNumber("CAM-001");
        saved.setLocation((org.locationtech.jts.geom.Point)
                new org.locationtech.jts.io.WKTReader().read("POINT(126.97 37.55)"));
        saved.setDirection("북");
        saved.setStatus("active");
        setField(saved, "createdAt", LocalDateTime.of(2024, 1, 1, 0, 0, 0));

        given(trailCommandHandler.handle(any(CreateTrailCommand.class))).willReturn(saved);

        String requestBody = """
                {
                  "streamId": 1,
                  "cameraNumber": "CAM-001",
                  "location": "POINT(126.97 37.55)",
                  "direction": "북",
                  "status": "active"
                }
                """;

        // when & then
        mockMvc.perform(post("/internal/trails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.streamId").value(1))
                .andExpect(jsonPath("$.cameraNumber").value("CAM-001"))
                .andExpect(jsonPath("$.location").value("POINT(126.97 37.55)"))
                .andExpect(jsonPath("$.direction").value("북"))
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    @DisplayName("POST /internal/trails - 파싱 불가능한 WKT면 400 Bad Request를 반환한다")
    void create_returns400OnUnparseableWkt() throws Exception {
        given(trailCommandHandler.handle(any(CreateTrailCommand.class)))
                .willThrow(new org.locationtech.jts.io.ParseException("Unknown geometry type: NOT-A-WKT"));

        String requestBody = """
                {
                  "streamId": 1,
                  "cameraNumber": "CAM-005",
                  "location": "NOT-A-WKT",
                  "direction": "북",
                  "status": "active"
                }
                """;

        mockMvc.perform(post("/internal/trails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /internal/trails - Point가 아닌 WKT(LINESTRING 등)면 400 Bad Request를 반환한다")
    void create_returns400OnWrongGeometryType() throws Exception {
        given(trailCommandHandler.handle(any(CreateTrailCommand.class)))
                .willThrow(new ClassCastException("class org.locationtech.jts.geom.LineString cannot be cast to class org.locationtech.jts.geom.Point"));

        String requestBody = """
                {
                  "streamId": 1,
                  "cameraNumber": "CAM-006",
                  "location": "LINESTRING(0 0, 1 1)",
                  "direction": "북",
                  "status": "active"
                }
                """;

        mockMvc.perform(post("/internal/trails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /internal/trails - status가 active/inactive가 아니면 400 Bad Request를 반환한다")
    void create_returns400OnInvalidStatus() throws Exception {
        given(trailCommandHandler.handle(any(CreateTrailCommand.class)))
                .willThrow(new IllegalArgumentException("Invalid status: unknown (must be 'active' or 'inactive')"));

        String requestBody = """
                {
                  "streamId": 1,
                  "cameraNumber": "CAM-007",
                  "location": "POINT(126.97 37.55)",
                  "direction": "북",
                  "status": "unknown"
                }
                """;

        mockMvc.perform(post("/internal/trails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /internal/trails - (stream_id, camera_number) 중복이면 409 Conflict를 반환한다")
    void create_returns409OnDuplicateTrail() throws Exception {
        given(trailCommandHandler.handle(any(CreateTrailCommand.class)))
                .willThrow(new DuplicateTrailException("stream_id=1, camera_number=CAM-001 already exists"));

        String requestBody = """
                {
                  "streamId": 1,
                  "cameraNumber": "CAM-001",
                  "location": "POINT(126.97 37.55)",
                  "direction": "북",
                  "status": "active"
                }
                """;

        mockMvc.perform(post("/internal/trails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Duplicate trail"))
                .andExpect(jsonPath("$.message").value("stream_id=1, camera_number=CAM-001 already exists"));
    }
}
```

- [ ] **Step 9: 테스트 실행 → 컴파일 실패 확인**

Run: `cd services/writer && ./mvnw -q -B test -Dtest=TrailControllerTest`
Expected: FAIL — `TrailController` 클래스가 없어서 컴파일 에러

- [ ] **Step 10: writer TrailController를 작성한다**

`services/writer/src/main/java/com/stream/writer/controller/TrailController.java`:

```java
package com.stream.writer.controller;

import com.stream.shared.dto.TrailView;
import com.stream.writer.command.CreateTrailCommand;
import com.stream.writer.command.TrailCommandHandler;
import com.stream.writer.exception.DuplicateTrailException;
import org.locationtech.jts.io.ParseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// ─────────────────────────────────────────
// 내부 전용 엔드포인트 — backend가 Trail 등록 요청을 동기 HTTP로 위임하는 대상.
// Stream과 동일하게 Kafka 이벤트가 아니라 직접 호출로 생성된다.
// ─────────────────────────────────────────
@RestController
@RequestMapping("/internal/trails")
public class TrailController {

    private final TrailCommandHandler trailCommandHandler;

    public TrailController(TrailCommandHandler trailCommandHandler) {
        this.trailCommandHandler = trailCommandHandler;
    }

    @PostMapping
    public ResponseEntity<TrailView> create(@RequestBody CreateTrailCommand command) throws ParseException {
        var saved = trailCommandHandler.handle(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(TrailView.from(saved));
    }

    @ExceptionHandler({ParseException.class, ClassCastException.class, IllegalArgumentException.class})
    public ResponseEntity<java.util.Map<String, String>> handleInvalidTrailData(Exception e) {
        return ResponseEntity.badRequest().body(java.util.Map.of("error", "Invalid trail data: " + e.getMessage()));
    }

    @ExceptionHandler(DuplicateTrailException.class)
    public ResponseEntity<java.util.Map<String, String>> handleDuplicateTrail(DuplicateTrailException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(java.util.Map.of("error", "Duplicate trail", "message", e.getMessage()));
    }
}
```

- [ ] **Step 11: 테스트 실행 → 통과 확인**

Run: `cd services/writer && ./mvnw -q -B test -Dtest=TrailControllerTest`
Expected: PASS (5개 테스트 전부 통과)

- [ ] **Step 12: writer 전체 테스트 실행 → 회귀 없는지 확인**

Run: `cd services/writer && ./mvnw -q -B test -Dtest='!WriterApplicationTests'`
Expected: 기존 Stream/Capture 관련 테스트 포함 전부 PASS (`WriterApplicationTests`는 실제 Postgres 연결이 필요해 이 샌드박스에서 제외 — Stream 계획 때와 동일한 이유)

- [ ] **Step 13: 커밋**

```bash
git add services/writer/src/main/java/com/stream/writer/repository/TrailRepository.java services/writer/src/main/java/com/stream/writer/command/CreateTrailCommand.java services/writer/src/main/java/com/stream/writer/exception/DuplicateTrailException.java services/writer/src/main/java/com/stream/writer/command/TrailCommandHandler.java services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerTest.java services/writer/src/main/java/com/stream/writer/controller/TrailController.java services/writer/src/test/java/com/stream/writer/controller/TrailControllerTest.java
git commit -m "feat(writer): Trail 쓰기 구현 - status 기본값/검증, UNIQUE 중복 409 처리"
```

---

## Task 4: Reader — Trail 읽기 (Repository/Controller)

**Files:**
- Create: `services/reader/src/main/java/com/stream/reader/repository/TrailRepository.java`
- Create: `services/reader/src/main/java/com/stream/reader/controller/TrailController.java`
- Create: `services/reader/src/test/java/com/stream/reader/controller/TrailControllerTest.java`

**Interfaces:**
- Consumes: `com.stream.shared.entity.Trail` (Task 1), `com.stream.shared.dto.TrailView` (Task 2)
- Produces: `GET /trails` (선택적 쿼리 파라미터 `stream_id` — 없으면 전체, 있으면 필터링) → `TrailView[]`. `GET /trails/{id}` → `TrailView` 200 또는 404.

- [ ] **Step 1: reader TrailController 테스트를 먼저 작성한다**

`services/reader/src/test/java/com/stream/reader/controller/TrailControllerTest.java`:

```java
package com.stream.reader.controller;

import com.stream.reader.repository.TrailRepository;
import com.stream.shared.entity.Trail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TrailController.class)
@DisplayName("Reader - TrailController 테스트")
class TrailControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TrailRepository trailRepository;

    private Trail buildTrail(Long id, Long streamId, String cameraNumber) throws Exception {
        Trail trail = new Trail();
        Field idField = Trail.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(trail, id);
        trail.setStreamId(streamId);
        trail.setCameraNumber(cameraNumber);
        trail.setLocation((org.locationtech.jts.geom.Point)
                new org.locationtech.jts.io.WKTReader().read("POINT(126.97 37.55)"));
        trail.setDirection("북");
        trail.setStatus("active");
        Field createdAtField = Trail.class.getDeclaredField("createdAt");
        createdAtField.setAccessible(true);
        createdAtField.set(trail, LocalDateTime.of(2024, 1, 1, 0, 0, 0));
        return trail;
    }

    @Test
    @DisplayName("GET /trails - 전체 트레일 목록을 200 OK로 반환한다")
    void getAll_returns200WithTrailList() throws Exception {
        // given
        given(trailRepository.findAll()).willReturn(List.of(buildTrail(1L, 1L, "CAM-001")));

        // when & then
        mockMvc.perform(get("/trails"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].streamId").value(1))
                .andExpect(jsonPath("$[0].cameraNumber").value("CAM-001"))
                .andExpect(jsonPath("$[0].location").value("POINT(126.97 37.55)"));
    }

    @Test
    @DisplayName("GET /trails?stream_id=1 - stream_id로 필터링된 트레일 목록을 반환한다")
    void getAll_returns200FilteredByStreamId() throws Exception {
        // given
        given(trailRepository.findByStreamId(1L)).willReturn(List.of(buildTrail(1L, 1L, "CAM-001")));

        // when & then
        mockMvc.perform(get("/trails").param("stream_id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].streamId").value(1));
    }

    @Test
    @DisplayName("GET /trails - 트레일이 없으면 빈 배열을 200 OK로 반환한다")
    void getAll_returns200WithEmptyList() throws Exception {
        given(trailRepository.findAll()).willReturn(List.of());

        mockMvc.perform(get("/trails"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("GET /trails/{id} - 존재하는 트레일을 200 OK로 반환한다")
    void getById_returns200WhenFound() throws Exception {
        given(trailRepository.findById(1L)).willReturn(Optional.of(buildTrail(1L, 1L, "CAM-001")));

        mockMvc.perform(get("/trails/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.cameraNumber").value("CAM-001"));
    }

    @Test
    @DisplayName("GET /trails/{id} - 존재하지 않으면 404를 반환한다")
    void getById_returns404WhenNotFound() throws Exception {
        given(trailRepository.findById(999L)).willReturn(Optional.empty());

        mockMvc.perform(get("/trails/999"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: 테스트 실행 → 컴파일 실패 확인**

Run: `cd services/reader && ./mvnw -q -B test -Dtest=TrailControllerTest`
Expected: FAIL — `TrailController`/`TrailRepository` 클래스가 없어서 컴파일 에러

- [ ] **Step 3: reader TrailRepository를 작성한다**

`services/reader/src/main/java/com/stream/reader/repository/TrailRepository.java`:

```java
package com.stream.reader.repository;

import com.stream.shared.entity.Trail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TrailRepository extends JpaRepository<Trail, Long> {
    List<Trail> findByStreamId(Long streamId);
}
```

- [ ] **Step 4: reader TrailController를 작성한다**

`services/reader/src/main/java/com/stream/reader/controller/TrailController.java`:

```java
package com.stream.reader.controller;

import com.stream.reader.repository.TrailRepository;
import com.stream.shared.dto.TrailView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/trails")
public class TrailController {

    private final TrailRepository trailRepository;

    public TrailController(TrailRepository trailRepository) {
        this.trailRepository = trailRepository;
    }

    @GetMapping
    public List<TrailView> getAll(@RequestParam(value = "stream_id", required = false) Long streamId) {
        var trails = streamId == null
                ? trailRepository.findAll()
                : trailRepository.findByStreamId(streamId);
        return trails.stream().map(TrailView::from).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<TrailView> getById(@PathVariable Long id) {
        Optional<TrailView> view = trailRepository.findById(id).map(TrailView::from);
        return view.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
```

- [ ] **Step 5: 테스트 실행 → 통과 확인**

Run: `cd services/reader && ./mvnw -q -B test -Dtest=TrailControllerTest`
Expected: PASS (5개 테스트 전부 통과)

- [ ] **Step 6: reader 전체 테스트 실행 → 회귀 없는지 확인**

Run: `cd services/reader && ./mvnw -q -B test -Dtest='!ReaderApplicationTests'`
Expected: 기존 Stream/Capture 관련 테스트 포함 전부 PASS (`ReaderApplicationTests`는 실제 Postgres 연결이 필요해 이 샌드박스에서 제외 — Stream 계획 때와 동일한 이유)

- [ ] **Step 7: 커밋**

```bash
git add services/reader/src/main/java/com/stream/reader/repository/TrailRepository.java services/reader/src/main/java/com/stream/reader/controller/TrailController.java services/reader/src/test/java/com/stream/reader/controller/TrailControllerTest.java
git commit -m "feat(reader): Trail 읽기 구현 - stream_id 선택적 필터 포함"
```

---

## 후속 작업 (이 계획엔 포함 안 함, 참고용)

- **`apps/backend` 게이트웨이 연동**: `TrailServiceImpl`(reader/writer를 `RestClient`로 호출), 기존 RED 스텁 `TrailController`의 실제 구현 전환(`@RequestParam(value = "stream_id", ...)` 버그 수정 포함), 새 예외 3개(`TrailNotFoundException`/`InvalidTrailGeometryException`/`DuplicateTrailException`), `GlobalExceptionHandler` 확장, 기존 `TrailControllerTest`(backend)에 `@TestPropertySource` + 401 테스트 보강. 별도 브랜치에서 별도 계획으로 진행 — 설계는 `docs/superpowers/specs/2026-08-16-trail-cqrs-gis-design.md`의 "Backend" 섹션에 이미 정리되어 있음.
- **Docker 실기동 검증**: `infra/scripts/init-db.sql`은 이미 `trails` 테이블을 정의하고 있어 수정 불필요하지만, 실제 `docker compose up` 환경에서 PostGIS 대상으로 Trail 생성/조회가 동작하는지는 아직 검증되지 않았다 (Stream 때도 동일하게 미검증 상태로 남아있음).

## 최종 검증

- [ ] **shared/writer/reader 전체 테스트 재실행**

```bash
cd packages/shared && ./mvnw -q -B test
cd ../../services/reader && ./mvnw -q -B test -Dtest='!ReaderApplicationTests'
cd ../writer && ./mvnw -q -B test -Dtest='!WriterApplicationTests'
```

Expected: 3개 모듈 전부 GREEN.
