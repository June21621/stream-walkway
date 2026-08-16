# Stream CQRS + GIS Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `Stream` 도메인(하천 산책로)을 Capture와 동일한 CQRS 구조(reader 읽기 / writer 쓰기)로 추가하고, `location` 컬럼을 실제 DB 타입(`GEOMETRY(LineString,4326)`)에 맞춰 JTS/Hibernate Spatial로 제대로 매핑한다. Backend는 DB에 직접 접근하지 않고 reader/writer를 동기 HTTP로 호출하는 게이트웨이 역할만 한다.

**Architecture:** `packages/shared`에 `Stream` JPA 엔티티(`org.locationtech.jts.geom.LineString` 필드)와 `StreamView` 읽기 DTO(위치를 WKT 문자열로 변환)를 추가한다. Writer는 `POST /internal/streams`로 동기 생성 요청을 받아 저장하고 생성된 리소스를 즉시 응답한다(Kafka 미사용 — Stream 생성은 관리자가 등록하는 동기 작업이라 비동기 이벤트로 처리할 이유가 없음). Reader는 `GET /streams`, `GET /streams/{id}`로 조회를 제공한다. Backend는 `RestClient`로 reader/writer를 호출해서 기존 `StreamController`/`StreamService` 스텁을 구현한다.

**Tech Stack:** Java 21, Spring Boot 3.5.x, Spring Data JPA, Hibernate Spatial, JTS (`org.locationtech.jts`), Spring `RestClient`, JUnit 5, Mockito, AssertJ

## Global Constraints

- **Trail은 이 계획의 범위 밖이다.** Stream 패턴이 검증되면 거의 동일한 구조로 별도 계획을 짠다 (Trail은 Stream을 FK로 참조하므로 Stream이 먼저 존재해야 함).
- **Kafka를 쓰지 않는다.** Stream 생성은 `POST /api/streams`(backend) → `POST /internal/streams`(writer) 동기 HTTP 호출로 처리한다. Capture처럼 비동기 이벤트로 만들지 않는다.
- **GIS는 처음부터 제대로 매핑한다.** DB 실제 타입은 `GEOMETRY(LineString,4326)`이므로, 엔티티 필드는 `String`이 아니라 `org.locationtech.jts.geom.LineString`을 쓴다.
- **WKT 문자열 포맷은 공백 없이 `LINESTRING(...)`이다.** JTS의 기본 `WKTWriter`는 `LINESTRING (...)`(키워드 뒤에 공백)를 출력하므로, DTO에서 그 공백을 제거해서 `apps/backend`의 기존 테스트(`StreamControllerTest.java`)가 기대하는 정확한 포맷(`"LINESTRING(126.97 37.55, 126.98 37.56)"`, 공백 없음)과 맞춘다.
- **Repository 레벨의 실제 DB 통합 테스트(H2 등)는 이 계획의 범위 밖이다.** H2가 PostGIS/공간 타입을 안정적으로 지원한다는 보장이 없어서, geometry 컬럼을 실제 쿼리하는 테스트는 만들지 않는다. 대신 컨트롤러 테스트에서 Repository를 mock으로 대체해서 로직만 검증한다. (나중에 실제 Postgres/PostGIS 대상 통합 테스트가 필요하면 Testcontainers 같은 별도 도구 도입을 검토— 이 계획엔 포함 안 함)
- 패키지 루트는 `com.stream.reader`, `com.stream.writer`, `com.stream.backend`, 공유 모듈은 `com.stream.shared`를 쓴다.
- Java 21, `jakarta.persistence.*` 어노테이션을 쓴다.
- 모든 신규 클래스는 `mvnw`(각 서비스에 이미 있는 Maven Wrapper)로 빌드/테스트한다.
- `apps/backend`의 `application.yaml`은 현재 `DataSourceAutoConfiguration`, `RedisAutoConfiguration`을 명시적으로 제외하고 있다 (`exclude:` 목록). 이 계획의 어떤 태스크도 backend에 실제 DB/Redis 연결을 추가하지 않으므로 이 제외 설정은 그대로 둔다 — `packages/shared` 의존성은 JPA 어노테이션 클래스(컴파일 타임)만 쓰고, backend가 Hibernate/DataSource를 부팅하는 게 아니다.
- 기존 GREEN 테스트(reader, writer, shared, backend의 `HealthCheckTest`)는 각 태스크가 끝날 때마다 계속 GREEN이어야 한다.

---

## Task 1: `packages/shared` — GIS 의존성 추가 + Stream 엔티티

**Files:**
- Modify: `packages/shared/pom.xml`
- Create: `packages/shared/src/main/java/com/stream/shared/entity/Stream.java`
- Create: `packages/shared/src/test/java/com/stream/shared/entity/StreamTest.java`

**Interfaces:**
- Produces: `com.stream.shared.entity.Stream` — `@Entity @Table(name = "streams")`, 필드 `id: Long, name: String, location: org.locationtech.jts.geom.LineString, createdAt: LocalDateTime`, `@PrePersist onCreate()`로 `createdAt` 자동 설정. `getId()/getName()/setName()/getLocation()/setLocation()/getCreatedAt()` 제공 (id/createdAt은 getter만).

- [ ] **Step 1: `packages/shared/pom.xml`에 JTS + Hibernate Spatial 의존성을 추가한다**

