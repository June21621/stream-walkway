# Frontend

Stream Walkway 웹 프론트엔드. Next.js 16 App Router + React 19.

하천과 카메라 관측 지점을 지도에서 고르고, 지점별 캡처 이미지와 분석 결과를 본다.

| 경로 | 화면 |
|---|---|
| `/` | 지도(하천 선택) + 하천 목록 |
| `/streams/[id]` | 하천 상세 + 관측 지점 목록 |
| `/trails/[id]` | 관측 지점 상세 + 캡처 이미지 |

정적 사이트(static export)로 빌드해 CDN에 올린다. Node 런타임 서버를 두지 않고,
데이터는 빌드 타임에 백엔드 게이트웨이에서 가져와 HTML로 굽는다.

## 로컬 실행

```bash
cp .env.local.example .env.local   # API 주소, 이미지 base URL, 네이버 지도 키
npm install
npm run dev
```

빌드하려면 백엔드 스택이 떠 있어야 한다(`bash infra/scripts/dev-up.sh`).
빌드 타임에 API를 호출하기 때문이다.

```bash
npm run build     # -> out/
npm run preview   # out/ 을 로컬에서 서빙
npm test
```

---

> **코드를 만지기 전에 [`CLAUDE.md`](./CLAUDE.md)를 읽을 것.**
> 정적 export 제약(쓰면 안 되는 Next 기능), 데이터 페칭 규약, 네이버 지도 키 설정,
> 그리고 실제로 당했던 함정들(`next` 버전 하한, WKT 좌표 순서, `image_path` 조립)이
> 거기 정리돼 있다. 사람이 손댈 때도 똑같이 필요한 내용이다.
