# Stream Walkway Architecture

이 문서는 `stream-walkway` 프로젝트의 전체 아키텍처와 디렉터리 구조, 각 컴포넌트의 역할을 설명합니다.

Stream Walkway는 스트리밍 데이터(예: YouTube 영상 등)를 수집하고, 분석한 뒤, 읽기/쓰기 분리(CQRS)와 마이크로서비스 아키텍처(MSA)를 통해 효율적으로 제공하는 시스템을 목표로 합니다.
하나의 모노레포 안에 프론트엔드, 백엔드 게이트웨이, 도메인 마이크로서비스, ML/워크로드 서비스, 인프라 설정을 함께 관리합니다.[cite:1][cite:2][cite:4]

## 디렉터리 구조 개요

```text
stream-walkway/
├── apps/                      # 주요 애플리케이션 (엔드투엔드 단위)
│   ├── frontend/              # React/Next.js 기반 웹 프론트엔드
│   ├── backend/               # Spring Boot API Gateway 및 오케스트레이션
│   ├── youtube-service/       # Node.js 기반 YouTube/영상 캡처 서비스
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

### 서비스
- Spring Boot (Writer, Reader, Backend Gateway)
- Node.js (YouTube Service)
- Python/FastAPI (ML Service)
```