`packages/shared/pom.xml`의 `<dependencies>` 블록에서 `jakarta.persistence-api` 의존성 바로 다음에 추가:

```xml
		<dependency>
			<groupId>org.hibernate.orm</groupId>
			<artifactId>hibernate-spatial</artifactId>
			<version>6.6.18.Final</version>
		</dependency>
		<dependency>
			<groupId>org.locationtech.jts</groupId>
			<artifactId>jts-core</artifactId>
			<version>1.19.0</version>
		</dependency>
```

(`hibernate-spatial` 6.6.18.Final은 reader/writer가 이미 쓰고 있는 Hibernate ORM 버전(`org.hibernate.Version` 로그에서 확인된 `6.6.18.Final`)과 맞춘 것이다.)

- [ ] **Step 2: Stream 엔티티 테스트를 먼저 작성한다**

`packages/shared/src/test/java/com/stream/shared/entity/StreamTest.java`:

```java
package com.stream.shared.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Shared - Stream Entity 테스트")
class StreamTest {

    private final WKTReader wktReader = new WKTReader();

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("setName() / getName() - name을 저장하고 반환한다")
    void name_setAndGet() {
        Stream stream = new Stream();
        stream.setName("한강 산책로");
        assertThat(stream.getName()).isEqualTo("한강 산책로");
    }

    @Test
    @DisplayName("setLocation() / getLocation() - LineString을 저장하고 반환한다")
    void location_setAndGet() throws ParseException {
        Stream stream = new Stream();
        LineString line = (LineString) wktReader.read("LINESTRING(126.97 37.55, 126.98 37.56)");

        stream.setLocation(line);

        assertThat(stream.getLocation().toText()).isEqualTo("LINESTRING (126.97 37.55, 126.98 37.56)");
    }

    @Test
    @DisplayName("getId() - 저장 전에는 id가 null이다")
    void getId_isNullBeforePersist() {
        assertThat(new Stream().getId()).isNull();
    }

    @Test
    @DisplayName("getCreatedAt() - onCreate() 호출 전에는 createdAt이 null이다")
    void getCreatedAt_isNullBeforeOnCreate() {
        assertThat(new Stream().getCreatedAt()).isNull();
    }

    @Test
    @DisplayName("@PrePersist - onCreate() 호출 시 createdAt이 현재 시각으로 설정된다")
    void onCreate_setsCreatedAt() throws Exception {
        Stream stream = new Stream();
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        Method onCreateMethod = Stream.class.getDeclaredMethod("onCreate");
        onCreateMethod.setAccessible(true);
        onCreateMethod.invoke(stream);

        LocalDateTime after = LocalDateTime.now().plusSeconds(1);
        assertThat(stream.getCreatedAt()).isNotNull();
        assertThat(stream.getCreatedAt()).isAfterOrEqualTo(before);
        assertThat(stream.getCreatedAt()).isBeforeOrEqualTo(after);
    }

    @Test
    @DisplayName("기본 생성자로 만들면 모든 필드가 null이다")
    void defaultConstructor_allFieldsAreNull() {
        Stream empty = new Stream();
        assertThat(empty.getId()).isNull();
        assertThat(empty.getName()).isNull();
        assertThat(empty.getLocation()).isNull();
        assertThat(empty.getCreatedAt()).isNull();
    }
}
```

- [ ] **Step 3: 테스트 실행 → 컴파일 실패 확인**

Run: `cd packages/shared && ./mvnw -q -B test -Dtest=StreamTest`
Expected: FAIL — `Stream` 클래스가 없어서 컴파일 에러

- [ ] **Step 4: Stream 엔티티를 작성한다**

`packages/shared/src/main/java/com/stream/shared/entity/Stream.java`:

```java
package com.stream.shared.entity;

import jakarta.persistence.*;
import org.locationtech.jts.geom.LineString;

import java.time.LocalDateTime;

@Entity
@Table(name = "streams")
public class Stream {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, columnDefinition = "geometry(LineString,4326)")
    private LineString location;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public LineString getLocation() { return location; }
    public void setLocation(LineString location) { this.location = location; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 5: 테스트 실행 → 통과 확인**

Run: `cd packages/shared && ./mvnw -q -B test -Dtest=StreamTest`
Expected: PASS (6개 테스트 전부 통과)

- [ ] **Step 6: shared 전체 테스트 실행 → 회귀 없는지 확인 후 로컬 저장소에 재설치**

Run:
```bash
cd packages/shared && ./mvnw -q -B test
cd packages/shared && ./mvnw -q -B install -DskipTests
```
Expected: 기존 Capture/CaptureView 테스트 포함 전부 PASS, install 성공 (reader/writer/backend가 갱신된 shared jar을 쓸 수 있도록)

- [ ] **Step 7: 커밋**

```bash
git add packages/shared/pom.xml packages/shared/src/main/java/com/stream/shared/entity/Stream.java packages/shared/src/test/java/com/stream/shared/entity/StreamTest.java
git commit -m "feat(shared): Stream 엔티티 추가 (JTS LineString 매핑)"
```

---

## Task 2: `packages/shared` — StreamView 읽기 DTO

**Files:**
- Create: `packages/shared/src/main/java/com/stream/shared/dto/StreamView.java`
- Create: `packages/shared/src/test/java/com/stream/shared/dto/StreamViewTest.java`

**Interfaces:**
- Consumes: `com.stream.shared.entity.Stream` (Task 1)
- Produces: `StreamView` record — `id: Long, name: String, location: String, createdAt: LocalDateTime`, 정적 팩토리 `StreamView.from(Stream stream): StreamView`. `location`은 WKT 문자열이며 **키워드 뒤 공백이 없는 형태**(`"LINESTRING(126.97 37.55, 126.98 37.56)"`)로 만들어야 한다.

- [ ] **Step 1: StreamView 테스트를 먼저 작성한다**

`packages/shared/src/test/java/com/stream/shared/dto/StreamViewTest.java`:

```java
package com.stream.shared.dto;

