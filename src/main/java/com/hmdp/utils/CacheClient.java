package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * 缓存工具类：把「写缓存 / 空值防穿透 / 逻辑过期防击穿」三套模板写一次，业务侧直接调用
 */
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 把对象序列化成 JSON 写进 Redis，并设置超时时间
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 把对象包一层 RedisData（带逻辑过期时间）写进 Redis，不设真实 TTL
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 查缓存 + 空值缓存，解决缓存穿透
     *
     * @param keyPrefix  缓存 key 前缀，如 CACHE_SHOP_KEY
     * @param id         业务 id
     * @param type       返回的数据类型，如 Shop.class
     * @param dbFallback 缓存未命中时怎么查库，如 this::getById
     * @param time       超时时间
     * @param unit       超时时间单位
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                          Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.查缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //1.1 命中真实数据
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        //1.2 命中空值缓存
        if (json != null) {
            return null;
        }
        //2.查库
        R r = dbFallback.apply(id);
        //2.1 库中不存在：写空值，短 TTL 防穿透
        if (r == null) {
            stringRedisTemplate.opsForValue()
                    .set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //2.2 库中存在：写缓存
        this.set(key, r, time, unit);
        return r;
    }

    /**
     * 查缓存 + 逻辑过期 + 互斥异步重建，解决缓存击穿
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type,
                                            Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.查缓存（逻辑过期方案要求提前预热，查不到直接返回 null）
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return null;
        }
        //2.反序列化：先取 RedisData，再取真实对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        //3.逻辑时间还没到：直接返回
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return r;
        }
        //4.已过期：抢锁成功就开线程异步重建，抢不到也不等
        String lockKey = LOCK_SHOP_KEY + id;
        if (tryLock(lockKey)) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R newValue = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, newValue, time, unit);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        //5.无论重建是否完成，先返回旧数据
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
