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

    const storage = createStorage(process.env);
    await storage.ensureBucket();

    const jobs = createJobs(redis, { ttlSec: 3600 });
    const capture = createCapture(process.env);
    const { runCapture } = createPipeline({ capture, storage, jobs, publish: publishMessage });

    createApp({ jobs, runCapture }).listen(PORT, () => {
      console.log(`[youtube-service] running on port ${PORT}`);
    });
  } catch (err) {
    console.error('[youtube-service] 시작 실패:', err.message);
    process.exit(1);
  }
}

start();
