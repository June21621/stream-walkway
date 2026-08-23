# 테스트 스키마에 실제 FK/UNIQUE 제약 도입 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** writer/reader의 H2 테스트 스키마에 `streams`/`trails` 테이블과 실제 제약(FK, UNIQUE, CHECK)을 추가해서, `TrailCommandHandler`의 FK/UNIQUE 분기를 가짜 예외 메시지가 아니라 **진짜 제약 위반**으로 검증한다.

**Architecture:** 테스트 스키마를 운영 `infra/scripts/init-db.sql`과 같은 구조·같은 제약 이름으로 맞춘다. 부모 행(`streams`/`trails`)은 `data.sql` 시드로 넣고 IDENTITY 시퀀스를 1000부터 재시작시켜 자동 생성 id와 충돌을 막는다. 검증은 2층으로 한다 — H2 `@DataJpaTest`(빠름, 항상 실행)와 Testcontainers postgis(실제 Postgres 에러 문자열).

**Tech Stack:** Java 21, Spring Boot 3.5.1, Spring Data JPA, Hibernate Spatial, H2 2.3.232, PostgreSQL/PostGIS 15, Testcontainers 1.21.2, JUnit 5, Mockito, AssertJ

**설계 문서:** `docs/superpowers/specs/2026-08-22-test-schema-fk-constraints-design.md`

---

## 계획 수립 전 실측으로 확인한 사실 (추측 아님)

전부 이 저장소에서 실제로 실행해서 얻은 결과다. 구현 중 이 사실들을 다시 의심하느라 시간 쓰지 말 것.

1. **H2 2.3.232는 PostGIS 없이 GEOMETRY를 네이티브 지원한다.** `GEOMETRY(POINT, 4326)` / `GEOMETRY(LINESTRING, 4326)` 컬럼이 만들어지고, subtype과 SRID를 강제하며, JTS `Point`/`LineString`으로 그대로 읽힌다. `@DataJpaTest`에서 `Stream`/`Trail` 엔티티가 SRID 4326을 유지한 채 저장·재조회됐다.

2. **`ddl-auto: none`이 테스트에도 적용된다.** `services/writer/src/main/resources/application.yaml`의 설정이 `@DataJpaTest`에서도 유효하다(`Environment`로 확인). Hibernate가 테이블을 만들지 않으므로 **`schema.sql`이 유일한 스키마 소스**다.

3. **제약 위반 에러 문자열이 H2와 Postgres에서 다르다.**
   - H2 FK: `Referential integrity constraint violation: "TRAILS_STREAM_ID_FKEY: PUBLIC.TRAILS FOREIGN KEY(STREAM_ID) REFERENCES PUBLIC.STREAMS(ID) (999999)"` — **대문자**
   - Postgres FK: `ERROR: insert or update on table "trails" violates foreign key constraint "trails_stream_id_fkey"` — **소문자**
   - H2 UNIQUE: `Unique index or primary key violation: "PUBLIC.TRAILS_STREAM_ID_CAMERA_NUMBER_KEY_INDEX_9 ON ..."` — 제약 이름이 아니라 **인덱스 이름**이고 뒤의 숫자는 불안정하다.
   - Postgres UNIQUE: `ERROR: duplicate key value violates unique constraint "trails_stream_id_camera_number_key"`
   - **따라서 현재 핸들러의 `contains("trails_stream_id_fkey")`는 Postgres에서 true, H2에서 false다.**

4. **Hibernate의 `getConstraintName()`은 H2에서 `RAILS_STREAM_ID_FKEY`를 돌려준다** (앞 글자 `T`가 잘림). 이 API로 갈아타는 선택지는 없다.

5. **제약 이름을 명시하지 않으면 H2가 `CONSTRAINT_A67` 같은 이름을 붙인다.** 반드시 `CONSTRAINT <name>` 절로 Postgres와 같은 이름을 지정해야 한다.

6. **`save()` 단독으로도 제약 위반이 즉시 발생한다.** IDENTITY 생성이라 Hibernate가 INSERT를 미루지 않는다. `saveAndFlush()`가 필요 없다. 핸들러의 `try { trailRepository.save(trail) } catch (DataIntegrityViolationException e)`가 그대로 동작한다.

7. **현재 핸들러는 H2에서 변환하지 않고 `DataIntegrityViolationException`을 그대로 던진다.** 실제로 확인했다. 이것이 Task 3의 RED 상태다.

8. **`data.sql`이 자동으로 적용된다.** `schema.sql` 다음에 실행되며, `@DataJpaTest`의 트랜잭션 롤백 이전에 커밋되므로 모든 테스트에서 시드 행이 보인다.

9. **`ALTER TABLE ... ALTER COLUMN id RESTART WITH 1000`이 H2에서 동작한다.** 시드로 명시적 id 1,2,3을 넣어도 이후 자동 생성 id가 1000부터 시작해 충돌하지 않는다(실측: generated stream id = 1000).

10. **`@Sql(statements=...)`은 `@BeforeEach`보다 먼저 실행된다.** 그래서 부모 행 시드는 `@BeforeEach`가 아니라 `data.sql`에 있어야 한다. `data.sql` 시드 위에서 reader의 `@Sql` capture INSERT가 정상 동작하는 걸 확인했다.

11. **한글 CHECK 제약은 H2에서 정상 동작한다.** `CHECK (road_status IN ('양호', '주의', '불량'))`가 `'양호'`/`'불량'`은 통과시키고 `'BOGUS'`와 `'보통'`은 SQLState 23513으로 거부한다.

12. **reader의 `CaptureRepositoryTest`가 `'보통'`을 5번 삽입한다.** 운영 CHECK 제약이 허용하지 않는 값이다(`'양호', '주의', '불량'`만 허용). 이 5곳은 `'주의'`로 고쳐야 한다.

13. **기준선 (측정값)**

    | 모듈 | 명령 | 결과 | 소요 |
    |---|---|---|---|
    | shared | `./mvnw -o test` | 30 pass | 4.0s |
    | reader | `./mvnw -o test` | 32 run / 1 error | 17.6s |
    | writer | `./mvnw -o test -Dtest='!WriterApplicationTests'` | 46 pass | 16.1s |
    | backend | `./mvnw -o test` | 36 run / 5 error | 11.4s |

    실패 6개는 **전부 기존 RED**이며 이번 작업과 무관하다:
    - `reader`: `ReaderApplicationTests.contextLoads` — 실제 Postgres 연결 필요
    - `backend`: `CaptureControllerTest` 5개 — `UnsupportedOperationException: Not implemented` (알려진 스텁)

    즉 GREEN 기준선은 shared 30 / reader 31 / writer 46 / backend 31이다.

14. **Docker가 떠 있고 `postgis/postgis:15-3.3-alpine` 이미지가 로컬에 있다** (pull 시간 0). 컨테이너 기동부터 `pg_isready` OK까지 약 2.5초.

15. **Testcontainers 1.21.2를 Spring Boot 3.5.1이 관리한다.** `org.testcontainers:junit-jupiter` 1.21.2에 `EnabledIfDockerAvailable` 클래스가 실재한다(jar 목록으로 확인).

16. **운영 `init-db.sql`을 단일 `Statement.execute()`로 넘기면 `$$ ... $$` plpgsql 블록까지 문제없이 적용된다** (probe에서 성공).

---

## Global Constraints

