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

쓰기 경로에는 공유 비밀 `X-Internal-Key`가 걸려 있다. 값은 두 서비스가 같은
`INTERNAL_API_KEY` 환경변수에서 읽는다.

- `apps/backend`의 쓰기 엔드포인트(`POST /api/streams`, `POST /api/trails`,
  `POST /api/captures/jobs`)가 헤더를 검사한다
- `services/writer`의 `/internal/**` 전체를 `InternalKeyFilter`가 다시 검사한다

**두 겹인 이유:** writer의 포트가 compose에서 호스트로 열려 있어
(`${WRITER_PORT:-8002}:8080`) 게이트웨이를 건너뛰고 직접 쓸 수 있다. 게이트웨이에만
검사를 두면 우회로가 열린 채로 남는다. 필터는 컨트롤러 바깥에 있으므로 앞으로
추가되는 `/internal/**` 엔드포인트도 자동으로 덮인다.

reader에는 걸지 않는다. 조회는 공개 API이고 게이트웨이도 GET에는 키를 요구하지 않는다.

**한계:** 단일 공유 비밀이고 기본값이 `dev-internal-key-change-me`다. 운영에서는
`.env`의 `INTERNAL_API_KEY`를 반드시 바꿔야 하며, 그 자체가 인증·인가 체계를
대신하지는 못한다. 서비스 계정이나 mTLS로 가는 것은 별개 작업이다.
