const { buildKey } = require('./storage');

const TOPIC = 'image.downloaded';

// 던져진 값이 Error가 아닐 수도 있다(문자열, 코드만 있는 객체 등).
// .message가 없으면 접두사 부착이 TypeError로 죽어 원래 실패 사유가
// "Cannot read properties of undefined"로 뒤덮인다.
function errorMessage(err) {
  return err?.message ?? String(err);
}

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
        const msg = errorMessage(err);
        throw new Error(msg.startsWith('capture failed') ? msg : `capture failed: ${msg}`);
      }

      const key = buildKey(streamId, trailId);

      try {
        await storage.upload(key, buffer);
      } catch (err) {
        throw new Error(`upload failed: ${errorMessage(err)}`);
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
        throw new Error(`publish failed: ${errorMessage(err)}`);
      }

      await jobs.update(jobId, { status: 'completed', progress: 100, downloaded_count: 1 });
    } catch (err) {
      // 여기서도 jobs.update가 실패할 수 있다 - 예를 들어 맨 처음
      // processing 기록이 Redis 장애로 실패해 이 catch로 떨어졌다면,
      // 같은 Redis 장애 때문에 실패 기록마저 거부(reject)된다. 이
      // update를 감싸지 않으면 그 거부가 runCapture 밖으로 새 나가고,
      // 202 응답 이후 비동기로 도는 코드라 프로세스가 죽는다. 그래서
      // 실패를 "기록하려는 시도"조차 던지지 않게 한 번 더 감싼다.
      try {
        await jobs.update(jobId, {
          status: 'failed',
          progress: 0,
          downloaded_count: 0,
          error: errorMessage(err),
        });
      } catch (updateErr) {
        console.error(`[pipeline] 작업 실패 기록도 실패함 jobId=${jobId}:`, errorMessage(updateErr));
      }
    }
  }

  return { runCapture };
}

module.exports = { createPipeline };
