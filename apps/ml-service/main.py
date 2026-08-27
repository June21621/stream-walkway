import asyncio
import os
import json
import time
from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from fastapi import FastAPI
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
    finally:
        await consumer.stop()
        await producer.stop()


@asynccontextmanager
async def lifespan(app: FastAPI):
    asyncio.create_task(consume())
    yield


app = FastAPI(lifespan=lifespan)


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
