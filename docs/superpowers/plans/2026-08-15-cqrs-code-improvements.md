# CQRS 코드 개선 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** reader/writer 서비스에 Read DTO, Command 객체, Command Handler를 도입하고, `Capture` 엔티티를 `packages/shared` 공유 모듈로 통합하여 CQRS 코드 레벨 표준에 맞춘다.

**Architecture:** reader는 `CaptureView` 읽기 전용 DTO를 통해 응답하고 캐시 미스 시 Redis를 재적재한다. writer는 Kafka 메시지를 `CreateCaptureCommand`로 변환해 `CaptureCommandHandler`에 위임한다. `Capture` JPA 엔티티는 `packages/shared`라는 독립 Maven 모듈로 옮기고 reader/writer가 그 모듈을 의존성으로 사용한다.

**Tech Stack:** Java 21, Spring Boot 3.5.x, Spring Data JPA, Spring Data Redis, JUnit 5, Mockito, AssertJ, Maven (`mvnw`)

## Global Constraints

- 기존 GREEN 테스트(reader 26개, writer 26개, `docs/tdd-test-plan.md` 기준)는 각 태스크가 끝날 때마다 계속 GREEN이어야 한다. 어떤 커밋도 컴파일 실패나 RED 테스트 상태로 남겨두지 않는다. 단, 아래 "알려진 사전 존재 예외" 두 가지는 이 계획의 변경과 무관하므로 GREEN 판정에서 제외한다.
- 패키지 루트는 `com.stream.reader`, `com.stream.writer`, 공유 모듈은 `com.stream.shared`를 쓴다.
- Java 21, `jakarta.persistence.*` 어노테이션(Jakarta EE 10, Spring Boot 3.5.x 호환)을 쓴다.
- 모든 신규 클래스는 `mvnw`(각 서비스에 이미 있는 Maven Wrapper)로 빌드/테스트한다.
- Backend(`apps/backend`)와 Kafka 스키마 계약, 에러 처리 정책은 이 계획의 범위 밖이다 (이미 별도로 열려있는 결정 사항).
- `services/reader/src/test/resources/schema.sql`, `services/writer/src/test/resources/schema.sql`은 이 계획 실행 직전에 별도로 추가된 사전 수정(`@DataJpaTest`가 H2에 `captures` 테이블을 만들지 못하던 기존 버그 수정)이다. 이 계획의 어떤 태스크도 이 두 파일을 다시 만들거나 삭제하지 않는다.

### 알려진 사전 존재 예외 (이 계획 시작 전부터 있던 문제, 계획 범위 밖)

1. **`ReaderApplicationTests.contextLoads` / `WriterApplicationTests.contextLoads`**: `@SpringBootTest`로 전체 컨텍스트를 띄우며 실제 Postgres/Redis 접속을 시도한다. 이 작업 환경엔 Docker 데몬이 떠 있지 않아(로컬 Postgres/Redis 없음) 이 두 테스트는 항상 실패한다. **전체 테스트를 실행하는 모든 단계에서 `-Dtest='!ReaderApplicationTests'` (writer는 `'!WriterApplicationTests'`)로 제외하고 실행한다.** 이 두 클래스의 실패는 이 계획이 만든 회귀가 아니다.
2. **`services/writer/src/test/java/com/stream/writer/consumer/ImageAnalyzedConsumerTest.java`의 Mockito `UnnecessaryStubbingException`**: 원본 파일의 `@BeforeEach setUp()`이 `redisTemplate.opsForValue()`를 무조건 스터빙하는데, invalid-JSON/필드 누락 테스트 3개는 그 스텁을 쓰지 않아 Mockito 엄격 모드에서 실패한다(`docs/tdd-test-plan.md`의 "writer 26 GREEN" 기록과 달리 이 환경에서는 재현됨). **Task 3에서 이 파일 전체를 새 구조(Command/Handler 위임)로 다시 쓰면서 `@BeforeEach`의 redis 스텁 자체가 사라지므로 자동으로 해결된다.** Task 1~2 단계에서는 writer 전체 테스트를 실행할 필요가 없으므로(reader만 건드림) 이 문제와 마주치지 않는다. Task 3 이전에 writer 전체 스위트를 돌리면 이 3개 실패가 나타나는 것이 정상이며, Task 3 완료 후에는 사라져야 한다.

---

## Task 1: Reader — CaptureView 읽기 전용 DTO 도입

**Files:**
- Create: `services/reader/src/main/java/com/stream/reader/dto/CaptureView.java`
- Create: `services/reader/src/test/java/com/stream/reader/dto/CaptureViewTest.java`
- Modify: `services/reader/src/main/java/com/stream/reader/controller/CaptureController.java`

**Interfaces:**
- Produces: `CaptureView` record — `id: Long, trailId: Integer, streamId: Integer, imagePath: String, roadStatus: String, confidence: Double, createdAt: LocalDateTime`, static factory `CaptureView.from(Capture capture): CaptureView`
- `CaptureController.getAll()`은 이제 `List<CaptureView>`를 반환 (기존 `List<Capture>`에서 변경)
- `CaptureController.getLatestByTrail()`의 Postgres 폴백 분기의 `data` 필드는 이제 `List<CaptureView>` (기존 `List<Capture>`에서 변경)

- [ ] **Step 1: CaptureView 테스트를 먼저 작성한다**

`services/reader/src/test/java/com/stream/reader/dto/CaptureViewTest.java`:

```java
package com.stream.reader.dto;

import com.stream.reader.entity.Capture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Reader - CaptureView 테스트")
class CaptureViewTest {

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("from() - Capture 엔티티의 모든 필드를 CaptureView로 변환한다")
    void from_mapsAllFieldsFromEntity() throws Exception {
        Capture capture = new Capture();
        setField(capture, "id", 1L);
        setField(capture, "trailId", 2);
        setField(capture, "streamId", 3);
        setField(capture, "imagePath", "/images/capture_001.jpg");
        setField(capture, "roadStatus", "양호");
        setField(capture, "confidence", 0.95);
        setField(capture, "createdAt", LocalDateTime.of(2024, 1, 1, 0, 0, 0));

        CaptureView view = CaptureView.from(capture);

        assertThat(view.id()).isEqualTo(1L);
        assertThat(view.trailId()).isEqualTo(2);
        assertThat(view.streamId()).isEqualTo(3);
        assertThat(view.imagePath()).isEqualTo("/images/capture_001.jpg");
        assertThat(view.roadStatus()).isEqualTo("양호");
        assertThat(view.confidence()).isEqualTo(0.95);
        assertThat(view.createdAt()).isEqualTo(LocalDateTime.of(2024, 1, 1, 0, 0, 0));
    }
}
```

