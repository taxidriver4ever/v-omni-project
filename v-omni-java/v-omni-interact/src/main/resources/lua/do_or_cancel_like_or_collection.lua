--[[
  KEYS[1]: 视频总计数器 Key (interact:counts:media_id:{media_id})
  KEYS[2]: 用户点赞状态 Key (interact:like:set:media_id:{media_id})
  ARGV[1]: 用户id
  ARGV[2]: 操作类型 ("1" 为点赞, "0" 为取消)
]]

-- 1. 判定 Set 是否存在 (回源判定)
local setExists = redis.call('EXISTS', KEYS[2])
if setExists == 0 then
    return -1 -- 返回 -1 给 Java，去查 MySQL 确定关系
end

-- 2. 判定用户是否在 Set 中
local hasLiked = redis.call('SISMEMBER', KEYS[2], ARGV[1])

if ARGV[2] == "1" then
    if hasLiked == 0 then
        -- 核心逻辑：添加关系
        redis.call('SADD', KEYS[2], ARGV[1])
        -- 逻辑：只有计数器 Key 存在，才加 1
        if redis.call('EXISTS', KEYS[1]) == 1 then
            redis.call('INCR', KEYS[1])
            redis.call('EXPIRE', KEYS[1], 86400) -- 续期
        end
        return 1
    else
        return 0 -- 重复点赞
    end
else
    if hasLiked == 1 then
        -- 核心逻辑：移除关系
        redis.call('SREM', KEYS[2], ARGV[1])
        -- 逻辑：只有计数器 Key 存在，才减 1
        if redis.call('EXISTS', KEYS[1]) == 1 then
            local current = tonumber(redis.call('GET', KEYS[1]) or "0")
            if current > 0 then
                redis.call('DECR', KEYS[1])
                redis.call('EXPIRE', KEYS[1], 86400) -- 续期
            end
        end
        return 1
    else
        return 0 -- 本就没点过
    end
end
