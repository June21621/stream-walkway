# Capture 조회 경로 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /api/captures`와 `GET /api/captures/{id}`를 동작시켜 파이프라인의 출구를 연다. backend RED 5개를 GREEN으로 전환한다.

**Architecture:** Stream/Trail과 같은 CQRS 읽기 경로다. reader가 PostgreSQL을 조회하고, backend 게이트웨이가 `RestClient`로 reader를 호출해 외부 API 형태(snake_case)로 변환한다. **캡처 생성 경로는 건드리지 않는다** — 캡처는 Kafka `image.analyzed` 이벤트로만 생기며 이 계획은 읽기 전용이다.

**Tech Stack:** Spring Boot 3.5.x, Java 21, Spring Data JPA, RestClient (모두 이미 사용 중)

**Spec:** `apps/backend/src/test/java/com/stream/backend/controller/CaptureControllerTest.java` (RED 5개가 계약이다) + `docs/api-specs/stream-walkway.postman_collection.json`의 "Backend Gateway :8080 / Captures"

## Global Constraints

- **브랜치 2개로 나눈다.** DB 접근 계층과 게이트웨이 계층을 따로 검토·머지하는 이 저장소의 표준 워크플로우를 따른다.
  - Phase 1 `feature/capture-reader` (Task 1~3) — `packages/shared` + `services/reader`
  - Phase 2 `feature/capture-gateway` (Task 4~6) — `apps/backend`. **Phase 1이 main에 머지된 뒤 시작한다**
- 테스트는 항상 `clean` 포함으로 돌린다: `./services/writer/mvnw -o clean test -fae` (삭제된 소스의 `.class`가 `target/`에 남아 개수가 부풀려진 전례가 있다)
- **모듈 단독 실행에는 반드시 `-am`을 붙인다.** `-pl services/reader`만 쓰면 `shared`를 로컬 Maven 저장소의 설치본에서 가져오는데, 이 계획은 Task 1에서 `shared`를 수정하므로 옛 버전이 잡혀 `NoSuchFieldException: updatedAt`으로 죽는다(Task 2 실행 중 실제로 겪었다). `-am`은 의존 모듈을 같은 리액터에서 함께 빌드한다. `-Dtest=`와 함께 쓰면 다른 모듈에 해당 테스트가 없어 실패하므로 `-Dsurefire.failIfNoSpecifiedTests=false`도 같이 붙인다
- 기준선(2026-08-27 실측): shared 30/30, reader 32/33, writer 85/86, backend 31/36. **reader/writer의 실패 1개씩은 `ApplicationTests.contextLoads`로 이 작업과 무관하다.** 이 숫자가 늘어나면 회귀다
- 커밋 메시지는 한글로, 무엇을/왜 했는지 포함
- Docker는 Phase 1 Task 1에서 한 번 필요하다(Testcontainers). 없으면 해당 테스트가 스킵된다

---

## File Structure

| 파일 | 책임 | 작업 |
|---|---|---|
| `packages/shared/.../entity/Capture.java` | JPA 엔티티 | `updatedAt` 필드 추가 |
| `packages/shared/.../dto/CaptureView.java` | 조회용 DTO | `updatedAt` 컴포넌트 추가 |
| `services/reader/.../repository/CaptureRepository.java` | 조회 쿼리 | 필터 쿼리 1개 추가 |
| `services/reader/.../controller/CaptureController.java` | reader 엔드포인트 | `GET /captures/{id}` 추가, `/captures`에 필터 |
| `apps/backend/.../service/CaptureServiceImpl.java` | 게이트웨이 호출 | 스텁 → 구현 |
| `apps/backend/.../controller/CaptureController.java` | 외부 API | 스텁 → 구현 |
| `apps/backend/.../exception/CaptureNotFoundException.java` | 404 매핑 | 신규 |
| `apps/backend/.../exception/GlobalExceptionHandler.java` | 404 본문 | 핸들러 1개 추가 |

---

## 사전 확인 사실

구현 전에 확인한 것들이다. 추측이 아니다.

1. **`updated_at`이 엔티티에 없다.** DB `captures` 테이블에는 `updated_at TIMESTAMPTZ`와 자동 갱신 트리거가 있는데, `shared.entity.Capture`에도 `CaptureView`에도 필드가 없다. 반면 backend 테스트는 `$[0].updated_at`을 단언한다. **Task 1이 이걸 메운다.**
2. **`Integer` vs `Long`은 손대지 않는다.** `Capture.trailId`/`streamId`는 `Integer`인데 `Trail.id`/`Stream.id`는 `Long`이다. 다만 DB 컬럼이 `captures.trail_id INTEGER`(참조 대상 `trails.id`가 `SERIAL`=int4)라 **엔티티 쪽이 오히려 DB와 정확히 맞다.** backend 모델은 `Long`을 쓰지만 JSON 숫자는 타입이 없어 경계에서 그냥 바인딩된다. 바꾸면 8개 파일과 테스트가 딸려오는데 얻는 게 개념적 일관성뿐이라 이번 범위 밖으로 둔다.
3. **reader가 지금 제공하는 것은 `GET /captures`(전체)와 `GET /captures/trail/{trailId}/latest` 둘뿐이다.** `{id}` 단건도, 필터 파라미터도 없다.
4. **backend 테스트가 요구하는 기본값:** `findAll(null, null, 20, "created_at")` — `limit` 기본 20, `sort` 기본 `created_at`. 컨트롤러가 이 기본값을 채워 서비스에 넘겨야 한다.
5. **기존 shared 테스트는 필드 개수를 단언하지 않는다.** `CaptureViewTest`는 `from()`으로 만들어 개별 필드만 확인하므로 컴포넌트를 추가해도 컴파일이 깨지지 않는다.

