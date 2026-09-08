const { captureFromTestPattern, createCapture, fileOffsetFor } = require('../src/capture');

// 이 파일만 진짜 ffmpeg를 실행한다. mock이 거짓말할 수 있는 지점이라
// 실물이 필요하다. 나머지 테스트는 캡처를 주입받아 가짜로 대체한다.
describe('capture.js', () => {

  test('captureFromTestPattern - JPEG 바이트를 반환한다', async () => {
    const buf = await captureFromTestPattern();

    expect(Buffer.isBuffer(buf)).toBe(true);
    expect(buf.length).toBeGreaterThan(1000);
    // JPEG 매직 바이트 FF D8 FF
    expect(buf[0]).toBe(0xff);
    expect(buf[1]).toBe(0xd8);
    expect(buf[2]).toBe(0xff);
  }, 30000);

  test('captureFromTestPattern - 타임아웃이 걸리면 capture failed로 거부한다', async () => {
    await expect(captureFromTestPattern({ timeoutMs: 1 }))
      .rejects.toThrow(/capture failed/);
  }, 30000);

  test('createCapture - CAPTURE_SOURCE가 testsrc면 테스트 패턴을 쓴다', async () => {
    const capture = createCapture({ CAPTURE_SOURCE: 'testsrc' });
    const buf = await capture('ignored-url');

    expect(buf[0]).toBe(0xff);
    expect(buf[1]).toBe(0xd8);
  }, 30000);

  test('createCapture - 알 수 없는 CAPTURE_SOURCE는 즉시 던진다', () => {
    expect(() => createCapture({ CAPTURE_SOURCE: 'nope' }))
      .toThrow(/unknown CAPTURE_SOURCE/);
  });
});

// 영상 파일 하나로 여러 관측 지점을 대신할 때, 지점마다 다른 프레임이 나와야
// 한다. 오프셋이 고정이면 모든 지점 사진이 똑같아진다.
describe('fileOffsetFor', () => {

  test('step/span이 없으면 base를 그대로 쓴다 (기존 동작)', () => {
    expect(fileOffsetFor({ CAPTURE_FILE_OFFSET_SEC: '12' }, 7)).toBe(12);
    expect(fileOffsetFor({}, 7)).toBe(0);
  });

  test('trailId가 다르면 오프셋이 다르다', () => {
    const env = {
      CAPTURE_FILE_OFFSET_SEC: '10',
      CAPTURE_FILE_OFFSET_STEP_SEC: '37',
      CAPTURE_FILE_OFFSET_SPAN_SEC: '100',
    };
    const offsets = [7, 8, 9].map((id) => fileOffsetFor(env, id));

    expect(new Set(offsets).size).toBe(3);
    expect(offsets).toEqual([69, 106, 43]);
  });

  test('오프셋은 base와 base+span 사이에 머문다', () => {
    const env = {
      CAPTURE_FILE_OFFSET_SEC: '10',
      CAPTURE_FILE_OFFSET_STEP_SEC: '37',
      CAPTURE_FILE_OFFSET_SPAN_SEC: '100',
    };
    for (let id = 0; id < 50; id += 1) {
      const off = fileOffsetFor(env, id);
      expect(off).toBeGreaterThanOrEqual(10);
      expect(off).toBeLessThan(110);
    }
  });

  test('trailId가 없으면 base를 쓴다', () => {
    const env = {
      CAPTURE_FILE_OFFSET_SEC: '10',
      CAPTURE_FILE_OFFSET_STEP_SEC: '37',
      CAPTURE_FILE_OFFSET_SPAN_SEC: '100',
    };
    expect(fileOffsetFor(env, undefined)).toBe(10);
  });
});
