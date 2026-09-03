# TDD 작업 내역 정리

> 기준 API 명세: `docs/api-specs/stream-walkway.postman_collection.json`
> 작성일: 2026-03-09
> 최종 갱신: 2026-09-03 — backend에 캡처 트리거(`POST /api/captures/jobs`)와 작업 상태 조회(`GET /api/captures/jobs/{jobId}`)를 추가해 파이프라인 입구를 게이트웨이로 옮겼다. backend 수치만 이날 재측정했다 — youtube-service는 2026-08-28, 나머지 모듈은 2026-08-27 실측값을 그대로 유지한다(아래 "측정 방법 및 환경" 참고).

---

## 측정 방법 및 환경

이 문서의 테스트 수치는 추정이 아니라 실제로 실행한 결과입니다. shared/reader/writer/ml-service는 2026-08-27 실측값을, youtube-service는 2026-08-28 실측값을 유지하며, backend만 2026-09-03에 재측정했다(이 브랜치가 유일하게 건드린 모듈이라 다른 모듈은 재측정 대상이 아니다). 2026-08-28 재측정 시점에는 Docker가 실행 중이 아니어서 writer의 Testcontainers 테스트가 다시 스킵되는 것을 확인했지만 — 이는 회귀가 아니라 아래 "Docker" 항목에 적힌 환경 의존성이 재현된 것이며, writer 행은 Docker가 떠 있던 2026-08-27 실측값(85 GREEN / 1 RED / 0 SKIP)을 그대로 둔다.

| 대상 | 실행 명령 | 집계 방식 | 최종 실측일 |
|------|---------|---------|---------|
| shared / reader / writer / backend | `./services/writer/mvnw -o test -fae` (루트 애그리게이터가 4개 모듈 모두 빌드) | `target/surefire-reports/*.xml` 파싱 | 2026-08-27 |
| youtube-service | `npm test` (`jest --testPathPattern=tests/`) | jest JSON 리포터 | 2026-08-28 |
| ml-service | `python -m pytest tests -q` | pytest 요약 | 2026-08-27 |

**환경 의존 항목** — 아래 두 항목은 코드 결함이 아니라 실행 환경에 따라 갈립니다.

- **Docker**: `TrailCommandHandlerPostgresTest` 5개는 Docker가 실행 중이어야 통과한다. 2026-08-27에는 Docker Desktop을 띄우고 재실행해 **5개 전부 통과**를 확인했다(실 PostgreSQL 제약 이름과 예외 매핑이 하드코딩된 추정대로 맞음). 아래 표는 그 실측값이다. 2026-08-28에는 Docker가 꺼져 있어 같은 5개가 다시 스킵되는 것을 확인했는데, 이는 코드 회귀가 아니라 이 환경 의존성 자체이므로 표 값은 바꾸지 않았다.
- **DB 미기동 (미해소)**: `ReaderApplicationTests.contextLoads`, `WriterApplicationTests.contextLoads` 2개가 실패합니다. 원인은 `Unable to determine Dialect without JDBC metadata` — `@SpringBootTest`가 실제 DataSource를 요구하는데 테스트 프로파일에 JDBC URL이 없습니다. Docker를 띄운 상태에서도 동일하게 실패하므로 **Docker 유무와 무관한 테스트 설정 문제**입니다. 2026-08-28 재실행에서도 동일하게 재현되어 회귀가 없음을 확인했다.

즉 아래 표의 RED 2개는 모두 reader·writer의 테스트 설정 문제이며, youtube-service의 RED는 이번 구현으로 0개가 되었다.

---

## 배경 및 주의사항

API 명세서를 기반으로 TDD(Red → Green → Refactor) 방식으로 테스트 코드를 작성하였다.
테스트 코드 작성 과정에서 **테스트가 컴파일되기 위해 필요한 소스 코드 스텁도 함께 생성**되었다.

- **의도**: 테스트 코드만 작성
- **실제**: 테스트 코드 + 소스 코드 스텁 (컴파일 가능한 최소 구조) 동시 생성

