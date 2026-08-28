const { captureFromTestPattern, createCapture } = require('../src/capture');

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
