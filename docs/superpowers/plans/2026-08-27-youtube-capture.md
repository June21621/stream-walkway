# youtube-service 캡처 구현 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /download` 한 번에 프레임 한 장을 떠서 MinIO에 올리고 `image.downloaded`를 발행해, 기존 파이프라인을 타고 `GET /api/captures`로 조회되는 것까지 관통시킨다.

**Architecture:** youtube-service를 상태 없는 단발 캡처 실행기로 만든다. HTTP 계약(`app.js`)과 부수효과(`capture.js`/`storage.js`/`jobs.js`)를 분리하고, `createApp(deps)` 팩토리로 주입해 테스트가 진짜 ffmpeg를 띄우지 않게 한다. 캡처는 ffmpeg 하나로 하며 yt-dlp를 쓰지 않는다.

**Tech Stack:** Node.js 20, Express 4, kafkajs 2, ffmpeg(시스템 바이너리), `@aws-sdk/client-s3`, `ioredis`, Jest 29 + Supertest 7

**Spec:** `docs/superpowers/specs/2026-08-27-youtube-capture-design.md`

## Global Constraints

- 브랜치: `feature/youtube-capture` (`main`에서 분기, 이미 생성됨)
- **YouTube 어댑터를 만들지 않는다.** YouTube에서 프레임을 추출하는 것은 이용약관 위반이다. `yt-dlp`를 의존성에 추가하지 않는다
- 캡처 소스는 `CAPTURE_SOURCE` 환경변수로 고른다: `testsrc`(기본) / `file` / `hls`
- **`interval_sec`을 받지 않는다.** 요청 본문에 있어도 무시하고 응답에도 넣지 않는다
- 작업 상태는 `queued | processing | completed | failed` 넷
- `image_path`는 **객체 키**다. 완성된 URL이 아니다. 형식: `captures/{streamId}/{trailId}/{ISO8601}.jpg`
- Kafka `image.downloaded` 메시지의 **필드 이름을 바꾸지 않는다** (`imageId`, `streamId`, `trailId`, `imagePath`, `timestamp`)
- ffmpeg 타임아웃 기본 30초. 초과 시 kill 후 실패
- 테스트 실행 위치는 `apps/youtube-service`. 명령은 `npm test`
- 기준선: youtube-service 22개 중 11 GREEN / 11 RED. 다른 모듈은 251개 중 238 GREEN / 13 RED. **다른 모듈 수치가 바뀌면 회귀다**
- 커밋 메시지는 한글로, 무엇을/왜 했는지 포함
- 직접 촬영한 영상 파일을 git에 커밋하지 않는다

---

## File Structure

| 파일 | 책임 | 작업 |
|---|---|---|
| `src/capture.js` | ffmpeg 실행. 소스 3종 어댑터 | 신규 |
| `src/storage.js` | MinIO 업로드, 키 생성 | 신규 |
| `src/jobs.js` | Redis 기반 작업 상태 CRUD | 신규 |
| `src/pipeline.js` | 캡처→업로드→발행 조립. 상태 전이 | 신규 |
| `src/app.js` | HTTP 계약만. `createApp(deps)` 팩토리 | 수정 |
| `src/index.js` | 의존성 조립 + 서버 기동 | 수정 |
| `src/kafka.js` | Kafka 프로듀서 | 변경 없음 |
| `Dockerfile` | ffmpeg 설치 | 수정 |
| `package.json` | `@aws-sdk/client-s3`, `ioredis` 추가 | 수정 |
| `infra/docker/docker-compose.yml` | MinIO 서비스, 환경변수 | 수정 |

`app.js`가 `pipeline.js`를 **주입받는다.** 직접 `require`하면 테스트가 진짜 ffmpeg를 띄운다.

---

### Task 1: ffmpeg 캡처 어댑터

**Files:**
- Create: `apps/youtube-service/src/capture.js`
- Create: `apps/youtube-service/tests/capture.test.js`
- Modify: `apps/youtube-service/Dockerfile`

**Interfaces:**
- Produces: `captureFromTestPattern(opts?) -> Promise<Buffer>` — JPEG 바이트
- Produces: `captureFromFile(path, offsetSec, opts?) -> Promise<Buffer>`
- Produces: `captureFromHls(url, opts?) -> Promise<Buffer>`
- Produces: `createCapture(env) -> (sourceUrl) => Promise<Buffer>` — `CAPTURE_SOURCE`로 어댑터 선택
- `opts`는 `{ timeoutMs?: number }`. 기본 30000
- 실패 시 `Error`의 message가 `capture failed: `로 시작한다

**이 태스크를 먼저 하는 이유:** 스펙의 "위험" 절이 지목한 항목이다. ffmpeg 출력을 버퍼로 받아 그대로 넘기는 경로가 이 저장소에서 검증된 적이 없다. 나머지를 다 만들고 이게 안 되면 전부 헛일이다.

- [ ] **Step 1: Dockerfile에 ffmpeg를 추가한다**

`RUN npm install` 줄 **위**에 넣는다. 레이어 캐시가 npm 설치보다 덜 자주 바뀌는 쪽에 있어야 재빌드가 빠르다.

```dockerfile
FROM node:20-alpine

# 캡처에 ffmpeg가 필요하다. yt-dlp는 쓰지 않는다 —
# YouTube에서 프레임을 추출하는 것은 이용약관 위반이라
# 설계 단계에서 YouTube 어댑터를 만들지 않기로 했다.
RUN apk add --no-cache ffmpeg

WORKDIR /app
```

나머지 줄은 그대로 둔다.

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`apps/youtube-service/tests/capture.test.js`

```js
const { captureFromTestPattern, createCapture } = require('../src/capture');

// 이 파일만 진짜 ffmpeg를 실행한다. mock이 거짓말할 수 있는 지점이라
// 실물이 필요하다. 나머지 테스트는 캡처를 주입받아 가짜로 대체한다.
describe('capture.js', () => {

  test('captureFromTestPattern - JPEG 바이트를 반환한다', async () => {
    const buf = await captureFromTestPattern();

    expect(Buffer.isBuffer(buf)).toBe(true);
    expect(buf.length).toBeGreaterThan(1000);
    // JPEG 매직 바이트 FF D8 FF
    expect(buf[0]).toBe(0xff);
    expect(buf[1]).toBe(0xd8);
    expect(buf[2]).toBe(0xff);
  }, 30000);

  test('captureFromTestPattern - 타임아웃이 걸리면 capture failed로 거부한다', async () => {
    await expect(captureFromTestPattern({ timeoutMs: 1 }))
      .rejects.toThrow(/capture failed/);
  }, 30000);

  test('createCapture - CAPTURE_SOURCE가 testsrc면 테스트 패턴을 쓴다', async () => {
    const capture = createCapture({ CAPTURE_SOURCE: 'testsrc' });
    const buf = await capture('ignored-url');

    expect(buf[0]).toBe(0xff);
    expect(buf[1]).toBe(0xd8);
  }, 30000);

  test('createCapture - 알 수 없는 CAPTURE_SOURCE는 즉시 던진다', () => {
    expect(() => createCapture({ CAPTURE_SOURCE: 'nope' }))
      .toThrow(/unknown CAPTURE_SOURCE/);
  });
});
```

