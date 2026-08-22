# created_at 타임존 정보 복구 (LocalDateTime → Instant) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `created_at`이 타임존 정보 없이 직렬화되어 API 응답에 `Z`가 빠지는 버그를 고친다. `packages/shared`의 3개 엔티티/3개 DTO의 `createdAt`을 `LocalDateTime` → `Instant`로 바꾸고, DB 컬럼을 `TIMESTAMP` → `TIMESTAMPTZ`로 변경한다.

**Architecture:** `LocalDateTime`은 타임존 정보를 갖지 않으므로 Jackson이 `"2024-01-01T00:00:00"`(Z 없음)으로 직렬화한다. `Instant`로 바꾸면 같은 Jackson 설정에서 `"2024-01-01T00:00:00Z"`가 나온다. `apps/backend`는 이 DTO를 받아 자기 모델의 `String createdAt`으로 옮기는데, 지금 `.format(ISO_LOCAL_DATE_TIME)`으로 Z를 지우고 있어 `.toString()`(= `Instant`의 ISO-8601 표현, 항상 `Z` 포함)으로 바꾼다. 도메인(Stream/Trail/Capture)별로 태스크를 나눠서 매 커밋마다 4개 모듈이 전부 빌드/GREEN 상태를 유지한다.

**Tech Stack:** Java 21, Spring Boot 3.5.x, Spring Data JPA, Hibernate 6, Jackson (`jackson-datatype-jsr310`), PostgreSQL, JUnit 5, Mockito, AssertJ

## 계획 수립 전 실측으로 확인한 사실 (추측 아님)

이 계획의 전제는 전부 실제로 코드를 실행해서 확인했다. 구현 중 다르게 동작하면 그건 계획의 오류이므로 보고할 것.

1. **버그는 실재하며 wire 레벨에서 재현됨.** reader의 `GET /trails` 응답 본문을 실제로 찍어보면 `{"id":1,...,"createdAt":"2024-01-01T00:00:00"}` — `Z`가 없다.
2. **Jackson은 날짜를 ISO 문자열로 직렬화한다** (epoch 숫자가 아님). 위 응답에서 `LocalDateTime`이 `"2024-01-01T00:00:00"` 문자열로 나온 것이 증거다. 즉 `WRITE_DATES_AS_TIMESTAMPS`가 비활성(Spring Boot 기본값)이므로, `Instant`로 바꾸면 `"2024-01-01T00:00:00Z"`가 나온다. 별도 Jackson 설정 추가 불필요.
3. **H2 테스트 스키마는 손댈 필요 없다.** `services/{reader,writer}/src/test/resources/schema.sql`의 `created_at TIMESTAMP` 컬럼에 `Instant` 필드를 매핑해서 `CaptureRepositoryTest`를 돌려봤고(writer 쪽은 실제 INSERT 8건 수행), reader 10/10·writer 8/8 전부 통과했다. Hibernate가 알아서 변환한다.
4. **서로 모순되는 테스트 2개가 지금 둘 다 GREEN이다.** `apps/backend`의 `StreamServiceImplTest:50`은 `"2024-01-01T00:00:00"`(Z 없음)을 기대하고, 같은 필드에 대해 `StreamControllerTest:56`은 `"2024-01-01T00:00:00Z"`(Z 있음)를 기대한다. 후자가 통과하는 건 서비스가 mock이라 변환 로직을 안 거치기 때문이다. **이 마이그레이션의 목표 상태는 "둘 다 Z 있음"이며, 컨트롤러 테스트의 기대값이 비로소 진짜가 된다.**
5. **`TrailServiceImpl`의 `import java.time.format.DateTimeFormatter;`는 이 작업 후 미사용이 된다** (제거 대상). `StreamServiceImpl`은 인라인 FQN을 쓰므로 제거할 import가 없다.

## Global Constraints

- **이 작업은 4개 모듈을 한 브랜치에서 같이 가야 한다. 쪼갤 수 없다.** `apps/backend`의 `StreamServiceImpl`/`TrailServiceImpl`이 `view.createdAt().format(...)`을 호출하는데 `Instant`에는 `.format()` 메서드가 없어서, `packages/shared`의 DTO 타입을 바꾸는 순간 backend가 컴파일 실패한다. 지금까지 Trail 작업에서 쓰던 "DB 계층 브랜치 → backend 브랜치" 분리를 이번엔 적용할 수 없다.
- **대신 도메인(Stream/Trail/Capture)별로 태스크를 나눈다.** Stream만 바꾸면 Trail/Capture는 그대로 컴파일되므로, 각 태스크가 끝날 때마다 4개 모듈 전부가 빌드되고 GREEN이어야 한다. 어떤 커밋도 컴파일 실패 상태로 남기지 않는다.
- **`apps/backend`의 모델(`model/Stream.java`, `model/Trail.java`, `model/Capture.java`)은 바꾸지 않는다.** `String createdAt` + `@JsonProperty("created_at")`은 외부 API 계약이며 그대로 유지한다. 바뀌는 건 그 String을 만들어내는 `ServiceImpl`의 변환 로직뿐이다.
- **`Instant`의 문자열 표현은 `toString()`을 쓴다.** `Instant.toString()`은 ISO-8601 UTC 표현(`2024-01-01T00:00:00Z`)을 반환하도록 자바 표준에 규정되어 있으므로 별도 포맷터가 필요 없다.
- **DB 컬럼은 `TIMESTAMPTZ`로 바꾼다** (사용자 결정). Postgres가 타임존을 직접 보관하므로 `hibernate.jdbc.time_zone` 같은 추가 설정이 필요 없다. `infra/scripts/init-db.sql`은 컨테이너 최초 기동 시 실행되는 초기화 스크립트이고 현재 실사용 데이터가 없으므로(Docker 실기동 검증이 아직 한 번도 성공한 적 없음) 별도 마이그레이션 스크립트는 만들지 않는다.
- **`docs/diagrams/erd.md`는 스키마와 함께 갱신한다.** 이 문서 상단에 "스키마 변경 시 `init-db.sql`과 이 문서를 함께 수정해 주세요"라고 명시되어 있다.
- **테스트 픽스처의 기준 시각은 `Instant.parse("2024-01-01T00:00:00Z")`로 통일한다.** 기존 `LocalDateTime.of(2024, 1, 1, 0, 0, 0)`과 같은 시점을 UTC로 표현한 것이다.
- `WriterApplicationTests`/`ReaderApplicationTests`는 실제 Postgres 연결이 필요해 이 샌드박스에서 계속 실패한다 — 기존 계획들과 동일하게 `-Dtest='!WriterApplicationTests'`/`-Dtest='!ReaderApplicationTests'`로 제외한다. `apps/backend`는 `-Dtest='!CaptureControllerTest'`로 제외한다(Capture 게이트웨이 미구현, 범위 밖).
- **`packages/shared`를 수정한 태스크는 반드시 `./mvnw -q -B install -DskipTests`까지 실행한다.** reader/writer/backend가 로컬 `.m2`의 shared jar를 참조하므로, install을 빠뜨리면 다운스트림 모듈이 옛 jar로 컴파일되어 조용히 잘못된 결과가 나온다.
- 패키지 루트는 `com.stream.shared`, `com.stream.reader`, `com.stream.writer`, `com.stream.backend`. Java 21.

