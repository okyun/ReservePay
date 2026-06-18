-- unlock.lua : 토큰이 일치할 때만 락을 해제한다 (다른 스레드의 락을 지우지 않음)
-- KEYS[1] = lock key
-- ARGV[1] = lock token
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
