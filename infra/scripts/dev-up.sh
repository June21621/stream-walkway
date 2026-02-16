#!/usr/bin/env bash
set -e

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [ ! -f "$ROOT_DIR/infra/.env" ]; then
  echo "⚠️  infra/.env 파일이 없습니다. infra/.env.example 을 복사합니다."
  cp "$ROOT_DIR/infra/.env.example" "$ROOT_DIR/infra/.env"
fi

cd "$ROOT_DIR"

docker compose \
  --env-file infra/.env \
  -f infra/docker/docker-compose.yml \
  up -d --build

echo "✅ stream-walkway dev 환경이 올라갔습니다."