---

## Task 1: Stream 도메인 — createdAt을 Instant로

**Files:**
- Modify: `packages/shared/src/main/java/com/stream/shared/entity/Stream.java`
- Modify: `packages/shared/src/main/java/com/stream/shared/dto/StreamView.java`
- Modify: `packages/shared/src/test/java/com/stream/shared/entity/StreamTest.java`
- Modify: `packages/shared/src/test/java/com/stream/shared/dto/StreamViewTest.java`
- Modify: `services/reader/src/test/java/com/stream/reader/controller/StreamControllerTest.java`
- Modify: `services/writer/src/test/java/com/stream/writer/controller/StreamControllerTest.java`
- Modify: `apps/backend/src/main/java/com/stream/backend/service/StreamServiceImpl.java`
- Modify: `apps/backend/src/test/java/com/stream/backend/service/StreamServiceImplTest.java`

**Interfaces:**
- Produces: `Stream.getCreatedAt()`이 `Instant`를 반환. `StreamView`의 4번째 record 컴포넌트가 `Instant createdAt`. `StreamServiceImpl.toModel()`이 `Instant.toString()`으로 `"...Z"` 형태의 String을 만들어 `com.stream.backend.model.Stream`에 넣음(모델 자체는 `String createdAt` 그대로).

- [ ] **Step 1: reader의 StreamControllerTest에 JSON 형식 회귀 테스트를 먼저 추가한다 (RED)**

이것이 이 버그의 진짜 회귀 테스트다 — reader가 내보내는 JSON에 `Z`가 있는지를 직접 검증한다.

`services/reader/src/test/java/com/stream/reader/controller/StreamControllerTest.java`의 `getAll_returns200WithStreamList` 테스트에서, 마지막 `.andExpect(...)` 줄 끝의 세미콜론을 지우고 한 줄을 덧붙인다:

변경 전:
```java
                .andExpect(jsonPath("$[0].location").value("LINESTRING(126.97 37.55, 126.98 37.56)"));
```

변경 후:
```java
                .andExpect(jsonPath("$[0].location").value("LINESTRING(126.97 37.55, 126.98 37.56)"))
                .andExpect(jsonPath("$[0].createdAt").value("2024-01-01T00:00:00Z"));
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `cd services/reader && ./mvnw -q -B test -Dtest=StreamControllerTest`
Expected: FAIL — `JSON path "$[0].createdAt" expected:<2024-01-01T00:00:00Z> but was:<2024-01-01T00:00:00>` (Z가 없는 현재 동작이 그대로 드러난다)

- [ ] **Step 3: Stream 엔티티를 Instant로 바꾼다**

`packages/shared/src/main/java/com/stream/shared/entity/Stream.java`에서 4곳을 바꾼다.

import 줄:
```java
import java.time.LocalDateTime;
```
→
```java
import java.time.Instant;
```

필드 선언:
```java
    @Column(name = "created_at")
    private LocalDateTime createdAt;
```
→
```java
    @Column(name = "created_at")
    private Instant createdAt;
```

`@PrePersist` 본문:
```java
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
```
→
```java
    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
```

getter:
```java
    public LocalDateTime getCreatedAt() { return createdAt; }
```
→
```java
    public Instant getCreatedAt() { return createdAt; }
```

- [ ] **Step 4: StreamView DTO를 Instant로 바꾼다**

`packages/shared/src/main/java/com/stream/shared/dto/StreamView.java` 전체를 아래로 교체:

```java
package com.stream.shared.dto;

import com.stream.shared.entity.Stream;

import java.time.Instant;

