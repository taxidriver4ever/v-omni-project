#!lua name=auth_statemachine
local function initial_get_pre_signature_strategy(user_id)
    local retry_key = 'pre_sign:retry:times:user_id:' .. user_id
    local ttl = 300
    local max_attempts = 10

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
        return 'PREPARE_PUBLISH_MEDIA'
    end
end

local function prepare_publish_video_start_processing(id)
    return 'PROCESSING'
end

local function processing_finish_extraction(id)
    return 'EXTRACT_FINISH'
end

local function processing_finish_decoding(id)
    return 'DECODE_FINISH'
end

local function extract_finish_finish_decoding(id)
    return 'FINISHED'
end

local function decode_finish_finish_extraction(id)
    return 'FINISHED'
end

-- ==================== 状态表（映射表） ====================
local strategy_map = {
    -- 格式：["当前状态:事件"] = 策略函数
    ["INITIAL:GET_PRE_SIGNATURE"] = initial_get_pre_signature_strategy,
    ['PREPARE_PUBLISH_MEDIA:START_PROCESSING'] = prepare_publish_video_start_processing,
    ['PROCESSING:FINISH_EXTRACTION'] = processing_finish_extraction,
    ['PROCESSING:FINISH_DECODING'] = processing_finish_decoding,
    ['EXTRACT_FINISH:FINISH_DECODING'] = extract_finish_finish_decoding,
    ['DECODE_FINISH:FINISH_EXTRACTION'] = decode_finish_finish_extraction
}


-- ==================== 主处理函数 ====================
-- 参数：
--   user_id (string)
--   event  (string)  事件名称，如 "REGISTER_SEND_CODE"
--   ...    (可选) 额外参数，例如验证码校验时的输入码
-- 返回值：{ success (0/1), new_state, action_code }
--   action_code 用于 Java 侧回调对应的 Action，这里我们用目标状态名 + 后缀
redis.register_function('process_event', function(keys, args)
    local id = args[1]
    local event = args[2]
    local user_id = args[3]  -- 可选，某些策略需要

    local state_key = 'media:state:id:' .. id
    local current_state = redis.call('GET', state_key) or 'INITIAL'
    local event_key = current_state .. ':' .. event
    local state_ttl = 3600

    -- 从映射表中查找策略函数
    local strategy_func = strategy_map[event_key]
    if not strategy_func then
        -- 未定义的状态转移：返回错误标识，Java 侧可据此处理
        return 'ERROR:INVALID_TRANSITION:' .. current_state
    end

    -- 执行策略函数，根据函数是否需要 input_code 传递参数
    -- 简单判断：如果函数名包含 "verify" 则传入 input_code
    local new_state
    if user_id then
        new_state = strategy_func(user_id)
    else
        new_state = strategy_func(id)
    end

    -- 更新状态到 Redis
    if new_state then
        redis.call('SETEX', state_key, state_ttl, new_state)
    else
        new_state = current_state  -- 防御：策略函数应保证非空返回
    end

    return event_key .. ":" .. new_state
end)