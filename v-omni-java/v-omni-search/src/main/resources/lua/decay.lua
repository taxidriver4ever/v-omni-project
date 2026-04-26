-- 获取 key
local key = KEYS[1]
-- 获取衰减系数
local factor = ARGV[1]
-- 获取剔除阈值
local threshold = ARGV[2]

-- 1. 获取所有成员
local members = redis.call('ZRANGE', key, 0, -1, 'WITHSCORES')

for i = 1, #members, 2 do
    local member = members[i]
    local score = tonumber(members[i+1])
    local new_score = score * factor

    if new_score < tonumber(threshold) then
        -- 2. 分数太低，直接踢掉
        redis.call('ZREM', key, member)
    else
        -- 3. 更新分数
        redis.call('ZADD', key, new_score, member)
    end
end
return true
