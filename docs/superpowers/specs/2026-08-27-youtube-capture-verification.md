# youtube-capture 파이프라인 관통 검증 (2026-08-28)

브랜치: `feature/youtube-capture`. Docker Desktop 29.7.2에서 실제로 `docker compose up --build`로 9개
컨테이너를 띄우고, MinIO 테스트 소스(`CAPTURE_SOURCE=testsrc`)로 실제 캡처 요청을 흘려 파이프라인
끝까지 관통하는지, 그리고 Kafka 장애 시 실패 경로가 설계대로 동작하는지 확인했다.

**주의**: DB 볼륨(`postgres_data`)이 이전 세션에서 남아 있어 완전히 빈 상태가 아니었다.
`stream_id=1`("한강 산책로"), `trail_id=1`(`CAM-001`, `stream_id=1`)이 이미 존재해 브리프의
Step 2 "생성" 커맨드는 새 레코드(`stream id=3`)를 만들었을 뿐 실제 검증에는 기존 `id=1`을 사용했다.

## Step 1: 전체를 띄운다

명령:
```
cd infra/docker && docker compose --env-file ../../.env up -d --build
docker compose --env-file ../../.env ps
```

결과: 빌드 성공, 9개 컨테이너 전부 `Up`. 실제 출력:
```
NAME              IMAGE                                      SERVICE           STATUS
stream-backend    docker-backend                             backend           Up 4 seconds
stream-kafka      apache/kafka:3.9.0                         kafka             Up 16 seconds (healthy)
stream-minio      minio/minio:RELEASE.2024-09-13T20-26-02Z   minio             Up 16 seconds (healthy)
stream-ml         docker-ml-service                          ml-service        Up 5 seconds
stream-postgres   postgis/postgis:15-3.3-alpine              postgres          Up 16 seconds (healthy)
stream-reader     docker-reader                              reader            Up 5 seconds
stream-redis      redis:7-alpine                             redis             Up 16 seconds (healthy)
stream-writer     docker-writer                              writer            Up 5 seconds
stream-youtube    docker-youtube-service                     youtube-service   Up 5 seconds
```
`minio`가 `healthy`로 나타남 (`mc ready local` healthcheck 통과). frontend 서비스 없음 확인.

**PASS**

추가 확인 — ffmpeg가 이미지 빌드에서 실제로 설치되고 컨테이너 안에서 호출 가능한지:
```
$ docker exec stream-youtube ffmpeg -version
ffmpeg version 8.0.1 Copyright (c) 2000-2025 the FFmpeg developers
built with gcc 15.2.0 (Alpine 15.2.0) ...
```
**PASS** — `apk add ffmpeg`가 빌드에서 실제로 성공했고 컨테이너 안에서 실행 가능함을 확인.

## Step 2: 캡처를 한 번 요청한다

`stream_id=1`, `trail_id=1`은 이전 세션 볼륨에 이미 존재해 (아래 참고) 그대로 사용:
```
$ curl -s http://localhost:8080/api/streams
[{"id":1,"name":"한강 산책로", ..., "created_at":"2026-08-22T11:18:27.079357Z"}, ...]
$ curl -s http://localhost:8080/api/trails
[{"id":1,"location":"POINT(126.97 37.55)", ..., "stream_id":1,"camera_number":"CAM-001", ...}, ...]
```

캡처 요청:
```
$ curl -s -w "\nHTTP_STATUS:%{http_code}\n" -X POST http://localhost:3001/download \
  -H 'Content-Type: application/json' \
  -d '{"stream_id":1,"trail_id":1,"youtube_url":"unused-for-testsrc"}'

{"jobId":"489f4485-b412-43d8-9e93-bf39d971d37a","status":"queued","stream_id":1,"trail_id":1,"youtube_url":"unused-for-testsrc"}
HTTP_STATUS:202
```

**PASS**