- **패키지 루트**: writer는 `com.stream.writer`, reader는 `com.stream.reader`, 엔티티는 `com.stream.shared.entity`. Java 21.
- **제약 이름은 운영 Postgres가 자동 생성하는 이름과 정확히 일치해야 한다**: `trails_stream_id_fkey`, `trails_stream_id_camera_number_key`, `trails_status_check`, `captures_trail_id_fkey`, `captures_stream_id_fkey`, `captures_road_status_check`.
- **H2 에러 문자열 자체를 테스트에서 단언하지 말 것.** `..._KEY_INDEX_9`의 숫자 접미사는 H2 내부 객체 카운터라 불안정하다. 예외 타입과 동작만 검증한다.
- **`services/writer/src/test/resources/schema.sql`과 `services/reader/src/test/resources/schema.sql`은 내용이 완전히 동일해야 한다.** `data.sql` 두 벌도 마찬가지다.
- **Mockito 엄격 모드(strict stubs)를 쓴다.** 실제로 호출되지 않는 스텁을 추가하면 `UnnecessaryStubbingException`으로 실패한다.
- **`WriterApplicationTests`와 `ReaderApplicationTests`는 실제 Postgres가 필요해 이 환경에서 실패한다.** writer 테스트 실행 시 `-Dtest='!WriterApplicationTests'`로 제외한다. reader는 제외하지 않고 돌리되 `ReaderApplicationTests` 1건 실패는 기존 RED로 간주한다.
- **PowerShell에서 `-Dtest=...`를 넘길 때는 반드시 따옴표로 감싼다**: `.\mvnw.cmd -B -o test "-Dtest=SomeTest"`. 안 그러면 Maven이 인자를 lifecycle phase로 오해한다.
- **`packages/shared`를 먼저 install해야 reader/writer가 빌드된다**: `cd packages/shared && ./mvnw -o install -DskipTests`.
- 루트에 `mvn`이 없다. 각 모듈의 `./mvnw`(PowerShell에서는 `.\mvnw.cmd`)를 쓴다.

---

## Task 1: writer 테스트 스키마와 시드 데이터

**Files:**
- Modify: `services/writer/src/test/resources/schema.sql`
- Create: `services/writer/src/test/resources/data.sql`
- Modify: `services/writer/src/test/java/com/stream/writer/repository/CaptureRepositoryTest.java`

**Interfaces:**
- Produces: 테스트 H2 DB에 `streams`(시드 id 1,2), `trails`(시드 id 1,2,3), `captures` 테이블이 운영과 같은 제약 이름으로 존재한다. `streams`/`trails`의 IDENTITY는 1000부터 시작한다. 이후 모든 Task가 이 스키마를 전제로 한다.

- [ ] **Step 1: `schema.sql`을 아래 내용으로 완전히 교체한다**

`services/writer/src/test/resources/schema.sql`:

```sql
-- 이 파일은 운영 스키마(infra/scripts/init-db.sql)를 H2에 맞게 옮긴 것이다.
-- 제약 이름은 운영 PostgreSQL이 자동 생성하는 이름과 정확히 일치시켰다.
-- TrailCommandHandler가 그 이름으로 FK/UNIQUE 위반을 구분하기 때문이다.
-- 이름을 명시하지 않으면 H2가 CONSTRAINT_A67 같은 이름을 붙여 아무것도 검증할 수 없다.
--
-- 운영에는 있지만 여기 없는 것:
--   - PostGIS extension: H2 2.3.232가 GEOMETRY를 네이티브 지원하므로 불필요하다.
--   - update_updated_at_column() 트리거: H2에 plpgsql이 없다.
--     captures.updated_at 컬럼은 두되 자동 갱신되지 않는다.
--   - 성능 인덱스: 테스트에서 의미가 없다.
--
-- ⚠️ writer와 reader의 이 파일은 내용이 바이트 단위로 동일해야 한다.
--    한쪽을 고치면 다른 쪽에 그대로 복사할 것.

CREATE TABLE IF NOT EXISTS streams (
    id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    location GEOMETRY(LINESTRING, 4326) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS trails (
    id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    stream_id INTEGER NOT NULL,
    camera_number VARCHAR NOT NULL,
    location GEOMETRY(POINT, 4326) NOT NULL,
    direction VARCHAR(50),
    status VARCHAR(20) DEFAULT 'active',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT trails_stream_id_fkey FOREIGN KEY (stream_id) REFERENCES streams(id) ON DELETE CASCADE,
    CONSTRAINT trails_stream_id_camera_number_key UNIQUE (stream_id, camera_number),
    CONSTRAINT trails_status_check CHECK (status IN ('active', 'inactive'))
);

CREATE TABLE IF NOT EXISTS captures (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    trail_id INTEGER NOT NULL,
    stream_id INTEGER NOT NULL,
    image_path VARCHAR(500) NOT NULL,
    road_status VARCHAR(10),
    confidence DECIMAL(3,2) DEFAULT 0.0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT captures_trail_id_fkey FOREIGN KEY (trail_id) REFERENCES trails(id) ON DELETE CASCADE,
    CONSTRAINT captures_stream_id_fkey FOREIGN KEY (stream_id) REFERENCES streams(id) ON DELETE CASCADE,
    CONSTRAINT captures_road_status_check CHECK (road_status IN ('양호', '주의', '불량'))
);
```

- [ ] **Step 2: `data.sql`을 새로 만든다**

`services/writer/src/test/resources/data.sql`:

```sql
-- captures에 FK가 생겼으므로 부모 행이 먼저 있어야 한다.
-- 기존 테스트들이 trail_id 1~3, stream_id 1~2를 하드코딩해서 쓰므로 그 id를 그대로 만든다.
--
-- 이 시드는 @BeforeEach가 아니라 data.sql에 있어야 한다.
-- @Sql(statements=...)이 @BeforeEach보다 먼저 실행되기 때문이다(reader 테스트가 @Sql을 쓴다).
--
-- H2의 GENERATED BY DEFAULT AS IDENTITY는 명시적 id를 받아도 시퀀스를 앞당기지 않는다.
-- RESTART를 하지 않으면 이후 자동 생성 id가 1부터 나와 시드와 PK 충돌이 난다.
--
-- ⚠️ writer와 reader의 이 파일은 내용이 바이트 단위로 동일해야 한다.
--    한쪽을 고치면 다른 쪽에 그대로 복사할 것.

INSERT INTO streams (id, name, location) VALUES
    (1, 'seed-stream-1', 'SRID=4326;LINESTRING(126.97 37.55, 126.98 37.56)'),
    (2, 'seed-stream-2', 'SRID=4326;LINESTRING(127.00 37.50, 127.01 37.51)');

INSERT INTO trails (id, stream_id, camera_number, location, status) VALUES
    (1, 1, 'SEED-CAM-1', 'SRID=4326;POINT(126.97 37.55)', 'active'),
    (2, 1, 'SEED-CAM-2', 'SRID=4326;POINT(126.98 37.56)', 'active'),
    (3, 2, 'SEED-CAM-3', 'SRID=4326;POINT(127.00 37.50)', 'active');

ALTER TABLE streams ALTER COLUMN id RESTART WITH 1000;
ALTER TABLE trails ALTER COLUMN id RESTART WITH 1000;
```

- [ ] **Step 3: 테스트를 돌려서 기존 writer 테스트가 여전히 통과하는지 확인한다**