- [ ] **Step 3: 실패를 확인한다**

Run: `npm test -- capture.test.js`
Expected: `Cannot find module '../src/capture'`

- [ ] **Step 4: `capture.js`를 구현한다**

```js
const { spawn } = require('child_process');

const DEFAULT_TIMEOUT_MS = 30000;

// ffmpeg를 실행해 stdout을 버퍼로 모은다. 디스크를 거치지 않는다.
// 임시 파일이 없으므로 정리할 것도, 볼륨도 필요 없다.
function runFfmpeg(args, { timeoutMs = DEFAULT_TIMEOUT_MS } = {}) {
  return new Promise((resolve, reject) => {
    const proc = spawn('ffmpeg', args);
    const chunks = [];
    let stderr = '';
    let settled = false;

    // 타임아웃이 없으면 응답 없는 소스를 문 프로세스가 영원히 남아
    // 좀비가 쌓이고 컨테이너가 서서히 죽는다.
    const timer = setTimeout(() => {
      settled = true;
      proc.kill('SIGKILL');
      reject(new Error(`capture failed: ffmpeg timed out after ${timeoutMs}ms`));
    }, timeoutMs);

    proc.stdout.on('data', (c) => chunks.push(c));
    proc.stderr.on('data', (c) => { stderr += c.toString(); });

    proc.on('error', (err) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      reject(new Error(`capture failed: ${err.message}`));
    });

    proc.on('close', (code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);

      if (code !== 0) {
        // ffmpeg는 진행 로그를 stderr에 잔뜩 쏟으므로 마지막 줄만 남긴다.
        const lastLine = stderr.trim().split('\n').pop() || `exit code ${code}`;
        return reject(new Error(`capture failed: ${lastLine}`));
      }

      const buf = Buffer.concat(chunks);
      if (buf.length === 0) {
        return reject(new Error('capture failed: empty output'));
      }
      resolve(buf);
    });
  });
}

// 공통 출력 인자: 프레임 1장을 MJPEG로 stdout에 낸다.
const OUTPUT_ARGS = ['-frames:v', '1', '-f', 'image2', '-vcodec', 'mjpeg', '-'];

// ffmpeg가 즉석에서 만드는 테스트 패턴. 네트워크도 저작권도 개입하지 않고
// 코드 경로는 실제와 같다. 자동화 테스트와 CI에서 쓴다.
function captureFromTestPattern(opts) {
  return runFfmpeg(['-f', 'lavfi', '-i', 'testsrc=size=1280x720:rate=1', ...OUTPUT_ARGS], opts);
}

// 직접 촬영한 영상 파일. -ss를 입력 앞에 두면 디코딩을 건너뛰어 훨씬 빠르다.
function captureFromFile(path, offsetSec = 0, opts) {
  return runFfmpeg(['-ss', String(offsetSec), '-i', path, ...OUTPUT_ARGS], opts);
}

// 공공 CCTV 등의 HLS/RTSP 스트림. ffmpeg가 직접 읽으므로 새 의존성이 없다.
function captureFromHls(url, opts) {
  return runFfmpeg(['-i', url, ...OUTPUT_ARGS], opts);
}

// CAPTURE_SOURCE로 어댑터를 고른다. YouTube 어댑터는 없다 —
// 이용약관 위반이라 설계 단계에서 만들지 않기로 했다.
function createCapture(env = process.env) {
  const source = env.CAPTURE_SOURCE || 'testsrc';

  if (source === 'testsrc') return (_url) => captureFromTestPattern();
  if (source === 'file') return (_url) => captureFromFile(env.CAPTURE_FILE_PATH, Number(env.CAPTURE_FILE_OFFSET_SEC || 0));
  if (source === 'hls') return (url) => captureFromHls(url);

  throw new Error(`unknown CAPTURE_SOURCE: ${source} (supported: testsrc, file, hls)`);
}

module.exports = { captureFromTestPattern, captureFromFile, captureFromHls, createCapture };
```

- [ ] **Step 5: 통과를 확인한다**

Run: `npm test -- capture.test.js`
Expected: 4개 전부 PASS

로컬에 ffmpeg가 없으면 `spawn ffmpeg ENOENT`로 실패한다. Windows에서는 `winget install Gyan.FFmpeg` 또는 Docker 안에서 실행한다.

- [ ] **Step 6: 커밋**

```bash
git add apps/youtube-service/src/capture.js apps/youtube-service/tests/capture.test.js apps/youtube-service/Dockerfile
git commit -m "feat(youtube-service): ffmpeg 캡처 어댑터 추가

프레임 한 장을 JPEG 바이트로 뽑는 어댑터다. 디스크를 거치지 않고
ffmpeg stdout을 버퍼로 모아 그대로 넘긴다 - 임시 파일 정리도 볼륨도
필요 없다.

소스 3종을 어댑터로 분리하고 CAPTURE_SOURCE로 고른다.
- testsrc: ffmpeg가 만드는 테스트 패턴. 자동화 테스트와 CI용
- file: 직접 촬영한 영상
- hls: 공공 CCTV 스트림

YouTube 어댑터는 만들지 않는다. YouTube에서 프레임을 추출하는 것은
이용약관 위반이고 Data API는 프레임을 주지 않아 합법적 대체 경로가
없다. 없는 코드는 약관을 위반할 수 없다. 덕분에 yt-dlp와 python이
빠져 이미지가 200MB대가 아니라 130MB대에 머문다.

타임아웃 30초를 건다. 없으면 응답 없는 소스를 문 프로세스가 영원히
남아 좀비가 쌓이고 컨테이너가 서서히 죽는다.

이 테스트 파일만 진짜 ffmpeg를 실행한다. mock이 거짓말할 수 있는
지점이라 실물이 필요하다 - H2를 PostGIS로 일반화했다가 틀렸던 것과
같은 종류의 위험이다."
```

---

### Task 2: MinIO 저장소 어댑터

**Files:**
- Create: `apps/youtube-service/src/storage.js`
- Create: `apps/youtube-service/tests/storage.test.js`
- Modify: `apps/youtube-service/package.json`
- Modify: `infra/docker/docker-compose.yml`
- Modify: `.env.example`

**Interfaces:**
- Consumes: 없음
- Produces: `buildKey(streamId, trailId, date) -> string` — `captures/{streamId}/{trailId}/{ISO}.jpg`
- Produces: `createStorage(env) -> { upload(key, buffer) -> Promise<void>, ensureBucket() -> Promise<void> }`

- [ ] **Step 1: 의존성을 추가한다**

```bash
cd apps/youtube-service
npm install @aws-sdk/client-s3
```

