import aio_pika
import asyncio
import os
import json
from fastapi import FastAPI
from contextlib import asynccontextmanager


# ─────────────────────────────────────────
# RabbitMQ 구독 (Consumer)
# image.downloaded 큐에서 메시지를 받아 처리
# ─────────────────────────────────────────
async def consume():
    connection = await aio_pika.connect_robust(
        host=os.getenv("RABBITMQ_HOST", "localhost"),
        port=int(os.getenv("RABBITMQ_PORT", 5672)),
        login=os.getenv("RABBITMQ_USER", "stream_user"),
        password=os.getenv("RABBITMQ_PASSWORD", "rabbitmq_pw"),
        virtualhost=os.getenv("RABBITMQ_VHOST", "/"),
    )

    channel = await connection.channel()
    queue = await channel.declare_queue("image.downloaded", durable=True)

    print("[ml-service] RabbitMQ 구독 시작: image.downloaded")

    async with queue.iterator() as q:
        async for message in q:
            async with message.process():
                data = json.loads(message.body)
                print(f"[ml-service] 메시지 수신: {data}")
                # TODO: 실제 ML 분석 로직 추가 예정


# ─────────────────────────────────────────
# FastAPI 서버 시작 시 consumer 실행
# ─────────────────────────────────────────
@asynccontextmanager
async def lifespan(app: FastAPI):
    asyncio.create_task(consume())
    yield


app = FastAPI(lifespan=lifespan)


# ─────────────────────────────────────────
# Health Check
# ─────────────────────────────────────────
@app.get("/health")
def health():
    return {"status": "ok", "service": "ml-service"}
