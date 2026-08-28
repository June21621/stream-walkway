# ml-service RED 13개 해소 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `apps/ml-service`의 RED 테스트 13개를 GREEN으로 전환한다 — `GET /health` 응답 형식, `POST /analyze` 신설, `consume()` 예외 처리.

**Architecture:** 단일 파일(`apps/ml-service/main.py`) 수정으로 끝난다. 새 모듈도 새 의존성도 없다. FastAPI 앱에 Pydantic 요청 모델과 엔드포인트 하나를 추가하고, 기존 Kafka 소비 루프에 메시지별 `try/except`를 두른다.

**Tech Stack:** Python 3.10, FastAPI, aiokafka, pytest / pytest-asyncio (모두 `requirements.txt`에 이미 있음)

**Spec:** 테스트 파일 자체가 명세다 — `apps/ml-service/tests/test_main.py`, `apps/ml-service/tests/test_consume.py`. 상위 계약은 `docs/api-specs/stream-walkway.postman_collection.json`의 "ML Service :5001" 폴더.

## Global Constraints

- 브랜치: `feature/ml-service-api` (`main`에서 분기)
- 파이썬 실행은 가상환경 기준. 이 저장소에는 venv가 커밋돼 있지 않으므로 각자 만들어 쓴다: `python -m venv .venv && .venv/Scripts/python.exe -m pip install -r requirements.txt`
- 테스트 실행 위치는 항상 `apps/ml-service` 디렉터리 (테스트가 `from main import app`으로 임포트한다)
- 기존 GREEN 15개를 깨뜨리지 않는다. 매 태스크 끝에 `pytest tests -q` 전체를 돌려 확인한다
- 커밋 메시지는 한글로, 무엇을/왜 했는지 포함
- **실제 ML 모델은 이번 범위 밖이다.** 분석 결과는 지금처럼 고정값을 쓴다. `model: "loaded"`는 명세가 요구하는 문자열일 뿐 실제 모델 적재 상태를 뜻하지 않는다 — 이 사실을 코드 주석에 남긴다

---

## File Structure

| 파일 | 책임 | 이번 작업 |
|---|---|---|
| `apps/ml-service/main.py` | FastAPI 앱 + Kafka 소비 루프 전체 | 수정 (유일한 수정 대상) |
| `apps/ml-service/tests/test_main.py` | `/health`, `/analyze` 계약 검증 | 수정 없음 (이미 작성됨) |
| `apps/ml-service/tests/test_consume.py` | `consume()` 동작 검증 | 수정 없음 (이미 작성됨) |

**테스트를 새로 쓰지 않는다.** 이 계획의 RED 13개는 이미 저장소에 있다. 따라서 각 태스크는 "실패 확인 → 구현 → 통과 확인 → 커밋" 순환이며, "테스트 작성" 단계가 없다. 이는 일반적인 TDD 순환과 다르지만, 테스트가 먼저 존재한다는 TDD의 본질은 지켜진다.

---

### Task 1: `GET /health` 응답 형식을 명세에 맞춘다

**Files:**
- Modify: `apps/ml-service/main.py` (현재 `health()` 함수, 파일 맨 끝)
- Test: `apps/ml-service/tests/test_main.py::TestHealth` (4개 중 3개가 RED)

**Interfaces:**
- Consumes: 없음
- Produces: 없음 (다른 태스크가 이 함수를 참조하지 않는다)

현재 구현은 `{"status": "ok", "service": "ml-service"}`를 반환한다. 명세와 테스트는 `{"status": "healthy", "model": "loaded", "uptime_sec": <number >= 0>}`를 요구한다.

`uptime_sec`은 프로세스 기동 시각을 모듈 로드 시점에 기록해두고 매 호출마다 차를 낸다. 모듈 로드 시점을 쓰는 이유는 FastAPI의 `lifespan`이 테스트에서 mock으로 대체되기 때문이다 — lifespan 안에서 시각을 기록하면 테스트에서 그 코드가 실행되지 않아 `uptime_sec`이 없다.

- [ ] **Step 1: 실패를 먼저 확인한다**

Run: `python -m pytest tests/test_main.py::TestHealth -v`

Expected: 4개 중 3개 FAIL
- `test_health_returns_200` → PASS (이미 200을 준다)
- `test_health_response_has_status_healthy` → FAIL, `assert 'ok' == 'healthy'`
- `test_health_response_has_model_field` → FAIL, `assert 'model' in body`
- `test_health_response_has_uptime_sec` → FAIL, `assert 'uptime_sec' in body`