(부수 관찰 — 최초 보고와 재검증: 브리프의 스트림 생성 예시 커맨드를 한글 이름으로 그대로 실행하면
`400 Bad Request` / `Invalid UTF-8 middle byte 0xd7`가 발생했다. 최초 보고에서는 이를 "Git Bash UTF-8
전달 문제"로 결론지었으나, 그 근거("ASCII로 재시도하니 201")는 다른 입력이 성공했다는 것만 보여줄
뿐 원래 입력이 전송 과정에서 깨졌다는 것을 보여주지 않아 판별력이 없었다. 아래 판별 테스트로
재검증했다.

판별 테스트 (백엔드+postgres+redis+kafka+writer+reader만 재기동, youtube-service/minio 제외):
1. 요청 본문을 `printf`로 파일에 직접 기록하고 바이트를 `xxd`로 확인:
   ```
   $ printf '{"name":"테스트 하천","location":"LINESTRING(126.97 37.55, 126.98 37.56)"}' > body.json
   $ xxd body.json | head -1
   00000000: 7b22 6e61 6d65 223a 22ed 858c ec8a a4ed  {"name":".......
   ```
   `ed 85 8c`는 "테"의 정상 3바이트 UTF-8 시퀀스 — argv가 아니라 파일 자체가 이미 UTF-8 클린임을
   확인.
2. 그 파일을 `curl --data-binary @body.json`으로 전송(inline `-d` argv를 완전히 우회):
   ```
   $ curl -s -w "\nHTTP_STATUS:%{http_code}\n" -X POST http://localhost:8080/api/streams \
     -H 'Content-Type: application/json' -H 'X-Internal-Key: dev-internal-key-change-me' \
     --data-binary "@body.json"
   {"id":4,"name":"테스트 하천","location":"LINESTRING(126.97 37.55, 126.98 37.56)","created_at":"2026-08-28T11:43:02.578634514Z"}
   HTTP_STATUS:201
   ```
   `201`, 이름이 "테스트 하천"으로 정확히 왕복됨.
3. 같은 세션에서 원래의 inline `-d '...'` 커맨드를 그대로 재실행 — 동일하게 재현:
   ```
   $ curl -s -w "\nHTTP_STATUS:%{http_code}\n" -X POST http://localhost:8080/api/streams \
     -H 'Content-Type: application/json' -H 'X-Internal-Key: dev-internal-key-change-me' \
     -d '{"name":"테스트 하천","location":"LINESTRING(126.97 37.55, 126.98 37.56)"}'
   {"timestamp":"2026-08-28T11:43:09.879+00:00","status":400,"error":"Bad Request","path":"/api/streams"}
   HTTP_STATUS:400
   ```
   백엔드 로그도 동일: `JSON parse error: Invalid UTF-8 middle byte 0xd7`.

**결론**: 파일로 전달한(전송 경로가 깨끗함이 확인된) 동일 한글 내용은 `201`로 정상 처리되고
JSON 응답에도 "테스트 하천"이 정확히 되돌아온다 — 이는 **애플리케이션 버그가 아님**을 확정한다.
반면 inline `-d` argv 경로는 같은 세션에서 재현 가능하게 `400`/`0xd7`를 낸다. 다만 이 깨짐이
"Git Bash" 자체의 문제라고 단정할 수는 없다 — 컨트롤러가 제시한 반증대로 이 환경의 Git Bash
`printf`는 UTF-8 클린 argv를 만들어내며(`ed 85 8c` 그대로), 이번 판별 테스트에서 파일에 기록한
바이트도 동일하게 클린했다. 따라서 문제는 "Git Bash 일반"이 아니라 **이 Bash 툴 세션이 curl의
inline `-d` 인자를 넘기는 특정 경로**에 국한된 것으로 좁혀졌으며, 그 정확한 원인(툴 자체의 인자
전달 방식인지, curl의 인자 파싱인지)은 추가로 특정하지 않았다. `road_status: "양호"`가 응답
방향(DB → JSON)으로 한글이 깨지지 않고 나온다는 보조 증거로 여전히 유효하다 — 이번 판별
테스트는 요청 본문(HTTP 진입점) 방향의 공백을 메운 것이다.)

## Step 3: 작업 상태를 확인한다

```
$ curl -s http://localhost:3001/status/489f4485-b412-43d8-9e93-bf39d971d37a
{"jobId":"489f4485-b412-43d8-9e93-bf39d971d37a","status":"completed","progress":100,"downloaded_count":1}
```

**PASS** — 브리프 기대값과 정확히 일치.

## Step 4: MinIO에 객체가 생겼는지 본다

