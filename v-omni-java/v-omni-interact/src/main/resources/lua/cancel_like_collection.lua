--[[
  KEYS[1]: 视频详情 Hash Key (interact:media:info:{media_id})
  KEYS[2]: 用户点赞状态 Set Key (interact:like:set:media_id:{media_id})
  ARGV[1]: 用户id
  ARGV[2]: Hash 中的字段名 (通常为 "likes_count")
]]

-- 1. 判定点赞关系 Set 是否存在
-- 这是为了保证“用户是否点赞过”这一判定的准确性
local setExists = redis.call('EXISTS', KEYS[2])

if setExists == 0 then
    return -1 -- 告知 Java：关系缓存已过期，需回源 MySQL
end

-- 2. 判定用户是否在 Set 中
local hasDone = redis.call('SISMEMBER', KEYS[2], ARGV[1])

if hasDone == 1 then
    -- 更新计数
    if redis.call('EXISTS', KEYS[1]) == 1 then
        local current = tonumber(redis.call('HGET', KEYS[1], ARGV[2]) or "0")
        if current > 0 then
            redis.call('HINCRBY', KEYS[1], ARGV[2], -1)
            redis.call('EXPIRE', KEYS[1], 86400) -- 详情 Hash 续期 24h
        end
    end
    -- 更新关系：从 Set 移除
    redis.call('SREM', KEYS[2], ARGV[1])
    return 1 -- 成功
else
    return 0 -- 本就没点过
end