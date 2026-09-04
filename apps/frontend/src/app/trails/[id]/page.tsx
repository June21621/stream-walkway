import Link from 'next/link';
import { getCaptures, getTrail, getTrails, imageUrl } from '@/lib/api';

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
      )}
    </main>
  );
}
