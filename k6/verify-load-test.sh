#!/usr/bin/env bash
# k6 부하 테스트 후 재고 정합성·공정성 검증
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT"

PRODUCT_ID="${PRODUCT_ID:-1}"
TOTAL_STOCK="${TOTAL_STOCK:-10}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_USER="${MYSQL_USER:-reservepay}"
MYSQL_PASS="${MYSQL_PASS:-reservepay}"
MYSQL_DB="${MYSQL_DB:-reservepay}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"

redis_cmd() {
  if command -v redis-cli >/dev/null 2>&1 && redis-cli -h "$REDIS_HOST" ping >/dev/null 2>&1; then
    redis-cli -h "$REDIS_HOST" "$@"
  else
    docker compose exec -T redis redis-cli "$@"
  fi
}

mysql_cmd() {
  local args=(-h"$MYSQL_HOST" -u"$MYSQL_USER" -p"$MYSQL_PASS" "$MYSQL_DB" -N -B)
  if mysql "${args[@]}" -e "SELECT 1" >/dev/null 2>&1; then
    mysql "${args[@]}" "$@"
  else
    docker compose exec -T mysql mysql -u"$MYSQL_USER" -p"$MYSQL_PASS" "$MYSQL_DB" -N -B "$@"
  fi
}

echo "==> 사후 검증 (productId=${PRODUCT_ID}, 기대 재고 소진=${TOTAL_STOCK})"

ORDER_COUNT=$(mysql_cmd -e "SELECT COUNT(*) FROM orders WHERE product_id=${PRODUCT_ID} AND status='PENDING';")
DB_STOCK=$(mysql_cmd -e "SELECT remaining_stock FROM stock WHERE product_id=${PRODUCT_ID};")
REDIS_STOCK=$(redis_cmd GET "stock:${PRODUCT_ID}")
DLT_COUNT=$(mysql_cmd -e "SELECT COUNT(*) FROM booking_dead_letter;")
WINNER_SPREAD=$(mysql_cmd -e "
SELECT CONCAT(MIN(member_id), ',', MAX(member_id), ',', COUNT(DISTINCT member_id))
FROM orders WHERE product_id=${PRODUCT_ID};
")

MIN_WINNER=$(echo "$WINNER_SPREAD" | cut -d',' -f1)
MAX_WINNER=$(echo "$WINNER_SPREAD" | cut -d',' -f2)
DISTINCT_WINNERS=$(echo "$WINNER_SPREAD" | cut -d',' -f3)

FAIL=0

if [[ "$ORDER_COUNT" != "$TOTAL_STOCK" ]]; then
  echo "FAIL: PENDING 주문 수 = ${ORDER_COUNT} (기대 ${TOTAL_STOCK})"
  FAIL=1
else
  echo "OK: PENDING 주문 수 = ${ORDER_COUNT}"
fi

if [[ "$DB_STOCK" != "0" ]]; then
  echo "FAIL: DB remaining_stock = ${DB_STOCK} (기대 0)"
  FAIL=1
else
  echo "OK: DB remaining_stock = 0"
fi

if [[ "$REDIS_STOCK" != "0" ]]; then
  echo "FAIL: Redis stock = ${REDIS_STOCK} (기대 0)"
  FAIL=1
else
  echo "OK: Redis stock = 0"
fi

if [[ "$DISTINCT_WINNERS" != "$TOTAL_STOCK" ]]; then
  echo "FAIL: 당첨 회원 중복 — distinct winners = ${DISTINCT_WINNERS}"
  FAIL=1
else
  echo "OK: 당첨 회원 ${DISTINCT_WINNERS}명 (중복 없음)"
fi

if [[ "$DLT_COUNT" != "0" ]]; then
  echo "WARN: booking_dead_letter = ${DLT_COUNT} (일시 장애 시 발생 가능)"
else
  echo "OK: booking_dead_letter = 0"
fi

echo "==> 공정성: 당첨 memberId 범위 ${MIN_WINNER} ~ ${MAX_WINNER} (무작위 풀에서 산출)"
if [[ -n "$MIN_WINNER" && -n "$MAX_WINNER" && "$MIN_WINNER" != "NULL" ]]; then
  SPAN=$((MAX_WINNER - MIN_WINNER))
  if [[ "$SPAN" -lt 100 ]]; then
    echo "WARN: 당첨 ID 편중 가능성 (span=${SPAN}). 재실행 시 분산 확인 권장."
  else
    echo "OK: 당첨 ID 분산 span=${SPAN}"
  fi
fi

if [[ "$FAIL" -ne 0 ]]; then
  echo ""
  echo "검증 실패 — 초과판매 또는 미달 판매가 발생했을 수 있습니다."
  exit 1
fi

echo ""
echo "검증 통과 — 재고 정합성 OK (당첨 ${TOTAL_STOCK}건, 초과판매 0건)"
