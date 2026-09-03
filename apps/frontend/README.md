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

`next start`(Node 서버)는 이 배포 모델에서 **동작하지 않는다.** `output: 'export'`에서 실행하면
Next가 `"next start" does not work with "output: export" configuration` 오류를 낸다.
그래서 `start` 스크립트를 두지 않고, 빌드 결과를 로컬에서 확인할 때 쓰는 `preview`를 대신 둔다.

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
| `NEXT_PUBLIC_IMAGE_BASE_URL` | 캡처 이미지 base URL. **버킷 이름까지 포함시킨다** |
| `NEXT_PUBLIC_NAVER_MAP_CLIENT_ID` | 네이버 지도 Client ID. 비워두면 지도 자리에 안내 문구가 뜬다 |

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

### image_path 는 URL이 아니다

API가 주는 `image_path` 는 `captures/1/7/2026-09-03T01-45-00Z.jpg` 형태의 **객체 키**다.
`<endpoint>/<bucket>/<key>` 로 조립해야 브라우저에서 뜬다 (`lib/api.ts` 의 `imageUrl()`).

**버킷 이름이 키에 한 번 더 들어간다.** 버킷이 `captures` 인데
`youtube-service` 의 `buildKey()` 가 만드는 키도 `captures/` 로 시작한다.
그래서 실제 주소는 이렇게 된다.

```
http://localhost:9000/captures/captures/1/7/2026-09-03T01-45-00Z.jpg
```

`NEXT_PUBLIC_IMAGE_BASE_URL` 에 버킷까지 포함시키면(`http://localhost:9000/captures`)
그 중복이 그대로 맞아떨어지므로 프론트에 우회 로직이 필요 없다.
백엔드가 나중에 키에서 접두사를 제거하면 이 환경변수만 고치면 된다.

MinIO endpoint는 **컨테이너 내부 주소와 브라우저 주소가 다르다.**
compose는 `http://minio:9000` 이지만 브라우저는 그 호스트를 모르므로
퍼블리시 포트(`http://localhost:9000`)를 써야 한다.

---

## 로컬 개발

```bash
cp .env.local.example .env.local
npm install
npm run dev
```

데이터가 필요하므로 **백엔드 스택이 떠 있어야 한다** (`bash infra/scripts/dev-up.sh`).
빌드 타임에 API를 호출하기 때문에, API가 죽어 있으면 빌드가 실패한다(의도된 동작).

```bash
npm run build && ls out   # 빌드 + 산출물 확인
npm test                  # lib/api.ts 자체 점검 (node --test)
```

정적 산출물을 그대로 띄워보려면:

```bash
python -m http.server 4000 --directory out
```

---

## 화면

| 경로 | 내용 |
|---|---|
| `/` | 지도(하천 선택) + 하천 목록 |
| `/streams/[id]` | 하천 상세 + 지도(관측 지점 마커) + 관측 지점 목록 |
| `/trails/[id]` | 관측 지점 상세 + 캡처 이미지 |

뒤의 둘은 `generateStaticParams` 로 빌드 타임에 전체 경로를 생성한다.

알려진 제약:

- **쓰기 API는 붙일 수 없다.** `POST /api/streams`, `/api/trails`, `/api/captures/jobs` 는
  `X-Internal-Key` 를 요구하는데, 정적 사이트에 공유 비밀을 박을 수 없다.
  관리 기능이 필요하면 별도 경로를 설계해야 한다
- **`road_status` 는 항상 `"양호"`, `confidence` 는 항상 `0.95`** — ml-service에 실제 모델이 없다
- **Trail 에 `name` 이 없다.** writer는 `name` 을 검증하는데 `TrailView`/게이트웨이 응답에는
  없어서 화면에는 `camera_number` 를 쓴다. 백엔드 쪽 갭

빌드 결과를 실제 정적 호스트처럼 띄워 보려면:

```bash
npm run preview
```

`npx serve@latest out`을 실행한다. `serve`를 의존성에 넣지 않은 것은 배포에 필요 없는
로컬 확인용 도구이기 때문이다.

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

---

## 지도 (네이버)

지도를 하천 선택의 입구로 쓴다. `/` 상단에 지도, 아래에 하천 목록을 같이 둔다.

**목록을 함께 두는 건 중복이 아니라 폴백이다.** 지도는 외부 스크립트·API 키·도메인
화이트리스트에 의존해서 깨질 수 있는데, 목록은 서버 렌더라 정적 HTML에 남는다.
지도가 안 떠도 하천 선택은 되고, 크롤러도 하천 이름을 읽는다.

### 키 발급

NCP 콘솔 → Maps → Application 등록 → **Web Dynamic Map** 활성화 → Client ID.

**서비스 URL(도메인)을 반드시 등록할 것.** 등록 안 된 도메인에서는 인증 실패로
지도가 뜨지 않는다. 최소 `http://localhost:3000`(dev), `http://localhost:4000`(preview),
그리고 배포 도메인.

키는 `NEXT_PUBLIC_` 이라 빌드 시 번들에 박히고 브라우저에서 보인다.
이게 웹 지도 API의 정상 사용법이며, **도메인 화이트리스트가 유일한 보호막**이다.

> 지도가 인증 오류로 안 뜨면 `components/NaverMap.tsx` 의 `KEY_PARAM` 을 확인할 것.
> 스크립트 쿼리 파라미터가 예전 `ncpClientId` 에서 NCP 이관 후 `ncpKeyId` 로 바뀌었다.
> 콘솔이 주는 예제 스니펫이 항상 최신이다.

### 좌표 순서

**WKT는 `(경도 위도)`, 네이버는 `LatLng(위도, 경도)` 로 순서가 뒤집힌다.**
여기가 실수 나는 지점이라 `lib/wkt.ts` 에 테스트를 붙여뒀다.

`parseWkt()` 는 `POINT EMPTY` / `LINESTRING EMPTY` 를 예외 대신 빈 배열로 준다.
빌드 타임에 그리는 화면이라 예외를 던지면 빌드 전체가 깨진다 —
한 행을 못 그리는 것과 배포가 막히는 건 다르다.
