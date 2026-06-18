-- stock_decr.lua
-- Checkout 1차 방어선: 재고 확인 → 차감 → 1인 1예약 등록을 단일 Lua 스크립트로 원자 수행한다.
--
-- Redis는 단일 명령은 원자적이지만, GET·DECR·SADD를 나눠 실행하면 그 사이 다른 요청이
-- 끼어들 수 있다. Lua 스크립트는 실행 전체가 원자 단위이므로 선착순 경쟁에서 초과판매를
-- 막는다. 통과한 요청만 DB 백스톱(StockRepository.decreaseIfAvailable)으로 내려간다.
--
-- 키·인자
--   KEYS[1] = stock:{productId}    — 남은 재고 수 (정수 문자열, StockBootstrapRunner가 DB와 동기화)
--   KEYS[2] = reserved:{productId} — 해당 상품을 이미 예약한 memberId 집합 (SET)
--   ARGV[1] = memberId             — 예약 시도 회원 ID (문자열)
--
-- 처리 순서 (의도적으로 이 순서를 지킨다)
--   1) 1인 1예약 검사 — 이미 reserved에 있으면 재고와 무관하게 거절
--   2) 재고 키 존재 여부 — nil이면 상품 미등록/미동기화
--   3) 재고 0 이하 — 매진
--   4) DECR + SADD — 차감과 당첨자 기록을 한 번에 확정
--
-- 반환값 (StockGate.reserve()가 해석)
--   양수  — 성공. 차감 후 남은 재고 수 (예: 10 → 9 반환)
--   -1    — 매진 (stock <= 0)           → SoldOutException
--   -2    — stock 키 없음               → ProductNotFoundException
--   -3    — 동일 회원 중복 예약          → DuplicateReservationException
--
-- 보상: 결제 실패 시 StockGate.release()가 SREM(reserved) + INCR(stock)으로 롤백한다.

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -3
end

local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil then
    return -2
end
if stock <= 0 then
    return -1
end

redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return stock - 1
