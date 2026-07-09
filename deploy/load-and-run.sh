#!/usr/bin/env bash
# ============================================================================
# [배포 서버(폐쇄망)에서 실행] — Docker Engine 만 있으면 됨
# tar.gz 이미지 적재 → 컨테이너 실행(재시작 정책 포함)
# 사전:  같은 폴더에 it-dash-<버전>.tar.gz, it-dash.env 가 있어야 함
# 사용법:  PORT=8080 ./load-and-run.sh [버전]
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"

VERSION="${1:-0.0.1}"
IMAGE="it-dash:${VERSION}"
TAR="it-dash-${VERSION}.tar.gz"
ENVFILE="it-dash.env"
PORT="${PORT:-8080}"                 # 바깥(호스트) 포트. 원하는 값으로: PORT=9090 ./load-and-run.sh

[ -f "$TAR" ]     || { echo "✗ '$TAR' 없음 (빌드머신에서 만든 이미지 tar 필요)"; exit 1; }
[ -f "$ENVFILE" ] || { echo "✗ '$ENVFILE' 없음 — it-dash.env.example 복사해 접속정보 입력하세요"; exit 1; }

echo "▶ 1/3  이미지 적재"
gunzip -c "$TAR" | docker load

echo "▶ 2/3  기존 컨테이너 정리(있으면)"
docker rm -f it-dash 2>/dev/null || true

echo "▶ 3/3  컨테이너 실행 (호스트:${PORT} → 컨테이너:8080)"
docker run -d --name it-dash \
  --restart unless-stopped \
  -p "${PORT}:8080" \
  --env-file "$ENVFILE" \
  "$IMAGE"

echo ""
echo "✔ 실행됨. 확인:"
echo "    curl http://localhost:${PORT}/api/v1/health"
echo "    브라우저: http://<서버IP>:${PORT}/swagger-ui/index.html"
