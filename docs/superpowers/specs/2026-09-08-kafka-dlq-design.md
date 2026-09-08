# Kafka 컨슈머 DLQ 설계

**작성일:** 2026-09-08
**브랜치:** `feature/kafka-dlq`
**관련 문서:** `2026-08-27-youtube-capture-design.md`의 "후속 작업"에서 DLQ를 제외 항목으로 남겨둔 것을 이제 다룬다

## 배경

두 컨슈머가 실패한 메시지를 **로그만 남기고 버린다.**

```python
# apps/ml-service/main.py
except Exception:
    log.exception("메시지 처리 실패, 건너뜀")
```

```java
// services/writer/.../ImageAnalyzedConsumer.java
} catch (Exception e) {
    log.error("처리 실패, 메시지를 건너뛴다", e);
}
```

둘 다 의도적으로 넓게 잡는다. 잘못된 메시지 하나가 컨슈머 전체를 멈추게 두지 않으려는 것이고 그 판단은 옳다. 문제는 **그다음이 없다는 것**이다.

지금 상태에서 writer의 DB 저장이 실패하면 이런 일이 벌어진다. youtube-service가 프레임을 떠서 MinIO에 올리고, ml-service가 분석 결과를 발행하고, writer가 그걸 받아 저장에 실패한다. MinIO에는 이미지가 있는데 `captures` 테이블에는 행이 없다. **아무도 모른다.** 로그를 사람이 들여다보기 전까지는 유실됐다는 사실 자체가 드러나지 않는다.

파이프라인의 정상 경로는 실기동으로 관통 검증까지 마쳤는데(`2026-09-03-capture-trigger-verification.md`) 실패 경로만 비어 있다.

## 목표

실패한 메시지를 **원문 그대로, 실패 사유와 함께** 별도 토픽에 남긴다. 사람이 나중에 무엇이 왜 실패했는지 알 수 있으면 이 작업은 끝이다.

## 범위

**포함:** 두 컨슈머의 DLQ 발행, 공통 페이로드 계약, 관련 테스트, 실기동 검증.

**제외:**

- **재시도·백오프** — 일시 실패를 자동으로 다시 시도하는 것. 오프셋 커밋 시점과 순서 보장을 같이 봐야 해서 이 작업의 몇 배가 된다
- **리드라이브** — DLQ를 원본 토픽으로 되돌려보내는 경로. 쓰기 경로라 인증·중복 방지 설계가 따라붙는다
- **알림** — 실패가 쌓이면 사람을 부르는 것
- **DLQ 조회 API** — 게이트웨이에 엔드포인트를 내는 것
- **`image.analyzed` 발행의 `send` → `send_and_wait` 전환** — 아래 "후속 작업" 참고

---

## 결정 사항

브레인스토밍에서 하나씩 확정했다. 근거와 기각한 대안을 함께 남긴다.

### 1. DLQ는 "유실 방지 + 진단"까지만 한다

재처리는 사람이 판단한다. 자동 재시도도, 리드라이브 도구도 넣지 않는다.

**근거:** 지금 아픈 곳은 "무언가 사라지는데 아무도 모른다"는 것 하나다. 재시도를 붙이면 오프셋 커밋 시점, 순서 보장, 중복 처리를 전부 설계해야 하고, 그건 지금 없는 문제를 위해 지금 있는 문제를 미루는 일이 된다.

**기각한 대안:** "재시도 N번 후 DLQ"는 Spring 쪽과 aiokafka 쪽의 구현 난이도가 심하게 비대칭이다(전자는 `DefaultErrorHandler` 한 줄, 후자는 수작업). 같은 정책을 두 서비스가 다른 신뢰도로 구현하게 된다.

### 2. DLQ 토픽은 원본 토픽별로 나눈다

| 원본 | DLQ |
|---|---|
| `image.downloaded` | `image.downloaded.dlq` |
| `image.analyzed` | `image.analyzed.dlq` |

**근거:** 한 토픽 안의 메시지 모양이 균일해서 읽기 쉽고, 나중에 재처리할 때 되돌려보낼 곳이 토픽 이름에 드러난다. Kafka 관례이기도 하다.

**기각한 대안:** `dead-letter` 하나로 합치고 본문의 `source`로 구분하는 방식. 볼 곳이 한 군데라 편하지만 모양이 섞이고, 재처리 대상을 알려면 본문을 열어야 한다.

### 3. 모든 실패를 DLQ로 보내고 `reason`으로 구분한다

깨진 JSON(영구 실패)이든 DB 장애(일시 실패)든 전부 DLQ로 간다.

**받아들이는 대가를 명시한다:** DB가 5분 죽으면 그동안 들어온 **정상 메시지가 전부 DLQ로 간다.** 재시도가 없으니 당연한 귀결이다. 대신 유실은 0이고, `reason`을 보면 "DB 장애로 밀린 정상 메시지"라는 게 드러나므로 사람이 골라낼 수 있다.

