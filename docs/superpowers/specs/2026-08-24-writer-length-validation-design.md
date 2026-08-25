# writer 핸들러의 길이 미검증 필드로 인한 500 수정 설계

**작성일:** 2026-08-24
**브랜치:** `fix/writer-length-validation`
**기준 커밋:** `2489dca` (main)

## 배경

직전 브랜치(`test-schema-fk-constraints`)의 전체 리뷰가 **원래 버그와 정확히 같은 클래스의 버그가 아직 살아있다**는 것을 찾아냈다.

`trails.direction`은 `VARCHAR(50)`인데 `TrailCommandHandler`가 이 필드만 검증하지 않는다. 51자를 보내면 DB가 거부하고, 그 예외 메시지에는 핸들러가 매칭하는 제약 이름(`trails_stream_id_fkey`, `trails_stream_id_camera_number_key`)이 없으므로 그대로 rethrow되어 **클라이언트가 잘못된 입력에 500을 받는다.**

이는 이 저장소가 이미 한 번 겪은 버그와 구조가 같다 — 존재하지 않는 `stream_id`로 Trail을 만들면 500이 나가던 문제. 그때는 유닛 테스트 176개를 통과하고 Docker 실기동에서야 발견됐다.

## 목표

동기 HTTP 경로에서 길이 제한 컬럼에 미검증 입력이 닿아 500이 나가는 경로를 없앤다.

## 계획 수립 전 실측으로 확인한 사실 (추측 아님)

`@DataJpaTest`로 실제 H2에 붙여서 확인했다.

### 1. 길이 제한 컬럼별 검증 현황 (전수 조사)

| 컬럼 | 제한 | 필수값 검증 | 길이 검증 | 위반 시 |
|---|---|---|---|---|
| `streams.name` | `VARCHAR(255)` | ✅ null/blank | ❌ | **500** |
| `trails.direction` | `VARCHAR(50)` | — (nullable) | ❌ | **500** |
| `trails.camera_number` | `VARCHAR` (무제한) | ✅ null/blank | n/a | — |
| `trails.status` | `VARCHAR(20)` + CHECK | ✅ 화이트리스트 | 사실상 ✅ | — |
| `captures.image_path` | `VARCHAR(500)` | ❌ | ❌ | 조용히 유실 |
| `captures.road_status` | `VARCHAR(10)` + CHECK | ❌ | ❌ | 조용히 유실 |
| `captures.confidence` | `DECIMAL(3,2)` (최대 9.99) | ❌ | ❌ | 조용히 유실 |

**`direction`만의 문제가 아니다.** `streams.name`에 정확히 같은 구멍이 있다. `StreamCommandHandler`가 null/blank는 보지만 길이는 보지 않는다. 오히려 이쪽이 더 자주 터질 가능성이 높다 — 하천 이름은 사용자가 자유롭게 입력하는 텍스트다.

### 2. 실제 발생하는 예외

- `direction` 51자 → `org.springframework.dao.DataIntegrityViolationException`
  - cause: `org.hibernate.exception.DataException` → `org.h2.jdbc.JdbcSQLDataException`
  - H2 메시지: `Value too long for column "DIRECTION CHARACTER VARYING(50)"`, SQLState `22001`
- `streams.name` 256자 → 동일한 예외 체인, `Value too long for column "NAME CHARACTER VARYING(255)"`
- **경계값은 정상 통과한다**: `direction` 정확히 50자, `name` 정확히 255자 모두 저장 성공.

`TrailCommandHandler`의 catch 블록은 두 제약 이름을 찾지 못해 rethrow하고, `StreamCommandHandler`는 catch 자체가 없다. 두 경우 모두 컨트롤러의 `@ExceptionHandler`가 잡지 않는 예외 타입이라 Spring 기본 500이 나간다.

### 3. `VARCHAR(n)`이 세는 단위가 두 엔진에서 다르다

**H2는 UTF-16 코드 단위를 센다.** 이모지 26개(`String.length()` = 52, `codePointCount()` = 26)를 `VARCHAR(50)` 컬럼에 넣으니 거부됐다.

PostgreSQL은 문자(코드포인트) 단위로 센다고 알려져 있으나 **이 환경에서는 확인하지 못했다** — 확인하려던 시점에 Docker Desktop 데몬이 내려가 있었다. 아래 설계는 이 사실을 알 필요가 없도록 구성했다.

