# youtube-service 캡처 구현 설계

**작성일:** 2026-08-27
**브랜치:** `feature/youtube-capture`
**관련 결정:** 프로젝트 메모리 "캡처 파이프라인 트리거 설계" (입구는 backend, 이미지는 MinIO, `image_path`는 키)

## 배경

`apps/youtube-service`의 `POST /download`와 `GET /status/:jobId`가 스텁이다(`throw new Error('Not implemented')`). 이 저장소에서 미구현으로 인한 RED 11개가 전부 여기에 있고, **파이프라인의 입구가 막혀 있어 실제 이미지가 하나도 생기지 않는다.**

이미지 저장 위치가 정해지지 않아 `captures.image_path`에 들어가는 값은 지금까지 전부 하드코딩된 가짜(`/images/capture_001.jpg`)였다. ml-service가 실제 모델을 붙일 수 없는 것도 분석할 파일이 없기 때문이다.

## 목표

`POST /download` 한 번에 프레임 한 장을 떠서 MinIO에 올리고 `image.downloaded` 이벤트를 발행한다. 그 이벤트가 기존 파이프라인(ml-service → writer → DB → reader → backend)을 타고 흘러 `GET /api/captures`로 조회되는 것까지 관통시킨다.

## 범위

**포함:** youtube-service의 캡처 구현, MinIO 도입(compose + 버킷), `image_path` 계약 확정, 관련 테스트와 문서 갱신.

**제외:**
- **스케줄러** — "15분마다"를 세는 주체. backend 트리거 작업에서 다룬다
- **실제 ML 모델** — ml-service는 계속 고정값을 낸다
- **DLQ** — 아래 "후속 작업" 참고
- **이미지 보관 정책** — 무한히 쌓이는 문제. 아래 "후속 작업" 참고

---

## 결정 사항

브레인스토밍에서 하나씩 확정한 것들이다. 근거와 기각한 대안을 함께 남긴다.

### 1. 대상은 라이브 스트림, 요청당 프레임 1장

하천 CCTV를 15분 간격으로 표본 추출한다. VOD가 아니다.

이 사실이 기존 테스트 계약과 충돌한다. 테스트는 2026-03-09에 **VOD를 전제로** 작성되어 `progress` 0~100과 `completed` 상태를 요구하는데, 라이브는 끝나지 않는다. 아래 2번이 이 충돌을 해소한다.

### 2. 작업은 단발(one-shot), 스케줄은 바깥이 소유

`POST /download` = **"지금 한 장 떠라"**. 몇 초 만에 끝난다.

라이브에서 한 장을 뽑는 것은 짧은 작업이다. 스트림 URL을 얻어 프레임 하나를 뜨면 끝이므로 장시간 실행되는 다운로드가 아니다.

**채택 근거 셋:**
- 작업이 실제로 완료되므로 `queued → processing → completed`, `progress` 0→100, `downloaded_count: 1`이 자연스럽다. **기존 테스트 계약이 그대로 맞는다**
- youtube-service가 상태 없는 실행기가 되어 재시작 복구, 중복 등록, 좀비 타이머 문제가 사라진다
- 스케줄 소유는 오케스트레이션이고, "파이프라인 입구는 backend가 관리한다"는 기존 결정과 일관된다

**기각: youtube-service가 스케줄을 소유하는 안.** `completed`가 영영 안 나와 테스트 계약을 고쳐야 하고, 중지 API와 재시작 복구가 필요해지며, 프로세스가 죽으면 캡처가 조용히 멈춘다. 유일한 장점인 "한 번 등록하면 끝"은 backend에 등록 API를 두면 똑같이 얻는다.

### 3. `failed` 상태를 추가한다

테스트가 정한 유효 status는 `queued | processing | completed` 셋뿐이라 실패를 표현할 자리가 없다. `failed`를 추가하고 테스트의 `validStatuses` 배열을 고친다.

