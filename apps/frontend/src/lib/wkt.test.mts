// node --test src/lib/wkt.test.mts
import assert from 'node:assert/strict';
import { test } from 'node:test';

const { parseWkt } = await import('./wkt.ts');

test('POINT는 원소 1개', () => {
  assert.deepEqual(parseWkt('POINT(127.01 37.5)'), [[127.01, 37.5]]);
});

test('LINESTRING은 좌표 순서를 유지한다', () => {
  assert.deepEqual(parseWkt('LINESTRING(126.97 37.55, 126.98 37.56)'), [
    [126.97, 37.55],
    [126.98, 37.56],
  ]);
});

test('경도가 앞, 위도가 뒤 (뒤집히면 안 된다)', () => {
  const [[lng, lat]] = parseWkt('POINT(127.01 37.5)');
  assert.equal(lng, 127.01);
  assert.equal(lat, 37.5);
});

test('EMPTY는 던지지 않고 빈 배열', () => {
  assert.deepEqual(parseWkt('POINT EMPTY'), []);
  assert.deepEqual(parseWkt('LINESTRING EMPTY'), []);
});

test('Z 좌표가 섞여도 앞 두 개만 쓴다', () => {
  assert.deepEqual(parseWkt('POINT(127.01 37.5 12)'), [[127.01, 37.5]]);
});

test('공백이 들쭉날쭉해도 읽는다', () => {
  assert.deepEqual(parseWkt('  LINESTRING (126.97   37.55,126.98 37.56) '), [
    [126.97, 37.55],
    [126.98, 37.56],
  ]);
});

test('모르는 형식은 던진다', () => {
  assert.throws(() => parseWkt('POLYGON((0 0, 1 1, 1 0, 0 0))'), /지원하지 않는 WKT/);
  assert.throws(() => parseWkt('POINT(하나 둘)'), /좌표를 읽을 수 없다/);
});