---

# Phase 1 — reader 계층 (`feature/capture-reader`)

```bash
git checkout main && git checkout -b feature/capture-reader
```

### Task 1: `shared`의 Capture에 `updatedAt` 추가

**Files:**
- Modify: `packages/shared/src/main/java/com/stream/shared/entity/Capture.java`
- Modify: `packages/shared/src/main/java/com/stream/shared/dto/CaptureView.java`
- Test: `packages/shared/src/test/java/com/stream/shared/entity/CaptureTest.java`, `packages/shared/src/test/java/com/stream/shared/dto/CaptureViewTest.java`

**Interfaces:**
- Produces: `CaptureView`가 8번째 컴포넌트 `Instant updatedAt`을 갖는다. Task 2·3과 Phase 2가 이 값을 그대로 흘려보낸다
- Produces: `Capture.getUpdatedAt()` → `Instant`

`createdAt`과 같은 방식으로 `@PrePersist`에서 채운다. DB에 `DEFAULT CURRENT_TIMESTAMP`가 있지만, 그 값은 `save()` 직후 엔티티에 반영되지 않아 `CaptureView.from(saved)`이 null을 담게 된다 — `createdAt`이 이미 같은 이유로 `@PrePersist`를 쓰고 있다.

캡처는 애플리케이션이 수정하지 않으므로 `@PreUpdate`는 두지 않는다. DB 트리거가 이미 갱신을 담당하고, 앱에는 UPDATE 경로 자체가 없다.

- [ ] **Step 1: 실패하는 테스트를 먼저 쓴다**

`packages/shared/src/test/java/com/stream/shared/entity/CaptureTest.java`에 추가한다.

```java
    @Test
    @DisplayName("@PrePersist - onCreate() 호출 시 updatedAt이 createdAt과 같은 값으로 설정된다")
    void onCreate_setsUpdatedAtEqualToCreatedAt() throws Exception {
        Capture capture = new Capture();

        java.lang.reflect.Method onCreate = Capture.class.getDeclaredMethod("onCreate");
        onCreate.setAccessible(true);
        onCreate.invoke(capture);

        assertThat(capture.getUpdatedAt()).isNotNull();
        assertThat(capture.getUpdatedAt()).isEqualTo(capture.getCreatedAt());
    }
```

`packages/shared/src/test/java/com/stream/shared/dto/CaptureViewTest.java`의 `from_mapsAllFieldsFromEntity`에 두 줄을 더한다. `setField(...)` 블록 끝과 단언 블록 끝에 각각 넣는다.

```java
        setField(capture, "updatedAt", Instant.parse("2024-01-02T00:00:00Z"));
```

```java
        assertThat(view.updatedAt()).isEqualTo(Instant.parse("2024-01-02T00:00:00Z"));
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./services/writer/mvnw -o clean test -pl packages/shared`

Expected: 컴파일 실패. `cannot find symbol: method getUpdatedAt()`, `cannot find symbol: method updatedAt()`. 필드가 아직 없기 때문이다.

- [ ] **Step 3: 엔티티에 필드를 추가한다**

`Capture.java`의 `createdAt` 선언 바로 아래에 넣고, `onCreate()`와 getter를 고친다.

```java
    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        // 캡처는 앱이 수정하지 않으므로 @PreUpdate를 두지 않는다. DB 트리거
        // (update_captures_updated_at)가 갱신을 담당한다. 여기서 값을 채우는
        // 이유는 DB DEFAULT가 save() 직후 엔티티에 반영되지 않아
        // CaptureView.from(saved)이 null을 담게 되기 때문이다 - createdAt과 같은 사정이다.
        updatedAt = createdAt;
    }
```

getter는 `getCreatedAt()` 아래에 둔다.

```java
    public Instant getUpdatedAt() { return updatedAt; }
```

- [ ] **Step 4: DTO에 컴포넌트를 추가한다**

`CaptureView.java`의 record 헤더와 `from()`을 고친다.

```java
public record CaptureView(
        Long id,
        Integer trailId,
        Integer streamId,
        String imagePath,
        String roadStatus,
        Double confidence,
        Instant createdAt,
        Instant updatedAt
) {
    public static CaptureView from(Capture capture) {
        return new CaptureView(
                capture.getId(),
                capture.getTrailId(),
                capture.getStreamId(),
                capture.getImagePath(),
                capture.getRoadStatus(),
                capture.getConfidence(),
                capture.getCreatedAt(),
                capture.getUpdatedAt()
        );
    }
}
```

