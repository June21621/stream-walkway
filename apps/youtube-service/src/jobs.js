const KEY_PREFIX = 'job:';
const DEFAULT_TTL_SEC = 3600;

// 작업 상태를 Redis에 둔다. 이전 구현은 프로세스 메모리의 Map이었는데
// 넣기만 하고 지우는 코드가 없어 15분마다 1건씩 영원히 쌓였다.
// TTL이 그 문제를 구조적으로 없앤다 - 정리 코드를 직접 쓸 필요가 없다.
function createJobs(redis, { ttlSec = DEFAULT_TTL_SEC } = {}) {
  const key = (jobId) => `${KEY_PREFIX}${jobId}`;

  async function create(job) {
    const record = {
      status: 'queued',
      progress: 0,
      downloaded_count: 0,
      ...job,
    };
    await redis.set(key(record.jobId), JSON.stringify(record), 'EX', ttlSec);
    return record;
  }

  async function get(jobId) {
    const raw = await redis.get(key(jobId));
    return raw ? JSON.parse(raw) : null;
  }

  async function update(jobId, patch) {
    const current = await get(jobId);
    if (!current) return null;

    const merged = { ...current, ...patch };
    // TTL을 다시 건다. 갱신할 때마다 수명이 연장되는데, 작업이 몇 초짜리라
    // 실질적인 차이는 없고 만료 직전 갱신이 값을 잃는 것을 막는다.
    await redis.set(key(jobId), JSON.stringify(merged), 'EX', ttlSec);
    return merged;
  }

  return { create, get, update };
}

module.exports = { createJobs };
