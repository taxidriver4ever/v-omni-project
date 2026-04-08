-- KEYS[1]: user:email:xxx:retry (String)
-- ARGV[1]: retry_ttl (10秒)

local retry_key = KEYS[1]
local retry_ttl = tonumber(ARGV[1])

local retry_exists = redis.call('EXISTS', retry_key)

if retry_exists == 1 then
    return -1 -- 冷静期未过，重发太频繁
else
    -- 冷静期已过，允许重发
    -- 重新设置冷静期标志
    redis.call('SETEX', retry_key, retry_ttl, 'true')
    return 1
end