- [ ] **Step 5: 통과를 확인한다**

Run: `./services/writer/mvnw -o clean test -pl packages/shared`

Expected: 32개 통과 (기존 30 + 신규 2), 실패 0

- [ ] **Step 6: 전체 스위트로 회귀를 확인한다**

Run: `./services/writer/mvnw -o clean test -fae`

Expected: shared 32/32, reader 32/33, writer 85/86, backend 31/36. **reader와 writer의 실패가 각각 1개를 넘으면 회귀다** — `CaptureView`를 쓰는 곳(writer의 Redis 캐싱, reader의 캐시 재적재)이 깨졌는지 확인한다.

- [ ] **Step 7: 커밋**

```bash
git add packages/shared
git commit -m "feat(shared): Capture 엔티티와 CaptureView에 updatedAt 추가

DB captures 테이블에는 updated_at 컬럼과 자동 갱신 트리거가 있는데
엔티티에도 DTO에도 대응 필드가 없었다. backend의 CaptureControllerTest가
응답에 updated_at을 요구하므로 조회 경로를 뚫으려면 먼저 필요하다.

createdAt과 같이 @PrePersist에서 채운다. DB DEFAULT는 save() 직후
엔티티에 반영되지 않아 CaptureView.from(saved)이 null을 담게 되는데,
createdAt이 이미 같은 이유로 @PrePersist를 쓰고 있다.

@PreUpdate는 두지 않는다. 캡처는 애플리케이션이 수정하지 않고 앱에
UPDATE 경로 자체가 없으며, DB 트리거가 이미 갱신을 담당한다.

주의: Redis에 이미 캐싱된 CaptureView JSON에는 updatedAt이 없다.
캐시 미스 시 재적재되므로 시간이 지나면 해소되지만, 즉시 맞추려면
capture:latest:trail:* 키를 비워야 한다."
```

---

### Task 2: reader에 `GET /captures/{id}` 추가

**Files:**
- Modify: `services/reader/src/main/java/com/stream/reader/controller/CaptureController.java`
- Test: `services/reader/src/test/java/com/stream/reader/controller/CaptureControllerTest.java`

**Interfaces:**
- Consumes: Task 1의 `CaptureView`(8 컴포넌트)
- Produces: `GET /captures/{id}` → 200 + `CaptureView` JSON, 없으면 404 (본문 없음). Phase 2의 `CaptureServiceImpl.findById`가 이걸 호출한다

reader의 다른 단건 조회(`StreamController.getById`, `TrailController.getById`)와 같은 형태로 만든다. **본문 없는 404**다 — 에러 본문 조립은 게이트웨이 책임이고, backend가 `HttpClientErrorException.NotFound`를 잡아 `Optional.empty()`로 바꾼다.

- [ ] **Step 1: 실패하는 테스트를 먼저 쓴다**

`services/reader/src/test/java/com/stream/reader/controller/CaptureControllerTest.java`에 추가한다. 기존 파일의 `@MockBean CaptureRepository captureRepository` 등 설정을 그대로 쓴다.

```java
    @Test
    @DisplayName("GET /captures/{id} - 존재하는 캡처를 200과 CaptureView로 반환한다")
    void getById_returns200WhenFound() throws Exception {
        Capture capture = new Capture();
        setField(capture, "id", 7L);
        capture.setTrailId(1);
        capture.setStreamId(1);
        capture.setImagePath("/images/capture_007.jpg");
        capture.setRoadStatus("양호");
        capture.setConfidence(0.95);
        setField(capture, "createdAt", Instant.parse("2024-01-01T00:00:00Z"));
        setField(capture, "updatedAt", Instant.parse("2024-01-02T00:00:00Z"));
        given(captureRepository.findById(7L)).willReturn(Optional.of(capture));

        mockMvc.perform(get("/captures/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.imagePath").value("/images/capture_007.jpg"))
                .andExpect(jsonPath("$.updatedAt").value("2024-01-02T00:00:00Z"));
    }

    @Test
    @DisplayName("GET /captures/{id} - 없으면 404를 반환한다")
    void getById_returns404WhenNotFound() throws Exception {
        given(captureRepository.findById(999L)).willReturn(Optional.empty());

        mockMvc.perform(get("/captures/999"))
                .andExpect(status().isNotFound());
    }
```

`setField` 헬퍼가 이 파일에 없으면 `CaptureViewTest`와 같은 것을 클래스 안에 추가한다.

