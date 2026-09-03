# 캡처 트리거 실기동 검증

브랜치 `feature/capture-trigger`, 커밋 `78ec7cb` 기준. Docker Compose로 9개 컨테이너를
전부 띄우고 게이트웨이만 호출해서 확인했다. 자동화 테스트는 youtube-service를
`MockRestServiceServer`로 대신하므로, 두 서비스 사이에 실제로 오가는 JSON과
`X-Internal-Key` 기본값, 컨테이너 포트(3000/3001) 배선은 여기서만 증명된다.

## 준비

`bash infra/scripts/dev-up.sh` 후 stream/trail을 하나씩 만들었다.
`stream_id=1`, `trail_id=6` (`CAM-TRIGGER-1`). `CAPTURE_SOURCE`는 `.env`의
기본값 `testsrc`, `INTERNAL_API_KEY`는 `.env`에 없어 compose 기본값
`dev-internal-key-change-me`가 쓰였다.

UTF-8 본문은 파일로 만들어 `--data-binary @file`로 보냈다. 인라인 `-d`는
이 셸 세션에서 한글이 깨진 전례가 있다.

## 결과

| # | 확인 | 결과 |
|---|------|------|
| 1 | 잘못된 `X-Internal-Key` | **401** `{"error":"Unauthorized","message":"Invalid or missing X-Internal-Key"}` |
| 2 | `source_url: "file:///etc/passwd"` | **400** `{"error":"Invalid capture job request","message":"source_url must be an http(s) or rtsp(s) URL"}` |
| 3 | `source_url` 누락 | **400** `{"...","message":"source_url is required"}` |
| 4 | 정상 트리거 | **202** `{"jobId":"9f20df7e-...","status":"queued"}` |
| 5 | `GET /api/captures/jobs/{jobId}` | **200** `{"jobId":"9f20df7e-...","status":"completed","progress":100,"downloaded_count":1}` |
| 6 | 없는 jobId | **404** `{"error":"Capture job not found","jobId":"nope-not-real"}` |
| 7 | `GET /api/captures?trail_id=6` | **200** `image_path: "captures/1/6/2026-09-03T01-15-42Z.jpg"`, `road_status: "양호"` |
| 8 | 이미지 익명 접근 | **200** `image/jpeg` 34,878바이트 |
| 9 | youtube-service 정지 후 트리거 | **502** `{"error":"Capture service unavailable","message":"...HTTP connect timed out"}` |
| 10 | 정지 상태에서 상태 조회 | **502** 같은 형태 |
| — | backend 생존 | `RestartCount 0`, `running` |

## 확인된 것

**게이트웨이 한 번 호출로 파이프라인 전체가 돈다.** 4번에서 받은 jobId
`9f20df7e-...`가 youtube-service 로그의 Kafka 발행 메시지에 그대로 나타나고
(`imageId: '9f20df7e-...', streamId: 1, trailId: 6`), 같은 이미지가 7번의
`GET /api/captures`에 행으로 나온다. backend → youtube-service(HTTP) →
Kafka → ml-service → writer → PostgreSQL → reader → backend가 한 번에 이어졌다.

**`status`의 실제 초기값은 `queued`다.** 리뷰가 지적한 대로 `jobs.js`가 심는 값이며
`pending`이 아니다. 명세와 테스트 픽스처를 이 값으로 고친 것이 맞았다.

**`source_url` 스킴 제한이 실제로 막는다**(2번). 자동화 테스트가 확인하는 것과
같은 동작이 실기동에서도 나온다.

**다운스트림이 죽어도 게이트웨이는 502를 내고 살아남는다**(9·10번). 202 이후
비동기로 도는 캡처와 달리 이 두 호출은 동기라 게이트웨이가 직접 응답을 만든다.

## 검증 중 알게 된 것 — 객체 키가 버킷 이름을 한 번 더 갖는다

8번에서 `http://localhost:9000/captures/1/6/...jpg`로 요청했다가 404를 받았다.
올바른 주소는 `http://localhost:9000/captures/captures/1/6/...jpg`다.
버킷이 `captures`이고 `buildKey`가 만드는 키도 `captures/`로 시작하기 때문이다
(`apps/youtube-service/src/storage.js:15`).

동작에 문제는 없다 — `image_path`는 객체 키이고 URL은 조회 시점에
`<endpoint>/<bucket>/<key>`로 조립하기로 한 설계 그대로다. 다만 이 검증을
하면서 실제로 한 번 틀렸고, 프론트엔드에서 URL을 조립할 때 같은 실수가 나기
쉽다. 후속 항목으로 기록한다(이 브랜치의 문제가 아니라 캡처 구현 때부터 있던
것이므로 여기서 고치지 않는다).
