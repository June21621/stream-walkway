// node --test 로 실행한다 (Node 24 내장 러너 + 타입 스트리핑).
//   node --test src/lib/api.test.mts
import assert from 'node:assert/strict';
import { test } from 'node:test';

process.env.NEXT_PUBLIC_IMAGE_BASE_URL = 'http://localhost:9000/captures/';

const { buildQuery, imageUrl } = await import('./api.ts');

test('buildQuery: undefined 값은 뺀다', () => {
  assert.equal(buildQuery({ stream_id: 1, trail_id: undefined }), '?stream_id=1');
});

test('buildQuery: 남는 값이 없으면 빈 문자열', () => {
  assert.equal(buildQuery({ stream_id: undefined }), '');
});

test('buildQuery: 여러 값은 &로 잇는다', () => {
  assert.equal(buildQuery({ stream_id: 1, limit: 20 }), '?stream_id=1&limit=20');
});

test('imageUrl: base 끝 슬래시와 키가 겹쳐도 //가 안 생긴다', () => {
  assert.equal(
    imageUrl('captures/1/7/2026-09-03T01-45-00Z.jpg'),
    'http://localhost:9000/captures/captures/1/7/2026-09-03T01-45-00Z.jpg',
  );
});
