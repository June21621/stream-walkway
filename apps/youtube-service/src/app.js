const express = require('express');
const crypto = require('crypto');

const REQUIRED_FIELDS = ['stream_id', 'trail_id', 'youtube_url'];

// 의존성을 주입받는다. app.js가 capture/storage/kafka를 직접 require하면
// 테스트가 진짜 ffmpeg를 띄우고 브로커에 붙으려 한다.
function createApp({ jobs, runCapture }) {
  const app = express();
  app.use(express.json());

  app.get('/health', (req, res) => {
    res.json({ status: 'ok', service: 'youtube-service' });
  });

  // ─────────────────────────────────────────
  // POST /download — 지금 한 장 뜬다.
  //
  // "15분마다"를 세는 것은 스케줄러의 일이라 interval_sec을 받지 않는다.
  // 요청 본문에 있어도 무시하고 응답에도 넣지 않는다.
  // ─────────────────────────────────────────
  app.post('/download', async (req, res) => {
    for (const field of REQUIRED_FIELDS) {
      if (req.body[field] === undefined || req.body[field] === null) {
        return res.status(400).json({ error: `${field} is required` });
      }
    }

    const job = await jobs.create({
      jobId: crypto.randomUUID(),
      stream_id: req.body.stream_id,
      trail_id: req.body.trail_id,
      youtube_url: req.body.youtube_url,
    });

    // 응답을 먼저 보내고 캡처는 뒤에서 돈다. runCapture는 던지지 않도록
    // 만들어져 있지만, 혹시 모를 동기 예외까지 여기서 막는다.
    setImmediate(() => {
      Promise.resolve()
        .then(() => runCapture(job))
        .catch((err) => console.error('[youtube-service] 캡처 실행 실패:', err.message));
    });

    res.status(202).json({
      jobId: job.jobId,
      status: job.status,
      stream_id: job.stream_id,
      trail_id: job.trail_id,
      youtube_url: job.youtube_url,
    });
  });

  // ─────────────────────────────────────────
  // GET /status/:jobId
  // ─────────────────────────────────────────
  app.get('/status/:jobId', async (req, res) => {
    const job = await jobs.get(req.params.jobId);
    if (!job) {
      return res.status(404).json({ error: 'Job not found', jobId: req.params.jobId });
    }

    const body = {
      jobId: job.jobId,
      status: job.status,
      progress: job.progress,
      downloaded_count: job.downloaded_count,
    };
    if (job.error) body.error = job.error;

    res.json(body);
  });

  return app;
}

module.exports = { createApp };
