// node --test src/lib/format.test.mts
import assert from 'node:assert/strict';
import { test } from 'node:test';

const { formatDate, formatDateTime } = await import('./format.ts');

test('UTC 시각을 KST 로 옮겨 찍는다', () => {
  // 2026-09-08T01:45:00Z 는 서울에서 같은 날 10:45 다.
  assert.equal(formatDateTime('2026-09-08T01:45:00Z'), '2026-09-08 10:45');
});

test('KST 로 날짜가 넘어가는 시각도 맞게 넘긴다', () => {
  // UTC 로는 8일 밤이지만 서울은 이미 9일 새벽이다.
  assert.equal(formatDateTime('2026-09-08T15:30:00Z'), '2026-09-09 00:30');
  assert.equal(formatDate('2026-09-08T15:30:00Z'), '2026-09-09');
});

test('빌드 머신 타임존과 무관하게 같은 값이 나온다', () => {
  // 타임존 고정을 안 하면 이 값이 머신마다 달라진다.
  assert.equal(formatDate('2026-01-01T00:00:00Z'), '2026-01-01');
});

test('파싱 안 되는 문자열은 원문 그대로', () => {
  assert.equal(formatDateTime('언젠가'), '언젠가');
  assert.equal(formatDate(''), '');
});