### 4. 컨트롤러는 이미 준비돼 있다

`StreamController`와 `TrailController` 모두 `@ExceptionHandler({ParseException.class, ClassCastException.class, IllegalArgumentException.class})`를 갖고 있어 400을 반환한다. **컨트롤러 변경은 필요 없다.**

### 5. 현재 테스트 개수 (기준선)

- `StreamCommandHandlerTest` 5개 (mock)
- `TrailCommandHandlerTest` 12개 (mock)
- `TrailCommandHandlerConstraintTest` 6개 (진짜 H2)
- writer 전체 58개 (`-Dtest=!WriterApplicationTests`)

### 6. Docker가 내려가면 Testcontainers 테스트 5개가 조용히 skip된다

방금 실제로 재현됐다. Docker 데몬이 내려간 상태에서 writer 스위트를 돌리면 `Tests run: 58, Failures: 0, Errors: 0, Skipped: 5`에 **BUILD SUCCESS**가 나온다. 직전 리뷰가 경고했던 silent-skip 실패 모드다. 이번 작업의 범위는 아니지만, 구현 중 `Skipped: 5`를 보더라도 회귀가 아니라는 점을 알고 있어야 한다.

## 설계

### 1. 핸들러에 명시적 길이 검증 추가

기존 검증이 전부 핸들러 안의 `if`문이므로 같은 스타일을 따른다. 상한값은 이름 있는 상수로 빼고 어느 컬럼에서 온 값인지 주석으로 연결한다.

`TrailCommandHandler`:

```java
private static final int MAX_DIRECTION_LENGTH = 50;   // trails.direction VARCHAR(50)
```

검증 위치는 status 화이트리스트 검사 바로 뒤다. 둘 다 "값의 형태"를 보는 검사라 묶이고, WKT 파싱·`existsById`·`save()`보다 앞이라 DB를 건드리기 전에 걸러진다.

```java
if (command.direction() != null && command.direction().length() > MAX_DIRECTION_LENGTH) {
    throw new IllegalArgumentException(
            "direction must be " + MAX_DIRECTION_LENGTH + " characters or fewer");
}
```

`StreamCommandHandler`도 같은 형태로 `MAX_NAME_LENGTH = 255`를 두고, `name` null/blank 검사 바로 뒤에 검증한다.

`direction`은 nullable이므로 null은 통과시킨다. `name`은 이미 앞 검사에서 null/blank가 걸러지므로 null 체크가 중복되지 않는다.

에러 메시지는 기존 메시지들과 같이 영어로 쓴다(`"name is required"`, `"Invalid status: ..."`와 같은 톤).

### 2. `String.length()`를 쓰는 근거

`length()`(UTF-16 코드 단위)와 `codePointCount()`(코드포인트) 중 전자를 쓴다.

`length() >= codePointCount()`가 항상 성립하므로, `length()` 기준 검증은 **코드 단위 해석과 코드포인트 해석 모두의 상한**이다. 따라서 `VARCHAR(n)`이 둘 중 어느 쪽을 세든, 이 검증을 통과한 값은 컬럼에 들어간다. PostgreSQL의 정확한 규칙을 확인하지 못했어도 이 두 경우 안에 있는 한 안전하다.

**단, 이 논리는 세 번째 가능성에는 적용되지 않는다** — `VARCHAR`를 바이트 길이로 세는 엔진에는 성립하지 않는다. `"북".repeat(50)`은 UTF-16 코드 단위로 50이지만 UTF-8 바이트로는 150이다. 이 프로젝트는 H2와 PostgreSQL만 쓰고 둘 다 바이트로 세지 않으므로 실질적 문제는 없지만, 주장의 범위는 그 두 엔진까지다.

대가는 astral 평면 문자(이모지 등)에 대해 PostgreSQL보다 엄격할 수 있다는 것이다. 방위(`direction`)와 하천 이름(`name`)에는 실질적 영향이 없다고 판단했다. 이 트레이드오프를 코드 주석에 명시한다.

`codePointCount()`를 쓰지 않는 이유는, PostgreSQL 기준에 맞추려다 H2에서는 통과시켰는데 DB가 거부하는 상황(= 다시 500)을 만들 수 있기 때문이다. 실측으로 H2가 코드 단위를 센다는 것이 확인됐으므로 이는 가상의 위험이 아니다.

### 3. 테스트 (writer 58 → 66)

