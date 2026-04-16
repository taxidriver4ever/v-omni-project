#!lua name=auth_statemachine
local function initial_register_send_code_strategy(user_id)
    local retry_key = 'register:code:retry:times:id:' .. user_id
    local ttl = 300
    local max_attempts = 5

    local current = redis.call('GET', retry_key)
    if current and tonumber(current) >= max_attempts then
        return 'EXCEED_LIMIT'
    end

    local new_retry = redis.call('INCR', retry_key)
    if new_retry == 1 then
        redis.call('EXPIRE', retry_key, ttl)
    end
    return 'PENDING'
end


local function pending_register_send_code_strategy(user_id)
    local retry_key = 'register:code:retry:times:id:' .. user_id
    local ttl = 300
    local max_attempts = 5

    -- 先检查是否已达上限（对应 ExceedLimitGuard）
    local current = redis.call('GET', retry_key)
    if current and tonumber(current) >= max_attempts then
        return 'EXCEED_LIMIT'
    end

    -- 未达上限，原子自增（对应 PendingGuard）
    local new_retry = redis.call('INCR', retry_key)
    if new_retry == 1 then
        redis.call('EXPIRE', retry_key, ttl)
    end

    -- 自增后再次判断（处理并发边界）
    if new_retry > max_attempts then
        return 'EXCEED_LIMIT'
    else
        return 'PENDING'
    end
end

-- 策略：PENDING + REGISTER_VERIFY_CODE
-- 返回值：
--   'VERIFIED'      : 验证码正确，注册成功
--   'EXCEED_LIMIT'  : 验证码错误且重试次数已达上限
--   'PENDING'       : 验证码错误但未超限（状态保持不变，允许继续重试）
local function pending_register_verify_code_strategy(user_id, input_code)
    local code_key = 'register:code:id:' .. user_id
    local retry_key = 'register:retry:times:id:' .. user_id
    local ttl = 300      -- 对应 REGISTER_VERIFY_RETRY_TTL
    local max_attempts = 5   -- 对应 REGISTER_VERIFY_RETRY_TIMES

    -- 1. 检查验证码是否存在
    local correct_code = redis.call('GET', code_key)
    if not correct_code then
        return 'PENDING'   -- 验证码过期，状态不变
    end

    -- 2. 验证码正确
    if input_code == correct_code then
        redis.call('DEL', code_key, retry_key)   -- 清理验证码和重试计数器
        return 'VERIFIED'
    end

    -- 3. 验证码错误，处理重试限流
    local current = redis.call('GET', retry_key)
    if current and tonumber(current) >= max_attempts then
        return 'EXCEED_LIMIT'
    end

    local new_count = redis.call('INCR', retry_key)
    if new_count == 1 then
        redis.call('EXPIRE', retry_key, ttl)
    end

    if new_count > max_attempts then
        return 'EXCEED_LIMIT'
    end

    return 'PENDING'   -- 验证码错误，但未超限，状态不变
end

-- 策略：REGISTERED + LOGIN_SEND_CODE
-- 已注册用户请求登录验证码，检查重试次数，未超限进入 PENDING_LOGIN，超限返回 EXCEED_LIMIT
local function registered_login_send_code_strategy(user_id)
    local retry_key = 'login:code:retry:times:id:' .. user_id
    local ttl = 300      -- 对应 LOGIN_RETRY_TTL
    local max_attempts = 5   -- 对应 LOGIN_RETRY_TIMES

    local current = redis.call('GET', retry_key)
    if current and tonumber(current) >= max_attempts then
        return 'EXCEED_LIMIT'
    end

    local new_retry = redis.call('INCR', retry_key)
    if new_retry == 1 then
        redis.call('EXPIRE', retry_key, ttl)
    end

    if new_retry > max_attempts then
        return 'EXCEED_LIMIT'
    end
    return 'PENDING_LOGIN'
end

-- 策略：PENDING_LOGIN + LOGIN_SEND_CODE
-- 在 PENDING_LOGIN 状态下再次请求发送登录验证码，检查重试次数并决定下一状态
local function pending_login_send_code_strategy(user_id)
    local retry_key = 'login:code:retry:times:id:' .. user_id
    local ttl = 300        -- LOGIN_RETRY_TTL
    local max_attempts = 5 -- LOGIN_RETRY_TIMES（注意：你的 Guard 中使用了 LOGIN_VERIFY_RETRY_TIMES，此处应统一，建议使用 LOGIN_RETRY_TIMES）

    local current = redis.call('GET', retry_key)
    if current and tonumber(current) >= max_attempts then
        return 'EXCEED_LIMIT'
    end

    local new_retry = redis.call('INCR', retry_key)
    if new_retry == 1 then
        redis.call('EXPIRE', retry_key, ttl)
    end

    if new_retry > max_attempts then
        return 'EXCEED_LIMIT'
    end
    return 'PENDING_LOGIN'
