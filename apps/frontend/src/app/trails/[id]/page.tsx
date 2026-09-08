import Link from 'next/link';
import { getCaptures, getTrail, getTrails, imageUrl } from '@/lib/api';

// ml-service 에 실제 모델이 없어 road_status 는 항상 "양호", confidence 는 항상
// 0.95 다. 값만 놓고 보면 진짜 분석 결과처럼 읽히므로 화면에 밝힌다.
//
// 실제 모델이 붙으면 이 상수와 아래 안내 문구를 지울 것.
// 남겨두면 진짜 분석을 가짜라고 말하는 상태가 된다.
const ANALYSIS_IS_STUB = true;

export async function generateStaticParams() {
  const trails = await getTrails();
  return trails.map((t) => ({ id: String(t.id) }));
}

export default async function TrailDetail({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const trail = await getTrail(id);
  const captures = await getCaptures({ trail_id: trail.id });

  return (
    <main>
      <nav>
        <Link href={`/streams/${trail.stream_id}/`}>← 하천으로</Link>
      </nav>

      <h1>카메라 {trail.camera_number}</h1>
      <dl>
        <dt>위치</dt>
        <dd>
          <code>{trail.location}</code>
        </dd>
        <dt>방향</dt>
        <dd>{trail.direction}</dd>
        <dt>상태</dt>
        <dd>{trail.status}</dd>
      </dl>

      <h2>캡처 ({captures.length})</h2>
      {captures.length === 0 ? (
        <p className="empty">캡처된 이미지가 없습니다.</p>
      ) : (
        <>
          {ANALYSIS_IS_STUB && (
            <p className="note">
              분석 모델 미연동 — 상태·신뢰도는 파이프라인 검증용 고정값입니다.
            </p>
          )}
          <ul className="captures">
            {captures.map((c) => (
              <li key={c.id}>
                <figure style={{ margin: 0 }}>
                  {/* next/image 최적화는 정적 export에서 꺼져 있다. CLAUDE.md 참고 */}
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={imageUrl(c.image_path)} alt={`캡처 ${c.id}`} loading="lazy" />
                  <figcaption>
                    {c.road_status} ({Math.round(c.confidence * 100)}%)
                    <br />
                    {c.created_at}
                  </figcaption>
                </figure>
              </li>
            ))}
          </ul>
        </>
      )}
    </main>
  );
}
