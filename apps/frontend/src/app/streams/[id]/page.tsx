import Link from 'next/link';
import NaverMap from '@/components/NaverMap';
import StatusChip from '@/components/StatusChip';
import { getStream, getStreams, getTrails } from '@/lib/api';
import { formatDate } from '@/lib/format';

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
    <main className="site-main">
      <nav className="breadcrumb">
        <Link href="/">← 하천 목록</Link>
      </nav>

      <div className="page-head">
        <h1 className="page-title">{stream.name}</h1>
      </div>

      <dl className="facts">
        <div>
          <dt>등록</dt>
          <dd className="mono">{formatDate(stream.created_at)}</dd>
        </div>
        <div>
          <dt>관측 지점</dt>
          <dd className="mono">{trails.length}</dd>
        </div>
        <div className="facts__wide">
          <dt>경로 좌표</dt>
          <dd>
            <code className="wkt">{stream.location}</code>
          </dd>
        </div>
      </dl>

      <section className="section">
        <div className="section__head">
          <h2>지점 배치</h2>
          <span className="section__note">마커를 누르면 해당 지점으로</span>
        </div>

        <div className="map-frame">
          <NaverMap
            items={[
              {
                id: stream.id,
                label: stream.name,
                wkt: stream.location,
                href: `/streams/${stream.id}/`,
              },
              ...trails.map((t) => ({
                id: t.id,
                label: t.camera_number,
                wkt: t.location,
                href: `/trails/${t.id}/`,
              })),
            ]}
          />
          <p className="map-frame__caption">
            선은 하천 경로, 마커는 카메라가 선 자리입니다.
          </p>
        </div>
      </section>

      <section className="section">
        <div className="section__head">
          <h2>관측 지점</h2>
          <span className="section__count mono">{trails.length}</span>
        </div>

        {trails.length === 0 ? (
          <div className="empty-state">
            <strong>등록된 관측 지점이 없습니다.</strong>
            <p>이 하천에는 아직 카메라가 배치되지 않았습니다.</p>
          </div>
        ) : (
          <ul className="card-grid">
            {trails.map((t) => (
              <li key={t.id}>
                <Link href={`/trails/${t.id}/`} className="card">
                  <span className="card__title">카메라 {t.camera_number}</span>
                  <span className="card__meta">
                    <span>
                      방향 <b>{t.direction}</b>
                    </span>
                  </span>
                  <StatusChip status={t.status} />
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  );
}
