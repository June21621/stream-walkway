# Trail(산책로 카메라 스테이션) 도메인 CQRS + GIS 설계

## 배경 및 목표

Stream(하천) 도메인 CQRS+GIS 구현(`docs/superpowers/plans/2026-08-15-stream-cqrs-gis.md`)이 완료되어 `main`에 merge/push되었다. 해당 계획 문서는 "Trail은 이 계획의 범위 밖이며, Stream 패턴이 검증되면 거의 동일한 구조로 별도 계획을 짠다"고 명시했다. 이 문서는 그 후속 계획이다.

Trail은 이름과 달리 "산책로 경로"가 아니라 **하천을 따라 설치된 카메라 관측 지점**을 모델링한다: `stream_id`로 특정 Stream에 속하고, `camera_number`(카메라 번호), `location`(POINT 좌표), `direction`(촬영 방향), `status`(운영 상태: active/inactive)를 가진다. Capture(이미지 캡처)는 Trail(카메라)에서 발생한다.

이미 존재하는 것 (재설계 대상 아님, 고정된 계약으로 취급):
- `infra/scripts/init-db.sql`의 `trails` 테이블 스키마 (`stream_id` FK ON DELETE CASCADE, `location GEOMETRY(POINT,4326)`, `UNIQUE(stream_id, camera_number)`, `status CHECK IN ('active','inactive') DEFAULT 'active'`)
- `apps/backend`의 RED 스텁: `TrailController`(`/api/trails`), `TrailService` 인터페이스, `Trail` 모델(POJO), `TrailControllerTest`(7개 테스트, 2026-03-09 작성)
- `docs/api-specs/stream-walkway.postman_collection.json`의 Trail API 스펙
- `docs/diagrams/erd.md`의 Stream-Trail 관계 (1:N, `stream_id`)

## 아키텍처

Stream과 동일하게 **동기 HTTP, Kafka 미사용**. Trail은 관리자/시스템이 카메라를 등록하는 작업이라 비동기 이벤트로 처리하지 않는다 (Capture와는 다른 이유로 Stream과 같은 선택).

```
backend (/api/trails) --RestClient(sync HTTP)--> reader (/trails)   [조회]
                        --RestClient(sync HTTP)--> writer (/internal/trails) [생성]
```

reader/writer는 각자의 DB(PostgreSQL+PostGIS)에 직접 접근, backend는 DB 없이 두 서비스를 HTTP로 중개한다 (Stream과 동일 구조, `HttpClientConfig`의 `readerRestClient`/`writerRestClient` Bean 재사용, 신규 Bean 불필요).

## `packages/shared`

**`entity/Trail.java`** (신규)

```java
@Entity
@Table(name = "trails")
public class Trail {
    public static final int SRID = 4326;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
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
    protected void onCreate() { createdAt = LocalDateTime.now(); }

    // getter, setter (id/createdAt은 getter만 — Stream 패턴과 동일)
}
```

`streamId`는 JPA 연관관계(`@ManyToOne`)로 매핑하지 않고 원시 `Long` 컬럼으로 둔다. reader/writer가 조인 없이 단순 CRUD만 수행하므로 Stream의 설계 원칙과 일관성을 유지하며, FK 무결성은 DB의 `REFERENCES streams(id) ON DELETE CASCADE`가 보장한다.

**`dto/TrailView.java`** (신규) — `StreamView`와 동일한 구조의 record.

```java
public record TrailView(
        Long id,
        Long streamId,
        String cameraNumber,
        String location,   // WKT, "POINT(...)" — Stream과 동일하게 키워드 뒤 공백 제거
        String direction,
        String status,
        LocalDateTime createdAt
) {
    public static TrailView from(Trail trail) { ... }
}
```

WKT 변환 시 `WKTWriter().write(...).replaceFirst("\\s+\\(", "(")` — Stream의 SRID 0 버그 수정 교훈을 그대로 적용, `GeometryFactory`에 `Trail.SRID`(4326)를 명시해서 파싱한다 (아래 writer 참고).

Maven 의존성 추가 불필요 (`hibernate-spatial`/`jts-core`는 Stream 작업에서 이미 `packages/shared/pom.xml`에 추가됨). `Point`는 `org.locationtech.jts.geom.Point`.

## Writer (`services/writer`, `com.stream.writer`)

**`command/CreateTrailCommand.java`** — record `(Long streamId, String cameraNumber, String location, String direction, String status)`. `location`은 WKT 문자열(`"POINT(126.97 37.55)"`), `status`는 nullable(선택적 필드).

