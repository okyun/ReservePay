-- stock_decr.lua : 재고 확인 + 차감 + 당첨자 기록을 원자적으로 수행
-- KEYS[1] = stock:{productId}
-- KEYS[2] = reserved:{productId}
-- ARGV[1] = memberId
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -3 end  -- 1인 1예약
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil then return -2 end
if stock <= 0 then return -1 end
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return stock - 1
