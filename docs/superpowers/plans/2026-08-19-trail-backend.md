# Trail Backend Gateway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `apps/backend`의 기존 RED 스텁(`TrailController`/`TrailService`/`Trail` 모델/`TrailControllerTest`, 2026-03-09 작성)을 실제로 동작하게 만든다. `TrailServiceImpl`이 이미 `main`에 merge된 reader(`GET /trails`, `GET /trails/{id}`)와 writer(`POST /internal/trails`)를 `RestClient`로 호출해서 응답한다.

**Architecture:** `apps/backend`는 DB에 직접 접근하지 않는 게이트웨이다. 이미 Stream 작업에서 만들어진 `HttpClientConfig`의 `readerRestClient`/`writerRestClient` Bean을 그대로 재사용해서 `TrailServiceImpl`을 작성하고(`StreamServiceImpl`과 동일한 구조), 기존 `TrailController` 스텁의 `UnsupportedOperationException`을 실제 호출로 교체한다. `X-Internal-Key` 검증, 새 예외 3개(`TrailNotFoundException`/`InvalidTrailGeometryException`/`DuplicateTrailException`), `GlobalExceptionHandler` 확장이 함께 들어간다.

**Tech Stack:** Java 21, Spring Boot 3.5.x, Spring `RestClient`, JUnit 5, Mockito, AssertJ

## Global Constraints

- **DB 계층은 이미 `main`에 있다.** `packages/shared`(`Trail` 엔티티, `TrailView` DTO), `services/writer`(`POST /internal/trails`), `services/reader`(`GET /trails`, `GET /trails/{id}`)는 이전 계획(`docs/superpowers/plans/2026-08-16-trail-cqrs.md`)에서 이미 구현되어 `main`에 merge/push됨. 이 계획은 `apps/backend`만 다룬다.
- **`TrailController`/`TrailService`/`Trail`(모델)의 기존 시그니처는 고정 계약이다.** 메서드 이름, URL 경로(`/api/trails`), JSON 필드명(`stream_id`/`camera_number`/`created_at` 등 snake_case, `@JsonProperty`로 이미 매핑됨)은 바꾸지 않는다. 유일한 예외는 아래 "알려진 버그 수정" 항목.
- **알려진 버그 수정 1**: 기존 `TrailController.getAll()`의 `@RequestParam(required = false) Long streamId`에는 `value`가 없어서 `?stream_id=1` 쿼리가 자바 파라미터명(`streamId`, camelCase)으로 바인딩되길 시도해 조용히 무시된다. `value = "stream_id"`를 추가해서 고친다 (reader 쪽 동일 버그를 이미 이 방식으로 고쳤음).
- **알려진 버그 수정 2**: 기존 `TrailControllerTest`는 `X-Internal-Key` 값이 실제로 맞는지 검증하지 않는다(헤더 존재 여부만 확인, `@TestPropertySource` 없음). `StreamControllerTest`(backend)와 동일하게 `@TestPropertySource(properties = "internal.api-key=test-internal-key")`를 추가하고 "잘못된 키면 401" 테스트를 보강한다.
- **Kafka를 쓰지 않는다.** 전부 동기 HTTP.
- **`apps/backend`는 DB에 접근하지 않는다.** `application.yaml`의 `DataSourceAutoConfiguration`/`RedisAutoConfiguration` 제외 설정은 그대로 둔다. 새 Maven 의존성도 필요 없다 — `readerRestClient`/`writerRestClient` Bean은 `HttpClientConfig`(Stream 작업에서 이미 생성됨)에 이미 있다.
- 패키지 루트는 `com.stream.backend`. Java 21.
- 모든 신규/수정 클래스는 `mvnw`(이미 있는 Maven Wrapper)로 빌드/테스트한다.
- 기존 GREEN 테스트(`HealthCheckTest`, `StreamControllerTest`, `StreamServiceImplTest`, `CaptureControllerTest` 등)는 각 태스크가 끝날 때마다 계속 GREEN이어야 한다. `CaptureControllerTest`는 여전히 RED(Capture 게이트웨이 연동은 이 계획의 범위 밖)이므로 최종 검증에서 제외한다.

---

## Task 1: 새 예외 3개 + GlobalExceptionHandler 확장