**기각한 대안:** 영구 실패만 DLQ로 보내고 일시 실패는 오프셋을 커밋하지 않아 재전달에 맡기는 방식. 정확하지만 예외를 두 부류로 나누는 기준이 필요하고, 잘못 분류하면 무한 재시도로 컨슈머가 막힌다. 그 기준을 지금 신뢰할 만큼 실패 사례를 모으지 못했다.

### 4. DLQ 발행 자체가 실패하면 ERROR 로그만 남기고 계속한다

**근거:** DLQ 발행이 실패하는 상황은 대개 브로커가 죽은 것이고, 그러면 새 메시지도 들어오지 않는다. 여기서 컨슈머를 멈춰도 얻는 게 없다.

로그에는 **원본 메시지를 통째로** 찍는다. 마지막 흔적이기 때문이다.

**기각한 대안:** 예외를 다시 던져 컨슈머를 멈추는 방식. ml-service는 `async for` 루프만 죽고 FastAPI 프로세스는 살아남아 **헬스체크는 통과하는데 아무것도 안 먹는 상태**가 된다. 그걸 막으려면 `/health`가 컨슈머 상태를 반영하도록 고쳐야 하는데, 이 작업의 범위를 넘는다.

### 5. 발행 헬퍼를 각 서비스에 직접 둔다 (프레임워크 기능 대신)

writer도 Spring의 `DeadLetterPublishingRecoverer`를 쓰지 않고 ml-service와 같은 모양의 헬퍼를 직접 만든다.

**근거:** Spring은 실패 사유를 **Kafka 헤더**(`kafka_dlt-exception-message` 등)에 넣고 본문은 원본 그대로 둔다. ml-service는 본문에 담는다. 그대로 두면 두 DLQ를 **다른 방법으로** 읽어야 하고, "전부 DLQ + `reason`으로 구분"이라는 3번 결정이 한쪽에서만 성립한다.

이 프로젝트에는 같은 종류의 전례가 있다. reader와 writer가 Redis 캐시에 **서로 다른 페이로드 모양**을 쓰다가 최종 리뷰에서야 발견된 적이 있다(`CaptureView`를 `packages/shared`로 옮겨 해결). 진단용 데이터일수록 읽는 쪽의 단순함이 중요하다.

**대가:** 손으로 쓴 코드 20~30줄이 늘어난다. 재시도·백오프를 붙이는 날이 오면 그때 프레임워크 기능으로 옮기는 게 맞고, 그때는 헤더 방식이 오히려 자연스럽다.

---

## 구조

새 파일 둘, 수정 셋.

| 파일 | 역할 |
|---|---|
| `apps/ml-service/dlq.py` (신규) | `build_dead_letter()`, `publish_dead_letter()` |
| `services/writer/src/main/java/com/stream/writer/consumer/DeadLetterPublisher.java` (신규) | 같은 일을 하는 Spring 컴포넌트 |
| `apps/ml-service/main.py` | `except` 블록에서 발행 호출 |
| `services/writer/.../consumer/ImageAnalyzedConsumer.java` | `catch` 블록에서 발행 호출 |
| `services/writer/src/main/resources/application.yaml` | 프로듀서 직렬화 설정 추가 |

**컨슈머는 DLQ 토픽 이름을 모른다.** 발행자가 `source + ".dlq"`로 만든다. 규칙이 한 곳에만 있어야 토픽이 늘어도 어긋나지 않는다.

`packages/shared`에 계약을 두지 않는 이유는 한쪽이 Python이라 공유가 불가능하기 때문이다. 대신 이 문서와 양쪽 테스트가 계약을 지킨다.

## 페이로드 (두 서비스 동일)

```json
{
  "source": "image.analyzed",
  "consumer": "writer",
  "failedAt": "2026-09-08T09:12:44.512Z",
  "reason": "DataIntegrityViolationException: trails_stream_id_fkey",
  "payload": "{\"trailId\":7,\"streamId\":4,...}"
}
```

| 필드 | 규칙 |
|---|---|
| `source` | **구독 중인** 원본 토픽 이름. ml-service가 `image.analyzed` 발행에 실패한 경우에도 `source`는 `image.downloaded`다 — 되돌려보낼 곳은 그 메시지를 받은 토픽이기 때문이다 |
| `consumer` | `ml` 또는 `writer`. 누가 실패했는지 |
| `failedAt` | UTC, `Z` 접미사. Java `Instant.now()`, Python `datetime.now(timezone.utc)` |
| `reason` | `예외타입: 메시지` 한 줄. **500자에서 자른다** |
| `payload` | **원본 메시지 문자열 그대로** |

**`payload`가 문자열인 이유:** 깨진 JSON이 실패 원인의 하나다. 파싱해서 객체로 담으면 정작 담아야 할 경우를 담을 수 없다. ml-service는 `msg.value`가 bytes이므로 `decode("utf-8", errors="replace")`로 문자열화한다 — 디코딩 실패로 DLQ 발행이 죽으면 본말전도다.

