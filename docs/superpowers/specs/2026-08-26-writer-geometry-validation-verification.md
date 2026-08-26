# writer 지오메트리 검증 — 최종 검증 결과

**작성일:** 2026-08-26
**브랜치:** `fix/writer-geometry-validation`
**커밋:** `cc865af`(Task 1+2), `43d6bd5`(Task 3)
**계획 문서:** `docs/superpowers/plans/2026-08-26-writer-geometry-validation.md`
**설계 문서:** `docs/superpowers/specs/2026-08-26-writer-geometry-validation-design.md`

이 문서는 Task 2 리포트(`.superpowers/sdd/task-2-report.md`)와 Task 3 리포트
(`.superpowers/sdd/task-3-report.md`)에 실제로 기록된 테스트 실행 결과만 옮긴 것이다.
이 문서를 쓰는 과정에서 테스트를 다시 돌리지 않았다. 두 리포트에 없는 수치는
"확인 안 됨"으로 남긴다.

## 1. 네 모듈의 최종 테스트 수치

Task 3 완료 시점(`43d6bd5` 이후)의 최종 상태다. 각 줄의 출처를 옆에 적는다.

| 모듈 | 기준선 | 최종 | 실패/에러 | 스킵 | 증감 | 출처 |
|---|---|---|---|---|---|---|
| `packages/shared` | 30 passing, 0 skip | 30 passing, 0 skip | 0 | 0 | 없음 | task-3-report.md Step 7 |
| `services/writer` | 66 passing, 5 skip (71 total) | 84 passing, 5 skip (89 total) | 0 | 5 | +18개 테스트 메서드 추가, 실패 0 | task-2-report.md("What I tested"), task-3-report.md("Full-suite verification") |
| `services/reader` | 33 중 1개 기존 RED(`ReaderApplicationTests.contextLoads`, Postgres 필요) | 동일: 33 중 1개 에러 | 1 (기존과 동일) | 0 | 없음(이 브랜치는 reader를 건드리지 않음) | task-3-report.md Step 7 |
| `apps/backend` | 36 중 5개 기존 RED(`CaptureControllerTest`, `UnsupportedOperationException: Not implemented`) | 동일: 36 중 5개 에러 | 5 (기존과 동일) | 0 | 없음(이 브랜치는 backend를 건드리지 않음) | task-3-report.md Step 7 |

writer의 증가분 세부 내역(두 리포트의 합산):
- Task 1: `GeometryValidatorTest` 5개 신규.
- Task 2: `StreamCommandHandlerTest` +2, `TrailCommandHandlerTest` +2, `GeometryColumnConstraintTest` 4개 신규. (66 → 79, task-2-report.md 기준)
- Task 3: `GeometryValidatorTest` +3, `StreamCommandHandlerTest` +1, `TrailCommandHandlerTest` +1. (79 → 84, task-3-report.md 기준)

reader/backend의 실패 개수는 이번 브랜치 작업 전후로 변화가 없다. 두 모듈 모두 이 브랜치가 손대지 않았고, Task 3 리포트가 재확인한 숫자가 계획 문서의 기준선(reader 33/1, backend 36/5)과 정확히 일치한다.

## 2. Task 2 Step 3의 RED가 예측한 형태로 나왔는가

계획은 두 가지 실패 형태를 예측했다. Task 2 리포트에 실제 출력이 그대로 인용되어 있다.

**Stream 쪽 — 예측: 예외가 아예 안 남 (mock `save()`가 `null` 반환, 정상 종료)**

```
StreamCommandHandlerTest.handle_throwsIllegalArgumentExceptionOnNon2dLocation:139
Expecting code to raise a throwable.

StreamCommandHandlerTest.handle_throwsIllegalArgumentExceptionOnEmptyLocation:155
Expecting code to raise a throwable.
```

예측과 정확히 일치했다.

**Trail 쪽 — 예측: 예외는 나지만 메시지가 `stream_id=1 does not exist`**

```
TrailCommandHandlerTest.handle_throwsIllegalArgumentExceptionOnNon2dLocation:256 [POINT Z(126.97 37.55 1)]
Expecting throwable message:
  "stream_id=1 does not exist"
to contain:
  "2D"
but did not.

TrailCommandHandlerTest.handle_throwsIllegalArgumentExceptionOnEmptyLocation:270
Expecting throwable message:
  "stream_id=1 does not exist"
to contain:
  "empty"
but did not.
```

이 역시 예측과 정확히 일치했다. `existsById`를 스텁하지 않은 채로 둔 것이 의도대로 "함정" 역할을 했다 — 예외 타입 단언만 있었다면 이 RED는 통과해버렸을 것이고, 메시지 단언이 있었기 때문에 검증 미배선이 잡혔다.