- [ ] **Step 2: 기동 시각 기록을 추가한다**

`main.py` 상단 import 블록 바로 아래에 넣는다.

```python
import time

# 프로세스가 살아 있는 시간을 재기 위한 기준점.
# lifespan이 아니라 모듈 로드 시점에 잡는다 — 테스트는 lifespan을 mock으로
# 대체하므로 lifespan 안에서 기록하면 테스트에서 값이 생기지 않는다.
_STARTED_AT = time.monotonic()
```

`time.monotonic()`을 쓰는 이유는 시스템 시계가 조정돼도 뒤로 가지 않기 때문이다. 테스트가 `uptime_sec >= 0`을 단언하므로 음수가 나오면 안 된다.

- [ ] **Step 3: `health()`를 명세 형식으로 바꾼다**

`main.py` 맨 끝의 기존 함수를 통째로 교체한다.

```python
@app.get("/health")
def health():
    # model은 API 명세가 요구하는 고정 문자열이다. 이 서비스는 아직 실제 ML
    # 모델을 적재하지 않고 consume()이 고정값을 낸다. 진짜 모델을 붙이면
    # 이 값을 실제 적재 상태에서 끌어와야 한다.
    return {
        "status": "healthy",
        "model": "loaded",
        "uptime_sec": round(time.monotonic() - _STARTED_AT, 3),
    }
```

- [ ] **Step 4: 통과를 확인한다**

Run: `python -m pytest tests/test_main.py::TestHealth -v`

Expected: 4개 전부 PASS

- [ ] **Step 5: 전체 스위트로 회귀를 확인한다**

Run: `python -m pytest tests -q`

Expected: `10 failed, 18 passed` — 실패 10개는 Task 2가 다룰 `/analyze` 8개와 Task 3이 다룰 `consume()` 2개다. 통과는 15 → 18로 3개 늘어난다.

- [ ] **Step 6: 커밋**

```bash
git add apps/ml-service/main.py
git commit -m "fix(ml-service): GET /health 응답을 API 명세 형식으로 교체

기존 응답은 {\"status\": \"ok\", \"service\": \"ml-service\"}였는데 명세와
테스트는 {\"status\": \"healthy\", \"model\": \"loaded\", \"uptime_sec\": N}을
요구한다. TestHealth 3개가 이것 때문에 RED였다.

uptime_sec의 기준 시각은 lifespan이 아니라 모듈 로드 시점에 잡는다.
테스트가 lifespan을 mock으로 대체하기 때문에 lifespan 안에서 기록하면
테스트 경로에서 값이 생기지 않는다. 시스템 시계 조정에 영향받지 않도록
time.monotonic()을 쓴다.

model: \"loaded\"는 명세가 요구하는 고정 문자열이며 실제 모델 적재 상태가
아니다. 이 서비스는 아직 고정값을 반환한다 - 주석에 남겼다."
```

---

### Task 2: `POST /analyze` 엔드포인트 신설

**Files:**
- Modify: `apps/ml-service/main.py` (`app = FastAPI(lifespan=lifespan)` 아래에 추가)
- Test: `apps/ml-service/tests/test_main.py::TestAnalyze` (8개 전부 RED)

**Interfaces:**
- Consumes: Task 1이 추가한 `import time`은 여기서 쓰지 않는다. 독립적이다
- Produces: 없음

테스트가 요구하는 계약은 정확히 이렇다.

| 항목 | 값 |
|---|---|
| 요청 바디 | `{"stream_id": int, "trail_id": int, "image_path": str}` — 셋 다 필수 |
| 필수 필드 누락 | 422 (FastAPI가 Pydantic 검증 실패 시 자동으로 낸다) |
| 성공 상태 코드 | 202 |
| 응답 바디 | `jobId`(빈 문자열 아닌 str, 요청마다 고유), `status`("queued"), `image_path`(요청값 그대로) |

요청은 snake_case, 응답의 `jobId`는 camelCase다. 섞여 있지만 명세와 테스트가 그렇게 요구하므로 그대로 따른다.

- [ ] **Step 1: 실패를 먼저 확인한다**

Run: `python -m pytest tests/test_main.py::TestAnalyze -v`

Expected: 8개 전부 FAIL. 대부분 `assert 404 == 202` 형태이고, 응답 본문을 보는 것들은 `KeyError: 'jobId'`로 실패한다. 엔드포인트가 아예 없기 때문이다.