2026-08-28 기준으로 모든 스텁이 구현으로 대체되었다. 마지막까지 `throw new Error('Not implemented')`를 던지던 `apps/youtube-service`의 `src/app.js`(`POST /download`, `GET /status/:jobId`)도 캡처 파이프라인(capture/storage/jobs/pipeline) 구현으로 대체되어, 저장소 전체에 미구현 스텁이 남아 있지 않다.

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
| `src/test/.../config/HttpClientConfigTest.java` | `MockRestServiceServer` | 1 | 1 | 0 |
| `src/test/.../HealthCheckTest.java` | `@WebMvcTest` | 3 | 3 | 0 |
| `src/test/.../controller/StreamControllerTest.java` | `@WebMvcTest` + `@MockBean` | 7 | 7 | 0 |
| `src/test/.../controller/TrailControllerTest.java` | `@WebMvcTest` + `@MockBean` | 8 | 8 | 0 |
| `src/test/.../controller/CaptureControllerTest.java` | `@WebMvcTest` + `@MockBean` | 11 | 11 | 0 |
| `src/test/.../service/CaptureServiceImplTest.java` | Mockito 단위 테스트 + `MockRestServiceServer` | 12 | 12 | 0 |
| `src/test/.../service/StreamServiceImplTest.java` | Mockito 단위 테스트 | 5 | 5 | 0 |
| `src/test/.../service/TrailServiceImplTest.java` | Mockito 단위 테스트 | 7 | 7 | 0 |
| **합계** | | **55** | **55** | **0** |

---

### apps/youtube-service (Node.js/Express)

#### 소스 코드

| 파일 | 종류 | 현재 상태 |
|------|------|---------|
| `src/app.js` | Express 앱 | 구현 완료. `POST /download`(`interval_sec` 미수신 — 스케줄러가 별도 담당), `GET /status/:jobId`(`failed` 시 `error` 필드 포함) |
| `src/capture.js` | 캡처 어댑터 | 구현 완료. ffmpeg를 spawn해 프레임 1장을 stdout으로 뽑는다. `CAPTURE_SOURCE`(`testsrc`/`file`/`hls`)로 소스를 고른다. **YouTube 어댑터는 없다** — YouTube에서 프레임을 추출하는 것은 이용약관 위반이라 설계 단계에서 만들지 않기로 했다 |
| `src/storage.js` | MinIO 저장소 어댑터 | 구현 완료. `captures/{streamId}/{trailId}/{ISO8601}.jpg` 키로 S3 호환 API(MinIO)에 업로드 |
| `src/jobs.js` | 작업 상태 저장소 | 구현 완료. Redis에 TTL 1시간으로 저장 — 이전의 프로세스 메모리 Map은 정리 로직이 없어 무한히 쌓이는 문제가 있었다 |
| `src/pipeline.js` | 캡처 파이프라인 | 구현 완료. capture → storage 업로드 → jobs 상태 갱신 → Kafka `image.downloaded` 발행을 조립한다. 각 단계 실패를 잡아 작업을 `failed`로 기록하며, 그 기록 자체가 실패해도 던지지 않는다 |
| `src/kafka.js` | Kafka 프로듀서 | 구현 완료 (`connectKafka`, `publishMessage`) |
| `src/index.js` | 엔트리포인트 | 구현 완료. `app.js`를 import해 의존성(jobs/capture/storage/pipeline)을 조립하는 하나의 앱으로 통합됨 — 이전에 있던 중복 정의는 해소됐다 |

#### 테스트 코드

| 파일 | 방식 | 전체 | GREEN | RED |
|------|------|-----|-------|-----|
| `tests/app.test.js` | Jest + Supertest | 10 | 10 | 0 |
| `tests/capture.test.js` | Jest (`child_process` mock) | 4 | 4 | 0 |
| `tests/storage.test.js` | Jest (S3 client mock) | 7 | 7 | 0 |
| `tests/jobs.test.js` | Jest (ioredis mock) | 6 | 6 | 0 |
| `tests/pipeline.test.js` | Jest (의존성 mock) | 10 | 10 | 0 |
| `tests/kafka.test.js` | Jest (kafkajs mock) | 10 | 10 | 0 |
| **합계** | | **47** | **47** | **0** |

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
| `src/test/.../controller/StreamControllerTest.java` | `@WebMvcTest` + `@MockBean` | 5 | 5 | 0 | 0 |
| `src/test/.../controller/TrailControllerTest.java` | `@WebMvcTest` + `@MockBean` | 5 | 5 | 0 | 0 |
| `src/test/.../security/InternalKeyFilterTest.java` | 서블릿 목 단위 테스트 | 5 | 5 | 0 | 0 |
| `src/test/.../repository/CaptureRepositoryTest.java` | `@DataJpaTest` + H2 | 8 | 8 | 0 | 0 |
| **합계** | | **92** | **91** | **1** | **0** |

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