**근거:** 실패를 `completed` + `downloaded_count: 0`으로 표현하면 "성공했는데 아직 0장"과 구분되지 않고 원인도 알 수 없다. 이 저장소는 "테스트가 GREEN인데 실제로는 틀렸던" 경우를 이미 여러 번 겪었다(H2/PostGIS 불일치, 모듈 경계 미검증). **테스트 계약을 지키려고 실패를 성공이라 부르는 것은 같은 종류의 함정이다.**

테스트 수정 비용은 한 줄이고, 캡처 실행부 주입은 어차피 필요하다(아래 6번).

기존 backend 스텁을 "고정 계약"으로 취급해온 전례가 있지만 그것은 **구현이 명세를 못 따라간 경우**였다. 지금은 반대로 **명세가 도메인을 잘못 가정한 경우**다.

### 4. `image_path`는 객체 키, URL은 조회 시점에 조립

```
image_path = "captures/1/1/2026-08-27T10-15-00Z.jpg"
```

DB에는 키만 저장한다. 브라우저에 줄 URL은 베이스 주소를 붙여 만든다.

**근거:** 프론트엔드가 정적 export + CDN 배포로 확정됐다. 완성된 URL을 저장하면 `minio:9000`(컨테이너 내부 호스트명, 브라우저에서 접근 불가)이나 `localhost:9000`이 DB에 박힌다. 환경마다 달라지는 값이라 영구 저장에 부적합하다. 키만 저장하면 백킹 스토어를 S3로 바꿔도 DB가 안 깨진다.

**키 형식:** `captures/{streamId}/{trailId}/{ISO8601}.jpg`
트레일별로 나뉘어 사람이 봐도 이해되고, 시각이 파일명이라 정렬이 자연스러우며, 같은 트레일에 같은 초로 두 번 찍힐 일이 없어 충돌하지 않는다.

**기각: presigned URL 저장.** 유효기간이 있어 저장하면 시간이 지나 죽는다.

### 5. 캡처 소스는 어댑터로 분리, YouTube 어댑터는 만들지 않는다

**YouTube에서 프레임을 추출하는 것은 YouTube 이용약관 위반이다.** ToS는 YouTube가 제공하는 인터페이스 외의 방법으로 콘텐츠에 접근·복제·다운로드하는 것을 금지하며 `yt-dlp` 사용은 여기 해당한다. YouTube Data API는 메타데이터만 주고 프레임이나 스트림은 주지 않으므로 합법적 대체 경로가 없다.

따라서 **`yt-dlp`를 도입하지 않고 YouTube 어댑터를 구현하지 않는다.** 없는 코드는 약관을 위반할 수 없다.

```
src/capture.js
  ├─ captureFromTestPattern()       ← 자동화 테스트 / CI
  ├─ captureFromFile(path, offset)  ← 직접 촬영한 영상. 개발·시연
  └─ captureFromHls(url)            ← 공공 CCTV 스트림 확보 시
```

`CAPTURE_SOURCE` 환경변수로 선택한다. **셋 다 ffmpeg 하나로 되므로 새 시스템 의존성은 ffmpeg뿐이다.**

`testsrc`는 ffmpeg가 즉석에서 만드는 테스트 패턴이다. 네트워크도 저작권도 개입하지 않으면서 코드 경로는 실제와 완전히 같다(ffmpeg → JPEG 바이트 → MinIO → Kafka). 결정적이고 오프라인에서 돌아 CI에 적합하다.

직접 촬영한 영상은 **git에 커밋하지 않는다.** MinIO의 `sources/` 경로에 두거나 gitignore된 로컬 디렉터리에 둔다.

**부수 효과:** yt-dlp와 python을 넣지 않으므로 컨테이너 이미지가 200MB대가 아니라 130MB대에 머문다.

**요청 필드 이름:** 테스트가 `youtube_url`을 요구하므로 계약상 유지하되 실제로는 "소스 URL"로 해석한다. `source_url`로 바꾸는 것은 후속 항목.

### 6. `jobStore`는 Redis + TTL

현재는 프로세스 메모리의 `Map`이며 **넣기만 하고 지우는 코드가 없다.** 15분마다 1건 × 트레일 N개가 영원히 쌓인다.

