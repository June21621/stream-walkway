const request = require('supertest');
const { createApp } = require('../src/app');

// 캡처 실행부를 가짜로 주입한다. 진짜 ffmpeg를 띄우지 않는다.
// runCapture는 파이프라인이 그렇듯 던지지 않고 조용히 끝난다.
function buildApp() {
  const store = new Map();
  const jobs = {
    create: jest.fn(async (job) => {
      const record = { status: 'queued', progress: 0, downloaded_count: 0, ...job };
      store.set(record.jobId, record);
      return record;
    }),
    get: jest.fn(async (jobId) => store.get(jobId) || null),
    update: jest.fn(async (jobId, patch) => {
      const cur = store.get(jobId);
      if (!cur) return null;
      const merged = { ...cur, ...patch };
      store.set(jobId, merged);
      return merged;
    }),
  };
  const runCapture = jest.fn().mockResolvedValue(undefined);
  return { app: createApp({ jobs, runCapture }), jobs, runCapture };
}

// ─────────────────────────────────────────
// YouTube Service API 테스트
// POST /download, GET /status/:jobId
// ─────────────────────────────────────────

describe('YouTube Service API', () => {

  // ─────────────────────────────────────────
  // POST /download
  // ─────────────────────────────────────────
  describe('POST /download', () => {

    test('유효한 요청으로 다운로드 작업을 시작하면 202 Accepted를 반환한다', async () => {
      // given
      const { app } = buildApp();
      const body = {
        stream_id: 1,
        trail_id: 1,
        youtube_url: 'https://www.youtube.com/watch?v=example',
      };

      // when
      const res = await request(app)
        .post('/download')
        .send(body)
        .set('Content-Type', 'application/json');

      // then
      expect(res.status).toBe(202);
      expect(res.body).toHaveProperty('jobId');
      expect(res.body.status).toBe('queued');
      expect(res.body.stream_id).toBe(1);
      expect(res.body.trail_id).toBe(1);
      expect(res.body.youtube_url).toBe('https://www.youtube.com/watch?v=example');
    });

    test('jobId는 고유한 문자열이다', async () => {
      // given
      const { app } = buildApp();
      const body = {
        stream_id: 1,
        trail_id: 1,
        youtube_url: 'https://www.youtube.com/watch?v=example',
      };

      // when
      const res1 = await request(app).post('/download').send(body);
      const res2 = await request(app).post('/download').send(body);

      // then
      expect(res1.body.jobId).not.toBe(res2.body.jobId);
    });

    test('stream_id가 없으면 400 Bad Request를 반환한다', async () => {
      // given
      const { app } = buildApp();
      const body = {
        trail_id: 1,
        youtube_url: 'https://www.youtube.com/watch?v=example',
      };

      // when
      const res = await request(app)
        .post('/download')
        .send(body)
        .set('Content-Type', 'application/json');

      // then
      expect(res.status).toBe(400);
    });

    test('trail_id가 없으면 400 Bad Request를 반환한다', async () => {
      // given
      const { app } = buildApp();
      const body = {
        stream_id: 1,
        youtube_url: 'https://www.youtube.com/watch?v=example',
      };

      // when
      const res = await request(app)
        .post('/download')
        .send(body)
        .set('Content-Type', 'application/json');

      // then
      expect(res.status).toBe(400);
    });

    test('youtube_url이 없으면 400 Bad Request를 반환한다', async () => {
      // given
      const { app } = buildApp();
      const body = {
        stream_id: 1,
        trail_id: 1,
      };

      // when
      const res = await request(app)
        .post('/download')
        .send(body)
        .set('Content-Type', 'application/json');

      // then
      expect(res.status).toBe(400);
    });
  });

  // ─────────────────────────────────────────
  // GET /status/:jobId
  // ─────────────────────────────────────────
  describe('GET /status/:jobId', () => {

    test('등록된 jobId의 상태를 200 OK로 반환한다', async () => {
      // given - 먼저 작업을 등록한다
      const { app } = buildApp();
      const downloadRes = await request(app)
        .post('/download')
        .send({
          stream_id: 1,
          trail_id: 1,
          youtube_url: 'https://www.youtube.com/watch?v=example',
        });
      const { jobId } = downloadRes.body;

      // when
      const res = await request(app).get(`/status/${jobId}`);

      // then
      expect(res.status).toBe(200);
      expect(res.body.jobId).toBe(jobId);
      expect(res.body).toHaveProperty('status');
      expect(res.body).toHaveProperty('progress');
      expect(res.body).toHaveProperty('downloaded_count');
    });

    test('status는 queued | processing | completed 중 하나이다', async () => {
      // given
      const { app } = buildApp();
      const downloadRes = await request(app)
        .post('/download')
        .send({
          stream_id: 1,
          trail_id: 1,
          youtube_url: 'https://www.youtube.com/watch?v=example',
        });
      const { jobId } = downloadRes.body;

      // when
      const res = await request(app).get(`/status/${jobId}`);

      // then
      const validStatuses = ['queued', 'processing', 'completed', 'failed'];
      expect(validStatuses).toContain(res.body.status);
    });

    test('progress는 0~100 사이의 숫자이다', async () => {
      // given
      const { app } = buildApp();
      const downloadRes = await request(app)
        .post('/download')
        .send({
          stream_id: 1,
          trail_id: 1,
          youtube_url: 'https://www.youtube.com/watch?v=example',
        });
      const { jobId } = downloadRes.body;

      // when
      const res = await request(app).get(`/status/${jobId}`);

      // then
      expect(res.body.progress).toBeGreaterThanOrEqual(0);
      expect(res.body.progress).toBeLessThanOrEqual(100);
    });

    test('존재하지 않는 jobId는 404 Not Found를 반환한다', async () => {
      // when
      const { app } = buildApp();
      const res = await request(app).get('/status/nonexistent-job-id');

      // then
      expect(res.status).toBe(404);
    });
  });

  // ─────────────────────────────────────────
  // GET /health
  // ─────────────────────────────────────────
  describe('GET /health', () => {

    test('서비스가 정상 상태이면 200 OK를 반환한다', async () => {
      const { app } = buildApp();
      const res = await request(app).get('/health');
      expect(res.status).toBe(200);
      expect(res.body.status).toBe('ok');
      expect(res.body.service).toBe('youtube-service');
    });
  });
});
