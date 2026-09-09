# Kafka DLQ 실기동 검증

브랜치 `feature/kafka-dlq`, 커밋 `366db39d913e48366da3d71d437fbea155d820d0` 기준.
`bash infra/scripts/dev-up.sh`(`docker compose ... up -d --build`)로 9개 컨테이너를
전부 새로 빌드해 띄우고, `docker exec`로 Kafka 콘솔 프로듀서/컨슈머를 직접 찔러서
확인했다. 유닛 테스트는 mock 위에서 도니까, 실제 브로커에 `.dlq` 토픽이 자동
생성되는지와 writer의 Kafka 프로듀서가 실제로 뜨는지는 여기서만 증명된다.

## 준비 — 겪은 환경 문제 두 가지

**Docker Desktop 백엔드가 기동 중 크래시했다.** 첫 `Start-Process`로 띄운 인스턴스는
`com.docker.backend.exe.log`에 `backend crashed ... initializing Ingest server:
listening on unix://.../sailor-ingest.sock: remove ...: The file cannot be accessed
by the system.`를 남기고 죽었다. 이전 세션이 비정상 종료되며 남은 AF_UNIX 소켓
리파스포인트가 원인으로 보인다. 프로세스를 모두 죽이고 `wsl --shutdown` 후 파일
삭제를 시도했지만 OS 레벨에서 계속 "The file cannot be accessed by the system"로
막혔다 — `Remove-Item`, `Rename-Item`, `icacls`, cmd `del` 전부 동일하게 실패했다.
재부팅 없이는 못 지운다고 판단했는데, Docker Desktop을 한 번 더 재시작하니
(`Start-Process`) 이번엔 크래시 없이 정상 기동했다. **원인은 못 지운 채로 우회한
것**이므로, 다음에 또 이 크래시가 나면 같은 재시도가 항상 통한다고 보장할 수 없다.

**MinIO 기본 포트(9000/9001)가 Windows 예약 포트 범위에 걸렸다.** `docker compose
up -d --build`가 이미지는 전부 새로 빌드했지만(아래 "이미지가 실제로 새로 빌드됐다"
참고) `Error response from daemon: ports are not available: exposing port TCP
0.0.0.0:9000 -> 127.0.0.1:0: ... An attempt was made to access a socket in a way
forbidden by its access permissions.`로 멈췄다. `netsh interface ipv4 show
excludedportrange protocol=tcp`로 확인하니 `8950-9049`가 Hyper-V/WSL NAT용으로
동적 예약되어 있어 9000·9001이 둘 다 그 안에 들어간다. 코드 문제가 아니라 이 머신의
현재 동적 포트 예약 상태 문제라, `.env`(git 추적 안 됨)의 `MINIO_PORT`/
`MINIO_CONSOLE_PORT`만 `19000`/`19001`로 바꿔서 우회했다. 컨테이너 내부 포트나
서비스 간 통신(`MINIO_ENDPOINT=http://minio:9000`)은 그대로다 — 호스트에서
`localhost:<port>`로 접근할 때만 영향받는다. `docker-compose.yml`은 건드리지
않았다.

## 이미지가 실제로 새로 빌드됐다

`docker compose up -d --build` 로그에서 `writer builder 12/13 COPY services/writer/src
src`와 `writer builder 13/13 RUN ./mvnw -q -B package -DskipTests`가 `CACHED`가 아니라
`DONE`(각각 0.1s, 7.8s)으로 찍혔다 — Task 3이 바꾼 `application.yaml`과
`DeadLetterPublisher`/`ImageAnalyzedConsumer`가 이미지에 실제로 반영됐다는 뜻이다.
나머지(backend/reader/ml-service/youtube-service)는 소스 미변경이라 캐시 그대로다.

## 결과