`SET job:{jobId} {...} EX 3600`으로 두면 TTL이 알아서 지운다. compose가 이미 youtube-service에 `REDIS_HOST`/`REDIS_PASSWORD`를 주입하고 있어 **새 인프라가 아니다** — 연결선이 이미 그어져 있는데 쓰지 않던 상태였다.

**근거:** 메모리 `Map`은 "새 의존성 0"처럼 보이지만 만료 정리를 직접 구현해야 한다. Redis는 `EX` 인자 하나다. **코드를 덜 쓰는 쪽이 Redis다.** 인스턴스를 늘려도 POST와 GET이 다른 인스턴스에 가도 된다.

**기각: 지금은 메모리, 나중에 Redis.** 전환 시 `Map.get()`(동기)이 `await`(비동기)로 바뀌어 호출부와 테스트를 전부 고쳐야 한다.

---

## 아키텍처

```
[스케줄러 — 범위 밖]
      │ 15분마다
      ▼
POST /download {stream_id, trail_id, youtube_url}
      │
      ├─ 즉시 202 {jobId, status:"queued", ...}
      │
      └─ 비동기 캡처
             ├─ 1. ffmpeg          → JPEG 바이트 (디스크 경유 안 함)
             ├─ 2. MinIO PUT       → captures/{streamId}/{trailId}/{ISO}.jpg
             ├─ 3. Kafka 발행      → image.downloaded
             └─ Redis 상태 갱신    → 각 단계마다 processing / completed / failed
                        │
                        ▼
                  ml-service → writer → DB → reader → backend
```

**디스크를 거치지 않는다.** ffmpeg 출력을 파이프로 받아 그대로 MinIO에 올린다. 임시 파일 정리도 볼륨도 필요 없다.

### 모듈 분리

| 파일 | 책임 | 상태 |
|---|---|---|
| `src/app.js` | HTTP 계약만 — 검증, 상태 코드, 응답 형태 | 수정 |
| `src/capture.js` | ffmpeg 실행. **주입 지점** | 신규 |
| `src/storage.js` | MinIO 업로드, 키 생성 | 신규 |
| `src/jobs.js` | Redis 기반 작업 상태 CRUD | 신규 |
| `src/kafka.js` | 기존 그대로 | 변경 없음 |
| `src/index.js` | 엔트리포인트 | 의존성 조립 추가 |

`app.js`는 `capture.js`를 직접 `require`하지 않고 **주입받는다.** `createApp(deps)` 팩토리로 감싼다. 그래야 테스트가 진짜 ffmpeg를 띄우지 않는다.

---

## API 계약 (확정본)

### `POST /download`

```json
요청:  { "stream_id": 1, "trail_id": 1, "youtube_url": "..." }
202:   { "jobId": "uuid", "status": "queued",
         "stream_id": 1, "trail_id": 1, "youtube_url": "..." }
400:   { "error": "stream_id is required" }
```

세 필드 전부 필수. **`interval_sec`은 받지 않는다** — 설계 2번에서 "몇 분마다"가 스케줄러의 관심사가 되어 이 서비스와 무관해졌다. VOD 전제로 쓰인 명세의 잔재다. 보내도 무시하고 응답에도 없다.

### `GET /status/:jobId`

```json
200:  { "jobId": "...", "status": "completed", "progress": 100, "downloaded_count": 1 }
200:  { "jobId": "...", "status": "failed", "progress": 0, "downloaded_count": 0,
        "error": "capture failed: ..." }
404:  { "error": "Job not found", "jobId": "..." }
```

`progress`는 사실상 0 또는 100이다. 한 장짜리 작업이라 중간이 없다. 테스트 계약이 요구하는 필드라 유지하며, 여러 장을 뜨게 되면 그때 의미가 생긴다. `error`는 `failed`일 때만 존재한다.

### Kafka `image.downloaded` — 기존 형태 유지

```json
{ "imageId": "<jobId와 동일>", "streamId": 1, "trailId": 1,
  "imagePath": "captures/1/1/2026-08-27T10-15-00Z.jpg",
  "timestamp": "2026-08-27T10:15:00.000Z" }
```

