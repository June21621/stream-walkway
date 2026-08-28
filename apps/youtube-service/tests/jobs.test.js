const RedisMock = require('ioredis-mock');
const { createJobs } = require('../src/jobs');

describe('jobs.js', () => {
  let redis;
  let jobs;

  beforeEach(() => {
    redis = new RedisMock();
    jobs = createJobs(redis, { ttlSec: 3600 });
  });

  test('create - 기본 필드를 채워 저장한다', async () => {
    const job = await jobs.create({ jobId: 'j1', stream_id: 1, trail_id: 2 });

    expect(job.jobId).toBe('j1');
    expect(job.status).toBe('queued');
    expect(job.progress).toBe(0);
    expect(job.downloaded_count).toBe(0);
    expect(job.stream_id).toBe(1);
  });

  test('get - 저장한 작업을 되읽는다', async () => {
    await jobs.create({ jobId: 'j1', stream_id: 1, trail_id: 2 });
    const found = await jobs.get('j1');

    expect(found.jobId).toBe('j1');
    expect(found.status).toBe('queued');
  });

  test('get - 없는 작업은 null을 반환한다', async () => {
    expect(await jobs.get('nope')).toBeNull();
  });

  test('update - 기존 값에 병합한다', async () => {
    await jobs.create({ jobId: 'j1', stream_id: 1, trail_id: 2 });
    const updated = await jobs.update('j1', { status: 'completed', progress: 100, downloaded_count: 1 });

    expect(updated.status).toBe('completed');
    expect(updated.progress).toBe(100);
    // 병합이므로 기존 필드가 남아야 한다
    expect(updated.stream_id).toBe(1);
  });

  test('update - 없는 작업은 null을 반환한다', async () => {
    expect(await jobs.update('nope', { status: 'failed' })).toBeNull();
  });

  test('create - TTL이 설정된다', async () => {
    await jobs.create({ jobId: 'j1', stream_id: 1, trail_id: 2 });
    const ttl = await redis.ttl('job:j1');

    // 넣기만 하고 지우는 코드가 없으면 무한히 쌓인다. TTL이 그걸 막는다.
    expect(ttl).toBeGreaterThan(0);
    expect(ttl).toBeLessThanOrEqual(3600);
  });
});
