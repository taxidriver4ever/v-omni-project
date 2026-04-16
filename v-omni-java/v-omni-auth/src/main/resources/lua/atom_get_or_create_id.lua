-- KEYS[1]: emailToIdKey  (例如 "auth:id:email:xxx@xx.com")
-- KEYS[2]: stateKey      (例如 "auth:id:state:123456")
-- ARGV[1]: newId         (Java端生成的雪花ID)
-- ARGV[2]: emailTtl      (邮箱映射过期时间，秒)
-- ARGV[3]: stateTtl      (状态过期时间，秒)

local emailKey = KEYS[1]
local stateKey = KEYS[2]
local newId = ARGV[1]
local emailTtl = tonumber(ARGV[2])
local stateTtl = tonumber(ARGV[3])

-- 1. 检查邮箱是否已关联ID
local existingId = redis.call('GET', emailKey)

if existingId then
    redis.call('EXPIRE', emailKey, emailTtl)
    return existingId  -- 已存在，直接返回已有ID
end

-- 2. 不存在则原子性地写入两个Key
redis.call('SETEX', stateKey, stateTtl, 'INITIAL')
redis.call('SETEX', emailKey, emailTtl, newId)

-- 3. 返回新生成的ID
return newId