import com.stream.shared.entity.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Shared - StreamView 테스트")
class StreamViewTest {

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("from() - Stream 엔티티를 StreamView로 변환한다 (location은 공백 없는 WKT 문자열)")
    void from_mapsAllFieldsFromEntity() throws Exception {
        Stream stream = new Stream();
        setField(stream, "id", 1L);
        stream.setName("한강 산책로");
        stream.setLocation((org.locationtech.jts.geom.LineString)
                new WKTReader().read("LINESTRING(126.97 37.55, 126.98 37.56)"));
        setField(stream, "createdAt", LocalDateTime.of(2024, 1, 1, 0, 0, 0));

        StreamView view = StreamView.from(stream);

        assertThat(view.id()).isEqualTo(1L);
        assertThat(view.name()).isEqualTo("한강 산책로");
        assertThat(view.location()).isEqualTo("LINESTRING(126.97 37.55, 126.98 37.56)");
        assertThat(view.createdAt()).isEqualTo(LocalDateTime.of(2024, 1, 1, 0, 0, 0));
    }

    @Test
    @DisplayName("from() - WKTWriter가 기본으로 넣는 공백('LINESTRING (')이 제거된다")
    void from_stripsSpaceAfterGeometryKeyword() throws ParseException {
        Stream stream = new Stream();
        stream.setName("테스트");
        stream.setLocation((org.locationtech.jts.geom.LineString)
                new WKTReader().read("LINESTRING(0 0, 1 1)"));

        StreamView view = StreamView.from(stream);

        assertThat(view.location()).doesNotContain("LINESTRING (");
        assertThat(view.location()).startsWith("LINESTRING(");
    }
}
```

- [ ] **Step 2: 테스트 실행 → 컴파일 실패 확인**

Run: `cd packages/shared && ./mvnw -q -B test -Dtest=StreamViewTest`
Expected: FAIL — `StreamView`가 없어서 컴파일 에러

- [ ] **Step 3: StreamView record를 작성한다**

`packages/shared/src/main/java/com/stream/shared/dto/StreamView.java`:

```java
package com.stream.shared.dto;

import com.stream.shared.entity.Stream;
import org.locationtech.jts.io.WKTWriter;

import java.time.LocalDateTime;

