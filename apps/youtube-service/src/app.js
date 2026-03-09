const express = require('express');

const app = express();
app.use(express.json());

// 진행 중인 job 상태를 메모리에 저장 (추후 Redis 등으로 대체)
const jobStore = new Map();

// ─────────────────────────────────────────
// Health Check
// ─────────────────────────────────────────
app.get('/health', (req, res) => {
  res.json({ status: 'ok', service: 'youtube-service' });
});

// ─────────────────────────────────────────
// POST /download
// YouTube URL을 받아 이미지 다운로드 작업을 시작한다.
// 완료 시 Kafka [image.downloaded] 이벤트를 발행한다.
// ─────────────────────────────────────────
app.post('/download', async (req, res) => {
  // TODO: 구현 필요 (TDD - RED 단계)
  throw new Error('Not implemented');
});

// ─────────────────────────────────────────
// GET /status/:jobId
// 다운로드 작업의 현재 진행 상태를 반환한다.
// ─────────────────────────────────────────
app.get('/status/:jobId', (req, res) => {
  // TODO: 구현 필요 (TDD - RED 단계)
  throw new Error('Not implemented');
});

// ─────────────────────────────────────────
// 테스트용 메시지 발행 엔드포인트 (기존)
// ─────────────────────────────────────────
app.post('/test/publish', async (req, res) => {
  try {
    const { publishMessage } = require('./kafka');
    const message = {
      imageId: 'test-001',
      streamId: 1,
      trailId: 1,
      imagePath: '/images/test-001.jpg',
      timestamp: new Date().toISOString(),
    };
    await publishMessage('image.downloaded', message);
    res.json({ status: 'ok', message: '메시지 발행 완료', published: message });
  } catch (err) {
    console.error('[youtube-service] 발행 실패:', err.message);
    res.status(500).json({ status: 'error', message: err.message });
  }
});

module.exports = { app, jobStore };
