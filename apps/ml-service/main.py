import asyncio
import os
import json
import time
import uuid
from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from fastapi import FastAPI
from pydantic import BaseModel
from contextlib import asynccontextmanager


# 프로세스가 살아 있는 시간을 재기 위한 기준점.
# lifespan이 아니라 모듈 로드 시점에 잡는다 — 테스트는 lifespan을 mock으로
# 대체하므로 lifespan 안에서 기록하면 테스트 경로에서 값이 생기지 않는다.
# monotonic을 쓰는 이유는 시스템 시계가 조정돼도 뒤로 가지 않기 때문이다.
_STARTED_AT = time.monotonic()


# ─────────────────────────────────────────
# image.downloaded 구독 → 분석 → image.analyzed 발행
# ─────────────────────────────────────────
async def consume():
    bootstrap_servers = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")

    consumer = AIOKafkaConsumer(
        "image.downloaded",
        bootstrap_servers=bootstrap_servers,
        group_id="ml-group",
        auto_offset_reset="earliest",
    )
    producer = AIOKafkaProducer(bootstrap_servers=bootstrap_servers)

    await consumer.start()
    await producer.start()

    print("[ml-service] 구독 시작: image.downloaded")

    try:
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
                # 루프를 죽여서는 안 되고, 어느 쪽이든 이 메시지는 버린다.
                print(f"[ml-service] 메시지 처리 실패, 건너뜀: {e}")
    finally:
        await consumer.stop()
        await producer.stop()


@asynccontextmanager
async def lifespan(app: FastAPI):
    asyncio.create_task(consume())
    yield


app = FastAPI(lifespan=lifespan)


class AnalyzeRequest(BaseModel):
    # 세 필드 모두 기본값이 없다 = 필수. 누락되면 FastAPI가 422를 낸다.
    # 요청 필드는 snake_case, 응답의 jobId는 camelCase다 — API 명세가
    # 그렇게 정의돼 있어 그대로 따른다.
    stream_id: int
    trail_id: int
    image_path: str


# ─────────────────────────────────────────
# POST /analyze — 분석 요청 접수
# ─────────────────────────────────────────
@app.post("/analyze", status_code=202)
def analyze(request: AnalyzeRequest):
    # 요청을 접수하고 식별자만 돌려준다. 실제 분석은 Kafka 경로
    # (image.downloaded -> consume -> image.analyzed)가 담당하며,
    # 이 엔드포인트는 아직 그 경로에 일을 넣지 않는다 — 의도된 것이다.
    # 계획 문서(2026-08-27-ml-service-red-green.md)의 "결정됨" 절에
    # 근거와 검토한 대안이 있다.
    return {
        "jobId": str(uuid.uuid4()),
        "status": "queued",
        "image_path": request.image_path,
    }


@app.get("/health")
def health():
    # model은 API 명세가 요구하는 고정 문자열이다. 이 서비스는 아직 실제 ML
    # 모델을 적재하지 않고 consume()이 고정값(양호/0.95)을 낸다.
    # 진짜 모델을 붙이면 이 값을 실제 적재 상태에서 끌어와야 한다.
    return {
        "status": "healthy",
        "model": "loaded",
        "uptime_sec": round(time.monotonic() - _STARTED_AT, 3),
    }