**`command/TrailCommandHandler.java`**
- `WKTReader`를 `Trail.SRID`(4326)로 스코프해서 POINT 파싱 (Stream의 `StreamCommandHandler`와 동일 패턴)
- `status`가 null이면 `"active"`로 채움; `"active"`/`"inactive"` 외의 값이면 `IllegalArgumentException`을 던짐 (→ 컨트롤러에서 400)
- 저장 시 DB의 `UNIQUE(stream_id, camera_number)` 위반으로 `DataIntegrityViolationException`이 발생하면 `DuplicateTrailException`(writer 모듈 내부 예외)으로 변환

**`controller/TrailController.java`** — `@RequestMapping("/internal/trails")`, `POST`만.
- 정상: `201` + `TrailView`
- `@ExceptionHandler({ParseException.class, ClassCastException.class, IllegalArgumentException.class})` → `400` `{"error": "Invalid trail data: ..."}`
- `@ExceptionHandler(DuplicateTrailException.class)` → `409` `{"error": "Duplicate trail", "message": "stream_id=1, camera_number=CAM-001 already exists"}`

**`repository/TrailRepository.java`** — `JpaRepository<Trail, Long>` (Stream과 동일, 추가 쿼리 메서드 없음).

## Reader (`services/reader`, `com.stream.reader`)

**`controller/TrailController.java`** — `@RequestMapping("/trails")`
- `GET`: `@RequestParam(value = "stream_id", required = false) Long streamId` — null이면 전체 조회(`findAll()`), 있으면 `findByStreamId(streamId)`로 필터링. **주의**: 쿼리 파라미터명은 Postman 스펙/ERD와 동일하게 `stream_id`(스네이크 케이스)를 명시적으로 지정해야 함 — `value` 없이 `@RequestParam`만 쓰면 Spring이 자바 파라미터명(`streamId`)으로 바인딩해서 `?stream_id=1` 요청이 조용히 무시된다.
- `GET /{id}`: Stream과 동일 (`404` if absent)

**`repository/TrailRepository.java`** — `JpaRepository<Trail, Long>` + `List<Trail> findByStreamId(Long streamId)` (Spring Data 메서드 이름 파생 쿼리)

## Backend (`apps/backend`, `com.stream.backend`) — 기존 RED 스텁을 GREEN으로

기존에 이미 커밋되어 있는 `TrailController`/`TrailService`/`Trail`(모델)/`TrailControllerTest`는 **고정 계약**으로 취급하고 변경하지 않는다. `TrailServiceImpl`을 새로 작성해서 `UnsupportedOperationException`을 대체한다.

**`controller/TrailController.java`** (기존 RED 스텁의 본문을 구현으로 교체; `getAll`의 `@RequestParam` 애노테이션에 `value = "stream_id"`를 추가하는 것 외 시그니처 불변 — 기존 스텁은 `value` 없이 `@RequestParam(required = false) Long streamId`로 되어 있어 `?stream_id=1` 쿼리가 바인딩되지 않는 잠재 버그가 있었음)
```java
@GetMapping
public ResponseEntity<List<Trail>> getAll(@RequestParam(value = "stream_id", required = false) Long streamId) {
    return ResponseEntity.ok(trailService.findAll(streamId));
}

@GetMapping("/{id}")
public ResponseEntity<?> getById(@PathVariable Long id) {
    Trail trail = trailService.findById(id).orElseThrow(() -> new TrailNotFoundException(id));
    return ResponseEntity.ok(trail);
}

@PostMapping
public ResponseEntity<?> create(@RequestBody Trail trail, @RequestHeader("X-Internal-Key") String internalKey) {
    if (!internalApiKey.equals(internalKey)) throw new InvalidInternalKeyException();
    return ResponseEntity.status(HttpStatus.CREATED).body(trailService.create(trail));
}
```
`InvalidInternalKeyException`은 Stream에서 쓰던 걸 그대로 재사용 (새 예외 불필요).

**`service/TrailServiceImpl.java`** — `StreamServiceImpl`과 동일한 구조로 `readerRestClient`/`writerRestClient` 재사용(신규 `@Bean` 불필요).
- `findAll(Long streamId)`: `streamId == null`이면 `GET /trails`, 아니면 `GET /trails?stream_id={streamId}` 호출
- `findById(Long id)`: `GET /trails/{id}`, `HttpClientErrorException.NotFound` → `Optional.empty()`
- `create(Trail trail)`: `POST /internal/trails`. `HttpClientErrorException.BadRequest` → `InvalidTrailGeometryException`(400 대응), `HttpClientErrorException.Conflict` → `DuplicateTrailException`(409 대응)