- [ ] **Step 2: 테스트 실행 → 컴파일 실패 확인**

Run: `cd services/reader && ./mvnw -q -B test -Dtest=CaptureViewTest`
Expected: FAIL — `CaptureView`가 존재하지 않아 컴파일 에러

- [ ] **Step 3: CaptureView record를 작성한다**

`services/reader/src/main/java/com/stream/reader/dto/CaptureView.java`:

```java
package com.stream.reader.dto;

import com.stream.reader.entity.Capture;

import java.time.LocalDateTime;

public record CaptureView(
        Long id,
        Integer trailId,
        Integer streamId,
        String imagePath,
        String roadStatus,
        Double confidence,
        LocalDateTime createdAt
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

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `cd services/reader && ./mvnw -q -B test -Dtest=CaptureViewTest`
Expected: PASS

- [ ] **Step 5: CaptureController가 CaptureView를 반환하도록 수정한다**

`services/reader/src/main/java/com/stream/reader/controller/CaptureController.java` 전체를 아래로 교체:

```java
package com.stream.reader.controller;

import com.stream.reader.dto.CaptureView;
import com.stream.reader.repository.CaptureRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/captures")
public class CaptureController {

    private final CaptureRepository captureRepository;
    private final StringRedisTemplate redisTemplate;

    public CaptureController(CaptureRepository captureRepository,
                              StringRedisTemplate redisTemplate) {
        this.captureRepository = captureRepository;
        this.redisTemplate = redisTemplate;
    }

    // ─────────────────────────────────────────
    // 전체 조회 (PostgreSQL)
    // ─────────────────────────────────────────
    @GetMapping
    public List<CaptureView> getAll() {
        return captureRepository.findAll().stream()
                .map(CaptureView::from)
                .toList();
    }

