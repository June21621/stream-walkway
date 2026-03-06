const express = require('express');
const { connectKafka, publishMessage } = require('./kafka');

const app = express();
app.use(express.json());

const PORT = process.env.PORT || 3000;

// ─────────────────────────────────────────
// Health Check
// ─────────────────────────────────────────
app.get('/health', (req, res) => {
  res.json({ status: 'ok', service: 'youtube-service' });
});

// ─────────────────────────────────────────
// 테스트용 메시지 발행 엔드포인트
// ─────────────────────────────────────────
app.post('/test/publish', async (req, res) => {
  try {
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

// ─────────────────────────────────────────
// 서버 시작
// ─────────────────────────────────────────
async function start() {
  try {
    await connectKafka();
    app.listen(PORT, () => {
      console.log(`[youtube-service] running on port ${PORT}`);
    });
  } catch (err) {
    console.error('[youtube-service] 시작 실패:', err.message);
    process.exit(1);
  }
}

start();
