# writer 작업 규약

CQRS의 쓰기 쪽. PostgreSQL/Redis를 갱신한다. `apps/backend`가 HTTP로 호출한다.

---

## 검증 순서를 바꾸지 말 것

`TrailCommandHandler` / `StreamCommandHandler`에서 순서가 의도적으로 고정돼 있다.

```
1. WKT 파싱 + 타입 캐스트
2. GeometryValidator.validateLocation()
3. FK 존재 확인 (StreamRepository.existsById)
4. save()
```

**캐스트가 검증보다 먼저다.** 그래야 잘못된 지오메트리 타입이 기존처럼
`ClassCastException` 경로로 400이 된다.
**검증이 FK 조회보다 먼저다.** 잘못된 좌표로 DB를 조회할 이유가 없다.

## 지오메트리 정책 (`GeometryValidator`)

거부 대상:

| 입력 | 이유 |
|---|---|
| Z/M/ZM 좌표 | 컬럼이 2D `geometry(Point,4326)`다. 예전엔 500으로 터졌다 |
| 빈 지오메트리 (`POINT EMPTY`) | `location`이 `NOT NULL`인데 좌표 없이 통과 = NULL 우회 |
| 경도 \|x\| > 180, 위도 \|y\| > 90 | SRID 4326은 WGS84 경위도다. 경계값(±180/±90)은 허용 |

**차원 판별에 `CoordinateSequence.getDimension()`을 쓰면 안 된다.**
JTS 1.19.0에서 순수 2D `POINT(126.97 37.55)`에도 3을 반환한다(실측).
`Coordinate.getZ()` / `getM()`의 NaN 검사를 쓸 것.

`POINT EMPTY` 거부는 버그 수정이 아니라 **동작 변경**이다(201 → 400).
하류는 안 깨진다 — `toText()`가 `"POINT EMPTY"`를 반환하고 예외를 안 낸다.
"막지 않으면 터진다"는 논거는 성립하지 않으므로, 되돌릴 거면 NULL 허용 설계를 같이 검토할 것.

## DB 제약명이 코드에 하드코딩돼 있다

`init-db.sql`이 제약에 이름을 안 붙여서 PostgreSQL 자동 생성명을 쓴다.
**스키마를 바꾸면 이 문자열도 같이 고쳐야 한다.**

| 제약명 | 변환 |
|---|---|
| `trails_stream_id_camera_number_key` | 중복 → 409 Conflict |
| `trails_stream_id_fkey` | 없는 FK → 400 |

`DataIntegrityViolationException`을 전부 409로 뭉뚱그리지 말 것.
`getMostSpecificCause()` 메시지에서 **실제 제약명이 매칭될 때만** 변환하고
나머지는 그대로 재던진다.

## 길이 검증은 애플리케이션에서

`name` 255자, `direction` 50자. DB까지 보내면 500이 되므로 핸들러에서 400으로 막는다.
`infra/scripts/init-db.sql`의 컬럼 정의와 맞춰야 한다.

## 인증

`InternalKeyFilter`가 `/internal/**`의 `X-Internal-Key`를 검사한다.
게이트웨이 우회 쓰기를 막는 유일한 장치다. 필터를 우회하는 경로
(인코딩된 경로 등)가 생기지 않게 할 것 — 실제로 그 구멍이 있었다.

## 스레드 안전성

`WKTReader` / `WKTWriter`는 스레드 안전하지 않다. 인스턴스를 필드로 공유하지 말 것.

## 테스트

```bash
./mvnw clean test
```

`clean` 필수. 테스트 스키마(`src/test/resources/schema.sql`)에는 실제 FK/UNIQUE 제약이
들어 있어야 한다 — 없으면 제약 위반 버그가 구조적으로 안 잡힌다.
실 PostgreSQL 제약 문자열은 `TrailCommandHandlerPostgresTest`(Testcontainers)가 검증한다.

**H2에서 통과한 동작을 PostGIS 동작으로 일반화하지 말 것.** 실제로 갈린 적이 있다.