전체 실행 결과: `Tests run: 25, Failures: 4, Errors: 0, Skipped: 0` — 새로 추가한 4건이 정확히 실패하고 나머지 21건은 영향받지 않았다.

Task 3 Step 2의 RED도 계획대로였다: `GeometryValidatorTest` 8개 중 `rejectsCoordinatesOutOfWgs84Bounds` 1개만 실패(`Expecting code to raise a throwable`), 나머지 `acceptsCoordinatesAtWgs84Bounds`/`reportsDimensionBeforeBounds` 2개는 이미 통과 — 계획이 "이 둘은 회귀 방지용이라 RED가 목적이 아니다"라고 명시한 그대로다.

## 3. `GeometryColumnConstraintTest` 4건의 결과

Task 2 리포트(Step 8)에 따르면 4건 모두 통과했다(`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`).

| 테스트 | 결과 | 의미 |
|---|---|---|
| 3D LineString을 검증 없이 저장 | `DataIntegrityViolationException` 발생 | 컬럼이 3D를 거부 — Z/M 검사가 필요한 이유 |
| `POINT EMPTY`를 검증 없이 저장 | `DataIntegrityViolationException` 발생 | 컬럼이 POINT EMPTY를 거부 — Trail EMPTY 검사가 필요한 이유 |
| `LINESTRING EMPTY`를 검증 없이 저장 | 저장 성공(id 발급, `isEmpty()` true) | **재확인됨**: 컬럼은 LINESTRING EMPTY를 받아준다. 이걸 막는 것은 DB 제약이 아니라 정책 결정이라는 조사 문서·설계 문서의 전제가 이 테스트로 다시 증명됐다 |
| 2D 지오메트리를 저장 | 저장 성공(SRID 4326 확인) | 검증이 정상 입력을 막지 않음 |

Task 2 리포트는 실제 관찰된 SQL 예외까지 인용한다. 3D LineString은 `SQL Error: 22018` (`Data conversion error`), `POINT EMPTY`도 동일하게 `22018`이며, Spring이 이를 `DataIntegrityViolationException`으로 감쌌다. 조사 문서(`2026-08-25`)가 실측한 결과와 완전히 일치하고, 리포트 원문에도 "no discrepancy to report"라고 명시되어 있다.

## 4. 조사 문서의 500 7가지가 이제 400이라는 근거

조사 문서가 확정한 500 입력은 Stream/Trail 양쪽의 Z/M/ZM(6가지)과 Trail의 `POINT EMPTY`(1가지)다. 이를 증명하는 테스트는 다음과 같다 — 모두 핸들러를 목(mock)이 아니라 실제 로직으로 호출하는 테스트다.

| 500이었던 입력 | 지금 이를 증명하는 테스트 |
|---|---|
| Stream `LINESTRING Z/M/ZM` (3가지) | `StreamCommandHandlerTest.handle_throwsIllegalArgumentExceptionOnNon2dLocation` — 3가지 WKT를 루프로 돌며 `IllegalArgumentException` + "2D" 메시지 단언, `save()` 미호출 verify |
| Trail `POINT Z/M/ZM` (3가지) | `TrailCommandHandlerTest.handle_throwsIllegalArgumentExceptionOnNon2dLocation` — 동일 구조, `existsById` 미스텁 상태에서도 메시지가 "2D"임을 확인(진짜 검증이 먼저 실행됐다는 뜻) |
| Trail `POINT EMPTY` | `TrailCommandHandlerTest.handle_throwsIllegalArgumentExceptionOnEmptyLocation` — `IllegalArgumentException` + "empty" 메시지, `save()` 미호출 verify |

핸들러 테스트는 `IllegalArgumentException`이 `save()` 이전에 던져진다는 것까지만 증명한다. `IllegalArgumentException → 400` 매핑 자체는 이번 브랜치가 만든 게 아니라 두 컨트롤러(`StreamController`, `TrailController`)에 이미 있던
`@ExceptionHandler({ParseException.class, ClassCastException.class, IllegalArgumentException.class})`이며(계획 문서 사전 확인 사실 4번), 이 매핑은 기존 컨트롤러 테스트(`StreamControllerTest`/`TrailControllerTest`의 `create_returns400On...` 계열)로 이미 커버되어 있어 이번 작업에서 다시 검증하지 않았다. 이 판단의 근거는 §설계 문서 정정(아래 참고) 및 계획 문서의 "컨트롤러 테스트는 추가하지 않는다" 절에 그대로 있다.

`GeometryColumnConstraintTest`는 이 7가지가 왜 500이었는지(검증을 우회하면 컬럼이 `DataIntegrityViolationException`을 던진다)를 독립적으로 뒷받침한다 — 검증이 없던 시절 이 예외가 어느 catch에도 잡히지 않아 Spring 기본 500으로 나갔다는 조사 문서의 설명과 일치한다.

