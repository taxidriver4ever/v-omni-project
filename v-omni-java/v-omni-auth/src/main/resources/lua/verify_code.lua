-- KEYS[1]: user:email:xxx:state
-- KEYS[2]: register:email:xxx:code
-- KEYS[3]: register:email:xxx:attempts
-- ARGV[1]: input_code
-- ARGV[2]: max_attempts (比如 5)
-- ARGV[3]: state_ttl (比如 86400)

local state_key = KEYS[1]
local code_key = KEYS[2]
local attempts_key = KEYS[3]
local input_code = ARGV[1]
local max_attempts = tonumber(ARGV[2])
local state_ttl = tonumber(ARGV[3])

-- 1. 获取当前 state 状态
local current_state = redis.call('GET', state_key)

if not current_state then
    return -2 -- 非法请求：没有 state 记录（可能没发过验证码）
end

if current_state == '1' or current_state == '2' then
    return 1 -- 已经是验证通过状态，无需重复验证
end

-- 2. 检查尝试次数（锁定逻辑）
local current_attempts = tonumber(redis.call('GET', attempts_key) or "0")
if current_attempts >= max_attempts then
    return -4 -- 错误次数过多，锁定
end

-- 3. 开始校验验证码
local saved_code = redis.call('GET', code_key)

if not saved_code then
    return -3 -- 验证码已过期
end

-- 4. 比对验证码
if input_code == saved_code then
    -- 验证通过：更新 state 为 '1' (代表已过验证码校验，但还没填完资料)
    redis.call('SETEX', state_key, state_ttl, '1')

    -- 销毁验证码和尝试计数
    redis.call('DEL', code_key, attempts_key)

    return 2 -- 校验成功
else
    -- 验证码错误：增加尝试次数并续期
    redis.call('INCR', attempts_key)
    redis.call('EXPIRE', attempts_key, 300) -- 5分钟冷静期
    return -1 -- 验证码错误
end