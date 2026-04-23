-- Key 1: user_actions_key (例如 user:actions:1001)
-- Arg 1: video_id (视频ID)
-- Arg 2: action_type (操作类型: like, collect, click 等)
-- Arg 3: max_window_size (窗口大小: 15)

local user_actions_key = KEYS[1]
local video_id = ARGV[1]
local action_type = ARGV[2]
local max_size = tonumber(ARGV[3])

-- 1. 构造存储字符串，格式为 "video_id:action_type"
-- 也可以用 JSON，但这种格式解析起来最快
local action_data = video_id .. ":" .. action_type

-- 2. 推入列表左侧（头部）
redis.call("LPUSH", user_actions_key, action_data)

-- 3. 裁剪列表，保留最新的 max_size 条记录
-- 索引从 0 开始，所以是 0 到 max_size - 1
redis.call("LTRIM", user_actions_key, 0, max_size - 1)

return redis.call("LLEN", user_actions_key)