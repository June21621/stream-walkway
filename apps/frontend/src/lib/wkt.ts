/** WKT 순서 그대로 `[경도, 위도]`. 네이버 지도는 `(위도, 경도)`라 뒤집어 써야 한다. */
export type LngLat = [number, number];

const WKT = /^\s*(POINT|LINESTRING)\s*(?:\(([^)]*)\)|(EMPTY))\s*$/i;

/**
 * 백엔드가 주는 WKT를 좌표 배열로 바꾼다. POINT는 원소 1개, LINESTRING은 n개.
 *
 * `POINT EMPTY` / `LINESTRING EMPTY`는 빈 배열을 준다. writer가 지금은 거부하지만
 * 그 검증이 들어오기 전에 저장된 행이 남아 있을 수 있고, 빌드 타임에 그리는
 * 화면이라 예외를 던지면 빌드 전체가 깨진다. 못 그리는 것과 배포가 막히는 건 다르다.
 */
export function parseWkt(wkt: string): LngLat[] {
  const m = WKT.exec(wkt);
  if (!m) throw new Error(`지원하지 않는 WKT: ${wkt}`);
  if (m[3]) return [];

  return m[2].split(',').map((pair) => {
    // Z/M 좌표가 섞여 들어와도 앞 두 개만 쓴다.
    const [lng, lat] = pair.trim().split(/\s+/).map(Number);
    if (!Number.isFinite(lng) || !Number.isFinite(lat)) {
      throw new Error(`좌표를 읽을 수 없다: "${pair.trim()}" (${wkt})`);
    }
    return [lng, lat] as LngLat;
  });
}
