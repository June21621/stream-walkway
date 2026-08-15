# TDD 작업 내역 정리

> 기준 API 명세: `docs/api-specs/stream-walkway.postman_collection.json`
> 작성일: 2026-03-09

---

## 배경 및 주의사항

API 명세서를 기반으로 TDD(Red → Green → Refactor) 방식으로 테스트 코드를 작성하였다.
테스트 코드 작성 과정에서 **테스트가 컴파일되기 위해 필요한 소스 코드 스텁도 함께 생성**되었다.

- **의도**: 테스트 코드만 작성
- **실제**: 테스트 코드 + 소스 코드 스텁 (컴파일 가능한 최소 구조) 동시 생성

소스 코드 스텁은 모두 `UnsupportedOperationException` 또는 `throw new Error('Not implemented')`를 던지도록 되어 있으며, 실제 구현은 되어 있지 않다.

---

## 생성된 파일 전체 목록

### apps/backend (Spring Boot)

#### 소스 코드 (신규 생성 — 스텁)

| 파일 | 종류 | 설명 |
|------|------|------|
| `src/main/java/.../model/Stream.java` | Model | id, name, location, created_at |
| `src/main/java/.../model/Trail.java` | Model | id, stream_id, camera_number, location, direction, status, created_at |
| `src/main/java/.../model/Capture.java` | Model | id, trail_id, stream_id, image_path, road_status, confidence, created_at, updated_at |
| `src/main/java/.../service/StreamService.java` | Interface | findAll(), findById(), create() |
| `src/main/java/.../service/TrailService.java` | Interface | findAll(streamId), findById(), create() |
| `src/main/java/.../service/CaptureService.java` | Interface | findAll(streamId, trailId, limit, sort), findById() |
| `src/main/java/.../controller/StreamController.java` | Controller 스텁 | 모든 메서드 UnsupportedOperationException |
| `src/main/java/.../controller/TrailController.java` | Controller 스텁 | 모든 메서드 UnsupportedOperationException |
| `src/main/java/.../controller/CaptureController.java` | Controller 스텁 | 모든 메서드 UnsupportedOperationException |

#### 테스트 코드 (신규 생성)

| 파일 | 방식 | 테스트 수 | 상태 |
|------|------|-----------|------|
| `src/test/.../HealthCheckTest.java` | `@WebMvcTest` | 3 | GREEN |
| `src/test/.../controller/StreamControllerTest.java` | `@WebMvcTest` + `@MockBean` | 6 | RED |
| `src/test/.../controller/TrailControllerTest.java` | `@WebMvcTest` + `@MockBean` | 7 | RED |
| `src/test/.../controller/CaptureControllerTest.java` | `@WebMvcTest` + `@MockBean` | 5 | RED |

---

### apps/youtube-service (Node.js/Express)

#### 소스 코드 (신규 생성 — 스텁)

| 파일 | 종류 | 설명 |
|------|------|------|
| `src/app.js` | Express 앱 | index.js에서 app 분리. POST /download, GET /status/:jobId 스텁 포함 |

#### 설정 변경

| 파일 | 변경 내용 |
|------|---------|
| `package.json` | devDependencies에 `jest@^29`, `supertest@^7` 추가 / `test` 스크립트 추가 |

#### 테스트 코드 (신규 생성)

| 파일 | 방식 | 테스트 수 | 상태 |
|------|------|-----------|------|
| `tests/app.test.js` | Jest + Supertest | 10 | GREEN 1 / RED 9 |
| `tests/kafka.test.js` | Jest (kafkajs mock) | 12 | GREEN 10 / RED 2 |

---

### apps/ml-service (Python/FastAPI)

#### 설정 변경

| 파일 | 변경 내용 |
|------|---------|
| `requirements.txt` | `pytest`, `pytest-asyncio`, `httpx` 추가 |

#### 테스트 코드 (신규 생성)

| 파일 | 방식 | 테스트 수 | 상태 |
|------|------|-----------|------|
| `tests/__init__.py` | — | — | — |
| `tests/test_main.py` | pytest + FastAPI TestClient | 12 | GREEN 1 / RED 11 |
| `tests/test_consume.py` | pytest + AsyncMock | 15 | GREEN 12 / RED 3 |

---

### services/reader (Spring Boot)

#### 설정 변경

| 파일 | 변경 내용 |
|------|---------|
| `pom.xml` | `h2` test scope 추가 (`@DataJpaTest` 용) |

#### 테스트 코드 (신규 생성)