**Files:**
- Create: `apps/backend/src/main/java/com/stream/backend/exception/TrailNotFoundException.java`
- Create: `apps/backend/src/main/java/com/stream/backend/exception/InvalidTrailGeometryException.java`
- Create: `apps/backend/src/main/java/com/stream/backend/exception/DuplicateTrailException.java`
- Modify: `apps/backend/src/main/java/com/stream/backend/exception/GlobalExceptionHandler.java`

**Interfaces:**
- Produces: `TrailNotFoundException(Long id)`(`getId()` 제공), `InvalidTrailGeometryException(String message)`, `DuplicateTrailException(String message)` — 각각 `StreamNotFoundException`/`InvalidStreamGeometryException`과 동일 구조. `GlobalExceptionHandler`가 이 3개를 각각 404/400/409로 변환.

이 3개 예외 클래스는 단순 데이터 캐리어라 기존 `StreamNotFoundException`/`InvalidStreamGeometryException`(둘 다 대응하는 전용 테스트 파일 없음)과 동일하게 별도 테스트 없이 Task 3의 `TrailControllerTest`로 간접 검증한다.

- [ ] **Step 1: TrailNotFoundException을 작성한다**

`apps/backend/src/main/java/com/stream/backend/exception/TrailNotFoundException.java`:

```java
package com.stream.backend.exception;

public class TrailNotFoundException extends RuntimeException {

    private final Long id;

    public TrailNotFoundException(Long id) {
        super("Trail not found: " + id);
        this.id = id;
    }

    public Long getId() {
        return id;
    }
}
```

- [ ] **Step 2: InvalidTrailGeometryException을 작성한다**

`apps/backend/src/main/java/com/stream/backend/exception/InvalidTrailGeometryException.java`:

```java
package com.stream.backend.exception;

public class InvalidTrailGeometryException extends RuntimeException {

    public InvalidTrailGeometryException(String message) {
        super(message);
    }
}
```

- [ ] **Step 3: DuplicateTrailException을 작성한다**

`apps/backend/src/main/java/com/stream/backend/exception/DuplicateTrailException.java`:

```java
package com.stream.backend.exception;

public class DuplicateTrailException extends RuntimeException {

    public DuplicateTrailException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: GlobalExceptionHandler에 핸들러 3개를 추가한다**

`apps/backend/src/main/java/com/stream/backend/exception/GlobalExceptionHandler.java` 전체를 아래로 교체 (기존 3개 핸들러는 그대로 유지하고 끝에 3개 추가):

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

    @ExceptionHandler(InvalidStreamGeometryException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidStreamGeometry(InvalidStreamGeometryException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Invalid stream geometry", "message", e.getMessage()));
    }

    @ExceptionHandler(InvalidInternalKeyException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidInternalKey(InvalidInternalKeyException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Unauthorized", "message", e.getMessage()));
    }

    @ExceptionHandler(TrailNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleTrailNotFound(TrailNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Trail not found", "id", e.getId()));
    }

    @ExceptionHandler(InvalidTrailGeometryException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidTrailGeometry(InvalidTrailGeometryException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Invalid trail data", "message", e.getMessage()));
    }

    @ExceptionHandler(DuplicateTrailException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateTrail(DuplicateTrailException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Duplicate trail", "message", e.getMessage()));
    }
}
```

- [ ] **Step 5: backend 컴파일 확인 (아직 이 예외들을 쓰는 코드가 없으므로 컴파일만 검증)**

Run: `cd apps/backend && ./mvnw -q -B compile`
Expected: 컴파일 성공 (에러 없음)

- [ ] **Step 6: 커밋**

```bash
git add apps/backend/src/main/java/com/stream/backend/exception/TrailNotFoundException.java apps/backend/src/main/java/com/stream/backend/exception/InvalidTrailGeometryException.java apps/backend/src/main/java/com/stream/backend/exception/DuplicateTrailException.java apps/backend/src/main/java/com/stream/backend/exception/GlobalExceptionHandler.java
git commit -m "feat(backend): Trail 전용 예외 3개 추가 및 GlobalExceptionHandler 확장"
```

---

## Task 2: TrailServiceImpl (reader/writer 동기 HTTP 연동)

**Files:**
- Create: `apps/backend/src/main/java/com/stream/backend/service/TrailServiceImpl.java`
- Test: `apps/backend/src/test/java/com/stream/backend/service/TrailServiceImplTest.java`