**필드 이름을 하나도 바꾸지 않는다.** ml-service와 writer가 이미 이 형태를 소비하므로 `imagePath`의 **의미만** 가짜 경로에서 진짜 MinIO 키로 바뀐다. 하류 코드 수정이 0이다.

`imageId`에 `jobId`를 그대로 쓴다. 작업 하나가 이미지 하나를 만들므로 별도 식별자를 만들 이유가 없고, 캡처 행에서 어느 작업이 만들었는지 역추적할 수 있다.

---

## 에러 처리

| 단계 | 실패 원인 | 결과 |
|---|---|---|
| 요청 검증 | 필수 필드 누락 | 400 즉시. 작업을 만들지 않음 |
| 1. 프레임 추출 | ffmpeg 실패, 소스 없음, 타임아웃 | `failed` / `capture failed: <stderr 마지막 줄>` |
| 2. MinIO 업로드 | 버킷 없음, 인증 실패 | `failed` / `upload failed: <메시지>` |
| 3. Kafka 발행 | 브로커 다운 | `failed` / `publish failed: <메시지>` |

### 3번의 고아 객체

이미지는 올라갔는데 발행만 실패하면 **파일은 있고 DB에는 없는 객체**가 남는다. **되돌리지 않고 그대로 둔다.**

삭제도 실패할 수 있어 같은 문제가 한 겹 뒤로 밀릴 뿐이고, 고아 객체는 나중에 "DB에 없는 키 정리" 배치로 한 번에 치울 수 있다. 그런 배치는 어차피 보관 정책과 함께 필요해진다. 지금 롤백 코드를 쓰는 것은 이른 최적화다.

### 발행 실패에 DLQ를 쓰지 않는 이유

**DLQ는 컨슈머 쪽 도구다.** 발행 실패는 브로커에 못 닿았다는 뜻이고 DLQ도 같은 브로커의 토픽이므로 거기에도 못 쓴다.

`kafkajs` 프로듀서는 기본 내부 재시도(지수 백오프)를 하므로 일시적 단절은 이미 처리된다. 그래도 실패하면 **작업을 `failed`로 두고 스케줄러가 15분 뒤 다시 부른다.**

**근거는 도메인이다.** 캡처는 15분마다 반복되는 표본이다. 한 장 놓쳐도 다음 장이 온다. 결제나 주문처럼 한 건이 반드시 살아야 하는 데이터가 아니다. Outbox 패턴은 "이 메시지는 절대 잃으면 안 된다"일 때 쓰는 도구이며 DB 테이블과 별도 발행 프로세스가 붙는다. 15분 뒤 재시도로 해결되는 문제에는 과하다.

### 타임아웃

ffmpeg가 응답 없는 소스를 물면 영원히 기다린다. **기본 30초, 초과 시 kill 후 `failed`.** 없으면 좀비 프로세스가 쌓여 컨테이너가 서서히 죽는다.

### 비동기 예외가 프로세스를 내리지 않는다

캡처는 202 응답 이후 비동기로 돈다. 거기서 나는 예외가 프로세스를 죽이면 안 된다 — ml-service `consume()`에 `try/except`를 두른 것과 같은 이유다.

---

## MinIO 구성

compose에 컨테이너를 추가하고 `captures` 버킷을 만든다. 버킷 생성은 기동 시 코드에서 없으면 만드는 방식으로 처리한다(별도 초기화 컨테이너를 두지 않는다).

환경변수: `MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`, `MINIO_BUCKET`.

**클라이언트는 `@aws-sdk/client-s3`를 쓴다.** MinIO 전용 SDK가 아니라 S3 SDK를 쓰는 이유는 운영에서 실제 S3로 갈 때 엔드포인트 설정만 바꾸면 되기 때문이다.

**브라우저 접근:** 버킷을 공개 읽기로 연다. 하천 CCTV 이미지라 민감도가 낮고, 키만 저장하는 구조라 나중에 presigned URL로 바꿀 수 있다(키만 있으면 언제든 서명 가능).

---

## 테스트 전략

reader/writer에서 쓴 3층 구조를 따른다.

