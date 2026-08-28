const { buildKey } = require('../src/storage');

describe('storage.js - buildKey', () => {

  test('captures/{streamId}/{trailId}/{ISO}.jpg 형식으로 만든다', () => {
    const key = buildKey(1, 2, new Date('2026-08-27T10:15:00.000Z'));
    expect(key).toBe('captures/1/2/2026-08-27T10-15-00Z.jpg');
  });

  test('콜론을 대시로 바꾼다 - URL과 파일명에서 안전하도록', () => {
    const key = buildKey(1, 1, new Date('2026-01-02T03:04:05.000Z'));
    expect(key).not.toContain(':');
    expect(key).toBe('captures/1/1/2026-01-02T03-04-05Z.jpg');
  });

  test('밀리초를 버린다 - 15분 간격 표본이라 초 단위로 충분하다', () => {
    const key = buildKey(1, 1, new Date('2026-01-02T03:04:05.678Z'));
    expect(key).toBe('captures/1/1/2026-01-02T03-04-05Z.jpg');
  });

  test('streamId와 trailId가 경로에 그대로 들어간다', () => {
    expect(buildKey(42, 7, new Date('2026-01-01T00:00:00.000Z')))
      .toBe('captures/42/7/2026-01-01T00-00-00Z.jpg');
  });
});
