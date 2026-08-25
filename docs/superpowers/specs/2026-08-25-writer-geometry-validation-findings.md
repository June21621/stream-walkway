# WKT 지오메트리 입력 전수 조사 결과 (설계 전 실측)

**작성일:** 2026-08-25
**브랜치:** `fix/writer-geometry-validation`
**상태:** 설계 미완료 — 이 문서는 실측 데이터만 담는다.

## 배경

직전 브랜치(`fix/writer-length-validation`)의 전체 브랜치 리뷰가 `LINESTRING Z(...)`를 보내면
500이 난다는 것을 찾았다. 이번 조사는 그 범위를 확정하기 위해 WKT 변형 21가지를 실제 핸들러에
통과시켜 결과를 분류한 것이다. `@DataJpaTest`(H2)로 측정했다.

## Trail — 컬럼 `GEOMETRY(POINT, 4326)`

| WKT 입력 | 결과 |
|---|---|
| `POINT(126.97 37.55)` | 201 저장 성공 |
| `POINT Z(126.97 37.55 1)` | **500** `DataIntegrityViolationException` (22018) |
| `POINT M(126.97 37.55 1)` | **500** (22018) |
| `POINT ZM(126.97 37.55 1 9)` | **500** (22018) |
| `POINT EMPTY` | **500** (22018) |
| `POINT(999 999)` | 201 저장 성공 |
| `SRID=3857;POINT(126.97 37.55)` | 400 `ParseException` |
| `MULTIPOINT((1 2))` | 400 `ClassCastException` |
| `LINESTRING(0 0, 1 1)` | 400 `ClassCastException` |
| `GEOMETRYCOLLECTION(POINT(1 2))` | 400 `ClassCastException` |

## Stream — 컬럼 `GEOMETRY(LINESTRING, 4326)`

| WKT 입력 | 결과 |
|---|---|
| `LINESTRING(126.97 37.55, 126.98 37.56)` | 201 저장 성공 |
| `LINESTRING Z(...)` | **500** (22018) |
| `LINESTRING M(...)` | **500** (22018) |
| `LINESTRING ZM(...)` | **500** (22018) |
| `LINESTRING EMPTY` | 201 저장 성공 |
| `LINESTRING(126.97 37.55)` (점 1개) | 400 `IllegalArgumentException` (JTS가 거부) |
| `LINESTRING(999 999, 1000 1000)` | 201 저장 성공 |
| `SRID=3857;LINESTRING(...)` | 400 `ParseException` |
| `MULTILINESTRING((0 0, 1 1))` | 400 `ClassCastException` |
| `POINT(1 2)` | 400 `ClassCastException` |
| `GEOMETRYCOLLECTION(POINT(1 2))` | 400 `ClassCastException` |

## 이 데이터에서 읽히는 것

### 1. 500이 나는 입력은 7가지다

Z/M/ZM 세 변형이 양쪽 핸들러에서(6가지), 그리고 Trail의 `POINT EMPTY`(1가지).

원인은 공통이다 — `WKTReader.read()`가 3D/4D 좌표와 빈 지오메트리를 정상 파싱하고, `(Point)`/
`(LineString)` 캐스트도 타입이 맞으므로 통과한다. 그래서 `save()`까지 도달하고 거기서 컬럼
typmod가 거부한다. 어느 catch 블록도 이 예외를 잡지 않아 Spring 기본 500이 나간다.

### 2. `POINT EMPTY`와 `LINESTRING EMPTY`의 동작이 다르다

같은 "빈 지오메트리"인데 Trail은 500, Stream은 201이다. 예상 밖의 비대칭이라 설계 시
어느 쪽을 정답으로 볼지 정해야 한다. (빈 지오메트리를 애초에 거부하는 게 맞아 보이지만,
그러면 Stream의 현재 동작이 바뀐다.)

### 3. 좌표 범위 검사가 전혀 없다

`POINT(999 999)`와 `LINESTRING(999 999, 1000 1000)`이 그대로 저장된다. SRID 4326은 경위도
좌표계인데 위도 999는 존재할 수 없다. 500은 아니지만 데이터 품질 문제이며, 이번 작업에
포함할지 별도로 볼지 판단이 필요하다.

### 4. 이미 잘 처리되는 것들

다른 지오메트리 타입(`MULTIPOINT`, `GEOMETRYCOLLECTION` 등)은 `ClassCastException`으로,
WKT에 SRID 접두사를 붙이면 `ParseException`으로, 점이 하나뿐인 LINESTRING은 JTS의
`IllegalArgumentException`으로 전부 400이 된다. 손댈 필요가 없다.

## 미확인

PostGIS에서의 동작은 확인하지 못했다. 이 조사 시점에 Docker 데몬이 내려가 있었다.
H2와 PostGIS 모두 `GEOMETRY(POINT,4326)` typmod를 강제하므로 같은 거부가 예상되지만
실측하지 않았다.

## 다음 단계

설계 논의가 필요한 지점:
- 검증을 어디에 둘 것인가 (핸들러의 명시적 검사 vs `WKTReader` 설정 vs catch 분기 추가)
- `EMPTY` 지오메트리를 허용할 것인가 (현재 Stream/Trail 동작이 다름)
- 좌표 범위 검사를 이번 범위에 포함할 것인가
