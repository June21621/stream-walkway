# TDD 작업 내역 정리

> 기준 API 명세: `docs/api-specs/stream-walkway.postman_collection.json`
> 작성일: 2026-03-09
> 최종 갱신: 2026-08-27 — ml-service RED 13개 해소로 ml-service도 RED가 0이 되었다. 같은 날 Capture 조회 경로 완료로 backend RED가 0이 되었다. 같은 날 전체 스위트 실측 교체와 Docker SKIP 5개 해소도 함께 했다. 이 문서는 스텁 생성 당시의 스냅샷이 아니라 구현 진행에 맞춰 계속 갱신하는 문서입니다.

---

## 측정 방법 및 환경

이 문서의 모든 테스트 수치는 추정이 아니라 2026-08-27에 실제로 실행한 결과입니다.

| 대상 | 실행 명령 | 집계 방식 |
|------|---------|---------|
| shared / reader / writer / backend | `./services/writer/mvnw -o test -fae` (루트 애그리게이터가 4개 모듈 모두 빌드) | `target/surefire-reports/*.xml` 파싱 |
| youtube-service | `npm test` (`jest --testPathPattern=tests/`) | jest JSON 리포터 |
| ml-service | `python -m pytest tests -q` | pytest 요약 |

**환경 의존 항목** — 아래 두 항목은 코드 결함이 아니라 실행 환경에 따라 갈립니다.

- **Docker (해소됨)**: 1차 측정에서 `TrailCommandHandlerPostgresTest` 5개가 `Docker is not available`로 스킵됐습니다. 같은 날 Docker Desktop을 띄우고 재실행해 **5개 전부 통과**했습니다(실 PostgreSQL 제약 이름과 예외 매핑이 하드코딩된 추정대로 맞음). 아래 표는 재실행 후 수치입니다.
- **DB 미기동 (미해소)**: `ReaderApplicationTests.contextLoads`, `WriterApplicationTests.contextLoads` 2개가 실패합니다. 원인은 `Unable to determine Dialect without JDBC metadata` — `@SpringBootTest`가 실제 DataSource를 요구하는데 테스트 프로파일에 JDBC URL이 없습니다. Docker를 띄운 상태에서도 동일하게 실패하므로 **Docker 유무와 무관한 테스트 설정 문제**입니다.

즉 아래 표의 RED 13개 중 **youtube 11개만이 실제 미구현으로 인한 RED**이고, reader·writer의 2개는 테스트 설정 문제입니다.

---

## 배경 및 주의사항

API 명세서를 기반으로 TDD(Red → Green → Refactor) 방식으로 테스트 코드를 작성하였다.
테스트 코드 작성 과정에서 **테스트가 컴파일되기 위해 필요한 소스 코드 스텁도 함께 생성**되었다.

- **의도**: 테스트 코드만 작성
- **실제**: 테스트 코드 + 소스 코드 스텁 (컴파일 가능한 최소 구조) 동시 생성

2026-08-27 기준으로 스텁 중 상당수는 실제 구현으로 대체되었다. **아직 `throw new Error('Not implemented')`를 던지는 채로 남아 있는 것은 `apps/youtube-service`의 `src/app.js` 한 곳뿐이다** — `POST /download`, `GET /status/:jobId`.

---

## 파일별 현황

### apps/backend (Spring Boot)

#### 소스 코드

| 파일 | 종류 | 현재 상태 |
|------|------|---------|
| `src/main/java/.../model/Stream.java` | Model | 구현 완료 |
| `src/main/java/.../model/Trail.java` | Model | 구현 완료 |
| `src/main/java/.../model/Capture.java` | Model | 구현 완료 |
| `src/main/java/.../service/StreamServiceImpl.java` | 구현체 | 구현 완료 (RestClient로 reader/writer 연동) |
| `src/main/java/.../service/TrailServiceImpl.java` | 구현체 | 구현 완료 (RestClient로 reader/writer 연동) |
| `src/main/java/.../service/CaptureServiceImpl.java` | 구현체 | 구현 완료 (readerClient만 주입 — 캡처는 HTTP 생성 경로가 없다) |
| `src/main/java/.../controller/StreamController.java` | Controller | 구현 완료 (X-Internal-Key 검증 포함) |
| `src/main/java/.../controller/TrailController.java` | Controller | 구현 완료 (X-Internal-Key 검증 포함) |
| `src/main/java/.../controller/CaptureController.java` | Controller | 구현 완료 (`limit`/`sort` 기본값, 404 본문) |
| `src/main/java/.../exception/GlobalExceptionHandler.java` | 예외 처리 | 구현 완료 (스텁 생성 이후 추가) |
| `src/main/java/.../config/HttpClientConfig.java` | 설정 | 구현 완료 (스텁 생성 이후 추가) |

