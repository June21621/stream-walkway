# stream-walkway — 작업 규약

프로젝트가 뭔지는 `README.md`를 보면 된다. 이 파일은 **코드를 만질 때 지켜야 할 것**만 적는다.

디렉터리별 규약은 그 디렉터리의 `CLAUDE.md`에 있다. 해당 모듈을 만지기 전에 읽을 것.

| 위치 | 내용 |
|---|---|
| `apps/frontend/CLAUDE.md` | 정적 export 제약, 데이터 페칭, 지도, 알려진 함정 |
| `apps/backend/CLAUDE.md` | 게이트웨이 계약, 인증, 에러 변환 |
| `services/writer/CLAUDE.md` | 검증 순서, DB 제약명, 지오메트리 정책 |

---

## 명령

Java 모듈은 각자 `mvnw`를 가진다. 루트 `pom.xml`은 애그리게이터다
(`packages/shared` → `services/reader` → `services/writer` → `apps/backend` 순서로 빌드).

```bash
# 모듈 하나 테스트 — clean 을 반드시 붙인다
cd services/writer && ./mvnw clean test
```

**`clean` 없이 `test`만 돌리지 말 것.** 삭제된 소스의 `.class`가 `target/`에 남아
테스트 개수가 부풀려 보고된다. 실제로 33개로 잘못 보고했다가 23개였던 적이 있다.

```bash
# 전체 스택 (컨테이너 9개)
bash infra/scripts/dev-up.sh
bash infra/scripts/dev-down.sh     # 볼륨은 남는다

# 프론트엔드
cd apps/frontend && npm run build   # 백엔드 스택이 떠 있어야 한다 (아래 참고)
cd apps/frontend && npm test

# 그 외
cd apps/youtube-service && npm test
```

**프론트엔드 빌드는 백엔드에 의존한다.** 빌드 타임에 게이트웨이 API를 호출해
정적 HTML을 굽기 때문에, 스택이 안 떠 있으면 빌드가 실패한다. 의도된 동작이다.

---

## 구조

```
apps/frontend        Next.js 정적 사이트 (Node 런타임 없음, CDN 배포)
apps/backend         Spring Boot API 게이트웨이 — DB에 직접 접근하지 않는다
apps/youtube-service Node.js, 영상에서 이미지 캡처 → MinIO
apps/ml-service      Python, 이미지 분석 (실제 모델 없음, 고정값 반환)
services/writer      쓰기 — PostgreSQL/Redis 갱신
services/reader      읽기 — 조회 최적화
packages/shared      공유 엔티티/DTO (Java 전용, 프론트에서 재사용 불가)
```

CQRS다. **`apps/backend`는 DB를 직접 만지지 않고 reader/writer를 HTTP로 호출한다.**
이 경계를 넘지 말 것.

---

## 작업 방식

- **파트별로 브랜치를 나눈다.** 모듈/계층/도메인 경계로 쪼개고 짧은 이름을 쓴다
  (`feature/trail-cqrs`, `feature/frontend-screens`). 특히 DB 접근 계층과
  게이트웨이 계층은 따로 간다
- **머지해도 브랜치를 지우지 않는다**
- **`git push`는 명시적으로 요청받았을 때만 한다.** 로컬 커밋까지는 자유
- **커밋 메시지는 한글로, 무엇을/왜 했는지 자세히**

## 실기동 검증을 미루지 말 것

유닛 테스트 176개가 전부 GREEN인 상태에서 Docker 빌드 자체가 안 되던 적이 있다
(CRLF shebang). 테스트 스키마에 FK가 없어서 FK 버그는 구조적으로 안 잡혔다.

GIS, DB 제약, 직렬화 포맷, 컨테이너 빌드처럼 **실제 환경에서만 드러나는** 변경을
했으면 같은 흐름에서 Docker로 확인할 것. 정상 경로만이 아니라 실패 경로
(중복, 없는 FK, 필수값 누락, 잘못된 키)까지 봐야 의미가 있다.

## 외부에서 받은 리뷰 문서는 대조 후 적용

다른 세션/사람/도구가 만든 이슈 목록은 그대로 믿지 말 것. 계획 문서에 박제된
초안 코드를 보고 작성되는 경우가 많아서, 이미 고친 걸 다시 지적한다.
항목마다 현재 코드를 확인하고 살아있는 것만 골라낼 것.

## 상태 추적 문서는 최신으로

`docs/tdd-test-plan.md` 처럼 구현 상태(RED/GREEN)를 표로 담은 문서는
작업이 끝나면 갱신한다. 오래됐다고 "역사적 스냅샷"으로 넘기지 말 것.