| # | 확인 | 결과 |
|---|------|------|
| 1 | writer 기동 — Kafka 프로듀서/컨슈머가 실제로 뜨는가 | `Started WriterApplication in 5.03 seconds`, `Subscribed to topic(s): image.analyzed`, `RestartCount=0`. `ConfigException`·`key.serializer` 관련 에러 없음 |
| 2 | 깨진 JSON → `image.downloaded` (ml 파싱 실패) | `image.downloaded.dlq`에서 읽힘: `{"source": "image.downloaded", "consumer": "ml", "failedAt": "2026-09-09T06:02:30.612470Z", "reason": "JSONDecodeError: Expecting property name enclosed in double quotes: line 1 column 3 (char 2)", "payload": "{ broken json"}` |
| 3 | 없는 FK/CHECK 위반 → `image.analyzed` (writer DB 실패, CHECK) | `roadStatus:"yanghof"`로 보낸 첫 메시지는 FK가 아니라 `captures_road_status_check` CHECK 제약에서 먼저 걸렸다. `image.analyzed.dlq`: `{"source":"image.analyzed","consumer":"writer","failedAt":"2026-09-09T06:02:54.532581290Z","reason":"DataIntegrityViolationException: could not execute statement [ERROR: new row for relation \"captures\" violates check constraint \"captures_road_status_check\" ... (500자에서 절단)","payload":"{\"trailId\":999999,\"streamId\":999999,\"imagePath\":\"captures/x/y.jpg\",\"roadStatus\":\"yanghof\",\"confidence\":0.95}"}` |
| 4 | 없는 FK → `image.analyzed` (writer DB 실패, FK, 브리프 지정 페이로드) | `roadStatus:"양호"` + `trailId/streamId:999999`로 보내자 FK 제약 위반으로 걸렸다. `image.analyzed.dlq`: `{"source":"image.analyzed","consumer":"writer","failedAt":"2026-09-09T06:03:05.860173682Z","reason":"DataIntegrityViolationException: could not execute statement [ERROR: insert or update on table \"captures\" violates foreign key constraint \"captures_trail_id_fkey\"\n  Detail: Key (trail_id)=(999999) is not present in table \"trails\".] ... (500자에서 절단)","payload":"{\"trailId\":999999,\"streamId\":999999,\"imagePath\":\"captures/x/y.jpg\",\"roadStatus\":\"양호\",\"confidence\":0.95}"}` — `payload`의 한글이 깨지지 않고 그대로 들어갔다 (heredoc을 통한 `docker exec` 프로듀서는 CLAUDE.md가 경고하는 `curl -d` CP949 문제를 겪지 않았다) |
| 5 | 토픽 자동 생성 | `kafka-topics.sh --list`에 `image.downloaded.dlq`, `image.analyzed.dlq` 둘 다 있음. 별도 조치 불필요 |
| 6 | 재시도(리드라이브) 파이프 | 3·4번 DLQ 레코드의 `payload`를 그대로 뽑아 `image.analyzed`로 되돌리자, 두 메시지 모두 **다시 같은 이유로 실패해 `image.analyzed.dlq`에 레코드가 2개 더 쌓였다**(총 2 → 4개). `payload`를 원문 문자열로 저장한 결정 덕분에 파싱 없이 그대로 재발행할 수 있었다 — 영구 실패(없는 FK)는 되돌려도 튕긴다는 운영 주의사항이 실물로 확인됐다 |

## Step 7 — 정상 경로

`python infra/scripts/seed-demo-data.py`로 하천 3개/관측 지점 9개를 등록했고,
`curl -s "http://localhost:8080/api/captures?limit=5"`가 기존 캡처 3건을 **200**으로
그대로 반환했다. 이 과정에서 `image.analyzed.dlq`(4건)와 `image.downloaded.dlq`(1건)
레코드 수가 늘지 않았다 — DLQ를 붙이면서 정상 흐름에 부작용을 만들지 않았다.

## Step 8 — 브로커 정지

`docker stop stream-kafka` 후 20초 뒤 `stream-ml`, `stream-writer` 모두 `Up`,
`RestartCount=0` 그대로였다. 로그에는 재연결 시도가 찍혔다:

- ml-service: `Unable connect to node with id 1: [Errno 111] Connect call failed
  ('172.18.0.2', 9092)`, `Failed fetch messages from 1: NodeNotReadyError: Attempt to
  send a request to node which is not ready (node id 1).`
- writer: `Disconnecting from node 1 due to socket connection setup timeout`,
  `org.apache.kafka.common.errors.DisconnectException: null`

**알려진 갭 확인**: 브로커가 끊긴 상태에서 `curl http://localhost:5001/health`는
여전히 `{"status":"healthy","model":"loaded","uptime_sec":318.28}`를 반환했다.
컨슈머 루프가 죽어도(또는 재연결을 못 해도) `/health`는 그 상태를 반영하지 않는다.
스펙의 후속 작업 항목이라 이번에 고치지 않았다.

`docker start stream-kafka` 후 15초 만에 `healthy`로 복귀했고, writer 로그에
`Discovered group coordinator kafka:9092`가 다시 찍히며 재연결을 확인했다.

## 확인된 것

**두 DLQ 토픽에서 같은 모양의 5필드 JSON이 읽힌다.** `source`/`consumer`/
`failedAt`/`reason`/`payload` 순서와 이름이 ml-service(Python)와 writer(Java)
양쪽에서 동일하다.

**파싱 실패(ml)와 DB 실패(writer) 두 경로 모두 확인했다.** DB 실패는 계획에 없던
CHECK 제약 위반까지 우연히 함께 확인됐다 — `DataIntegrityViolationException`이
FK 위반뿐 아니라 CHECK 위반에서도 같은 방식으로 DLQ로 간다.

**`reason`은 정확히 500자에서 절단된다.** 두 writer DLQ 레코드 모두 `len(reason)
== 500`을 파이썬으로 직접 세어 확인했다.

**`payload`가 원문 문자열이라 리드라이브가 파싱 없이 그대로 된다.** Step 6에서
직접 증명했다 — 한글 필드(`roadStatus:"양호"`)를 포함한 원문도 깨지지 않고
왕복했다.

## 검증 중 알게 된 것 — 이 세션에 한정된 두 가지 환경 문제

위 "준비" 절의 Docker Desktop 크래시와 MinIO 포트 충돌은 **DLQ 코드와 무관한 이
Windows 머신의 상태 문제**다. 재현 조건(오래 꺼져 있던 Docker Desktop, Hyper-V의
동적 포트 예약 상태)이 다음 세션엔 다를 수 있으므로, 다음에 이 저장소를 실기동
검증할 때 같은 증상이 안 나올 수도 있고 또 날 수도 있다. `docker-compose.yml`의
MinIO 포트를 고정으로 바꾸지 않은 이유이기도 하다 — `.env`의 `MINIO_PORT`/
`MINIO_CONSOLE_PORT` 오버라이드로 이미 대응 가능하게 설계되어 있었다.