```java
    private void setField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./services/writer/mvnw -o clean test -pl services/reader -am -Dtest=CaptureControllerTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: 신규 2개 FAIL. `getById_returns200WhenFound`는 `Status expected:<200> but was:<404>` (매핑이 없어 Spring이 404를 낸다), `getById_returns404WhenNotFound`는 우연히 PASS할 수 있다 — 매핑이 없어도 404가 나오기 때문이다. **그래서 200 테스트가 이 태스크의 진짜 RED다.**

- [ ] **Step 3: 엔드포인트를 추가한다**

`CaptureController.java`의 `getAll()` 아래, `getLatestByTrail()` 위에 넣는다.

```java
    // ─────────────────────────────────────────
    // 단건 조회 (PostgreSQL)
    // 본문 없는 404를 낸다 - 에러 본문 조립은 게이트웨이(backend) 책임이다.
    // ─────────────────────────────────────────
    @GetMapping("/{id}")
    public ResponseEntity<CaptureView> getById(@PathVariable Long id) {
        return captureRepository.findById(id)
                .map(CaptureView::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
```

`ResponseEntity` import가 없으면 추가한다: `import org.springframework.http.ResponseEntity;`

- [ ] **Step 4: 통과를 확인한다**

Run: `./services/writer/mvnw -o clean test -pl services/reader -am -Dtest=CaptureControllerTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: 11개 통과 (기존 9 + 신규 2), 실패 0

- [ ] **Step 5: 커밋**

```bash
git add services/reader
git commit -m "feat(reader): GET /captures/{id} 단건 조회 추가

reader가 제공하던 것은 GET /captures(전체)와
GET /captures/trail/{trailId}/latest 둘뿐이라 단건 조회가 없었다.
backend 게이트웨이의 GET /api/captures/{id}가 이걸 필요로 한다.

StreamController/TrailController의 getById와 같은 형태로,
본문 없는 404를 낸다. 에러 본문({\"error\": ..., \"id\": ...}) 조립은
게이트웨이 책임이며 backend가 NotFound를 잡아 Optional.empty()로 바꾼다."
```

---

### Task 3: reader `/captures`에 필터·정렬·개수 제한 추가

**Files:**
- Modify: `services/reader/src/main/java/com/stream/reader/repository/CaptureRepository.java`
- Modify: `services/reader/src/main/java/com/stream/reader/controller/CaptureController.java`
- Test: `services/reader/src/test/java/com/stream/reader/controller/CaptureControllerTest.java`

**Interfaces:**
- Consumes: Task 1의 `CaptureView`
- Produces: `GET /captures?stream_id=&trail_id=&limit=&sort=` → 200 + `List<CaptureView>`. Phase 2의 `CaptureServiceImpl.findAll`이 이 쿼리 문자열을 그대로 만든다

네 가지 조합(필터 없음 / stream만 / trail만 / 둘 다)을 파생 쿼리 4개로 만들지 않는다. nullable 파라미터를 받는 `@Query` 하나로 전부 처리한다.

정렬은 `created_at` 하나만 허용한다. API 명세에 그것뿐이고, 임의 문자열을 `Sort.by()`에 그대로 넘기면 엔티티에 없는 속성명일 때 500이 난다. 화이트리스트에 없으면 400을 낸다. 순서는 **최신순(DESC)** 이다 — 캡처는 최근 관측이 먼저 보여야 한다.

- [ ] **Step 1: 실패하는 테스트를 먼저 쓴다**

```java
    @Test
    @DisplayName("GET /captures?trail_id=1&limit=2 - 필터와 개수 제한이 리포지토리에 전달된다")
    void getAll_passesFilterAndLimitToRepository() throws Exception {
        given(captureRepository.findFiltered(eq(null), eq(1), any(Pageable.class)))
                .willReturn(List.of());

        mockMvc.perform(get("/captures").param("trail_id", "1").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(captureRepository).findFiltered(eq(null), eq(1), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(2);
        assertThat(captor.getValue().getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("GET /captures - 파라미터가 없으면 limit 20, created_at 정렬이 기본값이다")
    void getAll_usesDefaultLimitAndSort() throws Exception {
        given(captureRepository.findFiltered(eq(null), eq(null), any(Pageable.class)))
                .willReturn(List.of());

        mockMvc.perform(get("/captures")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(captureRepository).findFiltered(eq(null), eq(null), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
        assertThat(captor.getValue().getSort().getOrderFor("createdAt")).isNotNull();
    }

    @Test
    @DisplayName("GET /captures?sort=drop_table - 허용하지 않는 정렬 키는 400을 반환한다")
    void getAll_returns400OnUnknownSortKey() throws Exception {
        mockMvc.perform(get("/captures").param("sort", "drop_table"))
                .andExpect(status().isBadRequest());
    }
```

필요한 import: `org.mockito.ArgumentCaptor`, `org.springframework.data.domain.Pageable`, `org.springframework.data.domain.Sort`, `static org.mockito.ArgumentMatchers.any`, `eq`, `static org.mockito.Mockito.verify`, `static org.assertj.core.api.Assertions.assertThat`.

- [ ] **Step 2: 실패를 확인한다**

Run: `./services/writer/mvnw -o clean test -pl services/reader -am -Dtest=CaptureControllerTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: 컴파일 실패. `cannot find symbol: method findFiltered(...)`.

- [ ] **Step 3: 리포지토리에 필터 쿼리를 추가한다**

`CaptureRepository.java`에 넣는다.

```java
    // 필터 조합 네 가지(없음/stream만/trail만/둘 다)를 파생 쿼리 4개로 만드는 대신
    // nullable 파라미터 하나로 처리한다. 정렬과 개수 제한은 Pageable이 담당한다.
    @Query("SELECT c FROM Capture c "
            + "WHERE (:streamId IS NULL OR c.streamId = :streamId) "
            + "AND (:trailId IS NULL OR c.trailId = :trailId)")
    List<Capture> findFiltered(@Param("streamId") Integer streamId,
                               @Param("trailId") Integer trailId,
                               Pageable pageable);
```

import 추가: `org.springframework.data.jpa.repository.Query`, `org.springframework.data.repository.query.Param`, `org.springframework.data.domain.Pageable`.

- [ ] **Step 4: 컨트롤러의 `getAll()`을 교체한다**

```java
    // 정렬 키 화이트리스트. API 명세에 created_at 하나뿐이고, 임의 문자열을
    // Sort.by()에 그대로 넘기면 엔티티에 없는 속성명일 때 500이 난다.
    private static final Map<String, String> SORT_KEYS = Map.of("created_at", "createdAt");

    private static final int DEFAULT_LIMIT = 20;

    @GetMapping
    public List<CaptureView> getAll(
            @RequestParam(value = "stream_id", required = false) Integer streamId,
            @RequestParam(value = "trail_id", required = false) Integer trailId,
            @RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit,
            @RequestParam(value = "sort", required = false, defaultValue = "created_at") String sort) {

        String property = SORT_KEYS.get(sort);
        if (property == null) {
            throw new IllegalArgumentException(
                    "Unsupported sort key: " + sort + " (supported: " + SORT_KEYS.keySet() + ")");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }

        // 최신순. 캡처는 최근 관측이 먼저 보여야 한다.
        Pageable pageable = PageRequest.of(0, limit, Sort.by(property).descending());

        return captureRepository.findFiltered(streamId, trailId, pageable).stream()
                .map(CaptureView::from)
                .toList();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleInvalidQuery(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
```

import 추가: `org.springframework.data.domain.PageRequest`, `Pageable`, `Sort`, `org.springframework.web.bind.annotation.ExceptionHandler`. `Map`은 이미 import돼 있다.

- [ ] **Step 5: 통과를 확인한다**

Run: `./services/writer/mvnw -o clean test -pl services/reader -am -Dtest=CaptureControllerTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: 14개 통과 (기존 9 + Task 2의 2 + 신규 3), 실패 0

- [ ] **Step 6: 전체 스위트로 회귀를 확인한다**

Run: `./services/writer/mvnw -o clean test -fae`

Expected: shared 32/32, reader 37/38, writer 85/86, backend 31/36

- [ ] **Step 7: 커밋**

```bash
git add services/reader
git commit -m "feat(reader): GET /captures에 필터/정렬/개수 제한 추가

backend 게이트웨이가 stream_id, trail_id, limit, sort를 받는데 reader에는
전체 조회밖에 없었다.

필터 조합 네 가지(없음/stream만/trail만/둘 다)를 파생 쿼리 4개로 만드는
대신 nullable 파라미터를 받는 @Query 하나로 처리한다. 정렬과 개수 제한은
Pageable이 담당한다.

정렬 키는 화이트리스트로 막는다. API 명세에 created_at 하나뿐인데 임의
문자열을 Sort.by()에 그대로 넘기면 엔티티에 없는 속성명일 때 500이 난다.
허용하지 않는 키와 0 이하 limit은 400으로 거부한다.

정렬 순서는 최신순(DESC)이다. 캡처는 최근 관측이 먼저 보여야 한다."
```

---

**Phase 1 완료 조건:** `feature/capture-reader`를 main에 머지하고 push한다. Phase 2는 그 다음이다. 머지 전에 diff를 파일별로(엔티티/DTO → 리포지토리 → 컨트롤러 순서) 검토한다.

---

# Phase 2 — backend 게이트웨이 (`feature/capture-gateway`)

```bash
git checkout main && git pull && git checkout -b feature/capture-gateway
```

### Task 4: `CaptureServiceImpl` 구현

**Files:**
- Modify: `apps/backend/src/main/java/com/stream/backend/service/CaptureServiceImpl.java`
- Create: `apps/backend/src/test/java/com/stream/backend/service/CaptureServiceImplTest.java`

**Interfaces:**
- Consumes: Phase 1의 reader `GET /captures?stream_id=&trail_id=&limit=&sort=`와 `GET /captures/{id}`
- Produces: `CaptureService.findAll(Long streamId, Long trailId, Integer limit, String sort)` → `List<Capture>`, `findById(Long)` → `Optional<Capture>`

`TrailServiceImpl`과 같은 구조다. `readerRestClient`만 쓴다 — **캡처 생성 경로가 없으므로 `writerRestClient`는 주입하지 않는다.**

`toModel`에서 `Instant` → `String` 변환은 `TrailServiceImpl`과 같이 `.toString()`을 쓴다. `Instant.toString()`이 ISO-8601에 `Z`를 붙인다.

- [ ] **Step 1: 실패하는 테스트를 먼저 쓴다**

새 파일 `apps/backend/src/test/java/com/stream/backend/service/CaptureServiceImplTest.java`.

```java
package com.stream.backend.service;

import com.stream.backend.model.Capture;
import com.stream.shared.dto.CaptureView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Backend - CaptureServiceImpl 테스트")
class CaptureServiceImplTest {

    private static CaptureView view() {
        return new CaptureView(1L, 2, 3, "/images/capture_001.jpg", "양호", 0.95,
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-01-02T00:00:00Z"));
    }

    @Test
    @DisplayName("toModel - CaptureView를 Capture 모델로 변환하며 시각을 ISO 문자열로 만든다")
    void toModel_convertsViewToModel() {
        Capture model = CaptureServiceImpl.toModel(view());

        assertThat(model.getId()).isEqualTo(1L);
        assertThat(model.getTrailId()).isEqualTo(2L);
        assertThat(model.getStreamId()).isEqualTo(3L);
        assertThat(model.getImagePath()).isEqualTo("/images/capture_001.jpg");
        assertThat(model.getRoadStatus()).isEqualTo("양호");
        assertThat(model.getConfidence()).isEqualTo(0.95);
        assertThat(model.getCreatedAt()).isEqualTo("2024-01-01T00:00:00Z");
        assertThat(model.getUpdatedAt()).isEqualTo("2024-01-02T00:00:00Z");
    }

    @Test
    @DisplayName("toModel - createdAt/updatedAt이 null이면 null을 유지한다")
    void toModel_keepsNullTimestamps() {
        CaptureView v = new CaptureView(1L, 2, 3, "/p.jpg", "양호", 0.95, null, null);

        Capture model = CaptureServiceImpl.toModel(v);

        assertThat(model.getCreatedAt()).isNull();
        assertThat(model.getUpdatedAt()).isNull();
    }
}
```

RestClient 호출 자체는 `TrailServiceImplTest`가 쓰는 mock 방식이 있으면 그걸 따라 확장한다. 이 태스크의 최소 목표는 변환 로직을 고정하는 것이다 — **호출 경로는 Task 5의 컨트롤러 테스트가 `@MockBean CaptureService`로 덮는다.**

- [ ] **Step 2: 실패를 확인한다**

Run: `./services/writer/mvnw -o clean test -pl apps/backend -am -Dtest=CaptureServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: 컴파일 실패. `toModel`이 없고 `CaptureServiceImpl`이 스텁이다.

- [ ] **Step 3: 구현으로 교체한다**

`CaptureServiceImpl.java` 전체를 교체한다.

```java
package com.stream.backend.service;

import com.stream.backend.model.Capture;
import com.stream.shared.dto.CaptureView;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

@Service
public class CaptureServiceImpl implements CaptureService {

    private final RestClient readerClient;

    // 캡처는 Kafka image.analyzed 이벤트로만 생성된다. HTTP 생성 경로가 없으므로
    // writerRestClient를 주입하지 않는다 - Stream/Trail 서비스와 다른 점이다.
    public CaptureServiceImpl(@Qualifier("readerRestClient") RestClient readerClient) {
        this.readerClient = readerClient;
    }

    static Capture toModel(CaptureView view) {
        return new Capture(
                view.id(),
                view.trailId() == null ? null : view.trailId().longValue(),
                view.streamId() == null ? null : view.streamId().longValue(),
                view.imagePath(),
                view.roadStatus(),
                view.confidence(),
                view.createdAt() == null ? null : view.createdAt().toString(),
                view.updatedAt() == null ? null : view.updatedAt().toString()
        );
    }

    @Override
    public List<Capture> findAll(Long streamId, Long trailId, Integer limit, String sort) {
        List<CaptureView> views = readerClient.get()
                .uri("/captures?stream_id={streamId}&trail_id={trailId}&limit={limit}&sort={sort}",
                        streamId, trailId, limit, sort)
                .retrieve()
                .body(new ParameterizedTypeReference<List<CaptureView>>() {});
        return views == null ? List.of() : views.stream().map(CaptureServiceImpl::toModel).toList();
    }

    @Override
    public Optional<Capture> findById(Long id) {
        try {
            CaptureView view = readerClient.get()
                    .uri("/captures/{id}", id)
                    .retrieve()
                    .body(CaptureView.class);
            return Optional.ofNullable(view).map(CaptureServiceImpl::toModel);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./services/writer/mvnw -o clean test -pl apps/backend -am -Dtest=CaptureServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: 2개 통과

- [ ] **Step 5: 커밋**

```bash
git add apps/backend
git commit -m "feat(backend): CaptureServiceImpl 구현

UnsupportedOperationException을 던지던 스텁을 reader 호출로 교체한다.
TrailServiceImpl과 같은 RestClient 패턴이다.

writerRestClient는 주입하지 않는다. 캡처는 Kafka image.analyzed
이벤트로만 생성되고 HTTP 생성 경로가 설계상 없기 때문이다 -
CaptureService 인터페이스에 create가 없는 것과 같은 이유다.

CaptureView의 trailId/streamId는 Integer이고 backend 모델은 Long이라
경계에서 변환한다. DB 컬럼이 captures.trail_id INTEGER라 reader 쪽
타입이 DB와 맞는 쪽이며, 이 불일치를 없애려면 8개 파일이 딸려오므로
경계 변환으로 둔다."
```

---

### Task 5: `CaptureController` 배선과 404 처리

**Files:**
- Modify: `apps/backend/src/main/java/com/stream/backend/controller/CaptureController.java`
- Create: `apps/backend/src/main/java/com/stream/backend/exception/CaptureNotFoundException.java`
- Modify: `apps/backend/src/main/java/com/stream/backend/exception/GlobalExceptionHandler.java`
- Test: `apps/backend/src/test/java/com/stream/backend/controller/CaptureControllerTest.java` (기존 RED 5개, 수정하지 않는다)

**Interfaces:**
- Consumes: Task 4의 `CaptureService`
- Produces: `GET /api/captures`, `GET /api/captures/{id}` — 이 계획의 최종 산출물

`TrailNotFoundException`/`StreamNotFoundException`과 같은 형태로 만든다.

- [ ] **Step 1: 기존 RED 5개의 실패를 확인한다**

Run: `./services/writer/mvnw -o clean test -pl apps/backend -am -Dtest=CaptureControllerTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: 5개 전부 FAIL, 전부 `UnsupportedOperationException: Not implemented`

- [ ] **Step 2: 예외 클래스를 만든다**

`apps/backend/src/main/java/com/stream/backend/exception/CaptureNotFoundException.java`

```java
package com.stream.backend.exception;

public class CaptureNotFoundException extends RuntimeException {

    private final Long id;

    public CaptureNotFoundException(Long id) {
        super("Capture not found: " + id);
        this.id = id;
    }

    public Long getId() {
        return id;
    }
}
```

`TrailNotFoundException`과 동일한 형태다(실제 파일을 확인해 맞췄다 — `RuntimeException` 상속, `Long id` 필드, `"<도메인> not found: " + id` 메시지, `getId()`).

- [ ] **Step 3: `GlobalExceptionHandler`에 핸들러를 추가한다**

`handleTrailNotFound` 아래에 넣는다.

```java
    @ExceptionHandler(CaptureNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleCaptureNotFound(CaptureNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Capture not found", "id", e.getId()));
    }
```

- [ ] **Step 4: 컨트롤러를 구현한다**

`CaptureController.java`의 두 메서드를 교체한다. **기본값 `limit=20`, `sort=created_at`을 컨트롤러가 채운다** — 테스트가 `findAll(null, null, 20, "created_at")`을 기대한다.

```java
    @GetMapping
    public ResponseEntity<List<Capture>> getAll(
            @RequestParam(value = "stream_id", required = false) Long streamId,
            @RequestParam(value = "trail_id", required = false) Long trailId,
            @RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit,
            @RequestParam(value = "sort", required = false, defaultValue = "created_at") String sort) {
        return ResponseEntity.ok(captureService.findAll(streamId, trailId, limit, sort));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        Capture capture = captureService.findById(id)
                .orElseThrow(() -> new CaptureNotFoundException(id));
        return ResponseEntity.ok(capture);
    }
```

import 추가: `com.stream.backend.exception.CaptureNotFoundException`.

- [ ] **Step 5: 통과를 확인한다**

Run: `./services/writer/mvnw -o clean test -pl apps/backend -am -Dtest=CaptureControllerTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: 5개 전부 PASS

- [ ] **Step 6: 전체 스위트로 회귀를 확인한다**

Run: `./services/writer/mvnw -o clean test -fae`

Expected: shared 32/32, reader 37/38, writer 85/86, **backend 38/38** (기존 36 + Task 4의 2, 실패 0). `BackendApplicationTests.contextLoads`도 계속 통과해야 한다.

- [ ] **Step 7: 커밋**

```bash
git add apps/backend
git commit -m "feat(backend): CaptureController 배선으로 조회 경로 완성

UnsupportedOperationException을 던지던 스텁 두 메서드를 구현했다.
CaptureControllerTest 5개가 GREEN으로 전환되어 backend RED가 0이 된다.

limit(20)과 sort(created_at) 기본값은 컨트롤러가 채운다. 테스트가
findAll(null, null, 20, \"created_at\")을 기대하며, 서비스 계층이 아니라
HTTP 경계에서 기본값을 정하는 것이 Stream/Trail과도 일관된다.

404는 CaptureNotFoundException을 GlobalExceptionHandler가 받아
{\"error\": \"Capture not found\", \"id\": N} 본문으로 만든다.
Stream/Trail과 같은 형태다.

이로써 파이프라인의 출구가 열렸다. Kafka image.analyzed로 저장된 캡처를
GET /api/captures로 외부에서 조회할 수 있다."
```

---

### Task 6: 문서 갱신

**Files:**
- Modify: `docs/tdd-test-plan.md`
- Modify: `docs/api-specs/stream-walkway.postman_collection.json`

- [ ] **Step 1: 실측값을 얻는다**

Run: `./services/writer/mvnw -o clean test -fae`

각 모듈의 통과/실패 수를 그대로 옮긴다. 추정하지 않는다.

- [ ] **Step 2: `docs/tdd-test-plan.md`를 갱신한다**

- backend 소스 코드 표: `CaptureServiceImpl`, `CaptureController`의 "**스텁**"을 "구현 완료"로
- backend 테스트 표: `CaptureControllerTest` 5/5/0, `CaptureServiceImplTest` 행 추가, 합계 수정
- reader 테스트 표: `CaptureControllerTest` 개수 수정, 합계 수정
- shared 테스트 표: `CaptureTest`/`CaptureViewTest` 개수 수정, 합계 수정
- 요약표와 "RED 테스트 상세"의 "Backend — 5개 RED" 절 삭제
- "배경 및 주의사항"의 "아직 스텁으로 남아 있는 것" 목록에서 backend 항목 삭제 (youtube-service만 남는다)

- [ ] **Step 3: Postman 컬렉션을 확인한다 — 수정 불필요로 확인됨**

실제로 열어보니 "Backend Gateway :8080 / Captures" 폴더의 두 요청이 이미 `updated_at`을 포함한 200 예시와 `{"error": "Capture not found", "id": 1}` 404 예시를 갖고 있었다. 명세가 먼저 있었고 구현이 이번에 따라잡은 것이므로 고칠 것이 없다. 다른 작업에서 이 절을 참고할 때는 **먼저 열어보고** 실제로 어긋난 것만 고칠 것.

- [ ] **Step 4: 커밋**

```bash
git add docs
git commit -m "docs: Capture 조회 경로 구현을 문서에 반영

tdd-test-plan.md의 backend Capture 스텁 표기를 구현 완료로 바꾸고
테스트 수치를 실측값으로 갱신했다. backend RED 5개가 사라져 남은 RED는
youtube-service 11개와 환경 의존 2개뿐이다.

Postman 컬렉션에 updated_at 필드와 404 응답 본문 예시를 반영했다."
```

---

## 완료 후 상태

| 모듈 | 작업 전 | 작업 후 |
|---|---|---|
| `packages/shared` | 30 / 30 | 32 / 32 |
| `services/reader` | 33 중 32 | 38 중 37 |
| `services/writer` | 86 중 85 | 변화 없음 |
| `apps/backend` | 36 중 31 | 38 중 38 |

**남는 RED:** youtube-service 11개(미구현), ml-service 13개(미구현), `ApplicationTests.contextLoads` 2개(테스트 설정 문제). backend RED는 0이 된다.

## Phase 2 완료 후 정리 대상

- **`CaptureRepository.findByTrailId` / `findByStreamId`가 죽은 메서드가 됐다.** Task 3의 `findFiltered`가 두 경우를 모두 덮는다. Phase 1 시점에 지우지 않은 것은 backend가 붙기 전까지 실제로 아무도 부르지 않는지 확정할 수 없어서다. Phase 2가 끝나면 호출자를 확인하고 지운다. `findFirstByTrailIdOrderByCreatedAtDesc`는 `/captures/trail/{id}/latest`가 계속 쓰므로 남긴다.
- **reader `CaptureController`의 `@ExceptionHandler(IllegalArgumentException)`가 컨트롤러 전체 범위다.** 지금은 `getAll`의 파라미터 검증만 IAE를 던지지만, 나중에 다른 메서드에서 내부 오류로 IAE가 나면 500이어야 할 것이 400으로 나간다. 그런 경로가 생기면 파라미터 검증 전용 예외로 분리한다.

---

## 이 계획이 다루지 않는 것

- **캡처 생성 경로.** 캡처는 Kafka `image.analyzed`로만 생기며 이 계획은 읽기 전용이다
- **파이프라인 트리거.** backend에서 youtube-service를 시작시키는 입구는 별도 작업이다. 설계 결정과 순서는 프로젝트 메모리의 "캡처 파이프라인 트리거 설계"에 있다
- **`Integer`/`Long` 통일.** 위 "사전 확인 사실 2"에 근거를 적었다. 경계에서 변환한다
- **Redis 캐시 스키마 변경.** `updatedAt` 추가로 `CaptureView` JSON 모양이 바뀌지만 기존 캐시는 미스 시 재적재된다. 즉시 맞추려면 `capture:latest:trail:*` 키를 비운다
- **페이지네이션.** `limit`만 있고 offset/cursor가 없다. 명세에 없으므로 넣지 않는다
