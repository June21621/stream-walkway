import Link from 'next/link';
import NaverMap from '@/components/NaverMap';
import { getStreams } from '@/lib/api';

export default async function Home() {
  const streams = await getStreams();

  return (
    <main>
      <h1>하천 목록</h1>
      <p>지도에서 하천을 고르거나 아래 목록에서 선택하세요. 총 {streams.length}개</p>

      <NaverMap
        items={streams.map((s) => ({
          id: s.id,
          label: s.name,
          wkt: s.location,
          href: `/streams/${s.id}/`,
        }))}
      />

      {/* 지도가 못 떠도 여기서 고를 수 있다. 서버 렌더라 HTML에도 남는다. */}
      {streams.length === 0 ? (
        <p className="empty">등록된 하천이 없습니다.</p>
      ) : (
        <ul className="cards" style={{ marginTop: '1.5rem' }}>
          {streams.map((s) => (
            <li key={s.id}>
              <Link href={`/streams/${s.id}/`}>{s.name}</Link>
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
