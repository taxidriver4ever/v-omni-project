-- KEYS[1]: globalKey
-- KEYS[2]: currentKey
-- ARGV: 衰减后的向量列表

-- 1. 清空并重写全局库
redis.call('DEL', KEYS[1])
for i, vector in ipairs(ARGV) do
    redis.call('RPUSH', KEYS[1], vector)
end

-- 2. 设置全局库 7 天过期 (7 * 24 * 60 * 60 = 604800 秒)
redis.call('EXPIRE', KEYS[1], 604800)

-- 3. 清空临时库并设置 3 天过期 (3 * 24 * 60 * 60 = 259200 秒)
redis.call('DEL', KEYS[2])
redis.call('EXPIRE', KEYS[2], 259200)

return true