MinIO 전용 SDK가 아니라 S3 SDK를 쓴다. 운영에서 실제 S3로 갈 때 엔드포인트 설정만 바꾸면 된다.

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`apps/youtube-service/tests/storage.test.js`

```js
const { buildKey } = require('../src/storage');

describe('storage.js - buildKey', () => {

  test('captures/{streamId}/{trailId}/{ISO}.jpg 형식으로 만든다', () => {
    const key = buildKey(1, 2, new Date('2026-08-27T10:15:00.000Z'));
    expect(key).toBe('captures/1/2/2026-08-27T10-15-00Z.jpg');
  });

  test('콜론을 대시로 바꾼다 - URL과 파일명에서 안전하도록', () => {
    const key = buildKey(1, 1, new Date('2026-01-02T03:04:05.000Z'));
    expect(key).not.toContain(':');
    expect(key).toBe('captures/1/1/2026-01-02T03-04-05Z.jpg');
  });

  test('밀리초를 버린다 - 15분 간격 표본이라 초 단위로 충분하다', () => {
    const key = buildKey(1, 1, new Date('2026-01-02T03:04:05.678Z'));
    expect(key).toBe('captures/1/1/2026-01-02T03-04-05Z.jpg');
  });

  test('streamId와 trailId가 경로에 그대로 들어간다', () => {
    expect(buildKey(42, 7, new Date('2026-01-01T00:00:00.000Z')))
      .toBe('captures/42/7/2026-01-01T00-00-00Z.jpg');
  });
});
```

- [ ] **Step 3: 실패를 확인한다**

Run: `npm test -- storage.test.js`
Expected: `Cannot find module '../src/storage'`

- [ ] **Step 4: `storage.js`를 구현한다**

```js
const { S3Client, PutObjectCommand, HeadBucketCommand, CreateBucketCommand } = require('@aws-sdk/client-s3');

// captures/{streamId}/{trailId}/{ISO8601}.jpg
//
// 트레일별로 폴더가 나뉘어 사람이 봐도 이해되고, 시각이 파일명이라
// 정렬이 자연스러우며, 같은 트레일에 같은 초로 두 번 찍힐 일이 없어
// 충돌하지 않는다.
//
// 콜론을 대시로 바꾸는 이유는 URL과 일부 파일시스템에서 콜론이
// 성가시기 때문이다. 밀리초는 버린다 - 15분 간격 표본에 불필요하다.
function buildKey(streamId, trailId, date = new Date()) {
  const iso = date.toISOString()
    .replace(/\.\d{3}Z$/, 'Z')
    .replace(/:/g, '-');
  return `captures/${streamId}/${trailId}/${iso}.jpg`;
}

function createStorage(env = process.env) {
  const bucket = env.MINIO_BUCKET || 'captures';

  const client = new S3Client({
    endpoint: env.MINIO_ENDPOINT || 'http://minio:9000',
    region: env.MINIO_REGION || 'us-east-1',
    credentials: {
      accessKeyId: env.MINIO_ACCESS_KEY,
      secretAccessKey: env.MINIO_SECRET_KEY,
    },
    // MinIO는 가상 호스트 방식 주소를 기본으로 지원하지 않는다.
    // 경로 방식(http://host/bucket/key)을 강제해야 한다.
    forcePathStyle: true,
  });

  // 버킷이 없으면 만든다. 별도 초기화 컨테이너를 두지 않는다.
  async function ensureBucket() {
    try {
      await client.send(new HeadBucketCommand({ Bucket: bucket }));
    } catch (err) {
      await client.send(new CreateBucketCommand({ Bucket: bucket }));
    }
  }

  async function upload(key, buffer) {
    await client.send(new PutObjectCommand({
      Bucket: bucket,
      Key: key,
      Body: buffer,
      ContentType: 'image/jpeg',
    }));
  }

  return { upload, ensureBucket };
}

module.exports = { buildKey, createStorage };
```

- [ ] **Step 5: 통과를 확인한다**

Run: `npm test -- storage.test.js`
Expected: 4개 전부 PASS

- [ ] **Step 6: compose에 MinIO를 추가한다**

`infra/docker/docker-compose.yml`의 `kafka:` 블록 **아래**, `backend:` 블록 위에 넣는다.

```yaml
  minio:
    image: minio/minio:RELEASE.2024-09-13T20-26-02Z
    container_name: stream-minio
    command: server /data --console-address ":9001"
    ports:
      - "${MINIO_PORT:-9000}:9000"
      - "${MINIO_CONSOLE_PORT:-9001}:9001"
    environment:
      MINIO_ROOT_USER: ${MINIO_ACCESS_KEY}
      MINIO_ROOT_PASSWORD: ${MINIO_SECRET_KEY}
    volumes:
      - minio_data:/data
    healthcheck:
      test: ["CMD", "mc", "ready", "local"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - stream-network
```

`volumes:` 절 맨 아래에 `minio_data:`를 추가한다.

youtube-service 블록의 `environment:`에 넣는다.

```yaml
      MINIO_ENDPOINT: http://minio:9000
      MINIO_ACCESS_KEY: ${MINIO_ACCESS_KEY}
      MINIO_SECRET_KEY: ${MINIO_SECRET_KEY}
      MINIO_BUCKET: ${MINIO_BUCKET:-captures}
      CAPTURE_SOURCE: ${CAPTURE_SOURCE:-testsrc}
```

youtube-service의 `depends_on:`에 추가한다.

```yaml
      minio:
        condition: service_healthy
```

- [ ] **Step 7: `.env.example`에 변수를 추가한다**

`# Redis` 블록 아래에 넣는다.

```bash
# MinIO (S3 호환 오브젝트 스토리지)
MINIO_ACCESS_KEY=minio_admin
MINIO_SECRET_KEY=change_me_minio
MINIO_BUCKET=captures
MINIO_PORT=9000
MINIO_CONSOLE_PORT=9001

# 캡처 소스: testsrc | file | hls
CAPTURE_SOURCE=testsrc
```

- [ ] **Step 8: MinIO가 실제로 뜨는지 확인한다**

```bash
cd infra/docker && docker compose --env-file ../../.env up -d minio
docker compose --env-file ../../.env ps minio
```

Expected: `healthy` 상태. 브라우저에서 `http://localhost:9001`에 콘솔이 뜬다.

- [ ] **Step 9: 커밋**