public record StreamView(
        Long id,
        String name,
        String location,
        LocalDateTime createdAt
) {
    private static final WKTWriter WKT_WRITER = new WKTWriter();

    public static StreamView from(Stream stream) {
        String wkt = WKT_WRITER.write(stream.getLocation()).replaceFirst("\\s+\\(", "(");
        return new StreamView(
                stream.getId(),
                stream.getName(),
                wkt,
                stream.getCreatedAt()
        );
    }
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `cd packages/shared && ./mvnw -q -B test -Dtest=StreamViewTest`
Expected: PASS

- [ ] **Step 5: shared 전체 테스트 실행 후 재설치**

Run:
```bash
cd packages/shared && ./mvnw -q -B test
cd packages/shared && ./mvnw -q -B install -DskipTests
```
Expected: 전부 PASS, install 성공

- [ ] **Step 6: 커밋**

```bash
git add packages/shared/src/main/java/com/stream/shared/dto/StreamView.java packages/shared/src/test/java/com/stream/shared/dto/StreamViewTest.java
git commit -m "feat(shared): StreamView 읽기 DTO 추가 (WKT 문자열 변환)"
```

---

## Task 3: Writer — Stream 쓰기 (Command/Handler/내부 REST 엔드포인트)

**Files:**
- Modify: `services/writer/pom.xml`
- Create: `services/writer/src/main/java/com/stream/writer/repository/StreamRepository.java`
- Create: `services/writer/src/main/java/com/stream/writer/command/CreateStreamCommand.java`
- Create: `services/writer/src/main/java/com/stream/writer/command/StreamCommandHandler.java`
- Create: `services/writer/src/main/java/com/stream/writer/controller/StreamController.java`
- Create: `services/writer/src/test/java/com/stream/writer/command/StreamCommandHandlerTest.java`
- Create: `services/writer/src/test/java/com/stream/writer/controller/StreamControllerTest.java`

**Interfaces:**
- Consumes: `com.stream.shared.entity.Stream`, `com.stream.shared.dto.StreamView` (Task 1, 2)
- Produces: `CreateStreamCommand` record — `name: String, location: String` (WKT). `StreamCommandHandler.handle(CreateStreamCommand): Stream`. `POST /internal/streams` — 요청 바디 `{"name": "...", "location": "LINESTRING(...)"}, ` 응답 `201 Created` + `StreamView` JSON.

- [ ] **Step 1: writer/pom.xml에 shared 갱신 버전을 다시 받도록 하기 위한 확인**

`services/writer/pom.xml`은 이미 `com.stream:shared:0.0.1-SNAPSHOT` 의존성을 갖고 있다 (이전 CQRS 리팩터링 때 추가됨). 별도 수정 불필요 — Task 1/2에서 `./mvnw install`로 로컬 저장소에 새 shared jar을 설치했으므로 writer가 다음 빌드 때 자동으로 갱신된 버전을 쓴다.

- [ ] **Step 2: CreateStreamCommand record를 작성한다**

`services/writer/src/main/java/com/stream/writer/command/CreateStreamCommand.java`:

```java
package com.stream.writer.command;

public record CreateStreamCommand(
        String name,
        String location
) {}
```

- [ ] **Step 3: StreamRepository를 작성한다**

`services/writer/src/main/java/com/stream/writer/repository/StreamRepository.java`:

```java
package com.stream.writer.repository;

import com.stream.shared.entity.Stream;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StreamRepository extends JpaRepository<Stream, Long> {
}
```

- [ ] **Step 4: StreamCommandHandler 테스트를 먼저 작성한다**

`services/writer/src/test/java/com/stream/writer/command/StreamCommandHandlerTest.java`:

```java
package com.stream.writer.command;

import com.stream.shared.entity.Stream;
import com.stream.writer.repository.StreamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.io.ParseException;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("Writer - StreamCommandHandler 테스트")
class StreamCommandHandlerTest {

    @Mock
    private StreamRepository streamRepository;

    @InjectMocks
    private StreamCommandHandler handler;

    @Test
    @DisplayName("handle() - Command를 처리하면 WKT를 파싱해서 PostgreSQL에 Stream을 저장한다")
    void handle_savesStreamWithParsedGeometry() throws ParseException {
        // given
        CreateStreamCommand command = new CreateStreamCommand("한강 산책로", "LINESTRING(126.97 37.55, 126.98 37.56)");
        Stream savedStream = new Stream();
        savedStream.setName("한강 산책로");
        given(streamRepository.save(any(Stream.class))).willReturn(savedStream);

        // when
        Stream result = handler.handle(command);

        // then
        ArgumentCaptor<Stream> captor = ArgumentCaptor.forClass(Stream.class);
        verify(streamRepository).save(captor.capture());

        Stream toSave = captor.getValue();
        assertThat(toSave.getName()).isEqualTo("한강 산책로");
        assertThat(toSave.getLocation().toText()).isEqualTo("LINESTRING (126.97 37.55, 126.98 37.56)");
        assertThat(result).isEqualTo(savedStream);
    }

    @Test
    @DisplayName("handle() - 잘못된 WKT 문자열이면 ParseException을 던진다")
    void handle_throwsParseExceptionOnInvalidWkt() {
        // given
        CreateStreamCommand command = new CreateStreamCommand("잘못된 스트림", "NOT-A-VALID-WKT");

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(ParseException.class, () -> handler.handle(command));
    }
}
```

- [ ] **Step 5: 테스트 실행 → 컴파일 실패 확인**

Run: `cd services/writer && ./mvnw -q -B test -Dtest=StreamCommandHandlerTest`
Expected: FAIL — `StreamCommandHandler`가 없어서 컴파일 에러

- [ ] **Step 6: StreamCommandHandler를 작성한다**

`services/writer/src/main/java/com/stream/writer/command/StreamCommandHandler.java`:

```java
package com.stream.writer.command;

import com.stream.shared.entity.Stream;
import com.stream.writer.repository.StreamRepository;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.springframework.stereotype.Component;

@Component
public class StreamCommandHandler {

    private final StreamRepository streamRepository;
    private final WKTReader wktReader = new WKTReader();

    public StreamCommandHandler(StreamRepository streamRepository) {
        this.streamRepository = streamRepository;
    }

    // ─────────────────────────────────────────
    // CreateStreamCommand 처리 → WKT 문자열을 LineString으로 파싱 → PostgreSQL 저장
    // ─────────────────────────────────────────
    public Stream handle(CreateStreamCommand command) throws ParseException {
        Stream stream = new Stream();
        stream.setName(command.name());
        stream.setLocation((LineString) wktReader.read(command.location()));
        return streamRepository.save(stream);
    }
}
```

- [ ] **Step 7: 테스트 실행 → 통과 확인**

Run: `cd services/writer && ./mvnw -q -B test -Dtest=StreamCommandHandlerTest`
Expected: PASS

- [ ] **Step 8: StreamController(내부 전용 REST) 테스트를 먼저 작성한다**

`services/writer/src/test/java/com/stream/writer/controller/StreamControllerTest.java`:

```java
package com.stream.writer.controller;

import com.stream.shared.entity.Stream;
import com.stream.writer.command.CreateStreamCommand;
import com.stream.writer.command.StreamCommandHandler;
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

@WebMvcTest(StreamController.class)
@DisplayName("Writer - StreamController(내부 전용) 테스트")
class StreamControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StreamCommandHandler streamCommandHandler;

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("POST /internal/streams - Stream을 생성하면 201 Created와 StreamView를 반환한다")
    void create_returns201WithStreamView() throws Exception {
        // given
        Stream saved = new Stream();
        setField(saved, "id", 1L);
        saved.setName("한강 산책로");
        saved.setLocation((org.locationtech.jts.geom.LineString)
                new org.locationtech.jts.io.WKTReader().read("LINESTRING(126.97 37.55, 126.98 37.56)"));
        setField(saved, "createdAt", LocalDateTime.of(2024, 1, 1, 0, 0, 0));

        given(streamCommandHandler.handle(any(CreateStreamCommand.class))).willReturn(saved);

        String requestBody = """
                {
                  "name": "한강 산책로",
                  "location": "LINESTRING(126.97 37.55, 126.98 37.56)"
                }
                """;

        // when & then
        mockMvc.perform(post("/internal/streams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("한강 산책로"))
                .andExpect(jsonPath("$.location").value("LINESTRING(126.97 37.55, 126.98 37.56)"));
    }
}
```

- [ ] **Step 9: 테스트 실행 → 컴파일 실패 확인**

Run: `cd services/writer && ./mvnw -q -B test -Dtest=StreamControllerTest`
Expected: FAIL — `StreamController`(writer)가 없어서 컴파일 에러

- [ ] **Step 10: StreamController(내부 전용)를 작성한다**

`services/writer/src/main/java/com/stream/writer/controller/StreamController.java`:

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
}
```

- [ ] **Step 11: 테스트 실행 → 통과 확인**

Run: `cd services/writer && ./mvnw -q -B test -Dtest=StreamControllerTest`
Expected: PASS

- [ ] **Step 12: writer 전체 테스트 실행 → 회귀 없는지 확인**

Run: `cd services/writer && ./mvnw -q -B test -Dtest='!WriterApplicationTests'`
Expected: PASS. `WriterApplicationTests`는 Global Constraints에 따라 실제 Postgres/Redis가 필요해서 이 환경에선 항상 제외한다 (이 계획과 무관한 사전 조건).

- [ ] **Step 13: 커밋**

```bash
git add services/writer/src/main/java/com/stream/writer/repository/StreamRepository.java services/writer/src/main/java/com/stream/writer/command/CreateStreamCommand.java services/writer/src/main/java/com/stream/writer/command/StreamCommandHandler.java services/writer/src/main/java/com/stream/writer/controller/StreamController.java services/writer/src/test/java/com/stream/writer/command/StreamCommandHandlerTest.java services/writer/src/test/java/com/stream/writer/controller/StreamControllerTest.java
git commit -m "feat(writer): Stream 생성용 내부 REST 엔드포인트 추가 (Command/Handler, Kafka 미사용)"
```

---

## Task 4: Reader — Stream 읽기 (Repository/Controller)

**Files:**
- Create: `services/reader/src/main/java/com/stream/reader/repository/StreamRepository.java`
- Create: `services/reader/src/main/java/com/stream/reader/controller/StreamController.java`
- Create: `services/reader/src/test/java/com/stream/reader/controller/StreamControllerTest.java`

**Interfaces:**
- Consumes: `com.stream.shared.entity.Stream`, `com.stream.shared.dto.StreamView` (Task 1, 2)
- Produces: `GET /streams` → `List<StreamView>`. `GET /streams/{id}` → `StreamView`(200) 또는 404.

- [ ] **Step 1: StreamRepository를 작성한다**

`services/reader/src/main/java/com/stream/reader/repository/StreamRepository.java`:

```java
package com.stream.reader.repository;

