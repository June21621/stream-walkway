"""
ML Service API 테스트 (TDD - RED 단계)

API 명세서 기준:
  POST /analyze  → 202 Accepted
  GET  /health   → 200 OK {"status": "healthy", "model": "loaded", "uptime_sec": ...}
"""
import pytest
from fastapi.testclient import TestClient
from unittest.mock import patch, AsyncMock

# Kafka 연결 없이 앱을 임포트하기 위해 lifespan을 무력화
import asyncio
from contextlib import asynccontextmanager


@pytest.fixture(scope="module")
def client():
    """
    Kafka consumer/producer 없이 FastAPI TestClient를 생성한다.
    aiokafka 연결을 mock으로 대체하여 외부 의존성을 제거한다.
    """
    with (
        patch("aiokafka.AIOKafkaConsumer.start", new_callable=AsyncMock),
        patch("aiokafka.AIOKafkaProducer.start", new_callable=AsyncMock),
        patch("aiokafka.AIOKafkaConsumer.stop", new_callable=AsyncMock),
        patch("aiokafka.AIOKafkaProducer.stop", new_callable=AsyncMock),
        patch("asyncio.create_task"),
    ):
        from main import app
        with TestClient(app) as c:
            yield c


# ─────────────────────────────────────────
# GET /health
# ─────────────────────────────────────────

class TestHealth:

    def test_health_returns_200(self, client):
        """GET /health - 200 OK를 반환한다"""
        response = client.get("/health")
        assert response.status_code == 200

    def test_health_response_has_status_healthy(self, client):
        """GET /health - status 필드가 'healthy'이다"""
        # API 명세: {"status": "healthy", "model": "loaded", "uptime_sec": 3600}
        # 현재 구현: {"status": "ok", "service": "ml-service"} → TDD RED
        response = client.get("/health")
        body = response.json()
        assert body["status"] == "healthy"

    def test_health_response_has_model_field(self, client):
        """GET /health - model 필드가 'loaded'이다"""
        response = client.get("/health")
        body = response.json()
        assert "model" in body
        assert body["model"] == "loaded"

    def test_health_response_has_uptime_sec(self, client):
        """GET /health - uptime_sec 필드가 숫자이다"""
        response = client.get("/health")
        body = response.json()
        assert "uptime_sec" in body
        assert isinstance(body["uptime_sec"], (int, float))
        assert body["uptime_sec"] >= 0


# ─────────────────────────────────────────
# POST /analyze
# ─────────────────────────────────────────

class TestAnalyze:

    def test_analyze_returns_202(self, client):
        """POST /analyze - 202 Accepted를 반환한다"""
        # API 명세: 이미지를 분석하여 도로 상태를 추출한다
        payload = {
            "stream_id": 1,
            "trail_id": 1,
            "image_path": "/images/capture_001.jpg",
        }
        response = client.post("/analyze", json=payload)
        assert response.status_code == 202

    def test_analyze_response_has_job_id(self, client):
        """POST /analyze - 응답에 jobId가 포함된다"""
        payload = {
            "stream_id": 1,
            "trail_id": 1,
            "image_path": "/images/capture_001.jpg",
        }
        response = client.post("/analyze", json=payload)
        body = response.json()
        assert "jobId" in body
        assert isinstance(body["jobId"], str)
        assert len(body["jobId"]) > 0

    def test_analyze_response_status_is_queued(self, client):
        """POST /analyze - 응답의 status가 'queued'이다"""
        payload = {
            "stream_id": 1,
            "trail_id": 1,
            "image_path": "/images/capture_001.jpg",
        }
        response = client.post("/analyze", json=payload)
        body = response.json()
        assert body["status"] == "queued"

    def test_analyze_response_contains_image_path(self, client):
        """POST /analyze - 응답에 요청한 image_path가 포함된다"""
        image_path = "/images/capture_001.jpg"
        payload = {
            "stream_id": 1,
            "trail_id": 1,
            "image_path": image_path,
        }
        response = client.post("/analyze", json=payload)
        body = response.json()
        assert body["image_path"] == image_path

    def test_analyze_without_image_path_returns_422(self, client):
        """POST /analyze - image_path 없이 요청하면 422 Unprocessable Entity를 반환한다"""
        payload = {
            "stream_id": 1,
            "trail_id": 1,
        }
        response = client.post("/analyze", json=payload)
        assert response.status_code == 422

    def test_analyze_without_stream_id_returns_422(self, client):
        """POST /analyze - stream_id 없이 요청하면 422 Unprocessable Entity를 반환한다"""
        payload = {
            "trail_id": 1,
            "image_path": "/images/capture_001.jpg",
        }
        response = client.post("/analyze", json=payload)
        assert response.status_code == 422

    def test_analyze_without_trail_id_returns_422(self, client):
        """POST /analyze - trail_id 없이 요청하면 422 Unprocessable Entity를 반환한다"""
        payload = {
            "stream_id": 1,
            "image_path": "/images/capture_001.jpg",
        }
        response = client.post("/analyze", json=payload)
        assert response.status_code == 422

    def test_analyze_job_ids_are_unique(self, client):
        """POST /analyze - 각 요청마다 고유한 jobId가 생성된다"""
        payload = {
            "stream_id": 1,
            "trail_id": 1,
            "image_path": "/images/capture_001.jpg",
        }
        res1 = client.post("/analyze", json=payload)
        res2 = client.post("/analyze", json=payload)
        assert res1.json()["jobId"] != res2.json()["jobId"]
