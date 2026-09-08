import Link from 'next/link';
import NaverMap from '@/components/NaverMap';
import { getStreams, getTrails } from '@/lib/api';
import { formatDate } from '@/lib/format';

export default async function Home() {
  // 관측 지점은 하천별로 다시 묻지 않고 한 번에 받아 세어 쓴다.
  // 화면에 찍히는 개수는 전부 응답에서 직접 센 값이다.
  const [streams, trails] = await Promise.all([getStreams(), getTrails()]);

  const trailCount = new Map<number, number>();
  for (const t of trails) {
    trailCount.set(t.stream_id, (trailCount.get(t.stream_id) ?? 0) + 1);
  }

  return (
    <main className="site-main">
      <div className="page-head">
        <h1 className="page-title">하천을 따라 선 카메라가 남긴 기록</h1>
        <p className="lede">
          관측 지점마다 영상에서 프레임을 뽑아 저장하고, 그 이미지의 분석 결과를 함께 모읍니다.
        </p>
      </div>

      <dl className="metrics">
        <div>
          <dt>하천</dt>
          <dd>{streams.length}</dd>
        </div>
        <div>
          <dt>관측 지점</dt>
          <dd>{trails.length}</dd>
        </div>
      </dl>

      <section className="section">
        <div className="section__head">
          <h2>관측 범위</h2>
          <span className="section__note">선을 누르면 해당 하천으로</span>
        </div>

        <div className="map-frame">
          <NaverMap
            items={streams.map((s) => ({
              id: s.id,
              label: s.name,
              wkt: s.location,
              href: `/streams/${s.id}/`,
            }))}
          />
          <p className="map-frame__caption">
            하천 경로는 등록된 좌표를 그대로 그린 것입니다.
          </p>
        </div>
      </section>

      <section className="section">
        <div className="section__head">
          <h2>하천</h2>
          <span className="section__count mono">{streams.length}</span>
        </div>

        {/* 지도가 못 떠도 여기서 고를 수 있다. 서버 렌더라 HTML에도 남는다. */}
        {streams.length === 0 ? (
          <div className="empty-state">
            <strong>등록된 하천이 없습니다.</strong>
            <p>하천이 등록되면 다음 빌드에서 이 자리에 나타납니다.</p>
          </div>
        ) : (
          <ul className="card-grid">
            {streams.map((s) => (
              <li key={s.id}>
                <Link href={`/streams/${s.id}/`} className="card">
                  <span className="card__title">{s.name}</span>
                  <span className="card__meta">
                    <span>
                      관측 지점 <b className="mono">{trailCount.get(s.id) ?? 0}</b>
                    </span>
                    <span>
                      등록 <b className="mono">{formatDate(s.created_at)}</b>
                    </span>
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  );
}