- [ ] **Step 2: Pydantic 요청 모델을 정의한다**

`main.py`의 import 블록에 두 줄을 더한다.

```python
import uuid
from pydantic import BaseModel
```

그리고 `app = FastAPI(lifespan=lifespan)` 아래에 모델을 둔다.

```python
class AnalyzeRequest(BaseModel):
    # 세 필드 모두 기본값이 없다 = 필수. 누락되면 FastAPI가 422를 낸다.
    # 요청 필드는 snake_case, 응답의 jobId는 camelCase다 - API 명세가
    # 그렇게 정의돼 있어 그대로 따른다.
    stream_id: int
    trail_id: int
    image_path: str
```

- [ ] **Step 3: 엔드포인트를 추가한다**

`AnalyzeRequest` 바로 아래에 둔다.

```python
@app.post("/analyze", status_code=202)
def analyze(request: AnalyzeRequest):
    # 요청을 접수하고 식별자만 돌려준다. 실제 분석은 Kafka 경로
    # (image.downloaded -> consume -> image.analyzed)가 담당하며,
    # 이 엔드포인트는 아직 그 경로에 일을 넣지 않는다 - 의도된 것이다.
    # 계획 문서의 "결정됨" 절에 근거와 검토한 대안이 있다.
    return {
        "jobId": str(uuid.uuid4()),
        "status": "queued",
        "image_path": request.image_path,
    }
```

`uuid.uuid4()`를 쓰는 이유는 `test_analyze_job_ids_are_unique`가 연속 두 호출의 `jobId`가 달라야 한다고 단언하기 때문이다. 카운터를 쓰면 프로세스 재시작 시 중복되고, 타임스탬프를 쓰면 같은 밀리초에 두 요청이 오면 충돌한다.

- [ ] **Step 4: 통과를 확인한다**

Run: `python -m pytest tests/test_main.py::TestAnalyze -v`

Expected: 8개 전부 PASS

- [ ] **Step 5: 전체 스위트로 회귀를 확인한다**

Run: `python -m pytest tests -q`

Expected: `2 failed, 26 passed` — 남은 실패 2개는 Task 3이 다룰 `consume()` 예외 처리다.

- [ ] **Step 6: 커밋**

```bash
git add apps/ml-service/main.py
git commit -m "feat(ml-service): POST /analyze 엔드포인트 추가

API 명세에 있으나 구현이 없어 호출하면 404가 나던 엔드포인트다.
TestAnalyze 8개가 전부 이것 때문에 RED였다.

계약: 요청 {stream_id, trail_id, image_path} 셋 다 필수, 누락 시 422
(Pydantic 검증을 FastAPI가 422로 변환), 성공 시 202와
{jobId, status: queued, image_path}.

jobId는 uuid4를 쓴다. 테스트가 연속 두 호출의 고유성을 단언하는데
카운터는 프로세스 재시작 시 중복되고 타임스탬프는 같은 밀리초 요청에서
충돌한다.

현재 이 엔드포인트는 접수만 하고 실제 분석 경로에 일을 넣지 않는다.
근거와 대안은 계획 문서의 미결 사항에 적었다."
```

---

### Task 3: `consume()`에 메시지별 예외 처리 추가

**Files:**
- Modify: `apps/ml-service/main.py` (`async for msg in consumer:` 루프 내부)
- Test: `apps/ml-service/tests/test_consume.py::TestConsumeErrorHandling` (3개 중 2개가 RED)

**Interfaces:**
- Consumes: 없음
- Produces: 없음

현재 루프는 `json.loads(msg.value)`를 예외 처리 없이 호출한다. 잘못된 JSON이 하나 오면 `JSONDecodeError`가 `consume()` 밖으로 전파되고 소비 루프 자체가 죽는다. 운영에서는 이 한 건이 컨슈머 전체를 멈춘다.

테스트가 요구하는 동작은 세 가지다.
1. 잘못된 JSON을 받아도 예외가 밖으로 전파되지 않는다
2. 잘못된 메시지는 발행하지 않는다 (`producer.send`가 호출되지 않아야 한다)
3. 잘못된 메시지 다음의 유효한 메시지는 정상 처리된다 (`send.call_count == 1`)

`try/except`를 루프 **안쪽**에 둬야 3번이 성립한다. 루프 바깥(`async for`를 감싸는 위치)에 두면 첫 예외에서 루프를 빠져나가 다음 메시지를 처리하지 못한다.

