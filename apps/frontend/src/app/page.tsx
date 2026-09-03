import Link from 'next/link';
import { getStreams } from '@/lib/api';

export default async function Home() {
  const streams = await getStreams();

  return (
    <main>
      <h1>하천 목록</h1>
      <p>총 {streams.length}개</p>

      {streams.length === 0 ? (
        <p className="empty">등록된 하천이 없습니다.</p>
      ) : (
        <ul className="cards">
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