**새 예외 3개** (`exception/` 패키지)
- `TrailNotFoundException(Long id)` — `StreamNotFoundException`과 동일 구조
- `InvalidTrailGeometryException(String message)` — `InvalidStreamGeometryException`과 동일 구조
- `DuplicateTrailException(String message)` — 신규 (Stream에는 없던 케이스)

**`GlobalExceptionHandler`에 핸들러 3개 추가**
```java
@ExceptionHandler(TrailNotFoundException.class)      // 404, {"error": "Trail not found", "id": ...}
@ExceptionHandler(InvalidTrailGeometryException.class) // 400, {"error": "Invalid trail data", "message": ...}
@ExceptionHandler(DuplicateTrailException.class)       // 409, {"error": "Duplicate trail", "message": ...}
```
기존 `InvalidInternalKeyException` 핸들러는 그대로 재사용.

## 테스트 계획

Stream 때와 동일한 계층별 커버리지, `mvnw clean test`로 매번 검증:

| 모듈 | 테스트 파일 | 범위 |
|---|---|---|
| shared | `TrailTest`, `TrailViewTest` | 엔티티 생성, WKT 변환(POINT, 공백 제거), SRID |
| writer | `TrailCommandHandlerTest` | 정상 생성, WKT 파싱 실패, status 검증 실패, 중복(UNIQUE) 처리 |
| writer | `TrailControllerTest` | 201/400/409 응답 |
| reader | `TrailControllerTest` | 전체 조회, `stream_id` 필터, 단건 조회, 404 |
| backend | `TrailControllerTest` (기존, RED→GREEN 전환) | 기존 7개 테스트 통과 확인 |
| backend | `TrailServiceImplTest` (신규) | RestClient 호출 매핑, 예외 변환(404/400/409) |

**H2 테스트 스키마**: `services/reader/src/test/resources/schema.sql`, `services/writer/src/test/resources/schema.sql`에 `trails` 테이블 DDL 추가 필요 (현재 `captures`만 정의되어 있음, H2는 PostGIS 없이 `geometry` 타입을 직접 지원하지 않으므로 Stream 때 썼던 것과 동일한 H2용 컬럼 타입 처리 방식을 따른다).

**범위 밖으로 유지**: Postgres/PostGIS 실통합 테스트(Testcontainers) — Stream 계획에서도 명시적으로 범위 밖으로 뒀던 것과 동일한 이유(이번 세션에서 별도로 진행하기로 한 Docker 실기동 검증 단계에서 확인).

## DB / Docker

`infra/scripts/init-db.sql`은 이미 `trails` 테이블을 정의하고 있어 **수정 불필요**. `docs/diagrams/erd.md`도 이미 Stream-Trail 관계를 반영하고 있어 수정 불필요.

## 결정 사항 요약 (사용자 확인 완료)

1. **중복 `(stream_id, camera_number)`**: writer가 DB 제약 위반을 잡아 `409 Conflict` + 명확한 에러 메시지로 변환 (500 그대로 노출하지 않음)
2. **`status` 필드**: 선택적, 미입력 시 `"active"` 기본값, `"active"`/`"inactive"` 외 값은 애플리케이션 레벨에서 `400`으로 거부 (DB CHECK 제약에만 의존하지 않음)
3. **`stream_id` 조회 필터**: 선택적 쿼리 파라미터. 없으면 전체 Trail 반환, 있으면 필터링 (기존 backend RED 테스트 `getAll_returns200WithTrailList`가 `findAll(null)`을 기대하는 것과 일치)
4. **작업 공간**: 워크트리 없이 `main`에서 직접 진행 (Stream 때와 동일)
5. **자체 리뷰에서 발견한 기존 버그**: `apps/backend`의 기존 커밋된 `TrailController` RED 스텁이 `@RequestParam(required = false) Long streamId`로 `value`를 지정하지 않아 `?stream_id=1` 쿼리와 바인딩되지 않는 문제가 있었음. 구현 시 `value = "stream_id"`를 추가해서 수정한다 (기존 RED 테스트가 검증하려던 동작과 일치시키는 수정이므로 "고정 계약 불변" 원칙에 위배되지 않음).