#### 테스트 코드

| 파일 | 방식 | 전체 | GREEN | RED |
|------|------|-----|-------|-----|
| `src/test/.../BackendApplicationTests.java` | `@SpringBootTest` | 1 | 1 | 0 |
| `src/test/.../HealthCheckTest.java` | `@WebMvcTest` | 3 | 3 | 0 |
| `src/test/.../controller/StreamControllerTest.java` | `@WebMvcTest` + `@MockBean` | 7 | 7 | 0 |
| `src/test/.../controller/TrailControllerTest.java` | `@WebMvcTest` + `@MockBean` | 8 | 8 | 0 |
| `src/test/.../controller/CaptureControllerTest.java` | `@WebMvcTest` + `@MockBean` | 5 | 5 | 0 |
| `src/test/.../service/CaptureServiceImplTest.java` | Mockito 단위 테스트 | 6 | 6 | 0 |
| `src/test/.../service/StreamServiceImplTest.java` | Mockito 단위 테스트 | 5 | 5 | 0 |
| `src/test/.../service/TrailServiceImplTest.java` | Mockito 단위 테스트 | 7 | 7 | 0 |
| **합계** | | **42** | **42** | **0** |

---

### apps/youtube-service (Node.js/Express)

#### 소스 코드

| 파일 | 종류 | 현재 상태 |
|------|------|---------|
| `src/app.js` | Express 앱 | `POST /download`, `GET /status/:jobId` 둘 다 **스텁** (`throw new Error('Not implemented')`) |
| `src/kafka.js` | Kafka 프로듀서 | 구현 완료 (`connectKafka`, `publishMessage`) |
| `src/index.js` | 엔트리포인트 | 구현 완료. 단 `app.js`를 import하지 않고 Express 앱을 따로 정의하고 있어 **중복 상태** |

#### 테스트 코드

| 파일 | 방식 | 전체 | GREEN | RED |
|------|------|-----|-------|-----|
| `tests/app.test.js` | Jest + Supertest | 10 | 1 | **9** |
| `tests/kafka.test.js` | Jest (kafkajs mock) | 12 | 10 | **2** |
| **합계** | | **22** | **11** | **11** |

---

### apps/ml-service (Python/FastAPI)

#### 소스 코드

| 파일 | 종류 | 현재 상태 |
|------|------|---------|
| `main.py` — `GET /health` | 엔드포인트 | 구현 완료. `{status, model, uptime_sec}` 반환. **`model: "loaded"`는 명세가 요구하는 고정 문자열이며 실제 모델 적재 상태가 아니다** |
| `main.py` — `POST /analyze` | 엔드포인트 | 구현 완료. 202 + `{jobId, status, image_path}`. 접수만 하고 실제 분석 경로에 일을 넣지 않는다(의도된 결정, 계획 문서 참고) |
| `main.py` — `consume()` | Kafka 소비/발행 | 메시지별 `try/except` 추가로 잘못된 메시지가 루프를 죽이지 않는다. **분석 결과는 여전히 고정값** (`roadStatus: "양호"`, `confidence: 0.95`) — 실제 ML 모델은 미적재 |

#### 테스트 코드

| 파일 | 방식 | 전체 | GREEN | RED |
|------|------|-----|-------|-----|
| `tests/test_main.py` | pytest + FastAPI TestClient | 12 | 12 | 0 |
| `tests/test_consume.py` | pytest + AsyncMock | 16 | 16 | 0 |
| **합계** | | **28** | **28** | **0** |

---

### services/reader (Spring Boot)

#### 테스트 코드

| 파일 | 방식 | 전체 | GREEN | RED |
|------|------|-----|-------|-----|
| `src/test/.../ReaderApplicationTests.java` | `@SpringBootTest` | 1 | 0 | **1** (환경 의존 — 위 "측정 방법" 참고) |
| `src/test/.../HealthCheckTest.java` | `@WebMvcTest` | 3 | 3 | 0 |
| `src/test/.../TestSchemaSyncTest.java` | 스키마 동기화 검증 | 1 | 1 | 0 |
| `src/test/.../controller/StreamControllerTest.java` | `@WebMvcTest` + `@MockBean` | 4 | 4 | 0 |
| `src/test/.../controller/TrailControllerTest.java` | `@WebMvcTest` + `@MockBean` | 5 | 5 | 0 |
| `src/test/.../controller/CaptureControllerTest.java` | `@WebMvcTest` + `@MockBean` | 17 | 17 | 0 |
| `src/test/.../repository/CaptureRepositoryTest.java` | `@DataJpaTest` + H2 + `@Sql` | 10 | 10 | 0 |
| **합계** | | **41** | **40** | **1** |

> 엔티티 테스트는 `packages/shared`로 이동했다. reader에는 더 이상 `entity/CaptureTest.java`가 없다.