    // ─────────────────────────────────────────
    // trailId로 최신 결과 조회
    // Redis 캐시 우선 → 없으면 PostgreSQL 조회
    // ─────────────────────────────────────────
    @GetMapping("/trail/{trailId}/latest")
    public Object getLatestByTrail(@PathVariable Integer trailId) {
        String redisKey = "capture:latest:trail:" + trailId;
        String cached = redisTemplate.opsForValue().get(redisKey);

        if (cached != null) {
            System.out.println("[reader] Redis 캐시 히트: " + redisKey);
            return Map.of("source", "redis", "data", cached);
        }

        System.out.println("[reader] Redis 캐시 미스 → PostgreSQL 조회");
        List<CaptureView> views = captureRepository.findByTrailId(trailId).stream()
                .map(CaptureView::from)
                .toList();
        return Map.of("source", "postgresql", "data", views);
    }
}
```

- [ ] **Step 6: 기존 CaptureControllerTest가 여전히 통과하는지 확인**

Run: `cd services/reader && ./mvnw -q -B test -Dtest=CaptureControllerTest`
Expected: PASS (기존 6개 테스트는 `$.data`의 배열 여부만 검증하므로 DTO로 바꿔도 깨지지 않음)

- [ ] **Step 7: 커밋**

```bash
git add services/reader/src/main/java/com/stream/reader/dto/CaptureView.java services/reader/src/test/java/com/stream/reader/dto/CaptureViewTest.java services/reader/src/main/java/com/stream/reader/controller/CaptureController.java
git commit -m "refactor(reader): CaptureView 읽기 전용 DTO 도입"
```

---

## Task 2: Reader — Redis 캐시 미스 시 재적재(self-healing)

**Files:**
- Modify: `services/reader/src/main/java/com/stream/reader/controller/CaptureController.java`
- Modify: `services/reader/src/test/java/com/stream/reader/controller/CaptureControllerTest.java`

**Interfaces:**
- Consumes: `CaptureView` from Task 1
- `CaptureController`는 이제 생성자에 `ObjectMapper objectMapper`를 추가로 받는다.

- [ ] **Step 1: 캐시 재적재 검증 테스트를 먼저 추가한다**

`services/reader/src/test/java/com/stream/reader/controller/CaptureControllerTest.java`의 마지막 `@Test` 메서드(`getLatestByTrail_doesNotQueryPostgresOnCacheHit`) 뒤, 클래스 닫는 `}` 앞에 아래 두 테스트를 추가한다:

```java
    @Test
    @DisplayName("GET /captures/trail/{trailId}/latest - Redis 캐시 미스 시 조회 결과를 Redis에 다시 채워넣는다")
    void getLatestByTrail_repopulatesCacheOnRedisMiss() throws Exception {
        // given
        String redisKey = "capture:latest:trail:1";
        given(valueOperations.get(redisKey)).willReturn(null);

        Capture capture = new Capture();
        given(captureRepository.findByTrailId(1)).willReturn(List.of(capture));

        // when
        mockMvc.perform(get("/captures/trail/1/latest"))
                .andExpect(status().isOk());

        // then
        org.mockito.Mockito.verify(valueOperations).set(org.mockito.ArgumentMatchers.eq(redisKey), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("GET /captures/trail/{trailId}/latest - Redis 캐시 미스이고 결과도 없으면 Redis에 캐싱하지 않는다")
    void getLatestByTrail_doesNotCacheOnEmptyResult() throws Exception {
        // given
        String redisKey = "capture:latest:trail:999";
        given(valueOperations.get(redisKey)).willReturn(null);
        given(captureRepository.findByTrailId(999)).willReturn(List.of());

        // when
        mockMvc.perform(get("/captures/trail/999/latest"))
                .andExpect(status().isOk());

        // then
        org.mockito.Mockito.verify(valueOperations, org.mockito.Mockito.never())
                .set(org.mockito.ArgumentMatchers.eq(redisKey), org.mockito.ArgumentMatchers.anyString());
    }
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `cd services/reader && ./mvnw -q -B test -Dtest=CaptureControllerTest`
Expected: FAIL — `getLatestByTrail_repopulatesCacheOnRedisMiss`가 `valueOperations.set(...)`이 호출되지 않아 실패

- [ ] **Step 3: CaptureController에 캐시 재적재 로직을 추가한다**

`services/reader/src/main/java/com/stream/reader/controller/CaptureController.java`를 아래로 교체:

```java
package com.stream.reader.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stream.reader.dto.CaptureView;
import com.stream.reader.repository.CaptureRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/captures")
public class CaptureController {

    private final CaptureRepository captureRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public CaptureController(CaptureRepository captureRepository,
                              StringRedisTemplate redisTemplate,
                              ObjectMapper objectMapper) {
        this.captureRepository = captureRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────────────────────
    // 전체 조회 (PostgreSQL)
    // ─────────────────────────────────────────
    @GetMapping
    public List<CaptureView> getAll() {
        return captureRepository.findAll().stream()
                .map(CaptureView::from)
                .toList();
    }

    // ─────────────────────────────────────────
    // trailId로 최신 결과 조회
    // Redis 캐시 우선 → 없으면 PostgreSQL 조회 → 조회 결과를 Redis에 재적재
    // ─────────────────────────────────────────
    @GetMapping("/trail/{trailId}/latest")
    public Object getLatestByTrail(@PathVariable Integer trailId) {
        String redisKey = "capture:latest:trail:" + trailId;
        String cached = redisTemplate.opsForValue().get(redisKey);

        if (cached != null) {
            System.out.println("[reader] Redis 캐시 히트: " + redisKey);
            return Map.of("source", "redis", "data", cached);
        }

        System.out.println("[reader] Redis 캐시 미스 → PostgreSQL 조회");
        List<CaptureView> views = captureRepository.findByTrailId(trailId).stream()
                .map(CaptureView::from)
                .toList();

        if (!views.isEmpty()) {
            try {
                redisTemplate.opsForValue().set(redisKey, objectMapper.writeValueAsString(views));
                System.out.println("[reader] Redis 캐시 재적재 완료: " + redisKey);
            } catch (JsonProcessingException e) {
                System.err.println("[reader] Redis 캐시 재적재 실패: " + e.getMessage());
            }
        }

        return Map.of("source", "postgresql", "data", views);
    }
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `cd services/reader && ./mvnw -q -B test -Dtest=CaptureControllerTest`
Expected: PASS (기존 6개 + 신규 2개 = 8개 전부 통과)

- [ ] **Step 5: 커밋**

```bash
git add services/reader/src/main/java/com/stream/reader/controller/CaptureController.java services/reader/src/test/java/com/stream/reader/controller/CaptureControllerTest.java
git commit -m "feat(reader): Redis 캐시 미스 시 조회 결과를 재적재하여 self-healing 지원"
```

---

## Task 3: Writer — CreateCaptureCommand + CaptureCommandHandler 도입

**Files:**
- Create: `services/writer/src/main/java/com/stream/writer/command/CreateCaptureCommand.java`
- Create: `services/writer/src/main/java/com/stream/writer/command/CaptureCommandHandler.java`
- Create: `services/writer/src/test/java/com/stream/writer/command/CaptureCommandHandlerTest.java`
- Modify: `services/writer/src/main/java/com/stream/writer/consumer/ImageAnalyzedConsumer.java`
- Modify: `services/writer/src/test/java/com/stream/writer/consumer/ImageAnalyzedConsumerTest.java`

**Interfaces:**
- Produces: `CreateCaptureCommand` record — `trailId: Integer, streamId: Integer, imagePath: String, roadStatus: String, confidence: Double`
- Produces: `CaptureCommandHandler.handle(CreateCaptureCommand command): Capture`
- `ImageAnalyzedConsumer`는 이제 생성자에 `CaptureCommandHandler`, `ObjectMapper`만 받는다 (기존 `CaptureRepository`, `StringRedisTemplate` 직접 의존 제거)

- [ ] **Step 1: CreateCaptureCommand record를 작성한다**

`services/writer/src/main/java/com/stream/writer/command/CreateCaptureCommand.java`:

```java
package com.stream.writer.command;

public record CreateCaptureCommand(
        Integer trailId,
        Integer streamId,
        String imagePath,
        String roadStatus,
        Double confidence
) {}
```

- [ ] **Step 2: CaptureCommandHandler 테스트를 먼저 작성한다**

`services/writer/src/test/java/com/stream/writer/command/CaptureCommandHandlerTest.java`:

```java
package com.stream.writer.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stream.writer.entity.Capture;
import com.stream.writer.repository.CaptureRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Writer - CaptureCommandHandler 테스트")
class CaptureCommandHandlerTest {

    @Mock
    private CaptureRepository captureRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private CaptureCommandHandler handler;

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
    }

    @Test
    @DisplayName("handle() - Command를 처리하면 PostgreSQL에 Capture를 저장한다")
    void handle_savesCaptureToDatabase() {
        // given
        CreateCaptureCommand command = new CreateCaptureCommand(1, 1, "/images/capture_001.jpg", "양호", 0.95);
        Capture savedCapture = new Capture();
        savedCapture.setTrailId(1);
        savedCapture.setStreamId(1);
        savedCapture.setImagePath("/images/capture_001.jpg");
        savedCapture.setRoadStatus("양호");
        savedCapture.setConfidence(0.95);
        given(captureRepository.save(any(Capture.class))).willReturn(savedCapture);

        // when
        handler.handle(command);

        // then
        ArgumentCaptor<Capture> captor = ArgumentCaptor.forClass(Capture.class);
        verify(captureRepository).save(captor.capture());
        Capture saved = captor.getValue();
        assertThat(saved.getTrailId()).isEqualTo(1);
        assertThat(saved.getStreamId()).isEqualTo(1);
        assertThat(saved.getImagePath()).isEqualTo("/images/capture_001.jpg");
        assertThat(saved.getRoadStatus()).isEqualTo("양호");
        assertThat(saved.getConfidence()).isEqualTo(0.95);
    }

    @Test
    @DisplayName("handle() - Command를 처리한 후 Redis에 최신 결과를 캐싱한다")
    void handle_cachesLatestCaptureToRedis() {
        // given
        CreateCaptureCommand command = new CreateCaptureCommand(1, 1, "/images/capture_001.jpg", "양호", 0.95);
        Capture savedCapture = new Capture();
        savedCapture.setTrailId(1);
        given(captureRepository.save(any(Capture.class))).willReturn(savedCapture);

        // when
        handler.handle(command);

        // then - Redis key: capture:latest:trail:{trailId}
        verify(valueOperations).set(eq("capture:latest:trail:1"), anyString());
    }

    @Test
    @DisplayName("handle() - Redis 캐시 키는 'capture:latest:trail:{trailId}' 형식이다")
    void handle_usesCorrectRedisKey() {
        // given
        CreateCaptureCommand command = new CreateCaptureCommand(42, 1, "/images/capture_042.jpg", "보통", 0.80);
        Capture savedCapture = new Capture();
        savedCapture.setTrailId(42);
        given(captureRepository.save(any(Capture.class))).willReturn(savedCapture);

        // when
        handler.handle(command);

        // then
        verify(valueOperations).set(eq("capture:latest:trail:42"), anyString());
    }
}
```

- [ ] **Step 3: 테스트 실행 → 컴파일 실패 확인**

Run: `cd services/writer && ./mvnw -q -B test -Dtest=CaptureCommandHandlerTest`
Expected: FAIL — `CaptureCommandHandler`가 존재하지 않아 컴파일 에러

- [ ] **Step 4: CaptureCommandHandler를 작성한다**

`services/writer/src/main/java/com/stream/writer/command/CaptureCommandHandler.java`:

```java
package com.stream.writer.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stream.writer.entity.Capture;
import com.stream.writer.repository.CaptureRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class CaptureCommandHandler {

    private final CaptureRepository captureRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public CaptureCommandHandler(CaptureRepository captureRepository,
                                  StringRedisTemplate redisTemplate,
                                  ObjectMapper objectMapper) {
        this.captureRepository = captureRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────────────────────
    // CreateCaptureCommand 처리 → PostgreSQL 저장 → Redis 캐싱
    // ─────────────────────────────────────────
    public Capture handle(CreateCaptureCommand command) {
        Capture capture = new Capture();
        capture.setTrailId(command.trailId());
        capture.setStreamId(command.streamId());
        capture.setImagePath(command.imagePath());
        capture.setRoadStatus(command.roadStatus());
        capture.setConfidence(command.confidence());

        Capture saved = captureRepository.save(capture);
        System.out.println("[writer] PostgreSQL 저장 완료 id=" + saved.getId());

        String redisKey = "capture:latest:trail:" + saved.getTrailId();
        try {
            String redisValue = objectMapper.writeValueAsString(command);
            redisTemplate.opsForValue().set(redisKey, redisValue);
            System.out.println("[writer] Redis 캐싱 완료 key=" + redisKey);
        } catch (JsonProcessingException e) {
            System.err.println("[writer] Redis 캐싱 실패: " + e.getMessage());
        }

        return saved;
    }
}
```

- [ ] **Step 5: 테스트 실행 → 통과 확인**

Run: `cd services/writer && ./mvnw -q -B test -Dtest=CaptureCommandHandlerTest`
Expected: PASS

- [ ] **Step 6: ImageAnalyzedConsumer가 Command를 만들어 Handler에 위임하도록 수정한다**

`services/writer/src/main/java/com/stream/writer/consumer/ImageAnalyzedConsumer.java` 전체를 아래로 교체:

```java
package com.stream.writer.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stream.writer.command.CaptureCommandHandler;
import com.stream.writer.command.CreateCaptureCommand;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ImageAnalyzedConsumer {

    private final CaptureCommandHandler captureCommandHandler;
    private final ObjectMapper objectMapper;

    public ImageAnalyzedConsumer(CaptureCommandHandler captureCommandHandler,
                                  ObjectMapper objectMapper) {
        this.captureCommandHandler = captureCommandHandler;
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────────────────────
    // image.analyzed 토픽 구독
    // 메시지 수신 → CreateCaptureCommand로 변환 → CaptureCommandHandler에 위임
    // ─────────────────────────────────────────
    @KafkaListener(topics = "image.analyzed", groupId = "writer-group")
    public void consume(String message) {
        try {
            Map<String, Object> data = objectMapper.readValue(message, Map.class);
            System.out.println("[writer] 메시지 수신: " + data);

            CreateCaptureCommand command = new CreateCaptureCommand(
                    Integer.valueOf(data.get("trailId").toString()),
                    Integer.valueOf(data.get("streamId").toString()),
                    data.get("imagePath").toString(),
                    data.get("roadStatus").toString(),
                    Double.valueOf(data.get("confidence").toString())
            );

            captureCommandHandler.handle(command);

        } catch (Exception e) {
            System.err.println("[writer] 처리 실패: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 7: ImageAnalyzedConsumerTest를 새 구조에 맞게 다시 작성한다**

`services/writer/src/test/java/com/stream/writer/consumer/ImageAnalyzedConsumerTest.java` 전체를 아래로 교체:

```java
package com.stream.writer.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stream.writer.command.CaptureCommandHandler;
import com.stream.writer.command.CreateCaptureCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("Writer - ImageAnalyzedConsumer 테스트")
class ImageAnalyzedConsumerTest {

    @Mock
    private CaptureCommandHandler captureCommandHandler;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ImageAnalyzedConsumer consumer;

    // ─────────────────────────────────────────
    // 정상 메시지 처리
    // ─────────────────────────────────────────

    @Test
    @DisplayName("유효한 image.analyzed 메시지를 수신하면 CaptureCommandHandler에 위임한다")
    void consume_delegatesToHandlerOnValidMessage() {
        // given
        String message = """
                {
                  "trailId": 1,
                  "streamId": 1,
                  "imagePath": "/images/capture_001.jpg",
                  "roadStatus": "양호",
                  "confidence": 0.95
                }
                """;

        // when
        consumer.consume(message);

        // then
        ArgumentCaptor<CreateCaptureCommand> captor = ArgumentCaptor.forClass(CreateCaptureCommand.class);
        verify(captureCommandHandler).handle(captor.capture());

        CreateCaptureCommand command = captor.getValue();
        assertThat(command.trailId()).isEqualTo(1);
        assertThat(command.streamId()).isEqualTo(1);
        assertThat(command.imagePath()).isEqualTo("/images/capture_001.jpg");
        assertThat(command.roadStatus()).isEqualTo("양호");
        assertThat(command.confidence()).isEqualTo(0.95);
    }

    @Test
    @DisplayName("메시지의 roadStatus와 confidence가 올바르게 파싱되어 Command에 담긴다")
    void consume_parsesRoadStatusAndConfidenceCorrectly() {
        // given
        String message = """
                {
                  "trailId": 1,
                  "streamId": 1,
                  "imagePath": "/images/capture_001.jpg",
                  "roadStatus": "불량",
                  "confidence": 0.73
                }
                """;

        // when
        consumer.consume(message);

        // then
        ArgumentCaptor<CreateCaptureCommand> captor = ArgumentCaptor.forClass(CreateCaptureCommand.class);
        verify(captureCommandHandler).handle(captor.capture());
        assertThat(captor.getValue().roadStatus()).isEqualTo("불량");
        assertThat(captor.getValue().confidence()).isEqualTo(0.73);
    }

    // ─────────────────────────────────────────
    // 예외 처리 (잘못된 메시지)
    // ─────────────────────────────────────────

    @Test
    @DisplayName("잘못된 JSON 형식의 메시지를 수신해도 예외가 외부로 전파되지 않는다")
    void consume_doesNotThrowOnInvalidJson() {
        // given
        String invalidMessage = "invalid-json-string";

        // when & then
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                consumer.consume(invalidMessage)
        );

        // Handler 위임 시도 없음
        verify(captureCommandHandler, never()).handle(any());
    }

    @Test
    @DisplayName("잘못된 JSON 형식의 메시지를 수신하면 Handler를 호출하지 않는다")
    void consume_doesNotCallHandlerOnInvalidJson() {
        // given
        String invalidMessage = "{ broken json }";

        // when
        consumer.consume(invalidMessage);

        // then
        verify(captureCommandHandler, never()).handle(any());
    }

    @Test
    @DisplayName("필수 필드(trailId)가 누락된 메시지를 수신해도 예외가 외부로 전파되지 않는다")
    void consume_doesNotThrowOnMissingRequiredField() {
        // given - trailId 누락
        String messageWithoutTrailId = """
                {
                  "streamId": 1,
                  "imagePath": "/images/capture_001.jpg",
                  "roadStatus": "양호",
                  "confidence": 0.95
                }
                """;

        // when & then
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                consumer.consume(messageWithoutTrailId)
        );

        verify(captureCommandHandler, never()).handle(any());
    }
}
```

- [ ] **Step 8: writer 전체 테스트 실행 → 통과 확인**

Run: `cd services/writer && ./mvnw -q -B test -Dtest='!WriterApplicationTests'`
Expected: PASS (전부 GREEN, 총 테스트 수는 기존보다 늘어남 — Handler 테스트 3개 추가). `WriterApplicationTests`는 Global Constraints의 "알려진 사전 존재 예외 1"에 따라 제외한다 — 이 시점부터는 Global Constraints의 "알려진 사전 존재 예외 2"(Mockito UnnecessaryStubbingException)도 이 리라이트로 사라졌어야 한다.

- [ ] **Step 9: 커밋**

```bash
git add services/writer/src/main/java/com/stream/writer/command/CreateCaptureCommand.java services/writer/src/main/java/com/stream/writer/command/CaptureCommandHandler.java services/writer/src/test/java/com/stream/writer/command/CaptureCommandHandlerTest.java services/writer/src/main/java/com/stream/writer/consumer/ImageAnalyzedConsumer.java services/writer/src/test/java/com/stream/writer/consumer/ImageAnalyzedConsumerTest.java
git commit -m "refactor(writer): CreateCaptureCommand와 CaptureCommandHandler로 저장 로직 분리"
```

---

## Task 4: packages/shared — 공유 Capture 엔티티 모듈 생성

**Files:**
- Create: `packages/shared/pom.xml`
- Create: `packages/shared/mvnw`, `packages/shared/mvnw.cmd`, `packages/shared/.mvn/wrapper/maven-wrapper.properties`
- Create: `packages/shared/src/main/java/com/stream/shared/entity/Capture.java`
- Create: `packages/shared/src/test/java/com/stream/shared/entity/CaptureTest.java`

**Interfaces:**
- Produces: `com.stream.shared.entity.Capture` — `@Entity @Table(name = "captures")`, getter/setter 전체, `@PrePersist onCreate()`로 `createdAt` 자동 설정

- [ ] **Step 1: Maven Wrapper를 reader에서 복사한다**

Run:
```bash
mkdir -p packages/shared/.mvn/wrapper
cp services/reader/mvnw packages/shared/mvnw
cp services/reader/mvnw.cmd packages/shared/mvnw.cmd
cp services/reader/.mvn/wrapper/maven-wrapper.properties packages/shared/.mvn/wrapper/maven-wrapper.properties
chmod +x packages/shared/mvnw
```

- [ ] **Step 2: packages/shared/pom.xml을 작성한다**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.stream</groupId>
    <artifactId>shared</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>jakarta.persistence</groupId>
            <artifactId>jakarta.persistence-api</artifactId>
            <version>3.1.0</version>
        </dependency>

        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.10.3</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <version>3.26.3</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: 공유 Capture 엔티티 테스트를 먼저 작성한다**

`packages/shared/src/test/java/com/stream/shared/entity/CaptureTest.java`:

```java
package com.stream.shared.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Shared - Capture Entity 테스트")
class CaptureTest {

    private Capture capture;

    @BeforeEach
    void setUp() {
        capture = new Capture();
        capture.setTrailId(1);
        capture.setStreamId(2);
        capture.setImagePath("/images/capture_001.jpg");
        capture.setRoadStatus("양호");
        capture.setConfidence(0.95);
    }

    @Test
    @DisplayName("setTrailId() / getTrailId() - trailId를 저장하고 반환한다")
    void trailId_setAndGet() {
        capture.setTrailId(42);
        assertThat(capture.getTrailId()).isEqualTo(42);
    }

    @Test
    @DisplayName("setStreamId() / getStreamId() - streamId를 저장하고 반환한다")
    void streamId_setAndGet() {
        capture.setStreamId(10);
        assertThat(capture.getStreamId()).isEqualTo(10);
    }

    @Test
    @DisplayName("setImagePath() / getImagePath() - imagePath를 저장하고 반환한다")
    void imagePath_setAndGet() {
        capture.setImagePath("/images/new_capture.jpg");
        assertThat(capture.getImagePath()).isEqualTo("/images/new_capture.jpg");
    }

    @Test
    @DisplayName("setRoadStatus() / getRoadStatus() - roadStatus를 저장하고 반환한다")
    void roadStatus_setAndGet() {
        capture.setRoadStatus("불량");
        assertThat(capture.getRoadStatus()).isEqualTo("불량");
    }

    @Test
    @DisplayName("setConfidence() / getConfidence() - confidence를 저장하고 반환한다")
    void confidence_setAndGet() {
        capture.setConfidence(0.73);
        assertThat(capture.getConfidence()).isEqualTo(0.73);
    }

    @Test
    @DisplayName("getId() - 저장 전에는 id가 null이다")
    void getId_isNullBeforePersist() {
        assertThat(capture.getId()).isNull();
    }

    @Test
    @DisplayName("getCreatedAt() - onCreate() 호출 전에는 createdAt이 null이다")
    void getCreatedAt_isNullBeforeOnCreate() {
        assertThat(capture.getCreatedAt()).isNull();
    }

    @Test
    @DisplayName("@PrePersist - onCreate() 호출 시 createdAt이 현재 시각으로 설정된다")
    void onCreate_setsCreatedAt() throws Exception {
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        Method onCreateMethod = Capture.class.getDeclaredMethod("onCreate");
        onCreateMethod.setAccessible(true);
        onCreateMethod.invoke(capture);

        LocalDateTime after = LocalDateTime.now().plusSeconds(1);

        assertThat(capture.getCreatedAt()).isNotNull();
        assertThat(capture.getCreatedAt()).isAfterOrEqualTo(before);
        assertThat(capture.getCreatedAt()).isBeforeOrEqualTo(after);
    }

    @Test
    @DisplayName("@PrePersist - onCreate()를 두 번 호출하면 createdAt이 덮어써진다")
    void onCreate_overwritesCreatedAt() throws Exception {
        Method onCreateMethod = Capture.class.getDeclaredMethod("onCreate");
        onCreateMethod.setAccessible(true);

        onCreateMethod.invoke(capture);
        LocalDateTime first = capture.getCreatedAt();

        Thread.sleep(10);
        onCreateMethod.invoke(capture);
        LocalDateTime second = capture.getCreatedAt();

        assertThat(second).isAfterOrEqualTo(first);
    }

    @Test
    @DisplayName("기본 생성자로 만들면 모든 필드가 null이다")
    void defaultConstructor_allFieldsAreNull() {
        Capture empty = new Capture();
        assertThat(empty.getId()).isNull();
        assertThat(empty.getTrailId()).isNull();
        assertThat(empty.getStreamId()).isNull();
        assertThat(empty.getImagePath()).isNull();
        assertThat(empty.getRoadStatus()).isNull();
        assertThat(empty.getConfidence()).isNull();
        assertThat(empty.getCreatedAt()).isNull();
    }
}
```

- [ ] **Step 4: 테스트 실행 → 컴파일 실패 확인**

Run: `cd packages/shared && ./mvnw -q -B test`
Expected: FAIL — `Capture` 클래스가 존재하지 않아 컴파일 에러

- [ ] **Step 5: 공유 Capture 엔티티를 작성한다**

`packages/shared/src/main/java/com/stream/shared/entity/Capture.java`:

```java
package com.stream.shared.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "captures")
public class Capture {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trail_id", nullable = false)
    private Integer trailId;

    @Column(name = "stream_id", nullable = false)
    private Integer streamId;

    @Column(name = "image_path", nullable = false)
    private String imagePath;

    @Column(name = "road_status")
    private String roadStatus;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Integer getTrailId() { return trailId; }
    public void setTrailId(Integer trailId) { this.trailId = trailId; }
    public Integer getStreamId() { return streamId; }
    public void setStreamId(Integer streamId) { this.streamId = streamId; }
    public String getImagePath() { return imagePath; }
    public void setImagePath(String imagePath) { this.imagePath = imagePath; }
    public String getRoadStatus() { return roadStatus; }
    public void setRoadStatus(String roadStatus) { this.roadStatus = roadStatus; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 6: 테스트 실행 → 통과 확인**

Run: `cd packages/shared && ./mvnw -q -B test`
Expected: PASS (9개 테스트 전부 통과)

- [ ] **Step 7: 로컬 Maven 저장소에 설치한다 (reader/writer가 의존하기 위해 필요)**

Run: `cd packages/shared && ./mvnw -q -B install -DskipTests`
Expected: `BUILD SUCCESS`, `~/.m2/repository/com/stream/shared/0.0.1-SNAPSHOT/`에 jar 설치됨

- [ ] **Step 8: 커밋**

```bash
git add packages/shared/pom.xml packages/shared/mvnw packages/shared/mvnw.cmd packages/shared/.mvn packages/shared/src
git commit -m "feat(shared): Capture 공유 엔티티 모듈 생성"
```

---

## Task 5: Reader — 공유 Capture 엔티티로 전환

**Files:**
- Modify: `services/reader/pom.xml`
- Delete: `services/reader/src/main/java/com/stream/reader/entity/Capture.java`
- Delete: `services/reader/src/test/java/com/stream/reader/entity/CaptureTest.java` (Task 4의 shared 모듈 테스트가 대체)
- Modify: `services/reader/src/main/java/com/stream/reader/repository/CaptureRepository.java`
- Modify: `services/reader/src/main/java/com/stream/reader/dto/CaptureView.java`
- Modify: `services/reader/src/test/java/com/stream/reader/controller/CaptureControllerTest.java`
- Modify: `services/reader/src/test/java/com/stream/reader/repository/CaptureRepositoryTest.java`
- Modify: `services/reader/src/test/java/com/stream/reader/dto/CaptureViewTest.java`
- Modify: `services/reader/Dockerfile`
- Modify: `infra/docker/docker-compose.yml`

**Interfaces:**
- Consumes: `com.stream.shared.entity.Capture` from Task 4

- [ ] **Step 1: reader/pom.xml에 shared 의존성을 추가한다**

`services/reader/pom.xml`의 `<dependencies>` 블록 맨 앞에 추가:

```xml
		<dependency>
			<groupId>com.stream</groupId>
			<artifactId>shared</artifactId>
			<version>0.0.1-SNAPSHOT</version>
		</dependency>
```

- [ ] **Step 2: reader 내부의 중복 Capture 엔티티와 그 테스트를 삭제한다**

Run:
```bash
rm services/reader/src/main/java/com/stream/reader/entity/Capture.java
rm services/reader/src/test/java/com/stream/reader/entity/CaptureTest.java
rmdir services/reader/src/main/java/com/stream/reader/entity 2>/dev/null || true
rmdir services/reader/src/test/java/com/stream/reader/entity 2>/dev/null || true
```

- [ ] **Step 3: import 경로를 shared 패키지로 바꾼다**

`services/reader/src/main/java/com/stream/reader/repository/CaptureRepository.java`에서:
```java
import com.stream.reader.entity.Capture;
```
을
```java
import com.stream.shared.entity.Capture;
```
로 교체.

`services/reader/src/main/java/com/stream/reader/dto/CaptureView.java`에서:
```java
import com.stream.reader.entity.Capture;
```
을
```java
import com.stream.shared.entity.Capture;
```
로 교체.

`services/reader/src/test/java/com/stream/reader/controller/CaptureControllerTest.java`에서:
```java
import com.stream.reader.entity.Capture;
```
을
```java
import com.stream.shared.entity.Capture;
```
로 교체.

`services/reader/src/test/java/com/stream/reader/repository/CaptureRepositoryTest.java`에서:
```java
import com.stream.reader.entity.Capture;
```
을
```java
import com.stream.shared.entity.Capture;
```
로 교체.

`services/reader/src/test/java/com/stream/reader/dto/CaptureViewTest.java`에서:
```java
import com.stream.reader.entity.Capture;
```
을
```java
import com.stream.shared.entity.Capture;
```
로 교체.

- [ ] **Step 4: reader 전체 테스트 실행 → 통과 확인**

Run: `cd services/reader && ./mvnw -q -B test -Dtest='!ReaderApplicationTests'`
Expected: PASS (모든 테스트가 `com.stream.shared.entity.Capture`로 컴파일되어 통과). `ReaderApplicationTests`는 Global Constraints의 "알려진 사전 존재 예외 1"에 따라 제외한다.

- [ ] **Step 5: Dockerfile을 shared 모듈을 빌드하도록 수정한다**

`services/reader/Dockerfile` 전체를 아래로 교체 (이제 Docker 빌드 컨텍스트가 저장소 루트라고 가정, Step 6에서 docker-compose.yml을 그렇게 바꿈):

```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /shared
COPY packages/shared/mvnw packages/shared/pom.xml ./
COPY packages/shared/.mvn .mvn
RUN ./mvnw -q -B dependency:go-offline
COPY packages/shared/src src
RUN ./mvnw -q -B install -DskipTests

WORKDIR /app
COPY services/reader/mvnw services/reader/pom.xml ./
COPY services/reader/.mvn .mvn
RUN ./mvnw -q -B dependency:go-offline

COPY services/reader/src src
RUN ./mvnw -q -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
ENV JAVA_OPTS="-XX:+UseContainerSupport"
COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

- [ ] **Step 6: docker-compose.yml의 reader 빌드 컨텍스트를 저장소 루트로 바꾼다**

`infra/docker/docker-compose.yml`의 `reader:` 서비스 정의에서:

```yaml
  reader:
    build:
      context: ../../services/reader
      dockerfile: Dockerfile
```

를

```yaml
  reader:
    build:
      context: ../..
      dockerfile: services/reader/Dockerfile
```

로 교체.

- [ ] **Step 7: Docker 빌드로 검증한다**

Run: `cd infra/docker && docker compose build reader`
Expected: `writer` 이미지가 `packages/shared`를 먼저 install한 뒤 reader를 정상적으로 package하여 빌드 성공

- [ ] **Step 8: 커밋**

```bash
git add services/reader/pom.xml services/reader/src services/reader/Dockerfile infra/docker/docker-compose.yml
git commit -m "refactor(reader): 공유 Capture 엔티티 모듈(packages/shared)로 전환"
```

---

## Task 6: Writer — 공유 Capture 엔티티로 전환

**Files:**
- Modify: `services/writer/pom.xml`
- Delete: `services/writer/src/main/java/com/stream/writer/entity/Capture.java`
- Delete: `services/writer/src/test/java/com/stream/writer/entity/CaptureTest.java` (Task 4의 shared 모듈 테스트가 대체)
- Modify: `services/writer/src/main/java/com/stream/writer/repository/CaptureRepository.java`
- Modify: `services/writer/src/main/java/com/stream/writer/command/CaptureCommandHandler.java`
- Modify: `services/writer/src/test/java/com/stream/writer/command/CaptureCommandHandlerTest.java`
- Modify: `services/writer/src/test/java/com/stream/writer/repository/CaptureRepositoryTest.java`
- Modify: `services/writer/Dockerfile`
- Modify: `infra/docker/docker-compose.yml`

**Interfaces:**
- Consumes: `com.stream.shared.entity.Capture` from Task 4

- [ ] **Step 1: writer/pom.xml에 shared 의존성을 추가한다**

`services/writer/pom.xml`의 `<dependencies>` 블록 맨 앞에 추가:

```xml
		<dependency>
			<groupId>com.stream</groupId>
			<artifactId>shared</artifactId>
			<version>0.0.1-SNAPSHOT</version>
		</dependency>
```

- [ ] **Step 2: writer 내부의 중복 Capture 엔티티와 그 테스트를 삭제한다**

Run:
```bash
rm services/writer/src/main/java/com/stream/writer/entity/Capture.java
rm services/writer/src/test/java/com/stream/writer/entity/CaptureTest.java
rmdir services/writer/src/main/java/com/stream/writer/entity 2>/dev/null || true
rmdir services/writer/src/test/java/com/stream/writer/entity 2>/dev/null || true
```

- [ ] **Step 3: import 경로를 shared 패키지로 바꾼다**

`services/writer/src/main/java/com/stream/writer/repository/CaptureRepository.java`에서:
```java
import com.stream.writer.entity.Capture;
```
을
```java
import com.stream.shared.entity.Capture;
```
로 교체.

`services/writer/src/main/java/com/stream/writer/command/CaptureCommandHandler.java`에서:
```java
import com.stream.writer.entity.Capture;
```
을
```java
import com.stream.shared.entity.Capture;
```
로 교체.

`services/writer/src/test/java/com/stream/writer/command/CaptureCommandHandlerTest.java`에서:
```java
import com.stream.writer.entity.Capture;
```
을
```java
import com.stream.shared.entity.Capture;
```
로 교체.

`services/writer/src/test/java/com/stream/writer/repository/CaptureRepositoryTest.java`에서:
```java
import com.stream.writer.entity.Capture;
```
을
```java
import com.stream.shared.entity.Capture;
```
로 교체.

- [ ] **Step 3.5: WriterApplication에 @EntityScan을 추가한다 (Task 5에서 발견된 필수 수정)**

Task 5(reader)에서 같은 전환을 하다가 발견된 문제: Spring Boot의 기본 엔티티 스캔은 `@SpringBootApplication` 클래스의 패키지(`com.stream.writer`)만 대상으로 하므로, `com.stream.shared.entity.Capture`를 managed JPA 엔티티로 인식하지 못해 `Not a managed type: class com.stream.shared.entity.Capture` 에러가 난다. `services/writer/src/main/java/com/stream/writer/WriterApplication.java`에 `@EntityScan`을 추가해야 한다. 이 파일을 읽고 `@SpringBootApplication` 어노테이션 바로 위에 `@EntityScan(basePackages = "com.stream.shared.entity")`를 추가하고, `import org.springframework.boot.autoconfigure.domain.EntityScan;`을 추가한다. writer에는 이제 자체 엔티티가 없으므로(Step 2에서 삭제) 공유 패키지만 스캔 대상으로 지정하면 된다.

- [ ] **Step 4: writer 전체 테스트 실행 → 통과 확인**

Run: `cd services/writer && ./mvnw -q -B test -Dtest='!WriterApplicationTests'`
Expected: PASS. `WriterApplicationTests`는 Global Constraints의 "알려진 사전 존재 예외 1"에 따라 제외한다.

- [ ] **Step 5: Dockerfile을 shared 모듈을 빌드하도록 수정한다**

`services/writer/Dockerfile` 전체를 아래로 교체:

```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /shared
COPY packages/shared/mvnw packages/shared/pom.xml ./
COPY packages/shared/.mvn .mvn
RUN ./mvnw -q -B dependency:go-offline
COPY packages/shared/src src
RUN ./mvnw -q -B install -DskipTests

WORKDIR /app
COPY services/writer/mvnw services/writer/pom.xml ./
COPY services/writer/.mvn .mvn
RUN ./mvnw -q -B dependency:go-offline

COPY services/writer/src src
RUN ./mvnw -q -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
ENV JAVA_OPTS="-XX:+UseContainerSupport"
COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

- [ ] **Step 6: docker-compose.yml의 writer 빌드 컨텍스트를 저장소 루트로 바꾼다**

`infra/docker/docker-compose.yml`의 `writer:` 서비스 정의에서:

```yaml
  writer:
    build:
      context: ../../services/writer
      dockerfile: Dockerfile
```

를

```yaml
  writer:
    build:
      context: ../..
      dockerfile: services/writer/Dockerfile
```

로 교체.

- [ ] **Step 7: Docker 빌드로 검증한다**

Run: `cd infra/docker && docker compose build writer`
Expected: 빌드 성공

- [ ] **Step 8: 커밋**

```bash
git add services/writer/pom.xml services/writer/src services/writer/Dockerfile infra/docker/docker-compose.yml
git commit -m "refactor(writer): 공유 Capture 엔티티 모듈(packages/shared)로 전환"
```

---

## 최종 검증

- [ ] **전체 서비스 테스트 재실행**

```bash
cd packages/shared && ./mvnw -q -B test
cd ../../services/reader && ./mvnw -q -B test -Dtest='!ReaderApplicationTests'
cd ../writer && ./mvnw -q -B test -Dtest='!WriterApplicationTests'
```

`ReaderApplicationTests`/`WriterApplicationTests`는 Global Constraints의 "알려진 사전 존재 예외 1"에 따라 제외한다(로컬에 Postgres/Redis가 없어 컨텍스트 로딩이 항상 실패함, 이 계획과 무관).

Expected: 세 모듈 모두 `BUILD SUCCESS`, `docs/tdd-test-plan.md`에 기록된 reader 26개 + writer 26개보다 늘어난 테스트 수(신규 DTO/Command/Handler 테스트 포함)가 전부 GREEN.

- [ ] **docs/tdd-test-plan.md 갱신 여부 확인**

이 계획 실행 후 reader/writer의 테스트 개수와 구조가 바뀌었으므로, `docs/tdd-test-plan.md`의 "생성된 파일 전체 목록"과 "테스트 현황 요약" 표를 갱신할지 사용자에게 확인한다 (이 계획의 범위에는 포함하지 않음 — 별도 커밋으로 처리 권장).
