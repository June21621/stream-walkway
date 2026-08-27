# Frontend

Stream Walkway 웹 프론트엔드. Next.js 16 App Router + React 19.

**정적 사이트(static export)로 빌드해 CDN에 배포한다. Node 런타임 서버를 두지 않는다.**

---

## 배포 모델

```
[주기 실행]
  → 백엔드 게이트웨이에서 트레일/분석 결과 JSON 조회
  → next build (output: 'export')
  → out/ 검증 후 CDN에 원자적으로 교체
```

`npm run build` 결과물은 `out/` 디렉터리이며, 정적 HTML과 `_next/static/` 에셋만 들어 있다.
서버 런타임 산출물이 없으므로 S3, CloudFront, Nginx 등 어떤 정적 호스트에도 그대로 올릴 수 있다.

`npm run start`(Node 서버)는 이 배포 모델에서 사용하지 않는다.

---

## 데이터 페칭 규약

데이터는 **빌드 타임에 서버 컴포넌트에서 fetch**하는 것을 기본으로 한다.
이는 SSR이 아니라 정적 생성(SSG)의 정상 동작이며, `output: 'export'`에서 권장되는 방식이다.

빌드 시점에 값을 알 수 없는 것(실시간 수치, 로그인 후 개인 데이터 등)만
클라이언트 컴포넌트에서 fetch한다.

백엔드는 API 게이트웨이(`apps/backend`)만 호출한다.
Reader/Writer 서비스를 프론트에서 직접 호출하지 않는다.

### 사용하지 않는 기능

정적 export와 호환되지 않거나, 배포 모델을 서버 의존으로 되돌리는 기능은 쓰지 않는다.

- Route Handlers (`app/api/*`)
- `middleware.ts`
- 요청 시점 서버 렌더링(SSR), ISR
- 인증 로직 — 인증은 백엔드 게이트웨이가 담당한다

---

## 라우팅

동적 라우트(`/trails/[id]`)는 `generateStaticParams`로 **빌드 타임에 전체 경로를 생성**한다.
백엔드가 트레일 목록 JSON을 제공하므로 빌드 시점에 모든 id를 알 수 있다.

```tsx
// app/trails/[id]/page.tsx
export async function generateStaticParams() {
  const trails = await fetch(`${process.env.NEXT_PUBLIC_API_BASE_URL}/api/trails`)
    .then((r) => r.json());
  return trails.map((t) => ({ id: String(t.id) }));
}
```

각 트레일이 실제 HTML 파일로 생성되므로 호스팅 쪽 SPA 폴백(404 → index.html) 설정은 **필요 없다**.
App Router에서는 그 방식이 동작하지도 않는다 — 폴백으로 받은 `index.html`은
루트 경로의 RSC 페이로드를 담고 있어서, 주소는 `/trails/123`인데 홈 화면이 렌더된다.

`trailingSlash: true`이므로 경로는 `out/trails/123/index.html` 형태로 생성된다.
S3/CloudFront가 `/trails/123` → `/trails/123.html` 매핑을 해주지 않기 때문에,
디렉터리 인덱스 규칙에 맞춰 별도 rewrite 없이 서빙되도록 한 설정이다.

---

## 환경변수

| 변수 | 용도 |
|---|---|
| `NEXT_PUBLIC_API_BASE_URL` | 백엔드 게이트웨이 주소 |

`.env.local.example`을 `.env.local`로 복사해서 쓴다.

> **`NEXT_PUBLIC_` 값은 빌드 타임에 번들에 그대로 박힌다.**
>
> - 런타임에 바꿀 수 없다. 환경(dev/staging/prod)마다 **별도로 빌드**해야 한다.
> - 브라우저에서 그대로 보인다. 공개해도 되는 주소만 넣을 것.
> - 클러스터 내부 주소나 비밀값을 넣지 말 것.

---

## 이미지

`output: 'export'`에서는 `next/image` 최적화 로더가 동작하지 않아 `images.unoptimized: true`로 두었다.
리사이즈/WebP 변환은 프론트가 아니라 **백엔드 저장 시점 또는 CDN**에서 처리한다.

---

## 로컬 개발

```bash
cp .env.local.example .env.local
npm install
npm run dev
```

빌드 확인:

```bash
npm run build && ls out
```

---

## next 버전 하한 (16.3.3)

`next` 버전을 `^16.3.3`으로 올려둔 이유가 있다. **내려잡지 말 것.**

16.1.6에서는 npm workspaces 호이스팅으로 `typescript`가 루트 `node_modules`에 설치되면
Next가 이를 찾지 못하고 "TypeScript가 없다"고 판단해 **빌드 도중 pnpm으로 재설치를 시도**한다.
실행 중인 빌드 워커 밑에서 `node_modules`가 갈아치워지면서
`The "id" argument must be of type string. Received undefined`로 죽는다.

증상이 고약한 이유는 **두 번째 실행부터는 성공**하기 때문이다.
로컬에서는 멀쩡해 보이고 깨끗한 CI 환경에서만 실패한다.

16.3.3에서는 호이스팅된 `typescript`를 정상적으로 찾아 재설치가 일어나지 않는다.