## 테스트 현황 요약 (backend·writer는 2026-09-03, youtube-service는 2026-08-28, 나머지는 2026-08-27 실측)

| 서비스 | 전체 테스트 수 | GREEN | RED | SKIP |
|--------|-------------|-------|-----|------|
| backend | 55 | 55 | 0 | 0 |
| youtube-service | 47 | 47 | 0 | 0 |
| ml-service | 28 | 28 | 0 | 0 |
| reader | 41 | 40 | 1 | 0 |
| writer | 92 | 91 | 1 | 0 |
| shared | 32 | 32 | 0 | 0 |
| **합계** | **295** | **293** | **2** | **0** |

> **직전 갱신 대비 변화(2026-09-03 두 번째)**: writer의 `/internal/**`에 `X-Internal-Key` 검사를 넣었다(`InternalKeyFilter`). 그전까지 이 검사는 게이트웨이에만 있었는데 writer 포트가 호스트로 열려 있어 우회가 가능했다. writer 86 → 92(필터 5 + 컨트롤러 배선 401 1), backend 54 → 55(`writerRestClient`가 헤더를 싣는지 확인). 기존 writer 컨트롤러 테스트 9개에는 키 헤더를 추가했다 — 이제 그것이 실제 계약이다.
>
> **2026-08-28 대비 변화**: backend에 캡처 트리거 엔드포인트(`POST /api/captures/jobs`, `GET /api/captures/jobs/{jobId}`)를 더해 테스트가 42개에서 54개로 늘었다. `CaptureServiceImplTest`의 새 테스트 5개만 `MockRestServiceServer`를 쓴다 — 기존 딥 스텁 목은 나가는 JSON 본문을 볼 수 없는데 이 경로의 핵심이 `source_url` → `youtube_url` 매핑이라 실제 직렬화를 확인해야 한다.
>
> **2026-08-27 → 2026-08-28 변화**: youtube-service의 캡처 파이프라인(capture/storage/jobs/pipeline) 구현으로 RED 11개가 사라지고 테스트가 22개에서 47개로 늘었다(capture/storage/jobs/pipeline 테스트 파일 신설, kafka.test.js는 12개에서 10개로 재구성). 남은 RED 2개는 reader/writer의 `contextLoads` — 테스트 환경 설정 문제로 이번 구현과 무관하다.
>
> **2026-08-16 대비 변화**: 당시 표는 총 122개(GREEN 85 / RED 37)였다. 그 표는 스텁 생성 당시 존재하던 파일만 집계했고, 이후 추가된 Stream/Trail/Geometry 관련 테스트와 `packages/shared` 모듈이 빠져 있었다.

---

## RED 테스트 상세 — 구현이 필요한 항목

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

2. YouTube Service — 완료 (2026-08-28)
   - app.js의 POST /download 구현 (jobs 저장소 + 캡처 파이프라인 트리거) — 완료
   - app.js의 GET /status/:jobId 구현 (`failed` 시 `error` 필드 포함) — 완료
   - index.js와 app.js 통합 (중복 앱 정의 해소) — 완료
   - ffmpeg 기반 capture.js, MinIO storage.js, Redis jobs.js, pipeline.js 조립 — 완료

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
- youtube-service 캡처 파이프라인 RED 11개 해소 — 완료 (2026-08-28, 설계: `docs/superpowers/sdd/2026-08-27-youtube-capture/`). ffmpeg로 프레임을 캡처해 MinIO에 업로드하고 Kafka `image.downloaded`를 발행한다. YouTube에서 직접 프레임을 추출하는 어댑터는 이용약관 문제로 만들지 않기로 했다(`CAPTURE_SOURCE=testsrc|file|hls`). 이로써 저장소에 미구현 스텁이 하나도 남지 않는다
