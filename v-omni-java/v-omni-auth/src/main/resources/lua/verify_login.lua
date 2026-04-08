-- KEYS[1]: login:email:xxx:code (验证码本身)
-- KEYS[2]: login:email:xxx:attempts (尝试次数计数器)
-- ARGV[1]: input_code (用户输入)
-- ARGV[2]: max_attempts (最大尝试次数，比如 5)

local code_key = KEYS[1]
local attempts_key = KEYS[2]
local input_code = ARGV[1]
local max_attempts = tonumber(ARGV[2])

-- 1. 检查是否超过最大尝试次数
local current_attempts = tonumber(redis.call('GET', attempts_key) or "0")
if current_attempts >= max_attempts then
    return -2 -- 错误次数过多，锁定
end

-- 2. 获取正确的验证码
local saved_code = redis.call('GET', code_key)

if not saved_code then
    return 0 -- 验证码已过期或不存在
end

if input_code == saved_code then
    -- 成功：删除验证码和尝试次数
    redis.call('DEL', code_key)
    redis.call('DEL', attempts_key)
    return 1 -- 校验成功
else
    -- 失败：增加尝试次数，设置过期时间（比如 5 分钟）
    redis.call('INCR', attempts_key)
    redis.call('EXPIRE', attempts_key, 300)
    return -1 -- 验证码错误
end