| 테스트 클래스 | 성격 | 추가 |
|---|---|---|
| `StreamCommandHandlerTest` | mock: name 256자 → `IllegalArgumentException` + `save()` 호출 안 함 / 255자 통과 | +2 |
| `TrailCommandHandlerTest` | mock: direction 51자 → `IllegalArgumentException` + `save()` 호출 안 함 / 50자 통과 | +2 |
| `TrailCommandHandlerConstraintTest` | 진짜 H2: direction 51자 → `IllegalArgumentException`(500 아님) / 정확히 50자 실제 저장 성공 | +2 |
| `StreamCommandHandlerConstraintTest` (신규) | 진짜 H2: name 256자 → `IllegalArgumentException` / 정확히 255자 실제 저장 성공 | +2 |

**mock 테스트만으로 검증 로직 자체는 증명된다.** 진짜 DB 테스트를 함께 두는 이유는 다르다 — 핸들러의 상수(50, 255)가 컬럼 정의에서 드리프트하는 것을 막기 위해서다.

- 정확히 MAX 길이가 **실제로 저장되어야** 상수가 컬럼보다 엄격하지 않음이 증명된다.
- MAX+1이 **500이 아니라 400**이어야 상수가 컬럼보다 느슨하지 않음이 증명된다.

이 두 방향이 모두 있어야 상수와 스키마가 묶인다. 어느 한쪽만으로는 드리프트를 못 잡는다.

신규 `StreamCommandHandlerConstraintTest`는 기존 `TrailCommandHandlerConstraintTest`의 구조를 따른다(`@DataJpaTest`, 핸들러를 `new`로 직접 생성).

### 4. 검증 방법

구현 중 각 단계에서 RED를 먼저 확인한다. 특히 진짜 DB 테스트는 검증 추가 **전에** 작성해서 `DataIntegrityViolationException`이 나는 것(= 지금의 500 경로)을 눈으로 확인한 뒤 검증을 추가한다.

## 변경 대상 파일

| 파일 | 성격 |
|---|---|
| `services/writer/src/main/java/com/stream/writer/command/TrailCommandHandler.java` | 수정 (상수 + 검증) |
| `services/writer/src/main/java/com/stream/writer/command/StreamCommandHandler.java` | 수정 (상수 + 검증) |
| `services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerTest.java` | 수정 (+2) |
| `services/writer/src/test/java/com/stream/writer/command/StreamCommandHandlerTest.java` | 수정 (+2) |
| `services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerConstraintTest.java` | 수정 (+2) |
| `services/writer/src/test/java/com/stream/writer/command/StreamCommandHandlerConstraintTest.java` | 신규 (+2) |

컨트롤러, 스키마, `apps/backend`, reader 모듈은 건드리지 않는다.

## 성공 기준

- `direction` 51자와 `name` 256자가 각각 400을 반환한다(500 아님).
- 경계값(50자 / 255자)은 정상 저장된다.
- 기존 테스트가 하나도 깨지지 않는다. writer `Tests run` 58 → 66, shared 30 / reader 33(1 기존 RED) / backend 36(5 기존 RED)은 그대로.
- Docker 데몬이 내려가 있으면 위 66에 `Skipped: 5`가 함께 나온다(Testcontainers 테스트). 이는 회귀가 아니라 위 실측 6번의 알려진 동작이다. Docker가 살아 있으면 `Skipped: 0`이어야 한다.
- 핸들러 상수를 컬럼 정의보다 크게 바꾸면 진짜 DB 테스트가 실패한다.

## 이번 범위에서 제외

- **`captures` 필드 검증** — Kafka 경로라 `ImageAnalyzedConsumer`가 예외를 삼킨다. 검증을 넣어도 증상은 여전히 "조용한 유실"이고, 삼켜지는 예외의 종류만 바뀐다. 진짜 해결은 컨슈머의 재시도/DLQ 설계이므로 별도 사이클이 맞다.
- **Bean Validation(`@Size`) 전환** — 더 선언적이지만 새 의존성이 붙고 예외 타입이 바뀌어 기존 `@ExceptionHandler` 매핑을 손봐야 하며, 기존 `if` 검증들과 두 방식이 섞인다. 전환한다면 그것만으로 하나의 사이클이 되어야 한다.
- **입력 trim** — 요청되지 않은 동작 변경이다.
- **Testcontainers silent-skip 대응** — 별도 항목.
