# backend (API 게이트웨이) 작업 규약

외부 요청을 받아 reader/writer를 HTTP로 호출하고 오케스트레이션한다.

**DB에 직접 접근하지 않는다.** JPA 엔티티도 레포지토리도 여기 두지 말 것.
이 경계 덕분에 DB 계층과 게이트웨이 계층을 따로 개발·머지할 수 있다.

---

## `model/` 패키지는 API 경계 변환기다

`backend.model.Stream` / `Trail` / `Capture`는 "중복 DTO"처럼 보이지만 아니다.
`@JsonProperty`로 **snake_case 외부 계약 ↔ camelCase 내부 계약**을 변환한다.

```java
@JsonProperty("stream_id")
private Long streamId;
```

**지우면 공개 API 필드명이 바뀐다.** 중복 제거 대상이 아니다.

## 인증

쓰기 엔드포인트(`POST /api/streams`, `/api/trails`, `/api/captures/jobs`)는
`X-Internal-Key`를 요구한다. 검사는 컨트롤러에 있다.

**이 때문에 브라우저에서 쓰기 API를 못 부른다.** 프론트엔드가 정적 사이트라
공유 비밀을 박을 수 없다. 관리 기능이 필요하면 별도 경로를 설계해야 한다.

## 입력 검증은 다운스트림에 미루지 않는다

`source_url`은 `^(?:https?|rtsps?)://.+` 로 스킴을 제한한다.

이게 없으면 youtube-service의 `ffmpeg -i`까지 그대로 흘러가는데,
거기엔 `-protocol_whitelist`가 없어서 `file:` / `concat:`이 통한다.
**임의 파일을 읽어 그 프레임이 공개 버킷에 올라간다.** 게이트웨이에서 막을 것.

## 에러 변환 규칙

| 상황 | 응답 |
|---|---|
| 클라이언트 입력이 잘못됨 | 400 |
| 다운스트림이 계약을 어김 | **502** |

다운스트림 400을 클라이언트 400으로 그대로 옮기지 말 것. 계약 불일치는
서버 버그이고, 본문을 인용하면 내부 필드명이 새어나간다(실제로 `youtube_url is required`가 노출됐다).

**알려진 갭:** 에러 본문이 중첩돼 있다. 다운스트림 JSON이 문자열 안에
이스케이프된 채로 들어간다.

```json
{"error":"Invalid trail data","message":"Writer rejected...: {\"error\":\"...\"}"}
```

화면에 그대로 띄우면 이스케이프된 JSON이 노출된다. 아직 안 고쳤다.

## 다운스트림 호출

`RestClient`를 쓴다(`HttpClientConfig`). 타임아웃이 설정돼 있으니 지우지 말 것.

`hibernate-spatial`은 여기서 `provided`다. DB를 안 만지므로 런타임에 필요 없다.

테스트에서 `uri(String, Object...)` 오버로드를 모킹한 곳이 있다.
`UriBuilder` 람다로 바꾸면 **다른 오버로드**라 모킹이 깨진다.

## 테스트

```bash
./mvnw clean test
```

`clean` 필수. 컨텍스트 로드 실패는 대개 빈 누락이다 —
`CaptureServiceImpl`이 없어서 부팅부터 실패한 적이 있다.
