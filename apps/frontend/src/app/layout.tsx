import './globals.css';

export const metadata = {
  title: 'Stream Walkway',
  description: '하천 산책로 정보 분석 시스템',
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
