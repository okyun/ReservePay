#!/usr/bin/env bash
# Checkout 부하 테스트 전 Redis·MySQL 상태 초기화
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
  elif docker compose ps redis >/dev/null 2>&1; then
    docker compose exec -T redis redis-cli "$@"
  else
    echo "redis-cli를 찾을 수 없습니다. Redis가 실행 중인지 확인하세요." >&2
    exit 1
  fi
}

mysql_cmd() {
  local args=(-h"$MYSQL_HOST" -u"$MYSQL_USER" -p"$MYSQL_PASS" "$MYSQL_DB")
  if mysql "${args[@]}" -e "SELECT 1" >/dev/null 2>&1; then
    mysql "${args[@]}" "$@"
  elif docker compose ps mysql >/dev/null 2>&1; then
    docker compose exec -T mysql mysql -u"$MYSQL_USER" -p"$MYSQL_PASS" "$MYSQL_DB" "$@"
  else
    echo "mysql 클라이언트를 찾을 수 없습니다. MySQL이 실행 중인지 확인하세요." >&2
    exit 1
  fi
}

echo "==> Redis 재고·예약·상품 캐시 초기화 (productId=${PRODUCT_ID})"
redis_cmd DEL "stock:${PRODUCT_ID}" "reserved:${PRODUCT_ID}" \
  "product:${PRODUCT_ID}:opening_at" "product:${PRODUCT_ID}:price" >/dev/null || true
redis_cmd SET "stock:${PRODUCT_ID}" "$TOTAL_STOCK" >/dev/null

echo "==> MySQL 주문·재고·오픈 시각 초기화"
mysql_cmd -e "
DELETE pl FROM payment_line pl
  INNER JOIN payment p ON pl.payment_id = p.id
  INNER JOIN orders o ON p.order_id = o.id
  WHERE o.product_id = ${PRODUCT_ID};
DELETE p FROM payment p
  INNER JOIN orders o ON p.order_id = o.id
  WHERE o.product_id = ${PRODUCT_ID};
DELETE ph FROM point_history ph
  INNER JOIN orders o ON ph.order_id = o.id
  WHERE o.product_id = ${PRODUCT_ID};
DELETE FROM payment_dead_letter;
DELETE FROM booking_dead_letter;
DELETE FROM orders WHERE product_id = ${PRODUCT_ID};
UPDATE stock SET remaining_stock = ${TOTAL_STOCK} WHERE product_id = ${PRODUCT_ID};
UPDATE product SET checkin_opening_at = NOW() WHERE id = ${PRODUCT_ID};
"

echo "==> 초기화 완료 (재고 ${TOTAL_STOCK}, 판매 오픈=NOW)"