```bash
git add apps/youtube-service/src/storage.js apps/youtube-service/tests/storage.test.js apps/youtube-service/package.json apps/youtube-service/package-lock.json infra/docker/docker-compose.yml .env.example
git commit -m "feat(youtube-service): MinIO 저장소 어댑터와 compose 서비스 추가

캡처한 이미지를 둘 곳이 없어 image_path가 지금까지 전부 하드코딩된
가짜였다. MinIO를 도입해 실제 파일을 저장한다.

키 형식은 captures/{streamId}/{trailId}/{ISO8601}.jpg다. 트레일별로
폴더가 나뉘어 사람이 봐도 이해되고, 시각이 파일명이라 정렬이 자연스러우며,
같은 트레일에 같은 초로 두 번 찍힐 일이 없어 충돌하지 않는다. 콜론은
URL과 일부 파일시스템에서 성가시므로 대시로 바꾸고, 밀리초는 15분 간격
표본에 불필요하므로 버린다.

MinIO 전용 SDK가 아니라 @aws-sdk/client-s3를 쓴다. 운영에서 실제 S3로
갈 때 엔드포인트 설정만 바꾸면 된다. forcePathStyle이 필요한데 MinIO는
가상 호스트 방식 주소를 기본 지원하지 않기 때문이다.

버킷은 기동 시 없으면 만든다. 별도 초기화 컨테이너를 두지 않는다.

이 저장소의 첫 오브젝트 스토리지이며 첫 새 의존성이다."
```

---

### Task 3: Redis 작업 저장소

**Files:**
- Create: `apps/youtube-service/src/jobs.js`
- Create: `apps/youtube-service/tests/jobs.test.js`
- Modify: `apps/youtube-service/package.json`

**Interfaces:**
- Consumes: 없음
- Produces: `createJobs(redisClient, { ttlSec }) -> { create(job), get(jobId), update(jobId, patch) }`
- `create(job)`는 `{ jobId, status:'queued', progress:0, downloaded_count:0, ...job }`을 저장하고 그 객체를 반환
- `get(jobId)`는 없으면 `null`
- `update(jobId, patch)`는 기존 값에 patch를 병합. 없으면 `null` 반환

- [ ] **Step 1: 의존성을 추가한다**

```bash
cd apps/youtube-service
npm install ioredis
npm install --save-dev ioredis-mock
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`apps/youtube-service/tests/jobs.test.js`

```js
const RedisMock = require('ioredis-mock');
const { createJobs } = require('../src/jobs');

describe('jobs.js', () => {
  let redis;
  let jobs;

  beforeEach(() => {
    redis = new RedisMock();
    jobs = createJobs(redis, { ttlSec: 3600 });
  });

  test('create - 기본 필드를 채워 저장한다', async () => {
    const job = await jobs.create({ jobId: 'j1', stream_id: 1, trail_id: 2 });

    expect(job.jobId).toBe('j1');
    expect(job.status).toBe('queued');
    expect(job.progress).toBe(0);
    expect(job.downloaded_count).toBe(0);
    expect(job.stream_id).toBe(1);
  });

  test('get - 저장한 작업을 되읽는다', async () => {
    await jobs.create({ jobId: 'j1', stream_id: 1, trail_id: 2 });
    const found = await jobs.get('j1');

    expect(found.jobId).toBe('j1');
    expect(found.status).toBe('queued');
  });

  test('get - 없는 작업은 null을 반환한다', async () => {
    expect(await jobs.get('nope')).toBeNull();
  });

  test('update - 기존 값에 병합한다', async () => {
    await jobs.create({ jobId: 'j1', stream_id: 1, trail_id: 2 });
    const updated = await jobs.update('j1', { status: 'completed', progress: 100, downloaded_count: 1 });

    expect(updated.status).toBe('completed');
    expect(updated.progress).toBe(100);
    // 병합이므로 기존 필드가 남아야 한다
    expect(updated.stream_id).toBe(1);
  });

  test('update - 없는 작업은 null을 반환한다', async () => {
    expect(await jobs.update('nope', { status: 'failed' })).toBeNull();
  });

  test('create - TTL이 설정된다', async () => {
    await jobs.create({ jobId: 'j1', stream_id: 1, trail_id: 2 });
    const ttl = await redis.ttl('job:j1');

    // 넣기만 하고 지우는 코드가 없으면 무한히 쌓인다. TTL이 그걸 막는다.
    expect(ttl).toBeGreaterThan(0);
    expect(ttl).toBeLessThanOrEqual(3600);
  });
});
```

- [ ] **Step 3: 실패를 확인한다**

Run: `npm test -- jobs.test.js`
Expected: `Cannot find module '../src/jobs'`

- [ ] **Step 4: `jobs.js`를 구현한다**

```js
const KEY_PREFIX = 'job:';
const DEFAULT_TTL_SEC = 3600;

// 작업 상태를 Redis에 둔다. 이전 구현은 프로세스 메모리의 Map이었는데
// 넣기만 하고 지우는 코드가 없어 15분마다 1건씩 영원히 쌓였다.
// TTL이 그 문제를 구조적으로 없앤다 - 정리 코드를 직접 쓸 필요가 없다.
function createJobs(redis, { ttlSec = DEFAULT_TTL_SEC } = {}) {
  const key = (jobId) => `${KEY_PREFIX}${jobId}`;

  async function create(job) {
    const record = {
      status: 'queued',
      progress: 0,
      downloaded_count: 0,
      ...job,
    };
    await redis.set(key(record.jobId), JSON.stringify(record), 'EX', ttlSec);
    return record;
  }

  async function get(jobId) {
    const raw = await redis.get(key(jobId));
    return raw ? JSON.parse(raw) : null;
  }

  async function update(jobId, patch) {
    const current = await get(jobId);
    if (!current) return null;

    const merged = { ...current, ...patch };
    // TTL을 다시 건다. 갱신할 때마다 수명이 연장되는데, 작업이 몇 초짜리라
    // 실질적인 차이는 없고 만료 직전 갱신이 값을 잃는 것을 막는다.
    await redis.set(key(jobId), JSON.stringify(merged), 'EX', ttlSec);
    return merged;
  }

  return { create, get, update };
}

module.exports = { createJobs };
```

- [ ] **Step 5: 통과를 확인한다**

Run: `npm test -- jobs.test.js`
Expected: 6개 전부 PASS

- [ ] **Step 6: 커밋**

```bash
git add apps/youtube-service/src/jobs.js apps/youtube-service/tests/jobs.test.js apps/youtube-service/package.json apps/youtube-service/package-lock.json
git commit -m "feat(youtube-service): Redis 기반 작업 상태 저장소 추가

기존 jobStore는 프로세스 메모리의 Map이었고 넣기만 하고 지우는 코드가
없었다. 15분마다 1건 x 트레일 N개가 영원히 쌓이는 누수다.

Redis의 EX 인자 하나로 TTL이 붙어 그 문제가 구조적으로 사라진다.
메모리 Map을 유지하면 만료 정리를 직접 구현해야 하므로 코드를 덜 쓰는
쪽이 Redis다. 인스턴스를 늘려도 POST와 GET이 다른 인스턴스에 가도 된다.

