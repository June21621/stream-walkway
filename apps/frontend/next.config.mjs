/** @type {import('next').NextConfig} */
const nextConfig = {
  // 정적 export. Node 런타임 서버 없이 out/ 을 CDN에 올려 서빙한다.
  output: 'export',

  // export 모드에서는 next/image 최적화 로더를 쓸 수 없다.
  // 이미지 리사이즈/WebP 변환은 백엔드 저장 시점 또는 CDN에서 처리한다.
  images: { unoptimized: true },

  // out/trails/index.html 형태로 생성해 CDN 디렉토리 인덱스 규칙에 맞춘다.
  // (S3/CloudFront 는 /trails -> /trails.html 매핑을 해주지 않는다)
  trailingSlash: true,
};

export default nextConfig;
