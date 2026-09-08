// 화면에 찍히는 시각 표기.
//
// 빌드 타임에 문자열이 확정되므로 빌드 머신의 로컬 타임존에 끌려가면 안 된다.
// 서울에서 보는 관측 기록이니 표기는 항상 KST 로 고정한다.
//
// 조립은 formatToParts 로 한다. 로케일이 내는 완성 문자열("2026. 09. 08. 14:23")은
// 구분자가 로케일/런타임 사정에 따라 달라져서 문자열을 다시 자르면 깨진다.

const ZONE = 'Asia/Seoul';

const formatter = new Intl.DateTimeFormat('ko-KR', {
  timeZone: ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

interface Fields {
  year: string;
  month: string;
  day: string;
  hour: string;
  minute: string;
}

function fields(iso: string): Fields | null {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return null;

  const out: Record<string, string> = {};
  for (const { type, value } of formatter.formatToParts(d)) out[type] = value;
  return out as unknown as Fields;
}

/** ISO 시각을 `2026-09-08 14:23` (KST) 로. 파싱 불가면 원문을 그대로 돌려준다. */
export function formatDateTime(iso: string): string {
  const f = fields(iso);
  if (!f) return iso;
  return `${f.year}-${f.month}-${f.day} ${f.hour}:${f.minute}`;
}

/** ISO 시각을 `2026-09-08` (KST) 로. 파싱 불가면 원문을 그대로 돌려준다. */
export function formatDate(iso: string): string {
  const f = fields(iso);
  if (!f) return iso;
  return `${f.year}-${f.month}-${f.day}`;
}

/** WKT 를 화면용으로 줄인다. 좌표가 길면 앞 두 쌍만 보이고 나머지는 개수로. */
export function summarizeWkt(wkt: string): string {
  const inner = /\(([^)]*)\)/.exec(wkt)?.[1]?.trim();
  if (!inner) return wkt;

  const points = inner.split(',').map((p) => p.trim()).filter(Boolean);
  if (points.length <= 2) return points.join(', ');
  return `${points.slice(0, 2).join(', ')} 외 ${points.length - 2}개 지점`;
}
