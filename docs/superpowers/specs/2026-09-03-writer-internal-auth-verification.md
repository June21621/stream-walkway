# writer 내부 API 인증 실기동 검증

브랜치 `fix/writer-internal-auth`, 커밋 `a9afa31` 기준. Docker Compose로 9개 컨테이너를
띄우고 확인했다. 호스트 포트는 이 환경의 `.env` 값을 따른다(backend 8080,
writer 18002, reader 18001 — compose 기본값은 8080/8002/8001).

## 우회가 실제로 가능했다는 근거

리뷰가 지적한 우회를 이 저장소에서 재현했다. `StreamControllerTest`에
`MockMvc`로 `POST /%69nternal/streams`를 키 없이 보내자 401이 아니라
**`StreamCommandHandler.handle`이 호출됐다**(스텁이 없어 NPE로 드러남).
필터를 지나쳐 핸들러까지 닿은 것이다. 이 테스트는 회귀 방지용으로 남겼다.

원인은 `shouldNotFilter`가 `getRequestURI()`에 접두사 비교를 한 것이다.
`getRequestURI()`는 디코딩되지 않은 원본을 주는데 Spring MVC는 디코딩된
경로로 라우팅한다. 보안 검사가 라우터와 다른 문자열을 읽고 있었다.

## 결과

| # | 확인 | 결과 |
|---|------|------|
| 1 | writer 직접, 키 없음 | **401** `{"error":"Unauthorized","message":"Invalid or missing X-Internal-Key"}` |
| 2 | writer 직접, `POST /%69nternal/streams` (`curl --path-as-is`) | **401** — 우회 차단 확인 |
| 3 | writer 직접, 잘못된 키 | **401** |
| 4 | writer `/health`, 키 없음 | **200** `{"service":"writer","status":"ok"}` |
| 5 | 게이트웨이 → writer, `POST /api/streams` | **201** `id=6` |
| 6 | 게이트웨이 → writer, `POST /api/trails` | **201** `id=7` |
| 7 | 게이트웨이, 잘못된 키 | **401** |
| 8 | reader 직접 조회, 키 없음 | **200** (의도대로 열려 있음) |
| 9 | 게이트웨이 조회 | **200** |
| 10 | 캡처 트리거 (회귀) | **202** `{"jobId":"69a313f5-...","status":"queued"}` |
| 11 | 파이프라인 끝 | **200** `image_path: captures/1/7/2026-09-03T01-45-00Z.jpg` |
| 12 | `INTERNAL_API_KEY` 없이 `docker compose config` | **exit 1**, `required variable INTERNAL_API_KEY is missing a value` |

**1~3번 전후로 `streams` 행 수가 5에서 변하지 않았다.** 401만 확인하면 핸들러가
이미 쓴 뒤에 401을 붙였어도 통과하므로 행 수를 같이 봤다.

## 확인된 것

**우회가 막혔고 정상 경로는 살아있다.** 이 둘을 함께 봐야 의미가 있다 —
1~3번만 보면 writer를 통째로 막아버려도 통과하고, 5~6번만 보면 필터가 아무것도
안 해도 통과한다. 5·6번이 201을 내는 것은 backend의 `writerRestClient`가
`defaultHeader`로 실은 키가 writer 필터를 실제로 통과했다는 뜻이다.

**필터가 쓰기 경로를 회귀시키지 않았다.** 10·11번에서 캡처 파이프라인이 그대로
돈다. 이 경로의 DB 쓰기는 Kafka 컨슈머가 하므로 HTTP 필터와 무관한데, 그 사실
자체가 아래 한계와 이어진다.

**compose가 닫힌 채로 실패한다**(12번). 값을 안 주면 기동 자체가 거부된다.
저장소에 있는 기본값으로 조용히 초록불이 되는 것을 막는다.

## 이 검증이 다루지 않은 것

**Kafka 경로.** `ImageAnalyzedConsumer`가 `image.analyzed`에서 읽어 캡처 행을
직접 만든다. compose가 Kafka를 `9092:9092` `PLAINTEXT`로 게시하므로 호스트에
접근할 수 있으면 키 없이 메시지를 넣어 행을 쓸 수 있다. 이번 작업은 HTTP만
다뤘다.

**같은 비밀을 양쪽이 쓴다.** 게이트웨이 쓰기 API를 쓸 자격이 있는 호출자는 이미
writer를 직접 열 자격도 가진 셈이다. 막은 것은 인증되지 않은 접근이지 침해된
클라이언트가 아니다. 상세는 `docs/architecture/ARCHITECTURE.md`의 "내부 API 인증".
