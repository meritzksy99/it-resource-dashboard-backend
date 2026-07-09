#!/usr/bin/env bash
# ============================================================================
# [빌드 머신에서 실행] — 인터넷 O + 사내 CA 신뢰(JDK cacerts)
# 실행가능 jar 빌드 → (배포 서버 아키텍처용) Docker 이미지 → 폐쇄망 반입용 tar.gz
#
# 사용법:  ./deploy/build-image.sh [버전]
#          PLATFORM=linux/amd64 ./deploy/build-image.sh 0.0.1   # 서버가 x86_64면 amd64(기본)
#          PLATFORM=linux/arm64 ./deploy/build-image.sh 0.0.1   # 서버가 ARM이면 arm64
#
# ⚠️ 서버 아키텍처(`uname -m`)와 반드시 일치시킬 것.
#    x86_64 → linux/amd64 (기본) · aarch64 → linux/arm64
#    안 맞으면 서버에서 "platform does not match" 경고 후 컨테이너가 즉시 죽는다.
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."          # 프로젝트 루트

VERSION="${1:-0.0.1}"
PLATFORM="${PLATFORM:-linux/amd64}"
IMAGE="it-dash:${VERSION}"
OUT="deploy/it-dash-${VERSION}.tar.gz"

echo "▶ 1/3  실행가능 jar 빌드 (테스트 제외)"
./gradlew --no-daemon clean bootJar -x test

echo "▶ 2/3  ${PLATFORM} 이미지 빌드: ${IMAGE}"
if ! docker buildx version >/dev/null 2>&1; then
  echo "✗ docker buildx 필요(크로스 아키텍처 빌드용). 설치:"
  echo "    brew install docker-buildx"
  echo "    mkdir -p ~/.docker/cli-plugins"
  echo "    ln -sf \"\$(brew --prefix)/lib/docker/cli-plugins/docker-buildx\" ~/.docker/cli-plugins/docker-buildx"
  exit 1
fi
# 데몬(default) 드라이버 사용: 사내 CA 를 신뢰하는 도커 데몬이 베이스 이미지를 받는다.
# --provenance=false: attestation 매니페스트 없이 단일 이미지로 만들어 docker save/load 호환.
docker buildx build --builder default --platform "${PLATFORM}" --load --provenance=false \
  -f Dockerfile.amd64 -t "${IMAGE}" .

echo "▶ 3/3  이미지 tar.gz 추출: ${OUT}"
docker save "${IMAGE}" | gzip > "${OUT}"

echo ""
echo "✔ 완료 → ${OUT}  ($(du -h "${OUT}" | cut -f1))  [platform=${PLATFORM}]"
echo "  이 파일과 deploy/ 안의 스크립트·env 템플릿을 서버로 옮기세요."
