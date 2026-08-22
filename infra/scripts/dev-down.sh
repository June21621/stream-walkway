#!/usr/bin/env bash
set -e

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# dev-up.sh와 같은 .env를 써야 compose가 같은 프로젝트/리소스를 인식한다.
ENV_FILE="$ROOT_DIR/.env"

if [ ! -f "$ENV_FILE" ]; then
  echo "❌ .env 파일이 없습니다: $ENV_FILE"
  exit 1
fi

cd "$ROOT_DIR"

docker compose \
  --env-file "$ENV_FILE" \
  -f infra/docker/docker-compose.yml \
  down

echo "✅ stream-walkway dev 환경이 내려갔습니다."
echo "ℹ️  DB 스키마가 바뀐 뒤라면 볼륨까지 지워야 init-db.sql이 다시 실행됩니다:"
echo "     docker compose --env-file .env -f infra/docker/docker-compose.yml down -v"