end

-- 策略：PENDING_LOGIN + LOGIN_VERIFY_CODE
-- 返回值：
--   'LOGGED_IN'    : 验证码正确，登录成功
--   'EXCEED_LIMIT' : 验证码错误且重试次数已达上限
--   'PENDING_LOGIN': 验证码错误但未超限（状态保持不变，允许继续重试）
local function pending_login_verify_code_strategy(user_id, input_code)
    local code_key = 'login:code:id:' .. user_id
    local retry_key = 'login:retry:times:id:' .. user_id
    local ttl = 300              -- LOGIN_VERIFY_RETRY_TTL
    local max_attempts = 5       -- LOGIN_VERIFY_RETRY_TIMES

    -- 1. 检查验证码是否存在
    local correct_code = redis.call('GET', code_key)
    if not correct_code then
        return 'PENDING_LOGIN'   -- 验证码过期，状态不变
    end

    -- 2. 验证码正确
    if input_code == correct_code then
        redis.call('DEL', code_key, retry_key)   -- 清理验证码和重试计数器
        return 'LOGGED_IN'
    end

    -- 3. 验证码错误，处理重试限流
    local current = redis.call('GET', retry_key)
    if current and tonumber(current) >= max_attempts then
        return 'EXCEED_LIMIT'
    end

    local new_count = redis.call('INCR', retry_key)
    if new_count == 1 then
        redis.call('EXPIRE', retry_key, ttl)
    end

    if new_count > max_attempts then
        return 'EXCEED_LIMIT'
    end

    return 'PENDING_LOGIN'   -- 验证码错误，但未超限，状态不变
end

-- ==================== 状态表（映射表） ====================
local strategy_map = {
    -- 格式：["当前状态:事件"] = 策略函数
    ["INITIAL:REGISTER_SEND_CODE"]      = initial_register_send_code_strategy,
    ["PENDING:REGISTER_SEND_CODE"]      = pending_register_send_code_strategy,
    ["PENDING:REGISTER_VERIFY_CODE"]    = pending_register_verify_code_strategy,
    ["REGISTERED:LOGIN_SEND_CODE"]      = registered_login_send_code_strategy,
    ["PENDING_LOGIN:LOGIN_SEND_CODE"]   = pending_login_send_code_strategy,
    ["PENDING_LOGIN:LOGIN_VERIFY_CODE"] = pending_login_verify_code_strategy,
}


-- ==================== 主处理函数 ====================
-- 参数：
--   user_id (string)
--   event  (string)  事件名称，如 "REGISTER_SEND_CODE"
--   ...    (可选) 额外参数，例如验证码校验时的输入码
-- 返回值：{ success (0/1), new_state, action_code }
--   action_code 用于 Java 侧回调对应的 Action，这里我们用目标状态名 + 后缀
redis.register_function('process_event', function(keys, args)
    local user_id = args[1]
    local event = args[2]
    local input_code = args[3]  -- 可选，某些策略需要

    local state_key = 'auth:state:id:' .. user_id
    local current_state = redis.call('GET', state_key) or 'INITIAL'
    local event_key = current_state .. ':' .. event
    local state_ttl = 300

    -- 从映射表中查找策略函数
    local strategy_func = strategy_map[event_key]
    if not strategy_func then
        -- 未定义的状态转移：返回错误标识，Java 侧可据此处理
        return { 'ERROR:INVALID_TRANSITION', current_state }
    end

    -- 执行策略函数，根据函数是否需要 input_code 传递参数
    -- 简单判断：如果函数名包含 "verify" 则传入 input_code
    local new_state
    if string.find(event_key, 'VERIFY') then
        new_state = strategy_func(user_id, input_code)
    else
        new_state = strategy_func(user_id)
    end

    -- 更新状态到 Redis
    if new_state then
        redis.call('SETEX', state_key, state_ttl, new_state)
    else
        new_state = current_state  -- 防御：策略函数应保证非空返回
    end

    return { event_key, new_state }
end)