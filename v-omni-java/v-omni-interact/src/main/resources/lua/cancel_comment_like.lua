--[[
  KEYS[1]: 用户点赞状态 Set Key (interact:comment_like:set:comment_id:{comment_id})
  ARGV[1]: 用户id
]]

-- 1. 判定点赞关系 Set 是否存在
-- 这是为了保证“用户是否点赞过”这一判定的准确性
local setExists = redis.call('EXISTS', KEYS[1])

if setExists == 0 then
    return -1 -- 告知 Java：关系缓存已过期，需回源 MySQL
end

-- 2. 判定用户是否在 Set 中
local hasDone = redis.call('SISMEMBER', KEYS[1], ARGV[1])

if hasDone == 1 then
    redis.call('SREM', KEYS[1], ARGV[1])
    return 1 -- 成功
else
    return 0 -- 重复点赞
end