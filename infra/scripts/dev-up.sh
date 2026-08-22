#!/usr/bin/env bash
set -e

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# 환경 변수는 저장소 루트의 .env 하나만 쓴다.
# (docker-compose.yml 안에서도 postgres 서비스가 `env_file: ../../.env`로
#  같은 파일을 참조하므로, --env-file도 반드시 같은 파일이어야 값이 어긋나지 않는다.)
ENV_FILE="$ROOT_DIR/.env"

if [ ! -f "$ENV_FILE" ]; then
  echo "❌ .env 파일이 없습니다: $ENV_FILE"
  echo "   .env.example 을 복사한 뒤 값을 채워주세요:"
  echo "     cp .env.example .env"
  exit 1
fi

cd "$ROOT_DIR"

docker compose \
  --env-file "$ENV_FILE" \
  -f infra/docker/docker-compose.yml \
  up -d --build

echo "✅ stream-walkway dev 환경이 올라갔습니다."
