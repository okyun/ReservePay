#!/usr/bin/env bash
# 대규모 부하 테스트: 피크 1,000 RPS, 10만+ 요청
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT"

if ! command -v k6 >/dev/null 2>&1; then
  echo "k6가 설치되어 있지 않습니다. macOS: brew install k6" >&2
  exit 1
fi

echo "==> Checkout 상태 초기화"
bash "$SCRIPT_DIR/reset-checkout-state.sh"

echo "==> k6 대규모 부하 테스트 시작 (checkout-load-100k.js)"
k6 run "$SCRIPT_DIR/checkout-load-100k.js" "$@"

echo ""
bash "$SCRIPT_DIR/verify-load-test.sh"