compose가 이미 youtube-service에 REDIS_HOST와 REDIS_PASSWORD를
주입하고 있었다. 연결선이 이미 그어져 있는데 쓰지 않던 상태였다."
```

---

### Task 4: 캡처 파이프라인 조립

**Files:**
- Create: `apps/youtube-service/src/pipeline.js`
- Create: `apps/youtube-service/tests/pipeline.test.js`

**Interfaces:**
- Consumes: Task 1의 `createCapture`, Task 2의 `buildKey`/`createStorage`, Task 3의 `createJobs`, 기존 `kafka.publishMessage`
- Produces: `createPipeline({ capture, storage, jobs, publish }) -> runCapture(job) -> Promise<void>`
- `runCapture`는 던지지 않는다. 실패를 `jobs.update`로 기록하고 정상 반환한다

`runCapture`가 던지지 않는 것이 중요하다. HTTP 응답(202) 이후 비동기로 돌기 때문에, 여기서 새는 예외는 잡아줄 곳이 없어 프로세스를 내린다. ml-service `consume()`에 `try/except`를 두른 것과 같은 이유다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`apps/youtube-service/tests/pipeline.test.js`

```js
const { createPipeline } = require('../src/pipeline');

function makeDeps(overrides = {}) {
  const updates = [];
  return {
    updates,
    deps: {
      capture: jest.fn().mockResolvedValue(Buffer.from([0xff, 0xd8, 0xff, 0x00])),
      storage: { upload: jest.fn().mockResolvedValue(undefined) },
      jobs: {
        update: jest.fn(async (jobId, patch) => { updates.push(patch); return patch; }),
      },
      publish: jest.fn().mockResolvedValue(undefined),
      ...overrides,
    },
  };
}

const JOB = { jobId: 'j1', stream_id: 1, trail_id: 2, youtube_url: 'http://example/x' };

describe('pipeline.js - runCapture', () => {

  test('성공하면 completed와 downloaded_count 1로 끝난다', async () => {
    const { deps, updates } = makeDeps();
    await createPipeline(deps).runCapture(JOB);

    const last = updates[updates.length - 1];
    expect(last.status).toBe('completed');
    expect(last.progress).toBe(100);
    expect(last.downloaded_count).toBe(1);
  });

  test('성공 경로에서 processing을 먼저 기록한다', async () => {
    const { deps, updates } = makeDeps();
    await createPipeline(deps).runCapture(JOB);

    expect(updates[0].status).toBe('processing');
  });

  test('키 형식대로 업로드한다', async () => {
    const { deps } = makeDeps();
    await createPipeline(deps).runCapture(JOB);

    const [key, buffer] = deps.storage.upload.mock.calls[0];
    expect(key).toMatch(/^captures\/1\/2\/.*\.jpg$/);
    expect(Buffer.isBuffer(buffer)).toBe(true);
  });

  test('image.downloaded를 발행하고 imagePath가 업로드한 키와 같다', async () => {
    const { deps } = makeDeps();
    await createPipeline(deps).runCapture(JOB);

    const [topic, message] = deps.publish.mock.calls[0];
    const [uploadedKey] = deps.storage.upload.mock.calls[0];

    expect(topic).toBe('image.downloaded');
    expect(message.imagePath).toBe(uploadedKey);
    expect(message.imageId).toBe('j1');
    expect(message.streamId).toBe(1);
    expect(message.trailId).toBe(2);
    expect(message.timestamp).toEqual(expect.any(String));
  });

  test('캡처가 실패하면 failed와 error를 기록한다', async () => {
    const { deps, updates } = makeDeps({
      capture: jest.fn().mockRejectedValue(new Error('capture failed: no such source')),
    });
    await createPipeline(deps).runCapture(JOB);

    const last = updates[updates.length - 1];
    expect(last.status).toBe('failed');
    expect(last.error).toMatch(/capture failed/);
    expect(last.downloaded_count).toBe(0);
  });

  test('캡처가 실패하면 업로드도 발행도 하지 않는다', async () => {
    const { deps } = makeDeps({
      capture: jest.fn().mockRejectedValue(new Error('capture failed: boom')),
    });
    await createPipeline(deps).runCapture(JOB);

    expect(deps.storage.upload).not.toHaveBeenCalled();
    expect(deps.publish).not.toHaveBeenCalled();
  });

  test('발행이 실패해도 던지지 않고 failed로 기록한다', async () => {
    const { deps, updates } = makeDeps({
      publish: jest.fn().mockRejectedValue(new Error('broker down')),
    });

    // 비동기로 도는 코드라 여기서 새는 예외는 프로세스를 내린다.
    await expect(createPipeline(deps).runCapture(JOB)).resolves.toBeUndefined();

    const last = updates[updates.length - 1];
    expect(last.status).toBe('failed');
    expect(last.error).toMatch(/publish failed/);
  });
});
```

- [ ] **Step 2: 실패를 확인한다**

Run: `npm test -- pipeline.test.js`
Expected: `Cannot find module '../src/pipeline'`

- [ ] **Step 3: `pipeline.js`를 구현한다**

```js
const { buildKey } = require('./storage');

const TOPIC = 'image.downloaded';

// 캡처 -> 업로드 -> 발행을 순서대로 하고 각 단계마다 작업 상태를 갱신한다.
//
// runCapture는 절대 던지지 않는다. HTTP 응답(202) 이후 비동기로 돌기
// 때문에 여기서 새는 예외는 잡아줄 곳이 없어 프로세스를 내린다.
// ml-service consume()에 try/except를 두른 것과 같은 이유다.
function createPipeline({ capture, storage, jobs, publish }) {

  async function runCapture(job) {
    const { jobId, stream_id: streamId, trail_id: trailId, youtube_url: sourceUrl } = job;

    try {
      await jobs.update(jobId, { status: 'processing' });

      let buffer;
      try {
        buffer = await capture(sourceUrl);
      } catch (err) {
        throw new Error(err.message.startsWith('capture failed') ? err.message : `capture failed: ${err.message}`);
      }

      const key = buildKey(streamId, trailId);

      try {
        await storage.upload(key, buffer);
      } catch (err) {
        throw new Error(`upload failed: ${err.message}`);
      }

      try {
        await publish(TOPIC, {
          imageId: jobId,
          streamId,
          trailId,
          imagePath: key,
          timestamp: new Date().toISOString(),
        });
      } catch (err) {
        // 이미지는 이미 올라갔다. 되돌리지 않는다 - 삭제도 실패할 수 있어
        // 같은 문제가 한 겹 뒤로 밀릴 뿐이고, 고아 객체는 나중에
        // "DB에 없는 키 정리" 배치로 한 번에 치울 수 있다.
        throw new Error(`publish failed: ${err.message}`);
      }

      await jobs.update(jobId, { status: 'completed', progress: 100, downloaded_count: 1 });
    } catch (err) {
      await jobs.update(jobId, {
        status: 'failed',
        progress: 0,
        downloaded_count: 0,
        error: err.message,
      });
    }
  }

  return { runCapture };
}

module.exports = { createPipeline };
```

- [ ] **Step 4: 통과를 확인한다**

Run: `npm test -- pipeline.test.js`
Expected: 7개 전부 PASS

- [ ] **Step 5: 커밋**

```bash
git add apps/youtube-service/src/pipeline.js apps/youtube-service/tests/pipeline.test.js
git commit -m "feat(youtube-service): 캡처 파이프라인 조립

