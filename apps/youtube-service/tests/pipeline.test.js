const { createPipeline } = require('../src/pipeline');

function makeDeps(overrides = {}) {
  const updates = [];
  return {
    updates,
    deps: {
      capture: jest.fn().mockResolvedValue(Buffer.from([0xff, 0xd8, 0xff, 0x00])),
      storage: { upload: jest.fn().mockResolvedValue(undefined) },
      jobs: {
        update: jest.fn(async (jobId, patch) => { updates.push(patch); return patch; }),
      },
      publish: jest.fn().mockResolvedValue(undefined),
      ...overrides,
    },
  };
}

const JOB = { jobId: 'j1', stream_id: 1, trail_id: 2, youtube_url: 'http://example/x' };

describe('pipeline.js - runCapture', () => {

  test('성공하면 completed와 downloaded_count 1로 끝난다', async () => {
    const { deps, updates } = makeDeps();
    await createPipeline(deps).runCapture(JOB);

    const last = updates[updates.length - 1];
    expect(last.status).toBe('completed');
    expect(last.progress).toBe(100);
    expect(last.downloaded_count).toBe(1);
  });

  test('성공 경로에서 processing을 먼저 기록한다', async () => {
    const { deps, updates } = makeDeps();
    await createPipeline(deps).runCapture(JOB);

    expect(updates[0].status).toBe('processing');
  });

  test('키 형식대로 업로드한다', async () => {
    const { deps } = makeDeps();
    await createPipeline(deps).runCapture(JOB);

    const [key, buffer] = deps.storage.upload.mock.calls[0];
    expect(key).toMatch(/^captures\/1\/2\/.*\.jpg$/);
    expect(Buffer.isBuffer(buffer)).toBe(true);
  });

  test('image.downloaded를 발행하고 imagePath가 업로드한 키와 같다', async () => {
    const { deps } = makeDeps();
    await createPipeline(deps).runCapture(JOB);

    const [topic, message] = deps.publish.mock.calls[0];
    const [uploadedKey] = deps.storage.upload.mock.calls[0];

    expect(topic).toBe('image.downloaded');
    expect(message.imagePath).toBe(uploadedKey);
    expect(message.imageId).toBe('j1');
    expect(message.streamId).toBe(1);
    expect(message.trailId).toBe(2);
    expect(message.timestamp).toEqual(expect.any(String));
  });

  test('캡처가 실패하면 failed와 error를 기록한다', async () => {
    const { deps, updates } = makeDeps({
      capture: jest.fn().mockRejectedValue(new Error('capture failed: no such source')),
    });
    await createPipeline(deps).runCapture(JOB);

    const last = updates[updates.length - 1];
    expect(last.status).toBe('failed');
    expect(last.error).toMatch(/capture failed/);
    expect(last.downloaded_count).toBe(0);
  });

  test('캡처가 실패하면 업로드도 발행도 하지 않는다', async () => {
    const { deps } = makeDeps({
      capture: jest.fn().mockRejectedValue(new Error('capture failed: boom')),
    });
    await createPipeline(deps).runCapture(JOB);

    expect(deps.storage.upload).not.toHaveBeenCalled();
    expect(deps.publish).not.toHaveBeenCalled();
  });

  test('발행이 실패해도 던지지 않고 failed로 기록한다', async () => {
    const { deps, updates } = makeDeps({
      publish: jest.fn().mockRejectedValue(new Error('broker down')),
    });

    // 비동기로 도는 코드라 여기서 새는 예외는 프로세스를 내린다.
    await expect(createPipeline(deps).runCapture(JOB)).resolves.toBeUndefined();

    const last = updates[updates.length - 1];
    expect(last.status).toBe('failed');
    expect(last.error).toMatch(/publish failed/);
  });

  test('실패 기록(마지막 jobs.update)이 거부돼도 던지지 않는다', async () => {
    const update = jest.fn()
      .mockResolvedValueOnce(undefined) // processing 기록은 성공
      .mockRejectedValueOnce(new Error('redis down')); // failed 기록은 실패
    const { deps } = makeDeps({
      capture: jest.fn().mockRejectedValue(new Error('capture failed: boom')),
      jobs: { update },
    });

    await expect(createPipeline(deps).runCapture(JOB)).resolves.toBeUndefined();
  });

  test('맨 처음 processing 기록이 거부돼도 던지지 않는다', async () => {
    const { deps } = makeDeps({
      jobs: { update: jest.fn().mockRejectedValue(new Error('redis down')) },
    });

    await expect(createPipeline(deps).runCapture(JOB)).resolves.toBeUndefined();
  });

  test('capture가 Error가 아닌 값을 던져도 error는 capture failed로 시작한다', async () => {
    const { deps, updates } = makeDeps({
      capture: jest.fn().mockRejectedValue('boom'),
    });
    await createPipeline(deps).runCapture(JOB);

    const last = updates[updates.length - 1];
    expect(last.status).toBe('failed');
    expect(last.error).toMatch(/^capture failed:/);
  });
});