- [ ] **Step 1: 실패를 먼저 확인한다**

Run: `python -m pytest tests/test_consume.py::TestConsumeErrorHandling -v`

Expected: 3개 중 2개 FAIL
- `test_invalid_json_does_not_propagate_exception` → FAIL, `json.decoder.JSONDecodeError`가 `await consume()`에서 전파
- `test_invalid_json_skipped_and_next_message_processed` → FAIL, 같은 이유
- `test_stop_is_called_even_when_exception_occurs` → PASS (기존 `finally`가 이미 처리한다)

- [ ] **Step 2: 루프 본문을 try/except로 감싼다**

`main.py`의 `async for msg in consumer:` 블록 전체를 아래로 교체한다. 기존 본문(파싱, 결과 조립, 발행, print)은 그대로 두고 들여쓰기만 한 단계 넣은 뒤 `except`를 붙이는 것이다.

```python
        async for msg in consumer:
            # try/except는 반드시 루프 "안쪽"이다. 바깥에 두면 첫 예외에서
            # 루프를 빠져나가 뒤따르는 정상 메시지를 처리하지 못한다.
            # 잘못된 메시지 하나가 컨슈머 전체를 멈추게 두지 않는 것이 목적이다.
            try:
                data = json.loads(msg.value)
                print(f"[ml-service] 수신: {data}")

                # TODO: 실제 ML 분석 로직 자리 (현재는 고정값)
                result = {
                    "imageId": data.get("imageId"),
                    "trailId": data.get("trailId"),
                    "streamId": data.get("streamId"),
                    "imagePath": data.get("imagePath"),
                    "roadStatus": "양호",
                    "confidence": 0.95,
                    "analyzedAt": data.get("timestamp"),
                }

                await producer.send(
                    "image.analyzed",
                    value=json.dumps(result).encode(),
                )
                print(f"[ml-service] 발행 → image.analyzed: {result}")
            except Exception as e:
                # 넓게 잡는다. JSON 파싱뿐 아니라 발행 실패도 같은 이유로
                # 루프를 죽여서는 안 된다. 어차피 이 메시지는 버린다.
                print(f"[ml-service] 메시지 처리 실패, 건너뜀: {e}")
```

- [ ] **Step 3: 통과를 확인한다**

Run: `python -m pytest tests/test_consume.py::TestConsumeErrorHandling -v`

Expected: 3개 전부 PASS

- [ ] **Step 4: 전체 스위트로 회귀를 확인한다**

Run: `python -m pytest tests -q`

Expected: `28 passed` — RED 0개

- [ ] **Step 5: 커밋**

```bash
git add apps/ml-service/main.py
git commit -m "fix(ml-service): 잘못된 메시지 하나가 소비 루프를 죽이던 문제 수정

consume()이 json.loads를 예외 처리 없이 호출하고 있었다. 잘못된 JSON이
한 건 들어오면 JSONDecodeError가 consume() 밖으로 전파되고 소비 루프가
멈춘다. 운영에서는 메시지 한 건이 컨슈머 전체를 정지시킨다.

루프 본문을 try/except로 감쌌다. try를 루프 안쪽에 두는 것이 핵심이다.
바깥에 두면 첫 예외에서 루프를 빠져나가 뒤따르는 정상 메시지를 처리하지
못한다.

예외는 넓게 잡는다. JSON 파싱뿐 아니라 발행 실패도 같은 이유로 루프를
죽여서는 안 되고, 어느 쪽이든 그 메시지는 버리기 때문이다.

TestConsumeErrorHandling 2개가 GREEN으로 전환되어 ml-service의
RED가 0이 됐다(28개 전부 통과)."
```

---

### Task 4: 문서 갱신

**Files:**
- Modify: `docs/tdd-test-plan.md` (ml-service 절, 요약표, RED 상세, 다음 단계)

**Interfaces:**
- Consumes: Task 1~3의 최종 테스트 수치
- Produces: 없음

이 저장소의 `docs/tdd-test-plan.md`는 스냅샷이 아니라 계속 갱신하는 살아있는 문서다. 구현이 끝나면 반드시 반영한다.

- [ ] **Step 1: 실측값을 얻는다**

Run: `python -m pytest tests -q`

Expected: `28 passed`. 이 숫자를 그대로 쓴다. 추정하지 않는다.

- [ ] **Step 2: ml-service 절을 갱신한다**

