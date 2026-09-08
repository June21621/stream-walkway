import Link from 'next/link';
import StatusChip from '@/components/StatusChip';
import { getCaptures, getStream, getTrail, getTrails, imageUrl } from '@/lib/api';
import { formatDate, formatDateTime } from '@/lib/format';

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
  const [stream, captures] = await Promise.all([
    getStream(trail.stream_id),
    getCaptures({ trail_id: trail.id }),
  ]);

  // 응답 순서에 기대지 않고 최신순을 여기서 확정한다. 맨 앞 한 장을 크게 쓴다.
  //
  // 문자열 사전순으로 비교하면 안 된다. Instant.toString() 은 나노초가 0이면
  // 소수부를 통째로 생략해서, 같은 초 안에서 "...00Z" 와 "...00.1Z" 를 비교할 때
  // 'Z'(90) > '.'(46) 이 되어 순서가 뒤집힌다.
  const sorted = [...captures].sort(
    (a, b) => Date.parse(b.created_at) - Date.parse(a.created_at),
  );
  const [latest, ...rest] = sorted;

  return (
    <main className="site-main">
      <nav className="breadcrumb">
        <Link href={`/streams/${trail.stream_id}/`}>← {stream.name}</Link>
      </nav>

      <div className="page-head">
        <h1 className="page-title">카메라 {trail.camera_number}</h1>
        <p className="lede">
          {stream.name}에 설치된 지점입니다.
          {sorted.length > 0 && ' 아래는 이 카메라가 남긴 프레임입니다.'}
        </p>
      </div>

      <dl className="facts">
        <div>
          <dt>상태</dt>
          <dd>
            <StatusChip status={trail.status} />
          </dd>
        </div>
        <div>
          <dt>방향</dt>
          <dd>{trail.direction}</dd>
        </div>
        <div>
          <dt>등록</dt>
          <dd className="mono">{formatDate(trail.created_at)}</dd>
        </div>
        <div className="facts__wide">
          <dt>좌표</dt>
          <dd>
            <code className="wkt">{trail.location}</code>
          </dd>
        </div>
      </dl>

      <section className="section">
        <div className="section__head">
          <h2>캡처</h2>
          <span className="section__count mono">{sorted.length}</span>
        </div>

        {sorted.length === 0 ? (
          <div className="empty-state">
            <strong>캡처된 이미지가 없습니다.</strong>
            <p>이 지점의 영상이 들어오면 다음 빌드부터 여기에 쌓입니다.</p>
          </div>
        ) : (
          <>
            {ANALYSIS_IS_STUB && (
              <div className="callout">
                <strong>분석 모델 미연동</strong>
                <p>상태와 신뢰도는 파이프라인 검증용 고정값입니다. 실제 판정이 아닙니다.</p>
              </div>
            )}

            <figure className="capture-lead">
              {/* next/image 최적화는 정적 export에서 꺼져 있다. CLAUDE.md 참고 */}
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={imageUrl(latest.image_path)}
                alt={`카메라 ${trail.camera_number}의 ${formatDateTime(latest.created_at)} 캡처`}
              />
              <figcaption>
                <span className="capture-time__label">
                  최근 <span className="capture-time">{formatDateTime(latest.created_at)} KST</span>
                </span>
                <span className="reading">
                  <span className="reading__value">{latest.road_status}</span>
                  <span className="reading__scale">
                    신뢰도 <span className="mono">{Math.round(latest.confidence * 100)}%</span>
                  </span>
                </span>
              </figcaption>
            </figure>

            {rest.length > 0 && (
              <ul className="capture-grid">
                {rest.map((c) => (
                  <li key={c.id}>
                    <figure>
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img
                        src={imageUrl(c.image_path)}
                        alt={`카메라 ${trail.camera_number}의 ${formatDateTime(c.created_at)} 캡처`}
                        loading="lazy"
                      />
                      <figcaption>
                        <span>
                          {c.road_status} {Math.round(c.confidence * 100)}%
                        </span>
                        <span className="capture-time">{formatDateTime(c.created_at)}</span>
                      </figcaption>
                    </figure>
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
      </section>
    </main>
  );
}