또한 계획의 완료 조건에 있는 "이미 400이던 입력들의 동작이 그대로다"(다른 지오메트리 타입, `SRID=3857;` 접두사, 점 하나짜리 LINESTRING)는 별도의 신규 테스트로 재확인하지 않았다. 근거는 두 가지다: (1) `StreamCommandHandlerTest`/`TrailCommandHandlerTest`에 이미 있던 `ClassCastException`/`ParseException` 테스트가 이번 변경 후에도 그대로 통과했다(66→84 전 구간에서 실패 0). (2) 점 하나짜리 `LINESTRING`은 구조적으로 영향받을 수 없다 — JTS가 `WKTReader.read()` 단계에서 이미 예외를 던지므로 `GeometryValidator`가 호출되는 지점(캐스트 이후)에 도달하지 않는다.

## 5. PostgreSQL/PostGIS 실측 여부

**실측하지 않았다.** 두 리포트 모두 Docker 데몬이 이 작업 전 구간에서 내려가 있었다고 기록한다. `WriterApplicationTests`(Testcontainers 기반)는 매 실행에서 `Skipped: 5`로 표시됐고, 이는 Task 2/Task 3 리포트 모두 "기존 상태이며 회귀가 아니다"라고 명시한다. 이번 검증 문서를 쓰는 시점에도 Docker를 다시 띄워 확인하지 않았다.

측정하지 않았다는 사실과 별개로, 팀이 이를 받아들인 근거는 다음과 같다(측정이 아니라 논리적 근거임을 분명히 한다): `GeometryValidator.validateLocation()`은 `save()` 호출 이전, 즉 DB에 도달하기 전에 `IllegalArgumentException`을 던진다. 이 지점은 어떤 SQL 엔진을 쓰는지와 무관하다 — H2든 PostgreSQL/PostGIS든 검증에 걸린 요청은 애초에 DB 계층에 도달하지 않는다. 다만 이것은 코드 흐름을 근거로 한 추론이지, PostGIS로 직접 확인한 결과가 아니다. `GeometryColumnConstraintTest`가 실측한 컬럼 동작(3D/POINT EMPTY 거부, LINESTRING EMPTY 허용) 역시 H2 typmod 기준이며, PostGIS가 같은 typmod 의미론을 갖는다는 것은 조사 문서(`2026-08-25`)의 가정이지 실측이 아니다.

## 6. 계획과 리포트 사이에서 발견한 사소한 불일치

- 계획 Step 3(Task 2)와 Step 2(Task 3)의 실행 명령은 `+`를 클래스 구분자로 썼지만(`StreamCommandHandlerTest+TrailCommandHandlerTest`), 이 환경의 Surefire 3.5.3은 이를 거부했다. Task 2 리포트가 콤마 구분자(`,`)로 대체해 실행했다고 명시한다. 테스트 코드나 RED/GREEN 증거 자체에는 영향이 없다 — 명령 구문 문제일 뿐이다.
- 그 외 두 리포트와 계획 문서 사이에 수치 불일치는 없었다. writer 최종 84, reader 33/1, backend 36/5, shared 30/0 모두 계획의 기준선 서술과 일치한다.

## 7. Task 1+2 코드 리뷰에서 나온 항목과 처리 결과

Task 1+2 리뷰는 Critical/Important 0건이었다. 리뷰가 "diff만으로는 확인 불가"로 남긴 한 가지 — 이미 400이던 입력(다른 지오메트리 타입, `SRID=3857;` 접두사, 점 하나짜리 LINESTRING)이 그대로인가 — 는 위 4절 마지막 문단에서 다룬 근거로 해소됐다.

리뷰의 Minor 2건은 그대로 두기로 판단했다:
1. 신규 테스트가 `org.assertj.core.api.Assertions.assertThatThrownBy(...)`를 정적 임포트 없이 완전정규명으로 호출한다 — 하지만 같은 파일이 이미 `org.junit.jupiter.api.Assertions.assertThrows`, `org.mockito.Mockito.never()`를 같은 방식으로 인라인 호출하고 있어 기존 스타일과 일치한다.
2. 캐스트 후 검증하는 근거 주석이 두 핸들러에 거의 그대로 중복된다 — 하지만 두 핸들러는 이미 VARCHAR 길이 검증 주석 블록 전체를 중복하고 있어 기존 패턴을 따른 것이다.

Task 3 리뷰의 유일한 Minor는 의도적인 이중 루프(좌표를 두 번 순회)에 대한 확인이었다 — 문서화된 트레이드오프이지 결함이 아니므로 조치하지 않았다.

## 8. 건너뛴 스텝

계획의 Task 1~3에 있는 모든 스텝이 실제로 수행됐다. 건너뛴 스텝은 없다.