**`reason`에 스택트레이스를 넣지 않는 이유:** 그건 로그의 몫이다. DLQ 메시지가 스택으로 부풀면 진단용으로 읽기 나빠진다.

필드는 camelCase다. 기존 Kafka 메시지(`trailId`, `imagePath`)와 같은 규칙이다.

## 에러 처리 세부

### ml-service

`except` 블록에서 DLQ로 보내고, 그 발행은 자체 `try/except`로 감싼다.

**`send_and_wait()`를 쓴다.** aiokafka의 `send()`는 버퍼에 넣는 것까지만 기다리므로 브로커가 죽어도 그 자리에서 예외가 안 날 수 있다. DLQ 발행은 실패가 실제로 드러나야 4번 결정(실패 시 ERROR 로그)이 성립한다.

### writer

`catch (Exception e)`에서 `deadLetterPublisher.publish(...)`를 부른다. 발행 실패는 `whenComplete`로 받아 로그만 남긴다.

**`.get()`으로 기다리지 않는다.** DB가 죽어 메시지가 쏟아질 때 리스너 스레드가 메시지마다 몇 초씩 묶이면 안 된다.

**오프셋은 그대로 커밋된다.** 리스너가 정상 반환하기 때문이다. DLQ에 남겼으므로 원본 토픽에서 다시 받을 이유가 없다. 1번 결정의 귀결이다.

`application.yaml`에 프로듀서 직렬화를 **명시한다.** 이 저장소에서 writer가 프로듀서를 갖는 것은 처음이라(`KafkaTemplate` 사용처가 하나도 없다) 기본값에 기대지 않는다.

## 테스트

TDD로 간다. RED를 먼저 확인하고 구현한다.

**ml-service (pytest, 기존 16개 위에)**

- `build_dead_letter()` 순수 함수 — `reason` 포맷, 500자 절단, 디코딩 안 되는 bytes에서도 문자열이 나오는지
- 깨진 JSON 수신 → `image.downloaded.dlq`로 발행되는지
- **그리고 뒤이은 정상 메시지가 계속 처리되는지** (루프가 죽지 않는 것이 기존 계약이다)
- DLQ 발행이 실패해도 예외가 루프 밖으로 새지 않는지

**writer (JUnit)**

- `DeadLetterPublisherTest` — 토픽 이름이 `source + ".dlq"`인지, JSON 필드가 맞는지, 발행 실패 시 예외가 안 새는지
- `ImageAnalyzedConsumerTest` — 핸들러가 던지면 **원문 그대로** 발행자에 넘어가는지

테스트는 `./mvnw clean test`로 돌린다. `clean` 없이 돌리면 삭제된 소스의 `.class`가 남아 개수가 부풀려 보고된 전례가 있다.

## 실기동 검증

이 프로젝트에서 Docker 검증을 미루면 안 된다는 것은 규칙이다. 유닛 테스트 176개가 GREEN인 상태에서 Docker 빌드 자체가 깨져 있던 적이 있다.

1. 스택을 띄우고 `image.downloaded`에 **깨진 JSON**을 직접 넣어 `image.downloaded.dlq`에서 읽는다
2. `image.analyzed`에 **없는 `stream_id`**를 담은 정상 JSON을 넣어 writer의 FK 위반을 유발하고 `image.analyzed.dlq`에서 읽는다
   — 파싱 실패만 확인하면 "DB 장애도 DLQ로 간다"는 3번 결정이 검증되지 않는다
3. `.dlq` 토픽이 자동 생성되는지 확인한다(현재 브로커는 자동 생성이 켜져 있고 기존 토픽 둘도 그렇게 생겼다). 명시 생성이 필요하면 그때 판단한다
4. DLQ 발행 실패 경로 — 브로커를 내린 상태에서 컨슈머가 죽지 않고 ERROR 로그에 원문이 남는지

## 후속 작업

- **`image.analyzed` 발행도 `send_and_wait()`로** — 지금 `send()`라 발행 실패가 조용히 지나갈 수 있다. 이번 범위 밖이지만 같은 성격의 유실 경로다
- **재시도·백오프** — 실패 사례가 쌓여 일시/영구 구분 기준이 서면
- **DLQ 적재 알림** — 지금은 사람이 토픽을 들여다봐야 안다
- **ml-service `/health`가 컨슈머 상태를 반영하도록** — 4번 결정에서 드러난 갭. 루프가 죽어도 헬스체크는 통과한다

## 리스크

- **writer 프로듀서가 처음 도입된다.** 스프링 컨텍스트 로딩과 직렬화 설정을 실기동으로 확인해야 한다
- **DLQ가 조용히 쌓인다.** 알림이 없으므로 "유실은 막았지만 아무도 안 본다"가 될 수 있다. 후속 작업으로 남긴다
