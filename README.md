# Stream Walkway

MSA 기반 하천 산책로 정보 분석 시스템

## 프로젝트 개요

이 프로젝트는 마이크로서비스 아키텍처(MSA)를 기반으로 하천 산책로 영상을 분석하여 하천 산책로 정보를 추출하고 저장하는 시스템입니다.

### 주요 기능

- 영상 스트림에서 하천 산책로 이미지 자동 캡처 (ffmpeg 기반, YouTube 직접 다운로드는 이용약관 문제로 지원하지 않음)
- ML 모델을 활용한 하천 산책로 정보 자동 추출
- CQRS 패턴 기반 데이터 읽기/쓰기 분리
- Apache Kafka (KRaft)를 통한 이벤트 기반 비동기 처리
- PostgreSQL + Redis 하이브리드 데이터 저장

## 프로젝트 구조

```
stream-walkway/
├── apps/                      # 주요 애플리케이션
│   ├── frontend/             # Next.js 정적 사이트 (out/ 을 CDN에 배포, 런타임 서버 없음)
│   ├── backend/              # Spring Boot API Gateway 및 오케스트레이션
│   ├── youtube-service/      # ffmpeg 기반 프레임 캡처 서비스 (MinIO 업로드)
│   └── ml-service/           # Python/Node.js ML 분석 서비스
├── services/                  # 마이크로서비스
│   ├── writer/               # 쓰기 서비스 (PostgreSQL + Redis 업데이트)
│   └── reader/               # 읽기 서비스 (최적화된 조회)
├── packages/                  # 공유 라이브러리
│   └── shared/               # 공통 타입 정의 및 유틸리티
├── pom.xml                    # 루트 Maven 애그리게이터 (shared → reader/writer 순서로 빌드)
├── infra/                     # 인프라 구성
│   ├── docker/               # Docker Compose 설정
│   ├── kubernetes/           # K8s 매니페스트
│   └── scripts/              # 배포 및 초기화 스크립트
└── docs/                      # 문서
    ├── architecture/         # 아키텍처 다이어그램
    ├── api-specs/            # API 명세서
    └── diagrams/             # 시스템 흐름도
```

## 기술 스택

### Frontend
- Next.js (App Router, 정적 export)
- React
- TypeScript

프론트엔드는 `next build`로 `out/` 을 생성해 CDN에 올리는 정적 사이트입니다. Node 런타임 서버가 없으므로 `docker-compose`에도 포함되지 않습니다. 자세한 배포 모델과 라우팅 규약은 [apps/frontend/README.md](./apps/frontend/README.md)를 참고하세요.

### Backend
- Spring Boot (API Gateway, Writer, Reader)
- Node.js (YouTube Service)
- Python/Node.js (ML Service)

### Infrastructure
- PostgreSQL (장기 데이터 저장)
- Redis (빠른 읽기 캐싱)
- Apache Kafka (KRaft, apache/kafka:3.9.0) (이벤트 메시징)
- MinIO (S3 호환 오브젝트 스토리지)
- Docker & Kubernetes

저장소 루트의 `pom.xml`은 `packages/shared`, `services/reader`, `services/writer`를 모듈로 묶은 Maven 애그리게이터입니다. 루트에서 `mvn install`을 한 번 실행하면 Maven 리액터가 의존 관계를 감지해 `shared`를 먼저 빌드/설치한 뒤 이를 참조하는 `reader`/`writer`를 빌드합니다.

## 아키텍처

### MSA 구조

1. **통신 서비스** (Backend): 웹 요청 처리 및 서비스 오케스트레이션
2. **분석 서비스** (ML Service): 이미지 분석 및 하천 산책로 정보 추출, 이벤트 발행
3. **쓰기 서비스** (Writer): PostgreSQL 저장 및 Redis 업데이트
4. **읽기 서비스** (Reader): 최적화된 데이터 조회

### 데이터 흐름

```
Frontend → Backend (Gateway) → YouTube Service → ffmpeg 프레임 캡처 → MinIO
        POST /api/captures/jobs      POST /download
                              ↓
                         Kafka (image.downloaded)
                              ↓
                    ML Service (병렬 처리)
                              ↓
                         Kafka (분석 결과)
                              ↓
                    Writer Service → PostgreSQL + Redis
                              ↓
                    Reader Service ← 사용자 조회 요청
```

파이프라인의 입구는 게이트웨이 하나뿐입니다. Backend가 `POST /api/captures/jobs`를
받아 YouTube Service의 `POST /download`를 동기 호출해 작업을 시작시키고, 202와 함께
`jobId`를 돌려받는 것으로 관여가 끝납니다. 그 뒤 단계(분석 → 저장)는 Kafka로 흐릅니다.
진행 상태는 `GET /api/captures/jobs/{jobId}`로 확인합니다.

한 번 호출하면 프레임 한 장을 뜹니다. 주기적으로 호출하는 스케줄러는 아직 없습니다 —
현재 데이터 출처가 라이브 스트림이 아니라 녹화된 영상이라 벽시계 주기가 의미를 갖지
않기 때문입니다(`docs/superpowers/specs/2026-08-27-youtube-capture-design.md`의
"후속 작업" 참고).

## 시작하기

### 사전 요구사항

- Node.js 18+
- Java 17+
- Python 3.10+
- Docker & Docker Compose

### 로컬 개발 환경 설정

```bash
# 저장소 클론
git clone https://github.com/June21621/stream-walkway.git
cd stream-walkway

# 인프라 서비스 시작 (PostgreSQL, Redis, Kafka, MinIO)
cd infra/docker
docker-compose up -d

# 각 서비스 실행 (추후 업데이트 예정)
```

> ⚠️ 2026-08-22 이전에 만든 Postgres 볼륨이 있다면 `docker compose down -v` 로 지운 뒤 다시 올려주세요.
> `created_at`/`updated_at` 컬럼이 `TIMESTAMPTZ`로 바뀌어서, 기존 볼륨을 그대로 쓰면 스키마가 갱신되지 않습니다.

## 개발 가이드

자세한 개발 가이드는 [docs/](./docs/) 폴더를 참고하세요.

## 라이센스

This project is private and proprietary.
