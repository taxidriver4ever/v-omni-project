-- KEYS[1]: 建议库名字 (v-omni:search:suggest)
-- ARGV[1]: 词项 (term)
-- ARGV[2]: 分数 (score)
return redis.call('FT.SUGADD', KEYS[1], ARGV[1], ARGV[2], 'INCR')