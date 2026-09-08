import type { Metadata } from 'next';
import Link from 'next/link';
import { IBM_Plex_Mono, IBM_Plex_Sans_KR } from 'next/font/google';
import { formatDateTime } from '@/lib/format';
import './globals.css';

// 문장은 sans, 수치·좌표·시각은 mono. 같은 가족(IBM Plex)이라 섞여도 어긋나지 않는다.
//
// subsets 는 preload 대상만 고르는 옵션이고, 한글 청크를 포함한 모든 폰트 파일은
// 빌드 타임에 내려받아 셀프호스팅된다. 브라우저는 unicode-range 로 실제 쓰인
// 글자가 든 청크만 가져간다.
const sans = IBM_Plex_Sans_KR({
  subsets: ['latin'],
  weight: ['400', '600'],
  variable: '--font-sans',
  display: 'swap',
});

const mono = IBM_Plex_Mono({
  subsets: ['latin'],
  weight: ['400'],
  variable: '--font-mono',
  display: 'swap',
});

export const metadata: Metadata = {
  title: '하천 산책로 관측',
  description: '하천을 따라 설치된 관측 지점의 캡처와 분석 결과를 모아 보는 정적 사이트',
};

// 정적 사이트라 화면의 모든 값은 빌드 시점의 스냅샷이다. 언제 구운 것인지
// 밝혀두지 않으면 실시간 데이터로 오해할 여지가 있어 푸터에 기준 시각을 적는다.
const BUILT_AT = formatDateTime(new Date().toISOString());

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="ko" className={`${sans.variable} ${mono.variable}`}>
      <body>
        <header className="site-header">
          <div className="site-header__inner">
            <Link href="/" className="wordmark">
              하천 산책로 관측
              <span className="wordmark__sub">stream walkway</span>
            </Link>
          </div>
        </header>

        {children}

        <footer className="site-footer">
          <div className="site-footer__inner">
            <p>관측 데이터는 빌드 시점에 게이트웨이에서 받아 정적 페이지로 굽습니다.</p>
            <p>
              데이터 기준 <span className="mono">{BUILT_AT} KST</span>
            </p>
          </div>
        </footer>
      </body>
    </html>
  );
}
