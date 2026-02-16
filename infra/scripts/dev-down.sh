#!/usr/bin/env bash
set -e

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

docker compose \
  --env-file infra/.env \
  -f infra/docker/docker-compose.yml \
  down

echo "✅ stream-walkway dev 환경이 내려갔습니다."
