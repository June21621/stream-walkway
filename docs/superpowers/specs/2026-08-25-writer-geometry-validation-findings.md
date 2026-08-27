# WKT 지오메트리 입력 전수 조사 결과 (설계 전 실측)

**작성일:** 2026-08-25
**브랜치:** `fix/writer-geometry-validation`
**상태:** 설계 미완료 — 이 문서는 실측 데이터만 담는다.
**갱신:** 2026-08-27 — Docker 복구 후 PostGIS 실측 결과 추가 (맨 아래 절).

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

> **2026-08-27 정정:** PostGIS 실측 결과 이 중 `POINT EMPTY`는 H2에서만 500이었다.
> 프로덕션 기준으로는 6가지다. 아래 "PostGIS 실측" 절 참고.

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

## PostGIS 실측 (2026-08-27 추가)

조사 시점에는 Docker 데몬이 내려가 있어 H2로만 측정했다. 2026-08-27에 Docker를 띄우고
실제 PostGIS로 같은 입력을 넣어봤다. **가정이 하나 틀렸다.**

측정 환경: `postgis/postgis:15-3.3-alpine` (compose와 같은 이미지),
`PostGIS 3.3 USE_GEOS=1 USE_PROJ=1 USE_STATS=1`. `geometry(POINT,4326)`과
`geometry(LINESTRING,4326)` 컬럼을 만들고 `ST_GeomFromText(..., 4326)`으로 직접 INSERT했다.

| 입력 | H2 | PostGIS | 일치 |
|---|---|---|---|
| `POINT(126.97 37.55)` | 저장 성공 | `INSERT 0 1` | 일치 |
| `POINT Z(126.97 37.55 1)` | 500 (22018) | `ERROR: Geometry has Z dimension but column does not` | 일치 |
| `POINT M(126.97 37.55 1)` | 500 (22018) | `ERROR: Geometry has M dimension but column does not` | 일치 |
| `POINT ZM(126.97 37.55 1 9)` | 500 (22018) | `ERROR: Geometry has Z dimension but column does not` | 일치 |
| **`POINT EMPTY`** | **500 (22018)** | **`INSERT 0 1` — 저장된다** | **불일치** |
| `POINT(999 999)` | 저장 성공 | `INSERT 0 1` | 일치 |
| `LINESTRING(126.97 37.55, 126.98 37.56)` | 저장 성공 | `INSERT 0 1` | 일치 |
| `LINESTRING Z/M/ZM(...)` | 500 (22018) | 위와 같은 Z/M dimension ERROR | 일치 |
| `LINESTRING EMPTY` | 저장 성공 | `INSERT 0 1` | 일치 |

### 1. Z/M/ZM 거부는 두 엔진이 같다

PostGIS는 typmod가 Z/M 플래그를 직접 검사한다는 것이 에러 메시지로 확인된다
(`Geometry has Z dimension but column does not`). 이 6가지 입력이 프로덕션에서도 500이었다는
조사 결론은 유효하다. 이 부분의 수정은 진짜 버그 수정이다.

### 2. `POINT EMPTY`는 H2에서만 500이었다

위 표의 유일한 불일치다. PostGIS typmod는 지오메트리 타입·SRID·Z/M 플래그를 검사하는데
`POINT EMPTY`는 세 조건을 모두 만족하므로(타입 POINT, SRID 4326, Z/M 플래그 없음) 그대로
저장된다. **H2의 22018은 typmod 거부가 아니라 H2 자체의 변환 처리에서 온 것이었다.**

따라서 위 "500이 나는 입력은 7가지다" 결론은 **프로덕션 기준으로는 6가지**로 정정된다.
Trail `POINT EMPTY`는 프로덕션에서 지금까지 201로 성공하던 요청이다.

이 항목에 한해 이번 브랜치의 변경은 버그 수정이 아니라 **정책 변경**이다. 정책 자체(좌표
없는 하천/카메라 위치는 도메인상 의미가 없고 두 엔드포인트가 대칭이어야 한다)는 설계 문서에서
이미 그렇게 결정했고, `LINESTRING EMPTY` 거부는 애초부터 정책 변경으로 인지되어 있었다.
실측 결과 Trail/Stream EMPTY 거부가 **둘 다 같은 성격(정책 변경)** 이 되어 오히려 설계 의도와
일관된다. 다만 릴리스 노트에는 버그 수정이 아니라 동작 변경으로 적어야 한다.

### 3. 좌표 범위 검사는 원래부터 정책이었다

`POINT(999 999)`는 PostGIS도 그대로 저장한다. 위 3번 항목의 판단(500이 아니라 데이터 품질
문제)이 실측으로 확인됐다.

## 다음 단계

설계 논의가 필요한 지점:
- 검증을 어디에 둘 것인가 (핸들러의 명시적 검사 vs `WKTReader` 설정 vs catch 분기 추가)
- `EMPTY` 지오메트리를 허용할 것인가 (현재 Stream/Trail 동작이 다름)
- 좌표 범위 검사를 이번 범위에 포함할 것인가
