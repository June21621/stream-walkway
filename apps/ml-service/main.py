import aio_pika
import asyncio
import os
import json
from fastapi import FastAPI
from contextlib import asynccontextmanager


async def get_connection():
    return await aio_pika.connect_robust(
        host=os.getenv("RABBITMQ_HOST", "localhost"),
        port=int(os.getenv("RABBITMQ_PORT", 5672)),
        login=os.getenv("RABBITMQ_USER", "stream_user"),
        password=os.getenv("RABBITMQ_PASSWORD", "rabbitmq_pw"),
        virtualhost=os.getenv("RABBITMQ_VHOST", "/"),
    )


# ─────────────────────────────────────────
# image.downloaded 구독 → 분석 → image.analyzed 발행
# ─────────────────────────────────────────
async def consume():
    connection = await get_connection()
    channel = await connection.channel()

    in_queue = await channel.declare_queue("image.downloaded", durable=True)
    await channel.declare_queue("image.analyzed", durable=True)

    print("[ml-service] 구독 시작: image.downloaded")

    async with in_queue.iterator() as q:
        async for message in q:
            async with message.process():
                data = json.loads(message.body)
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

                await channel.default_exchange.publish(
                    aio_pika.Message(
                        body=json.dumps(result).encode(),
                        delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
                    ),
                    routing_key="image.analyzed",
                )
                print(f"[ml-service] 발행 → image.analyzed: {result}")


@asynccontextmanager
async def lifespan(app: FastAPI):
    asyncio.create_task(consume())
    yield


app = FastAPI(lifespan=lifespan)


@app.get("/health")
def health():
    return {"status": "ok", "service": "ml-service"}
