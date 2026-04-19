--[[
  KEYS[1]: 视频位图 Key (media:like:bitmap:{media_id})
  KEYS[2]: 视频统计 Hash Key (media:counts)

  ARGV[1]: 用户ID (映射后的 offset)
  ARGV[2]: 视频ID (Hash 的 field)
  ARGV[3]: 操作类型 ("1" 为点赞, "0" 为取消)
]]

local offset = tonumber(ARGV[1])
local mediaId = ARGV[2]
local action = ARGV[3]

local currentStatus = redis.call('GETBIT', KEYS[1], offset)

if action == "1" then
    if currentStatus == 0 then
        redis.call('SETBIT', KEYS[1], offset, 1)
        redis.call('HINCRBY', KEYS[2], mediaId, 1)
        return 1 -- 允许写入 ES
    else
        return 0 -- 重复点赞，拦截
    end
else
    if currentStatus == 1 then
        redis.call('SETBIT', KEYS[1], offset, 0)
        redis.call('HINCRBY', KEYS[2], mediaId, -1)
        return 1 -- 允许写入 ES (逻辑删除)
    else
        return 0 -- 未点赞，拦截
    end
end
