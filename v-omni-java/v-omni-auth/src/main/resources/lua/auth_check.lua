-- KEYS[1]: user:email:xxx:state (String)
-- KEYS[2]: user:email:xxx:retry (String)
-- ARGV[1]: state_ttl (1天, 86400)
-- ARGV[2]: retry_ttl (10秒)

local state_key = KEYS[1]
local retry_key = KEYS[2]
local state_ttl = tonumber(ARGV[1])
local retry_ttl = tonumber(ARGV[2])

-- 1. 尝试获取当前的 state
local current_state = redis.call('GET', state_key)

if not current_state then
    -- 情况 A: 全新用户 (Key 不存在)
    -- 初始化状态为 "0"，并设置过期时间
    redis.call('SETEX', state_key, state_ttl, '0')
    -- 设置冷静期标志位
    redis.call('SETEX', retry_key, retry_ttl, 'true')
    return 1 -- 允许发送
else
    -- 情况 B: 用户已存在
    if current_state ~= '0' then
        return 0 -- 用户状态非0（已激活/锁定等），拦截发送
    end

    -- 情况 C: state 为 "0"，检查冷静期是否还在
    local retry_exists = redis.call('EXISTS', retry_key)

    if retry_exists == 1 then
        return -1 -- 冷静期未过，重发太频繁
    else
        -- 冷静期已过，允许重发
        -- 重新设置冷静期标志
        redis.call('SETEX', retry_key, retry_ttl, 'true')
        -- 顺便给 state 续期，保证注册流程不中断
        redis.call('EXPIRE', state_key, state_ttl)
        return 1
    end
end