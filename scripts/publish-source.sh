#!/usr/bin/env bash
#
# publish-source.sh — 로컬 소스 트리에서 민감/대용량 파일을 제외한 "깨끗한 스냅샷"을
# GitHub origin/main 으로 올린다. 로컬 히스토리(민감파일 포함)는 절대 push 되지 않는다.
#
# 사용법:  bash scripts/publish-source.sh [SRC_BRANCH]   (기본 SRC=main)
# 동작:    SRC 트리 → 아래 DENY/패턴 제거 → snapshot-public 브랜치에 자식 커밋 → push origin main
#
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

SRC="${1:-main}"
PUB_BRANCH="snapshot-public"

# 사외 반출 금지 — 소스가 아닌 민감/내부 자료(경로 단위). 테스트 픽스처(src/test/**)는 유지된다.
DENY=(
  "ad계정설정.txt"
  "deploy.zip"
  "deploy/tar_백업"
  "prd"
  "쿼리"
  "보안성검토.xlsx"
  "보안성검토_원본백업_20260706_103359.xlsx"
  "SR유형코드정보_TBCPPE097L00.xlsx"
)

# 임시 인덱스에 SRC 트리를 읽어, 민감 항목을 인덱스에서만 제거한 뒤 깨끗한 트리를 만든다.
TMPIDX="$(mktemp)"
export GIT_INDEX_FILE="$TMPIDX"
git read-tree "$SRC"

for p in "${DENY[@]}"; do
  git rm -r --cached --ignore-unmatch -q -- "$p" || true
done
# 대용량 아카이브(*.tar / *.tar.gz)는 경로와 무관하게 모두 제외
git ls-files -z | while IFS= read -r -d '' f; do
  case "$f" in
    *.tar|*.tar.gz) git rm --cached -q -- "$f" || true ;;
  esac
done

TREE="$(git write-tree)"
unset GIT_INDEX_FILE
rm -f "$TMPIDX"

# snapshot-public 이 있으면 그 위에 자식 커밋(히스토리 유지, force-push 불필요), 없으면 첫 커밋.
if PARENT="$(git rev-parse -q --verify "refs/heads/$PUB_BRANCH")"; then
  COMMIT="$(git commit-tree "$TREE" -p "$PARENT" -m "chore: 소스 스냅샷 동기화 (민감자료 제외)")"
else
  COMMIT="$(git commit-tree "$TREE" -m "chore: 소스 스냅샷 (민감자료 제외)")"
fi
git update-ref "refs/heads/$PUB_BRANCH" "$COMMIT"

echo "==> origin/main 으로 push"
git push origin "$PUB_BRANCH:main"

# 검증: 방금 올린 커밋에 민감파일이 없어야 정상
echo "==> 검증(민감파일 없어야 함):"
git ls-tree -r --name-only "$COMMIT" | grep -aE "ad계정설정|deploy\.zip|deploy/tar_|^prd/|^쿼리/|보안성검토|SR유형|\.tar(\.gz)?$" \
  | grep -v "src/test/" && { echo "!! 민감파일 발견 — push 재검토"; exit 1; } || echo "OK: 민감파일 없음"