| 파일 | 방식 | 테스트 수 | 상태 |
|------|------|-----------|------|
| `src/test/.../HealthCheckTest.java` | `@WebMvcTest` | 3 | GREEN |
| `src/test/.../controller/CaptureControllerTest.java` | `@WebMvcTest` + `@MockBean` | 6 | GREEN |
| `src/test/.../entity/CaptureTest.java` | 순수 단위 테스트 (리플렉션) | 8 | GREEN |
| `src/test/.../repository/CaptureRepositoryTest.java` | `@DataJpaTest` + H2 + `@Sql` | 9 | GREEN |

---

### services/writer (Spring Boot)

#### 설정 변경

| 파일 | 변경 내용 |
|------|---------|
| `pom.xml` | `h2` test scope 추가 (`@DataJpaTest` 용) |

#### 테스트 코드 (신규 생성)

| 파일 | 방식 | 테스트 수 | 상태 |
|------|------|-----------|------|
| `src/test/.../HealthCheckTest.java` | `@WebMvcTest` | 3 | GREEN |
| `src/test/.../consumer/ImageAnalyzedConsumerTest.java` | Mockito 단위 테스트 | 7 | GREEN |
| `src/test/.../entity/CaptureTest.java` | 순수 단위 테스트 (리플렉션) | 9 | GREEN |
| `src/test/.../repository/CaptureRepositoryTest.java` | `@DataJpaTest` + H2 + `TestEntityManager` | 7 | GREEN |

---

## 테스트 현황 요약

| 서비스 | 전체 테스트 수 | GREEN | RED |
|--------|-------------|-------|-----|
| backend | 21 | 3 | 18 |
| youtube-service | 22 | 11 | 11 |
| ml-service | 27 | 13 | 14 |
| reader | 26 | 26 | 0 |
| writer | 26 | 26 | 0 |
| **합계** | **122** | **79** | **43** |

---

## RED 테스트 상세 — 구현이 필요한 항목

### Backend — 18개 RED

| 대상 | 필요한 구현 |
|------|-----------|
| `StreamController.getAll()` | StreamService.findAll() 호출 후 200 반환 |
| `StreamController.getById()` | StreamService.findById() 호출, 없으면 404 + `{"error": "Stream not found", "id": N}` |
| `StreamController.create()` | X-Internal-Key 검증, StreamService.create() 호출 후 201 반환 |
| `TrailController.getAll()` | stream_id 필터 포함, TrailService.findAll() 호출 후 200 반환 |
| `TrailController.getById()` | TrailService.findById() 호출, 없으면 404 + `{"error": "Trail not found", "id": N}` |
| `TrailController.create()` | X-Internal-Key 검증, TrailService.create() 호출 후 201 반환 |
| `CaptureController.getAll()` | stream_id, trail_id, limit, sort 파라미터 처리 후 200 반환 |
| `CaptureController.getById()` | CaptureService.findById() 호출, 없으면 404 + `{"error": "Capture not found", "id": N}` |
| `StreamService` 구현체 | 인터페이스 구현 (DB 연동 포함) |
| `TrailService` 구현체 | 인터페이스 구현 (DB 연동 포함) |
| `CaptureService` 구현체 | 인터페이스 구현 (DB 연동 포함) |

### YouTube Service — 11개 RED

| 대상 | 필요한 구현 |
|------|-----------|
| `POST /download` | 요청 검증, jobStore 저장, 202 반환 |
| `GET /status/:jobId` | jobStore 조회, 없으면 404 반환 |
| `/download` → Kafka 발행 | 다운로드 완료 시 `publishMessage('image.downloaded', {...})` 호출 |

### ML Service — 14개 RED

| 대상 | 필요한 구현 |
|------|-----------|
| `GET /health` | 응답을 `{"status": "healthy", "model": "loaded", "uptime_sec": N}` 형식으로 변경 |
| `POST /analyze` | Pydantic 요청 모델 정의, jobId 생성, 202 반환 |
| `consume()` 예외 처리 | `async for msg in consumer` 내부에 `try/except` 추가하여 JSON 파싱 에러 무시 |

---

## 다음 단계 (GREEN 전환 순서 권장)

```
1. ML Service
   - GET /health 응답 형식 수정 (간단한 변경)
   - POST /analyze 엔드포인트 추가
   - consume() 내부 try/except 추가

2. YouTube Service
   - app.js의 POST /download 구현
   - app.js의 GET /status/:jobId 구현

3. Backend
   - @ControllerAdvice 에러 핸들러 추가 (404 body 반환)
   - StreamService, TrailService, CaptureService 구현체 작성
   - 각 Controller에서 서비스 호출하도록 구현
   - pom.xml에 JPA, PostgreSQL 의존성 추가 (DB 연동 시)
```
