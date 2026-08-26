# writer 지오메트리 검증 설계

**작성일:** 2026-08-26
**브랜치:** `fix/writer-geometry-validation`
**조사 문서:** `docs/superpowers/specs/2026-08-25-writer-geometry-validation-findings.md`

## 목표

WKT의 Z/M/ZM 좌표와 빈 지오메트리가 DB까지 도달해 500이 나가는 것을 400으로 바꾼다.
덧붙여 SRID 4326 범위를 벗어난 좌표를 거부한다.

조사에서 확정된 500 입력 7가지가 대상이다 — Z/M/ZM이 Stream/Trail 양쪽(6가지),
그리고 Trail의 `POINT EMPTY`(1가지).

## 결정 사항

### 1. 검증 위치 — 핸들러의 명시적 검사

파싱 직후, 저장 전에 검사한다. `IllegalArgumentException`을 던지면 두 컨트롤러가 이미 갖고 있는
`@ExceptionHandler({ParseException, ClassCastException, IllegalArgumentException})`가 400으로
바꿔준다. 새 예외 타입도, advice 수정도 필요 없다.

버린 대안:

- **`WKTReader`/`GeometryFactory` 설정.** JTS에 "3D 좌표를 거부"하는 옵션이 없다. 2D
  `CoordinateSequenceFactory`를 강제하면 Z를 조용히 버리고 201로 저장돼서, 400이 아니라
  소리 없는 데이터 유실이 된다. 목표를 이루지 못한다.
- **`save()`의 `DataIntegrityViolationException` catch 분기.** DB 에러 문자열과 SQLState에
  의존하고 엔진마다 다를 수 있다. 잘못된 요청 때문에 불필요한 DB 왕복도 생긴다.

### 2. EMPTY 지오메트리 — 양쪽 다 거부

현재 `POINT EMPTY`는 500, `LINESTRING EMPTY`는 201로 비대칭이다. 둘 다 400으로 맞춘다.
좌표 없는 하천/카메라 위치는 도메인상 의미가 없다.

이건 Stream 쪽 동작 변경(201 → 400)이다. 다만 `LINESTRING EMPTY`가 성공하기를 기대하는
기존 테스트는 없다 — 저장소 전체 grep으로 확인했다.

### 3. 좌표 범위 검사 — 포함하되 별도 커밋

경도 ±180, 위도 ±90(경계 포함)을 벗어나면 거부한다. `POINT(999 999)`가 그대로 저장되는 것은
실제 데이터 품질 문제고 검사 자리도 같다. 다만 500 → 400 수정과 성질이 다르므로(기존에
201이던 것이 400이 된다) 커밋을 나눠 리뷰와 되돌리기를 쉽게 한다.

## 구조

새 파일 하나와 핸들러당 두 줄이 전부다.

`services/writer/src/main/java/com/stream/writer/command/GeometryValidator.java` — 패키지 전용
static 유틸. 두 핸들러가 똑같은 검사를 똑같은 순서로 하므로 진입점 하나로 모은다.

```java
Point location = (Point) wktReader.read(command.location());
GeometryValidator.validateLocation(location);   // ← 추가
trail.setLocation(location);
```

캐스트를 먼저 하고 검증한다. 그래야 `MULTIPOINT` 같은 잘못된 타입은 지금처럼
`ClassCastException` 메시지로 400이 나가고, 이미 잘 동작하는 경로가 하나도 바뀌지 않는다.

### 검사 순서

1. `isEmpty()`
2. Z/M 좌표
3. 좌표 범위 (커밋 2에서 추가)

EMPTY가 먼저인 이유는 빈 지오메트리에는 볼 좌표가 없어 2번과 3번의 판단 근거가 없기 때문이다.

### 차원 감지 방법 — 실측으로 확정

JTS 1.19.0에서 12가지 WKT를 실제로 파싱해 확인했다.

| 입력 | `seq.getDimension()` | `seq.getMeasures()` | `coord.getZ()` | `coord.getM()` |
|---|---|---|---|---|
| `POINT(126.97 37.55)` | 3 | 0 | NaN | NaN |
| `POINT Z(126.97 37.55 1)` | 3 | 0 | 1.0 | NaN |
| `POINT M(126.97 37.55 1)` | 3 | 1 | NaN | 1.0 |
| `POINT ZM(126.97 37.55 1 9)` | 4 | 1 | 1.0 | 9.0 |
| `POINT(126.97 37.55 1)` | 3 | 0 | 1.0 | NaN |
| `POINT EMPTY` | 3 | 0 | (좌표 없음) | (좌표 없음) |

