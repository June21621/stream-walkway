import asyncio
import os
import json
from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from fastapi import FastAPI
from contextlib import asynccontextmanager


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
    return {"status": "ok", "service": "ml-service"}