**Interfaces:**
- Consumes: `com.stream.shared.dto.TrailView`(이미 `main`에 있음), reader의 `GET /trails`(선택적 `stream_id` 쿼리)·`GET /trails/{id}`, writer의 `POST /internal/trails`(이미 `main`에 있음), `TrailNotFoundException`/`InvalidTrailGeometryException`/`DuplicateTrailException`(Task 1)
- Produces: 기존 `TrailService` 인터페이스(`findAll(Long streamId)`, `findById(Long)`, `create(Trail)`, 변경 없음)를 구현하는 `TrailServiceImpl`

- [ ] **Step 1: TrailServiceImpl 테스트를 먼저 작성한다**

`apps/backend/src/test/java/com/stream/backend/service/TrailServiceImplTest.java`:

```java
package com.stream.backend.service;

import com.stream.backend.exception.DuplicateTrailException;
import com.stream.backend.exception.InvalidTrailGeometryException;
import com.stream.backend.model.Trail;
import com.stream.shared.dto.TrailView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
@DisplayName("Backend - TrailServiceImpl 테스트")
class TrailServiceImplTest {

    @Test
    @DisplayName("findAll(null) - reader의 GET /trails 응답을 Trail 모델 리스트로 변환한다")
    void findAll_withNullStreamId_mapsReaderResponseToTrailList() {
        // given
        RestClient readerClient = mock(RestClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        RestClient writerClient = mock(RestClient.class);
        TrailView view = new TrailView(1L, 1L, "CAM-001", "POINT(126.97 37.55)", "북", "active",
                LocalDateTime.of(2024, 1, 1, 0, 0, 0));

        given(readerClient.get()
                .uri("/trails")
                .retrieve()
                .body(any(org.springframework.core.ParameterizedTypeReference.class)))
                .willReturn(List.of(view));

        TrailServiceImpl service = new TrailServiceImpl(readerClient, writerClient);

        // when
        List<Trail> result = service.findAll(null);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(1L);
        assertThat(result.get(0).getStreamId()).isEqualTo(1L);
        assertThat(result.get(0).getCameraNumber()).isEqualTo("CAM-001");
        assertThat(result.get(0).getLocation()).isEqualTo("POINT(126.97 37.55)");
        assertThat(result.get(0).getDirection()).isEqualTo("북");
        assertThat(result.get(0).getStatus()).isEqualTo("active");
        assertThat(result.get(0).getCreatedAt()).isEqualTo("2024-01-01T00:00:00");
    }

    @Test
    @DisplayName("findAll(streamId) - reader의 GET /trails?stream_id= 를 호출한다")
    void findAll_withStreamId_callsFilteredEndpoint() {
        // given
        RestClient readerClient = mock(RestClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        RestClient writerClient = mock(RestClient.class);
        TrailView view = new TrailView(1L, 1L, "CAM-001", "POINT(126.97 37.55)", "북", "active",
                LocalDateTime.of(2024, 1, 1, 0, 0, 0));

        given(readerClient.get()
                .uri("/trails?stream_id={streamId}", 1L)
                .retrieve()
                .body(any(org.springframework.core.ParameterizedTypeReference.class)))
                .willReturn(List.of(view));

        TrailServiceImpl service = new TrailServiceImpl(readerClient, writerClient);

        // when
        List<Trail> result = service.findAll(1L);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStreamId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("findById() - reader가 404를 주면 빈 Optional을 반환한다")
    void findById_returnsEmptyOnReader404() {
        // given
        RestClient readerClient = mock(RestClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        RestClient writerClient = mock(RestClient.class);

        given(readerClient.get()
                .uri("/trails/{id}", 999L)
                .retrieve()
                .body(TrailView.class))
                .willThrow(org.springframework.web.client.HttpClientErrorException.NotFound.class);

        TrailServiceImpl service = new TrailServiceImpl(readerClient, writerClient);

        // when
        Optional<Trail> result = service.findById(999L);

        // then
        assertThat(result).isEmpty();
    }

    // writerClient.post().uri(...).contentType(...).body(...).retrieve() 체인을 deep-stub 대신
    // 단계별로 직접 mock해서 연결한다 (Stream 작업에서 deep-stub 체이닝이 실제 프로덕션 호출과
    // 어긋나 null을 반환하는 문제가 있었음 - 각 인터페이스를 명시적으로 mock하면 확실히 매칭된다).
    private RestClient.ResponseSpec stubWriterCreateCall(RestClient writerClient) {
        RestClient.RequestBodyUriSpec bodyUriSpec =
                mock(RestClient.RequestBodyUriSpec.class, org.mockito.Answers.RETURNS_SELF);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        given(writerClient.post()).willReturn(bodyUriSpec);
        given(bodyUriSpec.retrieve()).willReturn(responseSpec);
        return responseSpec;
    }

    @Test
    @DisplayName("create() - writer의 POST /internal/trails 응답을 Trail 모델로 변환한다")
    void create_mapsWriterResponseToTrail() {
        // given
        RestClient readerClient = mock(RestClient.class);
        RestClient writerClient = mock(RestClient.class);
        TrailView created = new TrailView(1L, 1L, "CAM-001", "POINT(126.97 37.55)", "북", "active",
                LocalDateTime.of(2024, 1, 1, 0, 0, 0));

        RestClient.ResponseSpec responseSpec = stubWriterCreateCall(writerClient);
        given(responseSpec.body(TrailView.class)).willReturn(created);

        TrailServiceImpl service = new TrailServiceImpl(readerClient, writerClient);
        Trail input = new Trail(null, 1L, "CAM-001", "POINT(126.97 37.55)", "북", "active", null);

        // when
        Trail result = service.create(input);

        // then
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getStreamId()).isEqualTo(1L);
        assertThat(result.getCameraNumber()).isEqualTo("CAM-001");
        assertThat(result.getStatus()).isEqualTo("active");
        assertThat(result.getCreatedAt()).isEqualTo("2024-01-01T00:00:00");
    }

    @Test
    @DisplayName("create() - writer가 400을 반환하면 InvalidTrailGeometryException을 던진다")
    void create_throwsInvalidTrailGeometryExceptionOnWriter400() {
        // given
        RestClient readerClient = mock(RestClient.class);
        RestClient writerClient = mock(RestClient.class);

        RestClient.ResponseSpec responseSpec = stubWriterCreateCall(writerClient);
        given(responseSpec.body(TrailView.class))
                .willThrow(org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Bad Request",
                        org.springframework.http.HttpHeaders.EMPTY,
                        "Invalid trail data".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.UTF_8));

        TrailServiceImpl service = new TrailServiceImpl(readerClient, writerClient);
        Trail input = new Trail(null, 1L, "CAM-001", "NOT-A-WKT", "북", "active", null);

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                InvalidTrailGeometryException.class,
                () -> service.create(input));
    }

    @Test
    @DisplayName("create() - writer가 409를 반환하면 DuplicateTrailException을 던진다")
    void create_throwsDuplicateTrailExceptionOnWriter409() {
        // given
        RestClient readerClient = mock(RestClient.class);
        RestClient writerClient = mock(RestClient.class);

        RestClient.ResponseSpec responseSpec = stubWriterCreateCall(writerClient);
        given(responseSpec.body(TrailView.class))
                .willThrow(org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "Conflict",
                        org.springframework.http.HttpHeaders.EMPTY,
                        "stream_id=1, camera_number=CAM-001 already exists".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.UTF_8));

        TrailServiceImpl service = new TrailServiceImpl(readerClient, writerClient);
        Trail input = new Trail(null, 1L, "CAM-001", "POINT(126.97 37.55)", "북", "active", null);

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                DuplicateTrailException.class,
                () -> service.create(input));
    }

    @Test
    @DisplayName("create() - writer가 null 응답을 반환하면 InvalidTrailGeometryException을 던진다")
    void create_throwsInvalidTrailGeometryExceptionOnNullResponse() {
        // given
        RestClient readerClient = mock(RestClient.class);
        RestClient writerClient = mock(RestClient.class);

        RestClient.ResponseSpec responseSpec = stubWriterCreateCall(writerClient);
        given(responseSpec.body(TrailView.class)).willReturn(null);

        TrailServiceImpl service = new TrailServiceImpl(readerClient, writerClient);
        Trail input = new Trail(null, 1L, "CAM-001", "POINT(126.97 37.55)", "북", "active", null);

        // when & then
        org.junit.jupiter.api.Assertions.assertThrows(
                InvalidTrailGeometryException.class,
                () -> service.create(input));
    }
}
```

