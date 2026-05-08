--[[
  KEYS[1]: 视频详情 Hash Key (interact:media:info:{media_id})
  KEYS[2]: 视频点赞状态 Set Key (interact:like:set:media_id:{media_id})
  KEYS[3]: 用户点赞历史 ZSet Key (interact:user:like:zset:{user_id})

  ARGV[1]: 用户 id
  ARGV[2]: Hash 中的字段名 (通常为 "likes_count")
  ARGV[3]: (预留参数，此处无需时间戳)
  ARGV[4]: 视频 id (用于从 ZSet 中移除)
]]

-- 1. 判定点赞关系 Set 是否存在（保证缓存一致性）
local setExists = redis.call('EXISTS', KEYS[2])
if setExists == 0 then
    return -1 -- 告知 Java：关系缓存已过期，需回源 MySQL
end

-- 2. 判定用户是否在 Set 中
local hasDone = redis.call('SISMEMBER', KEYS[2], ARGV[1])

if hasDone == 1 then
    -- 3. 更新视频详情计数
    if redis.call('EXISTS', KEYS[1]) == 1 then
        local current = tonumber(redis.call('HGET', KEYS[1], ARGV[2]) or "0")
        if current > 0 then
            redis.call('HINCRBY', KEYS[1], ARGV[2], -1)
            -- 详情 Hash 续期 24h
            redis.call('EXPIRE', KEYS[1], 86400)
        end
    end

    -- 4. 从视频的点赞 Set 中移除该用户
    redis.call('SREM', KEYS[2], ARGV[1])

    -- 5. 从用户的点赞历史 ZSet 中移除该视频
    -- 这一步很关键，保证了用户查看“历史点赞”时不会刷出已取消的视频
    redis.call('ZREM', KEYS[3], ARGV[4])
    redis.call('EXPIRE', KEYS[3], 604800) -- 604800 秒 = 7 天

    return 1 -- 取消点赞成功
else
    return 0 -- 用户本来就没点过赞
end