`docs/tdd-test-plan.md`의 "apps/ml-service" 절에서 소스 코드 표의 세 행을 고친다.

- `GET /health` → "구현 완료. 명세 형식(`status`/`model`/`uptime_sec`) 반환"
- `POST /analyze` → "구현 완료. 202 + `{jobId, status, image_path}`. 실제 분석 경로 연결은 미결"
- `consume()` → "메시지별 try/except 추가. 분석 결과는 여전히 고정값"

테스트 코드 표는 `test_main.py` 12 / GREEN 12 / RED 0, `test_consume.py` 16 / GREEN 16 / RED 0, 합계 28 / 28 / 0으로 바꾼다.

- [ ] **Step 3: 요약표와 RED 상세를 갱신한다**

요약표의 ml-service 행을 `28 | 28 | 0 | 0`으로. 합계는 `251 | 238 | 13 | 0`이 된다 (기존 GREEN 225 + 13, RED 26 - 13). **실행 시점에 실측값을 다시 뽑아 확인할 것** — 아래 로드맵 표에 적힌 기준선이 그때도 맞는지 먼저 보라.

"RED 테스트 상세"의 "ML Service — 13개 RED" 절을 통째로 지우고, "다음 단계" 블록의 1번 항목을 완료 표시로 옮긴다.

- [ ] **Step 4: 커밋**

```bash
git add docs/tdd-test-plan.md
git commit -m "docs: ml-service RED 해소를 TDD 현황표에 반영

ml-service 28개가 전부 GREEN이 되어 RED 13개가 사라졌다. 전체는
251개 중 GREEN 238 / RED 13이 된다. 수치는 pytest 실행 결과를 그대로
옮긴 것이며 추정이 아니다."
```

---

## 결정됨 (2026-08-27) — `POST /analyze`는 접수만 한다

**테스트는 응답 형식만 검증한다.** 부수 효과를 요구하지 않는다. 그래서 Task 2는 202와 식별자만 돌려주는 최소 구현으로 잡았다. 하지만 이 상태의 `/analyze`는 아무 일도 하지 않는다 — 이름과 달리 분석을 시작하지 않는다.

선택지 셋을 검토했고 **1번(접수만)으로 결정했다.** Task 2는 계획에 적힌 대로 진행한다.

1. **접수만 한다 — 채택.** 계약은 만족하고 코드는 최소다. 다만 호출자는 `jobId`로 결과를 조회할 방법이 없고 분석도 일어나지 않는다. 명세에 `GET /analyze/{jobId}` 같은 조회 엔드포인트가 없으므로 `jobId`는 사실상 쓸모없는 값이 된다.
2. **`image.downloaded`로 발행한다.** 기존 Kafka 경로에 그대로 태우므로 실제로 분석이 일어나고 writer까지 저장된다. 코드도 몇 줄이다. 다만 youtube-service도 같은 토픽에 발행하므로 같은 이미지가 두 경로로 들어올 수 있다.
3. **동기로 분석하고 `image.analyzed`로 발행한다.** `consume()`의 고정값 로직을 함수로 빼서 재사용한다. 202(비동기 접수)를 반환하면서 실제로는 동기 처리라 의미가 어긋난다.

1번을 고른 이유는 테스트가 요구하지 않는 동작을 추측으로 넣지 않기 위해서다. 2번이 실용적으로는 가장 그럴듯해 보이지만 youtube-service와의 중복 발행 가능성이 확인되지 않았고, 그 확인은 youtube-service 계획에서 다루는 것이 맞다.

**후속으로 남기는 것:** `/analyze`가 접수만 하는 한 `jobId`는 조회할 곳이 없는 값이다. 명세에 `GET /analyze/{jobId}`가 없어서 그렇다. 실제 분석 경로 연결이 필요해지면 그때 2번(또는 조회 엔드포인트 추가)을 다시 꺼낸다 — youtube-service 작업에서 `image.downloaded` 발행 주체가 정리된 뒤가 적기다.

---

## 이 계획 이후의 로드맵

세 항목은 서로 독립적인 서브시스템이라 **계획을 하나로 묶지 않는다.** 각각 별도 계획 문서와 브랜치로 진행한다.

### ~~그다음: youtube-service RED 11개~~ — 완료 (2026-08-28, 설계: `docs/superpowers/specs/2026-08-27-youtube-capture-design.md`)