브라우저 콘솔 대신 `mc` CLI(minio 컨테이너 내장)로 확인 (기능적으로 동등, 실제 오브젝트/정책 조회):

```
$ docker exec stream-minio mc ls -r local/captures
[2026-08-28 11:34:51 UTC]  34KiB STANDARD captures/1/1/2026-08-28T11-34-51Z.jpg
```

버킷 정책(공개 읽기) 확인:
```
$ docker exec stream-minio mc anonymous get-json local/captures
{"Statement":[{"Action":["s3:GetObject"],"Effect":"Allow","Principal":{"AWS":["*"]},"Resource":["arn:aws:s3:::captures/*"]}],"Version":"2012-10-17"}
```

인증 없이 오브젝트를 실제로 받아지는지 (별도 curl 컨테이너, `docker_stream-network`에서):
```
$ docker run --rm --network docker_stream-network curlimages/curl:latest -s -o /dev/null -w "HTTP_STATUS:%{http_code}\n" \
  "http://minio:9000/captures/captures/1/1/2026-08-28T11-34-51Z.jpg"
HTTP_STATUS:200
```
(경로가 `/captures/captures/...`인 이유: 버킷명이 `captures`이고, `buildKey()`가 만드는
오브젝트 키 자체도 `captures/{streamId}/{trailId}/{iso}.jpg` 형식이라 리터럴로 `captures/`
접두사를 갖기 때문. `apps/youtube-service/src/storage.js`의 의도된 동작.)

오브젝트를 다운로드해 실제 파일 종류 확인:
```
$ curl -s -o capture.jpg "http://localhost:9000/captures/captures/1/1/2026-08-28T11-34-51Z.jpg"
$ file capture.jpg
capture.jpg: JPEG image data, JFIF standard 1.02, ... comment: "Lavc62.11.100", baseline, precision 8, 1280x720, components 3
```
이미지를 Read 툴로 열어 육안으로도 ffmpeg `testsrc` 컬러바 패턴(흑백/컬러 세로 막대 + 그라디언트
바 + 카운터 박스)을 확인했다 — 다만 이 육안 확인 자체는 재현 가능한 아티팩트로 남지 않는다.
결론을 실제로 뒷받침하는 것은 위에 이미 기록된 재현 가능한 사실들이다: 유효한 JPEG(`file` 커맨드
확인), 해상도가 정확히 `1280x720`(ffmpeg `testsrc` 기본 해상도와 일치), 파일 크기 34KiB가
색상 대비가 큰 컬러바 패턴의 전형적인 JPEG 압축 크기 범위에 부합. 육안 확인은 이 정황 증거에
대한 보강일 뿐, 그 자체가 독립적인 증거는 아니다.

**PASS**

## Step 5: 파이프라인 끝까지 흘렀는지 확인한다

```
$ curl -s -w "\nHTTP_STATUS:%{http_code}\n" "http://localhost:8080/api/captures?trail_id=1&limit=5"
[{"id":1,"confidence":0.95,"trail_id":1,"stream_id":1,"image_path":"captures/1/1/2026-08-28T11-34-51Z.jpg","road_status":"양호","created_at":"2026-08-28T11:34:51.098123Z","updated_at":"2026-08-28T11:34:51.098123Z"}]
HTTP_STATUS:200
```

**PASS** — `image_path`가 MinIO 오브젝트 키(`captures/1/1/2026-08-28T11-34-51Z.jpg`)이고 하드코딩된
가짜(`/images/capture_001.jpg`)가 아님을 확인. `road_status="양호"`, `confidence=0.95` — ml-service
고정값 그대로, 브리프 기대와 일치.

## Step 6: 실패 경로를 확인한다

```
$ docker compose --env-file ../../.env stop kafka
 Container stream-kafka Stopping
 Container stream-kafka Stopped

$ curl -s -w "\nHTTP_STATUS:%{http_code}\n" -X POST http://localhost:3001/download -H 'Content-Type: application/json' \
  -d '{"stream_id":1,"trail_id":1,"youtube_url":"x"}'
{"jobId":"628e7ae8-6104-49ac-829d-c24db591dcb2","status":"queued","stream_id":1,"trail_id":1,"youtube_url":"x"}
HTTP_STATUS:202
```