캡처 -> MinIO 업로드 -> Kafka 발행을 순서대로 하고 각 단계마다 작업
상태를 갱신한다.

runCapture는 절대 던지지 않는다. HTTP 응답(202) 이후 비동기로 돌기
때문에 여기서 새는 예외는 잡아줄 곳이 없어 프로세스를 내린다.
ml-service consume()에 try/except를 두른 것과 같은 이유다. 테스트가
이것을 직접 단언한다.

실패 메시지에 단계를 접두사로 붙인다(capture failed / upload failed /
publish failed). 어느 단계에서 깨졌는지 구분되지 않으면 운영에서
아무것도 고칠 수 없다.

발행이 실패하면 이미지는 이미 MinIO에 올라간 상태로 남는다. 되돌리지
않는다. 삭제도 실패할 수 있어 같은 문제가 한 겹 뒤로 밀릴 뿐이고,
고아 객체는 나중에 보관 정책 배치와 함께 한 번에 치울 수 있다.

Kafka 메시지의 필드 이름을 하나도 바꾸지 않았다. ml-service와 writer가
이미 이 형태를 소비하므로 imagePath의 의미만 가짜 경로에서 진짜 MinIO
키로 바뀐다. 하류 코드 수정이 0이다."
```

---

### Task 5: HTTP 계약 구현

**Files:**
- Modify: `apps/youtube-service/src/app.js`
- Modify: `apps/youtube-service/src/index.js`
- Modify: `apps/youtube-service/tests/app.test.js`
- Modify: `apps/youtube-service/tests/kafka.test.js`

**Interfaces:**
- Consumes: Task 4의 `createPipeline(...).runCapture`, Task 3의 `createJobs`
- Produces: `createApp({ jobs, runCapture }) -> express.Application`
- Produces: `module.exports = { createApp }` — `app` 단일 인스턴스 export는 없앤다

- [ ] **Step 1: 기존 테스트를 새 계약에 맞춘다**

`tests/app.test.js`에서 세 가지를 고친다.

**첫째, `interval_sec: 5,` 줄 5곳을 전부 지운다.** 설계 2번에서 "몇 분마다"가 스케줄러의 관심사가 되어 이 서비스와 무관해졌다.

**둘째, 파일 상단의 import와 앱 생성을 바꾼다.**

```js
const request = require('supertest');
const { createApp } = require('../src/app');

// 캡처 실행부를 가짜로 주입한다. 진짜 ffmpeg를 띄우지 않는다.
// runCapture는 파이프라인이 그렇듯 던지지 않고 조용히 끝난다.
function buildApp() {
  const store = new Map();
  const jobs = {
    create: jest.fn(async (job) => {
      const record = { status: 'queued', progress: 0, downloaded_count: 0, ...job };
      store.set(record.jobId, record);
      return record;
    }),
    get: jest.fn(async (jobId) => store.get(jobId) || null),
    update: jest.fn(async (jobId, patch) => {
      const cur = store.get(jobId);
      if (!cur) return null;
      const merged = { ...cur, ...patch };
      store.set(jobId, merged);
      return merged;
    }),
  };
  const runCapture = jest.fn().mockResolvedValue(undefined);
  return { app: createApp({ jobs, runCapture }), jobs, runCapture };
}
```

**각 `test()` 안에서 `buildApp()`을 정확히 한 번만 부르고, 그 `app`을 그 테스트의 모든 요청에 쓴다** (사전 점검 R3 판정).

```js
test('...', async () => {
  const { app } = buildApp();          // 테스트당 한 번
  const res1 = await request(app).post('/download').send({...});
  const res2 = await request(app).get(`/status/${res1.body.jobId}`);
  ...
});
```

테스트마다 새 앱과 새 저장소를 만들어 격리는 유지하면서, 테스트 내부의 POST→GET 상태 연속성은 보장된다. `beforeEach`로 공유하면 격리가 깨지고, 요청마다 새로 만들면 `GET /status`가 404를 낸다.

**셋째, `validStatuses`에 `'failed'`를 추가한다.**

```js
const validStatuses = ['queued', 'processing', 'completed', 'failed'];
```

`tests/kafka.test.js`에서는 **`[RED] POST /download → Kafka 발행 연동` describe 블록 전체를 삭제한다**(파일 끝의 140~183행 근처). 그 안에 있던 `interval_sec: 5,` 2곳도 함께 사라진다.

**삭제하는 이유 (사전 점검 R1 판정):** 그 두 테스트는 세 가지 이유로 이 설계와 맞지 않는다.

1. `const { app } = require('../src/app')` — 이 태스크가 `app` 단일 export를 없애고 `createApp` 팩토리로 바꾸므로 깨진다
2. 발행 메시지를 `{stream_id, trail_id, youtube_url}`(snake_case)로 단언하는데, 스펙은 Kafka 메시지를 `{imageId, streamId, trailId, imagePath, timestamp}`(camelCase)로 유지하기로 했다. **실제 컨슈머인 ml-service `consume()`이 `data.get("imageId")`를 읽으므로 snake_case를 기대하는 이 단언이 틀렸다**
3. `await request(app).post('/download')` 직후에 발행을 단언하는데, 설계상 캡처는 202 응답 **이후** 비동기로 돈다. 이 시점에는 아직 발행되지 않았다. 고쳐 살리려면 폴링이나 타이머에 의존해야 하고 그런 테스트는 흔들린다

발행 계약은 **Task 4의 `pipeline.test.js`가 더 정확히 덮는다** — 토픽, `imagePath`가 업로드한 키와 같은지, `imageId`/`streamId`/`trailId`/`timestamp`까지 `await`로 확실히 단언한다. 중복이 아니라 대체다.

- [ ] **Step 2: 실패를 확인한다**

Run: `npm test`
Expected: `createApp is not a function` — `app.js`가 아직 팩토리가 아니다

- [ ] **Step 3: `app.js`를 팩토리로 바꾼다**

파일 전체를 교체한다.

```js
const express = require('express');
const crypto = require('crypto');

const REQUIRED_FIELDS = ['stream_id', 'trail_id', 'youtube_url'];