### 1층 · HTTP 계약 (기존 `app.test.js`)

캡처 실행부를 가짜로 주입한다. 진짜 ffmpeg를 띄우지 않는다.

```js
const app = createApp({ capture: fakeCapture, storage: fakeStorage, jobs: fakeJobs });
```

기존 테스트 수정은 두 곳뿐이다 — `interval_sec` 제거, `validStatuses`에 `'failed'` 추가.

### 2층 · 어댑터 단위 테스트 (신규)

| 대상 | 검증 |
|---|---|
| `capture.js` | **실제 ffmpeg 실행.** `testsrc`로 JPEG 바이트가 나오는지, 타임아웃이 실제로 걸리는지 |
| `storage.js` | 키 형식이 규약대로인지. MinIO 업로드는 mock |
| `jobs.js` | Redis CRUD + TTL |

**`capture.js`만 진짜 ffmpeg를 쓴다.** 여기가 "mock이 거짓말하는" 지점이라 실물이 필요하다 — H2를 PostGIS로 일반화했다가 틀렸던 것과 같은 종류의 위험이다.

### 3층 · 파이프라인 관통 (자동화 아님, 검증 태스크)

Docker로 전체를 띄우고 `POST /download` 한 번 → MinIO에 객체 생성 → Kafka 전파 → **`GET /api/captures`로 조회**까지 확인한다.

**이것이 구현 계획의 첫 태스크다.** 나머지를 다 만들고 이게 안 되면 헛일이므로 최소 구현으로 먼저 관통시킨다.

### 예상 수치

| 시점 | youtube-service | 전체 |
|---|---|---|
| 현재 | 22 중 11 GREEN | 251 중 238 GREEN / 13 RED |
| 완료 | 약 35 전부 GREEN | 약 264 중 262 GREEN / **2 RED** |

남는 RED 2개는 `ReaderApplicationTests`/`WriterApplicationTests`의 `contextLoads`로 테스트 설정 문제이며 기능과 무관하다.

---

## 문서 변경 범위

| 대상 | 변경 |
|---|---|
| `tests/app.test.js` | `interval_sec` 5곳 제거, `validStatuses`에 `'failed'` 추가 |
| `tests/kafka.test.js` | `interval_sec` 2곳 제거 |
| `docs/api-specs/*.postman_collection.json` | 요청 본문에서 `interval_sec` 제거, `failed` 응답 예시 추가 |
| `infra/docker/docker-compose.yml` | MinIO 서비스, youtube-service에 MinIO 환경변수 |
| `.env.example` | MinIO 관련 변수 |
| `README.md` | youtube-service 설명, 데이터 흐름에 MinIO |
| `docs/architecture/ARCHITECTURE.md` | 데이터 흐름에 MinIO |
| `docs/tdd-test-plan.md` | 완료 후 수치 갱신 |
| `docs/superpowers/plans/2026-08-27-ml-service-red-green.md` | 로드맵의 `interval_sec` 언급 정정 |

---

## 후속 작업

- **`CAPTURE_SOURCE=file`이 매번 같은 프레임을 뽑는다** — `createCapture`의 file 분기가 오프셋을 `CAPTURE_FILE_OFFSET_SEC` 환경변수 하나로 고정한다(`src/capture.js`). 100번 호출하면 키만 다른 동일 이미지 100장이 쌓인다. `captureFromFile(path, offsetSec)`은 오프셋을 인자로 받도록 만들어져 있는데 그것을 움직여줄 주체가 없다. **실기동 검증을 `testsrc`로 했기 때문에 드러나지 않았다** — 테스트 패턴은 어차피 매번 같은 그림이라 정상으로 보인다. 실제 영상을 넣는 시점에 반드시 처리해야 한다. 선택지 셋:
  1. **요청이 오프셋을 지정** — `POST /download`에 `offset_sec`을 받는다. 서비스는 계속 상태가 없고 "어디까지 떴는지"는 호출자가 안다. 변경이 가장 작다
  2. **한 번에 여러 장** — 영상 전체를 일정 간격으로 훑어 N장을 뽑는다. 시연에 편하다. 다만 `downloaded_count`가 1이 아니게 되고 `progress`가 비로소 의미를 갖는다 — **원래 테스트 계약이 전제했던 VOD 모양으로 돌아가는 셈이다**(결정 1·2번 참고). 계약이 바뀌므로 테스트와 명세도 함께 손봐야 한다
  3. 서비스가 마지막 오프셋을 Redis에 기억하고 호출마다 진행 — 상태 없는 실행기라는 설계(결정 2번)가 깨진다