Run (PowerShell, `services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=CaptureRepositoryTest"
```
Expected: PASS, `Tests run: 8, Failures: 0, Errors: 0`.

writer의 `CaptureRepositoryTest`가 쓰는 `(trail_id, stream_id)` 조합은 `(1,1)`, `(1,2)`, `(2,1)`, `(3,2)`인데 전부 시드 범위 안이라 그대로 통과해야 한다. 만약 FK 위반이 나면 시드가 안 들어간 것이니 `data.sql` 파일 이름과 위치를 먼저 확인할 것.

- [ ] **Step 4: `createdAt` 검증을 강화하는 실패 테스트를 쓴다**

지금 `save_setsCreatedAtViaPrePersist`는 `isNotNull()`만 확인해서 타임존이 어긋나도 통과한다. `services/writer/src/test/java/com/stream/writer/repository/CaptureRepositoryTest.java`에서 해당 메서드를 아래로 교체한다:

```java
    @Test
    @DisplayName("save() - 저장 시 @PrePersist에 의해 createdAt이 자동 설정되고, DB 왕복 후에도 같은 시각이다")
    void save_setsCreatedAtViaPrePersist() {
        Capture capture = buildCapture(1, 1, "/images/cap_001.jpg");
        assertThat(capture.getCreatedAt()).isNull(); // 저장 전에는 null

        Instant before = Instant.now().minusSeconds(1);
        Capture saved = captureRepository.save(capture);
        entityManager.flush();
        Instant after = Instant.now().plusSeconds(1);

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getCreatedAt()).isBetween(before, after);

        // DB를 실제로 왕복시켜 TIMESTAMPTZ 컬럼이 Instant를 밀지 않는지 확인한다.
        // isNotNull()만 보던 이전 버전은 타임존이 어긋나도 통과했다.
        Instant inMemory = saved.getCreatedAt();
        entityManager.clear();
        Capture reloaded = captureRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getCreatedAt()).isBetween(before, after);
        assertThat(reloaded.getCreatedAt().getEpochSecond()).isEqualTo(inMemory.getEpochSecond());
    }
```

같은 파일의 import 블록에 추가한다(`import java.util.List;` 위):

```java
import java.time.Instant;
```

- [ ] **Step 5: 테스트를 돌려서 통과하는지 확인한다**

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=CaptureRepositoryTest"
```
Expected: PASS, `Tests run: 8`. 개수는 그대로다(메서드를 교체했을 뿐 추가하지 않았다).

- [ ] **Step 6: writer 전체 테스트로 회귀를 확인한다**

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=!WriterApplicationTests"
```
Expected: `Tests run: 46, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.

- [ ] **Step 7: 커밋**

```bash
git add services/writer/src/test/resources/schema.sql services/writer/src/test/resources/data.sql services/writer/src/test/java/com/stream/writer/repository/CaptureRepositoryTest.java
git commit -m "test(writer): H2 테스트 스키마에 streams/trails와 실제 FK/UNIQUE 제약 추가"
```

---

## Task 2: reader 테스트 스키마와 시드 데이터

**Files:**
- Modify: `services/reader/src/test/resources/schema.sql`
- Create: `services/reader/src/test/resources/data.sql`
- Modify: `services/reader/src/test/java/com/stream/reader/repository/CaptureRepositoryTest.java`

**Interfaces:**
- Consumes: Task 1에서 확정한 스키마·시드 내용(글자 그대로 동일해야 한다).
- Produces: reader 테스트 DB도 writer와 같은 스키마·시드를 갖는다.

- [ ] **Step 1: writer의 두 파일을 reader로 그대로 복사한다**

Run (저장소 루트에서, Git Bash):
```bash
cp services/writer/src/test/resources/schema.sql services/reader/src/test/resources/schema.sql
cp services/writer/src/test/resources/data.sql services/reader/src/test/resources/data.sql
```

두 파일은 **바이트 단위로 동일해야 한다.** 주석을 포함해 아무것도 고치지 말고 그대로 복사한다. 확인:

```bash
diff services/writer/src/test/resources/schema.sql services/reader/src/test/resources/schema.sql && echo "schema.sql identical"
diff services/writer/src/test/resources/data.sql services/reader/src/test/resources/data.sql && echo "data.sql identical"
```
Expected: 두 줄 모두 `identical`이 출력되고 diff 출력은 없다.

- [ ] **Step 2: 테스트를 돌려서 실패를 확인한다**

Run (`services/reader`에서):
```
.\mvnw.cmd -B -o test "-Dtest=CaptureRepositoryTest"
```
Expected: **FAIL.** `'보통'`을 삽입하는 테스트 5개가 `captures_road_status_check` CHECK 제약에 걸린다. 이게 이번 작업이 잡아내려던 드리프트다 — reader 테스트가 지금까지 운영 Postgres라면 거부됐을 데이터를 넣고 있었다.

- [ ] **Step 3: `'보통'`을 `'주의'`로 고친다**

운영 스키마의 `CHECK (road_status IN ('양호', '주의', '불량'))`가 허용하는 값은 세 개뿐이고 `'보통'`은 그중에 없다. `services/reader/src/test/java/com/stream/reader/repository/CaptureRepositoryTest.java`에서 `'보통'`이 나오는 5곳을 전부 `'주의'`로 바꾼다.

Run (저장소 루트에서, Git Bash):
```bash
sed -i "s/'보통'/'주의'/g" services/reader/src/test/java/com/stream/reader/repository/CaptureRepositoryTest.java
grep -c "'주의'" services/reader/src/test/java/com/stream/reader/repository/CaptureRepositoryTest.java
```
Expected: `5`

바뀐 줄은 아래 5곳이다(각각 `@Sql` 안의 두 번째 INSERT):
- `findByTrailId_returnsMatchingCaptures`
- `findByTrailId_excludesOtherTrailIds`
- `findByStreamId_returnsMatchingCaptures`
- `findByStreamId_excludesOtherStreamIds`
- `findAll_returnsAllCaptures`

이 테스트들은 `road_status` 값 자체를 단언하지 않고 `trailId`/`streamId`/개수만 보므로 값 변경이 검증 의도를 바꾸지 않는다. (`road_status`를 단언하는 유일한 테스트는 `findByTrailId_fieldsAreMappedCorrectly`인데 그건 `'양호'`를 쓰므로 손대지 않는다.)

- [ ] **Step 4: 테스트를 돌려서 통과하는지 확인한다**

Run (`services/reader`에서):
```
.\mvnw.cmd -B -o test "-Dtest=CaptureRepositoryTest"
```
Expected: PASS, `Tests run: 10, Failures: 0, Errors: 0`.

- [ ] **Step 5: reader 전체 테스트로 회귀를 확인한다**

Run (`services/reader`에서):
```
.\mvnw.cmd -B -o test
```
Expected: `Tests run: 32, Failures: 0, Errors: 1`. 유일한 실패는 `ReaderApplicationTests.contextLoads`이고 이건 실제 Postgres가 필요한 **기존 RED**다. 다른 실패가 있으면 안 된다.

- [ ] **Step 6: 커밋**

```bash
git add services/reader/src/test/resources/schema.sql services/reader/src/test/resources/data.sql services/reader/src/test/java/com/stream/reader/repository/CaptureRepositoryTest.java
git commit -m "test(reader): 테스트 스키마 동기화 및 CHECK 제약이 거부하는 road_status 값 수정"
```

---

## Task 3: 진짜 제약 위반으로 핸들러 분기 검증 (H2)

**Files:**
- Create: `services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerConstraintTest.java`
- Modify: `services/writer/src/main/java/com/stream/writer/command/TrailCommandHandler.java`

**Interfaces:**
- Consumes: Task 1의 스키마/시드. `TrailCommandHandler(TrailRepository, StreamRepository)` 생성자, `handle(CreateTrailCommand)`, `CreateTrailCommand(Long streamId, String cameraNumber, String location, String direction, String status)`, `DuplicateTrailException`.
- Produces: `TrailCommandHandler`의 제약 이름 매칭이 대소문자를 구분하지 않게 된다. Task 4가 같은 핸들러를 실제 Postgres에서 재검증한다.

**배경:** 지금 핸들러는 `message.contains("trails_stream_id_fkey")`로 소문자 매칭을 한다. Postgres에서는 맞지만 H2는 식별자를 대문자로 출력해서 매칭이 실패하고, 변환 없이 `DataIntegrityViolationException`이 그대로 나간다(실측 확인). SQL 식별자는 원래 대소문자를 구분하지 않으므로 대소문자 무시 비교가 프로덕션 코드로서도 옳다.

**핸들러 선체크 우회:** 핸들러는 `save()` 전에 `streamRepository.existsById()`로 하천 존재를 먼저 확인한다. 그래서 없는 id를 그냥 넘기면 FK까지 도달하지 못하고 선체크에서 걸린다. FK catch 분기를 태우려면 `StreamRepository`만 Mockito mock으로 `existsById → true` 고정하고 `TrailRepository`는 진짜 H2에 붙인 핸들러를 직접 생성해야 한다. 이는 핸들러 주석이 말하는 "존재 확인과 저장 사이에 하천이 삭제되는 경쟁에서 진 경우"를 그대로 재현하는 것이다. UNIQUE 분기는 이 우회가 필요 없다.

- [ ] **Step 1: 실패하는 테스트를 새 파일로 쓴다**

`services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerConstraintTest.java`:

```java
package com.stream.writer.command;

import com.stream.shared.entity.Stream;
import com.stream.shared.entity.Trail;
import com.stream.writer.exception.DuplicateTrailException;
import com.stream.writer.repository.StreamRepository;
import com.stream.writer.repository.TrailRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import org.hibernate.Session;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

// ─────────────────────────────────────────
// 손으로 쓴 가짜 예외 메시지가 아니라 H2가 실제로 일으킨 제약 위반으로
// TrailCommandHandler의 FK/UNIQUE 분기를 검증한다.
//
// 이 테스트가 존재하는 이유: 이전에는 테스트 스키마에 FK가 아예 없어서
// 어떤 자동화 테스트도 진짜 제약 위반을 만들 수 없었고, 그 때문에
// "없는 stream_id로 Trail 생성 시 500" 버그가 유닛 테스트 176개를
// 통과하면서도 Docker 실기동에서야 발견됐다.
//
// ⚠️ H2의 에러 문자열 자체는 단언하지 않는다. H2의 UNIQUE 위반 메시지는
// 제약 이름이 아니라 인덱스 이름(..._KEY_INDEX_9)을 담고, 뒤의 숫자는
// H2 내부 객체 카운터라 불안정하다. 예외 타입과 동작만 검증한다.
// 실제 Postgres 문자열 검증은 TrailCommandHandlerPostgresTest가 담당한다.
// ─────────────────────────────────────────
@DataJpaTest
@DisplayName("Writer - TrailCommandHandler 실제 제약 위반 테스트 (H2)")
class TrailCommandHandlerConstraintTest {

    @Autowired
    private TrailRepository trailRepository;

    @Autowired
    private StreamRepository streamRepository;

    @Autowired
    private TestEntityManager entityManager;

    private static final long SEED_STREAM_ID = 1L;

    private static WKTReader wkt() {
        return new WKTReader(new GeometryFactory(new PrecisionModel(), Trail.SRID));
    }

    // 선체크를 항상 통과시키는 핸들러. 존재 확인과 저장 사이에 하천이 삭제된
    // 경쟁 상황을 재현해서 FK catch 분기까지 도달하게 만든다.
    private TrailCommandHandler handlerThatSkipsExistenceCheck() {
        StreamRepository alwaysExists = mock(StreamRepository.class);
        given(alwaysExists.existsById(anyLong())).willReturn(true);
        return new TrailCommandHandler(trailRepository, alwaysExists);
    }

    @Test
    @DisplayName("진짜 FK 제약 위반이 IllegalArgumentException(400 경로)으로 변환된다")
    void realForeignKeyViolationBecomesIllegalArgumentException() {
        TrailCommandHandler handler = handlerThatSkipsExistenceCheck();
        CreateTrailCommand command =
                new CreateTrailCommand(999999L, "CAM-FK", "POINT(126.97 37.55)", "N", "active");

        assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999999")
                .hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("진짜 UNIQUE 제약 위반이 DuplicateTrailException(409 경로)으로 변환된다")
    void realUniqueViolationBecomesDuplicateTrailException() throws ParseException {
        TrailCommandHandler handler = new TrailCommandHandler(trailRepository, streamRepository);
        CreateTrailCommand first =
                new CreateTrailCommand(SEED_STREAM_ID, "CAM-DUP", "POINT(126.97 37.55)", "N", "active");
        handler.handle(first);

        CreateTrailCommand duplicate =
                new CreateTrailCommand(SEED_STREAM_ID, "CAM-DUP", "POINT(126.97 37.55)", "N", "active");

        assertThatThrownBy(() -> handler.handle(duplicate))
                .isInstanceOf(DuplicateTrailException.class)
                .hasMessageContaining("CAM-DUP");
    }

    @Test
    @DisplayName("리포지토리 수준에서도 FK 위반이 실제로 발생한다 (스키마에 제약이 살아있는지 확인)")
    void schemaActuallyEnforcesForeignKey() throws ParseException {
        Trail orphan = new Trail();
        orphan.setStreamId(999999L);
        orphan.setCameraNumber("CAM-ORPHAN");
        orphan.setLocation((org.locationtech.jts.geom.Point) wkt().read("POINT(126.97 37.55)"));
        orphan.setStatus("active");

        assertThatThrownBy(() -> trailRepository.save(orphan))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("geometry가 SRID 4326을 유지한 채 DB를 왕복한다")
    void geometrySurvivesRoundTrip() throws ParseException {
        TrailCommandHandler handler = new TrailCommandHandler(trailRepository, streamRepository);
        Trail saved = handler.handle(
                new CreateTrailCommand(SEED_STREAM_ID, "CAM-GEO", "POINT(126.97 37.55)", "N", "active"));

        entityManager.flush();
        entityManager.clear();

        Trail reloaded = trailRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getLocation().getSRID()).isEqualTo(4326);
        assertThat(reloaded.getLocation().getX()).isEqualTo(126.97);
        assertThat(reloaded.getLocation().getY()).isEqualTo(37.55);
    }

    @Test
    @DisplayName("createdAt이 정확히 왕복하고, created_at 컬럼이 실제로 TIMESTAMPTZ임을 raw JDBC 타입으로 증명한다")
    void createdAtSurvivesRoundTripAndColumnIsTimestamptz() throws ParseException {
        TrailCommandHandler handler = new TrailCommandHandler(trailRepository, streamRepository);

        Instant before = Instant.now().minusSeconds(1);
        Trail saved = handler.handle(
                new CreateTrailCommand(SEED_STREAM_ID, "CAM-TS", "POINT(126.97 37.55)", "N", "active"));
        Instant after = Instant.now().plusSeconds(1);

        Instant inMemory = saved.getCreatedAt();
        assertThat(inMemory).isBetween(before, after);

        entityManager.flush();
        entityManager.clear();

        // 이 왕복 비교만으로는 컬럼이 TIMESTAMP인지 TIMESTAMP WITH TIME ZONE인지 구분하지 못한다.
        // Hibernate가 쓰기와 읽기에 같은 JVM 기본 시간대 변환을 적용하고 그 변환은 self-inverse라서,
        // 한 프로세스 안에서는 어느 쪽이든 값이 그대로 돌아온다(실측 확인).
        // 여기서 잡히는 건 값이 멈췄거나 정밀도가 깎이는 경우다.
        Trail reloaded = trailRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getCreatedAt()).isBetween(before, after);
        assertThat(reloaded.getCreatedAt().getEpochSecond()).isEqualTo(inMemory.getEpochSecond());

        // 컬럼 타입은 raw JDBC 값의 런타임 타입으로 확인한다.
        // H2에서 TIMESTAMP WITH TIME ZONE 컬럼은 OffsetDateTime으로 읽히지만 평범한 TIMESTAMP는
        // 그렇지 않으므로, 스키마가 TIMESTAMP로 되돌아가면 실제로 실패하는 진짜 판별 기준이다.
        Object rawCreatedAt = entityManager.getEntityManager()
                .unwrap(Session.class)
                .doReturningWork(connection -> {
                    try (PreparedStatement stmt = connection.prepareStatement(
                            "SELECT created_at FROM trails WHERE id = ?")) {
                        stmt.setLong(1, saved.getId());
                        try (ResultSet rs = stmt.executeQuery()) {
                            rs.next();
                            return rs.getObject("created_at");
                        }
                    }
                });
        assertThat(rawCreatedAt).isInstanceOf(OffsetDateTime.class);
    }

    @Test
    @DisplayName("ON DELETE CASCADE가 동작한다 - 하천을 지우면 산책로도 사라진다")
    void deletingStreamCascadesToTrails() throws ParseException {
        Stream stream = new Stream();
        stream.setName("cascade-target");
        stream.setLocation((LineString) wkt().read("LINESTRING(126.97 37.55, 126.98 37.56)"));
        Stream savedStream = streamRepository.save(stream);

        TrailCommandHandler handler = new TrailCommandHandler(trailRepository, streamRepository);
        Trail trail = handler.handle(
                new CreateTrailCommand(savedStream.getId(), "CAM-CASCADE", "POINT(126.97 37.55)", "N", "active"));
        Long trailId = trail.getId();

        entityManager.flush();
        streamRepository.deleteById(savedStream.getId());
        entityManager.flush();
        entityManager.clear();

        assertThat(trailRepository.findById(trailId)).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트를 돌려서 실패를 확인한다**

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=TrailCommandHandlerConstraintTest"
```
Expected: **FAIL.** `realForeignKeyViolationBecomesIllegalArgumentException`과 `realUniqueViolationBecomesDuplicateTrailException` 두 개가 실패한다. 실제로 던져지는 건 `DataIntegrityViolationException`이다 — H2가 제약 이름을 대문자로 출력해서 핸들러의 소문자 `contains` 매칭이 실패하기 때문이다. 나머지 4개는 통과해야 한다.

- [ ] **Step 3: 핸들러의 매칭을 대소문자 무시로 바꾼다**

`services/writer/src/main/java/com/stream/writer/command/TrailCommandHandler.java`의 `catch` 블록을 아래로 교체한다:

```java
        } catch (DataIntegrityViolationException e) {
            String message = e.getMostSpecificCause().getMessage();
            String upper = message == null ? "" : message.toUpperCase(Locale.ROOT);
            if (upper.contains("TRAILS_STREAM_ID_CAMERA_NUMBER_KEY")) {
                throw new DuplicateTrailException(
                        "stream_id=" + command.streamId() + ", camera_number=" + command.cameraNumber() + " already exists");
            }
            if (upper.contains("TRAILS_STREAM_ID_FKEY")) {
                throw new IllegalArgumentException("stream_id=" + command.streamId() + " does not exist");
            }
            throw e;
        }
```

같은 파일의 import 블록에 추가한다(`import java.util.Set;` 위):

```java
import java.util.Locale;
```

그리고 클래스 주석의 아래 문단을

```
    // UNIQUE(stream_id, camera_number) 위반은 DuplicateTrailException(409)으로,
    // FK(trails_stream_id_fkey) 위반은 IllegalArgumentException(400)으로 변환한다.
```

아래로 교체한다:

```
    // UNIQUE(stream_id, camera_number) 위반은 DuplicateTrailException(409)으로,
    // FK(trails_stream_id_fkey) 위반은 IllegalArgumentException(400)으로 변환한다.
    //
    // 제약 이름 비교는 대소문자를 구분하지 않는다. SQL 식별자가 원래 대소문자를
    // 가리지 않을 뿐 아니라, 실제로 두 엔진이 다른 형태로 출력하기 때문이다.
    // PostgreSQL: ...violates foreign key constraint "trails_stream_id_fkey" (소문자)
    // H2:         "TRAILS_STREAM_ID_FKEY: PUBLIC.TRAILS FOREIGN KEY(...)"    (대문자)
    // UNIQUE 검사를 FK보다 먼저 하는데, 두 엔진 모두에서 안전하다 —
    // FK 위반 메시지에는 TRAILS_STREAM_ID_CAMERA_NUMBER_KEY가 들어가지 않는다.
```

- [ ] **Step 4: 테스트를 돌려서 통과하는지 확인한다**

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=TrailCommandHandlerConstraintTest"
```
Expected: PASS, `Tests run: 6, Failures: 0, Errors: 0`.

- [ ] **Step 5: 기존 mock 기반 테스트가 여전히 통과하는지 확인한다**

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=TrailCommandHandlerTest"
```
Expected: PASS, `Tests run: 12`. 기존 테스트의 가짜 메시지는 전부 소문자 Postgres 형식인데, 대소문자 무시 매칭이 소문자도 당연히 잡으므로 그대로 통과한다.

- [ ] **Step 6: writer 전체 테스트로 회귀를 확인한다**

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=!WriterApplicationTests"
```
Expected: `Tests run: 52, Failures: 0, Errors: 0` (기존 46 + 신규 6), `BUILD SUCCESS`.

- [ ] **Step 7: 커밋**

```bash
git add services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerConstraintTest.java services/writer/src/main/java/com/stream/writer/command/TrailCommandHandler.java
git commit -m "test(writer): 진짜 FK/UNIQUE 위반으로 핸들러 분기 검증, 제약명 매칭을 대소문자 무시로 변경"
```

---

## Task 4: 실제 PostgreSQL 에러 문자열 검증 (Testcontainers)

**Files:**
- Modify: `services/writer/pom.xml`
- Create: `services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerPostgresTest.java`

**Interfaces:**
- Consumes: Task 3에서 수정한 `TrailCommandHandler`. 운영 스키마 파일 `infra/scripts/init-db.sql`.
- Produces: 없음(검증 전용 테스트).

**배경:** Task 3은 진짜 제약 위반을 쓰지만 그건 H2의 에러 문자열이다. 이 저장소가 운영에서 쓰는 건 PostgreSQL이고, 원래 목표는 "실제 Postgres 에러 문자열이 `trails_stream_id_fkey`를 포함하는지" 검증하는 것이었다. 이 테스트가 그 답이다. 게다가 손으로 옮겨 적은 스키마가 아니라 **운영 `init-db.sql`을 그대로** 컨테이너에 적용하므로 스키마 드리프트도 원천 차단된다.

- [ ] **Step 1: Testcontainers 의존성을 추가한다**

`services/writer/pom.xml`의 `<dependencies>` 안, 기존 H2 의존성 바로 아래에 추가한다:

```xml
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>postgresql</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>junit-jupiter</artifactId>
			<scope>test</scope>
		</dependency>
```

버전은 쓰지 않는다 — `spring-boot-starter-parent` 3.5.1이 Testcontainers 1.21.2로 관리한다.

`postgresql` JDBC 드라이버는 이미 `runtime` 스코프로 선언돼 있으니 추가하지 않는다.

- [ ] **Step 2: 의존성이 받아지는지 확인한다**

Run (`services/writer`에서, **오프라인 플래그 없이**):
```
.\mvnw.cmd -B -q dependency:resolve
```
Expected: 에러 없이 끝난다. (이 스텝은 네트워크가 필요하다. 이후 스텝부터는 다시 `-o`를 써도 된다.)

- [ ] **Step 3: 테스트를 쓴다**

`services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerPostgresTest.java`:

```java
package com.stream.writer.command;

import com.stream.shared.entity.Trail;
import com.stream.writer.exception.DuplicateTrailException;
import com.stream.writer.repository.StreamRepository;
import com.stream.writer.repository.TrailRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

// ─────────────────────────────────────────
// TrailCommandHandler의 제약 이름 매칭을 "진짜 PostgreSQL이 만든 에러 문자열"로 검증한다.
//
// H2로 하는 TrailCommandHandlerConstraintTest와 목적이 다르다.
// H2는 제약 이름을 대문자로, 그것도 UNIQUE는 인덱스 이름 형태로 출력하기 때문에
// 운영에서 실제로 마주치는 문자열을 재현하지 못한다.
// 지난 버그(없는 stream_id → 500)가 유닛 테스트를 전부 통과하고 Docker 실기동에서야
// 드러난 이유가 정확히 이 간극이었다.
//
// 스키마를 손으로 옮겨 적지 않고 운영 infra/scripts/init-db.sql을 그대로 적용한다.
// 그래서 이 테스트는 스키마 드리프트도 함께 막아준다.
//
// Docker가 없으면 실패가 아니라 skip된다(@EnabledIfDockerAvailable).
// ─────────────────────────────────────────
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfDockerAvailable
@DisplayName("Writer - TrailCommandHandler 실제 PostgreSQL 제약 위반 테스트")
class TrailCommandHandlerPostgresTest {

    private static final Path INIT_DB_SQL =
            Path.of("..", "..", "infra", "scripts", "init-db.sql");

    // static: 클래스당 컨테이너 1개만 띄운다.
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:15-3.3-alpine")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("stream_db")
                    .withUsername("test")
                    .withPassword("test");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // 운영 스키마를 그대로 쓰므로 Spring의 스크립트 초기화(schema.sql/data.sql)를 끈다.
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Autowired
    private TrailRepository trailRepository;

    @Autowired
    private StreamRepository streamRepository;

    @Autowired
    private DataSource dataSource;

    private static final long TEST_STREAM_ID = 1L;

    // 운영 init-db.sql에는 $$ ... $$ 로 감싼 plpgsql 함수 본문이 있다.
    // Testcontainers의 withInitScript는 세미콜론 기준으로 문장을 쪼개서 이 블록을 깨뜨린다.
    // 파일 전체를 하나의 Statement로 넘기면 PostgreSQL이 알아서 처리한다.
    @BeforeAll
    static void applyProductionSchema() throws Exception {
        String sql = Files.readString(INIT_DB_SQL, StandardCharsets.UTF_8);
        try (Connection c = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute(sql);
            s.execute("INSERT INTO streams (id, name, location) VALUES "
                    + "(1, 'pg-test-stream', ST_GeomFromText('LINESTRING(126.97 37.55, 126.98 37.56)', 4326))");
        }
    }

    private TrailCommandHandler handlerThatSkipsExistenceCheck() {
        StreamRepository alwaysExists = mock(StreamRepository.class);
        given(alwaysExists.existsById(anyLong())).willReturn(true);
        return new TrailCommandHandler(trailRepository, alwaysExists);
    }

    @Test
    @DisplayName("운영 스키마가 적용되고 제약 이름이 기대한 그대로다")
    void productionSchemaHasExpectedConstraintNames() throws Exception {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             var rs = s.executeQuery(
                     "SELECT conname FROM pg_constraint WHERE conrelid = 'trails'::regclass ORDER BY conname")) {
            var names = new java.util.ArrayList<String>();
            while (rs.next()) {
                names.add(rs.getString(1));
            }
            assertThat(names).contains(
                    "trails_stream_id_camera_number_key",
                    "trails_stream_id_fkey",
                    "trails_status_check");
        }
    }

    @Test
    @DisplayName("실제 PostgreSQL FK 위반 메시지에 trails_stream_id_fkey가 들어있고 400 경로로 변환된다")
    void realPostgresForeignKeyViolationBecomesIllegalArgumentException() {
        TrailCommandHandler handler = handlerThatSkipsExistenceCheck();
        CreateTrailCommand command =
                new CreateTrailCommand(999999L, "CAM-PG-FK", "POINT(126.97 37.55)", "N", "active");

        assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999999")
                .hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("PostgreSQL이 실제로 내보내는 FK 에러 문자열이 소문자 제약 이름을 담고 있다")
    void postgresErrorMessageActuallyContainsLowercaseConstraintName() {
        Trail orphan = new Trail();
        orphan.setStreamId(999999L);
        orphan.setCameraNumber("CAM-PG-RAW");
        orphan.setLocation(new org.locationtech.jts.geom.GeometryFactory(
                new org.locationtech.jts.geom.PrecisionModel(), Trail.SRID)
                .createPoint(new org.locationtech.jts.geom.Coordinate(126.97, 37.55)));
        orphan.setStatus("active");

        assertThatThrownBy(() -> trailRepository.save(orphan))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> {
                    String message = ((DataIntegrityViolationException) e)
                            .getMostSpecificCause().getMessage();
                    // 핸들러가 이 문자열을 매칭한다. 이게 이 테스트의 존재 이유다.
                    assertThat(message).contains("trails_stream_id_fkey");
                });
    }

    @Test
    @DisplayName("실제 PostgreSQL UNIQUE 위반이 409 경로로 변환되고 메시지에 소문자 제약 이름이 들어있다")
    void realPostgresUniqueViolationBecomesDuplicateTrailException() throws Exception {
        TrailCommandHandler handler = new TrailCommandHandler(trailRepository, streamRepository);
        handler.handle(new CreateTrailCommand(
                TEST_STREAM_ID, "CAM-PG-DUP", "POINT(126.97 37.55)", "N", "active"));

        CreateTrailCommand duplicate = new CreateTrailCommand(
                TEST_STREAM_ID, "CAM-PG-DUP", "POINT(126.97 37.55)", "N", "active");

        assertThatThrownBy(() -> handler.handle(duplicate))
                .isInstanceOf(DuplicateTrailException.class)
                .hasMessageContaining("CAM-PG-DUP");
    }

    @Test
    @DisplayName("PostgreSQL이 실제로 내보내는 UNIQUE 에러 문자열이 소문자 제약 이름을 담고 있다")
    void postgresUniqueErrorMessageActuallyContainsLowercaseConstraintName() throws Exception {
        TrailCommandHandler handler = new TrailCommandHandler(trailRepository, streamRepository);
        handler.handle(new CreateTrailCommand(
                TEST_STREAM_ID, "CAM-PG-RAW-DUP", "POINT(126.97 37.55)", "N", "active"));

        Trail duplicate = new Trail();
        duplicate.setStreamId(TEST_STREAM_ID);
        duplicate.setCameraNumber("CAM-PG-RAW-DUP");
        duplicate.setLocation(new org.locationtech.jts.geom.GeometryFactory(
                new org.locationtech.jts.geom.PrecisionModel(), Trail.SRID)
                .createPoint(new org.locationtech.jts.geom.Coordinate(126.97, 37.55)));
        duplicate.setStatus("active");

        assertThatThrownBy(() -> trailRepository.save(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> {
                    String message = ((DataIntegrityViolationException) e)
                            .getMostSpecificCause().getMessage();
                    assertThat(message).contains("trails_stream_id_camera_number_key");
                });
    }
}
```

- [ ] **Step 4: 테스트를 돌려서 통과하는지 확인한다**

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=TrailCommandHandlerPostgresTest"
```
Expected: PASS, `Tests run: 5, Failures: 0, Errors: 0`.

**실패 시 확인 순서:**
1. `init-db.sql`을 못 찾는다는 에러 → `INIT_DB_SQL` 경로는 `services/writer`를 작업 디렉터리로 가정한 상대 경로다. surefire의 작업 디렉터리가 모듈 루트가 맞는지 확인한다. 아니면 절대 경로 계산으로 바꾼다.
2. `@DataJpaTest`가 트랜잭션을 롤백하므로 각 테스트가 남긴 trail은 사라진다. 하지만 `@BeforeAll`에서 넣은 stream(id=1)은 별도 커넥션으로 커밋했으므로 남는다.
3. `each test rolls back` 때문에 `CAM-PG-DUP` 같은 이름이 테스트 간 충돌하지 않는다. 그래도 테스트마다 다른 camera_number를 쓰도록 이미 분리해뒀다.

- [ ] **Step 5: 커밋**

```bash
git add services/writer/pom.xml services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerPostgresTest.java
git commit -m "test(writer): Testcontainers postgis로 실제 PostgreSQL 제약 위반 문자열 검증"
```

---

## Task 5: 제약이 진짜로 검증되고 있는지 확인하고 결과를 기록한다

**Files:**
- Modify: `docs/superpowers/plans/2026-08-22-test-schema-fk-constraints.md` (이 문서의 `## 최종 검증` 섹션)

**Interfaces:**
- Consumes: Task 1~4의 결과 전부.

**배경:** 새 테스트가 정말로 제약을 검증하는지, 아니면 다른 이유로 우연히 통과하는지 확인해야 한다. 제약을 일부러 제거했을 때 테스트가 실패하지 않는다면 그 테스트는 아무것도 지키지 않는 것이다.

- [ ] **Step 1: 전체 테스트를 돌려 시간을 측정한다**

Run (각 모듈에서 순서대로):
```
cd packages/shared  && .\mvnw.cmd -B -o install -DskipTests
cd ..\..\services\reader && .\mvnw.cmd -B -o test
cd ..\writer && .\mvnw.cmd -B -o test "-Dtest=!WriterApplicationTests"
cd ..\..\apps\backend && .\mvnw.cmd -B -o test
```

각 명령의 소요 시간을 기록한다. 기준선은 shared 4.0s / reader 17.6s / writer 16.1s / backend 11.4s다.

Expected:
- shared: `Tests run: 30, Failures: 0, Errors: 0`
- reader: `Tests run: 32, Failures: 0, Errors: 1` (기존 RED `ReaderApplicationTests`)
- writer: `Tests run: 57, Failures: 0, Errors: 0` (기존 46 + Task 3의 6 + Task 4의 5)
- backend: `Tests run: 36, Failures: 0, Errors: 5` (기존 RED `CaptureControllerTest`)

- [ ] **Step 2: FK 제약을 일부러 없애고 테스트가 실패하는지 확인한다**

`services/writer/src/test/resources/schema.sql`의 아래 줄을 임시로 주석 처리한다:

```sql
--    CONSTRAINT trails_stream_id_fkey FOREIGN KEY (stream_id) REFERENCES streams(id) ON DELETE CASCADE,
```

(바로 위 줄 끝의 쉼표 처리에 주의한다. `created_at ... DEFAULT CURRENT_TIMESTAMP,` 뒤에 UNIQUE 제약이 이어지므로 문법은 그대로 유효하다.)

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=TrailCommandHandlerConstraintTest"
```
Expected: **FAIL.** 최소한 `realForeignKeyViolationBecomesIllegalArgumentException`과 `schemaActuallyEnforcesForeignKey`가 실패해야 한다. 실패하지 않는다면 테스트가 제약을 검증하고 있지 않다는 뜻이므로 테스트를 고쳐야 한다.

- [ ] **Step 3: 주석을 되돌리고 다시 통과하는지 확인한다**

Run (저장소 루트에서):
```bash
git checkout services/writer/src/test/resources/schema.sql
```

Run (`services/writer`에서):
```
.\mvnw.cmd -B -o test "-Dtest=TrailCommandHandlerConstraintTest"
```
Expected: PASS, `Tests run: 6`.

- [ ] **Step 4: Docker 없이도 빌드가 깨지지 않는지 확인한다**

Testcontainers 테스트가 Docker 부재 시 실패가 아니라 skip인지 확인한다. Docker를 실제로 끄지 말고, `@EnabledIfDockerAvailable`이 붙어 있는지 소스에서 확인하는 것으로 갈음한다:

Run (저장소 루트에서):
```bash
grep -n "EnabledIfDockerAvailable" services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerPostgresTest.java
```
Expected: import 줄과 애노테이션 줄, 총 2줄이 나온다.

- [ ] **Step 5: 결과를 이 문서에 기록한다**

이 문서 맨 아래 `## 최종 검증` 섹션에 아래를 채운다:
- Step 1의 네 모듈 실제 테스트 개수와 소요 시간, 그리고 기준선 대비 증가분
- Step 2에서 FK를 제거했을 때 실제로 실패한 테스트 이름과 실패 메시지
- Task 4 테스트가 실제로 확인한 PostgreSQL 에러 문자열 원문

- [ ] **Step 6: 커밋**

```bash
git add docs/superpowers/plans/2026-08-22-test-schema-fk-constraints.md
git commit -m "docs: 테스트 스키마 제약 도입의 검증 결과 기록"
```

---

## 후속 작업 (이 계획엔 포함 안 함, 참고용)

- **`apps/backend` 에러 본문 중첩 정리**: `{"error":"Invalid trail data","message":"Writer rejected the trail data: {\"error\":\"...\"}"}` 처럼 JSON이 문자열 안에 이스케이프돼서 들어간다. `TrailServiceImpl.create`에서 writer 응답의 `error` 필드만 뽑아 쓰면 된다.
- **`GlobalExceptionHandler`의 "Invalid stream geometry" 문구가 부정확**: geometry와 무관한 오류에도 나가고 클라이언트에 그대로 노출된다.
- **`InvalidTrailGeometryException` → `InvalidTrailDataException` 리네임**
- **Capture 게이트웨이 연동**: `apps/backend`의 `CaptureController`가 아직 RED 스텁이다(`Not implemented`, 테스트 5개 실패 중). 이제 `captures`에도 FK가 있는 테스트 스키마가 생겼으므로 제대로 구현하면 그 위에서 검증된다.
- **`CaptureCommandHandler`의 필수 필드 검증 누락**: Capture도 `trail_id`/`stream_id` FK가 있는데 검증이 없다. 이번에 `captures` FK가 테스트 스키마에 들어갔으므로 이제 이 문제를 재현하는 테스트를 쓸 수 있다.
- **Redis 캐시 TTL 없음 + 캐시 히트/미스 시 `data` 필드 타입이 String↔Object로 바뀜**
- **`ON DELETE CASCADE` 경쟁**: INSERT가 먼저 커밋되면 cascade가 방금 만든 trail을 지워서 클라이언트는 201을 받는다. 핸들러 수준에서 해결 불가 — 스키마/트랜잭션 설계 차원 검토 필요.
- **CI 구축**: 이 저장소엔 CI가 없어서 모든 테스트가 사람이 기억해서 돌려야만 실행된다. CI가 생기면 Testcontainers 테스트를 failsafe `verify` 단계로 옮기는 것도 검토할 만하다.
- **테스트 스키마 2벌 중복**: writer/reader의 `schema.sql`·`data.sql`이 각각 복사본이다. 드리프트가 생기면 `packages/shared` test-jar로 공유하는 방안을 검토한다.

## 최종 검증

### Step 1: 전체 테스트 실행 결과 (2026-08-23, commit db1ffcc)

| module | command | 실측 결과 | 소요 시간 | 기준선 | 증감 |
|---|---|---|---|---|---|
| shared | `.\mvnw.cmd -B -o test` | `Tests run: 30, Failures: 0, Errors: 0` | 3.37s | 4.0s | -0.63s (오차 범위) |
| reader | `.\mvnw.cmd -B -o test` | `Tests run: 32, Failures: 0, Errors: 1` | 15.24s | 17.6s | -2.36s (오차 범위) |
| writer | `.\mvnw.cmd -B -o test "-Dtest=!WriterApplicationTests"` | `Tests run: 57, Failures: 0, Errors: 0` | 17.31s | 16.1s | +1.21s / **테스트 +11개**(46→57: Task 3 +6, Task 4 +5) |
| backend | `.\mvnw.cmd -B -o test` | `Tests run: 36, Failures: 0, Errors: 5` | 9.94s | 11.4s | -1.46s (오차 범위) |

네 모듈 모두 기대한 개수와 정확히 일치했다. reader의 에러 1건(`ReaderApplicationTests.contextLoads`, 실제 PostgreSQL 없이는 ApplicationContext 로딩 실패)과 backend의 에러 5건(`CaptureControllerTest`, `UnsupportedOperationException: Not implemented` 스텁)은 이 브랜치 이전부터 있던 기존 RED이고 회귀가 아니다. 그 외의 실패는 없었다.

writer 모듈의 `TrailCommandHandlerPostgresTest`(Testcontainers postgis)는 `Skipped: 0`으로 5개 테스트가 실제로 실행됐다 — Docker API 버전 고정이 정상 작동하고 있음을 확인했다.

shared/reader/backend는 이번 계획에서 테스트를 추가하지 않았으므로 개수 변화가 없다. 소요 시간 증감은 모두 컨테이너/CPU 부하에 따른 측정 노이즈 범위이며, writer의 +1.21초는 새로 추가된 11개 테스트(그중 5개는 Testcontainers로 실제 Postgres 컨테이너를 기동) 대비 오히려 작은 증가다.

### Step 2~3: 뮤테이션 테스트 — FK 제약을 일부러 제거했을 때 테스트가 실패하는지 확인

`services/writer/src/test/resources/schema.sql`의 FK 제약 줄을 주석 처리:
```sql
--    CONSTRAINT trails_stream_id_fkey FOREIGN KEY (stream_id) REFERENCES streams(id) ON DELETE CASCADE,
```

`.\mvnw.cmd -B -o test "-Dtest=TrailCommandHandlerConstraintTest"` 실행 결과, 예상대로 **FAIL**했다:

```
Tests run: 6, Failures: 3, Errors: 0, Skipped: 0, Time elapsed: 6.053 s <<< FAILURE! -- in com.stream.writer.command.TrailCommandHandlerConstraintTest

com.stream.writer.command.TrailCommandHandlerConstraintTest.realForeignKeyViolationBecomesIllegalArgumentException -- Time elapsed: 0.420 s <<< FAILURE!
java.lang.AssertionError:
Expecting code to raise a throwable.
	at com.stream.writer.command.TrailCommandHandlerConstraintTest.realForeignKeyViolationBecomesIllegalArgumentException(TrailCommandHandlerConstraintTest.java:80)

com.stream.writer.command.TrailCommandHandlerConstraintTest.deletingStreamCascadesToTrails -- Time elapsed: 0.104 s <<< FAILURE!
java.lang.AssertionError:
Expecting an empty Optional but was containing value: com.stream.shared.entity.Trail@47e935be
	at com.stream.writer.command.TrailCommandHandlerConstraintTest.deletingStreamCascadesToTrails(TrailCommandHandlerConstraintTest.java:191)

com.stream.writer.command.TrailCommandHandlerConstraintTest.schemaActuallyEnforcesForeignKey -- Time elapsed: 0.008 s <<< FAILURE!
java.lang.AssertionError:
Expecting code to raise a throwable.
	at com.stream.writer.command.TrailCommandHandlerConstraintTest.schemaActuallyEnforcesForeignKey(TrailCommandHandlerConstraintTest.java:111)
```

브리핑에서 요구한 두 테스트(`realForeignKeyViolationBecomesIllegalArgumentException`, `schemaActuallyEnforcesForeignKey`)가 정확히 실패했고, 추가로 `deletingStreamCascadesToTrails`도 함께 실패했다(ON DELETE CASCADE 자체가 FK에 딸린 옵션이므로 FK가 없으면 cascade도 없어져 당연한 결과). 세 테스트 모두 실제 FK 제약이 없으면 통과할 수 없다는 것을 확인했다 — 이 테스트들은 우연히 통과하는 것이 아니라 스키마의 FK 제약에 실제로 의존한다.

`git checkout services/writer/src/test/resources/schema.sql`로 되돌린 뒤 워킹 트리가 깨끗함을 확인했고, 같은 테스트를 재실행해 `Tests run: 6, Failures: 0, Errors: 0`으로 복구됨을 확인했다.

### Step 4: Docker 없이도 빌드가 깨지지 않는지 확인

```
$ grep -n "EnabledIfDockerAvailable" services/writer/src/test/java/com/stream/writer/command/TrailCommandHandlerPostgresTest.java
17:import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
45:// Docker가 없으면 실패가 아니라 skip된다(@EnabledIfDockerAvailable).
49:@EnabledIfDockerAvailable
```

import 줄(17)과 애노테이션 줄(49) 2줄에 더해, 그 사이 설명 주석(45)에서도 이름이 언급되어 총 3줄이 매치됐다(브리핑이 예상한 2줄 + 주석 1줄). 애노테이션이 클래스에 정확히 붙어 있음을 확인했으므로 Docker 미가용 시 실패가 아니라 skip으로 처리된다.

### Step 5: Task 4가 실제로 관찰한 PostgreSQL 에러 문자열 (원문)

writer 전체 테스트 실행 중 Hibernate `SqlExceptionHelper`가 실제 PostgreSQL(Testcontainers postgis 컨테이너)로부터 받아 로그로 남긴 에러 문자열:

FK 위반:
```
ERROR: insert or update on table "trails" violates foreign key constraint "trails_stream_id_fkey"
```

UNIQUE 위반:
```
ERROR: duplicate key value violates unique constraint "trails_stream_id_camera_number_key"
```

두 문자열 모두 소문자 제약 이름(`trails_stream_id_fkey`, `trails_stream_id_camera_number_key`)을 그대로 담고 있고, `TrailCommandHandlerPostgresTest`의 `postgresErrorMessageActuallyContainsLowercaseConstraintName`/`postgresUniqueErrorMessageActuallyContainsLowercaseConstraintName` 테스트가 `assertThat(message).contains(...)`로 이를 직접 단언한다. `TrailCommandHandler`가 매칭하는 제약 이름 문자열이 실제 운영 Postgres가 내보내는 에러 메시지와 정확히 일치함을 실측으로 확인했다.

### 종합

이 문단은 처음에 "Task 1~4에서 추가한 테스트는 전부 실제 스키마 제약에 의존하며, 그중 어느 것도 제약 없이 우연히 통과하지 않는다"고 적었으나, 이는 writer 모듈에만 해당하는 사실이었고 reader 모듈까지 뭉뚱그린 과장이었다. 리뷰에서 실측한 결과를 반영해 아래처럼 두 모듈을 구분한다.

**writer**는 Step 2~3의 뮤테이션 테스트로 직접 확인했다 — `schema.sql`의 FK 제약 줄을 주석 처리하면 `TrailCommandHandlerConstraintTest`의 3개 테스트가 실제로 FAIL했다. writer가 추가한 테스트는 우연히 통과하는 것이 아니라 스키마의 실제 제약에 의존한다. Testcontainers 기반 테스트(Task 4)는 Docker 유무에 따라 정상적으로 실행/스킵되고, 실행됐을 때 관찰하는 에러 문자열은 PostgreSQL 서버가 직접 생성한 것이다.

**reader**는 사정이 다르다. Task 2에서 reader에 손댄 테스트는 모두 happy-path이고 `road_status` 값 하나(`'보통'` → `'주의'`)를 고쳤을 뿐, CHECK/FK/UNIQUE 위반을 일부러 일으켜 보는 테스트는 하나도 추가하지 않았다. 실제로 reader의 `schema.sql`에서 제약 6개(FK 3, UNIQUE 1, CHECK 2)를 전부 지워도 reader 테스트 스위트는 그대로 GREEN이다. 즉 reader 모듈 자체에는 "제약 없이 통과하지 않는다"고 말할 수 있는 테스트가 없다. 대신 `TestSchemaSyncTest`(writer/reader 양쪽에 하나씩 존재)가 reader의 `schema.sql`/`data.sql`이 writer의 사본과 바이트 단위로 동일한지 검증해서, reader 스키마가 writer 몰래 드리프트하는 것을 막는다. 이 테스트는 reader의 제약이 "제약으로서 올바르게 동작한다"는 것을 증명하지는 않는다 — writer 쪽에서 뮤테이션 테스트로 검증된 스키마를 reader가 그대로 베끼고 있다는 사실만 보증한다.

정확히 어디까지 검증됐는지는 구분해둘 필요가 있다. 검증한 것은 **운영과 같은 이미지(`postgis/postgis:15-3.3-alpine`)와 같은 스키마 파일(`infra/scripts/init-db.sql`)을 쓰는 컨테이너**가 그 문자열을 내보낸다는 것이지, 운영 인스턴스에 붙어 대조한 것은 아니다. 이미지 태그와 스키마가 동일하므로 실질적으로 같다고 볼 근거는 충분하지만, 그 둘이 갈라지면 이 등식도 깨진다.