// 의존성을 주입받는다. app.js가 capture/storage/kafka를 직접 require하면
// 테스트가 진짜 ffmpeg를 띄우고 브로커에 붙으려 한다.
function createApp({ jobs, runCapture }) {
  const app = express();
  app.use(express.json());

  app.get('/health', (req, res) => {
    res.json({ status: 'ok', service: 'youtube-service' });
  });

  // ─────────────────────────────────────────
  // POST /download — 지금 한 장 뜬다.
  //
  // "15분마다"를 세는 것은 스케줄러의 일이라 interval_sec을 받지 않는다.
  // 요청 본문에 있어도 무시하고 응답에도 넣지 않는다.
  // ─────────────────────────────────────────
  app.post('/download', async (req, res) => {
    for (const field of REQUIRED_FIELDS) {
      if (req.body[field] === undefined || req.body[field] === null) {
        return res.status(400).json({ error: `${field} is required` });
      }
    }

    const job = await jobs.create({
      jobId: crypto.randomUUID(),
      stream_id: req.body.stream_id,
      trail_id: req.body.trail_id,
      youtube_url: req.body.youtube_url,
    });

    // 응답을 먼저 보내고 캡처는 뒤에서 돈다. runCapture는 던지지 않도록
    // 만들어져 있지만, 혹시 모를 동기 예외까지 여기서 막는다.
    setImmediate(() => {
      Promise.resolve()
        .then(() => runCapture(job))
        .catch((err) => console.error('[youtube-service] 캡처 실행 실패:', err.message));
    });

    res.status(202).json({
      jobId: job.jobId,
      status: job.status,
      stream_id: job.stream_id,
      trail_id: job.trail_id,
      youtube_url: job.youtube_url,
    });
  });

  // ─────────────────────────────────────────
  // GET /status/:jobId
  // ─────────────────────────────────────────
  app.get('/status/:jobId', async (req, res) => {
    const job = await jobs.get(req.params.jobId);
    if (!job) {
      return res.status(404).json({ error: 'Job not found', jobId: req.params.jobId });
    }

    const body = {
      jobId: job.jobId,
      status: job.status,
      progress: job.progress,
      downloaded_count: job.downloaded_count,
    };
    if (job.error) body.error = job.error;

    res.json(body);
  });

  return app;
}

module.exports = { createApp };
```

`/test/publish` 엔드포인트는 지운다. 이제 `POST /download`가 진짜 발행을 하므로 테스트용 발행기가 필요 없다.

- [ ] **Step 4: `index.js`에서 의존성을 조립한다**

```js
const Redis = require('ioredis');
const { createApp } = require('./app');
const { connectKafka, publishMessage } = require('./kafka');
const { createCapture } = require('./capture');
const { createStorage } = require('./storage');
const { createJobs } = require('./jobs');
const { createPipeline } = require('./pipeline');

const PORT = process.env.PORT || 3000;

async function start() {
  try {
    await connectKafka();

    const redis = new Redis({
      host: process.env.REDIS_HOST || 'localhost',
      port: Number(process.env.REDIS_PORT || 6379),
      password: process.env.REDIS_PASSWORD,
    });

    const storage = createStorage(process.env);
    await storage.ensureBucket();

    const jobs = createJobs(redis, { ttlSec: 3600 });
    const capture = createCapture(process.env);
    const { runCapture } = createPipeline({ capture, storage, jobs, publish: publishMessage });

    createApp({ jobs, runCapture }).listen(PORT, () => {
      console.log(`[youtube-service] running on port ${PORT}`);
    });
  } catch (err) {
    console.error('[youtube-service] 시작 실패:', err.message);
    process.exit(1);
  }
}

start();
```

- [ ] **Step 5: 통과를 확인한다**

Run: `npm test`
Expected: 전체 통과. 기존 22개에서 kafka.test.js의 RED 2개를 뺀 20개 + Task 1~4의 신규 테스트(4 + 4 + 6 + 7 = 21) = **41개 전부 GREEN**. 실제 개수가 다르면 그 값을 그대로 기록한다.

- [ ] **Step 6: 커밋**

```bash
git add apps/youtube-service/src apps/youtube-service/tests
git commit -m "feat(youtube-service): POST /download와 GET /status 구현

스텁 두 개를 구현해 RED 11개를 GREEN으로 바꾼다.

app.js를 createApp(deps) 팩토리로 바꿔 jobs와 runCapture를 주입받게
했다. 직접 require하면 테스트가 진짜 ffmpeg를 띄우고 브로커에 붙으려
한다.

기존 테스트를 두 가지 고쳤다.
- interval_sec 5곳 제거. 설계에서 '몇 분마다'가 스케줄러의 관심사가 되어
  이 서비스와 무관해졌다. VOD를 전제로 쓰인 명세의 잔재다
- validStatuses에 'failed' 추가. 실패를 completed로 부르면 '성공했는데
  0장'과 구분되지 않고 원인도 알 수 없다

응답을 먼저 보내고 캡처는 setImmediate로 뒤에서 돈다. runCapture가
던지지 않도록 만들어져 있지만 혹시 모를 동기 예외까지 여기서 막는다.

/test/publish 엔드포인트를 지웠다. POST /download가 진짜 발행을 하므로
테스트용 발행기가 필요 없다."
```

---

### Task 6: Docker 파이프라인 관통 검증

**Files:**
- Create: `docs/superpowers/specs/2026-08-27-youtube-capture-verification.md`

**Interfaces:**
- Consumes: Task 1~5 전부
- Produces: 없음 (검증 기록)

**자동화 테스트가 아니다.** 각 모듈의 테스트가 전부 GREEN이어도 실제로 뜨는지는 별개다. 이 저장소는 유닛 테스트 176개가 GREEN인 상태에서 Docker 빌드 자체가 안 되던 적이 있다.

- [ ] **Step 1: 전체를 띄운다**

```bash
cd infra/docker && docker compose --env-file ../../.env up -d --build
docker compose --env-file ../../.env ps
```

Expected: 컨테이너 9개(postgres, redis, kafka, minio, backend, youtube-service, ml-service, writer, reader)가 전부 기동. minio가 `healthy`. frontend는 정적 export로 전환되면서 compose에서 빠졌다.

- [ ] **Step 2: 캡처를 한 번 요청한다**

```bash
curl -s -X POST http://localhost:3001/download \
  -H 'Content-Type: application/json' \
  -d '{"stream_id":1,"trail_id":1,"youtube_url":"unused-for-testsrc"}'
```

Expected: 202와 `{"jobId":"...","status":"queued",...}`. jobId를 기록해 둔다.

`stream_id=1`, `trail_id=1`이 DB에 실제로 있어야 writer가 저장할 수 있다. 없으면 먼저 만든다.

```bash
curl -s -X POST http://localhost:8080/api/streams \
  -H 'Content-Type: application/json' -H 'X-Internal-Key: dev-internal-key-change-me' \
  -d '{"name":"테스트 하천","location":"LINESTRING(126.97 37.55, 126.98 37.56)"}'

curl -s -X POST http://localhost:8080/api/trails \
  -H 'Content-Type: application/json' -H 'X-Internal-Key: dev-internal-key-change-me' \
  -d '{"stream_id":1,"camera_number":"CAM-001","location":"POINT(126.97 37.55)","direction":"북","status":"active"}'
```

- [ ] **Step 3: 작업 상태를 확인한다**

```bash
curl -s http://localhost:3001/status/<jobId>
```

Expected: `{"jobId":"...","status":"completed","progress":100,"downloaded_count":1}`

`failed`가 나오면 `error` 필드의 접두사로 어느 단계인지 알 수 있다. `docker compose logs youtube-service`로 상세를 본다.

- [ ] **Step 4: MinIO에 객체가 생겼는지 본다**

브라우저에서 `http://localhost:9001` 콘솔에 접속해 `captures` 버킷을 연다.

