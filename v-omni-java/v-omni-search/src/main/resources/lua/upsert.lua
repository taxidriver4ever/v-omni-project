-- KEYS[1]: hot_words:global
-- ARGV[1]: 搜索词 (Member)
-- ARGV[2]: 增加的分值 (Score, 比如 4 或 6)

local key = KEYS[1]
local member = ARGV[1]
local score = tonumber(ARGV[2])

-- 检查成员是否存在
local exists = redis.call('ZSCORE', key, member)

if not exists then
    -- 场景：冷启动。直接设为初始分 (4 或 6)
    redis.call('ZADD', key, score, member)
else
    -- 场景：热度累加。在原有基础上增加
    redis.call('ZINCRBY', key, score, member)
end

return true