public record StreamView(
        Long id,
        String name,
        String location,
        Instant createdAt
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

- [ ] **Step 5: shared의 Stream 관련 테스트 2개를 고친다**

`packages/shared/src/test/java/com/stream/shared/entity/StreamTest.java`:

import 줄 `import java.time.LocalDateTime;` → `import java.time.Instant;`

`onCreate_setsCreatedAt` 테스트 본문을 아래로 교체:
```java
    @Test
    @DisplayName("@PrePersist - onCreate() 호출 시 createdAt이 현재 시각으로 설정된다")
    void onCreate_setsCreatedAt() throws Exception {
        Stream stream = new Stream();
        Instant before = Instant.now().minusSeconds(1);

        Method onCreateMethod = Stream.class.getDeclaredMethod("onCreate");
        onCreateMethod.setAccessible(true);
        onCreateMethod.invoke(stream);

        Instant after = Instant.now().plusSeconds(1);
        assertThat(stream.getCreatedAt()).isNotNull();
        assertThat(stream.getCreatedAt()).isAfterOrEqualTo(before);
        assertThat(stream.getCreatedAt()).isBeforeOrEqualTo(after);
    }
```

`packages/shared/src/test/java/com/stream/shared/dto/StreamViewTest.java`:

import 줄 `import java.time.LocalDateTime;` → `import java.time.Instant;`

`from_mapsAllFieldsFromEntity` 테스트 안의 두 줄을 바꾼다:
```java
        setField(stream, "createdAt", LocalDateTime.of(2024, 1, 1, 0, 0, 0));
```
→
```java
        setField(stream, "createdAt", Instant.parse("2024-01-01T00:00:00Z"));
```

```java
        assertThat(view.createdAt()).isEqualTo(LocalDateTime.of(2024, 1, 1, 0, 0, 0));
```
→
```java
        assertThat(view.createdAt()).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
```

- [ ] **Step 6: shared 테스트 실행 + install**

Run:
```bash
cd packages/shared && ./mvnw -q -B test
cd packages/shared && ./mvnw -q -B install -DskipTests
```
Expected: 전부 PASS (Trail/Capture 관련 테스트는 이 태스크에서 안 건드렸으므로 그대로 통과), install 성공

- [ ] **Step 7: reader/writer의 Stream 컨트롤러 테스트 픽스처를 고친다**

`services/reader/src/test/java/com/stream/reader/controller/StreamControllerTest.java`:

import 줄 `import java.time.LocalDateTime;` → `import java.time.Instant;`

`buildStream` 헬퍼 안의 줄:
```java
        createdAtField.set(stream, LocalDateTime.of(2024, 1, 1, 0, 0, 0));
```
→
```java
        createdAtField.set(stream, Instant.parse("2024-01-01T00:00:00Z"));
```

`services/writer/src/test/java/com/stream/writer/controller/StreamControllerTest.java`:

import 줄 `import java.time.LocalDateTime;` → `import java.time.Instant;`

`create_returns201WithStreamView` 테스트 안의 줄:
```java
        setField(saved, "createdAt", LocalDateTime.of(2024, 1, 1, 0, 0, 0));
```
→
```java
        setField(saved, "createdAt", Instant.parse("2024-01-01T00:00:00Z"));
```

- [ ] **Step 8: reader 테스트 실행 → Step 1의 회귀 테스트가 GREEN으로 바뀌는지 확인**

Run: `cd services/reader && ./mvnw -q -B test -Dtest='!ReaderApplicationTests'`
Expected: PASS 전부. 특히 Step 1에서 추가한 `jsonPath("$[0].createdAt").value("2024-01-01T00:00:00Z")`가 이제 통과한다 — reader가 실제로 `Z`가 붙은 JSON을 내보낸다는 뜻이다.

- [ ] **Step 9: writer 테스트 실행**

Run: `cd services/writer && ./mvnw -q -B test -Dtest='!WriterApplicationTests'`
Expected: PASS 전부

- [ ] **Step 10: backend의 StreamServiceImpl 변환 로직을 고친다**

`apps/backend/src/main/java/com/stream/backend/service/StreamServiceImpl.java`의 `toModel()` 메서드를 아래로 교체:

```java
    private Stream toModel(StreamView view) {
        return new Stream(
                view.id(),
                view.name(),
                view.location(),
                view.createdAt() == null ? null : view.createdAt().toString()
        );
    }
```

(이 파일에는 `DateTimeFormatter` import가 없다 — 인라인 FQN을 쓰고 있었으므로 지울 import가 없다.)

- [ ] **Step 11: backend의 StreamServiceImplTest를 고친다**

`apps/backend/src/test/java/com/stream/backend/service/StreamServiceImplTest.java`:

import 줄 `import java.time.LocalDateTime;` → `import java.time.Instant;`

`StreamView`를 만드는 두 곳(각각 `findAll_mapsReaderResponseToStreamList`, `create_mapsWriterResponseToStream` 테스트 안):
```java
                LocalDateTime.of(2024, 1, 1, 0, 0, 0));
```
→
```java
                Instant.parse("2024-01-01T00:00:00Z"));
```

기대값 어서션 두 곳:
```java
        assertThat(result.get(0).getCreatedAt()).isEqualTo("2024-01-01T00:00:00");
```
→
```java
        assertThat(result.get(0).getCreatedAt()).isEqualTo("2024-01-01T00:00:00Z");
```

```java
        assertThat(result.getCreatedAt()).isEqualTo("2024-01-01T00:00:00");
```
→
```java
        assertThat(result.getCreatedAt()).isEqualTo("2024-01-01T00:00:00Z");
```

- [ ] **Step 12: backend 테스트 실행 → 모순됐던 두 테스트가 이제 같은 값을 기대하는지 확인**

Run: `cd apps/backend && ./mvnw -q -B test -Dtest='!CaptureControllerTest'`
Expected: PASS 전부. `StreamServiceImplTest`(실제 변환 로직)와 `StreamControllerTest`(mock 기반)가 이제 둘 다 `"2024-01-01T00:00:00Z"`를 기대하고 둘 다 통과한다.

- [ ] **Step 13: 커밋**

```bash
git add packages/shared/src/main/java/com/stream/shared/entity/Stream.java packages/shared/src/main/java/com/stream/shared/dto/StreamView.java packages/shared/src/test/java/com/stream/shared/entity/StreamTest.java packages/shared/src/test/java/com/stream/shared/dto/StreamViewTest.java services/reader/src/test/java/com/stream/reader/controller/StreamControllerTest.java services/writer/src/test/java/com/stream/writer/controller/StreamControllerTest.java apps/backend/src/main/java/com/stream/backend/service/StreamServiceImpl.java apps/backend/src/test/java/com/stream/backend/service/StreamServiceImplTest.java
git commit -m "fix(shared,backend): Stream의 created_at을 Instant로 변경 - API 응답에 타임존(Z) 복구"
```

---

## Task 2: Trail 도메인 — createdAt을 Instant로

**Files:**
- Modify: `packages/shared/src/main/java/com/stream/shared/entity/Trail.java`
- Modify: `packages/shared/src/main/java/com/stream/shared/dto/TrailView.java`
- Modify: `packages/shared/src/test/java/com/stream/shared/entity/TrailTest.java`
- Modify: `packages/shared/src/test/java/com/stream/shared/dto/TrailViewTest.java`
- Modify: `services/reader/src/test/java/com/stream/reader/controller/TrailControllerTest.java`
- Modify: `services/writer/src/test/java/com/stream/writer/controller/TrailControllerTest.java`
- Modify: `apps/backend/src/main/java/com/stream/backend/service/TrailServiceImpl.java`
- Modify: `apps/backend/src/test/java/com/stream/backend/service/TrailServiceImplTest.java`

**Interfaces:**
- Produces: `Trail.getCreatedAt()`이 `Instant`를 반환. `TrailView`의 7번째 record 컴포넌트가 `Instant createdAt`. `TrailServiceImpl.toModel()`이 `Instant.toString()`으로 `"...Z"` String 생성.

- [ ] **Step 1: reader의 TrailControllerTest에 JSON 형식 회귀 테스트를 먼저 추가한다 (RED)**

`services/reader/src/test/java/com/stream/reader/controller/TrailControllerTest.java`의 `getAll_returns200WithTrailList` 테스트에서, 마지막 `.andExpect(...)` 줄 끝의 세미콜론을 지우고 한 줄을 덧붙인다:

변경 전:
```java
                .andExpect(jsonPath("$[0].location").value("POINT(126.97 37.55)"));
```

변경 후:
```java
                .andExpect(jsonPath("$[0].location").value("POINT(126.97 37.55)"))
                .andExpect(jsonPath("$[0].createdAt").value("2024-01-01T00:00:00Z"));
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `cd services/reader && ./mvnw -q -B test -Dtest=TrailControllerTest`
Expected: FAIL — `JSON path "$[0].createdAt" expected:<2024-01-01T00:00:00Z> but was:<2024-01-01T00:00:00>`

- [ ] **Step 3: Trail 엔티티를 Instant로 바꾼다**

`packages/shared/src/main/java/com/stream/shared/entity/Trail.java`에서 4곳을 바꾼다.

import 줄:
```java
import java.time.LocalDateTime;
```
→
```java
import java.time.Instant;
```

필드 선언:
```java
    @Column(name = "created_at")
    private LocalDateTime createdAt;
```
→
```java
    @Column(name = "created_at")
    private Instant createdAt;
```

`@PrePersist` 본문:
```java
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
```
→
```java
    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
```

getter:
```java
    public LocalDateTime getCreatedAt() { return createdAt; }
```
→
```java
    public Instant getCreatedAt() { return createdAt; }
```

- [ ] **Step 4: TrailView DTO를 Instant로 바꾼다**

`packages/shared/src/main/java/com/stream/shared/dto/TrailView.java` 전체를 아래로 교체:

```java
package com.stream.shared.dto;

import com.stream.shared.entity.Trail;

import java.time.Instant;

public record TrailView(
        Long id,
        Long streamId,
        String cameraNumber,
        String location,
        String direction,
        String status,
        Instant createdAt
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

- [ ] **Step 5: shared의 Trail 관련 테스트 2개를 고친다**

`packages/shared/src/test/java/com/stream/shared/entity/TrailTest.java`:

import 줄 `import java.time.LocalDateTime;` → `import java.time.Instant;`

`onCreate_setsCreatedAt` 테스트 본문을 아래로 교체:
```java
    @Test
    @DisplayName("@PrePersist - onCreate() 호출 시 createdAt이 현재 시각으로 설정된다")
    void onCreate_setsCreatedAt() throws Exception {
        Trail trail = new Trail();
        Instant before = Instant.now().minusSeconds(1);

        Method onCreateMethod = Trail.class.getDeclaredMethod("onCreate");
        onCreateMethod.setAccessible(true);
        onCreateMethod.invoke(trail);

        Instant after = Instant.now().plusSeconds(1);
        assertThat(trail.getCreatedAt()).isNotNull();
        assertThat(trail.getCreatedAt()).isAfterOrEqualTo(before);
        assertThat(trail.getCreatedAt()).isBeforeOrEqualTo(after);
    }
```

`packages/shared/src/test/java/com/stream/shared/dto/TrailViewTest.java`:

import 줄 `import java.time.LocalDateTime;` → `import java.time.Instant;`

`from_mapsAllFieldsFromEntity` 테스트 안의 두 줄을 바꾼다:
```java
        setField(trail, "createdAt", LocalDateTime.of(2024, 1, 1, 0, 0, 0));
```
→
```java
        setField(trail, "createdAt", Instant.parse("2024-01-01T00:00:00Z"));
```

```java
        assertThat(view.createdAt()).isEqualTo(LocalDateTime.of(2024, 1, 1, 0, 0, 0));
```
→
```java
        assertThat(view.createdAt()).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
```

- [ ] **Step 6: shared 테스트 실행 + install**

Run:
```bash
cd packages/shared && ./mvnw -q -B test
cd packages/shared && ./mvnw -q -B install -DskipTests
```
Expected: 전부 PASS, install 성공

- [ ] **Step 7: reader/writer의 Trail 컨트롤러 테스트 픽스처를 고친다**

`services/reader/src/test/java/com/stream/reader/controller/TrailControllerTest.java`:

import 줄 `import java.time.LocalDateTime;` → `import java.time.Instant;`

`buildTrail` 헬퍼 안의 줄:
```java
        createdAtField.set(trail, LocalDateTime.of(2024, 1, 1, 0, 0, 0));
```
→
```java
        createdAtField.set(trail, Instant.parse("2024-01-01T00:00:00Z"));
```

`services/writer/src/test/java/com/stream/writer/controller/TrailControllerTest.java`:

import 줄 `import java.time.LocalDateTime;` → `import java.time.Instant;`

`create_returns201WithTrailView` 테스트 안의 줄:
```java
        setField(saved, "createdAt", LocalDateTime.of(2024, 1, 1, 0, 0, 0));
```
→
```java
        setField(saved, "createdAt", Instant.parse("2024-01-01T00:00:00Z"));
```

- [ ] **Step 8: reader 테스트 실행 → Step 1의 회귀 테스트가 GREEN으로 바뀌는지 확인**

Run: `cd services/reader && ./mvnw -q -B test -Dtest='!ReaderApplicationTests'`
Expected: PASS 전부. Step 1의 `createdAt` 어서션이 통과한다.

- [ ] **Step 9: writer 테스트 실행**

Run: `cd services/writer && ./mvnw -q -B test -Dtest='!WriterApplicationTests'`
Expected: PASS 전부

- [ ] **Step 10: backend의 TrailServiceImpl 변환 로직을 고치고 미사용 import를 제거한다**

`apps/backend/src/main/java/com/stream/backend/service/TrailServiceImpl.java`에서 두 곳을 바꾼다.

`toModel()` 메서드를 아래로 교체:
```java
    private Trail toModel(TrailView view) {
        return new Trail(
                view.id(),
                view.streamId(),
                view.cameraNumber(),
                view.location(),
                view.direction(),
                view.status(),
                view.createdAt() == null ? null : view.createdAt().toString()
        );
    }
```

그리고 이제 미사용이 된 import 줄을 삭제한다:
```java
import java.time.format.DateTimeFormatter;
```

- [ ] **Step 11: backend의 TrailServiceImplTest를 고친다**

`apps/backend/src/test/java/com/stream/backend/service/TrailServiceImplTest.java`:

import 줄 `import java.time.LocalDateTime;` → `import java.time.Instant;`

`TrailView`를 만드는 세 곳(각각 `findAll_withNullStreamId_mapsReaderResponseToTrailList`, `findAll_withStreamId_callsFilteredEndpoint`, `create_mapsWriterResponseToTrail` 테스트 안):
```java
                LocalDateTime.of(2024, 1, 1, 0, 0, 0));
```
→
```java
                Instant.parse("2024-01-01T00:00:00Z"));
```

기대값 어서션 두 곳:
```java
        assertThat(result.get(0).getCreatedAt()).isEqualTo("2024-01-01T00:00:00");
```
→
```java
        assertThat(result.get(0).getCreatedAt()).isEqualTo("2024-01-01T00:00:00Z");
```

```java
        assertThat(result.getCreatedAt()).isEqualTo("2024-01-01T00:00:00");
```
→
```java
        assertThat(result.getCreatedAt()).isEqualTo("2024-01-01T00:00:00Z");
```

- [ ] **Step 12: backend 테스트 실행**

Run: `cd apps/backend && ./mvnw -q -B test -Dtest='!CaptureControllerTest'`
Expected: PASS 전부. `TrailServiceImplTest`와 `TrailControllerTest`가 이제 둘 다 `"2024-01-01T00:00:00Z"`를 기대하고 둘 다 통과한다.

- [ ] **Step 13: 커밋**

```bash
git add packages/shared/src/main/java/com/stream/shared/entity/Trail.java packages/shared/src/main/java/com/stream/shared/dto/TrailView.java packages/shared/src/test/java/com/stream/shared/entity/TrailTest.java packages/shared/src/test/java/com/stream/shared/dto/TrailViewTest.java services/reader/src/test/java/com/stream/reader/controller/TrailControllerTest.java services/writer/src/test/java/com/stream/writer/controller/TrailControllerTest.java apps/backend/src/main/java/com/stream/backend/service/TrailServiceImpl.java apps/backend/src/test/java/com/stream/backend/service/TrailServiceImplTest.java
git commit -m "fix(shared,backend): Trail의 created_at을 Instant로 변경 - API 응답에 타임존(Z) 복구"
```

---

## Task 3: Capture 도메인 — createdAt을 Instant로

**Files:**
- Modify: `packages/shared/src/main/java/com/stream/shared/entity/Capture.java`
- Modify: `packages/shared/src/main/java/com/stream/shared/dto/CaptureView.java`
- Modify: `packages/shared/src/test/java/com/stream/shared/entity/CaptureTest.java`
- Modify: `packages/shared/src/test/java/com/stream/shared/dto/CaptureViewTest.java`
- Modify: `services/reader/src/test/java/com/stream/reader/controller/CaptureControllerTest.java` (미사용 import 1줄 제거만)

**Interfaces:**
- Produces: `Capture.getCreatedAt()`이 `Instant`를 반환. `CaptureView`의 7번째 record 컴포넌트가 `Instant createdAt`.

**배경**: Capture는 Stream/Trail과 달리 `apps/backend` 쪽 변환 로직이 없다(`CaptureServiceImpl`이 아직 `UnsupportedOperationException`만 던지는 스텁). reader의 `CaptureController`도 `createdAt`을 JSON으로 검증하는 테스트가 없다. 그래서 이 태스크는 `packages/shared` 4개 파일만 건드린다. 다만 writer의 `CaptureCommandHandler`와 reader의 `CaptureController`가 `CaptureView`를 JSON으로 직렬화해 Redis에 넣으므로, **캐시에 저장되는 JSON 형식이 `"2024-01-01T00:00:00"` → `"2024-01-01T00:00:00Z"`로 바뀐다.** 현재 reader는 캐시 히트 시 저장된 문자열을 역직렬화하지 않고 그대로 반환하므로 옛 형식이 남아있어도 예외가 나지는 않는다(코드 변경 불필요).

- [ ] **Step 1: Capture 엔티티를 Instant로 바꾼다**

`packages/shared/src/main/java/com/stream/shared/entity/Capture.java`에서 4곳을 바꾼다.

import 줄:
```java
import java.time.LocalDateTime;
```
→
```java
import java.time.Instant;
```

필드 선언:
```java
    @Column(name = "created_at")
    private LocalDateTime createdAt;
```
→
```java
    @Column(name = "created_at")
    private Instant createdAt;
```

`@PrePersist` 본문:
```java
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
```
→
```java
    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
```

getter:
```java
    public LocalDateTime getCreatedAt() { return createdAt; }
```
→
```java
    public Instant getCreatedAt() { return createdAt; }
```

- [ ] **Step 2: CaptureView DTO를 Instant로 바꾼다**

`packages/shared/src/main/java/com/stream/shared/dto/CaptureView.java` 전체를 아래로 교체:

```java
package com.stream.shared.dto;

import com.stream.shared.entity.Capture;

import java.time.Instant;

public record CaptureView(
        Long id,
        Integer trailId,
        Integer streamId,
        String imagePath,
        String roadStatus,
        Double confidence,
        Instant createdAt
) {
    public static CaptureView from(Capture capture) {
        return new CaptureView(
                capture.getId(),
                capture.getTrailId(),
                capture.getStreamId(),
                capture.getImagePath(),
                capture.getRoadStatus(),
                capture.getConfidence(),
                capture.getCreatedAt()
        );
    }
}
```

- [ ] **Step 3: shared의 Capture 관련 테스트 2개를 고친다**

`packages/shared/src/test/java/com/stream/shared/entity/CaptureTest.java` — 이 파일은 `LocalDateTime`을 **두 개의 서로 다른 테스트에서** 쓰므로 둘 다 고쳐야 한다.

import 줄 `import java.time.LocalDateTime;` → `import java.time.Instant;`

(1) `onCreate_setsCreatedAt` 테스트의 두 줄:
```java
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
```
→
```java
        Instant before = Instant.now().minusSeconds(1);
```

```java
        LocalDateTime after = LocalDateTime.now().plusSeconds(1);
```
→
```java
        Instant after = Instant.now().plusSeconds(1);
```

(2) `onCreate_overwritesCreatedAt` 테스트의 두 줄 (Stream/Trail 엔티티 테스트에는 없고 Capture에만 있는 테스트다):
```java
        LocalDateTime first = capture.getCreatedAt();
```
→
```java
        Instant first = capture.getCreatedAt();
```

```java
        LocalDateTime second = capture.getCreatedAt();
```
→
```java
        Instant second = capture.getCreatedAt();
```

두 테스트의 `assertThat(...).isAfterOrEqualTo(...)` / `.isBeforeOrEqualTo(...)` 어서션은 AssertJ가 `Instant`도 동일하게 지원하므로 그대로 둔다.

`packages/shared/src/test/java/com/stream/shared/dto/CaptureViewTest.java`:

import 줄 `import java.time.LocalDateTime;` → `import java.time.Instant;`

두 줄을 바꾼다:
```java
        setField(capture, "createdAt", LocalDateTime.of(2024, 1, 1, 0, 0, 0));
```
→
```java
        setField(capture, "createdAt", Instant.parse("2024-01-01T00:00:00Z"));
```

```java
        assertThat(view.createdAt()).isEqualTo(LocalDateTime.of(2024, 1, 1, 0, 0, 0));
```
→
```java
        assertThat(view.createdAt()).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
```

- [ ] **Step 4: reader의 CaptureControllerTest에서 미사용 import를 제거한다**

`services/reader/src/test/java/com/stream/reader/controller/CaptureControllerTest.java`의 16번째 줄에 `import java.time.LocalDateTime;`이 있는데, 이 파일 안에서 `LocalDateTime`을 쓰는 곳이 한 군데도 없다(이 계획과 무관하게 원래부터 미사용이던 죽은 import다). 이 줄을 삭제한다:

```java
import java.time.LocalDateTime;
```

이걸 지워야 이 계획의 최종 검증(`grep -rn "LocalDateTime"` → 결과 없음)이 성립한다.

- [ ] **Step 5: shared 테스트 실행 + install**

Run:
```bash
cd packages/shared && ./mvnw -q -B test
cd packages/shared && ./mvnw -q -B install -DskipTests
```
Expected: 전부 PASS, install 성공

- [ ] **Step 6: reader/writer 테스트 실행 → Capture 리포지토리 테스트가 H2에서 여전히 통과하는지 확인**

Run:
```bash
cd services/reader && ./mvnw -q -B test -Dtest='!ReaderApplicationTests'
cd ../writer && ./mvnw -q -B test -Dtest='!WriterApplicationTests'
```
Expected: PASS 전부. 특히 `CaptureRepositoryTest`(reader 10개, writer 8개)가 통과해야 한다 — writer 쪽은 실제로 H2에 INSERT를 수행하므로 `Instant` ↔ H2 `TIMESTAMP` 매핑이 동작한다는 증거다. (계획 수립 시 미리 실험해서 통과를 확인해둔 부분이다.)

- [ ] **Step 7: backend 테스트 실행**

Run: `cd apps/backend && ./mvnw -q -B test -Dtest='!CaptureControllerTest'`
Expected: PASS 전부 (backend는 `CaptureView`를 쓰지 않으므로 영향 없음)

- [ ] **Step 8: 커밋**

```bash
git add packages/shared/src/main/java/com/stream/shared/entity/Capture.java packages/shared/src/main/java/com/stream/shared/dto/CaptureView.java packages/shared/src/test/java/com/stream/shared/entity/CaptureTest.java packages/shared/src/test/java/com/stream/shared/dto/CaptureViewTest.java services/reader/src/test/java/com/stream/reader/controller/CaptureControllerTest.java
git commit -m "fix(shared): Capture의 created_at을 Instant로 변경 - 3개 도메인 타입 통일, 미사용 import 제거"
```

---

## Task 4: DB 스키마를 TIMESTAMPTZ로 + ERD 문서 동기화

**Files:**
- Modify: `infra/scripts/init-db.sql`
- Modify: `docs/diagrams/erd.md`

**Interfaces:**
- 이 태스크는 자바 코드를 건드리지 않는다. Postgres 초기화 스크립트의 컬럼 타입만 바꾸므로 기존 테스트에는 영향이 없다(테스트는 H2를 쓰고, H2 스키마는 `services/{reader,writer}/src/test/resources/schema.sql`에 따로 있으며 Task 3에서 확인했듯 변경 불필요).

**배경**: `Instant`는 시점(instant)을 나타내는 타입인데 `TIMESTAMP`(= `TIMESTAMP WITHOUT TIME ZONE`) 컬럼은 타임존 정보를 보관하지 않는다. `TIMESTAMPTZ`로 바꾸면 Postgres가 타임존을 인식해서 `Instant`와 의미가 정확히 맞고, Hibernate 쪽 추가 설정도 필요 없다.

- [ ] **Step 1: init-db.sql의 timestamp 컬럼 3개 + 트리거 함수를 확인한다**

Run: `grep -n "TIMESTAMP" infra/scripts/init-db.sql`
Expected: 4곳이 나온다 — `streams.created_at`(9행 부근), `trails.created_at`(20행 부근), `captures.created_at`(32행 부근), `captures.updated_at`(33행 부근). 정확한 행 번호를 확인하고 다음 단계로 간다.

- [ ] **Step 2: 네 개 컬럼을 TIMESTAMPTZ로 바꾼다**

`infra/scripts/init-db.sql`에서 아래 네 줄을 각각 바꾼다.

`streams` 테이블:
```sql
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
```
→
```sql
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
```

`trails` 테이블:
```sql
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
```
→
```sql
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
```

`captures` 테이블의 두 줄:
```sql
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
```
→
```sql
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
```

`captures.updated_at`을 자동 갱신하는 트리거 함수(`update_updated_at_column()`)는 `NEW.updated_at = CURRENT_TIMESTAMP;`를 그대로 쓰면 되므로 **수정하지 않는다** — `CURRENT_TIMESTAMP`는 Postgres에서 원래 `timestamptz`를 반환하므로 컬럼 타입과 오히려 더 잘 맞게 된다.

- [ ] **Step 3: SQL 문법이 깨지지 않았는지 확인한다**

Run: `grep -n "TIMESTAMP" infra/scripts/init-db.sql`
Expected: `TIMESTAMPTZ` 4곳 + 트리거 함수 안의 `CURRENT_TIMESTAMP` 1곳이 보이고, 남아있는 맨 `TIMESTAMP` 컬럼 정의는 없어야 한다.

- [ ] **Step 4: ERD 문서를 스키마와 동기화한다**

`docs/diagrams/erd.md`의 mermaid 블록에서 세 테이블의 timestamp 컬럼 타입 표기를 바꾼다.

`streams` 블록:
```
        timestamp   created_at     "DEFAULT CURRENT_TIMESTAMP"
```
→
```
        timestamptz created_at     "DEFAULT CURRENT_TIMESTAMP (타임존 포함, 엔티티는 Instant)"
```

`trails` 블록:
```
        timestamp   created_at       "DEFAULT CURRENT_TIMESTAMP"
```
→
```
        timestamptz created_at       "DEFAULT CURRENT_TIMESTAMP (타임존 포함, 엔티티는 Instant)"
```

`captures` 블록의 두 줄:
```
        timestamp   created_at     "DEFAULT CURRENT_TIMESTAMP"
        timestamp   updated_at     "DEFAULT CURRENT_TIMESTAMP, 트리거로 자동 갱신"
```
→
```
        timestamptz created_at     "DEFAULT CURRENT_TIMESTAMP (타임존 포함, 엔티티는 Instant)"
        timestamptz updated_at     "DEFAULT CURRENT_TIMESTAMP, 트리거로 자동 갱신"
```

- [ ] **Step 5: ERD 문서의 "설계 노트" 섹션에 타임존 관련 항목을 추가한다**

`docs/diagrams/erd.md`의 `## 설계 노트` 섹션 맨 끝(마지막 `-` 항목 다음 줄)에 추가:

```markdown
- **timestamp 컬럼은 `TIMESTAMPTZ`** — 자바 엔티티의 `createdAt`이 `Instant`(시점 타입)이므로, 타임존 정보를 보관하지 않는 `TIMESTAMP`가 아니라 `TIMESTAMPTZ`를 쓴다. 이렇게 해야 API 응답의 `created_at`이 `2024-01-01T00:00:00Z`처럼 타임존이 명시된 ISO-8601 형식으로 나간다.
```

- [ ] **Step 6: 전체 모듈 테스트 실행 → 스키마 변경이 자바 테스트에 영향 없는지 확인**

Run:
```bash
cd packages/shared && ./mvnw -q -B test
cd ../../services/reader && ./mvnw -q -B test -Dtest='!ReaderApplicationTests'
cd ../writer && ./mvnw -q -B test -Dtest='!WriterApplicationTests'
cd ../../apps/backend && ./mvnw -q -B test -Dtest='!CaptureControllerTest'
```
Expected: 4개 모듈 전부 GREEN (테스트는 H2를 쓰므로 Postgres 스키마 변경의 영향을 받지 않는다)

- [ ] **Step 7: 커밋**

```bash
git add infra/scripts/init-db.sql docs/diagrams/erd.md
git commit -m "fix(infra): created_at/updated_at 컬럼을 TIMESTAMPTZ로 변경 - Instant 타입과 의미 일치, ERD 문서 동기화"
```

---

## 후속 작업 (이 계획엔 포함 안 함, 참고용)

- **Docker 실기동 검증**: 이 계획의 핵심 변경(`TIMESTAMPTZ` 컬럼 + `Instant` 매핑)은 실제 Postgres에 붙어봐야 최종 확인된다. 이 세션 환경엔 Docker 데몬이 없어 H2 기반 테스트로만 검증했다. `docker compose up` 후 `POST /api/streams` → `GET /api/streams` 해서 응답의 `created_at`에 `Z`가 붙어있는지 확인 필요. **`infra/scripts/init-db.sql`은 Postgres 컨테이너의 데이터 볼륨이 비어있을 때만 실행되므로, 기존 볼륨이 있다면 지우고 다시 띄워야 새 스키마가 적용된다.**
- **`apps/backend`의 Stream 에러 메시지 불일치**: 이전 계획(`2026-08-21-writer-validation-fixes.md`)의 후속 항목으로 이미 기록되어 있음. `GlobalExceptionHandler`의 `"Invalid stream geometry"`가 이제 부정확하다.
- **`CaptureCommandHandler`의 필수 필드 검증 누락**: 역시 이전 계획의 후속 항목.
- **Redis 캐시 TTL 없음**: `capture:latest:trail:{id}` 키에 만료가 없어서 옛 형식(`Z` 없는) JSON이 무한정 남는다. 지금은 reader가 캐시 값을 역직렬화하지 않아 문제가 안 되지만, 캐시 처리를 개선할 때 같이 볼 것.
- **reader JSON → backend DTO 역직렬화 경로에 테스트 없음** — `apps/backend`의 `StreamServiceImplTest`/`TrailServiceImplTest`는 `RestClient`를 mock해서 이미 만들어진 `StreamView`/`TrailView` 객체를 돌려주므로, `"...Z"` 문자열이 실제로 `Instant`로 역직렬화되는지는 검증되지 않습니다. 동작 자체는 Jackson 기본 설정으로 보장되고 이 브랜치 이전에도 마찬가지로 미검증이었지만, `MockRestServiceServer`나 간단한 `objectMapper.readValue(json, StreamView.class)` 왕복 검증으로 마지막 고리를 닫을 수 있습니다.
- **H2 테스트 스키마와 Postgres 스키마의 타입 불일치** — 테스트용 `services/{reader,writer}/src/test/resources/schema.sql`은 `created_at TIMESTAMP`인데 운영은 이제 `TIMESTAMPTZ`입니다. 현재 이 경로를 건드리는 유일한 테스트(`CaptureRepositoryTest`)가 `isNotNull()`만 검증해서, 타임존이 어긋나도 통과합니다. H2도 `TIMESTAMP WITH TIME ZONE`을 지원하므로 두 파일을 한 단어씩 고치고 실제 왕복 값을 검증하도록 보강하면 운영과 같은 매핑을 테스트하게 됩니다.
- **API 명세서의 `created_at` 예시에 초 단위 미만 정밀도 언급 없음** — Java 21의 `Instant.now()`는 마이크로초 정밀도를 내므로 실제 응답은 `"2026-08-22T05:59:33.262145Z"` 같은 형태가 됩니다. `docs/api-specs/stream-walkway.postman_collection.json`의 예시는 `"2024-01-01T00:00:00Z"`라 길이가 다릅니다. 둘 다 유효한 ISO-8601이고 `Z`는 항상 붙으므로 깨지는 건 없지만, 문자열을 정확히 매칭하는 소비자를 위해 명세서에 한 줄 적어두면 좋습니다.
- **Capture 게이트웨이 구현 시 마주칠 shape 불일치 2건** — `CaptureView`에는 `updatedAt`이 없는데 `apps/backend`의 `model/Capture.java`는 `@JsonProperty("updated_at") String updatedAt`을 갖고 있고 Postman 명세도 이를 기대합니다. 또 `CaptureView`는 `Integer trailId/streamId`인데 `model/Capture`는 `Long`입니다. 둘 다 이 계획 이전부터 있던 갭이지만, Capture 게이트웨이를 만들 때 바로 부딪히는 지점이라 기록해 둡니다.

## 최종 검증

- [ ] **4개 모듈 전체 테스트 재실행**

```bash
cd packages/shared && ./mvnw -q -B test && ./mvnw -q -B install -DskipTests
cd ../../services/reader && ./mvnw -q -B test -Dtest='!ReaderApplicationTests'
cd ../writer && ./mvnw -q -B test -Dtest='!WriterApplicationTests'
cd ../../apps/backend && ./mvnw -q -B test -Dtest='!CaptureControllerTest'
```

Expected: 4개 모듈 전부 GREEN.

- [ ] **`LocalDateTime` 잔존 여부 확인**

Run: `grep -rn "LocalDateTime" --include="*.java" packages/shared services/reader services/writer apps/backend`
Expected: 결과 없음. 이 계획이 끝나면 네 모듈 어디에도 `LocalDateTime`이 남아있지 않아야 한다.
