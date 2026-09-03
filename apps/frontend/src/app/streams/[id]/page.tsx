import Link from 'next/link';
import { getStream, getStreams, getTrails } from '@/lib/api';

export async function generateStaticParams() {
  const streams = await getStreams();
  return streams.map((s) => ({ id: String(s.id) }));
}

export default async function StreamDetail({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const [stream, trails] = await Promise.all([getStream(id), getTrails(Number(id))]);

  return (
    <main>
      <nav>
        <Link href="/">← 하천 목록</Link>
      </nav>

      <h1>{stream.name}</h1>
      <dl>
        <dt>경로</dt>
        <dd>
          <code>{stream.location}</code>
        </dd>
        <dt>등록</dt>
        <dd>{stream.created_at}</dd>
      </dl>

      <h2>카메라 관측 지점 ({trails.length})</h2>
      {trails.length === 0 ? (
        <p className="empty">등록된 관측 지점이 없습니다.</p>
      ) : (
        <ul className="cards">
          {trails.map((t) => (
            <li key={t.id}>
              <Link href={`/trails/${t.id}/`}>
                {t.camera_number} · {t.direction} · {t.status}
              </Link>
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
