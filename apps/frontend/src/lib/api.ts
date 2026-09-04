import type { Capture, Stream, Trail } from './types';

// 빌드 타임에 호출된다. 실패하면 빌드가 깨지는 게 의도된 동작이다 —
// 데이터를 못 받은 채로 사이트가 교체되는 것보다 낫다. CLAUDE.md 참고.

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL;
const IMAGE_BASE = process.env.NEXT_PUBLIC_IMAGE_BASE_URL;

/** 끝의 슬래시를 지워 `//` 가 생기지 않게 한다. */
function trimSlash(base: string): string {
  return base.replace(/\/+$/, '');
}

/** undefined 값은 빼고 쿼리 문자열을 만든다. 남는 게 없으면 빈 문자열. */
export function buildQuery(params: Record<string, string | number | undefined>): string {
  const entries = Object.entries(params).filter(([, v]) => v !== undefined);
  if (entries.length === 0) return '';
  return '?' + entries.map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`).join('&');
}

/**
 * 객체 키를 브라우저용 URL로 조립한다.
 *
 * image_path 는 `captures/1/7/....jpg` 형태의 키이고 버킷 이름도 `captures` 라
 * 실제 주소에 `captures` 가 두 번 들어간다. NEXT_PUBLIC_IMAGE_BASE_URL 에
 * 버킷까지 포함시키면(`http://localhost:9000/captures`) 그 중복이 그대로 맞는다.
 * 백엔드가 키에서 접두사를 제거하면 이 환경변수만 고치면 된다.
 */
export function imageUrl(imagePath: string): string {
  if (!IMAGE_BASE) throw new Error('NEXT_PUBLIC_IMAGE_BASE_URL 이 설정되지 않았습니다.');
  return `${trimSlash(IMAGE_BASE)}/${imagePath.replace(/^\/+/, '')}`;
}

async function get<T>(path: string): Promise<T> {
  if (!API_BASE) throw new Error('NEXT_PUBLIC_API_BASE_URL 이 설정되지 않았습니다.');
  const url = `${trimSlash(API_BASE)}${path}`;
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`GET ${url} 실패 (${res.status}): ${await res.text()}`);
  }
  return res.json() as Promise<T>;
}

export const getStreams = () => get<Stream[]>('/api/streams');
export const getStream = (id: number | string) => get<Stream>(`/api/streams/${id}`);

export const getTrails = (streamId?: number) =>
  get<Trail[]>(`/api/trails${buildQuery({ stream_id: streamId })}`);
export const getTrail = (id: number | string) => get<Trail>(`/api/trails/${id}`);

export const getCaptures = (params: { stream_id?: number; trail_id?: number; limit?: number } = {}) =>
  get<Capture[]>(`/api/captures${buildQuery(params)}`);
