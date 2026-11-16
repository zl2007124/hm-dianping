-- 秒杀资格校验脚本：0=成功 1=库存不足 2=重复下单
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
-- 库存 key
local stockKey = 'seckill:stock:' .. voucherId
-- 已下单用户集合 key
local orderKey = 'seckill:order:' .. voucherId

-- 1.判断库存（key 不存在也按库存不足处理，避免 tonumber(false) 报错）
local stock = redis.call('get', stockKey)
if (not stock or tonumber(stock) <= 0) then
    return 1
end

-- 2.判断该用户是否已经下过单
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

-- 3.扣减库存 + 记录用户
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)

redis.call('XADD', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0