import com.stream.shared.entity.Stream;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StreamRepository extends JpaRepository<Stream, Long> {
}
```

- [ ] **Step 2: StreamController 테스트를 먼저 작성한다**

`services/reader/src/test/java/com/stream/reader/controller/StreamControllerTest.java`:

```java
package com.stream.reader.controller;

import com.stream.reader.repository.StreamRepository;
import com.stream.shared.entity.Stream;
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

@WebMvcTest(StreamController.class)
@DisplayName("Reader - StreamController 테스트")
class StreamControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StreamRepository streamRepository;

    private Stream buildStream(Long id, String name) throws Exception {
        Stream stream = new Stream();
        Field idField = Stream.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(stream, id);
        stream.setName(name);
        stream.setLocation((org.locationtech.jts.geom.LineString)
                new org.locationtech.jts.io.WKTReader().read("LINESTRING(126.97 37.55, 126.98 37.56)"));
        Field createdAtField = Stream.class.getDeclaredField("createdAt");
        createdAtField.setAccessible(true);
        createdAtField.set(stream, LocalDateTime.of(2024, 1, 1, 0, 0, 0));
        return stream;
    }

    @Test
    @DisplayName("GET /streams - 전체 스트림 목록을 200 OK로 반환한다")
    void getAll_returns200WithStreamList() throws Exception {
        // given
        given(streamRepository.findAll()).willReturn(List.of(buildStream(1L, "한강 산책로")));

        // when & then
        mockMvc.perform(get("/streams"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("한강 산책로"))
                .andExpect(jsonPath("$[0].location").value("LINESTRING(126.97 37.55, 126.98 37.56)"));
    }

    @Test
    @DisplayName("GET /streams - 스트림이 없으면 빈 배열을 200 OK로 반환한다")
    void getAll_returns200WithEmptyList() throws Exception {
        given(streamRepository.findAll()).willReturn(List.of());

        mockMvc.perform(get("/streams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("GET /streams/{id} - 존재하는 스트림을 200 OK로 반환한다")
    void getById_returns200WhenFound() throws Exception {
        given(streamRepository.findById(1L)).willReturn(Optional.of(buildStream(1L, "한강 산책로")));

        mockMvc.perform(get("/streams/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("한강 산책로"));
    }

    @Test
    @DisplayName("GET /streams/{id} - 존재하지 않으면 404를 반환한다")
    void getById_returns404WhenNotFound() throws Exception {
        given(streamRepository.findById(999L)).willReturn(Optional.empty());

        mockMvc.perform(get("/streams/999"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 3: 테스트 실행 → 컴파일 실패 확인**

Run: `cd services/reader && ./mvnw -q -B test -Dtest=StreamControllerTest`
Expected: FAIL — `StreamController`(reader)가 없어서 컴파일 에러

- [ ] **Step 4: StreamController를 작성한다**

`services/reader/src/main/java/com/stream/reader/controller/StreamController.java`:

```java
package com.stream.reader.controller;

import com.stream.reader.repository.StreamRepository;
import com.stream.shared.dto.StreamView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/streams")
public class StreamController {

    private final StreamRepository streamRepository;

    public StreamController(StreamRepository streamRepository) {
        this.streamRepository = streamRepository;
    }

    @GetMapping
    public List<StreamView> getAll() {
        return streamRepository.findAll().stream()
                .map(StreamView::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<StreamView> getById(@PathVariable Long id) {
        Optional<StreamView> view = streamRepository.findById(id).map(StreamView::from);
        return view.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
```

- [ ] **Step 5: 테스트 실행 → 통과 확인**

Run: `cd services/reader && ./mvnw -q -B test -Dtest=StreamControllerTest`
Expected: PASS (4개 테스트 전부 통과)

- [ ] **Step 6: reader 전체 테스트 실행 → 회귀 없는지 확인**

Run: `cd services/reader && ./mvnw -q -B test -Dtest='!ReaderApplicationTests'`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add services/reader/src/main/java/com/stream/reader/repository/StreamRepository.java services/reader/src/main/java/com/stream/reader/controller/StreamController.java services/reader/src/test/java/com/stream/reader/controller/StreamControllerTest.java
git commit -m "feat(reader): Stream 조회 API 추가 (GET /streams, GET /streams/{id})"
```

---

## Task 5: Backend — StreamService 구현 (reader/writer 동기 HTTP 연동)

**Files:**
- Modify: `pom.xml` (레포 루트)
- Modify: `apps/backend/pom.xml`
- Modify: `apps/backend/src/main/resources/application.yaml`
- Create: `apps/backend/src/main/java/com/stream/backend/config/HttpClientConfig.java`
- Create: `apps/backend/src/main/java/com/stream/backend/service/StreamServiceImpl.java`
- Modify: `apps/backend/src/main/java/com/stream/backend/controller/StreamController.java`
- Create: `apps/backend/src/main/java/com/stream/backend/exception/GlobalExceptionHandler.java`
- Test: `apps/backend/src/test/java/com/stream/backend/controller/StreamControllerTest.java` (기존 파일, 이미 있음 — RED에서 GREEN으로 전환)
- Test: `apps/backend/src/test/java/com/stream/backend/service/StreamServiceImplTest.java` (신규)

**Interfaces:**
- Consumes: `com.stream.shared.dto.StreamView` (Task 2), reader의 `GET /streams`, `GET /streams/{id}` (Task 4), writer의 `POST /internal/streams` (Task 3)
- Produces: `StreamService`(기존 인터페이스, 변경 없음)를 구현하는 `StreamServiceImpl`. `StreamController`의 3개 엔드포인트가 실제로 동작.

- [ ] **Step 1: 레포 루트 pom.xml에 backend를 모듈로 추가한다**

`pom.xml`(레포 루트)의 `<modules>` 블록을 아래로 교체:

```xml
    <modules>
        <module>packages/shared</module>
        <module>services/reader</module>
        <module>services/writer</module>
        <module>apps/backend</module>
    </modules>
```

- [ ] **Step 2: backend/pom.xml에 shared 의존성을 추가한다**

`apps/backend/pom.xml`의 `<dependencies>` 블록 맨 앞에 추가:

```xml
		<dependency>
			<groupId>com.stream</groupId>
			<artifactId>shared</artifactId>
			<version>0.0.1-SNAPSHOT</version>
		</dependency>
```

- [ ] **Step 3: backend application.yaml에 reader/writer base-url 설정을 추가한다**

`apps/backend/src/main/resources/application.yaml` 전체를 아래로 교체:

```yaml
server:
  port: 8080

spring:
  application:
    name: backend
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
      - org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration

reader:
  base-url: ${READER_BASE_URL:http://localhost:8001}
writer:
  base-url: ${WRITER_BASE_URL:http://localhost:8002}
```

- [ ] **Step 4: RestClient 빈을 등록하는 설정 클래스를 작성한다**

`apps/backend/src/main/java/com/stream/backend/config/HttpClientConfig.java`:

```java
package com.stream.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient readerRestClient(@Value("${reader.base-url}") String readerBaseUrl) {
        return RestClient.builder().baseUrl(readerBaseUrl).build();
    }

    @Bean
    public RestClient writerRestClient(@Value("${writer.base-url}") String writerBaseUrl) {
        return RestClient.builder().baseUrl(writerBaseUrl).build();
    }
}
```

**알려진 리스크**: 아래 테스트는 `RestClient`의 fluent 체이닝 API(`.get().uri().retrieve().body()`)를 `mock(RestClient.class, RETURNS_DEEP_STUBS)`로 검증한다. Mockito의 deep-stub은 이런 체이닝 인터페이스에서 가끔 정확히 동작하지 않을 수 있다(특히 제네릭이 섞인 `body(ParameterizedTypeReference<T>)` 오버로드). 아래 코드대로 실행했을 때 컴파일은 되는데 mock 체이닝이 기대한 값을 반환하지 않는 등의 문제가 나면, 억지로 맞추려 하지 말고 `RestClient` 호출부를 별도의 작은 인터페이스(예: `StreamReaderClient`)로 감싸서 그 인터페이스를 mock하는 방식으로 바꿔도 된다 — 이 경우 BLOCKED/NEEDS_CONTEXT로 보고하지 말고, 이 방식 변경 사유를 보고서에 남기고 진행해도 되는 재량 범위로 취급한다.

- [ ] **Step 5: StreamServiceImpl 테스트를 먼저 작성한다**

`apps/backend/src/test/java/com/stream/backend/service/StreamServiceImplTest.java`:

```java
package com.stream.backend.service;

import com.stream.backend.model.Stream;
import com.stream.shared.dto.StreamView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayName("Backend - StreamServiceImpl 테스트")
class StreamServiceImplTest {

    @Test
    @DisplayName("findAll() - reader의 GET /streams 응답을 Stream 모델 리스트로 변환한다")
    void findAll_mapsReaderResponseToStreamList() {
        // given
        RestClient readerClient = mock(RestClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        RestClient writerClient = mock(RestClient.class);
        StreamView view = new StreamView(1L, "한강 산책로", "LINESTRING(126.97 37.55, 126.98 37.56)",
                LocalDateTime.of(2024, 1, 1, 0, 0, 0));

        given(readerClient.get()
                .uri("/streams")
                .retrieve()
                .body(any(org.springframework.core.ParameterizedTypeReference.class)))
                .willReturn(List.of(view));

        StreamServiceImpl service = new StreamServiceImpl(readerClient, writerClient);

        // when
        List<Stream> result = service.findAll();

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(1L);
        assertThat(result.get(0).getName()).isEqualTo("한강 산책로");
        assertThat(result.get(0).getLocation()).isEqualTo("LINESTRING(126.97 37.55, 126.98 37.56)");
        assertThat(result.get(0).getCreatedAt()).isEqualTo("2024-01-01T00:00:00");
    }

    @Test
    @DisplayName("findById() - reader가 404를 주면 빈 Optional을 반환한다")
    void findById_returnsEmptyOnReader404() {
        RestClient readerClient = mock(RestClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        RestClient writerClient = mock(RestClient.class);

        given(readerClient.get()
                .uri("/streams/{id}", 999L)
                .retrieve()
                .body(StreamView.class))
                .willThrow(org.springframework.web.client.HttpClientErrorException.NotFound.class);

        StreamServiceImpl service = new StreamServiceImpl(readerClient, writerClient);

        Optional<Stream> result = service.findById(999L);

        assertThat(result).isEmpty();
    }
}
```

- [ ] **Step 6: 테스트 실행 → 컴파일 실패 확인**

Run: `cd apps/backend && ./mvnw -q -B test -Dtest=StreamServiceImplTest`
Expected: FAIL — `StreamServiceImpl`이 없어서 컴파일 에러

- [ ] **Step 7: StreamServiceImpl을 작성한다**

`apps/backend/src/main/java/com/stream/backend/service/StreamServiceImpl.java`:

```java
package com.stream.backend.service;

import com.stream.backend.model.Stream;
import com.stream.shared.dto.StreamView;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

@Service
public class StreamServiceImpl implements StreamService {

    private final RestClient readerClient;
    private final RestClient writerClient;

    public StreamServiceImpl(@Qualifier("readerRestClient") RestClient readerClient,
                              @Qualifier("writerRestClient") RestClient writerClient) {
        this.readerClient = readerClient;
        this.writerClient = writerClient;
    }

    private Stream toModel(StreamView view) {
        return new Stream(
                view.id(),
                view.name(),
                view.location(),
                view.createdAt() == null ? null : view.createdAt().toString()
        );
    }

    @Override
    public List<Stream> findAll() {
        List<StreamView> views = readerClient.get()
                .uri("/streams")
                .retrieve()
                .body(new ParameterizedTypeReference<List<StreamView>>() {});
        return views == null ? List.of() : views.stream().map(this::toModel).toList();
    }

    @Override
    public Optional<Stream> findById(Long id) {
        try {
            StreamView view = readerClient.get()
                    .uri("/streams/{id}", id)
                    .retrieve()
                    .body(StreamView.class);
            return Optional.ofNullable(view).map(this::toModel);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    @Override
    public Stream create(Stream stream) {
        StreamView created = writerClient.post()
                .uri("/internal/streams")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(new CreateStreamRequest(stream.getName(), stream.getLocation()))
                .retrieve()
                .body(StreamView.class);
        return toModel(created);
    }

    private record CreateStreamRequest(String name, String location) {}
}
```

- [ ] **Step 8: 테스트 실행 → 통과 확인**

Run: `cd apps/backend && ./mvnw -q -B test -Dtest=StreamServiceImplTest`
Expected: PASS

- [ ] **Step 9: 404 응답 바디를 위한 전역 예외 핸들러를 작성한다**

`apps/backend/src/main/java/com/stream/backend/exception/GlobalExceptionHandler.java`:

```java
package com.stream.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(StreamNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleStreamNotFound(StreamNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Stream not found", "id", e.getId()));
    }
}
```

같은 파일 묶음으로 `apps/backend/src/main/java/com/stream/backend/exception/StreamNotFoundException.java`도 생성:

```java
package com.stream.backend.exception;

public class StreamNotFoundException extends RuntimeException {

    private final Long id;

    public StreamNotFoundException(Long id) {
        super("Stream not found: " + id);
        this.id = id;
    }

    public Long getId() {
        return id;
    }
}
```

- [ ] **Step 10: StreamController(backend)가 StreamService를 실제로 호출하도록 구현한다**

`apps/backend/src/main/java/com/stream/backend/controller/StreamController.java` 전체를 아래로 교체:

```java
package com.stream.backend.controller;

import com.stream.backend.exception.StreamNotFoundException;
import com.stream.backend.model.Stream;
import com.stream.backend.service.StreamService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/streams")
public class StreamController {

    private final StreamService streamService;

    public StreamController(StreamService streamService) {
        this.streamService = streamService;
    }

    @GetMapping
    public ResponseEntity<List<Stream>> getAll() {
        return ResponseEntity.ok(streamService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        Stream stream = streamService.findById(id)
                .orElseThrow(() -> new StreamNotFoundException(id));
        return ResponseEntity.ok(stream);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Stream stream,
                                    @RequestHeader("X-Internal-Key") String internalKey) {
        Stream created = streamService.create(stream);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
```

(`X-Internal-Key` 헤더가 없는 요청은 `@RequestHeader`가 필수 파라미터라서 Spring이 컨트롤러 로직에 도달하기도 전에 자동으로 400 Bad Request를 반환한다 — 별도 검증 코드 불필요.)

- [ ] **Step 11: 기존 StreamControllerTest(RED)가 GREEN으로 전환됐는지 확인한다**

`apps/backend/src/test/java/com/stream/backend/controller/StreamControllerTest.java`는 이미 존재하는 파일이며 수정하지 않는다 — 이 태스크의 목적은 그 테스트를 통과시키는 것이다.

Run: `cd apps/backend && ./mvnw -q -B test -Dtest=StreamControllerTest`
Expected: PASS (6개 테스트 전부 통과 — `getAll_returns200WithStreamList`, `getAll_returns200WithEmptyList`, `getById_returns200WhenFound`, `getById_returns404WhenNotFound`, `create_returns201WithInternalKey`, `create_returns400WithoutInternalKey`)

- [ ] **Step 12: backend 전체 테스트 실행 → 회귀 없는지 확인**

Run: `cd apps/backend && ./mvnw -q -B test -Dtest='!TrailControllerTest,!CaptureControllerTest'`
Expected: `StreamControllerTest`, `StreamServiceImplTest`, `HealthCheckTest`는 PASS. `TrailControllerTest`/`CaptureControllerTest`는 이 계획의 범위 밖(Trail은 별도 계획, backend의 Capture 스텁도 손대지 않음)이라 여전히 RED일 수 있음 — 이번 태스크가 만든 회귀가 아니라 애초에 미구현 상태이므로 제외하고 검증한다.

- [ ] **Step 13: 루트 aggregator로 전체 리액터 빌드를 검증한다**

Run: `./mvnw -q -B -f pom.xml install -DskipTests` (레포 루트에서, `packages/shared`의 `mvnw` 사용: `packages/shared/mvnw -f pom.xml install -DskipTests`)
Expected: `shared → reader/writer → backend` 순서로 4개 모듈 전부 `BUILD SUCCESS`

- [ ] **Step 14: 커밋**

```bash
git add pom.xml apps/backend/pom.xml apps/backend/src/main/resources/application.yaml apps/backend/src/main/java/com/stream/backend/config/HttpClientConfig.java apps/backend/src/main/java/com/stream/backend/service/StreamServiceImpl.java apps/backend/src/main/java/com/stream/backend/controller/StreamController.java apps/backend/src/main/java/com/stream/backend/exception/GlobalExceptionHandler.java apps/backend/src/main/java/com/stream/backend/exception/StreamNotFoundException.java apps/backend/src/test/java/com/stream/backend/service/StreamServiceImplTest.java
git commit -m "feat(backend): StreamService를 reader/writer 동기 HTTP 호출로 구현, StreamController RED 테스트 GREEN 전환"
```

---

## Docker/인프라 후속 작업 (이 계획엔 포함 안 함, 참고용)

`infra/docker/docker-compose.yml`의 `backend` 서비스에 `READER_BASE_URL=http://reader:8080`, `WRITER_BASE_URL=http://writer:8080` 환경 변수를 추가해야 실제 컨테이너 환경에서 backend가 reader/writer를 찾을 수 있다. 이 계획은 Docker 데몬이 없는 샌드박스에서 검증 불가능한 부분이라 범위에서 제외했다 — Trail 계획과 함께, 또는 별도로 처리 권장.

## 최종 검증

- [ ] **전체 모듈 테스트 재실행**

```bash
cd packages/shared && ./mvnw -q -B test
cd ../../services/reader && ./mvnw -q -B test -Dtest='!ReaderApplicationTests'
cd ../writer && ./mvnw -q -B test -Dtest='!WriterApplicationTests'
cd ../../apps/backend && ./mvnw -q -B test -Dtest='!TrailControllerTest,!CaptureControllerTest'
```

Expected: 4개 모듈 전부 GREEN.