---

### services/writer (Spring Boot)

#### 테스트 코드

| 파일 | 방식 | 전체 | GREEN | RED | SKIP |
|------|------|-----|-------|-----|------|
| `src/test/.../WriterApplicationTests.java` | `@SpringBootTest` | 1 | 0 | **1** (환경 의존) | 0 |
| `src/test/.../HealthCheckTest.java` | `@WebMvcTest` | 3 | 3 | 0 | 0 |
| `src/test/.../TestSchemaSyncTest.java` | 스키마 동기화 검증 | 1 | 1 | 0 | 0 |
| `src/test/.../command/StreamCommandHandlerTest.java` | Mockito + H2 | 10 | 10 | 0 | 0 |
| `src/test/.../command/StreamCommandHandlerConstraintTest.java` | 제약조건 검증 | 2 | 2 | 0 | 0 |
| `src/test/.../command/TrailCommandHandlerTest.java` | Mockito + H2 | 17 | 17 | 0 | 0 |
| `src/test/.../command/TrailCommandHandlerConstraintTest.java` | 제약조건 검증 | 8 | 8 | 0 | 0 |
| `src/test/.../command/TrailCommandHandlerPostgresTest.java` | Testcontainers + 실 PostgreSQL | 5 | 5 | 0 | 0 |
| `src/test/.../command/CaptureCommandHandlerTest.java` | Mockito 단위 테스트 | 4 | 4 | 0 | 0 |
| `src/test/.../command/GeometryValidatorTest.java` | 순수 단위 테스트 | 8 | 8 | 0 | 0 |
| `src/test/.../command/GeometryColumnConstraintTest.java` | 제약조건 검증 | 5 | 5 | 0 | 0 |
| `src/test/.../consumer/ImageAnalyzedConsumerTest.java` | Mockito 단위 테스트 | 5 | 5 | 0 | 0 |
| `src/test/.../controller/StreamControllerTest.java` | `@WebMvcTest` + `@MockBean` | 4 | 4 | 0 | 0 |
| `src/test/.../controller/TrailControllerTest.java` | `@WebMvcTest` + `@MockBean` | 5 | 5 | 0 | 0 |
| `src/test/.../repository/CaptureRepositoryTest.java` | `@DataJpaTest` + H2 | 8 | 8 | 0 | 0 |
| **합계** | | **86** | **85** | **1** | **0** |

---

### packages/shared (공유 라이브러리)

스텁 생성 당시에는 없던 모듈로, reader/writer가 공유하는 엔티티와 뷰 DTO를 담는다.

| 파일 | 방식 | 전체 | GREEN | RED |
|------|------|-----|-------|-----|
| `src/test/.../entity/StreamTest.java` | 순수 단위 테스트 | 6 | 6 | 0 |
| `src/test/.../entity/TrailTest.java` | 순수 단위 테스트 | 9 | 9 | 0 |
| `src/test/.../entity/CaptureTest.java` | 순수 단위 테스트 | 12 | 12 | 0 |
| `src/test/.../dto/StreamViewTest.java` | 순수 단위 테스트 | 2 | 2 | 0 |
| `src/test/.../dto/TrailViewTest.java` | 순수 단위 테스트 | 2 | 2 | 0 |
| `src/test/.../dto/CaptureViewTest.java` | 순수 단위 테스트 | 1 | 1 | 0 |
| **합계** | | **32** | **32** | **0** |

---

## 테스트 현황 요약 (2026-08-27 실측)

| 서비스 | 전체 테스트 수 | GREEN | RED | SKIP |
|--------|-------------|-------|-----|------|
| backend | 42 | 42 | 0 | 0 |
| youtube-service | 22 | 11 | 11 | 0 |
| ml-service | 28 | 28 | 0 | 0 |
| reader | 41 | 40 | 1 | 0 |
| writer | 86 | 85 | 1 | 0 |
| shared | 32 | 32 | 0 | 0 |
| **합계** | **251** | **238** | **13** | **0** |

> **이전 갱신(2026-08-16) 대비 변화**: 당시 표는 총 122개(GREEN 85 / RED 37)였다. 그 표는 스텁 생성 당시 존재하던 파일만 집계했고, 이후 추가된 Stream/Trail/Geometry 관련 테스트와 `packages/shared` 모듈이 빠져 있었다. 이번 표는 저장소의 모든 테스트를 실행해 집계한 값이다.

---

## RED 테스트 상세 — 구현이 필요한 항목

### YouTube Service — 11개 RED