- [ ] **Step 2: 테스트 실행 → 컴파일 실패 확인**

Run: `cd apps/backend && ./mvnw -q -B test -Dtest=TrailServiceImplTest`
Expected: FAIL — `TrailServiceImpl` 클래스가 없어서 컴파일 에러

- [ ] **Step 3: TrailServiceImpl을 작성한다**

`apps/backend/src/main/java/com/stream/backend/service/TrailServiceImpl.java`:

```java
package com.stream.backend.service;

import com.stream.backend.exception.DuplicateTrailException;
import com.stream.backend.exception.InvalidTrailGeometryException;
import com.stream.backend.model.Trail;
import com.stream.shared.dto.TrailView;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
public class TrailServiceImpl implements TrailService {

    private final RestClient readerClient;
    private final RestClient writerClient;

    public TrailServiceImpl(@Qualifier("readerRestClient") RestClient readerClient,
                             @Qualifier("writerRestClient") RestClient writerClient) {
        this.readerClient = readerClient;
        this.writerClient = writerClient;
    }

    private Trail toModel(TrailView view) {
        return new Trail(
                view.id(),
                view.streamId(),
                view.cameraNumber(),
                view.location(),
                view.direction(),
                view.status(),
                view.createdAt() == null ? null : view.createdAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );
    }

    @Override
    public List<Trail> findAll(Long streamId) {
        List<TrailView> views;
        if (streamId == null) {
            views = readerClient.get()
                    .uri("/trails")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<TrailView>>() {});
        } else {
            views = readerClient.get()
                    .uri("/trails?stream_id={streamId}", streamId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<TrailView>>() {});
        }
        return views == null ? List.of() : views.stream().map(this::toModel).toList();
    }

    @Override
    public Optional<Trail> findById(Long id) {
        try {
            TrailView view = readerClient.get()
                    .uri("/trails/{id}", id)
                    .retrieve()
                    .body(TrailView.class);
            return Optional.ofNullable(view).map(this::toModel);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    @Override
    public Trail create(Trail trail) {
        TrailView created;
        try {
            created = writerClient.post()
                    .uri("/internal/trails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CreateTrailRequest(trail.getStreamId(), trail.getCameraNumber(),
                            trail.getLocation(), trail.getDirection(), trail.getStatus()))
                    .retrieve()
                    .body(TrailView.class);
        } catch (HttpClientErrorException.BadRequest e) {
            throw new InvalidTrailGeometryException("Writer rejected the trail data: " + e.getResponseBodyAsString());
        } catch (HttpClientErrorException.Conflict e) {
            throw new DuplicateTrailException("Writer rejected duplicate trail: " + e.getResponseBodyAsString());
        }
        if (created == null) {
            throw new InvalidTrailGeometryException("Writer returned an empty response for trail creation");
        }
        return toModel(created);
    }

    private record CreateTrailRequest(Long streamId, String cameraNumber, String location, String direction, String status) {}
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `cd apps/backend && ./mvnw -q -B test -Dtest=TrailServiceImplTest`
Expected: PASS (7개 테스트 전부 통과)

- [ ] **Step 5: 커밋**

```bash
git add apps/backend/src/main/java/com/stream/backend/service/TrailServiceImpl.java apps/backend/src/test/java/com/stream/backend/service/TrailServiceImplTest.java
git commit -m "feat(backend): TrailServiceImpl 구현 - reader/writer 동기 HTTP 연동"
```

---

## Task 3: TrailController 구현 (기존 RED 테스트 GREEN 전환 + X-Internal-Key 검증 보강)

**Files:**
- Modify: `apps/backend/src/main/java/com/stream/backend/controller/TrailController.java` (기존 RED 스텁)
- Modify: `apps/backend/src/test/java/com/stream/backend/controller/TrailControllerTest.java` (기존 파일, RED→GREEN + 보강)

**Interfaces:**
- Consumes: `TrailService`(변경 없음), `TrailServiceImpl`(Task 2), `TrailNotFoundException`/`InvalidInternalKeyException`(Task 1 및 기존)
- Produces: `TrailController`의 3개 엔드포인트(`GET /api/trails`, `GET /api/trails/{id}`, `POST /api/trails`)가 실제로 동작

- [ ] **Step 1: 기존 TrailControllerTest에 @TestPropertySource와 401 테스트를 추가한다**

`apps/backend/src/test/java/com/stream/backend/controller/TrailControllerTest.java` 전체를 아래로 교체 (기존 7개 테스트는 내용 변경 없이 그대로 유지, 클래스 애노테이션에 `@TestPropertySource` 추가, 맨 끝에 401 테스트 1개 추가):

```java
package com.stream.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stream.backend.model.Trail;
import com.stream.backend.service.TrailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TrailController.class)
@org.springframework.test.context.TestPropertySource(properties = "internal.api-key=test-internal-key")
@DisplayName("TrailController 테스트")
class TrailControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TrailService trailService;

    // ─────────────────────────────────────────
    // GET /api/trails
    // ─────────────────────────────────────────

    @Test
    @DisplayName("GET /api/trails - 전체 트레일 목록을 200 OK로 반환한다")
    void getAll_returns200WithTrailList() throws Exception {
        // given
        Trail trail = new Trail(1L, 1L, "CAM-001", "POINT(126.97 37.55)", "북", "active", "2024-01-01T00:00:00Z");
        given(trailService.findAll(null)).willReturn(List.of(trail));

        // when & then
        mockMvc.perform(get("/api/trails"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].stream_id").value(1))
                .andExpect(jsonPath("$[0].camera_number").value("CAM-001"))
                .andExpect(jsonPath("$[0].location").value("POINT(126.97 37.55)"))
                .andExpect(jsonPath("$[0].direction").value("북"))
                .andExpect(jsonPath("$[0].status").value("active"))
                .andExpect(jsonPath("$[0].created_at").value("2024-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("GET /api/trails?stream_id=1 - stream_id로 필터링된 트레일 목록을 반환한다")
    void getAll_returns200FilteredByStreamId() throws Exception {
        // given
        Trail trail = new Trail(1L, 1L, "CAM-001", "POINT(126.97 37.55)", "북", "active", "2024-01-01T00:00:00Z");
        given(trailService.findAll(1L)).willReturn(List.of(trail));

        // when & then
        mockMvc.perform(get("/api/trails").param("stream_id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].stream_id").value(1));
    }

    @Test
    @DisplayName("GET /api/trails - 트레일이 없으면 빈 배열을 200 OK로 반환한다")
    void getAll_returns200WithEmptyList() throws Exception {
        // given
        given(trailService.findAll(null)).willReturn(List.of());

        // when & then
        mockMvc.perform(get("/api/trails"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    // ─────────────────────────────────────────
    // GET /api/trails/{id}
    // ─────────────────────────────────────────

    @Test
    @DisplayName("GET /api/trails/{id} - 존재하는 트레일을 200 OK로 반환한다")
    void getById_returns200WhenFound() throws Exception {
        // given
        Trail trail = new Trail(1L, 1L, "CAM-001", "POINT(126.97 37.55)", "북", "active", "2024-01-01T00:00:00Z");
        given(trailService.findById(1L)).willReturn(Optional.of(trail));

        // when & then
        mockMvc.perform(get("/api/trails/1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.stream_id").value(1))
                .andExpect(jsonPath("$.camera_number").value("CAM-001"))
                .andExpect(jsonPath("$.direction").value("북"))
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    @DisplayName("GET /api/trails/{id} - 존재하지 않는 트레일은 404 Not Found와 에러 바디를 반환한다")
    void getById_returns404WhenNotFound() throws Exception {
        // given
        given(trailService.findById(999L)).willReturn(Optional.empty());

        // when & then
        mockMvc.perform(get("/api/trails/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Trail not found"))
                .andExpect(jsonPath("$.id").value(999));
    }

    // ─────────────────────────────────────────
    // POST /api/trails (내부 전용)
    // ─────────────────────────────────────────

    @Test
    @DisplayName("POST /api/trails - X-Internal-Key 헤더와 함께 트레일을 등록하면 201 Created를 반환한다")
    void create_returns201WithInternalKey() throws Exception {
        // given
        Trail created = new Trail(1L, 1L, "CAM-001", "POINT(126.97 37.55)", "북", "active", "2024-01-01T00:00:00Z");
        given(trailService.create(any(Trail.class))).willReturn(created);

        String requestBody = """
                {
                  "stream_id": 1,
                  "camera_number": "CAM-001",
                  "location": "POINT(126.97 37.55)",
                  "direction": "북",
                  "status": "active"
                }
                """;

        // when & then
        mockMvc.perform(post("/api/trails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Internal-Key", "test-internal-key")
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.stream_id").value(1))
                .andExpect(jsonPath("$.camera_number").value("CAM-001"))
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    @DisplayName("POST /api/trails - X-Internal-Key 헤더 없이 요청하면 400 Bad Request를 반환한다")
    void create_returns400WithoutInternalKey() throws Exception {
        // given
        String requestBody = """
                {
                  "stream_id": 1,
                  "camera_number": "CAM-001",
                  "location": "POINT(126.97 37.55)",
                  "direction": "북",
                  "status": "active"
                }
                """;

        // when & then
        mockMvc.perform(post("/api/trails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/trails - 잘못된 X-Internal-Key면 401 Unauthorized를 반환한다")
    void create_returns401WithWrongInternalKey() throws Exception {
        // given
        String requestBody = """
                {
                  "stream_id": 1,
                  "camera_number": "CAM-001",
                  "location": "POINT(126.97 37.55)",
                  "direction": "북",
                  "status": "active"
                }
                """;

        // when & then
        mockMvc.perform(post("/api/trails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Internal-Key", "wrong-key")
                        .content(requestBody))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인 (여전히 RED — 컨트롤러가 아직 UnsupportedOperationException을 던짐)**

Run: `cd apps/backend && ./mvnw -q -B test -Dtest=TrailControllerTest`
Expected: FAIL — 8개 테스트 중 다수가 `UnsupportedOperationException` 또는 401 미구현으로 실패

- [ ] **Step 3: TrailController를 구현으로 교체한다**

`apps/backend/src/main/java/com/stream/backend/controller/TrailController.java` 전체를 아래로 교체:

```java
package com.stream.backend.controller;

import com.stream.backend.exception.InvalidInternalKeyException;
import com.stream.backend.exception.TrailNotFoundException;
import com.stream.backend.model.Trail;
import com.stream.backend.service.TrailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/trails")
public class TrailController {

    private final TrailService trailService;
    private final String internalApiKey;

    public TrailController(TrailService trailService,
                            @Value("${internal.api-key}") String internalApiKey) {
        this.trailService = trailService;
        this.internalApiKey = internalApiKey;
    }

    @GetMapping
    public ResponseEntity<List<Trail>> getAll(@RequestParam(value = "stream_id", required = false) Long streamId) {
        return ResponseEntity.ok(trailService.findAll(streamId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        Trail trail = trailService.findById(id)
                .orElseThrow(() -> new TrailNotFoundException(id));
        return ResponseEntity.ok(trail);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Trail trail,
                                    @RequestHeader("X-Internal-Key") String internalKey) {
        if (!internalApiKey.equals(internalKey)) {
            throw new InvalidInternalKeyException();
        }
        Trail created = trailService.create(trail);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `cd apps/backend && ./mvnw -q -B test -Dtest=TrailControllerTest`
Expected: PASS (8개 테스트 전부 통과 — 기존 7개 + 새로 추가한 401 테스트)

- [ ] **Step 5: backend 전체 테스트 실행 → 회귀 없는지 확인**

Run: `cd apps/backend && ./mvnw -q -B test -Dtest='!CaptureControllerTest'`
Expected: 기존 `HealthCheckTest`/`StreamControllerTest`/`StreamServiceImplTest` 포함 전부 PASS. `CaptureControllerTest`는 Capture 게이트웨이 연동이 아직 없어 여전히 RED이므로 제외 (`BackendApplicationTests`도 Capture/Trail 스텁 잔존 여부에 따라 실패할 수 있어 필요시 함께 제외 — 아래 최종 검증에서 정확한 제외 목록을 재확인한다).

- [ ] **Step 6: 커밋**

```bash
git add apps/backend/src/main/java/com/stream/backend/controller/TrailController.java apps/backend/src/test/java/com/stream/backend/controller/TrailControllerTest.java
git commit -m "feat(backend): TrailController 구현 - RED 테스트 GREEN 전환, X-Internal-Key 값 검증 보강"
```

---

## Task 4 (계획 외 추가, 최종 브랜치 리뷰에서 발견): CaptureServiceImpl 스텁 추가

**이 태스크는 원래 계획에 없었다.** Task 1~3 완료 후 전체 브랜치 최종 리뷰(Opus)에서, `apps/backend`가 애초에 컨텍스트 로드조차 안 되는 상태라는 사실이 발견됐다 — `CaptureController`가 `CaptureService`를 생성자로 요구하는데 이를 구현하는 `CaptureServiceImpl`이 저장소 어디에도 없어서 Spring이 빈을 못 찾고 부팅에 실패했다.

이 문제 자체는 `main`에 이미 있던 사전 존재 이슈였고(Capture backend 연동이 원래 한 번도 구현된 적이 없었음) Task 1~3의 Trail 코드와는 무관했지만, 그 결과 "Trail 엔드포인트가 실제로 살아있다"는 이 계획의 목표 자체가 무색해지는 상태였다(서비스가 아예 안 뜨니까). 그래서 사용자 확인 후 최소 범위로 즉시 수정했다.

**Files:**
- Create: `apps/backend/src/main/java/com/stream/backend/service/CaptureServiceImpl.java`

**내용**: `CaptureService` 인터페이스의 `findAll(...)`/`findById(...)`를 그대로 구현하되, 둘 다 `throw new UnsupportedOperationException("Not implemented")`만 던지는 스텁(`CaptureController`의 기존 RED 스텁 메서드와 동일한 관례). `@Service`로 등록해서 Spring이 빈을 찾을 수 있게 하는 게 유일한 목적 — 실제 Capture 비즈니스 로직 구현은 아니며, `CaptureController` 자체는 건드리지 않았다.

- [ ] **Step 1: CaptureServiceImpl 작성**

```java
package com.stream.backend.service;

import com.stream.backend.model.Capture;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class CaptureServiceImpl implements CaptureService {

    @Override
    public List<Capture> findAll(Long streamId, Long trailId, Integer limit, String sort) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public Optional<Capture> findById(Long id) {
        throw new UnsupportedOperationException("Not implemented");
    }
}
```

- [ ] **Step 2: 검증**

Run: `cd apps/backend && ./mvnw -q -B test -Dtest=BackendApplicationTests`
Expected: PASS (이전엔 빈 누락으로 실패하던 컨텍스트 로드가 이제 성공)

Run: `cd apps/backend && ./mvnw -q -B test`
Expected: `CaptureControllerTest`만 여전히 실패(정상 — `CaptureController` 자체는 안 건드렸으므로 그대로 RED), 나머지 전부(`HealthCheckTest`/`StreamControllerTest`/`StreamServiceImplTest`/`TrailControllerTest`/`TrailServiceImplTest`/`BackendApplicationTests`) PASS

- [ ] **Step 3: 커밋**

```bash
git add apps/backend/src/main/java/com/stream/backend/service/CaptureServiceImpl.java
git commit -m "fix(backend): CaptureServiceImpl 스텁 추가 - apps/backend 컨텍스트 로드 실패(누락된 Bean) 해결"
```

---

## 후속 작업 (이 계획엔 포함 안 함, 참고용)

- **Capture 게이트웨이 연동**: `apps/backend`의 `CaptureController`는 여전히 RED 스텁이다(Task 4는 컨텍스트 로드만 되게 한 것일 뿐, 실제 Capture 로직은 없음). Trail과 같은 패턴이지만 별도 계획으로 처리한다.
- **Docker 실기동 검증**: 이 세션 환경엔 Docker 데몬이 없어 정적 검토로만 확인했다. 실제 `docker compose up`으로 `apps/backend → services/reader/writer` 전체 흐름을 검증할 필요가 있다.

## 최종 검증 (실제로 실행한 결과)

- [x] **backend 전체 테스트 재실행**

```bash
cd apps/backend && ./mvnw -q -B test -Dtest='!CaptureControllerTest'
```

Result: GREEN — 31/31 통과 (`BackendApplicationTests` 포함, Task 4 덕분에 이제 제외할 필요 없이 통과). `CaptureControllerTest`만 여전히 RED(Capture 게이트웨이 미구현, 범위 밖, 정상).