Expected: `captures/1/1/<시각>.jpg`가 있고, 열면 ffmpeg 테스트 패턴(컬러바)이 보인다.

- [ ] **Step 5: 파이프라인 끝까지 흘렀는지 확인한다**

```bash
curl -s "http://localhost:8080/api/captures?trail_id=1&limit=5"
```

Expected: 배열에 캡처 1건. `image_path`가 **MinIO 키**(`captures/1/1/...jpg`)이고 하드코딩된 가짜(`/images/capture_001.jpg`)가 아니다. `road_status`는 `양호`, `confidence`는 `0.95` — ml-service가 아직 고정값을 내므로 정상이다.

- [ ] **Step 6: 실패 경로를 확인한다**

```bash
docker compose --env-file ../../.env stop kafka
curl -s -X POST http://localhost:3001/download -H 'Content-Type: application/json' \
  -d '{"stream_id":1,"trail_id":1,"youtube_url":"x"}'
# 잠시 후
curl -s http://localhost:3001/status/<새 jobId>
docker compose --env-file ../../.env start kafka
```

Expected: `status: "failed"`, `error`가 `publish failed: `로 시작. **youtube-service 컨테이너가 죽지 않고 살아 있어야 한다** — 비동기 예외가 프로세스를 내리지 않는다는 설계가 실제로 지켜지는지 보는 지점이다.

- [ ] **Step 7: 검증 결과를 기록한다**

`docs/superpowers/specs/2026-08-27-youtube-capture-verification.md`에 각 단계의 **실제 출력**을 옮긴다. 추정하지 않는다. 실패했거나 확인하지 못한 항목은 "확인 안 됨"으로 남긴다.

- [ ] **Step 8: 커밋**

```bash
git add docs/superpowers/specs/2026-08-27-youtube-capture-verification.md
git commit -m "docs: youtube-service 캡처 파이프라인 관통 검증 결과 기록"
```

---

### Task 7: 문서 갱신

**Files:**
- Modify: `docs/api-specs/stream-walkway.postman_collection.json`
- Modify: `README.md`
- Modify: `docs/architecture/ARCHITECTURE.md`
- Modify: `docs/tdd-test-plan.md`
- Modify: `docs/superpowers/plans/2026-08-27-ml-service-red-green.md`

- [ ] **Step 1: 실측값을 얻는다**

```bash
cd apps/youtube-service && npm test
cd ../.. && ./services/writer/mvnw -o clean test -fae
cd apps/ml-service && python -m pytest tests -q
```

각 모듈의 통과/실패 수를 그대로 옮긴다. 추정하지 않는다.

- [ ] **Step 2: Postman 컬렉션을 고친다**

"YouTube Service :3001" 폴더의 `POST /download` 요청 본문에서 `"interval_sec": 5`를 지운다. `GET /status/:jobId`에 `failed` 응답 예시를 추가한다.

```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "failed",
  "progress": 0,
  "downloaded_count": 0,
  "error": "capture failed: Server returned 404 Not Found"
}
```

- [ ] **Step 3: README와 ARCHITECTURE를 고친다**

`README.md`의 디렉터리 설명에서 youtube-service를 "Node.js 이미지 다운로더"에서 "ffmpeg 기반 프레임 캡처 서비스 (MinIO 업로드)"로 바꾼다. 데이터 흐름 다이어그램에 MinIO를 넣는다.

```
Frontend → Backend (Gateway) → YouTube Service → ffmpeg 프레임 캡처 → MinIO
                              ↓
                         Kafka (image.downloaded)
```

기술 스택의 인프라 절에 `MinIO (S3 호환 오브젝트 스토리지)`를 추가한다.

`docs/architecture/ARCHITECTURE.md`의 디렉터리 설명과 데이터 저장소 절에 같은 내용을 반영한다.

- [ ] **Step 4: `docs/tdd-test-plan.md`를 갱신한다**

youtube-service 소스 표의 `src/app.js` 행을 "스텁"에서 "구현 완료"로 바꾸고 `capture.js`/`storage.js`/`jobs.js`/`pipeline.js` 행을 추가한다. 테스트 표를 Step 1의 실측값으로 채운다. 요약표와 합계를 갱신하고 "RED 테스트 상세"의 YouTube Service 절을 삭제한다.

"배경 및 주의사항"의 스텁 목록에서 youtube-service를 지운다. **스텁이 하나도 남지 않으므로 그 문단 전체를 "모든 스텁이 구현으로 대체되었다"로 바꾼다.**

- [ ] **Step 5: ml-service 계획서의 로드맵을 고친다**

`docs/superpowers/plans/2026-08-27-ml-service-red-green.md`의 "다음: youtube-service RED 11개" 절을 완료로 표시하고, 결정 항목으로 적혀 있던 `interval_sec`·yt-dlp·저장 위치가 어떻게 결정됐는지 한 줄로 정리해 설계 문서를 가리킨다.

- [ ] **Step 6: 커밋**

```bash
git add docs README.md
git commit -m "docs: youtube-service 캡처 구현을 문서에 반영

youtube-service RED 11개가 사라져 이 저장소에 미구현 스텁이 하나도
남지 않는다. 남은 RED는 reader/writer의 contextLoads 2개뿐이며
테스트 설정 문제로 기능과 무관하다.

Postman 컬렉션에서 interval_sec을 지우고 failed 응답 예시를 넣었다.
README와 ARCHITECTURE의 데이터 흐름에 MinIO를 추가하고 youtube-service
설명을 ffmpeg 기반 캡처로 정정했다.

수치는 각 모듈의 테스트 실행 결과를 그대로 옮긴 것이며 추정이 아니다."
```

---

## 완료 후 예상 상태

| 모듈 | 현재 | 완료 후 |
|---|---|---|
| youtube-service | 22 중 11 GREEN | 약 43 전부 GREEN |
| 그 외 | 251 중 238 GREEN / 13 RED | 변화 없음 |
| **합계** | **251 / 238 / 13** | **약 272 / 270 / 2** |

남는 RED 2개는 `ReaderApplicationTests`/`WriterApplicationTests`의 `contextLoads`다.

**Step 1에서 실측값을 다시 뽑는다.** 위 수치는 예상이며 신규 테스트 개수가 달라질 수 있다.

## 이 계획이 다루지 않는 것

- **스케줄러** — "15분마다"를 세는 주체. backend 트리거 작업에서 만든다
- **실제 ML 모델** — ml-service는 계속 고정값을 낸다
- **Kafka 컨슈머 DLQ** — ml-service와 writer가 처리 실패한 메시지를 버린다
- **이미지 보관 정책** — 트레일당 하루 96장이 무한히 쌓인다. 고아 객체 정리와 같은 배치로 묶을 수 있다
- **`youtube_url` → `source_url` 개명** — 테스트·명세를 함께 고쳐야 하므로 분리
