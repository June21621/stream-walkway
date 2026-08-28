const Redis = require('ioredis');
const { createApp } = require('./app');
const { connectKafka, publishMessage } = require('./kafka');
const { createCapture } = require('./capture');
const { createStorage } = require('./storage');
const { createJobs } = require('./jobs');
const { createPipeline } = require('./pipeline');

const PORT = process.env.PORT || 3000;

async function start() {
  try {
    await connectKafka();

    const redis = new Redis({
      host: process.env.REDIS_HOST || 'localhost',
      port: Number(process.env.REDIS_PORT || 6379),
      password: process.env.REDIS_PASSWORD,
    });
    // ioredis는 리스너가 없으면 연결 오류를 조용히 버린다(silentEmit).
    // 리스너 없이 두면 Redis 장애가 로그 한 줄 없이 요청 행(hang)으로만 드러난다.
    redis.on('error', (err) => console.error('[youtube-service] Redis 오류:', err.message));

    const storage = createStorage(process.env);
    await storage.ensureBucket();

    const jobs = createJobs(redis, { ttlSec: 3600 });
    const capture = createCapture(process.env);
    const { runCapture } = createPipeline({ capture, storage, jobs, publish: publishMessage });

    createApp({ jobs, runCapture })
      .listen(PORT, () => {
        console.log(`[youtube-service] running on port ${PORT}`);
      })
      // 핸들러 없이는 포트 충돌 같은 listen 오류가 uncaughtException으로
      // 새어나가 위 catch를 건너뛴다. 여기서 잡아 같은 종료 경로를 타게 한다.
      .on('error', (err) => {
        console.error('[youtube-service] 시작 실패:', err.message);
        process.exit(1);
      });
  } catch (err) {
    console.error('[youtube-service] 시작 실패:', err.message);
    process.exit(1);
  }
}

start();
