const { buildKey } = require('./storage');

const TOPIC = 'image.downloaded';

// 캡처 -> 업로드 -> 발행을 순서대로 하고 각 단계마다 작업 상태를 갱신한다.
//
// runCapture는 절대 던지지 않는다. HTTP 응답(202) 이후 비동기로 돌기
// 때문에 여기서 새는 예외는 잡아줄 곳이 없어 프로세스를 내린다.
// ml-service consume()에 try/except를 두른 것과 같은 이유다.
function createPipeline({ capture, storage, jobs, publish }) {

  async function runCapture(job) {
    const { jobId, stream_id: streamId, trail_id: trailId, youtube_url: sourceUrl } = job;

    try {
      await jobs.update(jobId, { status: 'processing' });

      let buffer;
      try {
        buffer = await capture(sourceUrl);
      } catch (err) {
        throw new Error(err.message.startsWith('capture failed') ? err.message : `capture failed: ${err.message}`);
      }

      const key = buildKey(streamId, trailId);

      try {
        await storage.upload(key, buffer);
      } catch (err) {
        throw new Error(`upload failed: ${err.message}`);
      }

      try {
        await publish(TOPIC, {
          imageId: jobId,
          streamId,
          trailId,
          imagePath: key,
          timestamp: new Date().toISOString(),
        });
      } catch (err) {
        // 이미지는 이미 올라갔다. 되돌리지 않는다 - 삭제도 실패할 수 있어
        // 같은 문제가 한 겹 뒤로 밀릴 뿐이고, 고아 객체는 나중에
        // "DB에 없는 키 정리" 배치로 한 번에 치울 수 있다.
        throw new Error(`publish failed: ${err.message}`);
      }

      await jobs.update(jobId, { status: 'completed', progress: 100, downloaded_count: 1 });
    } catch (err) {
      await jobs.update(jobId, {
        status: 'failed',
        progress: 0,
        downloaded_count: 0,
        error: err.message,
      });
    }
  }

  return { runCapture };
}

module.exports = { createPipeline };