상태 폴링 (5초 간격, kafkajs 재시도 때문에 처리에 약 30초 소요):
```
attempt 1~5: {"jobId":"...","status":"processing","progress":0,"downloaded_count":0}
attempt 6:   {"jobId":"628e7ae8-6104-49ac-829d-c24db591dcb2","status":"failed","progress":0,"downloaded_count":0,"error":"publish failed: Connection timeout"}
```

**PASS** — `status="failed"`, `error`가 정확히 `publish failed: `로 시작.

youtube-service 컨테이너 생존 확인:
```
$ docker ps --format "{{.Names}}\t{{.Status}}" | grep stream-youtube
stream-youtube	Up 3 minutes

$ docker inspect stream-youtube --format='RestartCount: {{.RestartCount}}, Status: {{.State.Status}}, StartedAt: {{.State.StartedAt}}'
RestartCount: 0, Status: running, StartedAt: 2026-08-28T11:33:33.157912954Z
```
(`StartedAt`이 최초 기동 시각 그대로이고 `RestartCount: 0` — 실패 이후에도 프로세스가 재시작되지
않고 계속 살아 있었음을 확인.)

살아있는 상태에서 새 요청도 정상 접수됨을 추가 확인:
```
$ curl -s -w "\nHTTP_STATUS:%{http_code}\n" -X POST http://localhost:3001/download -H 'Content-Type: application/json' \
  -d '{"stream_id":1,"trail_id":1,"youtube_url":"still-alive-check"}'
{"jobId":"d710a256-d3c0-4212-81ac-421ff2d97553","status":"queued", ...}
HTTP_STATUS:202
```

Kafka 재기동:
```
$ docker compose --env-file ../../.env start kafka
 Container stream-kafka Starting
 Container stream-kafka Started
```

로그(`docker compose logs youtube-service`)에는 kafkajs 연결 에러가 다수 기록되어 있다. "크래시가
없었다"는 결론은 로그에 크래시 흔적이 없다는 것으로 뒷받침하는 것이 아니라(로그 부재는 반증이
안 됨), 위에서 이미 확인한 컨테이너 상태 증거 — `RestartCount: 0`, `StartedAt`이 최초 기동
시각 그대로 — 가 뒷받침한다. Kafka 재기동 후 "still-alive-check" 잡은 재시도 끝에 발행에 성공함:
```
stream-youtube  | [kafka] 메시지 발행 → image.downloaded: {
stream-youtube  |   imageId: 'd710a256-d3c0-4212-81ac-421ff2d97553',
stream-youtube  |   streamId: 1, trailId: 1,
stream-youtube  |   imagePath: 'captures/1/1/2026-08-28T11-37-02Z.jpg',
stream-youtube  |   timestamp: '2026-08-28T11:37:02.087Z'
stream-youtube  | }
```

**PASS** — "비동기 캡처 실패가 프로세스를 내리지 않는다"는 설계의 핵심 주장이 실제로 지켜짐을 확인.

## 종합

전체 6단계 모두 PASS. 캡처 → MinIO 업로드 → Kafka 발행 → ml-service → writer → PostgreSQL →
reader → backend `GET /api/captures`까지 실물 파이프라인이 관통했고, `image_path`는 하드코딩된
값이 아니라 실제 MinIO 키다. Kafka 장애 시 작업은 `failed`로 정확히 기록되고 컨테이너는 죽지
않는다.

확인 안 된 항목: 없음 (브리프의 모든 Step을 CLI/API로 직접 확인했다. Step 4의 "브라우저에서
콘솔 접속"만 `mc` CLI + 익명 curl 다운로드로 대체 — 접근 방식이 다를 뿐 검증하는 사실은 동일함).
Step 2의 한글 요청 본문 `400`은 판별 테스트(`--data-binary @file`)로 애플리케이션 버그가 아님을
확정했으나, inline argv 경로가 왜 깨지는지 그 자체의 원인은 미상으로 남아 있다 — 이 저장소의
실제 API 클라이언트(브라우저 fetch, 서비스 간 HTTP 호출)는 inline 쉘 argv를 거치지 않으므로
운영 경로에는 영향이 없다고 판단한다.
