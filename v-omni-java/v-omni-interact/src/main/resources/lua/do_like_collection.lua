--[[
  KEYS[1]: 视频详情 Hash (interact:media:info:{media_id})
  KEYS[2]: 视频点赞 Set (interact:like:set:media_id:{media_id})
  KEYS[3]: 用户点赞历史 ZSet (interact:user:like:zset:{user_id})

  ARGV[1]: 用户 id
  ARGV[2]: Hash 计数命 (likes_count)
  ARGV[3]: 当前时间戳 (score)
  ARGV[4]: 视频 id (member)
]]

-- 1. 判定点赞关系 Set 是否存在
local setExists = redis.call('EXISTS', KEYS[2])
if setExists == 0 then
    return -1 -- 缓存失效，回源 MySQL
end

-- 2. 判定用户是否已点赞
local hasDone = redis.call('SISMEMBER', KEYS[2], ARGV[1])

if hasDone == 0 then
    -- 3. 更新视频详情计数
    if redis.call('EXISTS', KEYS[1]) == 1 then
        redis.call('HINCRBY', KEYS[1], ARGV[2], 1)
        redis.call('EXPIRE', KEYS[1], 86400)
    end

    -- 4. 更新视频的点赞 Set（记录谁点过）
    redis.call('SADD', KEYS[2], ARGV[1])

    -- 5. 更新用户的点赞历史 ZSet（滑动窗口）
    redis.call('ZADD', KEYS[3], ARGV[3], ARGV[4])

    -- 6. 裁减 ZSet，只保留最新的 30 条数据
    -- 移除排名在 0 到 -31 之间的元素（即保留最大的 30 个 score）
    redis.call('ZREMRANGEBYRANK', KEYS[3], 0, -31)
    redis.call('EXPIRE', KEYS[3], 604800) -- 604800 秒 = 7 天

    return 1 -- 点赞成功
else
    return 0 -- 已经点过了
end