- **스케줄러** — "15분마다"를 세는 주체. backend 트리거 작업(`POST /api/captures/jobs`)에서 만든다. **다만 라이브 스트림을 확보한 뒤로 미룬다.** 15분 주기는 결정 1번의 전제(라이브 CCTV 표본 추출)에서 나온 것인데, 현재 실제 데이터 출처는 녹화된 영상이다. 녹화 영상에는 "지금"이 없어 벽시계로 15분을 세는 것이 아무 의미가 없다 — 15분을 기다려도 같은 파일에서 뽑을 뿐이다. 녹화 영상에 필요한 것은 시간 스케줄러가 아니라 **영상 안의 위치를 진행시키는 것**이며, 그것은 위 항목이 다룬다
- **Kafka 컨슈머 DLQ** — ml-service `consume()`과 writer `ImageAnalyzedConsumer`가 처리 실패한 메시지를 로그만 남기고 버린다. 되살릴 방법이 없다. 지금은 발행자가 youtube-service 하나뿐이고 형식이 고정돼 잘못된 메시지가 들어올 경로가 사실상 없어 미룬다. 발행자가 늘거나 스키마가 진화하기 시작하면 필요해진다
- **이미지 보관 정책** — 15분마다 1장이면 트레일당 하루 96장이다. 무한히 쌓인다. 보관 기간과 정리 배치가 필요하며, 위의 고아 객체 정리와 같은 배치로 묶을 수 있다
- **객체 키가 버킷 이름을 한 번 더 갖는다** — 버킷이 `captures`인데 `buildKey`가 만드는 키도 `captures/`로 시작해서(`src/storage.js:15`) 실제 주소가 `<endpoint>/captures/captures/1/6/...jpg`가 된다. 동작에 문제는 없지만 URL을 조립할 때 틀리기 쉽다 — 2026-09-03 트리거 실기동 검증에서 실제로 한 번 틀렸다. 키에서 `captures/` 접두사를 빼는 것이 맞는데, 이미 저장된 객체가 있어 기존 행의 `image_path`도 함께 옮겨야 한다
- **`youtube_url` → `source_url`** — YouTube를 쓰지 않게 되어 이름이 사실과 다르다. 테스트·명세를 함께 고쳐야 하므로 분리. **backend 쪽은 2026-09-03 트리거 작업에서 이미 `source_url`로 지었다** — 남은 것은 youtube-service의 `POST /download` 본문과 `jobs`에 저장되는 필드 이름이며, 그때 backend의 `DownloadRequest` 매핑도 함께 걷어낸다
- **데이터 출처 교체** — 공공 CCTV의 HLS/RTSP 주소를 확보하면 `captureFromHls`를 켠다. ffmpeg가 HLS를 직접 읽으므로 새 의존성이 없다
- **개인정보** — 직접 촬영한 영상에 행인 얼굴이나 차량 번호판이 담기면 공개 버킷에 개인정보가 올라간다. 공개 시연 전에 확인 필요

## 위험

**ffmpeg 파이프 처리가 이 저장소에서 검증된 적이 없다.** 프로세스 출력을 버퍼로 받아 그대로 S3에 올리는 경로는 처음이다. 계획의 첫 태스크에서 `testsrc`로 관통 검증을 먼저 한다.

**MinIO는 이 저장소의 첫 오브젝트 스토리지다.** 버킷 생성 시점, 공개 읽기 설정, 컨테이너 간 엔드포인트(`minio:9000`)와 브라우저용 엔드포인트(`localhost:9000`)가 다르다는 점에서 실기동 검증이 필요하다. Docker 검증을 미루지 않는다.