LineString 여섯 가지도 같은 양상이었다.

**`getDimension()`은 쓸 수 없다.** 순수 2D 입력에도 3을 반환한다 — `CoordinateArraySequence`가
기본 `Coordinate` 클래스로부터 차원을 추론하기 때문이다. 이걸 추측으로 골랐다면 모든 정상 입력을
거부했을 것이다.

**`Coordinate.getZ()`/`getM()`의 NaN 검사를 쓴다.** 여섯 경우를 정확히 구분하고,
`Geometry.getCoordinates()`로 접근하므로 Point와 LineString에 같은 코드가 쓰인다.

부수 효과: Z 키워드 없는 옛 JTS 문법 `POINT(126.97 37.55 1)`도 같은 검사에 걸린다.
조사 문서에 없던 입력이므로 이 동작을 테스트로 고정하고 결과를 기록한다.

## 에러 메시지

- `location must not be an empty geometry`
- `location must have 2D coordinates only (Z/M ordinates are not supported)`
- `location coordinate out of WGS84 bounds: (999.0, 999.0)`

## 에러 전파

`IllegalArgumentException` → 컨트롤러 `@ExceptionHandler` → 400
`{"error": "Invalid trail data: ..."}`. backend의 `StreamServiceImpl`/`TrailServiceImpl`이
writer의 `BadRequest`를 `InvalidStreamGeometryException`/`InvalidTrailGeometryException`으로
변환하므로 사용자까지 400으로 전달된다. backend는 손대지 않는다.

## 테스트

TDD로 RED를 먼저 만든다.

- **핸들러 단위 테스트** (`StreamCommandHandlerTest`, `TrailCommandHandlerTest`, Mockito):
  Z/M/ZM/EMPTY 각각 `IllegalArgumentException` + 메시지 단언 + `save()` 미호출 verify.
- **`GeometryColumnConstraintTest`** (진짜 H2, `@DataJpaTest`): 검증을 우회해 직접 저장했을 때
  컬럼이 실제로 어떻게 반응하는지를 스키마에 묶는다 — 3D LineString과 `POINT EMPTY`는
  `DataIntegrityViolationException`으로 거부되고, `LINESTRING EMPTY`는 받아준다는 비대칭을
  재확인한다. **컨트롤러 테스트는 추가하지 않는다** — `StreamControllerTest`/`TrailControllerTest`는
  핸들러를 `@MockBean`으로 갈아끼우고 예외를 스텁해서 던지므로, 지오메트리용을 하나 더 만들어도
  `IllegalArgumentException → 400` 매핑만 검증하는 셈이 된다. 그 매핑은 기존
  `create_returns400OnMissingRequiredField` 같은 테스트가 이미 커버하고 있어, 새로 만들면
  예외 메시지만 다른 복제본이 된다.
- **회귀**: 2D 정상 입력 201 유지, 다른 지오메트리 타입/SRID 접두사/점 하나짜리 LINESTRING의
  기존 400 경로 유지.
- **범위 커밋**: `POINT(999 999)`와 `LINESTRING(999 999, 1000 1000)` 거부, 경계값
  `(180 90)`과 `(-180 -90)` 통과.

Docker 데몬이 내려가 있어 Postgres Testcontainers 테스트는 Skipped로 남는다. 회귀가 아니며
검증은 H2 기준이다. PostGIS 실측은 하지 않는다 — 이 설계는 DB에 도달하기 전에 거부하므로
어느 엔진이든 결과가 같다.

## 범위 밖

- 조사에서 이미 400으로 확인된 입력들(다른 지오메트리 타입, SRID 접두사, 점 하나짜리
  LINESTRING)은 건드리지 않는다.
- reader와 backend는 WKT를 파싱하지 않는다(main 소스에서 JTS를 쓰는 곳은 writer 핸들러
  2개와 shared 엔티티 2개뿐이다). 수정 범위는 writer로 닫힌다.