| 대상 | 필요한 구현 |
|------|-----------|
| `POST /download` (app.test.js 9개) | 요청 검증, jobStore 저장, 202 반환, `progress` 필드를 포함한 상태 관리 |
| `GET /status/:jobId` | jobStore 조회, 없으면 404 반환 (현재는 500) |
| `/download` → Kafka 발행 (kafka.test.js 2개) | 다운로드 완료 시 `publishMessage('image.downloaded', {...})` 호출 |

> 별개 이슈: `src/index.js`가 `src/app.js`를 import하지 않고 Express 앱을 따로 정의하고 있다. `/download`를 구현할 때 두 파일을 하나로 합쳐야 실제 서버에 반영된다.

### reader / writer — 각 1개 RED (환경 의존)

| 대상 | 원인 | 조치 |
|------|-----|-----|
| `ReaderApplicationTests.contextLoads` | `@SpringBootTest`가 실 DataSource를 요구하나 테스트 프로파일에 JDBC URL 없음 | 테스트용 DataSource 설정을 주거나, 인프라 기동 후 재측정 |
| `WriterApplicationTests.contextLoads` | 동일 | 동일 |

### writer — SKIP 5개 해소됨 (2026-08-27)

`TrailCommandHandlerPostgresTest`는 Testcontainers로 실 PostgreSQL을 띄우는데, 1차 측정 시 Docker가 실행 중이 아니라 스킵됐다. Docker Desktop을 띄우고 재실행해 **5개 전부 통과**했다.

```
PASS realPostgresForeignKeyViolationBecomesIllegalArgumentException
PASS productionSchemaHasExpectedConstraintNames
PASS postgresUniqueErrorMessageActuallyContainsLowercaseConstraintName
PASS realPostgresUniqueViolationBecomesDuplicateTrailException
PASS postgresErrorMessageActuallyContainsLowercaseConstraintName
```

제약 이름(`trails_stream_id_fkey`, `trails_stream_id_camera_number_key`)과 위반 시 예외 매핑이 하드코딩된 추정대로 맞았다. 같은 기회에 PostGIS 지오메트리 동작도 실측했고, `POINT EMPTY`에 대한 H2/PostGIS 차이를 발견해 관련 문서를 정정했다 — `docs/superpowers/specs/2026-08-25-writer-geometry-validation-findings.md`의 "PostGIS 실측" 절 참고.

---

## 다음 단계 (GREEN 전환 순서 권장)

```
1. ML Service — 완료 (2026-08-27)
   - GET /health 응답 형식 수정 — 완료
   - POST /analyze 엔드포인트 추가 — 완료
   - consume() 내부 try/except 추가 — 완료

2. YouTube Service — 11개 RED
   - app.js의 POST /download 구현 (jobStore + Kafka 발행)
   - app.js의 GET /status/:jobId 구현
   - index.js와 app.js 통합 (현재 앱 정의가 중복)

3. Backend Capture 경로 — 완료 (2026-08-27)
   - reader에 GET /captures/{id} 및 /captures 필터 파라미터 추가 — 완료
   - backend CaptureServiceImpl 구현 — 완료
   - backend CaptureController에서 서비스 호출하도록 구현 — 완료

4. 환경 의존 항목 (구현이 아니라 검증)
   - Docker 기동 후 TrailCommandHandlerPostgresTest 5개 실행 — 완료 (2026-08-27, 전부 통과)
   - 테스트용 DataSource 설정 후 reader/writer contextLoads 2개 확인 — 미해소
```

### 완료된 항목

- `@ControllerAdvice` 에러 핸들러 (`GlobalExceptionHandler`) — 완료
- `StreamService` 구현체 — 완료 (2026-08-16, RestClient 기반 reader/writer 연동)
- `TrailService` 구현체 — 완료 (설계: `docs/superpowers/specs/2026-08-16-trail-cqrs-gis-design.md`)
- writer 지오메트리 검증 (길이 / Z·M 좌표 / 빈 지오메트리 / SRID 4326 범위) — 완료 (`docs/superpowers/specs/2026-08-26-writer-geometry-validation-design.md`)
- `packages/shared` 엔티티·DTO 분리 — 완료
- ml-service RED 13개 해소 — 완료 (계획: `docs/superpowers/plans/2026-08-27-ml-service-red-green.md`). **실제 ML 모델은 여전히 없다** — `consume()`이 고정값을 내고 이미지 파일을 열지도 않는다. 저장 위치가 정해지지 않아 분석할 파일에 접근할 방법 자체가 없다(youtube-service 작업에서 결정)
- Capture 조회 경로 (`GET /api/captures`, `GET /api/captures/{id}`) — 완료 (계획: `docs/superpowers/plans/2026-08-27-capture-read-path.md`). 파이프라인의 출구가 열렸다. 함께 고친 것: `shared`에 `updatedAt` 누락, backend `CaptureController`의 `@RequestParam` 이름 누락(`?stream_id=`와 바인딩 안 됨)
