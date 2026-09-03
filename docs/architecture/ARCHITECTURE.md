# Stream Walkway Architecture

이 문서는 `stream-walkway` 프로젝트의 전체 아키텍처와 디렉터리 구조, 각 컴포넌트의 역할을 설명합니다.

Stream Walkway는 스트리밍 데이터(예: YouTube 영상 등)를 수집하고, 분석한 뒤, 읽기/쓰기 분리(CQRS)와 마이크로서비스 아키텍처(MSA)를 통해 효율적으로 제공하는 시스템을 목표로 합니다.
하나의 모노레포 안에 프론트엔드, 백엔드 게이트웨이, 도메인 마이크로서비스, ML/워크로드 서비스, 인프라 설정을 함께 관리합니다.[cite:1][cite:2][cite:4]

## 디렉터리 구조 개요

```text
stream-walkway/
├── apps/                      # 주요 애플리케이션 (엔드투엔드 단위)
│   ├── frontend/              # Next.js 정적 사이트 (CDN 배포, 런타임 서버 없음)
│   ├── backend/               # Spring Boot API Gateway 및 오케스트레이션
│   ├── youtube-service/       # ffmpeg 기반 프레임 캡처 서비스 (MinIO 업로드)
│   └── ml-service/            # Python/Node.js 기반 ML 분석 서비스
├── services/                  # 도메인 마이크로서비스 (CQRS 등)
│   ├── writer/                # 쓰기 서비스 (명령 처리, PostgreSQL + Redis 업데이트)
│   └── reader/                # 읽기 서비스 (조회 최적화, 리드 모델)
├── packages/                  # 공유 라이브러리/모듈
│   └── shared/                # 공통 타입 정의, 유틸리티, SDK 등
├── infra/                     # 인프라 및 배포 구성
│   ├── docker/                # Docker Compose 설정 및 개별 Dockerfile
│   ├── kubernetes/            # Kubernetes 매니페스트(K8s 리소스 정의)
│   └── scripts/               # 배포 및 초기화 스크립트
└── docs/                      # 문서 (아키텍처, 다이어그램, API 스펙 등)
    ├── architecture/          # 아키텍처 상세 설명
    ├── api-specs/             # API 명세서 (OpenAPI/ADR 등)
    └── diagrams/              # 시스템/시퀀스 다이어그램

## 기술 스택

### 메시지 브로커
- Apache Kafka (KRaft, apache/kafka:3.9.0) — Zookeeper 없이 KRaft 모드로 운영

### 데이터 저장소
- PostgreSQL + PostGIS
- Redis
- MinIO (S3 호환 오브젝트 스토리지, 캡처 이미지 저장)

### 서비스
- Spring Boot (Writer, Reader, Backend Gateway)
- Node.js (YouTube Service)
- Python/FastAPI (ML Service)
```

## 내부 API 인증

**HTTP 쓰기 경로**에는 공유 비밀 `X-Internal-Key`가 걸려 있다. 값은 두 서비스가 같은
`INTERNAL_API_KEY` 환경변수에서 읽는다.

- `apps/backend`의 쓰기 엔드포인트(`POST /api/streams`, `POST /api/trails`,
  `POST /api/captures/jobs`)가 헤더를 검사한다
- `services/writer`는 `InternalKeyFilter`가 **`/health`를 제외한 모든 요청**에서 검사한다

**writer에서도 검사하는 이유:** writer의 포트가 compose에서 호스트로 열려 있어
(`${WRITER_PORT:-8002}:8080`) 게이트웨이를 건너뛰고 직접 쓸 수 있다. 게이트웨이에만
검사를 두면 익명 우회로가 열린 채로 남는다.

**이것은 심층 방어가 아니다.** 양쪽이 같은 비밀을 쓰므로, 게이트웨이 쓰기 API를
쓸 자격이 있는 호출자는 이미 writer를 직접 열 자격도 가진 셈이다. 이 검사가 막는
것은 **인증되지 않은** 직접 접근이지 침해된 클라이언트가 아니다. 진짜로 층을
나누려면 게이트웨이↔writer 전용 자격증명을 따로 두거나, 애초에 8002 포트를
호스트에 게시하지 않아야 한다(후자가 더 싸다 — 디버깅 편의를 위해 열어두고 있다).

**보호 대상을 경로 접두사로 고르지 않는다.** `/internal/`로 시작하면 막는 방식은
실제로 우회됐다. `getRequestURI()`가 디코딩되지 않은 원본을 주는데 Spring MVC는
디코딩된 경로로 라우팅해서, `POST /%69nternal/streams`가 필터를 지나쳐 핸들러까지
닿았다(2026-09-03 실측, 회귀 테스트로 고정). 그래서 공개 경로만 나열하는 허용
목록으로 뒤집었다 — 거부 목록은 정규화하지 않은 경로 앞에서 열린 채로 실패한다.

reader에는 걸지 않는다. 조회는 공개 API이고 게이트웨이도 GET에는 키를 요구하지 않는다.

**한계 — Kafka 경로는 인증이 없다.** writer의 `ImageAnalyzedConsumer`는
`image.analyzed` 토픽을 읽어 캡처 행을 직접 만든다. compose가 Kafka를 `9092:9092`로
`PLAINTEXT` 인증 없이 게시하므로, 호스트에 접근할 수 있으면 키 없이 메시지를 넣어
행을 쓸 수 있다. 이번 작업은 HTTP 경로만 다뤘고 이것은 남은 구멍이다.

**한계 — 단일 공유 비밀.** compose는 `INTERNAL_API_KEY`를 필수로 요구하므로
(`${INTERNAL_API_KEY:?...}`) 값을 안 주면 `docker compose up`이 실패한다. 조용히 약한
기본값으로 뜨지 않게 하려는 것이다. 다만 공유 비밀 하나가 인증·인가 체계를
대신하지는 못한다. 서비스 계정이나 mTLS로 가는 것은 별개 작업이다.
