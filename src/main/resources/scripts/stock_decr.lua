
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