`POST /download`와 `GET /status/:jobId`가 스텁이었다. 다만 이건 ml-service와 성격이 다르다 — **응답 형식을 맞추는 문제가 아니라 실제 영상 다운로드 로직이 아예 없다.** 계획을 쓰기 전에 결정할 것들이 있었고, 브레인스토밍으로 다음과 같이 정리됐다(근거는 위 설계 문서 참고).

- **다운로드 수단** — `yt-dlp`는 도입하지 않는다. YouTube에서 프레임을 추출하는 것은 이용약관 위반이라 YouTube 어댑터 자체를 만들지 않기로 했다. 대신 ffmpeg로 `CAPTURE_SOURCE`(`testsrc`/`file`/`hls`)를 캡처한다
- **저장 위치** — MinIO(S3 호환 오브젝트 스토리지)에 `captures/{streamId}/{trailId}/{ISO8601}.jpg` 키로 업로드한다. compose에 MinIO 서비스와 볼륨을 추가했다
- **`interval_sec`** — 받지 않기로 했다. "몇 분마다"는 스케줄러(오케스트레이션)의 관심사이고, `POST /download`는 "지금 한 장 떠라"는 단발 작업이다
- **`jobStore`** — 프로세스 메모리 `Map`에서 Redis(TTL 1시간)로 옮겼다. 컨테이너 재시작에도 살아남고 정리 로직을 따로 둘 필요가 없다

이미 해소됐던 선행 조건: `index.js`가 `app.js`를 재사용하도록 고쳐서, `app.js`의 스텁을 구현하면 실서버에 반영된다 (커밋 `782859f`).

### ~~그다음: backend Capture RED 5개~~ — 완료 (2026-08-27, main 머지 `622fb75`)

`CaptureController`/`CaptureServiceImpl`이 스텁이다. Stream·Trail과 같은 패턴이라 설계 결정은 거의 없지만 **선행 작업이 있다.**

reader가 제공하는 것은 `GET /captures`(전체)와 `GET /captures/trail/{trailId}/latest` 둘뿐이다. backend 테스트가 요구하는 것은 `GET /captures/{id}`와 `/captures`의 필터 파라미터(`stream_id`, `trail_id`, `limit`, `sort`)다. 따라서 순서는 이렇다.

1. reader 확장 — `GET /captures/{id}` 추가, `/captures`에 필터 파라미터 추가 (`CaptureRepository`에 `findByTrailId`/`findByStreamId`는 이미 있다)
2. backend `CaptureServiceImpl` 구현 — `TrailServiceImpl`과 같은 `RestClient` 패턴
3. backend `CaptureController` 배선

이 작업 중에 함께 볼 것: `Capture.trailId`/`streamId`가 `Integer`인데 `Trail.streamId`/`Stream.id`는 `Long`이다. 같은 FK를 두 타입으로 다루고 있어 게이트웨이 연동 시 드러난다.

### 세 항목 완료 시 예상 수치

| 시점 | 전체 | GREEN | RED |
|---|---|---|---|
| 계획 작성 시점 (2026-08-27) | 235 | 204 | 31 |
| backend Capture 완료 후 — **현재 기준선** | 251 | 225 | 26 |
| ml-service 후 (+13) | 251 | 238 | 13 |
| youtube-service 후 (+11) | 251 | 249 | 2 |

**2026-08-27 갱신:** 이 계획을 쓴 뒤 backend Capture 조회 경로가 먼저 완료됐다(계획: `2026-08-27-capture-read-path.md`, main 머지 `622fb75`). 그 작업이 shared/reader/backend에 테스트 16개를 더해 전체가 235 → 251로 늘었고 backend RED 5개가 사라졌다. 위 표의 기준선을 그에 맞춰 고쳤다.

youtube-service까지 끝내면 남는 RED 2개는 `ReaderApplicationTests`/`WriterApplicationTests`의 `contextLoads`다. 테스트 프로파일에 JDBC URL이 없어서 나는 설정 문제이며 기능 구현과 무관하므로 별도로 처리한다. youtube-service 작업은 새 테스트가 붙을 가능성이 높아 전체 개수가 251보다 늘어날 수 있다.

**2026-08-28 실측:** 예상대로 새 테스트가 붙어 youtube-service는 11개가 아니라 47개(전부 GREEN, capture/storage/jobs/pipeline 테스트 파일 신설)가 됐다. 저장소 전체는 276개 중 GREEN 274 / RED 2(위에서 말한 contextLoads 2개뿐)다. 실측값과 갱신 근거는 `docs/tdd-test-plan.md`를 참고.
